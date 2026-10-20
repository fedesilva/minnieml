package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class PartialApplicationElaboratorTests extends BaseEffFunSuite:

  private val addition = "fn add(a: Int, b: Int): Int = a + b;;"

  private def typeCheck(source: String): IO[CompilerState] =

    parseNotFailed(source).map { parsed =>
      val module  = injectCommonFunctions(injectStandardOperators(injectBasicTypes(parsed)))
      val initial = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)

      val phases: List[CompilerState => CompilerState] = List(
        IdAssigner.rewriteModule,
        TypeResolver.rewriteModule,
        ConstructorGenerator.rewriteModule,
        MemoryFunctionGenerator.rewriteModule,
        RefResolver.rewriteModule,
        ExpressionRewriter.rewriteModule,
        Simplifier.rewriteModule,
        CaptureAnalyzer.rewriteModule,
        TypeChecker.rewriteModule
      )

      phases.foldLeft(initial) { (state, phase) =>
        ResolvablesIndexer.rewriteModule(phase(state))
      }
    }

  private def member(state: CompilerState, name: String): Bnd =
    state.module.members
      .collectFirst {
        case binding: Bnd if binding.name == name => binding
      }
      .getOrElse(fail(s"Missing member $name"))

  private def partials(term: Term): List[Lambda] =
    TermTraversal.collect(term) {
      case lambda: Lambda if lambda.meta.exists(_.isPartialApplication) => lambda
    }

  private def assertStable(typed: CompilerState, elaborated: CompilerState): Unit =

    val definitions = ResolvablesIndexer.definitions(elaborated.module)
    val ids         = definitions.flatMap(_.id)
    assertEquals(ids.size, definitions.size)
    assertEquals(ids.distinct.size, ids.size)
    assert(ids.forall(elaborated.bindingIds.allocated.contains))
    definitions.foreach {
      case definition: ResolvableType =>
        assertEquals(
          definition.id.flatMap(elaborated.module.resolvables.lookupType),
          Some(definition)
        )
      case definition =>
        assertEquals(definition.id.flatMap(elaborated.module.resolvables.lookup), Some(definition))
    }

    val originalParams =
      typed.module.members.collect { case binding: Bnd => binding }.flatMap { binding =>
        TermTraversal.collect(binding.value) { case lambda: Lambda => lambda.params }.flatten
      }
    originalParams.foreach { parameter =>
      assertEquals(parameter.id.flatMap(elaborated.module.resolvables.lookup), Some(parameter))
    }

    val repeated = PartialApplicationElaborator.rewriteModule(elaborated)
    assertEquals(repeated.module.members, elaborated.module.members)
    assertEquals(repeated.bindingIds.allocated, elaborated.bindingIds.allocated)

  test("lambda-headed application chains elaborate their inner partial application") {
    typeCheck(s"""
      $addition
      fn example(): Int = { a: Int -> add a; } 1 2;;
      fn control(): Int -> Int = { a: Int -> add a; } 1;;
    """).map { typed =>
      assert(!typed.hasErrors, typed.errors)
      val elaborated = PartialApplicationElaborator.rewriteModule(typed)
      assert(!elaborated.hasErrors, elaborated.errors)
      assertStable(typed, elaborated)
      List("example", "control").foreach { name =>
        val sourceParams = TermTraversal
          .collect(member(typed, name).value) { case lambda: Lambda =>
            lambda.params
          }
          .flatten
        val captured  = sourceParams.find(_.name == "a").getOrElse(fail("Missing source parameter"))
        val generated = partials(member(elaborated, name).value)
        assertEquals(generated.size, 1, name)
        val residual = generated.head
        val originalPartial = TermTraversal.collect(member(typed, name).value) {
          case app: App if app.arg.terms.exists {
                case ref: Ref => ref.resolvedId == captured.id
                case _ => false
              } =>
            app
        }
        assertEquals(originalPartial.size, 1)
        assertEquals(residual.typeSpec, originalPartial.head.typeSpec)
        assertEquals(residual.captures.flatMap(_.ref.resolvedId), captured.id.toList)
        assertEquals(residual.params.size, 1)
        residual.typeSpec match
          case Some(signature: TypeFn) =>
            assertEquals(signature.paramTypes.toList, residual.params.flatMap(_.typeSpec))
            assertEquals(Some(signature.returnType), residual.body.typeSpec)
          case other => fail(s"Expected residual function type, found $other")
      }
    }
  }

  test("nested lambda-headed chains keep partial applications in their lexical bodies") {
    typeCheck(s"""
      $addition
      fn example(): Int =
        { a: Int ->
          let inner = { b: Int -> add b; } a 2;
          add inner;
        } 1 3;
      ;
    """).map { typed =>
      assert(!typed.hasErrors, typed.errors)
      val elaborated = PartialApplicationElaborator.rewriteModule(typed)
      assertStable(typed, elaborated)
      val body = member(elaborated, "example").value
      assertEquals(partials(body).size, 2)
      val sourceLambdas = TermTraversal.collect(member(typed, "example").value) {
        case lambda: Lambda if lambda.params.nonEmpty => lambda
      }
      sourceLambdas.foreach { original =>
        val rewritten = TermTraversal.collect(body) {
          case lambda: Lambda if lambda.params == original.params => lambda
        }
        assertEquals(rewritten.size, 1)
        assert(partials(rewritten.head.body).nonEmpty)
        assertEquals(rewritten.head.source, original.source)
        assertEquals(rewritten.head.typeAsc, original.typeAsc)
        assertEquals(rewritten.head.typeSpec, original.typeSpec)
      }
    }
  }

  private val holder = """
    struct Holder { f: Int -> Int -> Int };
    fn pack(~f: Int -> Int, n: Int): Holder = let result = f n; Holder add;;
  """

  List(
    "direct" -> "({ a: Int -> pack (add a); } 1 2)",
    "conditional" -> "(if flag then { a: Int -> pack (add a); } 1 2; else Holder add;)"
  ).foreach { (label, qualifier) =>
    test(s"$label callee qualifier elaborates nested PAPs and retains its owner") {
      typeCheck(s"""
        $addition
        $holder
        fn example(flag: Bool): Int = $qualifier.f 3 4;;
      """).map { typed =>
        assert(!typed.hasErrors, typed.errors)
        val elaborated = PartialApplicationElaborator.rewriteModule(typed)
        assertStable(typed, elaborated)
        val body = member(elaborated, "example").value
        assertEquals(partials(body).size, 2)
        val selections = TermTraversal.collect(body) {
          case ref: Ref if ref.qualifier.nonEmpty => ref
        }
        assertEquals(selections.size, 1)
        selections.head.qualifier match
          case Some(owner: Ref) =>
            assert(owner.resolvedId.flatMap(elaborated.module.resolvables.lookup).nonEmpty)
          case other => fail(s"Expected a bound qualifier owner, found $other")
        if label == "conditional" then
          val branches = TermTraversal.collect(body) { case cond: Cond => cond }
          assertEquals(branches.size, 1)
          assertEquals(partials(branches.head.ifTrue).size, 2)
          assertEquals(partials(branches.head.ifFalse).size, 0)
      }
    }
  }

  List(
    "named" -> "add 1 2",
    "literal" -> "{ a: Int, b: Int -> a + b; } 1 2",
    "immediate binding" -> "let a = 1; add a 2",
    "nullary" -> "{ add 1 2; } ()"
  ).foreach { (label, body) =>
    test(s"fully applied $label calls do not introduce PAPs") {
      typeCheck(s"$addition fn example(): Int = $body;;").map { typed =>
        assert(!typed.hasErrors, typed.errors)
        val elaborated = PartialApplicationElaborator.rewriteModule(typed)
        assertEquals(partials(member(elaborated, "example").value), Nil)
        assertStable(typed, elaborated)
      }
    }
  }

  test("callee traversal preserves source parameter contracts and application annotations") {
    typeCheck("""
      fn take(~text: String, n: Int): Int = text.length + n;;
      fn example(): Int = { ~text: String -> take text; } (int_to_str 123) 2;;
    """).map { typed =>
      assert(!typed.hasErrors, typed.errors)
      val elaborated = PartialApplicationElaborator.rewriteModule(typed)
      assertStable(typed, elaborated)
      val before = member(typed, "example").value
      val after  = member(elaborated, "example").value
      val sourceLambda = TermTraversal
        .collect(before) {
          case lambda: Lambda if lambda.params.exists(_.consuming) => lambda
        }
        .head
      val rewritten = TermTraversal
        .collect(after) {
          case lambda: Lambda if lambda.params == sourceLambda.params => lambda
        }
        .head
      assertEquals(rewritten.copy(body = sourceLambda.body), sourceLambda)
      val residual = partials(rewritten.body)
      assertEquals(residual.size, 1)
      assertEquals(
        residual.head.meta.map(_.transferredCaptures),
        Some(sourceLambda.params.flatMap(_.id).toSet)
      )
      val oldCalls = TermTraversal.collect(before) { case app: App => app }
      val newCalls = TermTraversal.collect(after) { case app: App => app }
      oldCalls.filter(_.fn.isInstanceOf[App]).foreach { original =>
        val retained = newCalls.filter(_.source == original.source)
        assert(retained.exists { app =>
          app.arg == original.arg && app.typeSpec == original.typeSpec &&
          app.typeAsc == original.typeAsc
        })
      }
    }
  }

  List(
    ("unrelated member", "fn bad(): Int = true;;", "{ a: Int -> add a; } 1 2", 1),
    ("invalid outer argument", "", "{ a: Int -> add a; } 1 true", 1),
    ("invalid inner PAP", "", "{ a: Int -> add true; } 1 2", 0)
  ).foreach { (label, extra, expression, expected) =>
    test(s"callee traversal preserves local recovery with $label") {
      typeCheck(s"$addition $extra fn example(): Int = $expression;;").map { typed =>
        assert(typed.hasErrors)
        val elaborated = PartialApplicationElaborator.rewriteModule(typed)
        assertEquals(elaborated.errors, typed.errors)
        assertEquals(partials(member(elaborated, "example").value).size, expected)
        assertStable(typed, elaborated)
      }
    }
  }

  test("an invalid callee body retains independent ownership diagnostics") {
    semState("""
      fn take(~text: String, n: Int): Int = text.length + n;;
      fn example(): Int =
        let owned = take (int_to_str 123);
        let first = owned 1;
        { a: Int ->
          let second = owned 2;
          take true;
        } 1 2;
      ;
    """).map { result =>
      assertEquals(
        result.errors.count {
          case SemanticError.TypeCheckingError(_: TypeError.TypeMismatch) => true
          case _ => false
        },
        1,
        result.errors
      )
      assertEquals(
        result.errors.count(_.isInstanceOf[SemanticError.UseAfterMove]),
        1,
        result.errors
      )
      assertEquals(result.errors.size, 2, result.errors)
    }
  }
