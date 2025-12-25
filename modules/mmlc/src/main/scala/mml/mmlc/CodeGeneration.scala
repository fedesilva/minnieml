package mml.mmlc

import cats.effect.{ExitCode, IO}
import mml.mmlclib.api.{CodeGenApi, NativeEmitterError}
import mml.mmlclib.ast.Module
import mml.mmlclib.codegen.{CompilationMode, LlvmOrchestrator}
import mml.mmlclib.util.error.print.ErrorPrinter

object CodeGeneration:

  def generateNativeOutput(
    module:       Module,
    sourceCode:   String,
    outputDir:    String,
    mode:         CompilationMode = CompilationMode.Binary,
    verbose:      Boolean         = false,
    targetTriple: Option[String]  = None
  ): IO[ExitCode] =
    for
      // Generate LLVM IR directly from the module
      codeGenResult <- CodeGenApi.generateFromModule(module).value
      result <- codeGenResult match
        case Left(error) =>
          IO.pure(Left(NativeEmitterError.CodeGenErrors(List(error))))
        case Right(llvmIr) =>
          // Compile the generated LLVM IR, passing the module name
          LlvmOrchestrator
            .compile(llvmIr, module.name, outputDir, mode, verbose, targetTriple)
            .map {
              case Right(exitCode) => Right(exitCode)
              case Left(error) => Left(NativeEmitterError.LlvmErrors(List(error)))
            }
      exitCode <- result match
        case Right(code) =>
          val outputType = if mode == CompilationMode.Binary then "executable" else "library"
          IO.println(s"Native $outputType generation successful. Exit code: $code")
            .as(ExitCode.Success)
        case Left(error) =>
          IO.println(
            s"Native code generation failed: ${ErrorPrinter.prettyPrint(error, Some(sourceCode))}"
          ).as(ExitCode.Error)
    yield exitCode

  def generateAndRunBinary(
    module:       Module,
    sourceCode:   String,
    outputDir:    String,
    verbose:      Boolean        = false,
    targetTriple: Option[String] = None
  ): IO[ExitCode] =
    for
      // Generate LLVM IR directly from the module
      codeGenResult <- CodeGenApi.generateFromModule(module).value
      result <- codeGenResult match
        case Left(error) =>
          IO.pure(Left(NativeEmitterError.CodeGenErrors(List(error))))
        case Right(llvmIr) =>
          // Compile and run the generated LLVM IR
          LlvmOrchestrator
            .compileAndRun(llvmIr, module.name, outputDir, verbose, targetTriple)
            .map {
              case Right(exitCode) => Right(exitCode)
              case Left(error) => Left(NativeEmitterError.LlvmErrors(List(error)))
            }
      exitCode <- result match
        case Right(code) =>
          IO.pure(ExitCode(code))
        case Left(error) =>
          IO.println(
            s"Native code generation failed: ${ErrorPrinter.prettyPrint(error, Some(sourceCode))}"
          ).as(ExitCode.Error)
    yield exitCode

  def generateLlvmIr(
    module:     Module,
    sourceCode: String,
    outputDir:  String = "build"
  ): IO[ExitCode] =
    for
      codeGenResult <- CodeGenApi.generateFromModule(module).value
      exitCode <- codeGenResult match
        case Right(ir) if ir.nonEmpty =>
          // Write the IR to a file in the output directory
          val llvmFile = s"$outputDir/${module.name}.ll"
          IO.blocking {
            val dir = new java.io.File(outputDir)
            if !dir.exists() then dir.mkdirs()
            val writer = new java.io.PrintWriter(llvmFile)
            try writer.write(ir)
            finally writer.close()
          } *> IO.println(s"LLVM IR written to $llvmFile").as(ExitCode.Success)
        case Left(error) =>
          IO.println(
            s"Code generation failed: ${ErrorPrinter.prettyPrint(error, Some(sourceCode))}"
          ).as(ExitCode.Error)
        case _ =>
          IO.unit.as(ExitCode.Success)
    yield exitCode
