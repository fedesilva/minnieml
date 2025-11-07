package mml.mmlclib.api

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.CodeGenEffect
import mml.mmlclib.ast.Module
import mml.mmlclib.codegen.LlvmIrEmitter
import mml.mmlclib.codegen.emitter.CodeGenError

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
    * successful, it then runs the LLVM IR printer. All errors are captured in an EitherT.
    *
    * @param source
    *   the source code to compile
    * @param name
    *   the module name to associate with the parsed members
    * @return
    *   a CodeGenEffect that, when run, yields either a CodeGenApiError or the generated LLVM IR
    *   string.
    */
  def generateFromString(
    source: String,
    name:   String
  ): CodeGenEffect[String] =
    for
      // Convert CompilerEffect to CodeGenEffect by mapping errors
      parsedModule <- CompilerApi
        .compileString(source, name)
        .leftMap(error => CodeGenApiError.CompilerErrors(List(error)))
      // Run LLVM IR printer and handle potential errors
      result <- generateFromModule(parsedModule)
    yield result

  /** Generate LLVM IR from an existing module */
  def generateFromModule(module: Module): CodeGenEffect[String] =
    EitherT(
      IO.blocking(LlvmIrEmitter.module(module))
        .attempt
        .map {
          case Right(innerEither) =>
            // Map CodeGenError to CodeGenApiError
            innerEither.leftMap(e => CodeGenApiError.CodeGenErrors(List(e)))
          case Left(throwable) =>
            // Wrap thrown errors as Unknown
            CodeGenApiError.Unknown(throwable.getMessage).asLeft
        }
    )
