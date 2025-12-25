package mml.mmlc

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import mml.mmlc.CommandLineConfig.{Command, Config}
import mml.mmlclib.codegen.LlvmOrchestrator
import scopt.OParser

object Main extends IOApp:

  // Centralized function to print version information
  private def printVersionInfo: IO[Unit] =
    IO.println(s"""
                  |mmlc ${MmlcBuildInfo.version} 
                  |(build: ${MmlcBuildInfo.build}-${MmlcBuildInfo.gitSha})
                  |(${MmlcBuildInfo.os}-${MmlcBuildInfo.arch})
      """.stripMargin.replaceAll("\n", " ").trim)

  def run(args: List[String]): IO[ExitCode] =
    parseAndProcessArgs(args)

  def printSupportedTriples: IO[Unit] =
    IO.println(s"\n${Console.CYAN}Example Target Triples:${Console.RESET}") *>
      IO.println(s"  ${Console.RED}Cross compilation might not work${Console.RESET}") *>
      IO.println(s"  ${Console.YELLOW}macOS:${Console.RESET}") *>
      IO.println(s"    x86_64-apple-macosx     (Intel Mac)") *>
      IO.println(s"    aarch64-apple-macosx    (Apple Silicon)") *>
      IO.println(s"  ${Console.YELLOW}Linux:${Console.RESET}") *>
      IO.println(s"    x86_64-pc-linux-gnu     (x86_64 Linux)") *>
      IO.println(s"    aarch64-pc-linux-gnu    (ARM64 Linux)") *>
      IO.println(s"  ${Console.YELLOW}WebAssembly:${Console.RESET}") *>
      IO.println(s"    wasm32-unknown-unknown  (WebAssembly)") *>
      IO.println(s"\n  Use with: mmlc bin --target <triple> <source-file>")

  private def parseAndProcessArgs(args: List[String]): IO[ExitCode] =
    OParser.parse(CommandLineConfig.createParser, args, Config()) match
      case Some(config) =>
        config.command match
          case bin: Command.Bin =>
            bin.file.fold(
              IO.println("Error: Source file is required for bin command").as(ExitCode(1))
            )(path =>
              val moduleName = FileOperations.sanitizeFileName(path)
              CompilationPipeline.processBinary(path, moduleName, bin)
            )

          case run: Command.Run =>
            run.file.fold(
              IO.println("Error: Source file is required for run command").as(ExitCode(1))
            )(path =>
              val moduleName = FileOperations.sanitizeFileName(path)
              CompilationPipeline.processRun(path, moduleName, run)
            )

          case lib: Command.Lib =>
            lib.file.fold(
              IO.println("Error: Source file is required for lib command").as(ExitCode(1))
            )(path =>
              val moduleName = FileOperations.sanitizeFileName(path)
              CompilationPipeline.processLibrary(path, moduleName, lib)
            )

          case ast: Command.Ast =>
            ast.file.fold(
              IO.println("Error: Source file is required for ast command").as(ExitCode(1))
            )(path =>
              val moduleName = FileOperations.sanitizeFileName(path)
              CompilationPipeline.processAstOnly(path, moduleName, ast)
            )

          case ir: Command.Ir =>
            ir.file.fold(
              IO.println("Error: Source file is required for ir command").as(ExitCode(1))
            )(path =>
              val moduleName = FileOperations.sanitizeFileName(path)
              CompilationPipeline.processIrOnly(path, moduleName, ir)
            )

          case clean: Command.Clean =>
            FileOperations.cleanOutputDir(clean.outputDir)

          case i: Command.Info =>
            printVersionInfo *>
              printToolInfo(i.diagnostics) *>
              (if i.showTriples then printSupportedTriples else IO.unit) *>
              IO.println(
                "Use `mmlc --help` or `mmlc -h` for more information on available commands."
              ).as(ExitCode(0))

      case _ =>
        // Return success for help command
        if args.contains("--help") || args.contains("-h") then IO.pure(ExitCode.Success)
        else IO.pure(ExitCode(1))

  def printToolInfo(printDiagnostics: Boolean): IO[Unit] =
    if !printDiagnostics then IO.unit
    else
      LlvmOrchestrator.collectLlvmToolVersions.flatMap { info =>
        val printVersions =
          if info.versions.nonEmpty then
            IO.println(s"${Console.CYAN}LLVM Tool Versions:${Console.RESET}") *>
              info.versions.toList.traverse_ { case (tool, version) =>
                IO.println(s"  ${Console.YELLOW}$tool:${Console.RESET} $version")
              }
          else IO.println(s"${Console.RED}No LLVM tools found.${Console.RESET}")

        val printMissing =
          if info.missing.nonEmpty then
            IO.println(s"${Console.RED}Missing LLVM Tools:${Console.RESET}") *>
              info.missing.toList.traverse_ { tool =>
                IO.println(s"  ${Console.YELLOW}$tool${Console.RESET}")
              } *> IO.println(LlvmOrchestrator.llvmInstallInstructions.stripMargin)
          else IO.unit

        // Sequence both operations
        printVersions *> printMissing
      }
