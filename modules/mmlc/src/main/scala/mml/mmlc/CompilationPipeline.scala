package mml.mmlc

import cats.effect.{ExitCode, IO}
import mml.mmlc.CommandLineConfig.Command
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.ast.Module
import mml.mmlclib.codegen.CompilationMode
import mml.mmlclib.util.prettyprint.error.ErrorPrinter

import java.nio.file.Path

object CompilationPipeline:

  def compilationFailed(error: String): String =
    s"${Console.RED}Compilation failed:${Console.RESET}\n" +
      s"${Console.YELLOW} $error${Console.RESET}"

  private def compileModule(
    path:       Path,
    moduleName: String
  ): IO[Either[String, Module]] =
    for
      contentResult <- FileOperations.readFile(path)
      result <- contentResult match
        case Left(error) =>
          IO.pure(Left(s"Error reading file: ${error.getMessage}"))
        case Right(content) =>
          // Pass source code directly to error printer instead of setting current file
          CompilerApi.compileString(content, Some(moduleName)).value.map {
            case Left(compilerError) =>
              Left(ErrorPrinter.prettyPrint(compilerError, Some(content)))
            case Right(module) => Right(module)
          }
    yield result

  def processBinary(path: Path, moduleName: String, config: Command.Bin): IO[ExitCode] =
    for
      moduleResult <- compileModule(path, moduleName)
      exitCode <- moduleResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right(module) =>
          processBinaryModule(module, config)
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
        config.verbose
      )
    yield exitCode

  def processLibrary(path: Path, moduleName: String, config: Command.Lib): IO[ExitCode] =
    for
      moduleResult <- compileModule(path, moduleName)
      exitCode <- moduleResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right(module) =>
          processLibraryModule(module, config)
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
        config.verbose
      )
    yield exitCode

  def processAstOnly(path: Path, moduleName: String, config: Command.Ast): IO[ExitCode] =
    for
      moduleResult <- compileModule(path, moduleName)
      exitCode <- moduleResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right(module) =>
          // Write AST to file and exit
          FileOperations.writeAstToFile(module, config.outputDir).as(ExitCode.Success)
    yield exitCode

  def processIrOnly(path: Path, moduleName: String, config: Command.Ir): IO[ExitCode] =
    for
      moduleResult <- compileModule(path, moduleName)
      exitCode <- moduleResult match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(ExitCode.Error)
        case Right(module) =>
          processIrModule(module, config)
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
