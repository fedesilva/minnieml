package mml.mmlclib.api

import cats.effect.IO
import cats.effect.Sync
import cats.syntax.all.*
import mml.mmlclib.ast.Module
import mml.mmlclib.codegen.CodeGenError
import mml.mmlclib.codegen.LlvmIrPrinter
import mml.mmlclib.parser.ParserError

import java.nio.file.Files
import java.nio.file.Path

// Define the errors that can occur during code generation.
enum CodeGenApiError:
  case CodeGenErrors(errors: List[CodeGenError])
  case CompilerErrors(errors: List[CompilerError])
  case Unknown(msg: String)

// API object for generating LLVM IR from a source string.
object CodeGenApi:

  /** Generate LLVM IR from a source string.
    *
    * This function first attempts to compile the source into a Module via CompilerApi. If
    * successful, it then runs the LLVM IR printer. All errors are captured in an Either.
    *
    * @param source
    *   the source code to compile
    * @param name
    *   an optional name for the module (defaults to "Anon")
    * @return
    *   an IO that, when run, yields either a CodeGenApiError or the generated LLVM IR string.
    */
  def generateFromString(
    source: String,
    name:   Option[String] = "Anon".some
  ): IO[Either[CodeGenApiError, String]] =
    for
      parsedModule <- CompilerApi.compileString(source, name)
      result <- parsedModule match
        case Right(module) =>
          // LlvmIrPrinter.printModule returns Either[CodeGenError, String].
          // We run it in a blocking IO and use .attempt to capture any thrown errors.
          IO.blocking(LlvmIrPrinter.printModule(module))
            .attempt
            .map {
              case Right(innerEither) =>
                // innerEither is Either[CodeGenError, String]; map any CodeGenError into our API error type.
                innerEither.leftMap(e => CodeGenApiError.CodeGenErrors(List(e)))
              case Left(throwable) =>
                // Wrap all thrown errors as Unknown using the throwable's message.
                CodeGenApiError.Unknown(throwable.getMessage).asLeft
            }
        case Left(error) =>
          // Wrap compiler errors as a CompilerErrors branch.
          IO.pure(CodeGenApiError.CompilerErrors(List(error)).asLeft)
    yield result
