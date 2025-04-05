package mml.mmlc

import scopt.OParser

import java.nio.file.{Path, Paths}

object CommandLineConfig:

  enum Command:
    case Bin(
      file:      Option[Path] = None,
      outputDir: String       = "build",
      outputAst: Boolean      = false,
      verbose:   Boolean      = false
    )
    case Lib(
      file:      Option[Path] = None,
      outputDir: String       = "build",
      outputAst: Boolean      = false,
      verbose:   Boolean      = false
    )
    case Ast(
      file:      Option[Path] = None,
      outputDir: String       = "build",
      verbose:   Boolean      = false
    )
    case Ir(
      file:      Option[Path] = None,
      outputDir: String       = "build",
      outputAst: Boolean      = false,
      verbose:   Boolean      = false
    )
    case Clean(
      outputDir: String = "build"
    )
    case Info(
      diagnostics: Boolean = false
    )

  case class Config(
    command: Command = Command.Info()
  )

  def createParser: OParser[Unit, Config] =
    val builder = OParser.builder[Config]
    import builder.*

    val fileArg = arg[String]("<source-file>")
      .text("Path to the source file")

    val outputDirOpt = opt[String]('o', "output-dir")
      .text("Directory where output files will be written (default: build)")

    val verboseOpt = opt[Unit]('v', "verbose")
      .text("Enable verbose logging with detailed command information")

    val astOpt = opt[Unit]('a', "ast")
      .text("Output the AST to a file in addition to other compilation")

    // Binary executable command
    val binCommand =
      cmd("bin")
        .action((_, config) => config.copy(command = Command.Bin()))
        .text("Compile source file to a binary executable")
        .children(
          fileArg.action((file, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(file = Some(Paths.get(file)))
              case cmd => cmd
            })
          ),
          outputDirOpt.action((dir, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(outputDir = dir)
              case cmd => cmd
            })
          ),
          astOpt.action((_, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(outputAst = true)
              case cmd => cmd
            })
          ),
          verboseOpt.action((_, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(verbose = true)
              case cmd => cmd
            })
          )
        )

    // Library command
    val libCommand =
      cmd("lib")
        .action((_, config) => config.copy(command = Command.Lib()))
        .text("Compile source file to a library")
        .children(
          fileArg.action((file, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(file = Some(Paths.get(file)))
              case cmd => cmd
            })
          ),
          outputDirOpt.action((dir, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(outputDir = dir)
              case cmd => cmd
            })
          ),
          astOpt.action((_, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(outputAst = true)
              case cmd => cmd
            })
          ),
          verboseOpt.action((_, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(verbose = true)
              case cmd => cmd
            })
          )
        )

    // AST only command
    val astCommand =
      cmd("ast")
        .action((_, config) => config.copy(command = Command.Ast()))
        .text("Generate only the AST and output to a file")
        .children(
          fileArg.action((file, config) =>
            config.copy(command = config.command match {
              case ast: Command.Ast => ast.copy(file = Some(Paths.get(file)))
              case cmd => cmd
            })
          ),
          outputDirOpt.action((dir, config) =>
            config.copy(command = config.command match {
              case ast: Command.Ast => ast.copy(outputDir = dir)
              case cmd => cmd
            })
          ),
          verboseOpt.action((_, config) =>
            config.copy(command = config.command match {
              case ast: Command.Ast => ast.copy(verbose = true)
              case cmd => cmd
            })
          )
        )

    // LLVM IR only command
    val irCommand =
      cmd("ir")
        .action((_, config) => config.copy(command = Command.Ir()))
        .text("Generate only LLVM IR and output to a file")
        .children(
          fileArg.action((file, config) =>
            config.copy(command = config.command match {
              case ir: Command.Ir => ir.copy(file = Some(Paths.get(file)))
              case cmd => cmd
            })
          ),
          outputDirOpt.action((dir, config) =>
            config.copy(command = config.command match {
              case ir: Command.Ir => ir.copy(outputDir = dir)
              case cmd => cmd
            })
          ),
          astOpt.action((_, config) =>
            config.copy(command = config.command match {
              case ir: Command.Ir => ir.copy(outputAst = true)
              case cmd => cmd
            })
          ),
          verboseOpt.action((_, config) =>
            config.copy(command = config.command match {
              case ir: Command.Ir => ir.copy(verbose = true)
              case cmd => cmd
            })
          )
        )

    // Clean command
    val cleanCommand =
      cmd("clean")
        .action((_, config) => config.copy(command = Command.Clean()))
        .text("Delete the output directory")
        .children(
          outputDirOpt.action((dir, config) =>
            config.copy(command = config.command match {
              case clean: Command.Clean => clean.copy(outputDir = dir)
              case cmd => cmd
            })
          )
        )

    val infoCommand =
      cmd("info")
        .action((_, config) => config.copy(command = Command.Info()))
        .text("Display version information and optional diagnostics")
        .children(
          opt[Unit]('d', "diagnostics")
            .action((_, config) =>
              config.copy(command = config.command match {
                case info: Command.Info => info.copy(diagnostics = true)
                case cmd => cmd
              })
            )
            .text("Display diagnostics information")
        )

    OParser.sequence(
      programName("mmlc"),
      head(
        "mmlc",
        MmlcBuildInfo.version,
        "(" + MmlcBuildInfo.build + "-" + MmlcBuildInfo.gitSha + ")",
        "(" + MmlcBuildInfo.os + "-" + MmlcBuildInfo.arch + ")"
      ),
      infoCommand,
      binCommand,
      libCommand,
      astCommand,
      irCommand,
      cleanCommand,
      help('h', "help").text("Display this help message")
    )
