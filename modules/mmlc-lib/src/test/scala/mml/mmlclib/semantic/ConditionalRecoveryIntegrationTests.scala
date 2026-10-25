package mml.mmlclib.semantic

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig, CompilerState, IngestStage}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.lsp.{DiagnosticSeverity, Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.*
import mml.mmlclib.util.error.print.ErrorPrinter

import java.nio.file.Files

class ConditionalRecoveryIntegrationTests extends BaseEffFunSuite:

  private val source =
    """let broken =
      |  if true then
      |    1 @;
      |  elif false then
      |    2;
      |  else
      |    3;
      |  ;
      |let alias = broken;
      |let independent = missing;
      |let good = 3 + 4;
      |let later = good;""".stripMargin

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case value: Bnd if value.name == name => value }
      .getOrElse(fail(s"Expected source binding $name"))

  private def spanOf(code: String, fragment: String): SrcSpan =
    val start = code.indexOf(fragment)
    assert(start >= 0, s"Missing fixture fragment: $fragment")
    val info = SourceInfo(code)
    SrcSpan(info.pointAt(start), info.pointAt(start + fragment.length))

  private def pointAt(code: String, fragment: String): SrcSpan =
    val point = spanOf(code, fragment).start
    SrcSpan(point, point)

  private def references(value: Term): List[Ref] =
    TermTraversal.collect(value) { case reference: Ref => reference }

  private def parseErrors(value: Term): List[ParseTermError] =
    TermTraversal.collect(value) { case error: ParseTermError => error }

  private def onlyParseError(value: Term): ParseTermError =
    val errors = parseErrors(value)
    assertEquals(errors.size, 1)
    errors.head

  private def conditionalAt(value: Term, code: String, fragment: String): Cond =
    val start = spanOf(code, fragment).start.index
    val conditionals = TermTraversal.collect(value) {
      case conditional: Cond if conditional.spanOpt.exists(_.start.index == start) => conditional
    }
    assertEquals(conditionals.size, 1, s"Expected conditional at $fragment")
    conditionals.head

  @scala.annotation.tailrec
  private def function(value: Expr): Lambda = value match
    case TXExprLambda(lambda) => lambda
    case TXExpr1(invalid: InvalidExpression) => function(invalid.originalExpr)
    case other => fail(s"Expected source function, found $other")

  private def compile(
    code:   String         = source,
    config: CompilerConfig = CompilerConfig.default
  ): IO[CompilerState] =
    CompilerApi.compileSource(code, "ConditionalRecovery", config).map {
      case Right(state) => state
      case Left(error) => fail(s"Expected retained compiler state, got $error")
    }

  private def assertAddition(value: Expr, module: Module): Unit =
    val operator = module.members
      .collectFirst {
        case value: Bnd
            if value.meta
              .exists(meta => meta.originalName == "+" && meta.arity == CallableArity.Binary) =>
          value
      }
      .getOrElse(fail("Expected addition declaration"))
    assert(operator.id.nonEmpty)
    value match
      case TXExprApp(reference, _, List(TXExprInt(3), TXExprInt(4))) =>
        assertEquals(reference.resolvedId, operator.id)
      case other => fail(s"Expected normalized addition, found $other")

  test("ingest stamps one source root without changing the retained clause or failed fragment"):
    for
      parsed <- parseNotFailed(source)
      ingested <- IO.blocking(IngestStage.fromSource(source, "ConditionalRecovery"))
    yield
      val original    = binding(parsed, "broken").value
      val conditional = conditionalAt(original, source, "if true")
      val error       = onlyParseError(conditional.ifTrue)
      assertEquals(error.message, "Invalid expression syntax")
      assertEquals(error.failedCode, "@".some)
      assertEquals(error.spanOpt, spanOf(source, "@").some)
      assertEquals(error.causes, Vector.empty)
      assert(TermTraversal.collect(original) { case value: InvalidExpression => value }.isEmpty)

      val retained = binding(ingested.module, "broken").value
      val stamped  = onlyParseError(retained)
      assertEquals(stamped.copy(causes = Vector.empty), error)
      assertEquals(stamped.causes, ingested.diagnostics.records.map(_.id))
      assertEquals(ingested.diagnostics.records.map(_.relation), Vector(DiagnosticRelation.Primary))
      assertEquals(
        ingested.visibleErrors.map(_.message),
        Vector("Parse error in expression: Invalid expression syntax")
      )
      val retainedClause = conditionalAt(retained, source, "if true")
      assertEquals(retainedClause.spanOpt, conditional.spanOpt)
      assertEquals(retainedClause.ifFalse, conditional.ifFalse)

  test("failed branches retain roots and independent members still resolve and normalize"):
    for
      ingested <- IO.blocking(IngestStage.fromSource(source, "ConditionalRecovery"))
      state <- compile()
    yield
      val broken   = binding(state.module, "broken")
      val alias    = binding(state.module, "alias")
      val retained = onlyParseError(broken.value)
      assertEquals(retained.spanOpt, spanOf(source, "@").some)
      assertEquals(retained.failedCode, "@".some)
      assertEquals(retained.message, "Invalid expression syntax")
      assertEquals(retained.causes, onlyParseError(binding(ingested.module, "broken").value).causes)
      assertEquals(
        state.visibleErrors.map(_.message),
        Vector(
          "Parse error in expression: Invalid expression syntax",
          "Undefined reference: 'missing'"
        )
      )
      val undefined = state.semanticErrors.collect {
        case SemanticError.UndefinedRef(reference, _, _) => reference
      }
      assertEquals(undefined.map(_.spanOpt), Vector(spanOf(source, "missing").some))
      assert(broken.id.nonEmpty)
      assertEquals(references(alias.value).map(_.resolvedId), List(broken.id))
      assertEquals(
        DiagnosticCauses.fromModule(state.module).knownRootsOf(alias.value),
        retained.causes
      )

      val good  = binding(state.module, "good")
      val later = binding(state.module, "later")
      assertAddition(good.value, state.module)
      assert(good.id.nonEmpty)
      assertEquals(references(later.value).map(_.resolvedId), List(good.id))
      val availability = ValueAvailability.fromModule(state.module)
      assert(!availability.isAvailable(broken.value))
      assert(!availability.isAvailable(alias.value))
      assert(availability.isAvailable(good.value))
      assert(availability.isAvailable(later.value))

  test("retained condition and branch references resolve to the actual function parameters"):
    val code =
      """fn example(flag: Bool, value: Int): Int =
        |  if flag then
        |    value @;
        |  elif false then
        |    absent;
        |  else
        |    3 + 4;
        |  ;
        |;
        |let after = 7;""".stripMargin
    for
      parsed <- parseNotFailed(code)
      state <- compile(code)
    yield
      val original = function(binding(parsed, "example").value)
      val retained = function(binding(state.module, "example").value)
      assertEquals(retained.params.map(_.nameNode.spanOpt), original.params.map(_.nameNode.spanOpt))
      assertEquals(retained.params.size, 2)
      val flag  = retained.params.head
      val value = retained.params.last
      assert(flag.id.nonEmpty)
      assert(value.id.nonEmpty)
      val first  = conditionalAt(retained, code, "if flag")
      val second = conditionalAt(retained, code, "false then")
      assertEquals(references(first.cond).map(_.resolvedId), List(flag.id))
      assertEquals(references(first.ifTrue).map(_.resolvedId), List(value.id))
      assertEquals(onlyParseError(first.ifTrue).spanOpt, spanOf(code, "@").some)
      assertAddition(second.ifFalse, state.module)
      assertEquals(
        state.visibleErrors.map(_.message),
        Vector(
          "Parse error in expression: Invalid expression syntax",
          "Undefined reference: 'absent'"
        )
      )
      val undefined = state.semanticErrors.collect {
        case SemanticError.UndefinedRef(reference, _, _) => reference
      }
      assertEquals(undefined.map(_.spanOpt), Vector(spanOf(code, "absent").some))
      assert(
        ValueAvailability.fromModule(state.module).isAvailable(binding(state.module, "after").value)
      )

  test("independent failures in three clauses remain three primary source roots after semantics"):
    val code = "let broken=if then 11;elif false then ;else @bad;;let after=7;"
    for
      parsed <- parseNotFailed(code)
      ingested <- IO.blocking(IngestStage.fromSource(code, "ConditionalRecovery"))
      state <- compile(code)
    yield
      val original = parseErrors(binding(parsed, "broken").value)
      val stamped  = parseErrors(binding(ingested.module, "broken").value)
      val retained = parseErrors(binding(state.module, "broken").value)
      assertEquals(
        original.map(_.message),
        List(
          "Expected expression",
          "Expected expression",
          "Invalid expression syntax"
        )
      )
      assertEquals(
        original.map(_.spanOpt),
        List(
          pointAt(code, "then 11").some,
          pointAt(code, ";else").some,
          spanOf(code, "@bad").some
        )
      )
      assertEquals(original.map(_.failedCode), List(None, None, "@bad".some))
      assertEquals(stamped.map(_.copy(causes = Vector.empty)), original)
      assertEquals(retained.map(_.causes), stamped.map(_.causes))
      assertEquals(stamped.map(_.causes.size), List(1, 1, 1))
      assertEquals(stamped.flatMap(_.causes).distinct.size, 3)
      assertEquals(
        ingested.diagnostics.records.map(_.relation),
        Vector.fill(3)(DiagnosticRelation.Primary)
      )
      assertEquals(state.visibleErrors, ingested.visibleErrors)
      assert(
        ValueAvailability.fromModule(state.module).isAvailable(binding(state.module, "after").value)
      )

  private case class MissingCase(fragment: String, boundary: String, message: String)

  private val missingCases = List(
    MissingCase("if then 11;else 22;", "then", "Expected expression"),
    MissingCase("if true then ;else 22;", ";else", "Expected expression"),
    MissingCase("if true then 11;else ;", ";)", "Expected expression"),
    MissingCase("if true then 11 else 22;", "else", "Expected ';'"),
    MissingCase("if true else 22;", "else", "Expected 'then'")
  )

  missingCases.foreach { fixture =>
    test(s"zero-width ${fixture.message} survives ingest, semantics, and LSP: ${fixture.fragment}"):
      val code = s"let broken=(${fixture.fragment});let after=7;"
      for
        ingested <- IO.blocking(IngestStage.fromSource(code, "ConditionalRecovery"))
        state <- compile(code)
      yield
        val original     = onlyParseError(binding(ingested.module, "broken").value)
        val retained     = onlyParseError(binding(state.module, "broken").value)
        val expectedSpan = pointAt(code, fixture.boundary)
        assertEquals(original.message, fixture.message)
        assertEquals(original.failedCode, None)
        assertEquals(original.spanOpt, expectedSpan.some)
        assertEquals(original.causes.size, 1)
        assertEquals(retained, original)
        assertEquals(state.visibleErrors, ingested.visibleErrors)
        val diagnostics = Diagnostics.fromCompilerState(state)
        assertEquals(diagnostics.map(_.message), List(s"Parsing error: ${fixture.message}"))
        assertEquals(diagnostics.map(_.range), List(Range.fromSrcSpan(expectedSpan)))
        assert(diagnostics.forall(_.severity == DiagnosticSeverity.Error))
  }

  test("LSP reports exact failed source and independent reference ranges"):
    compile().map { state =>
      val diagnostics = Diagnostics.fromCompilerState(state)
      assertEquals(
        diagnostics.map(_.message),
        List("Parsing error: Invalid expression syntax", "Undefined reference: missing")
      )
      assertEquals(
        diagnostics.map(_.range),
        List("@", "missing").map(fragment => Range.fromSrcSpan(spanOf(source, fragment)))
      )
      assert(diagnostics.forall(_.severity == DiagnosticSeverity.Error))
    }

  test("CLI and quiet entrypoints retain diagnostic content and produce no artifacts"):
    withSourceFixture(source, "conditional-recovery.mml") { (path, config) =>
      for
        output <- cats.effect.Ref.of[IO, Vector[String]](Vector.empty)
        exit <- CompilerApi.processAstOnly(path, config, message => output.update(_ :+ message))
        messages <- output.get
        ast <- CompilerApi.processAstQuiet(path, config)
        ir <- CompilerApi.processIrQuiet(path, config)
        native <- CompilerApi.compileNativeQuiet(path, config)
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        assertEquals(exit, ExitCode.Error)
        val rendered = messages.mkString("\n")
        List("Parser error", "Undefined reference 'missing'").foreach { header =>
          assertEquals(rendered.linesIterator.count(_.contains(header)), 1, rendered)
        }
        assert(rendered.contains("Invalid expression syntax"), rendered)
        List("@", "missing").foreach { fragment =>
          assert(rendered.contains(ErrorPrinter.formatLocation(spanOf(source, fragment))), rendered)
          assert(
            rendered.contains(s"${Console.RED}${Console.BOLD}$fragment${Console.RESET}"),
            rendered
          )
        }
        val expected =
          "Parse error in expression: Invalid expression syntax\nUndefined reference: 'missing'".some
        assertEquals(ast.left.toOption, expected)
        assertEquals(ir.left.toOption, expected)
        assertEquals(native.left.toOption, expected)
        assert(!outputExists)
    }

  test("CLI renders a missing then at its original zero-width boundary"):
    val code = "let broken=(if true else 22;);let after=7;"
    withSourceFixture(code, "conditional-recovery.mml") { (path, config) =>
      for
        output <- cats.effect.Ref.of[IO, Vector[String]](Vector.empty)
        exit <- CompilerApi.processAstOnly(path, config, message => output.update(_ :+ message))
        messages <- output.get
        quiet <- CompilerApi.processAstQuiet(path, config)
      yield
        assertEquals(exit, ExitCode.Error)
        val rendered = messages.mkString("\n")
        assertEquals(rendered.linesIterator.count(_.contains("Parser error")), 1, rendered)
        assert(rendered.contains("Expected 'then'"), rendered)
        assert(rendered.contains(ErrorPrinter.formatLocation(pointAt(code, "else"))), rendered)
        assertEquals(quiet.left.toOption, "Parse error in expression: Expected 'then'".some)
    }

  test("raw diagnostics block codegen even when emission is manually requested"):
    withSourceFixture(source, "conditional-recovery.mml") { (_, config) =>
      for
        state <- compile(config = config)
        validated = CodegenStage.validate(state.copy(canEmitCode = true))
        emitted <- CodegenStage.emitIrOnly(validated)
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        assert(state.hasErrors)
        assertEquals(state.visibleErrors.size, 2)
        assert(state.errors.size > state.visibleErrors.size)
        assert(!validated.canEmitCode)
        assertEquals(validated.diagnostics, state.diagnostics)
        assertEquals(emitted.diagnostics, state.diagnostics)
        assertEquals(emitted.llvmIr, None)
        assertEquals(emitted.nativeResult, None)
        assertEquals(emitted.resolvedTriple, None)
        assert(!outputExists)
    }
