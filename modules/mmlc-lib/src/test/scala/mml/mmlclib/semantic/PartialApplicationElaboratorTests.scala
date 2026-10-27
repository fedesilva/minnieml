package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.test.BaseEffFunSuite

class PartialApplicationElaboratorTests extends BaseEffFunSuite:

  test("partial application retains the annotation on its supplied call boundary") {
    val source = """
      fn add(first: Int, second: Int): Int = first + second;;
      let partial = ((add): Int -> Int -> Int) 1;
    """
    typeCheck(source).map { typed =>
      assertEquals(typed.errors, Vector.empty)
      val original = member(typed, "partial").value.terms match
        case (app: App) :: Nil => app
        case other => fail(s"Expected one supplied application, got $other")
      assertEquals(original.calleeAscriptions.size, 1)

      val elaborated = PartialApplicationElaborator.rewriteModule(typed)
      val functions  = partials(member(elaborated, "partial").value)
      assertEquals(functions.size, 1)
      val outer = functions.head.body.terms match
        case (app: App) :: Nil => app
        case other => fail(s"Expected the forwarding call, got $other")
      val supplied = outer.fn match
        case app: App => app
        case other => fail(s"Expected the original supplied call boundary, got $other")

      assertEquals(supplied.calleeAscriptions, original.calleeAscriptions)
      assertEquals(supplied.source, original.source)
      assertEquals(outer.calleeAscriptions, Nil)
      assertEquals(elaborated.errors, Vector.empty)
      assertStable(typed, elaborated)
    }
  }

  test("a bare typed function expands with concrete parameters and their ownership") {
    val source = """
      fn take(~text: String, count: Int): Int = text.length + count;;
      let function = take;
    """
    typeCheck(source).map { typed =>
      assertEquals(typed.errors, Vector.empty)
      val target = member(typed, "take")
      val original = member(typed, "function").value.terms match
        case (ref: Ref) :: Nil => ref
        case other => fail(s"Expected a bare typed reference before elaboration, got $other")
      val signature = target.typeSpec match
        case Some(signature: TypeFn) => signature
        case other => fail(s"Expected a concrete function type, got $other")
      assertEquals(original.resolvedId, target.id)
      assertEquals(original.typeSpec, target.typeSpec)

      val elaborated = PartialApplicationElaborator.rewriteModule(typed)
      val function   = forwardingFunction(member(elaborated, "function").value, target)
      assert(!function.meta.exists(_.isPartialApplication))
      assert(function.meta.forall(_.transferredCaptures.isEmpty))
      assertEquals(function.params.size, 2)
      val text  = function.params.head
      val count = function.params(1)

      assertEquals(function.typeSpec, original.typeSpec)
      assertEquals(function.body.typeSpec, Some(signature.returnType))
      assertEquals(text.typeSpec, Some(signature.paramTypes.head))
      assertEquals(count.typeSpec, Some(signature.paramTypes.tail.head))
      assert(text.consuming)
      assert(!count.consuming)
      assertNotEquals(text.id, count.id)
      assertEquals(text.id.flatMap(elaborated.module.resolvables.lookup), Some(text))
      assertEquals(count.id.flatMap(elaborated.module.resolvables.lookup), Some(count))
      assertEquals(function.captures, Nil)

      val application = function.body.terms match
        case (app: App) :: Nil => app
        case other => fail(s"Expected the expanded function call, got $other")
      val (callee, arguments) = CallableValues.application(application)
      assertEquals(callee, original)
      assertEquals(arguments.size, 2)
      assertEquals(
        arguments.head.terms.collect { case ref: Ref => ref.resolvedId },
        List(text.id)
      )
      assertEquals(
        arguments(1).terms.collect { case ref: Ref => ref.resolvedId },
        List(count.id)
      )
      assertEquals(elaborated.errors, Vector.empty)
      assertStable(typed, elaborated)
    }
  }

  test("a bare function returning another function keeps its invocation boundary") {
    val source = """
      fn makeAdder(first: Int): Int -> Int = { second: Int -> first + second; };;
      let function = makeAdder;
    """
    typeCheck(source).map { typed =>
      assertEquals(typed.errors, Vector.empty)
      val target = member(typed, "makeAdder")
      val signature = target.typeSpec match
        case Some(signature: TypeFn) => signature
        case other => fail(s"Expected makeAdder's function type, got $other")
      assertEquals(signature.paramTypes.length, 1)
      assert(signature.returnType.isInstanceOf[TypeFn])

      val elaborated = PartialApplicationElaborator.rewriteModule(typed)
      val function   = forwardingFunction(member(elaborated, "function").value, target)
      assert(!function.meta.exists(_.isPartialApplication))
      assert(function.meta.forall(_.transferredCaptures.isEmpty))
      assertEquals(function.params.size, 1)
      assertEquals(function.typeSpec, target.typeSpec)
      assertEquals(function.body.typeSpec, Some(signature.returnType))
      val applications = TermTraversal.collect(function.body) { case app: App => app }
      assertEquals(applications.size, 1)
      val targets = TermTraversal.collect(function.body) {
        case ref: Ref if ref.resolvedId == target.id => ref
      }
      assertEquals(targets.size, 1)
      assertEquals(elaborated.errors, Vector.empty)
      assertStable(typed, elaborated)
    }
  }

  test("a nullary function value remains a reference until its explicit Unit call") {
    val source = """
      fn answer(): Int = 42;;
      let function = answer;
    """
    typeCheck(source).map { typed =>
      assertEquals(typed.errors, Vector.empty)
      val original = member(typed, "function").value
      val ref = original.terms match
        case (ref: Ref) :: Nil => ref
        case other => fail(s"Expected a nullary function reference, got $other")
      assertEquals(ref.resolvedId, member(typed, "answer").id)

      val elaborated = PartialApplicationElaborator.rewriteModule(typed)

      assertEquals(member(elaborated, "function").value, original)
      assertEquals(partials(member(elaborated, "function").value), Nil)
      assertEquals(elaborated.errors, Vector.empty)
      assertStable(typed, elaborated)
    }
  }

  test("a rejected bare function value retains its reference without materializing a lambda") {
    val source = """
      fn increment(value: Int): Int = value + 1;;
      let rejected: Int = increment;
    """
    typeCheck(source).map { typed =>
      assert(typed.hasErrors)
      val rejected = member(typed, "rejected").value
      val failures = TermTraversal.collect(rejected) { case invalid: InvalidExpression => invalid }
      assertEquals(failures.size, 1)
      val retained = failures.head.originalExpr.terms match
        case (ref: Ref) :: Nil => ref
        case other => fail(s"Expected the rejected function reference, got $other")
      assertEquals(retained.resolvedId, member(typed, "increment").id)

      val elaborated = PartialApplicationElaborator.rewriteModule(typed)

      assertEquals(member(elaborated, "rejected").value, rejected)
      assertEquals(partials(member(elaborated, "rejected").value), Nil)
      assertEquals(elaborated.errors, typed.errors)
      assertStable(typed, elaborated)
    }
  }

  private val addition = "fn add(a: Int, b: Int): Int = a + b;;"

  private def typeCheck(source: String): IO[CompilerState] =
    parseNotFailed(source).map { parsed =>
      val prepared = prepareForTypeChecking(source, parsed)
      ResolvablesIndexer.rewriteModule(TypeChecker.rewriteModule(prepared))
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

  test("returned callables elaborate their own partial application stage") {
    typeCheck(s"""
      $addition
      fn pick(): Int -> Int -> Int = add;;
      fn make(seed: Int): Int -> Int -> Int = ~{ a: Int, b: Int -> seed + a + b; };;
      fn take(~text: String, a: Int, b: Int): Int = text.length + a + b;;
      fn make_once(~text: String): Int -> Int -> Int =
        ~{ a: Int, b: Int -> take text a b; };;
      fn returned(): Int = let q = (pick ()) 10; q 2;;
      fn owned(): Int = let q = (make 45) 1; q 2;;
      fn bound(): Int = let f = pick (); let q = f 10; q 2;;
      fn once(): Int = let q = (make_once (int_to_str 123)) 1; q 2;;
    """).map { typed =>
      assert(!typed.hasErrors, typed.errors)
      val elaborated = PartialApplicationElaborator.rewriteModule(typed)
      assert(!elaborated.hasErrors, elaborated.errors)
      assertStable(typed, elaborated)
      List(
        ("returned", "pick", 10, false),
        ("owned", "make", 1, false),
        ("bound", "pick", 10, false),
        ("once", "make_once", 1, true)
      ).foreach { (name, factory, supplied, callOnce) =>
        val body      = member(elaborated, name).value
        val factoryId = member(elaborated, factory).id
        val calleeBindings = TermTraversal
          .collect(body) {
            case app: App if app.arg.terms.exists {
                  case call: App =>
                    val (callee, _) = CallableValues.application(call)
                    callee match
                      case ref: Ref => ref.resolvedId == factoryId
                      case _ => false
                  case _ => false
                } =>
              app.fn
          }
          .collect { case scope: Lambda => scope.params }
          .flatten
        assertEquals(calleeBindings.size, 1, name)
        val local = calleeBindings.head
        assert(local.id.nonEmpty, name)
        val expectedType = member(elaborated, factory).typeSpec.collect { case signature: TypeFn =>
          signature.returnType
        }
        assert(expectedType.nonEmpty, name)
        assertEquals(local.typeSpec, expectedType, name)

        val generated = partials(body)
        assertEquals(generated.size, 1, name)
        val residual = generated.head
        assertEquals(residual.params.size, 1, name)
        assert(residual.isMove, s"$name: ${residual.meta}")
        assertEquals(residual.captures.flatMap(_.ref.resolvedId).toSet, local.id.toSet, name)
        assertEquals(
          residual.meta.map(_.transferredCaptures),
          Some(if callOnce then local.id.toSet else Set.empty[String]),
          name
        )
        assertEquals(residual.meta.map(_.borrowedCaptures), Some(Set.empty[String]), name)
        residual.body.terms match
          case List(call: App) =>
            val (callee, arguments) = CallableValues.application(call)
            callee match
              case ref: Ref => assertEquals(ref.resolvedId, local.id, name)
              case other => fail(s"Expected captured callee, found $other")
            assertEquals(arguments.size, 2, name)
            arguments.head.terms match
              case List(value: LiteralInt) => assertEquals(value.value, supplied.toLong, name)
              case other => fail(s"Expected supplied argument, found $other")
            arguments(1).terms match
              case List(ref: Ref) => assertEquals(ref.resolvedId, residual.params.head.id, name)
              case other => fail(s"Expected residual parameter, found $other")
            residual.typeSpec match
              case Some(signature: TypeFn) =>
                assertEquals(signature.paramTypes.toList, residual.params.flatMap(_.typeSpec), name)
                assertEquals(Some(signature.returnType), call.typeSpec, name)
              case other => fail(s"Expected residual callable type, found $other")
          case other => fail(s"Expected forwarding application, found $other")
      }
    }
  }

  test("a direct callee qualifier elaborates its partial calls and retains its owner") {
    val source = """
      fn add(a: Int, b: Int): Int = a + b;;
      struct Holder { f: Int -> Int -> Int };
      fn pack(~f: Int -> Int, n: Int): Holder = let result = f n; Holder add;;
      fn example(): Int = ({ a: Int -> pack (add a); } 1 2).f 3 4;;
    """
    typeCheck(source).map { typed =>
      assertEquals(typed.errors, Vector.empty)
      val elaborated  = PartialApplicationElaborator.rewriteModule(typed)
      val body        = member(elaborated, "example").value
      val addPartial  = forwardingFunction(body, member(elaborated, "add"))
      val packPartial = forwardingFunction(body, member(elaborated, "pack"))

      assertEquals(addPartial.params.size, 1)
      assertEquals(packPartial.params.size, 1)
      assert(addPartial.meta.exists(_.isPartialApplication))
      assert(packPartial.meta.exists(_.isPartialApplication))
      assertBoundQualifier(body, elaborated.module)
      assertStable(typed, elaborated)
    }
  }

  test("a conditional qualifier preserves partial calls and a bare function in separate branches") {
    val source = """
      fn add(a: Int, b: Int): Int = a + b;;
      struct Holder { f: Int -> Int -> Int };
      fn pack(~f: Int -> Int, n: Int): Holder = let result = f n; Holder add;;
      fn example(flag: Bool): Int =
        (if flag then { a: Int -> pack (add a); } 1 2; else Holder add;).f 3 4;
      ;
    """
    typeCheck(source).map { typed =>
      assertEquals(typed.errors, Vector.empty)
      val elaborated = PartialApplicationElaborator.rewriteModule(typed)
      val body       = member(elaborated, "example").value
      val branches   = TermTraversal.collect(body) { case cond: Cond => cond }
      assertEquals(branches.size, 1)
      val add         = member(elaborated, "add")
      val pack        = member(elaborated, "pack")
      val addPartial  = forwardingFunction(branches.head.ifTrue, add)
      val packPartial = forwardingFunction(branches.head.ifTrue, pack)
      val addValue    = forwardingFunction(branches.head.ifFalse, add)

      assertEquals(addPartial.params.size, 1)
      assertEquals(packPartial.params.size, 1)
      assert(addPartial.meta.exists(_.isPartialApplication))
      assert(packPartial.meta.exists(_.isPartialApplication))
      assertEquals(addValue.params.size, 2)
      assert(!addValue.meta.exists(_.isPartialApplication))
      assertEquals(addValue.typeSpec, add.typeSpec)
      assertEquals(addValue.captures, Nil)
      assertBoundQualifier(body, elaborated.module)
      assertStable(typed, elaborated)
    }
  }

  private def forwardingFunction(term: Term, target: Bnd): Lambda =
    val matches = TermTraversal.collect(term) { case lambda: Lambda => lambda }.filter { lambda =>
      lambda.body.terms match
        case (app: App) :: Nil =>
          CallableValues.application(app)._1 match
            case ref: Ref => ref.resolvedId == target.id
            case _ => false
        case _ => false
    }
    assertEquals(matches.size, 1, s"Expected one function forwarding to ${target.name}")
    matches.head

  private def assertBoundQualifier(term: Term, module: Module): Unit =
    val selections = TermTraversal.collect(term) {
      case ref: Ref if ref.qualifier.nonEmpty => ref
    }
    assertEquals(selections.size, 1)
    selections.head.qualifier match
      case Some(owner: Ref) =>
        assert(owner.resolvedId.flatMap(module.resolvables.lookup).nonEmpty)
      case other => fail(s"Expected a bound qualifier owner, got $other")

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
        def retainedFailures(state: CompilerState) =
          TermTraversal.collect(member(state, "example").value) { case invalid: InvalidExpression =>
            (invalid.source, invalid.typeSpec, invalid.causes)
          }
        assertEquals(retainedFailures(elaborated), retainedFailures(typed))
        assertStable(typed, elaborated)
      }
    }
  }

  test("an invalid callee body retains independent ownership diagnostics") {
    semVisibleState("""
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

  List(false, true).foreach { malformed =>
    test(s"failed PAP result boundaries preserve independent children: malformed=$malformed") {
      typeCheck(s"$addition let example = add 1;").map { typed =>
        assert(!typed.hasErrors, typed.errors)
        val original = member(typed, "example")
        val payload =
          if malformed then
            original.value.copy(terms = original.value.terms ++ original.value.terms)
          else original.value
        val prior =
          SemanticError.InvalidExpression(payload, "Retained frontend failure", "frontend")
        val (diagnostics, roots) = typed.diagnostics.emit(prior)
        val invalid = InvalidExpression(
          payload.source,
          payload,
          typeSpec = payload.typeSpec,
          causes   = roots
        )
        val replaced = original.copy(value = original.value.copy(terms = List(invalid)))
        val input = ResolvablesIndexer.rewriteModule(
          typed
            .copy(diagnostics = diagnostics)
            .withModule(
              typed.module.copy(members = typed.module.members.map {
                case binding: Bnd if binding.id == original.id => replaced
                case other => other
              })
            )
        )
        val elaborated = PartialApplicationElaborator.rewriteModule(input)
        val result     = member(elaborated, "example").value
        assertEquals(partials(result).size, if malformed then 2 else 0)
        assertEquals(elaborated.errors, Vector(prior))
        val failures = TermTraversal.collect(result) { case failure: InvalidExpression => failure }
        assertEquals(failures.size, 1)
        assertEquals(failures.head.causes, roots)
        assertEquals(failures.head.source, payload.source)
        assertEquals(failures.head.originalExpr.terms.size, payload.terms.size)
        assert(!ValueAvailability.fromModule(elaborated.module).isAvailable(result))
        assertStable(input, elaborated)
      }
    }
  }
