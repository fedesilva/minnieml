package mml.mmlc

import cats.effect.std.Console
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import mml.mmlc.CommandLineConfig.{Command, Config}
import mml.mmlclib.api.CompilerApi
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.dev.DevLoop
import mml.mmlclib.lsp.LspServer
import scopt.{OEffect, OParser}

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    runWithOutput(args, IO.println(_), Console[IO].errorln(_))

  private[mmlc] def runWithOutput(
    args:   List[String],
    stdout: String => IO[Unit],
    stderr: String => IO[Unit]
  ): IO[ExitCode] =
    IO.defer {
      val (config, effects) = OParser.runParser(CommandLineConfig.createParser, args, Config())
      val termination = effects.collectFirst { case OEffect.Terminate(status) =>
        if status.isRight then ExitCode.Success else ExitCode(1)
      }
      effects
        .traverse_ {
          case OEffect.DisplayToOut(message) => stdout(message)
          case OEffect.DisplayToErr(message) => stderr(message)
          case OEffect.ReportError(message) => stderr(s"Error: $message")
          case OEffect.ReportWarning(message) => stderr(s"Warning: $message")
          case OEffect.Terminate(_) => IO.unit
        }
        .flatMap { _ =>
          termination.fold(processConfig(config, stdout))(IO.pure)
        }
    }

  private def processConfig(
    config: Option[Config],
    stdout: String => IO[Unit]
  ): IO[ExitCode] =
    config match
      case Some(config) =>
        config.command match
          case build: Command.Build =>
            build.file.fold(
              stdout("Usage: mmlc [options] <source-file>\nRun 'mmlc -h' for help.")
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
                    config.optLevel,
                    build.emitScopedAlias,
                    build.asan,
                    build.llvmOptArgs,
                    showParserMetrics = build.parserMetrics
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
                    config.optLevel,
                    build.emitScopedAlias,
                    build.asan,
                    build.llvmOptArgs,
                    showParserMetrics = build.parserMetrics
                  )
              CompilerApi.processNative(path, cfg)
            }

          case run: Command.Run =>
            run.file.fold(
              stdout("Error: Source file is required for run command").as(ExitCode(1))
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
                config.optLevel,
                run.emitScopedAlias,
                run.asan,
                run.llvmOptArgs,
                showParserMetrics = run.parserMetrics
              )
              CompilerApi.processRun(path, cfg)
            }

          case ast: Command.Ast =>
            ast.file.fold(
              stdout("Error: Source file is required for ast command").as(ExitCode(1))
            ) { path =>
              val cfg = CompilerConfig.ast(
                ast.outputDir,
                ast.verbose,
                ast.timings,
                ast.noTco,
                showParserMetrics = ast.parserMetrics
              )
              CompilerApi.processAstOnly(path, cfg)
            }

          case ir: Command.Ir =>
            ir.file.fold(
              stdout("Error: Source file is required for ir command").as(ExitCode(1))
            ) { path =>
              val cfg =
                CompilerConfig.ir(
                  ir.outputDir,
                  ir.verbose,
                  ir.timings,
                  ir.outputAst,
                  ir.noTco,
                  showParserMetrics = ir.parserMetrics
                )
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
            stdout(cliInfo).flatMap { _ =>
              CompilerApi.processInfo(i.diagnostics, i.showTriples)
            }

          case dev: Command.Dev =>
            dev.file.fold(
              stdout("Error: Source file is required for dev command").as(ExitCode(1))
            ) { path =>
              val cfg = CompilerConfig.dev(verbose = dev.verbose)
              DevLoop.run(path, cfg)
            }

          case Command.Lsp() =>
            val cfg = CompilerConfig.dev(verbose = false)
            LspServer.run(cfg)

      case None => IO.pure(ExitCode(1))
