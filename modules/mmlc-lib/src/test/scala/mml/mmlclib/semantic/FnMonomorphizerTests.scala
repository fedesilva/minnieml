package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.ast.queries.DeclarationQueries
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.TXScopedBinding
import mml.mmlclib.util.error.print.diagnosticMessage

class FnMonomorphizerTests extends BaseEffFunSuite:

  private val identityDefinition = "fn identity(~value: 'T): 'T = value;;"

  private def instantiate(source: String): IO[CompilerState] =
    parseNotFailed(source).map { parsed =>
      val checked = ResolvablesIndexer.rewriteModule(
        TypeChecker.rewriteModule(prepareForTypeChecking(source, parsed))
      )
      assertEquals(checked.errors.map(diagnosticMessage), Vector.empty)
      FnMonomorphizer.rewriteModule(checked)
    }

  private def binding(state: CompilerState, name: String): Bnd =
    state.module.members
      .collectFirst { case b: Bnd if b.name == name => b }
      .getOrElse(fail(s"Missing fixture binding $name"))

  private def selected(state: CompilerState, name: String): Bnd =
    val refs = TermTraversal.collect(binding(state, name).value) { case ref: Ref => ref }
    assertEquals(refs.size, 1)
    assertEquals(refs.head.typeArgs, Nil)
    refs.head.resolvedId
      .flatMap(state.module.resolvables.lookup)
      .collect { case b: Bnd => b }
      .getOrElse(fail("Missing instantiated definition"))

  private def recursionPaths(state: CompilerState): Vector[List[Ref]] =
    state.semanticErrors.collect {
      case SemanticError.InvalidExpression(
            _,
            ExpressionProblem.ChangingGenericRecursion(path),
            _
          ) =>
        path
    }

  test(
    "inferred and explicit uses reuse an instance while distinct types have distinct definitions"
  ):
    instantiate(
      identityDefinition + "let a = identity 1; let b = identity Int 2; let c = identity true;"
    )
      .map { state =>
        assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
        val a = selected(state, "a")
        val b = selected(state, "b")
        val c = selected(state, "c")
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
        val first =
          TermTraversal.collect(a.value) { case l: Lambda => l.params.flatMap(_.id) }.flatten
        val second =
          TermTraversal.collect(c.value) { case l: Lambda => l.params.flatMap(_.id) }.flatten
        assert(first.nonEmpty)
        assertEquals(first.toSet.intersect(second.toSet), Set.empty[String])
        assertEquals(
          DeclarationQueries.genericFunctions(state.module.resolvables).map(_.name),
          List("identity")
        )
        assert(state.module.members.contains(binding(state, "identity")))
      }

  test("member specialization keys include the instance binding and ordered local arguments"):
    instantiate("""
      protocol Count 'T = fn count(x: 'T, other: 'U): Int; ;
      instance Count Int = fn count(x, other) = 1;; ;
      instance Count Bool = fn count(x, other) = 2;; ;
      let first = count 1 true;
      let repeated = count Bool 2 false;
      let other_local = count 1 7;
      let other_instance = count true true;
    """).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      val first = selected(state, "first")
      assertEquals(first.id, selected(state, "repeated").id)
      assertEquals(
        List(first, selected(state, "other_local"), selected(state, "other_instance"))
          .flatMap(_.id)
          .distinct
          .size,
        3
      )
    }

  test("unused valid templates produce no executable definition"):
    instantiate(identityDefinition + "let value = 7;").map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
      assert(state.module.members.contains(binding(state, "identity")))
      assert(
        !state.module.members
          .collect { case b: Bnd => b }
          .exists(_.value.terms.exists {
            case lambda: Lambda => lambda.meta.exists(_.templateId.nonEmpty)
            case _ => false
          })
      )
    }

  test("constrained operations select concrete protocol members"):
    semNotFailed("""
      protocol Echo 'T = fn echo(value: 'T): 'T; ;
      instance Echo Int = fn echo(value) = value;; ;
      fn relay(value: 'T): 'T where 'T: Echo = echo value;;
      fn main(): Int = relay 7;;
    """).map { module =>
      val refs = module.members
        .filterNot(DeclarationQueries.isGenericFunction)
        .collect { case b: Bnd => b }
        .flatMap { b =>
          TermTraversal.collect(b.value) { case ref: Ref => ref }
        }
      assert(!refs.exists(_.protocolType.nonEmpty))
      assert(
        !refs
          .flatMap(_.resolvedId.flatMap(module.resolvables.lookup))
          .exists(_.isInstanceOf[ProtocolSignature])
      )
    }

  test("same-substitution direct recursion reaches executable lowering"):
    semNotFailed("""
      fn repeat(value: 'T, n: Int): 'T =
        if n == 0 then value; else repeat value (n - 1); ;
      ;
      fn main(): Int = repeat 7 3;;
    """)

  test("same-substitution mutual recursion reaches executable lowering"):
    semNotFailed("""
      fn left(value: 'T, n: Int): 'T =
        if n == 0 then value; else right value (n - 1); ;
      ;
      fn right(value: 'T, n: Int): 'T =
        if n == 0 then value; else left value (n - 1); ;
      ;
      fn main(): Int = left 7 3;;
    """)

  test("changing-type recursion is a bounded diagnostic with dependency evidence"):
    instantiate("""
      fn again(value: 'T): Int = again true;;
      let value = again 1;
    """).map { state =>
      val failures = recursionPaths(state)
      assertEquals(failures.size, 1)
      assert(failures.head.nonEmpty)
      assert(failures.head.forall(_.source.spanOpt.nonEmpty))
    }

  test("unused invalid generic bodies still fail checking"):
    semState("fn wrong(value: 'T): 'T = 1;; fn main(): Int = 0;;").map { state =>
      assert(state.errors.exists {
        case SemanticError.TypeCheckingError(_: TypeError.TypeMismatch) => true
        case _ => false
      })
    }

  test("changing-type recursion is checked when the callee instance is already cached"):
    instantiate("""
      fn again(value: 'T): Int = again true;;
      let first = again true;
      let second = again 1;
    """).map { state =>
      val failures = recursionPaths(state)
      assertEquals(failures.size, 1)
      assertEquals(state.semanticErrors.size, 1)
      val message = diagnosticMessage(state.errors.head)
      assert(message.contains("again Int32 -> again Bool"), message)
      assert(failures.head.nonEmpty)
      assert(failures.head.forall(_.source.spanOpt.nonEmpty))
    }

  test("local generic functions instantiate in their lexical scope"):
    semNotFailed("""
      fn main(): Int =
        fn local(value: 'T): 'T = value;;
        let answer = local 7;
        let ready = local true;
        if ready then answer; else 0;;
      ;
    """)

  test("generic calls emit LLVM without abstract templates"):
    compileAndGenerate(identityDefinition + "pub fn main(): Int = identity 7;;").map { ir =>
      assert(ir.contains("define"))
    }

  test("canonical aliases reuse a concrete instance"):
    instantiate(
      identityDefinition + "type Count = Int; let a = identity Count 1; let b = identity Int 2;"
    )
      .map { state =>
        assertEquals(state.errors.map(diagnosticMessage), Vector.empty)
        assertEquals(selected(state, "a").id, selected(state, "b").id)
      }

  test("function-value dependencies reject changing-type recursion"):
    instantiate("""
      fn invoke(f: Bool -> Int): Int = f true;;
      fn again(value: 'T): Int = invoke again;;
      let value = again 1;
    """).map { state =>
      assertEquals(recursionPaths(state).size, 1)
      assertEquals(state.semanticErrors.size, 1)
    }

  test("finite mutually recursive changing-type dependencies are rejected"):
    instantiate("""
      fn left(value: 'T): Int = right value;;
      fn right(value: 'T): Int = left true;;
      let value = left 1;
    """).map { state =>
      val paths = recursionPaths(state)
      assertEquals(paths.size, 1)
      assertEquals(paths.head.size, 3)
      assertEquals(state.semanticErrors.size, 1)
    }

  test("recursive locals have distinct definitions and matching uses in each outer instance"):
    instantiate("""
      fn outer(value: 'T): Int =
        fn count(n: Int): Int = if n == 0 then 0; else count (n - 1); ;;
        count 3;
      ;
      let first = outer 7;
      let second = outer true;
    """).map { state =>
      assertEquals(state.errors.map(diagnosticMessage), Vector.empty)

      def countIdentity(owner: Bnd): String =
        val locals = TermTraversal.collect(owner.value) {
          case TXScopedBinding(scope, initializer: Lambda)
              if scope.params.size == 1 && scope.params.head.name == "count" =>
            (scope.params.head, initializer, scope.body)
        }
        assertEquals(locals.size, 1)
        val (parameter, initializer, continuation) = locals.head
        val id = parameter.id.getOrElse(fail("Missing local definition identity"))
        List(initializer.body, continuation).foreach { body =>
          val references = TermTraversal.collect(body) {
            case ref: Ref if ref.name == "count" => ref
          }
          assertEquals(references.size, 1)
          assertEquals(references.head.resolvedId, Some(id))
          assertEquals(state.module.resolvables.lookup(id), Some(parameter))
        }
        id

      assertNotEquals(
        countIdentity(selected(state, "first")),
        countIdentity(selected(state, "second"))
      )
    }
