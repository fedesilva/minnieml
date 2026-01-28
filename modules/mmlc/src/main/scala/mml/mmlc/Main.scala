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
    parseAndProcessArgs(args)

  private def parseAndProcessArgs(args: List[String]): IO[ExitCode] =
    OParser.parse(CommandLineConfig.createParser, args, Config()) match
      case Some(config) =>
        config.command match
          case bin: Command.Bin =>
            bin.file.fold(
              IO.println("Error: Source file is required for bin command").as(ExitCode(1))
            ) { path =>
              val cfg = CompilerConfig.binary(
                bin.outputDir,
                bin.verbose,
                bin.targetTriple,
                bin.targetCpu,
                bin.noStackCheck,
                bin.emitOptIr,
                bin.noTco,
                bin.timings,
                bin.outputAst,
                bin.outputName,
                bin.printPhases,
                bin.optLevel,
                bin.emitScopedAlias
              )
              CompilerApi.processNative(path, cfg)
            }

          case run: Command.Run =>
            run.file.fold(
              IO.println("Error: Source file is required for run command").as(ExitCode(1))
            ) { path =>
              val cfg = CompilerConfig.binary(
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
                run.emitScopedAlias
              )
              CompilerApi.processRun(path, cfg)
            }

          case lib: Command.Lib =>
            lib.file.fold(
              IO.println("Error: Source file is required for lib command").as(ExitCode(1))
            ) { path =>
              val cfg = CompilerConfig.library(
                lib.outputDir,
                lib.verbose,
                lib.targetTriple,
                lib.targetCpu,
                lib.noStackCheck,
                lib.emitOptIr,
                lib.noTco,
                lib.timings,
                lib.outputAst,
                lib.outputName,
                lib.printPhases,
                lib.optLevel,
                lib.emitScopedAlias
              )
              CompilerApi.processNative(path, cfg)
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
