package mml.mmlclib.lsp

import cats.effect.{ExitCode, IO}
import cats.syntax.option.*
import mml.mmlclib.api.{CompilerApi, CompilerError}
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.LlvmCompilationError
import mml.mmlclib.codegen.emitter.CodeGenError
import mml.mmlclib.compiler.{Compilation, CompilerConfig}
import mml.mmlclib.errors.CompilationError
import mml.mmlclib.parser.{ParserError, SourceInfo}
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.{ErrorPrinter, diagnosticMessage}

class DiagnosticsTests extends BaseEffFunSuite:

  test("error families outside editor conversion remain omitted"):
    parseNotFailed("let value = 1;").map { module =>
      val extensionError = new CompilationError {}

      val errors = List(
        ParserError.Failure("parse failure"),
        ParserError.Unknown("unknown parse failure"),
        CompilerError.Unknown("frontend failure"),
        CodeGenError(
          mml.mmlclib.codegen.emitter.CodeGenReason.InvalidExpressionStructure,
          module.some
        ),
        LlvmCompilationError.TargetResolutionError(
          mml.mmlclib.codegen.TargetFailure.UnknownHostArchitecture
        ),
        extensionError
      )
      errors.foreach { error =>
        assertEquals(Diagnostics.fromError(error), None, diagnosticMessage(error))
        assertEquals(Diagnostics.fromErrorAll(error), Nil, diagnosticMessage(error))
      }
    }

  test("direct and wrapped type errors preserve the same editor diagnostic"):
    semState("let value: Int = true;").map { result =>
      val errors = result.errors.collect { case error: SemanticError.TypeCheckingError => error }
      assertEquals(errors.size, 1)
      val wrapped = errors.head
      val direct  = Diagnostics.fromErrorAll(wrapped.error)
      assertEquals(direct.size, 1)
      assertEquals(direct.head.message, "Type mismatch: expected Int, got Bool")
      assertEquals(Diagnostics.fromErrorAll(wrapped), direct)
    }

  test("LSP compilation reports a moved capture at its use with the existing CLI evidence"):
    val code = """fn main(): Unit =
                 |  let s = "hello" ++ " world";
                 |  let f = ~{ println s; }: Unit;
                 |  let g = ~{ println s; }: Unit;
                 |  f ();
                 |  g ();
                 |;
                 |""".stripMargin
    val info         = SourceInfo(code)
    val start        = code.lastIndexOf("println s") + "println ".length
    val expectedSpan = SrcSpan(info.pointAt(start), info.pointAt(start + 1))

    withSourceFixture(code, "moved-capture.mml") { (path, config) =>
      for
        state <- Compilation.compileSource(code, "MovedCapture", path.toString, config)
        // Capture CLI output without redirecting the shared test-process console.
        output <- cats.effect.Ref.of[IO, Vector[String]](Vector.empty)
        exit <- CompilerApi.processAstOnly(path, config, message => output.update(_ :+ message))
        messages <- output.get
      yield
        val errors = state.visibleErrors.collect { case e: SemanticError.CapturedMovedHeapBinding =>
          e
        }
        assertEquals(errors.size, 1)
        val error = errors.head
        assertEquals(error.ref.spanOpt, expectedSpan.some)
        val expected = Diagnostic(
          range    = Range.fromSrcSpan(expectedSpan),
          severity = DiagnosticSeverity.Error,
          message  = diagnosticMessage(error)
        )
        assertEquals(Diagnostics.fromErrorAll(error), List(expected))
        assertEquals(
          errors.toList.flatMap(Diagnostics.fromErrorAll),
          List(expected)
        )

        assertEquals(exit, ExitCode.Error)
        val movedSpan = error.movedAt.spanOpt.getOrElse(fail("Expected the first capture location"))
        val cli       = messages.mkString("\n")
        val captureReport = ErrorPrinter.prettyPrint(error, info.some)
        assert(cli.contains(captureReport), cli)
        val captureLocation = ErrorPrinter.formatLocation(expectedSpan)
        assert(
          captureReport.contains(diagnosticMessage(error)) &&
            captureReport.contains(s"Use at: $captureLocation"),
          captureReport
        )
        assert(
          captureReport.contains(s"Moved at: ${ErrorPrinter.formatLocation(movedSpan)}"),
          captureReport
        )
        val highlightedCapture =
          s"let g = ~{ println ${Console.RED}${Console.BOLD}s${Console.RESET};"
        assert(captureReport.contains(highlightedCapture), captureReport)
        assertEquals(
          Diagnostics.fromErrorAll(error.copy(ref = error.ref.copy(source = SourceOrigin.Synth))),
          Nil
        )
    }

  test("visibility diagnostic presentation uses the reference location and existing message"):
    val code         = "let hidden = 1;\nlet use = hidden;"
    val info         = SourceInfo(code)
    val start        = code.lastIndexOf("hidden")
    val expectedSpan = SrcSpan(info.pointAt(start), info.pointAt(start + "hidden".length))

    parseNotFailed(code).map { module =>
      val (declaration, use) = module.members match
        case List(declaration: Bnd, use: Bnd) => (declaration, use)
        case other => fail(s"Expected two source bindings, got $other")
      val ref = use.value.terms match
        case List(ref: Ref) => ref
        case other => fail(s"Expected the reference to hidden, got $other")
      // Visibility enforcement does not yet emit this diagnostic; test its presentation contract.
      val error: SemanticError.VisibilityViolation =
        SemanticError.VisibilityViolation(ref, declaration, "test")
      val expected = Diagnostic(
        range    = Range.fromSrcSpan(expectedSpan),
        severity = DiagnosticSeverity.Error,
        message  = "'hidden' is not visible here (declared in 'hidden')"
      )
      assertEquals(Diagnostics.fromError(error), expected.some)
      assertEquals(Diagnostics.fromErrorAll(error), List(expected))
      val cli = ErrorPrinter.prettyPrint(error, info.some)
      assert(cli.contains("'hidden' is not visible here (declared in 'hidden')"), cli)
      val highlightedReference = s"let use = ${Console.RED}${Console.BOLD}hidden${Console.RESET};"
      assert(cli.contains(highlightedReference), cli)
      assertEquals(
        Diagnostics.fromErrorAll(error.copy(ref = ref.copy(source = SourceOrigin.Synth))),
        Nil
      )
    }

  test("LSP compilation reports nested invalid identifiers with the parser source span"):
    val code = "let x = let 123invalid = 5; 6;"

    Compilation
      .compileSource(code, "NestedParseError", "nested-parse-error.mml", CompilerConfig.default)
      .map { state =>
        val error = state.visibleErrors.toList match
          case List(SemanticError.ParseTermErrorFound(error, _)) => error
          case other => fail(s"Expected only the nested parse error, got $other")
        val span  = error.spanOpt.getOrElse(fail("Expected nested parse error source span"))
        val start = code.indexOf("123invalid")
        val info  = SourceInfo(code)
        assertEquals(span, SrcSpan(info.pointAt(start), info.pointAt(start + "123invalid".length)))
        assertEquals(error.failedCode, "123invalid".some)
        val diagnostics = Diagnostics.fromCompilerState(state)
        assertEquals(diagnostics.size, 1)
        assertEquals(diagnostics.head.range, Range.fromSrcSpan(span))
        assertEquals(diagnostics.head.severity, DiagnosticSeverity.Error)
        assertEquals(diagnostics.head.message, "Parsing error: Invalid identifier '123invalid'")
      }

  test("LSP compilation retains independent type and call-once diagnostics"):
    val code = """
      fn take(~text: String, extra: Int): Int = text.length + extra;;
      fn make(~text: String): Int -> Int =
        let consuming = take text;
        ~{ extra: Int -> consuming extra; };
      ;
      fn example(): Unit =
        let pap = make (int_to_str 123);
        let first = pap 1;
        pap 2;
        ();
      ;
    """

    Compilation
      .compileSource(code, "Recovery", "/tmp/recovery.mml", CompilerConfig.default)
      .map { state =>
        val diagnostics = Diagnostics.fromCompilerState(state)
        assertEquals(diagnostics.size, 2, diagnostics)
        assertEquals(
          state.visibleErrors.count {
            case SemanticError.TypeCheckingError(_: mml.mmlclib.semantic.TypeError.TypeMismatch) =>
              true
            case _ => false
          },
          1
        )
        assertEquals(state.visibleErrors.count(_.isInstanceOf[SemanticError.UseAfterMove]), 1)
        val callLine = code.linesIterator.zipWithIndex
          .collectFirst {
            case (line, index) if line.trim == "pap 2;" => index
          }
          .getOrElse(fail("Expected second call in fixture"))
        assert(diagnostics.forall(_.range.start.line == callLine), diagnostics)
        val tokens     = SemanticTokens.compute(state.module).data.grouped(5).toList
        val tokenLines = tokens.scanLeft(0)((line, token) => line + token.head).tail
        assert(
          tokens.zip(tokenLines).exists { (token, line) =>
            line == callLine && token(3) == TokenType.Number.ordinal
          },
          "Expected semantic highlighting inside the failed statement"
        )
      }

  test("duplicate diagnostic prefers the first real source span when synthetic entries exist"):
    val code =
      """
      fn print(a: String): Unit = ();;
      """

    semState(code).map { result =>
      val duplicateError =
        result.errors.collectFirst { case e: SemanticError.DuplicateName => e }.getOrElse {
          fail("Expected DuplicateName error from redefining stdlib print")
        }

      val diagnostic = Diagnostics.fromError(duplicateError).getOrElse {
        fail("Expected diagnostic for duplicate name with a real user declaration")
      }

      val expectedSpan = duplicateError.duplicates.iterator
        .collect { case fs: FromSource => fs.source.spanOpt }
        .collectFirst { case Some(span) => span }
        .getOrElse(fail("Expected at least one real source span in duplicate group"))
      assertEquals(diagnostic.range, Range.fromSrcSpan(expectedSpan))
    }

  test("duplicate diagnostics are omitted when all duplicates are synthetic"):
    val duplicateError = SemanticError.DuplicateName(
      name       = "x",
      duplicates = List(mkSyntheticBinding("x", 1), mkSyntheticBinding("x", 2)),
      phase      = "test"
    )

    assertEquals(Diagnostics.fromError(duplicateError), None)

  test("duplicate diagnostics emit one item per real duplicate span"):
    val code =
      """
      let a = 1;
      let a = 2;
      let a = 3;
      """

    semState(code).map { result =>
      val duplicateError =
        result.errors
          .collectFirst { case e: SemanticError.DuplicateName if e.name == "a" => e }
          .getOrElse(fail("Expected DuplicateName error for 'a'"))

      val expectedRanges = duplicateError.duplicates.iterator
        .collect { case fs: FromSource => fs.source.spanOpt }
        .collect { case Some(span) => Range.fromSrcSpan(span) }
        .toList

      val actualRanges = Diagnostics
        .fromErrorAll(duplicateError)
        .map(_.range)

      assertEquals(actualRanges, expectedRanges)
    }

  private def mkSyntheticBinding(name: String, value: Int): Bnd =
    val synthSpan = SrcSpan(SrcPoint(0, 0, -1), SrcPoint(0, 0, -1))
    Bnd(
      source   = SourceOrigin.Synth,
      nameNode = Name.synth(name),
      value    = Expr(synthSpan, List(LiteralInt(synthSpan, value)))
    )
