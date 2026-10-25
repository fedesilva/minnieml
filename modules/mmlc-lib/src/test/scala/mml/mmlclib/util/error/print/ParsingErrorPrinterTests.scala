package mml.mmlclib.util.error.print

import cats.syntax.option.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{Compilation, CompilerConfig}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.{DiagnosticCauses, SemanticError, ValueAvailability}
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.TXExprInt

class ParsingErrorPrinterTests extends BaseEffFunSuite:

  test("nested parser errors retain their message, location, and recovery text in CLI output") {
    val code = "let x = let 123invalid = 5; 6;"

    Compilation
      .compileSource(code, "NestedParseError", "nested-parse-error.mml", CompilerConfig.default)
      .map { state =>
        val visible = state.visibleErrors.collect { case error: SemanticError => error }.toList
        val diagnostic = visible match
          case List(error: SemanticError.ParseTermErrorFound) => error
          case other => fail(s"Expected only the nested parse error, got $other")
        val records = state.diagnostics.records
        val original = records
          .find { record =>
            record.error match
              case error: SemanticError.ParseTermErrorFound => error.error == diagnostic.error
              case _ => false
          }
          .getOrElse(fail("Missing parser diagnostic"))
        val consequences = records.filterNot(_.id == original.id)
        assert(consequences.nonEmpty)
        assert(consequences.forall(_.causes == Vector(original.id)), consequences)
        val error = diagnostic.error
        val span  = error.spanOpt.getOrElse(fail("Expected nested parse error source span"))
        val start = code.indexOf("123invalid")
        val info  = SourceInfo(code)
        assertEquals(span, SrcSpan(info.pointAt(start), info.pointAt(start + "123invalid".length)))
        assertEquals(
          diagnostic.message,
          "Parse error in expression: Invalid identifier '123invalid'"
        )

        val sourceInfo = SourceInfo(code)
        val output     = ErrorPrinter.prettyPrintSemanticErrors(visible, sourceInfo.some)
        assert(output.contains(error.message), output)
        assert(output.contains(ErrorPrinter.formatLocation(span)), output)
        assert(output.contains(sourceInfo.slice(span.start.index, span.end.index)), output)

        val withoutSource = SemanticErrorPrinter.prettyPrint(visible)
        assert(withoutSource.contains(error.message), withoutSource)
        assert(withoutSource.contains(LocationPrinter.printSpan(span)), withoutSource)
        assert(withoutSource.contains("\n123invalid"), withoutSource)
      }
  }

  test("dangling recovered parser terms retain their CLI diagnostic label") {
    val code = "let x = 1 let 123invalid = 5; 6;"

    parseNotFailed(code).map { module =>
      val recovered = module.members
        .collect { case binding: Bnd => binding }
        .flatMap(binding =>
          TermTraversal.collect(binding.value) { case error: ParseTermError =>
            error
          }
        )
      assertEquals(recovered.size, 1)
      val errors = List(
        SemanticError.DanglingTerms(
          recovered,
          "Unexpected term after expression - expected an operator",
          "printer-fixture"
        )
      )
      val outputs = List(
        ErrorPrinter.prettyPrintSemanticErrors(errors, SourceInfo(code).some),
        SemanticErrorPrinter.prettyPrint(errors)
      )
      outputs.foreach { output =>
        val termLabels = output.linesIterator.filter(_.startsWith("Term: ")).toList
        assertEquals(termLabels, List("Term: TermError"), output)
        assert(output.contains("Unexpected term after expression - expected an operator"), output)
      }
    }
  }

  test("a retained scoped continuation cannot hide an independent flat-expression failure") {
    val code = "let x = 1 let 123invalid = 5; 6;"

    def flatParts(value: Expr): (LiteralInt, App) = value.terms match
      case List(literal: LiteralInt, application: App) => (literal, application)
      case other => fail(s"Expected a flat literal and retained scoped binding, got $other")

    def onlyParseError(value: Term): ParseTermError =
      val errors = TermTraversal.collect(value) { case error: ParseTermError => error }
      assertEquals(errors.size, 1)
      errors.head

    for
      parsed <- parseNotFailed(code)
      state <- Compilation.compileSource(
        code,
        "FlatParseError",
        "flat-parse-error.mml",
        CompilerConfig.default
      )
    yield
      val original = parsed.members
        .collectFirst { case binding: Bnd => binding.value }
        .getOrElse(fail("Missing original binding"))
      val retained = state.module.members
        .collectFirst {
          case binding: Bnd if binding.name == "x" => binding.value
        }
        .getOrElse(fail("Missing retained binding"))
      val parserRecords = state.diagnostics.records.filter(
        _.error.isInstanceOf[SemanticError.ParseTermErrorFound]
      )
      val expressionRecords = state.diagnostics.records.filter(
        _.error.isInstanceOf[SemanticError.DanglingTerms]
      )
      assertEquals(parserRecords.size, 1)
      assertEquals(expressionRecords.size, 1)
      val parserRoot     = parserRecords.head
      val expressionRoot = expressionRecords.head
      assertEquals(parserRoot.relation, DiagnosticRelation.Primary)
      assertEquals(expressionRoot.relation, DiagnosticRelation.Primary)
      assertNotEquals(parserRoot.id, expressionRoot.id)
      val payload = retained.terms match
        case (invalid: InvalidExpression) :: Nil =>
          assertEquals(invalid.causes, Vector(expressionRoot.id))
          invalid.originalExpr
        case other => fail(s"Expected failed flat region, got $other")
      assertEquals(payload.source, original.source)
      val (originalLiteral, originalApplication) = flatParts(original)
      val (literal, application)                 = flatParts(payload)
      val originalError                          = onlyParseError(originalApplication.arg)
      val error                                  = onlyParseError(application.arg)
      assertEquals(literal.value, originalLiteral.value)
      assertEquals(literal.source, originalLiteral.source)
      assert(
        literal.typeSpec.exists(tpe => TypeUtils.canonical(tpe, state.module.resolvables).nonEmpty)
      )
      assertEquals(application.source, originalApplication.source)
      assertEquals(error.source, originalError.source)
      assertEquals(error.message, originalError.message)
      assertEquals(error.failedCode, originalError.failedCode)
      assertEquals(error.causes, Vector(parserRoot.id))
      application.fn match
        case scope: Lambda =>
          assertEquals(scope.source, application.source)
          assertEquals(scope.params.size, 1)
          assert(!scope.params.head.nameNode.source.isFromSource)
          scope.body match
            case TXExprInt(6) => ()
            case other => fail(s"Expected retained independent continuation, got $other")
        case other => fail(s"Expected retained binding scope, got $other")
      assert(ValueAvailability.fromModule(state.module).isAvailable(application))
      assertEquals(
        DiagnosticCauses.fromModule(state.module).knownRootsOf(application),
        Vector.empty
      )
      assertEquals(
        state.visibleErrors.map(_.message),
        Vector(
          "Parse error in expression: Invalid identifier '123invalid'",
          "Unexpected term after expression - expected an operator"
        )
      )
      val rootIds = Set(parserRoot.id, expressionRoot.id)
      assertEquals(state.diagnostics.visibleRecords.map(_.id).toSet, rootIds)
      val consequences = state.diagnostics.records.filter(_.relation != DiagnosticRelation.Primary)
      assert(consequences.nonEmpty)
      assert(
        consequences.forall(record =>
          record.causes.nonEmpty && record.causes.forall(rootIds.contains)
        ),
        consequences
      )
      val visible = state.visibleErrors.collect { case error: SemanticError => error }.toList
      val output  = ErrorPrinter.prettyPrintSemanticErrors(visible, SourceInfo(code).some)
      assert(output.contains("Invalid identifier '123invalid'"), output)
      assert(output.contains("Unexpected term after expression - expected an operator"), output)
  }
