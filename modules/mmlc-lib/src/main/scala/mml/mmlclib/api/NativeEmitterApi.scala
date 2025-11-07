package mml.mmlclib.api

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.EmitterEffect
import mml.mmlclib.codegen.{CompilationMode, LlvmCompilationError, LlvmOrchestrator}

// Define possible errors during native code emission
enum NativeEmitterError derives CanEqual:
  case CompilationErrors(errors: List[CompilerError])
  case CodeGenErrors(errors: List[CodeGenApiError])
  case LlvmErrors(errors: List[LlvmCompilationError])
  case Unknown(msg: String)

/** API for end-to-end compilation from MML source to native code */
object NativeEmitterApi:

  /** Compile MML source code to native executable or library
    *
    * This function orchestrates the entire compilation pipeline:
    *   1. Parse and semantically analyze MML source via CompilerApi
    *   2. Generate LLVM IR via CodeGenApi
    *   3. Compile LLVM IR to native code via LLVMOrchestrator
    *
    * @param source
    *   The MML source code
    * @param workingDirectory
    *   Directory where compilation artifacts will be stored
    * @param mode
    *   Whether to compile to an executable binary or a library
    * @param moduleName
    *   Module name for the compilation
    * @return
    *   An EmitterEffect that, when run, yields either a NativeEmitterError or the exit code of the
    *   compilation
    */
  def compileToNative(
    source:           String,
    workingDirectory: String,
    mode:             CompilationMode = CompilationMode.Binary,
    moduleName:       String
  ): EmitterEffect[Int] =
    for
      // Step 1: Generate LLVM IR with CodeGenApi
      llvmIr <- CodeGenApi
        .generateFromString(source, moduleName)
        .leftMap(error => NativeEmitterError.CodeGenErrors(List(error)))

      // Step 2: Compile LLVM IR to native code
      exitCode <- compileLlvmIR(llvmIr, moduleName, workingDirectory, mode)
    yield exitCode

  /** Compile MML source code to Llvm IR and save it to a file
    *
    * @param source
    *   The MML source code
    * @param outputPath
    *   Path where the Llvm IR file will be saved
    * @param moduleName
    *   Module name for the compilation
    * @return
    *   An EmitterEffect that, when run, yields either a NativeEmitterError or a success message
    */
  def emitLlvmIR(
    source:     String,
    outputPath: String,
    moduleName: String
  ): EmitterEffect[String] =
    for
      // Generate LLVM IR
      llvmIr <-
        CodeGenApi
          .generateFromString(source, moduleName)
          .leftMap(error => NativeEmitterError.CodeGenErrors(List(error)))

      // Save LLVM IR to file
      result <- EitherT(IO {
        try
          import java.io.{File, PrintWriter}
          val writer = new PrintWriter(new File(outputPath))
          writer.write(llvmIr)
          writer.close()
          s"LLVM IR successfully written to $outputPath".asRight
        catch
          case e: Exception =>
            NativeEmitterError
              .Unknown(s"Failed to write LLVM IR to file: ${e.getMessage}")
              .asLeft
      })
    yield result

  /** Compile existing LLVM IR to native code
    *
    * @param llvmIr
    *   The LLVM IR code as a string
    * @param workingDirectory
    *   Directory where compilation artifacts will be stored
    * @param mode
    *   Whether to compile to an executable binary or a library
    * @return
    *   An EmitterEffect that, when run, yields either a NativeEmitterError or the exit code of the
    *   compilation
    */
  private def compileLlvmIR(
    llvmIr:           String,
    moduleName:       String,
    workingDirectory: String,
    mode:             CompilationMode
  ): EmitterEffect[Int] =
    EitherT(
      LlvmOrchestrator
        .compile(llvmIr, moduleName, workingDirectory, mode)
        .map {
          case Right(exitCode) => exitCode.asRight
          case Left(error) => NativeEmitterError.LlvmErrors(List(error)).asLeft
        }
    )
