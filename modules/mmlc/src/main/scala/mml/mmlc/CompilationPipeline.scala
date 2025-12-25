package mml.mmlc

import cats.effect.{ExitCode, IO}
import mml.mmlc.CommandLineConfig.Command
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.ast.Module
import mml.mmlclib.codegen.CompilationMode
import mml.mmlclib.semantic.{PreCodegenValidator, SemanticPhaseState}
import mml.mmlclib.util.error.print.ErrorPrinter

import java.nio.file.Path

object CompilationPipeline:

  def compilationFailed(error: String): String =
    s"\n${Console.RED}Compilation failed:${Console.RESET}\n\n" +
      s"${Console.YELLOW}$error${Console.RESET}\n"

  private def compileModule(
    path:       Path,
    moduleName: String
  ): IO[Either[String, (SemanticPhaseState, String)]] =
    for
      contentResult <- FileOperations.readFile(path)
      result <- contentResult match
        case Left(error) =>
          IO.pure(Left(s"Error reading file: ${error.getMessage}"))
        case Right(content) =>
          // Pass source code directly to error printer instead of setting current file
          CompilerApi.compileString(content, moduleName).value.map {
            case Left(compilerError) =>
              Left(ErrorPrinter.prettyPrint(compilerError, Some(content)))
            case Right(semanticState) => Right((semanticState, content))
          }
    yield result

  def processBinary(path: Path, moduleName: String, config: Command.Bin): IO[ExitCode] =
    for
      semanticStateResult <- compileModule(path, moduleName)
      exitCode <- semanticStateResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right((semanticState, sourceCode)) =>
          // Mode-specific validation lives here because SemanticApi is mode-agnostic.
          val validatedState = PreCodegenValidator.validate(CompilationMode.Binary)(semanticState)
          if validatedState.errors.nonEmpty then
            IO.println(
              compilationFailed(
                ErrorPrinter.prettyPrintSemanticErrors(
                  validatedState.errors.toList,
                  Some(sourceCode)
                )
              )
            ).as(ExitCode.Error)
          else processBinaryModule(validatedState.module, sourceCode, config)
    yield exitCode

  private def processBinaryModule(
    module:     Module,
    sourceCode: String,
    config:     Command.Bin
  ): IO[ExitCode] =
    for
      // Write AST to file if requested
      _ <-
        if config.outputAst then FileOperations.writeAstToFile(module, config.outputDir)
        else IO.unit
      // Generate native executable
      exitCode <- CodeGeneration.generateNativeOutput(
        module,
        sourceCode,
        config.outputDir,
        CompilationMode.Binary,
        config.verbose,
        config.targetTriple
      )
    yield exitCode

  def processRun(path: Path, moduleName: String, config: Command.Run): IO[ExitCode] =
    for
      semanticStateResult <- compileModule(path, moduleName)
      exitCode <- semanticStateResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right((semanticState, sourceCode)) =>
          // Mode-specific validation lives here because SemanticApi is mode-agnostic.
          val validatedState = PreCodegenValidator.validate(CompilationMode.Binary)(semanticState)
          if validatedState.errors.nonEmpty then
            IO.println(
              compilationFailed(
                ErrorPrinter.prettyPrintSemanticErrors(
                  validatedState.errors.toList,
                  Some(sourceCode)
                )
              )
            ).as(ExitCode.Error)
          else processBinaryModuleAndRun(validatedState.module, sourceCode, config)
    yield exitCode

  private def processBinaryModuleAndRun(
    module:     Module,
    sourceCode: String,
    config:     Command.Run
  ): IO[ExitCode] =
    for
      // Write AST to file if requested
      _ <-
        if config.outputAst then FileOperations.writeAstToFile(module, config.outputDir)
        else IO.unit
      // Generate native executable and run it
      exitCode <- CodeGeneration.generateAndRunBinary(
        module,
        sourceCode,
        config.outputDir,
        config.verbose,
        config.targetTriple
      )
    yield exitCode

  def processLibrary(path: Path, moduleName: String, config: Command.Lib): IO[ExitCode] =
    for
      semanticStateResult <- compileModule(path, moduleName)
      exitCode <- semanticStateResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right((semanticState, sourceCode)) =>
          // Mode-specific validation lives here because SemanticApi is mode-agnostic.
          val validatedState = PreCodegenValidator.validate(CompilationMode.Library)(semanticState)
          if validatedState.errors.nonEmpty then
            IO.println(
              compilationFailed(
                ErrorPrinter.prettyPrintSemanticErrors(
                  validatedState.errors.toList,
                  Some(sourceCode)
                )
              )
            ).as(ExitCode.Error)
          else processLibraryModule(validatedState.module, sourceCode, config)
    yield exitCode

  private def processLibraryModule(
    module:     Module,
    sourceCode: String,
    config:     Command.Lib
  ): IO[ExitCode] =
    for
      // Write AST to file if requested
      _ <-
        if config.outputAst then FileOperations.writeAstToFile(module, config.outputDir)
        else IO.unit
      // Generate library
      exitCode <- CodeGeneration.generateNativeOutput(
        module,
        sourceCode,
        config.outputDir,
        CompilationMode.Library,
        config.verbose,
        config.targetTriple
      )
    yield exitCode

  def processAstOnly(path: Path, moduleName: String, config: Command.Ast): IO[ExitCode] =
    for
      semanticStateResult <- compileModule(path, moduleName)
      exitCode <- semanticStateResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right((semanticState, sourceCode)) =>
          // Mode-specific validation lives here because SemanticApi is mode-agnostic.
          val validatedState = PreCodegenValidator.validate(CompilationMode.Ast)(semanticState)
          if validatedState.errors.nonEmpty then
            IO.println(
              compilationFailed(
                ErrorPrinter.prettyPrintSemanticErrors(
                  validatedState.errors.toList,
                  Some(sourceCode)
                )
              )
            ).as(ExitCode.Error)
          else
            // Write AST to file and exit
            FileOperations
              .writeAstToFile(validatedState.module, config.outputDir)
              .as(ExitCode.Success)
    yield exitCode

  def processIrOnly(path: Path, moduleName: String, config: Command.Ir): IO[ExitCode] =
    for
      semanticStateResult <- compileModule(path, moduleName)
      exitCode <- semanticStateResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right((semanticState, sourceCode)) =>
          // Mode-specific validation lives here because SemanticApi is mode-agnostic.
          val validatedState = PreCodegenValidator.validate(CompilationMode.Ir)(semanticState)
          if validatedState.errors.nonEmpty then
            IO.println(
              compilationFailed(
                ErrorPrinter.prettyPrintSemanticErrors(
                  validatedState.errors.toList,
                  Some(sourceCode)
                )
              )
            ).as(ExitCode.Error)
          else processIrModule(validatedState.module, sourceCode, config)
    yield exitCode

  private def processIrModule(
    module:     Module,
    sourceCode: String,
    config:     Command.Ir
  ): IO[ExitCode] =
    for
      // Write AST to file if requested
      _ <-
        if config.outputAst then FileOperations.writeAstToFile(module, config.outputDir)
        else IO.unit
      // Generate LLVM IR only
      exitCode <- CodeGeneration.generateLlvmIr(module, sourceCode, config.outputDir)
    yield exitCode
