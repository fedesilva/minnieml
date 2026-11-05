package mml.mmlclib.util.error.print

import cats.effect.IO
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.ast.{SourceOrigin, SrcPoint, SrcSpan}
import mml.mmlclib.codegen.CompilationMode
import mml.mmlclib.compiler.CodegenStage
import mml.mmlclib.lsp.{Diagnostic, DiagnosticSeverity, Diagnostics, Range}
import mml.mmlclib.semantic.{EntryPointError, SemanticError}
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.diagnosticMessage

import java.nio.file.Files

class EntryPointMessageTests extends BaseEffFunSuite:

  /** Exercise one retained error through every presentation boundary and native failure gating. */
  private def checkPresentation(
    source:          String,
    expectedMessage: String,
    expectedSpan:    SrcSpan
  ): IO[Unit] =
    withSourceFixture(source, "entry-point.mml") { (path, initialConfig) =>
      val config = initialConfig.copy(mode = CompilationMode.Exe)

      for
        state <- semStateNotFailed(source)
        validated = CodegenStage.validate(state.copy(config = config))
        quiet <- CompilerApi.compileNativeQuiet(path, config)
        outputExists <- IO.blocking(Files.exists(config.outputDir))
      yield
        val error = validated.errors.toList match
          case (error: SemanticError.InvalidEntryPoint) :: Nil => error
          case errors => fail(s"Expected one entry-point diagnostic, got $errors")
        val excerpt     = source.substring(expectedSpan.start.index, expectedSpan.end.index)
        val cli         = ErrorPrinter.prettyPrint(error, Some(state.sourceInfo))
        val semanticCli = SemanticErrorPrinter.prettyPrint(List(error), Some(state.sourceInfo))

        assertEquals(error.error.source, SourceOrigin.Loc(expectedSpan))
        assertEquals(entryPointMessage(error.error), expectedMessage)
        assertEquals(diagnosticMessage(error), expectedMessage)
        assertEquals(validated.visibleErrors, validated.errors)
        assertEquals(validated.entryPoint, None)
        assert(!validated.canEmitCode)
        assertEquals(quiet.left.toOption, Some(expectedMessage))
        assert(!outputExists)

        List(cli, semanticCli).foreach { output =>
          assert(output.contains(expectedMessage), output)
          assert(output.contains(s"${Console.RED}${Console.BOLD}$excerpt${Console.RESET}"), output)
        }
        assert(cli.contains(ErrorPrinter.formatLocation(expectedSpan)), cli)
        assertEquals(
          Diagnostics.fromCompilerState(validated),
          List(
            Diagnostic(
              Range.fromSrcSpan(expectedSpan),
              DiagnosticSeverity.Error,
              s"Invalid entry point: $expectedMessage"
            )
          )
        )
    }

  test("missing entry points share wording and source evidence across outputs") {
    val source = "pub fn helper(): Int = 0;;"
    checkPresentation(
      source,
      "No entry point 'main' found for binary compilation",
      SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, source.length + 1, source.length))
    )
  }

  test("non-public entry points share wording and source evidence across outputs") {
    checkPresentation(
      "fn main(): Int = 0;;",
      "Entry point 'main' must be declared 'pub'",
      SrcSpan(SrcPoint(1, 4, 3), SrcPoint(1, 8, 7))
    )
  }

  test("invalid entry-point return types share wording and source evidence across outputs") {
    checkPresentation(
      "pub fn main(): String = \"hello\";;",
      "Entry point 'main' must have a return type of 'Unit', 'Int32', or 'Int64'",
      SrcSpan(SrcPoint(1, 8, 7), SrcPoint(1, 12, 11))
    )
  }

  test("synthetic entry-point failures retain plain wording without an editor range") {
    val error = SemanticError.InvalidEntryPoint(EntryPointError.NotFound(SourceOrigin.Synth))

    assertEquals(diagnosticMessage(error), "No entry point 'main' found for binary compilation")
    assertEquals(Diagnostics.fromErrorAll(error), Nil)
    assert(ErrorPrinter.prettyPrint(error).contains("[synthetic]"))
  }
