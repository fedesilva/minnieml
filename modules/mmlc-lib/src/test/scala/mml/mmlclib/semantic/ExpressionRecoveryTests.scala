package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class ExpressionRecoveryTests extends BaseEffFunSuite:

  private def phaseInput(code: String, parsed: Module): CompilerState =
    val module   = injectStandardOperators(injectBasicTypes(parsed))
    val initial  = CompilerState.empty(module, SourceInfo(code), CompilerConfig.default)
    val typed    = TypeResolver.rewriteModule(IdAssigner.rewriteModule(initial))
    val resolved = RefResolver.rewriteModule(typed)
    resolved.withModule(ResolvablesIndexer.refresh(resolved.module))

  private def authoredBinding(module: Module): Bnd =
    val bindings = module.members.collect {
      case binding: Bnd if binding.source.isFromSource => binding
    }
    assertEquals(bindings.size, 1)
    bindings.head

  private def localBinding(value: Expr): (App, Lambda) =
    val bindings = TermTraversal.collect(value) {
      case app: App if app.fn.isInstanceOf[Lambda] => app
    }
    assertEquals(bindings.size, 1)
    bindings.head.fn match
      case lambda: Lambda => (bindings.head, lambda)
      case other => fail(s"Expected local binding lambda, got $other")

  private def onlyInvalid(value: Expr): InvalidExpression = value.terms match
    case (invalid: InvalidExpression) :: Nil => invalid
    case other => fail(s"Expected one invalid region, got $other")

  private def assertAddition(value: Expr, left: Long, right: Long, module: Module): Unit =
    val additionId = module.members
      .collectFirst {
        case binding: Bnd
            if binding.meta
              .exists(meta => meta.originalName == "+" && meta.arity == CallableArity.Binary) =>
          binding.id
      }
      .flatten
      .getOrElse(fail("Missing addition declaration"))
    value.terms match
      case (outer: App) :: Nil =>
        outer.fn match
          case inner: App =>
            inner.fn match
              case ref: Ref => assertEquals(ref.resolvedId, Some(additionId))
              case other => fail(s"Expected resolved operator, got $other")
            assertEquals(
              inner.arg.terms.collect { case literal: LiteralInt => literal.value },
              List(left)
            )
            assertEquals(
              outer.arg.terms.collect { case literal: LiteralInt => literal.value },
              List(right)
            )
          case other => fail(s"Expected first operator application, got $other")
      case other => fail(s"Expected normalized addition, got $other")

  test("a failed initializer keeps its roots while an independent continuation normalizes"):
    val code = "fn example(): Int = let invalid = missing; 3 + 4;;"
    parseNotFailed(code).map { parsed =>
      val initial                              = phaseInput(code, parsed)
      val original                             = authoredBinding(initial.module)
      val (originalApplication, originalScope) = localBinding(original.value)
      val originalInvalid                      = onlyInvalid(originalApplication.arg)
      val result                               = ExpressionRewriter.rewriteModule(initial)
      val binding                              = authoredBinding(result.module)
      val (application, scope)                 = localBinding(binding.value)
      val invalid                              = onlyInvalid(application.arg)

      assertEquals(invalid.source, originalInvalid.source)
      assertEquals(invalid.originalExpr, originalInvalid.originalExpr)
      assertEquals(invalid.causes, originalInvalid.causes)
      assertEquals(scope.params.map(_.id), originalScope.params.map(_.id))
      assertAddition(scope.body, 3, 4, result.module)
      assertEquals(result.errors.count(_.isInstanceOf[SemanticError.InvalidExpressionFound]), 1)
      assertEquals(result.visibleErrors.map(_.message), Vector("Undefined reference: 'missing'"))
      assertEquals(result.errors.size, 2)
      scope.params.foreach { param =>
        assertEquals(result.module.resolvables.lookup(param.id.get), Some(param))
      }
      assertEquals(result.module.resolvables.lookup(binding.id.get), Some(binding))
    }

  test("local normalization failure retains its payload without rolling back its continuation"):
    val code = "fn example(): Int = let invalid = 1 2; 3 + 4;;"
    parseNotFailed(code).map { parsed =>
      val initial                              = phaseInput(code, parsed)
      val (originalApplication, originalScope) = localBinding(authoredBinding(initial.module).value)
      val result                               = ExpressionRewriter.rewriteModule(initial)
      val (application, scope)                 = localBinding(authoredBinding(result.module).value)
      val invalid                              = onlyInvalid(application.arg)

      assertEquals(initial.errors.size, 0)
      assertEquals(
        result.errors.map(_.message),
        Vector("Unexpected term after expression - expected an operator")
      )
      assertEquals(result.visibleErrors, result.errors)
      assertEquals(invalid.originalExpr, originalApplication.arg)
      result.errors.head match
        case error: SemanticError.DanglingTerms =>
          assertEquals(error.terms.map(_.source), originalApplication.arg.terms.tail.map(_.source))
        case other => fail(s"Expected dangling-term diagnostic, got $other")
      assertEquals(invalid.causes, result.diagnostics.records.map(_.id))
      assertEquals(scope.params.map(_.id), originalScope.params.map(_.id))
      assertAddition(scope.body, 3, 4, result.module)
    }

  test("separate tuple failures accumulate while a valid sibling normalizes"):
    val code = "let values = (1 2, 3 4, 5 + 6);"
    parseNotFailed(code).map { parsed =>
      val initial = phaseInput(code, parsed)
      val result  = ExpressionRewriter.rewriteModule(initial)
      val tuple = authoredBinding(result.module).value.terms match
        case (tuple: Tuple) :: Nil => tuple
        case other => fail(s"Expected tuple, got $other")
      val elements = tuple.elements.toList
      val failures = elements.take(2).map(onlyInvalid)

      assertEquals(
        result.errors.map(_.message),
        Vector.fill(2)("Unexpected term after expression - expected an operator")
      )
      assertEquals(failures.flatMap(_.causes).toVector, result.diagnostics.records.map(_.id))
      assertEquals(failures.flatMap(_.causes).distinct.size, 2)
      assertEquals(result.visibleErrors, result.errors)
      assertAddition(elements(2), 5, 6, result.module)
    }

  test("invalid flat regions preserve syntax and normalize nested groups once"):
    val code = "let value = missing + 1 * (2 + 3);"
    parseNotFailed(code).map { parsed =>
      val initial  = phaseInput(code, parsed)
      val original = authoredBinding(initial.module)
      val result   = ExpressionRewriter.rewriteModule(initial)
      val invalid  = onlyInvalid(authoredBinding(result.module).value)
      val payload  = invalid.originalExpr
      val group    = payload.terms.collect { case group: TermGroup => group }

      assertEquals(payload.terms.length, original.value.terms.length)
      assertEquals(payload.source, original.value.source)
      assert(!payload.terms.exists(_.isInstanceOf[App]))
      assertEquals(group.size, 1)
      assertAddition(group.head.inner, 2, 3, result.module)
      assertEquals(result.errors.count(_.isInstanceOf[SemanticError.InvalidExpressionFound]), 1)
      assertEquals(result.visibleErrors.map(_.message), Vector("Undefined reference: 'missing'"))
      assertEquals(invalid.causes, initial.diagnostics.records.map(_.id))
    }

  test("an unattributed invalid expression remains a visible primary failure"):
    val code = "let value = 1;"
    parseNotFailed(code).map { parsed =>
      val initial = phaseInput(code, parsed)
      val binding = authoredBinding(initial.module)
      val invalid = InvalidExpression(binding.value.source, binding.value)
      val updated = binding.copy(value = binding.value.copy(terms = List(invalid)))
      val members = initial.module.members.map {
        case candidate: Bnd if candidate.id == binding.id => updated
        case member => member
      }
      val result = ExpressionRewriter.rewriteModule(
        initial.withModule(ResolvablesIndexer.refresh(initial.module.copy(members = members)))
      )

      assertEquals(result.errors.map(_.message), Vector("Invalid expression"))
      assertEquals(result.visibleErrors, result.errors)
      assertEquals(result.diagnostics.records.map(_.relation), Vector(DiagnosticRelation.Primary))
      assertEquals(
        onlyInvalid(authoredBinding(result.module).value).causes,
        result.diagnostics.records.map(_.id)
      )
    }
