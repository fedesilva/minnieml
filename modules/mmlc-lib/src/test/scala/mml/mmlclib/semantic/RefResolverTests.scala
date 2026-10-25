package mml.mmlclib.semantic

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.errors.{CompilationError, DiagnosticRelation}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class RefResolverTests extends BaseEffFunSuite:

  private def beforeResolution(source: String): IO[CompilerState] =
    parseNotFailed(source).map { module =>
      val state = IdAssigner.rewriteModule(
        CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      )
      assertEquals(state.errors, Vector.empty)
      state
    }

  private def resolve(state: CompilerState): CompilerState =
    ResolvablesIndexer.rewriteModule(RefResolver.rewriteModule(state))

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case binding: Bnd if binding.name == name => binding }
      .getOrElse(fail(s"Expected binding $name"))

  private def lambda(binding: Bnd): Lambda =
    binding.value.terms
      .collectFirst { case lambda: Lambda => lambda }
      .getOrElse(fail("Expected lambda"))

  private def undefinedRefs(state: CompilerState): Vector[SemanticError.UndefinedRef] =
    state.semanticErrors.collect { case error: SemanticError.UndefinedRef => error }

  private def invalidExpressions(term: Term): List[InvalidExpression] =
    TermTraversal.collect(term) { case invalid: InvalidExpression => invalid }

  private def assertRoots(
    state:   CompilerState,
    invalid: List[InvalidExpression],
    errors:  Vector[SemanticError.UndefinedRef]
  ): Unit =
    assertEquals(invalid.size, errors.size)
    invalid.zip(errors).foreach { (expression, error) =>
      assertEquals(expression.causes.size, 1)
      val record = state.diagnostics
        .lookup(expression.causes.head)
        .getOrElse(fail("Expected original diagnostic for failed reference"))
      assertEquals(record.error, error: CompilationError)
      assertEquals(record.relation, DiagnosticRelation.Primary)
    }
    assertEquals(invalid.flatMap(_.causes).distinct.size, errors.size)

  test("independent undefined occurrences retain distinct roots beside a valid reference") {
    beforeResolution("fn example(value) = (missing, missing, value);;").map { initial =>
      val original = binding(initial.module, "example")
      val originalRefs = TermTraversal.collect(original.value) {
        case ref: Ref if ref.name == "missing" => ref
      }
      assertEquals(originalRefs.size, 2)

      val state   = resolve(initial)
      val errors  = undefinedRefs(state)
      val example = binding(state.module, "example")
      val invalid = invalidExpressions(example.value)
      assertEquals(errors.map(_.ref).toList, originalRefs)
      assertEquals(state.errors.size, 2)
      assertEquals(invalid.size, 2)
      assertEquals(invalid.map(_.originalExpr.terms), originalRefs.map(List(_)))
      assertEquals(invalid.map(_.source), originalRefs.map(_.source))
      assertRoots(state, invalid, errors)

      val param = lambda(example).params.head
      val valid = TermTraversal.collect(example.value) {
        case ref: Ref if ref.resolvedId == param.id => ref
      }
      assertEquals(valid.size, 1)
      assertEquals(valid.head.candidateIds, param.id.toList)
      assertEquals(param.id.flatMap(state.module.resolvables.lookup), param.some)
      assertEquals(resolve(state), state)
    }
  }

  test("undefined predicate and both branches accumulate in traversal order") {
    beforeResolution("fn example() = if missing then absent; else unknown;;;").map { initial =>
      val originalRefs = TermTraversal.collect(binding(initial.module, "example").value) {
        case ref: Ref => ref
      }
      assertEquals(originalRefs.size, 3)
      val state = resolve(initial)
      assertEquals(undefinedRefs(state).map(_.ref).toList, originalRefs)
      assertEquals(state.errors.size, 3)
      val invalid = invalidExpressions(binding(state.module, "example").value)
      assertEquals(invalid.map(_.originalExpr.terms), originalRefs.map(List(_)))
      assertRoots(state, invalid, undefinedRefs(state))
    }
  }

  for nested <- List(false, true) do
    test(s"undefined application callee preserves independent arguments with nested=$nested") {
      beforeResolution("fn example(value) = missing absent value;;").map { initial =>
        val original = binding(initial.module, "example")
        val function = lambda(original)
        val refs     = function.body.terms.collect { case ref: Ref => ref }
        assertEquals(refs.size, 3)
        val arg  = function.body.copy(terms = refs.tail)
        val call = App(function.body.source, refs.head, arg)
        val application =
          if nested then App(call.source, call, Expr(call.source, Nil)) else call
        val inputBinding = original.copy(value =
          original.value.copy(terms =
            List(
              function.copy(body = function.body.copy(terms = List(application)))
            )
          )
        )
        val input = initial.withModule(initial.module.copy(members = List(inputBinding)))
        val state = resolve(input)

        assertEquals(undefinedRefs(state).map(_.ref).toList, refs.take(2))
        assertEquals(state.errors.size, 2)
        val updated = lambda(binding(state.module, "example"))
        val failedCall = updated.body.terms match
          case (invalid: InvalidExpression) :: Nil => invalid
          case other => fail(s"Expected retained invalid application, got $other")
        val retainedApps = TermTraversal.collect(failedCall.originalExpr) { case app: App => app }
        assertEquals(retainedApps.size, if nested then 2 else 1)
        assertEquals(retainedApps.last.fn, refs.head)
        assertEquals(failedCall.source, application.source)
        val invalid = invalidExpressions(updated.body)
        assertEquals(invalid.size, 2)
        assertRoots(state, invalid, undefinedRefs(state))
        val valid = TermTraversal.collect(failedCall.originalExpr) {
          case ref: Ref if ref.resolvedId == updated.params.head.id => ref
        }
        assertEquals(valid.size, 1)
        assertEquals(resolve(state), state)
      }
    }

  test("recursive local initializer and continuation resolve the same binding identity") {
    beforeResolution("fn example() = let loop = { loop (); }; loop ();;").map { initial =>
      val state = resolve(initial)
      assertEquals(state.errors, Vector.empty)
      val example = lambda(binding(state.module, "example"))
      val local = TermTraversal
        .collect(example.body) {
          case lambda: Lambda if lambda.params.nonEmpty => lambda.params
        }
        .flatten
      assertEquals(local.size, 1)
      val recursive = local.head
      val refs      = TermTraversal.collect(example.body) { case ref: Ref => ref }
      assertEquals(refs.size, 2)
      refs.foreach { ref =>
        assertEquals(ref.resolvedId, recursive.id)
        assertEquals(ref.candidateIds, recursive.id.toList)
      }
      assertEquals(recursive.id.flatMap(state.module.resolvables.lookup), recursive.some)
    }
  }
