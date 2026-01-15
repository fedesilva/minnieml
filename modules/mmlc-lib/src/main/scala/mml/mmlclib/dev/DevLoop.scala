package mml.mmlclib.dev

import cats.effect.{ExitCode, IO}
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.compiler.{Compilation, CompilerConfig, CompilerState, FileOperations}
import mml.mmlclib.errors.CompilationError
import mml.mmlclib.parser.{ParserError, SourceInfo}
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.util.error.print.ErrorPrinter

import java.nio.file.Path

object DevLoop:

  def run(path: Path, config: CompilerConfig): IO[ExitCode] =
    for
      _ <- initialCompile(path, config)
      _ <- runWatchLoop(path, config)
    yield ExitCode.Success

  private def initialCompile(path: Path, config: CompilerConfig): IO[Unit] =
    compileDev(path, config).flatMap {
      case Right(_) => printSuccess()
      case Left((errorMsg, _)) => printError(errorMsg)
    }

  private def runWatchLoop(path: Path, config: CompilerConfig): IO[Unit] =
    val singleIteration =
      for
        _ <- FileWatcher.watchForChanges(path)
        _ <- FileWatcher.printChangeDetected()
        result <- compileDev(path, config)
        _ <- result match
          case Right(_) => printSuccess()
          case Left((errorMsg, _)) => printError(errorMsg)
      yield ()

    singleIteration.foreverM

  private def printSuccess(): IO[Unit] =
    IO.println(s"${Console.GREEN}compiled, ok${Console.RESET}")

  private def printError(errorMsg: String): IO[Unit] =
    IO.println(errorMsg)

  private def compileDev(
    path:   Path,
    config: CompilerConfig
  ): IO[Either[(String, Option[CompilerState]), CompilerState]] =
    val moduleName = Compilation.moduleNameFromPath(path)
    val sourcePath = Compilation.sourcePathFromPath(path)
    for
      contentResult <- FileOperations.readFile(path)
      result <- contentResult match
        case Left(error) =>
          IO.pure(Left((s"Error reading file: ${error.getMessage}", None)))
        case Right(content) =>
          FrontEndApi.compile(content, moduleName, config, Some(sourcePath)).value.map {
            case Left(error) =>
              Left((ErrorPrinter.prettyPrint(error, Some(SourceInfo(content))), None))
            case Right(state) =>
              if state.hasErrors then Left((prettyPrintStateErrors(state), Some(state)))
              else Right(state)
          }
    yield result

  private def prettyPrintStateErrors(state: CompilerState): String =
    val errors   = compilerErrorsFromState(state)
    val messages = errors.map(error => ErrorPrinter.prettyPrint(error, Some(state.sourceInfo)))
    if messages.isEmpty then "No errors"
    else
      val fileHeader = state.module.sourcePath.map(path => s"File: $path")
      (fileHeader.toList ++ messages).mkString("\n\n")

  private def compilerErrorsFromState(state: CompilerState): List[CompilationError] =
    val parserErrors   = state.errors.collect { case err: ParserError => err }
    val semanticErrors = state.errors.collect { case err: SemanticError => err }
    val knownErrors = List(
      Option.when(parserErrors.nonEmpty)(
        mml.mmlclib.api.CompilerError.ParserErrors(parserErrors.toList)
      ),
      Option.when(semanticErrors.nonEmpty)(
        mml.mmlclib.api.CompilerError.SemanticErrors(semanticErrors.toList)
      )
    ).flatten
    val remaining = state.errors.filterNot {
      case _: ParserError => true
      case _: SemanticError => true
      case _ => false
    }
    knownErrors ++ remaining.toList
