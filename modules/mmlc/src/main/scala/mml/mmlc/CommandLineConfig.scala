package mml.mmlc

import scopt.OParser

import java.nio.file.{Path, Paths}

object CommandLineConfig:

  enum Command:
    case Bin(
      file:         Option[Path]   = None,
      outputDir:    String         = "build",
      outputAst:    Boolean        = false,
      verbose:      Boolean        = false,
      targetTriple: Option[String] = None,
      targetCpu:    Option[String] = None,
      noStackCheck: Boolean        = false,
      emitOptIr:    Boolean        = false,
      noTco:        Boolean        = false,
      timings:      Boolean        = false,
      outputName:   Option[String] = None,
      printPhases:  Boolean        = false,
      optLevel:     Int            = 3
    )
    case Run(
      file:         Option[Path]   = None,
      outputDir:    String         = "build",
      outputAst:    Boolean        = false,
      verbose:      Boolean        = false,
      targetTriple: Option[String] = None,
      targetCpu:    Option[String] = None,
      noStackCheck: Boolean        = false,
      emitOptIr:    Boolean        = false,
      noTco:        Boolean        = false,
      timings:      Boolean        = false,
      outputName:   Option[String] = None,
      printPhases:  Boolean        = false,
      optLevel:     Int            = 3
    )
    case Lib(
      file:         Option[Path]   = None,
      outputDir:    String         = "build",
      outputAst:    Boolean        = false,
      verbose:      Boolean        = false,
      targetTriple: Option[String] = None,
      targetCpu:    Option[String] = None,
      noStackCheck: Boolean        = false,
      emitOptIr:    Boolean        = false,
      noTco:        Boolean        = false,
      timings:      Boolean        = false,
      outputName:   Option[String] = None,
      printPhases:  Boolean        = false,
      optLevel:     Int            = 3
    )
    case Ast(
      file:      Option[Path] = None,
      outputDir: String       = "build",
      verbose:   Boolean      = false,
      timings:   Boolean      = false,
      noTco:     Boolean      = false
    )
    case Ir(
      file:      Option[Path] = None,
      outputDir: String       = "build",
      outputAst: Boolean      = false,
      verbose:   Boolean      = false,
      timings:   Boolean      = false,
      noTco:     Boolean      = false
    )
    case Clean(
      outputDir: String = "build"
    )
    case Info(
      diagnostics: Boolean = false,
      showTriples: Boolean = false
    )
    case Dev(
      file:    Option[Path] = None,
      verbose: Boolean      = false
    )
    case Lsp()

  case class Config(
    command: Command = Command.Info()
  )

  def createParser: OParser[Unit, Config] =
    val builder = OParser.builder[Config]
    import builder.*

    val fileArg = arg[String]("<source-file>")
      .text("Path to the source file")

    val buildDirOpt = opt[String]('b', "build-dir")
      .text("Directory where output files will be written (default: build)")

    val outputNameOpt = opt[String]('o', "output")
      .text("Name of the output artifact (default: lowercase module name)")

    val verboseOpt = opt[Unit]('v', "verbose")
      .text("Enable verbose logging with detailed command information")

    val astOpt = opt[Unit]('a', "ast")
      .text("Output the AST to a file in addition to other compilation")

    val targetOpt = opt[String]('T', "target")
      .text("Target triple for cross-compilation (e.g., x86_64-pc-linux-gnu)")

    val targetCpuOpt = opt[String]('C', "cpu")
      .text("Target CPU for cross-compilation (e.g., cortex-a53, apple-m1)")

    val noStackCheckOpt = opt[Unit]("no-stack-check")
      .text("Pass -fno-stack-check to clang")

    val emitOptIrOpt = opt[Unit]('I', "emit-opt-ir")
      .text("Emit optimized LLVM IR to <name>_opt.ll")

    val noTcoOpt = opt[Unit]("no-tco")
      .text("Disable tail-call optimization")

    val timingOpt = opt[Unit]('t', "time")
      .text("Print compilation timings at the end")

    val printPhasesOpt = opt[Unit]('p', "print-phases")
      .text("Print detailed compilation phase information")

    val optLevelOpt = opt[Int]('O', "opt")
      .validate(o =>
        if o >= 0 && o <= 3 then success
        else failure("Optimization level must be between 0 and 3")
      )
      .text("Optimization level (0-3, default: 3)")

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
          buildDirOpt.action((dir, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(outputDir = dir)
              case cmd => cmd
            })
          ),
          outputNameOpt.action((name, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(outputName = Some(name))
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
          ),
          timingOpt.action((_, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(timings = true)
              case cmd => cmd
            })
          ),
          targetOpt.action((triple, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(targetTriple = Some(triple))
              case cmd => cmd
            })
          ),
          targetCpuOpt.action((cpu, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(targetCpu = Some(cpu))
              case cmd => cmd
            })
          ),
          noStackCheckOpt.action((_, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(noStackCheck = true)
              case cmd => cmd
            })
          ),
          emitOptIrOpt.action((_, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(emitOptIr = true)
              case cmd => cmd
            })
          ),
          noTcoOpt.action((_, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(noTco = true)
              case cmd => cmd
            })
          ),
          printPhasesOpt.action((_, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(printPhases = true)
              case cmd => cmd
            })
          ),
          optLevelOpt.action((level, config) =>
            config.copy(command = config.command match {
              case bin: Command.Bin => bin.copy(optLevel = level)
              case cmd => cmd
            })
          )
        )

    // Run command (compile and execute)
    val runCommand =
      cmd("run")
        .action((_, config) => config.copy(command = Command.Run()))
        .text("Compile source file to a binary executable and run it")
        .children(
          fileArg.action((file, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(file = Some(Paths.get(file)))
              case cmd => cmd
            })
          ),
          buildDirOpt.action((dir, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(outputDir = dir)
              case cmd => cmd
            })
          ),
          outputNameOpt.action((name, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(outputName = Some(name))
              case cmd => cmd
            })
          ),
          astOpt.action((_, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(outputAst = true)
              case cmd => cmd
            })
          ),
          verboseOpt.action((_, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(verbose = true)
              case cmd => cmd
            })
          ),
          timingOpt.action((_, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(timings = true)
              case cmd => cmd
            })
          ),
          targetOpt.action((triple, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(targetTriple = Some(triple))
              case cmd => cmd
            })
          ),
          targetCpuOpt.action((cpu, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(targetCpu = Some(cpu))
              case cmd => cmd
            })
          ),
          noStackCheckOpt.action((_, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(noStackCheck = true)
              case cmd => cmd
            })
          ),
          emitOptIrOpt.action((_, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(emitOptIr = true)
              case cmd => cmd
            })
          ),
          noTcoOpt.action((_, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(noTco = true)
              case cmd => cmd
            })
          ),
          printPhasesOpt.action((_, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(printPhases = true)
              case cmd => cmd
            })
          ),
          optLevelOpt.action((level, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(optLevel = level)
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
          buildDirOpt.action((dir, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(outputDir = dir)
              case cmd => cmd
            })
          ),
          outputNameOpt.action((name, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(outputName = Some(name))
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
          ),
          timingOpt.action((_, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(timings = true)
              case cmd => cmd
            })
          ),
          targetOpt.action((triple, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(targetTriple = Some(triple))
              case cmd => cmd
            })
          ),
          targetCpuOpt.action((cpu, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(targetCpu = Some(cpu))
              case cmd => cmd
            })
          ),
          noStackCheckOpt.action((_, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(noStackCheck = true)
              case cmd => cmd
            })
          ),
          emitOptIrOpt.action((_, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(emitOptIr = true)
              case cmd => cmd
            })
          ),
          noTcoOpt.action((_, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(noTco = true)
              case cmd => cmd
            })
          ),
          printPhasesOpt.action((_, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(printPhases = true)
              case cmd => cmd
            })
          ),
          optLevelOpt.action((level, config) =>
            config.copy(command = config.command match {
              case lib: Command.Lib => lib.copy(optLevel = level)
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
          buildDirOpt.action((dir, config) =>
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
          ),
          timingOpt.action((_, config) =>
            config.copy(command = config.command match {
              case ast: Command.Ast => ast.copy(timings = true)
              case cmd => cmd
            })
          ),
          noTcoOpt.action((_, config) =>
            config.copy(command = config.command match {
              case ast: Command.Ast => ast.copy(noTco = true)
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
          buildDirOpt.action((dir, config) =>
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
          ),
          timingOpt.action((_, config) =>
            config.copy(command = config.command match {
              case ir: Command.Ir => ir.copy(timings = true)
              case cmd => cmd
            })
          ),
          noTcoOpt.action((_, config) =>
            config.copy(command = config.command match {
              case ir: Command.Ir => ir.copy(noTco = true)
              case cmd => cmd
            })
          )
        )

    // Clean command
    val cleanCommand =
      cmd("clean")
        .action((_, config) => config.copy(command = Command.Clean()))
        .text("Delete the build directory")
        .children(
          buildDirOpt.action((dir, config) =>
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
            .text("Display diagnostics information"),
          opt[Unit]('t', "triples")
            .action((_, config) =>
              config.copy(command = config.command match {
                case info: Command.Info => info.copy(showTriples = true)
                case cmd => cmd
              })
            )
            .text("Display supported target triples")
        )

    // Dev command (watch mode with validation only, no LLVM)
    val devCommand =
      cmd("dev")
        .action((_, config) => config.copy(command = Command.Dev()))
        .text("Watch source file and validate on changes (no binary output)")
        .children(
          fileArg.action((file, config) =>
            config.copy(command = config.command match {
              case dev: Command.Dev => dev.copy(file = Some(Paths.get(file)))
              case cmd => cmd
            })
          ),
          verboseOpt.action((_, config) =>
            config.copy(command = config.command match {
              case dev: Command.Dev => dev.copy(verbose = true)
              case cmd => cmd
            })
          )
        )

    // LSP command (stdio-based language server)
    val lspCommand =
      cmd("lsp")
        .action((_, config) => config.copy(command = Command.Lsp()))
        .text("Start LSP server on stdio (for IDE integration)")

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
      runCommand,
      libCommand,
      astCommand,
      irCommand,
      devCommand,
      lspCommand,
      cleanCommand,
      help('h', "help").text("Display this help message")
    )
