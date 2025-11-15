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
  ): IO[Either[String, SemanticPhaseState]] = // Changed return type to SemanticPhaseState
    for
      contentResult <- FileOperations.readFile(path)
      result <- contentResult match
        case Left(error) =>
          IO.pure(Left(s"Error reading file: ${error.getMessage}"))
        case Right(content) =>
          // Pass source code directly to error printer instead of setting current file
          CompilerApi.compileString(content, moduleName).value.map { // Removed mode parameter
            case Left(compilerError) =>
              Left(ErrorPrinter.prettyPrint(compilerError, Some(content)))
            case Right(semanticState) => Right(semanticState) // Return SemanticPhaseState
          }
    yield result

  def processBinary(path: Path, moduleName: String, config: Command.Bin): IO[ExitCode] =
    for
      semanticStateResult <- compileModule(path, moduleName) // Removed mode parameter
      exitCode <- semanticStateResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right(semanticState) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Binary)(semanticState)
          if validatedState.errors.nonEmpty then
            IO.println(
              compilationFailed(
                ErrorPrinter.prettyPrintSemanticErrors(validatedState.errors.toList, None)
              )
            ).as(ExitCode.Error)
          else processBinaryModule(validatedState.module, config)
    yield exitCode

  private def processBinaryModule(module: Module, config: Command.Bin): IO[ExitCode] =
    for
      // Write AST to file if requested
      _ <-
        if config.outputAst then FileOperations.writeAstToFile(module, config.outputDir)
        else IO.unit
      // Generate native executable
      exitCode <- CodeGeneration.generateNativeOutput(
        module,
        config.outputDir,
        CompilationMode.Binary,
        config.verbose,
        config.targetTriple
      )
    yield exitCode

  def processLibrary(path: Path, moduleName: String, config: Command.Lib): IO[ExitCode] =
    for
      semanticStateResult <- compileModule(path, moduleName) // Removed mode parameter
      exitCode <- semanticStateResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right(semanticState) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Library)(semanticState)
          if validatedState.errors.nonEmpty then
            IO.println(
              compilationFailed(
                ErrorPrinter.prettyPrintSemanticErrors(validatedState.errors.toList, None)
              )
            ).as(ExitCode.Error)
          else processLibraryModule(validatedState.module, config)
    yield exitCode

  private def processLibraryModule(module: Module, config: Command.Lib): IO[ExitCode] =
    for
      // Write AST to file if requested
      _ <-
        if config.outputAst then FileOperations.writeAstToFile(module, config.outputDir)
        else IO.unit
      // Generate library
      exitCode <- CodeGeneration.generateNativeOutput(
        module,
        config.outputDir,
        CompilationMode.Library,
        config.verbose,
        config.targetTriple
      )
    yield exitCode

  def processAstOnly(path: Path, moduleName: String, config: Command.Ast): IO[ExitCode] =
    for
      semanticStateResult <- compileModule(path, moduleName) // Removed mode parameter
      exitCode <- semanticStateResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right(semanticState) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Ast)(semanticState)
          if validatedState.errors.nonEmpty then
            IO.println(
              compilationFailed(
                ErrorPrinter.prettyPrintSemanticErrors(validatedState.errors.toList, None)
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
      semanticStateResult <- compileModule(path, moduleName) // Removed mode parameter
      exitCode <- semanticStateResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right(semanticState) =>
          val validatedState = PreCodegenValidator.validate(CompilationMode.Ir)(semanticState)
          if validatedState.errors.nonEmpty then
            IO.println(
              compilationFailed(
                ErrorPrinter.prettyPrintSemanticErrors(validatedState.errors.toList, None)
              )
            ).as(ExitCode.Error)
          else processIrModule(validatedState.module, config)
    yield exitCode

  private def processIrModule(module: Module, config: Command.Ir): IO[ExitCode] =
    for
      // Write AST to file if requested
      _ <-
        if config.outputAst then FileOperations.writeAstToFile(module, config.outputDir)
        else IO.unit
      // Generate LLVM IR only
      exitCode <- CodeGeneration.generateLlvmIr(module, config.outputDir)
    yield exitCode
