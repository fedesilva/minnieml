package mml.mmlclib.api

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import mml.mmlclib.MmlLibBuildInfo
import mml.mmlclib.codegen.LlvmToolchain
import mml.mmlclib.compiler.{CodegenStage, CompilerConfig, CompilerState, Counter, FileOperations}
import mml.mmlclib.errors.CompilationError
import mml.mmlclib.parser.{ParserError, SourceInfo}
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.util.error.print.ErrorPrinter

import java.nio.file.Path

object CompilerApi:

  def compilationFailed(error: String): String =
    s"\n${Console.RED}Compilation failed:${Console.RESET}\n\n" +
      s"${Console.YELLOW}$error${Console.RESET}\n"

  def compileSource(
    source:     String,
    moduleName: String,
    config:     CompilerConfig,
    sourcePath: Option[String] = None
  ): IO[Either[String, CompilerState]] =
    FrontEndApi.compile(source, moduleName, config, sourcePath).value.map {
      case Left(error) =>
        Left(ErrorPrinter.prettyPrint(error, Some(SourceInfo(source))))
      case Right(state) =>
        Right(state)
    }

  def compilePath(
    path:   Path,
    config: CompilerConfig
  ): IO[Either[String, CompilerState]] =
    val moduleName = moduleNameFromPath(path)
    val sourcePath = sourcePathFrom(path)
    for
      contentResult <- FileOperations.readFile(path)
      result <- contentResult match
        case Left(error) =>
          IO.pure(Left(s"Error reading file: ${error.getMessage}"))
        case Right(content) =>
          compileSource(content, moduleName, config, sourcePath = Some(sourcePath))
    yield result

  private def runFrontend(
    path:   Path,
    config: CompilerConfig
  ): IO[Either[ExitCode, CompilerState]] =
    for
      result <- compilePath(path, config)
      outcome <- result match
        case Left(error) =>
          IO.println(compilationFailed(error)).as(Left(ExitCode.Error))
        case Right(state) =>
          if state.hasErrors then
            IO.println(compilationFailed(prettyPrintStateErrors(state)))
              *> maybePrintTimings(state).as(Left(ExitCode.Error))
          else IO.pure(Right(state))
    yield outcome

  /** Frontend + validation (pure). IR emission happens in processNative/processIrOnly. */
  private def runPipeline(
    path:   Path,
    config: CompilerConfig
  ): IO[Either[ExitCode, CompilerState]] =
    runFrontend(path, config).flatMap {
      case Left(exit) => IO.pure(Left(exit))
      case Right(state) =>
        val validated = CodegenStage.validate(state)
        if validated.hasErrors then
          IO.println(compilationFailed(prettyPrintStateErrors(validated)))
            *> maybePrintTimings(validated).as(Left(ExitCode.Error))
        else IO.pure(Right(validated))
    }

  def processNative(path: Path, config: CompilerConfig): IO[ExitCode] =
    runPipeline(path, config).flatMap {
      case Left(exit) => IO.pure(exit)
      case Right(state) => processNativeBinary(state)
    }

  def processRun(path: Path, config: CompilerConfig): IO[ExitCode] =
    runPipeline(path, config).flatMap {
      case Left(exit) => IO.pure(exit)
      case Right(state) => processNativeRun(state)
    }

  /** Compile to native without printing errors. Returns Left(errorMessage) on failure. */
  def compileNativeQuiet(path: Path, config: CompilerConfig): IO[Either[String, CompilerState]] =
    runPipelineQuiet(path, config).flatMap {
      case Left(msg) => IO.pure(Left(msg))
      case Right(state) => processNativeBinaryQuiet(state)
    }

  /** Clean output directory without printing. Returns Right with message on success. */
  def cleanQuiet(outputDir: String): IO[Either[String, String]] =
    IO.blocking {
      val dir = new java.io.File(outputDir)
      if dir.exists() then
        def deleteRecursively(file: java.io.File): Boolean =
          if file.isDirectory then file.listFiles().foreach(deleteRecursively)
          file.delete()
        deleteRecursively(dir)
        Right(s"Cleaned directory: $outputDir")
      else Right(s"Output directory does not exist: $outputDir")
    }.handleErrorWith { e =>
      IO.pure(Left(s"Failed to clean directory: ${e.getMessage}"))
    }

  /** Generate AST file without printing. Returns Right with AST file path on success. */
  def processAstQuiet(path: Path, config: CompilerConfig): IO[Either[String, String]] =
    compilePath(path, config).flatMap {
      case Left(error) =>
        IO.pure(Left(error))
      case Right(state) if state.hasErrors =>
        IO.pure(Left(plainErrorMessage(state)))
      case Right(state) =>
        writeAstQuiet(state.module, config.outputDir.toString)
    }

  /** Generate IR file without printing. Returns Right with IR file path on success. */
  def processIrQuiet(path: Path, config: CompilerConfig): IO[Either[String, String]] =
    compilePath(path, config).flatMap {
      case Left(error) =>
        IO.pure(Left(error))
      case Right(state) if state.hasErrors =>
        IO.pure(Left(plainErrorMessage(state)))
      case Right(state) =>
        IO.blocking(CodegenStage.validate(state)).flatMap { validated =>
          if validated.hasErrors then IO.pure(Left(plainErrorMessage(validated)))
          else
            CodegenStage.emitIrOnly(validated).flatMap { finalState =>
              finalState.llvmIr match
                case Some(ir) =>
                  writeIrQuiet(
                    ir,
                    finalState.module.name,
                    config.outputDir.toString,
                    finalState.resolvedTriple
                  )
                case None =>
                  IO.pure(Left(plainErrorMessage(finalState)))
            }
        }
    }

  private def writeAstQuiet(
    module:    mml.mmlclib.ast.Module,
    outputDir: String
  ): IO[Either[String, String]] =
    IO.blocking {
      val dir = new java.io.File(outputDir)
      if !dir.exists() then dir.mkdirs()
      val astFileName = s"$outputDir/${module.name}.ast"
      val writer      = new java.io.PrintWriter(new java.io.File(astFileName))
      try writer.write(mml.mmlclib.util.prettyprint.ast.prettyPrintAst(module, 2, false, true))
      finally writer.close()
      Right(astFileName)
    }.handleErrorWith { e =>
      IO.pure(Left(s"Failed to write AST: ${e.getMessage}"))
    }

  private def writeIrQuiet(
    llvmIr:       String,
    moduleName:   String,
    outputDir:    String,
    targetTriple: Option[String]
  ): IO[Either[String, String]] =
    IO.blocking {
      val dir = new java.io.File(outputDir)
      if !dir.exists() then dir.mkdirs()
      val triple   = targetTriple.getOrElse("unknown")
      val llvmFile = s"$outputDir/$moduleName-$triple.ll"
      val writer   = new java.io.PrintWriter(llvmFile)
      try writer.write(llvmIr)
      finally writer.close()
      Right(llvmFile)
    }.handleErrorWith { e =>
      IO.pure(Left(s"Failed to write IR: ${e.getMessage}"))
    }

  /** Run frontend + codegen validation without printing. Returns error message on failure. */
  private def runPipelineQuiet(
    path:   Path,
    config: CompilerConfig
  ): IO[Either[String, CompilerState]] =
    compilePath(path, config).flatMap {
      case Left(error) =>
        IO.pure(Left(error))
      case Right(state) if state.hasErrors =>
        IO.pure(Left(plainErrorMessage(state)))
      case Right(state) =>
        IO.blocking(CodegenStage.validate(state)).map { validated =>
          if validated.hasErrors then Left(plainErrorMessage(validated))
          else Right(validated)
        }
    }

  /** Process native binary without printing. Returns error message on failure. */
  private def processNativeBinaryQuiet(state: CompilerState): IO[Either[String, CompilerState]] =
    for
      _ <-
        if state.config.outputAst then
          FileOperations.writeAstToFile(state.module, state.config.outputDir.toString)
        else IO.unit
      finalState <- CodegenStage.processNative(state)
    yield finalState.nativeResult match
      case Some(_) => Right(finalState)
      case None => Left(plainErrorMessage(finalState))

  /** Generate plain text error message from state errors (no ANSI codes). */
  private def plainErrorMessage(state: CompilerState): String =
    val errors = state.errors.map(_.message)
    if errors.isEmpty then "Unknown compilation error"
    else if errors.size == 1 then errors.head
    else errors.mkString("\n")

  def processAstOnly(path: Path, config: CompilerConfig): IO[ExitCode] =
    runFrontend(path, config).flatMap {
      case Left(exit) => IO.pure(exit)
      case Right(state) =>
        FileOperations
          .writeAstToFile(state.module, config.outputDir.toString)
          .flatMap(_ => maybePrintTimings(state).as(ExitCode.Success))
    }

  def processIrOnly(path: Path, config: CompilerConfig): IO[ExitCode] =
    val outputDir = config.outputDir.toString
    runPipeline(path, config).flatMap {
      case Left(exit) => IO.pure(exit)
      case Right(state) =>
        CodegenStage.emitIrOnly(state).flatMap { finalState =>
          for
            _ <-
              if config.outputAst then FileOperations.writeAstToFile(finalState.module, outputDir)
              else IO.unit
            exit <- finalState.llvmIr match
              case Some(ir) =>
                writeLlvmIr(
                  ir,
                  finalState.module.name,
                  outputDir,
                  finalState.resolvedTriple
                )
              case None =>
                IO.println(compilationFailed(prettyPrintStateErrors(finalState))).as(ExitCode.Error)
            _ <- maybePrintTimings(finalState)
          yield exit
        }
    }

  def processClean(outputDir: String): IO[ExitCode] =
    FileOperations.cleanOutputDir(outputDir)

  def processInfo(
    diagnostics: Boolean,
    showTriples: Boolean
  ): IO[ExitCode] =
    printVersionInfo *>
      printToolInfo(diagnostics) *>
      (if showTriples then printTripleInfo else IO.unit) *>
      IO.println(
        "Use `mmlc --help` or `mmlc -h` for more information on available commands."
      ).as(ExitCode.Success)

  private def moduleNameFromPath(path: Path): String =
    FileOperations.sanitizeFileName(path)

  private def sourcePathFrom(path: Path): String =
    val cwd      = Path.of("").toAbsolutePath.normalize()
    val absolute = path.toAbsolutePath.normalize()
    try cwd.relativize(absolute).toString
    catch case _: IllegalArgumentException => path.normalize().toString

  private def compilerErrorsFromState(state: CompilerState): List[CompilationError] =
    val parserErrors   = state.errors.collect { case err: ParserError => err }
    val semanticErrors = state.errors.collect { case err: SemanticError => err }
    val knownErrors = List(
      Option.when(parserErrors.nonEmpty)(CompilerError.ParserErrors(parserErrors.toList)),
      Option.when(semanticErrors.nonEmpty)(CompilerError.SemanticErrors(semanticErrors.toList))
    ).flatten
    val remaining = state.errors.filterNot {
      case _: ParserError => true
      case _: SemanticError => true
      case _ => false
    }
    knownErrors ++ remaining.toList

  private def prettyPrintStateErrors(state: CompilerState): String =
    val messages = compilerErrorsFromState(state)
      .map(error => ErrorPrinter.prettyPrint(error, Some(state.sourceInfo)))
    if messages.isEmpty then "No errors"
    else
      val fileHeader = state.module.sourcePath.map(path => s"File: $path")
      (fileHeader.toList ++ messages).mkString("\n\n")

  private def processNativeBinary(state: CompilerState): IO[ExitCode] =
    for
      _ <-
        if state.config.outputAst then
          FileOperations.writeAstToFile(state.module, state.config.outputDir.toString)
        else IO.unit
      finalState <- CodegenStage.processNative(state)
      exit <- finalState.nativeResult match
        case Some(_) =>
          (if state.config.printPhases then IO.println("Done") else IO.unit).as(ExitCode.Success)
        case None =>
          IO.println(compilationFailed(prettyPrintStateErrors(finalState)))
            .as(ExitCode.Error)
      _ <- maybePrintTimings(finalState)
    yield exit

  private def processNativeRun(state: CompilerState): IO[ExitCode] =
    for
      _ <-
        if state.config.outputAst then
          FileOperations.writeAstToFile(state.module, state.config.outputDir.toString)
        else IO.unit
      finalState <- CodegenStage.processNative(state)
      _ <- maybePrintTimings(finalState)
      exit <- finalState.nativeResult match
        case Some(_) => executeBinary(finalState)
        case None =>
          IO.println(compilationFailed(prettyPrintStateErrors(finalState)))
            .as(ExitCode.Error)
    yield exit

  private def executeBinary(state: CompilerState): IO[ExitCode] =
    val outputDir = state.config.outputDir.toString
    val baseName  = state.module.name.toLowerCase
    val execPath = state.config.targetTriple match
      case Some(_) =>
        val triple = state.resolvedTriple.getOrElse("unknown")
        s"$outputDir/target/$baseName-$triple"
      case None =>
        s"$outputDir/target/$baseName"

    IO.blocking {
      val process = new ProcessBuilder(execPath).inheritIO().start()
      process.waitFor()
    }.map(ExitCode(_))
      .handleErrorWith { e =>
        IO.println(s"Failed to execute $execPath: ${e.getMessage}").as(ExitCode.Error)
      }

  private def writeLlvmIr(
    llvmIr:       String,
    moduleName:   String,
    outputDir:    String,
    targetTriple: Option[String]
  ): IO[ExitCode] =
    if llvmIr.nonEmpty then
      IO.blocking {
        val dir = new java.io.File(outputDir)
        if !dir.exists() then dir.mkdirs()
        val triple   = targetTriple.getOrElse("unknown")
        val llvmFile = s"$outputDir/$moduleName-$triple.ll"
        val writer   = new java.io.PrintWriter(llvmFile)
        try writer.write(llvmIr)
        finally writer.close()
        llvmFile
      }.flatMap(path => IO.println(s"LLVM IR written to $path").as(ExitCode.Success))
    else IO.unit.as(ExitCode.Success)

  private def maybePrintTimings(state: CompilerState): IO[Unit] =
    if !state.config.showTimings then IO.unit
    else if state.timings.isEmpty then IO.println("No timings recorded.")
    else
      val header = s"${Console.CYAN}Timings:${Console.RESET}"
      val lines = state.timings.map { timing =>
        val millis = timing.durationNanos.toDouble / 1000000.0
        f"  ${timing.stage}%-10s ${timing.name}%-24s ${millis}%.2f ms"
      }
      val stageOrder = state.timings.foldLeft(Vector.empty[String]) { (order, timing) =>
        if order.contains(timing.stage) then order else order :+ timing.stage
      }
      val stageTotals = stageOrder.map { stage =>
        val total = state.timings.foldLeft(0L) { (sum, timing) =>
          if timing.stage == stage then sum + timing.durationNanos else sum
        }
        stage -> total
      }
      val stageHeader = s"${Console.CYAN}Stage totals:${Console.RESET}"
      val stageLines = stageTotals.map { case (stage, nanos) =>
        val millis = nanos.toDouble / 1000000.0
        f"  ${stage}%-10s ${millis}%.2f ms"
      }
      val totalNanos = stageTotals.foldLeft(0L) { (sum, entry) => sum + entry._2 }
      val totalLine  = f"  ${"total"}%-10s ${totalNanos.toDouble / 1000000.0}%.2f ms"
      IO.println(header) *>
        lines.traverse_(IO.println) *>
        IO.println(stageHeader) *>
        stageLines.traverse_(IO.println) *>
        IO.println(totalLine) *>
        printCounters(state.counters)

  private def printCounters(counters: Vector[Counter]): IO[Unit] =
    if counters.isEmpty then IO.unit
    else
      val counterHeader = s"${Console.CYAN}Counters:${Console.RESET}"
      val counterLines = counters.map { c =>
        if c.name.startsWith("time:") then
          val ms = c.value.toDouble / 1000000.0
          f"  ${c.stage}%-10s ${c.name}%-24s $ms%.2f ms"
        else f"  ${c.stage}%-10s ${c.name}%-24s ${c.value}%,d"
      }
      IO.println(counterHeader) *>
        counterLines.traverse_(IO.println)

  def formatBuildInfo(
    label:   String,
    version: String,
    build:   String,
    gitSha:  String,
    os:      String,
    arch:    String
  ): String =
    s"""
       |$label $version
       |(build: $build-$gitSha)
       |($os-$arch)
      """.stripMargin.replaceAll("\n", " ").trim

  private def printVersionInfo: IO[Unit] =
    IO.println(
      formatBuildInfo(
        "mmlc",
        MmlLibBuildInfo.version,
        MmlLibBuildInfo.build,
        MmlLibBuildInfo.gitSha,
        MmlLibBuildInfo.os,
        MmlLibBuildInfo.arch
      )
    )

  private def printToolInfo(printDiagnostics: Boolean): IO[Unit] =
    if !printDiagnostics then IO.unit
    else
      LlvmToolchain.collectLlvmToolVersions().flatMap { info =>
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
              } *> IO.println(LlvmToolchain.llvmInstallInstructions.stripMargin)
          else IO.unit

        printVersions *> printMissing
      }

  private def printTripleInfo: IO[Unit] =
    LlvmToolchain.queryLocalTriple.flatMap {
      case Some(triple) =>
        IO.println(s"\n${Console.CYAN}Target Triple:${Console.RESET}") *>
          IO.println(s"  ${Console.GREEN}Current: $triple${Console.RESET} (auto-detected)") *>
          printSampleTriples *>
          IO.println(s"\n  Override with: mmlc build --target <triple> <source-file>") *>
          IO.println(
            s"  ${Console.YELLOW}Cross-compilation requires appropriate toolchains.${Console.RESET}"
          )
      case None =>
        IO.println(s"\n${Console.CYAN}Target Triple:${Console.RESET}") *>
          IO.println(
            s"  ${Console.RED}Could not detect local triple (is clang installed?)${Console.RESET}"
          ) *>
          printSampleTriples *>
          IO.println(s"\n  Specify with: mmlc build --target <triple> <source-file>")
    }

  private def printSampleTriples: IO[Unit] =
    IO.println(s"\n  ${Console.CYAN}Sample triples:${Console.RESET}") *>
      IO.println(s"    x86_64-apple-macosx      (Intel Mac)") *>
      IO.println(s"    aarch64-apple-macosx     (Apple Silicon)") *>
      IO.println(s"    x86_64-pc-linux-gnu      (x86_64 Linux)") *>
      IO.println(s"    aarch64-pc-linux-gnu     (ARM64 Linux)") *>
      IO.println(s"    wasm32-unknown-unknown   (WebAssembly)")
