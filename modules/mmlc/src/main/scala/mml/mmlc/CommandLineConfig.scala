package mml.mmlc

import scopt.OParser

import java.nio.file.{Path, Paths}

object CommandLineConfig:

  enum Command:
    case Build(
      file:            Option[Path]   = None,
      outputDir:       String         = "build",
      outputAst:       Boolean        = false,
      verbose:         Boolean        = false,
      targetTriple:    Option[String] = None,
      targetCpu:       Option[String] = None,
      noStackCheck:    Boolean        = false,
      emitOptIr:       Boolean        = false,
      noTco:           Boolean        = false,
      timings:         Boolean        = false,
      outputName:      Option[String] = None,
      printPhases:     Boolean        = false,
      optLevel:        Int            = 3,
      emitScopedAlias: Boolean        = false,
      targetType:      String         = "exe",
      asan:            Boolean        = false
    )
    case Run(
      file:            Option[Path]   = None,
      outputDir:       String         = "build",
      outputAst:       Boolean        = false,
      verbose:         Boolean        = false,
      targetTriple:    Option[String] = None,
      targetCpu:       Option[String] = None,
      noStackCheck:    Boolean        = false,
      emitOptIr:       Boolean        = false,
      noTco:           Boolean        = false,
      timings:         Boolean        = false,
      outputName:      Option[String] = None,
      printPhases:     Boolean        = false,
      optLevel:        Int            = 3,
      emitScopedAlias: Boolean        = false,
      asan:            Boolean        = false
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
    command: Command = Command.Build()
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

    val timingOpt = opt[Unit]('m', "metrics")
      .text("Print compilation metrics (timings, parser stats)")

    val printPhasesOpt = opt[Unit]('p', "print-phases")
      .text("Print detailed compilation phase information")

    val optLevelOpt = opt[Int]('O', "opt")
      .validate(o =>
        if o >= 0 && o <= 3 then success
        else failure("Optimization level must be between 0 and 3")
      )
      .text("Optimization level (0-3, default: 3)")

    val emitScopedAliasOpt = opt[Unit]("emit-scoped-alias")
      .text("Emit scoped alias metadata (disabled by default)")

    val asanOpt = opt[Unit]('s', "asan")
      .text("Enable AddressSanitizer for memory error detection")

    val targetTypeOpt = opt[String]('x', "target-type")
      .validate(t =>
        if t == "exe" || t == "lib" then success
        else failure("Target type must be 'exe' or 'lib'")
      )
      .text("Output type: exe (executable, default) or lib (library)")

    // Top-level build options (applied to default Build command)
    def topLevelFileArg = arg[String]("<source-file>")
      .optional()
      .action((file, config) =>
        config.command match
          case build: Command.Build =>
            config.copy(command = build.copy(file = Some(Paths.get(file))))
          case _ => config
      )
      .text("Path to the source file")

    def topLevelTargetTypeOpt = targetTypeOpt.action((t, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(targetType = t))
        case _ => c
    )

    def topLevelBuildDirOpt = buildDirOpt.action((d, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(outputDir = d))
        case _ => c
    )

    def topLevelOutputNameOpt = outputNameOpt.action((n, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(outputName = Some(n)))
        case _ => c
    )

    def topLevelAstOpt = astOpt.action((_, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(outputAst = true))
        case _ => c
    )

    def topLevelVerboseOpt = verboseOpt.action((_, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(verbose = true))
        case _ => c
    )

    def topLevelTimingOpt = timingOpt.action((_, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(timings = true))
        case _ => c
    )

    def topLevelTargetOpt = targetOpt.action((t, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(targetTriple = Some(t)))
        case _ => c
    )

    def topLevelTargetCpuOpt = targetCpuOpt.action((cpu, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(targetCpu = Some(cpu)))
        case _ => c
    )

    def topLevelNoStackCheckOpt = noStackCheckOpt.action((_, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(noStackCheck = true))
        case _ => c
    )

    def topLevelEmitOptIrOpt = emitOptIrOpt.action((_, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(emitOptIr = true))
        case _ => c
    )

    def topLevelNoTcoOpt = noTcoOpt.action((_, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(noTco = true))
        case _ => c
    )

    def topLevelPrintPhasesOpt = printPhasesOpt.action((_, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(printPhases = true))
        case _ => c
    )

    def topLevelOptLevelOpt = optLevelOpt.action((l, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(optLevel = l))
        case _ => c
    )

    def topLevelEmitScopedAliasOpt = emitScopedAliasOpt.action((_, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(emitScopedAlias = true))
        case _ => c
    )

    def topLevelAsanOpt = asanOpt.action((_, c) =>
      c.command match
        case b: Command.Build => c.copy(command = b.copy(asan = true))
        case _ => c
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
          ),
          emitScopedAliasOpt.action((_, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(emitScopedAlias = true)
              case cmd => cmd
            })
          ),
          asanOpt.action((_, config) =>
            config.copy(command = config.command match {
              case run: Command.Run => run.copy(asan = true)
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
      // Top-level build options (build is the default command)
      topLevelTargetTypeOpt,
      topLevelBuildDirOpt,
      topLevelOutputNameOpt,
      topLevelAstOpt,
      topLevelVerboseOpt,
      topLevelTimingOpt,
      topLevelTargetOpt,
      topLevelTargetCpuOpt,
      topLevelNoStackCheckOpt,
      topLevelEmitOptIrOpt,
      topLevelNoTcoOpt,
      topLevelPrintPhasesOpt,
      topLevelOptLevelOpt,
      topLevelEmitScopedAliasOpt,
      topLevelAsanOpt,
      // Subcommands (override the default Build when matched)
      runCommand,
      astCommand,
      irCommand,
      devCommand,
      lspCommand,
      cleanCommand,
      infoCommand,
      help('h', "help").text("Display this help message"),
      // File argument last so subcommands take precedence
      topLevelFileArg
    )
