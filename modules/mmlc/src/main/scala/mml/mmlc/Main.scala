package mml.mmlc

import cats.effect.{ExitCode, IO, IOApp}
import mml.mmlc.CommandLineConfig.{Command, Config}
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.dev.DevLoop
import mml.mmlclib.lsp.LspServer
import scopt.OParser

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    if args.contains("-h") || args.contains("--help") then
      IO.println(OParser.usage(CommandLineConfig.createParser)).as(ExitCode.Success)
    else parseAndProcessArgs(args)

  private def parseAndProcessArgs(args: List[String]): IO[ExitCode] =
    OParser.parse(CommandLineConfig.createParser, args, Config()) match
      case Some(config) =>
        config.command match
          case build: Command.Build =>
            build.file.fold(
              IO.println("Usage: mmlc [options] <source-file>\nRun 'mmlc -h' for help.")
                .as(ExitCode(1))
            ) { path =>
              val cfg =
                if build.targetType == "lib" then
                  CompilerConfig.library(
                    build.outputDir,
                    build.verbose,
                    build.targetTriple,
                    build.targetCpu,
                    build.noStackCheck,
                    build.emitOptIr,
                    build.noTco,
                    build.timings,
                    build.outputAst,
                    build.outputName,
                    build.printPhases,
                    build.optLevel,
                    build.emitScopedAlias,
                    build.asan
                  )
                else
                  CompilerConfig.exe(
                    build.outputDir,
                    build.verbose,
                    build.targetTriple,
                    build.targetCpu,
                    build.noStackCheck,
                    build.emitOptIr,
                    build.noTco,
                    build.timings,
                    build.outputAst,
                    build.outputName,
                    build.printPhases,
                    build.optLevel,
                    build.emitScopedAlias,
                    build.asan
                  )
              CompilerApi.processNative(path, cfg)
            }

          case run: Command.Run =>
            run.file.fold(
              IO.println("Error: Source file is required for run command").as(ExitCode(1))
            ) { path =>
              val cfg = CompilerConfig.exe(
                run.outputDir,
                run.verbose,
                run.targetTriple,
                run.targetCpu,
                run.noStackCheck,
                run.emitOptIr,
                run.noTco,
                run.timings,
                run.outputAst,
                run.outputName,
                run.printPhases,
                run.optLevel,
                run.emitScopedAlias,
                run.asan
              )
              CompilerApi.processRun(path, cfg)
            }

          case ast: Command.Ast =>
            ast.file.fold(
              IO.println("Error: Source file is required for ast command").as(ExitCode(1))
            ) { path =>
              val cfg = CompilerConfig.ast(ast.outputDir, ast.verbose, ast.timings, ast.noTco)
              CompilerApi.processAstOnly(path, cfg)
            }

          case ir: Command.Ir =>
            ir.file.fold(
              IO.println("Error: Source file is required for ir command").as(ExitCode(1))
            ) { path =>
              val cfg =
                CompilerConfig.ir(ir.outputDir, ir.verbose, ir.timings, ir.outputAst, ir.noTco)
              CompilerApi.processIrOnly(path, cfg)
            }

          case clean: Command.Clean =>
            CompilerApi.processClean(clean.outputDir)

          case i: Command.Info =>
            val cliInfo = CompilerApi.formatBuildInfo(
              "mmlc-cli",
              MmlcBuildInfo.version,
              MmlcBuildInfo.build,
              MmlcBuildInfo.gitSha,
              MmlcBuildInfo.os,
              MmlcBuildInfo.arch
            )
            IO.println(cliInfo).flatMap { _ =>
              CompilerApi.processInfo(i.diagnostics, i.showTriples)
            }

          case dev: Command.Dev =>
            dev.file.fold(
              IO.println("Error: Source file is required for dev command").as(ExitCode(1))
            ) { path =>
              val cfg = CompilerConfig.dev(verbose = dev.verbose)
              DevLoop.run(path, cfg)
            }

          case Command.Lsp() =>
            val cfg = CompilerConfig.dev(verbose = false)
            LspServer.run(cfg)

      case _ =>
        // Return success for help command
        if args.contains("--help") || args.contains("-h") then IO.pure(ExitCode.Success)
        else IO.pure(ExitCode(1))
