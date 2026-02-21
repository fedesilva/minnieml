package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.compiler.CompilerConfig
import mml.mmlclib.errors.CompilationError

import java.io.{File, InputStream}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.sys.process.{Process, ProcessLogger}

case class ToolInfo(versions: Map[String, String], missing: List[String])

case class PipelineTiming(name: String, durationNanos: Long)

enum CompilationMode derives CanEqual:
  case Exe
  case Library
  case Ast
  case Ir
  case Dev

enum LlvmCompilationError extends CompilationError derives CanEqual:
  case TemporaryFileCreationError(msg: String)
  case UnsupportedOperatingSystem(osName: String)
  case UnsupportedArchitecture(archName: String)
  case CommandExecutionError(command: String, errorMessage: String, exitCode: Int)
  case ExecutableRunError(path: String, exitCode: Int)
  case LlvmNotInstalled(missingTools: List[String])
  case RuntimeResourceError(msg: String)
  case TripleResolutionError(msg: String)

  def message: String = this match
    case TemporaryFileCreationError(msg) => msg
    case UnsupportedOperatingSystem(osName) => s"Unsupported operating system: $osName"
    case UnsupportedArchitecture(archName) => s"Unsupported architecture: $archName"
    case CommandExecutionError(command, errorMessage, exitCode) =>
      s"Command '$command' failed (exit $exitCode): $errorMessage"
    case ExecutableRunError(path, exitCode) => s"Executable '$path' failed with exit code $exitCode"
    case LlvmNotInstalled(missingTools) =>
      s"LLVM tools not installed: ${missingTools.mkString(", ")}"
    case RuntimeResourceError(msg) => msg
    case TripleResolutionError(msg) => msg

object LlvmToolchain:

  /** List of required LLVM tools */
  private val llvmTools = List("llvm-as", "llvm-link", "opt", "llc", "clang", "llvm-dis", "ld.lld")

  private type TimingRecorder = PipelineTiming => Unit

  private def clangStackProbeFlags(noStackCheck: Boolean): List[String] =
    if noStackCheck then List("-fno-stack-check") else Nil

  private def clangAsanFlags(asan: Boolean): List[String] =
    if asan then List("-fsanitize=address", "-fno-omit-frame-pointer") else Nil

  private def timedStep[A](
    name:         String,
    recordTiming: Option[TimingRecorder]
  )(
    io: IO[A]
  ): IO[A] =
    recordTiming match
      case None => io
      case Some(record) =>
        IO.delay(System.nanoTime()).flatMap { start =>
          io.guarantee(
            IO.delay(record(PipelineTiming(name, System.nanoTime() - start)))
          )
        }

  /** Marker file name to cache LLVM tool info */
  private val llvmInfoFile = "llvm-info"

  /** File name to cache the local target triple */
  private val localTargetTripleFile = "local-target-triple"
  private val hostCpuPrefix         = "Host CPU:"

  /** Installation instructions for different platforms */
  val llvmInstallInstructions: String =
    """
      |Installation instructions:
      |
      |- macOS: brew install llvm lld
      |- Ubuntu/Debian: apt-get install llvm clang lld
      |
      |Make sure the tools are in your PATH.
    """.stripMargin

  /** Collects version information of the requested LLVM tools.
    *
    * @return
    *   An IO effect with ToolInfo containing tool version info and missing tools.
    */
  def collectLlvmToolVersions(): IO[ToolInfo] = IO.blocking {
    llvmTools.foldLeft(ToolInfo(Map.empty[String, String], Nil)) {
      case (ToolInfo(versions, missing), tool) =>
        try
          val output = new StringBuilder
          val logger = ProcessLogger(
            line => output.append(line).append('\n'),
            line => output.append(line).append('\n')
          )
          val exitCode = Process(s"$tool --version").!(logger)
          if exitCode == 0 then ToolInfo(versions + (tool -> output.toString.trim), missing)
          else ToolInfo(versions, tool :: missing)
        catch case _: Exception => ToolInfo(versions, tool :: missing)
    }
  }

  /** Resolves the target triple to use for compilation.
    *
    * If userProvided is Some, returns that value directly (free-form, no validation). Otherwise,
    * checks for a cached triple in workingDir/local-target-triple. If not cached, queries clang for
    * the local triple and caches it.
    *
    * @param userProvided
    *   user-provided triple from CLI, if any
    * @param workingDir
    *   the build directory where the cache file lives
    * @return
    *   the resolved target triple
    */
  def resolveTargetTriple(
    userProvided: Option[String],
    workingDir:   String
  ): IO[Either[LlvmCompilationError, String]] =
    userProvided match
      case Some(triple) => IO.pure(triple.asRight)
      case None => resolveLocalTriple(workingDir)

  private def resolveLocalTriple(workingDir: String): IO[Either[LlvmCompilationError, String]] =
    IO.defer {
      val cacheFile = Paths.get(workingDir).resolve(localTargetTripleFile)
      if Files.exists(cacheFile) then
        IO {
          try Files.readString(cacheFile).trim.asRight
          catch
            case e: Exception =>
              LlvmCompilationError
                .TripleResolutionError(s"Failed to read cached triple: ${e.getMessage}")
                .asLeft
        }
      else queryAndCacheTriple(cacheFile)
    }

  private def queryAndCacheTriple(
    cacheFile: java.nio.file.Path
  ): IO[Either[LlvmCompilationError, String]] = IO.blocking {
    try
      val rawTriple = Process("clang -print-target-triple").!!.trim
      val triple    = normalizeTriple(rawTriple)
      if triple.nonEmpty then
        Files.createDirectories(cacheFile.getParent)
        Files.writeString(cacheFile, triple)
        triple.asRight
      else
        LlvmCompilationError
          .TripleResolutionError("clang -print-target-triple returned empty output")
          .asLeft
    catch
      case e: Exception =>
        LlvmCompilationError
          .TripleResolutionError(s"Failed to query clang for target triple: ${e.getMessage}")
          .asLeft
  }

  /** Normalizes a target triple by stripping version suffixes.
    *
    * Examples:
    *   - x86_64-apple-darwin24.6.0 -> x86_64-apple-macosx
    *   - aarch64-apple-darwin24.6.0 -> aarch64-apple-macosx
    *   - x86_64-pc-linux-gnu -> x86_64-pc-linux-gnu (unchanged)
    */
  private def normalizeTriple(triple: String): String =
    val parts = triple.split("-").toList
    parts match
      case arch :: vendor :: os :: _ if os.startsWith("darwin") || os.startsWith("macosx") =>
        s"$arch-$vendor-macosx"
      case _ => triple

  /** Query the local target triple without caching. For info/diagnostic commands. */
  def queryLocalTriple: IO[Option[String]] = IO.blocking {
    try
      val rawTriple = Process("clang -print-target-triple").!!.trim
      val triple    = normalizeTriple(rawTriple)
      if triple.nonEmpty then Some(triple) else None
    catch case _: Exception => None
  }

  /** Parse Host CPU from the llvm-info marker if available. */
  def readHostCpu(buildDir: String): Option[String] =
    def findMarker(path: Path): Option[Path] =
      if path == null then None
      else
        val candidate = path.resolve(llvmInfoFile)
        if Files.exists(candidate) then Some(candidate)
        else findMarker(path.getParent)

    findMarker(Paths.get(buildDir)).flatMap { marker =>
      try
        Files
          .readAllLines(marker)
          .asScala
          .find(_.stripLeading().startsWith(hostCpuPrefix))
          .map(_.stripLeading().stripPrefix(hostCpuPrefix).trim)
          .filter(_.nonEmpty)
      catch case _: Exception => None
    }

  private def programNameFrom(path: Path): String =
    val fileName = path.getFileName.toString.stripSuffix(".ll")
    val parts    = fileName.split("-").toList
    parts match
      case moduleName :: _ :: _ => moduleName
      case _ => fileName

  def compile(
    llvmIrPath:     Path,
    config:         CompilerConfig,
    resolvedTriple: Option[String],
    targetCpu:      Option[String]
  ): IO[Either[LlvmCompilationError, Int]] =
    compileInternal(
      llvmIrPath,
      config,
      resolvedTriple,
      targetCpu,
      recordTiming = None
    )

  def compileWithTimings(
    llvmIrPath:     Path,
    config:         CompilerConfig,
    resolvedTriple: Option[String],
    targetCpu:      Option[String]
  ): IO[(Either[LlvmCompilationError, Int], Vector[PipelineTiming])] =
    val timings = Vector.newBuilder[PipelineTiming]
    val record: TimingRecorder = timing => timings += timing
    compileInternal(
      llvmIrPath,
      config,
      resolvedTriple,
      targetCpu,
      recordTiming = Some(record)
    ).map(result => result -> timings.result())

  private def compileInternal(
    llvmIrPath:     Path,
    config:         CompilerConfig,
    resolvedTriple: Option[String],
    targetCpu:      Option[String],
    recordTiming:   Option[TimingRecorder]
  ): IO[Either[LlvmCompilationError, Int]] =
    val moduleName = programNameFrom(llvmIrPath)
    val inputFile  = llvmIrPath.toFile
    if !inputFile.exists() then
      IO.pure(
        LlvmCompilationError
          .TemporaryFileCreationError(s"LLVM IR file not found: $llvmIrPath")
          .asLeft
      )
    else
      for
        _ <- IO(logModule(s"Compiling module $moduleName", config.printPhases))
        _ <- IO(logInfo(s"Working directory: ${config.outputDir}", config.printPhases))
        _ <- IO(logInfo(s"Compilation mode: ${config.mode}", config.printPhases))
        _ <- createOutputDir(config.outputDir, config.printPhases)
        result <- processLlvmFile(inputFile, config, resolvedTriple, targetCpu, recordTiming)
      yield result

  private def processLlvmFile(
    inputFile:      File,
    config:         CompilerConfig,
    resolvedTriple: Option[String],
    targetCpu:      Option[String],
    recordTiming:   Option[TimingRecorder]
  ): IO[Either[LlvmCompilationError, Int]] =
    val programName   = programNameFrom(inputFile.toPath)
    val baseOutputDir = config.outputDir.resolve("out")
    val targetDir     = config.outputDir.resolve("target")

    for
      targetTripleResult <- detectOsTargetTriple(resolvedTriple)
      result <- targetTripleResult match
        case Left(error) =>
          IO(logError(s"Error detecting target triple: $error")) *>
            IO.pure(error.asLeft)
        case Right(triple) =>
          val outputDir = baseOutputDir.resolve(triple)
          for
            _ <- createOutputDir(outputDir, config.printPhases)
            _ <- createOutputDir(targetDir, config.printPhases)
            result <- processWithTargetTriple(
              triple,
              inputFile,
              programName,
              config,
              outputDir,
              targetDir,
              targetCpu,
              recordTiming
            )
          yield result
    yield result

  private def processWithTargetTriple(
    targetTriple: String,
    inputFile:    File,
    programName:  String,
    config:       CompilerConfig,
    outputDir:    Path,
    targetDir:    Path,
    targetCpu:    Option[String],
    recordTiming: Option[TimingRecorder]
  ): IO[Either[LlvmCompilationError, Int]] =
    logPhase(s"Starting LLVM compilation pipeline for $programName", config.printPhases)
    logInfo(s"Compilation mode: ${config.mode}", config.printPhases)
    logInfo(s"Using target triple: $targetTriple", config.printPhases)
    runCompilationPipeline(
      inputFile,
      programName,
      targetTriple,
      config,
      outputDir,
      targetDir,
      targetCpu,
      recordTiming
    )

  private def runCompilationPipeline(
    inputFile:    File,
    programName:  String,
    targetTriple: String,
    config:       CompilerConfig,
    outputDir:    Path,
    targetDir:    Path,
    targetCpu:    Option[String],
    recordTiming: Option[TimingRecorder]
  ): IO[Either[LlvmCompilationError, Int]] =
    import cats.data.EitherT

    val programBitcode = outputDir.resolve(s"$programName.bc").toAbsolutePath.toString
    val clangFlags     = clangStackProbeFlags(config.noStackCheck) ++ clangAsanFlags(config.asan)

    (for
      _ <- EitherT(
        timedStep("llvm-as", recordTiming)(
          irToBitcode(inputFile, programName, config, outputDir)
        )
      )
      optInputFile <- EitherT(
        if config.mode == CompilationMode.Exe then
          linkRuntimeBitcode(
            programName,
            targetTriple,
            config,
            outputDir,
            clangFlags,
            recordTiming
          )
        else IO.pure(programBitcode.asRight)
      )
      _ <- EitherT(
        timedStep("llvm-opt", recordTiming)(
          runOptimization(optInputFile, programName, config, outputDir, targetCpu)
        )
      )
      _ <- EitherT(
        if config.emitOptIr then
          timedStep("llvm-opt-ir", recordTiming)(
            generateOptimizedIr(programName, config, outputDir)
          )
        else IO.pure(0.asRight)
      )
      _ <- EitherT(
        timedStep("llvm-llc", recordTiming)(
          generateAssembly(programName, targetTriple, config, outputDir, targetCpu)
        )
      )
      result <- EitherT(
        compileForMode(
          programName,
          targetTriple,
          config,
          outputDir,
          targetDir,
          clangFlags,
          recordTiming
        )
      )
    yield result).value

  private def irToBitcode(
    inputFile:   File,
    programName: String,
    config:      CompilerConfig,
    outputDir:   Path
  ): IO[Either[LlvmCompilationError, Int]] =
    val sourceFile = inputFile.getAbsolutePath
    val outputFile = outputDir.resolve(s"$programName.bc").toAbsolutePath.toString
    logPhase(s"Converting IR to Bitcode", config.printPhases)
    logDebug(s"Input file: $sourceFile", config.verbose)
    logDebug(s"Output file: $outputFile", config.verbose)
    executeCommand(
      s"llvm-as $sourceFile -o $outputFile",
      "Failed to convert IR to Bitcode",
      config.outputDir,
      config.verbose
    )

  private def runOptimization(
    inputFile:   String,
    programName: String,
    config:      CompilerConfig,
    outputDir:   Path,
    targetCpu:   Option[String]
  ): IO[Either[LlvmCompilationError, Int]] =
    val outputFile = outputDir.resolve(s"${programName}_opt.bc").toAbsolutePath.toString
    val cpuFlag    = targetCpu.map(cpu => s" --mcpu=$cpu").getOrElse("")
    logPhase(s"Optimizing Bitcode", config.printPhases)
    logDebug(s"Input file: $inputFile", config.verbose)
    logDebug(s"Output file: $outputFile", config.verbose)
    executeCommand(
      s"opt -O${config.optLevel}$cpuFlag $inputFile -o $outputFile",
      "Failed to optimize bitcode",
      config.outputDir,
      config.verbose
    )

  private def generateOptimizedIr(
    programName: String,
    config:      CompilerConfig,
    outputDir:   Path
  ): IO[Either[LlvmCompilationError, Int]] =
    val inputFile  = outputDir.resolve(s"${programName}_opt.bc").toAbsolutePath.toString
    val outputFile = outputDir.resolve(s"${programName}_opt.ll").toAbsolutePath.toString
    logPhase(s"Generating optimized IR", config.printPhases)
    logDebug(s"Input file: $inputFile", config.verbose)
    logDebug(s"Output file: $outputFile", config.verbose)
    executeCommand(
      s"llvm-dis $inputFile -o $outputFile",
      "Failed to generate optimized IR",
      config.outputDir,
      config.verbose
    )

  private def generateAssembly(
    programName:  String,
    targetTriple: String,
    config:       CompilerConfig,
    outputDir:    Path,
    targetCpu:    Option[String]
  ): IO[Either[LlvmCompilationError, Int]] =
    val inputFile  = outputDir.resolve(s"${programName}_opt.bc").toAbsolutePath.toString
    val outputFile = outputDir.resolve(s"$programName.s").toAbsolutePath.toString
    val cpuFlag    = targetCpu.map(cpu => s" --mcpu=$cpu").getOrElse("")
    logPhase(s"Generating assembly", config.printPhases)
    logDebug(s"Input file: $inputFile", config.verbose)
    logDebug(s"Output file: $outputFile", config.verbose)
    executeCommand(
      s"llc -mtriple=$targetTriple$cpuFlag $inputFile -o $outputFile",
      "Failed to convert Bitcode to Assembly",
      config.outputDir,
      config.verbose
    )

  private def compileForMode(
    programName:  String,
    targetTriple: String,
    config:       CompilerConfig,
    outputDir:    Path,
    targetDir:    Path,
    clangFlags:   List[String],
    recordTiming: Option[TimingRecorder]
  ): IO[Either[LlvmCompilationError, Int]] = config.mode match
    case CompilationMode.Exe =>
      compileBinary(
        programName,
        targetTriple,
        config,
        outputDir,
        targetDir,
        clangFlags,
        recordTiming
      )
    case CompilationMode.Library =>
      compileLibrary(
        programName,
        targetTriple,
        config,
        outputDir,
        targetDir,
        clangFlags,
        recordTiming
      )
    case CompilationMode.Ast | CompilationMode.Ir | CompilationMode.Dev =>
      IO.pure(0.asRight)

  /** Path to the MML runtime file in resources */
  private val mmlRuntimeResourcePath = "mml_runtime.c"

  /** Filename for the MML runtime file */
  private val mmlRuntimeFilename = "mml_runtime.c"

  /** Get the filename for the compiled MML runtime object for a specific target */
  private def mmlRuntimeObjectFilename(targetTriple: String): String =
    s"mml_runtime-$targetTriple.o"

  /** Get the filename for the compiled MML runtime bitcode for a specific target */
  private def mmlRuntimeBitcodeFilename(targetTriple: String): String =
    s"mml_runtime-$targetTriple.bc"

  private def extractRuntimeResource(
    outputDir:   Path,
    verbose:     Boolean,
    printPhases: Boolean
  ): IO[Either[LlvmCompilationError, String]] = IO.defer {
    val sourcePath = outputDir.resolve(mmlRuntimeFilename).toAbsolutePath

    if Files.exists(sourcePath) then
      logDebug(s"Runtime source already exists at $sourcePath", verbose)
      IO.pure(sourcePath.toString.asRight)
    else
      IO.blocking {
        try
          logPhase("Extracting MML runtime source", printPhases)
          logDebug(s"Destination: $sourcePath", verbose)

          val resourceStream =
            val classLoader = getClass.getClassLoader
            val paths = List(
              mmlRuntimeResourcePath,
              s"/$mmlRuntimeResourcePath",
              s"modules/mmlc-lib/src/main/resources/$mmlRuntimeResourcePath",
              s"/modules/mmlc-lib/src/main/resources/$mmlRuntimeResourcePath"
            )

            val stream = paths.foldLeft[Option[InputStream]](None) { (acc, path) =>
              acc.orElse {
                val s = Option(classLoader.getResourceAsStream(path))
                if s.isDefined then logDebug(s"Found resource at path: $path", verbose)
                s
              }
            }

            stream.getOrElse {
              val localPath =
                Paths.get("modules/mmlc-lib/src/main/resources", mmlRuntimeResourcePath)
              logDebug(s"Trying to read from file system at: $localPath", verbose)
              if Files.exists(localPath) then
                logDebug(s"Found file at: $localPath", verbose)
                Files.newInputStream(localPath)
              else
                // FIXME:QA: Exceptions are not allowed in this codebase.
                throw new Exception(
                  s"Could not find resource: $mmlRuntimeResourcePath (tried multiple paths)"
                )
            }

          Files.copy(resourceStream, sourcePath, StandardCopyOption.REPLACE_EXISTING)
          resourceStream.close()
          logDebug(s"Successfully extracted runtime source to: $sourcePath", verbose)
          sourcePath.toString.asRight
        catch
          case e: Exception =>
            val error = LlvmCompilationError.RuntimeResourceError(
              s"Failed to extract runtime source: ${e.getMessage}"
            )
            logError(error.toString)
            error.asLeft
      }
  }

  private def compileRuntime(
    outputDir:    Path,
    targetTriple: String,
    config:       CompilerConfig,
    clangFlags:   List[String]
  ): IO[Either[LlvmCompilationError, String]] = IO.defer {
    val runtimeFilename = mmlRuntimeObjectFilename(targetTriple)
    val objPath         = outputDir.resolve(runtimeFilename).toAbsolutePath

    logPhase("Compiling runtime", config.printPhases)
    if Files.exists(objPath) then
      logInfo(
        s"Runtime object already exists at $objPath, skipping compilation",
        config.printPhases
      )
      IO.pure(objPath.toString.asRight)
    else
      for
        sourceResult <- extractRuntimeResource(outputDir, config.verbose, config.printPhases)
        result <- sourceResult match
          case Left(error) => IO.pure(error.asLeft)
          case Right(sourcePath) =>
            logPhase("Compiling MML runtime", config.printPhases)
            logDebug(s"Input file: $sourcePath", config.verbose)
            logDebug(s"Output file: $objPath", config.verbose)

            val cmd = (List(
              "clang",
              "-target",
              targetTriple,
              "-c",
              "-std=c17",
              s"-O${config.optLevel}",
              "-flto"
            ) ++ clangFlags ++ List("-fPIC", "-o", objPath.toString, sourcePath)).mkString(" ")
            executeCommand(cmd, "Failed to compile MML runtime", config.outputDir, config.verbose)
              .map {
                case Left(error) => error.asLeft
                case Right(_) => objPath.toString.asRight
              }
      yield result
  }

  private def compileRuntimeBitcode(
    outputDir:    Path,
    targetTriple: String,
    config:       CompilerConfig,
    clangFlags:   List[String]
  ): IO[Either[LlvmCompilationError, String]] = IO.defer {
    val runtimeFilename = mmlRuntimeBitcodeFilename(targetTriple)
    val bcPath          = outputDir.resolve(runtimeFilename).toAbsolutePath

    logPhase("Compiling runtime bitcode", config.printPhases)
    if Files.exists(bcPath) then
      logInfo(s"Runtime bitcode present, skipping", config.printPhases)
      IO.pure(bcPath.toString.asRight)
    else
      for
        sourceResult <- extractRuntimeResource(outputDir, config.verbose, config.printPhases)
        result <- sourceResult match
          case Left(error) => IO.pure(error.asLeft)
          case Right(sourcePath) =>
            logPhase("Compiling runtime bitcode", config.printPhases)
            logDebug(s"Input file: $sourcePath", config.verbose)
            logDebug(s"Output file: $bcPath", config.verbose)

            val cpuFlags = config.targetCpu.map(cpu => List(s"-mcpu=$cpu")).getOrElse(Nil)
            val cmd = (List(
              "clang",
              "-target",
              targetTriple,
              "-emit-llvm",
              "-c",
              "-std=c17",
              s"-O${config.optLevel}"
            ) ++ cpuFlags ++ clangFlags ++ List("-fPIC", "-o", bcPath.toString, sourcePath))
              .mkString(" ")
            executeCommand(
              cmd,
              "Failed to compile MML runtime bitcode",
              config.outputDir,
              config.verbose
            ).map {
              case Left(error) => error.asLeft
              case Right(_) => bcPath.toString.asRight
            }
      yield result
  }

  private def linkRuntimeBitcode(
    programName:  String,
    targetTriple: String,
    config:       CompilerConfig,
    outputDir:    Path,
    clangFlags:   List[String],
    recordTiming: Option[TimingRecorder]
  ): IO[Either[LlvmCompilationError, String]] =
    val programBitcode = outputDir.resolve(s"$programName.bc").toAbsolutePath.toString
    val linkedBitcode  = outputDir.resolve(s"${programName}_linked.bc").toAbsolutePath.toString

    for
      runtimeResult <- timedStep("llvm-runtime-bitcode", recordTiming)(
        compileRuntimeBitcode(outputDir, targetTriple, config, clangFlags)
      )
      result <- runtimeResult match
        case Left(error) => IO.pure(error.asLeft)
        case Right(runtimePath) =>
          logPhase("Linking MML runtime bitcode", config.printPhases)
          logDebug(s"Program bitcode: $programBitcode", config.verbose)
          logDebug(s"Runtime bitcode: $runtimePath", config.verbose)
          logDebug(s"Output file: $linkedBitcode", config.verbose)

          timedStep("llvm-link", recordTiming)(
            executeCommand(
              s"llvm-link $programBitcode $runtimePath -o $linkedBitcode",
              "Failed to link runtime bitcode",
              config.outputDir,
              config.verbose
            )
          ).map {
            case Left(error) => error.asLeft
            case Right(_) => linkedBitcode.asRight
          }
    yield result

  private def compileBinary(
    programName:  String,
    targetTriple: String,
    config:       CompilerConfig,
    outputDir:    Path,
    targetDir:    Path,
    clangFlags:   List[String],
    recordTiming: Option[TimingRecorder]
  ): IO[Either[LlvmCompilationError, Int]] =
    val targetDirPath = targetDir.toAbsolutePath
    if !Files.exists(targetDirPath) then
      logDebug(s"Creating target directory: $targetDirPath", config.verbose)
      Files.createDirectories(targetDirPath)

    val finalExecutablePath = config.outputName match
      case Some(path) => Paths.get(path).toAbsolutePath.toString
      case None =>
        val baseName = programName.toLowerCase
        val finalName =
          if config.targetTriple.isDefined then s"$baseName-$targetTriple" else baseName
        targetDirPath.resolve(finalName).toString
    val inputFile = outputDir.resolve(s"$programName.s").toAbsolutePath.toString

    val outputPath = Paths.get(finalExecutablePath)
    val parentDir  = outputPath.getParent
    if parentDir != null && !Files.exists(parentDir) then Files.createDirectories(parentDir)

    logPhase(s"Compiling and linking executable", config.printPhases)
    logDebug(s"Input file: $inputFile", config.verbose)
    logDebug(s"Output file: $finalExecutablePath", config.verbose)

    timedStep("llvm-compile-binary", recordTiming)(
      executeCommand(
        (List(
          "clang",
          "-target",
          targetTriple,
          "-fuse-ld=lld",
          s"-O${config.optLevel}"
        ) ++
          clangFlags ++ List(inputFile, "-o", finalExecutablePath)).mkString(" "),
        "Failed to compile and link",
        config.outputDir,
        config.verbose
      )
    ).map {
      case Left(error) => error.asLeft
      case Right(_) =>
        logInfo(s"Native code generation successful. Exit code: 0", config.printPhases)
        0.asRight
    }

  private def compileLibrary(
    programName:  String,
    targetTriple: String,
    config:       CompilerConfig,
    outputDir:    Path,
    targetDir:    Path,
    clangFlags:   List[String],
    recordTiming: Option[TimingRecorder]
  ): IO[Either[LlvmCompilationError, Int]] =
    val targetDirPath = targetDir.toAbsolutePath
    if !Files.exists(targetDirPath) then
      logDebug(s"Creating target directory: $targetDirPath", config.verbose)
      Files.createDirectories(targetDirPath)

    val finalLibraryPath = config.outputName match
      case Some(path) =>
        val p = Paths.get(path).toAbsolutePath.toString
        if p.endsWith(".o") then p else s"$p.o"
      case None =>
        val baseName = programName.toLowerCase
        val finalName =
          if config.targetTriple.isDefined then s"$baseName-$targetTriple" else baseName
        targetDirPath.resolve(s"$finalName.o").toString
    val inputFile = outputDir.resolve(s"$programName.s").toAbsolutePath.toString

    val outputPath = Paths.get(finalLibraryPath)
    val parentDir  = outputPath.getParent
    if parentDir != null && !Files.exists(parentDir) then Files.createDirectories(parentDir)

    for
      runtimeResult <- timedStep("llvm-runtime-object", recordTiming)(
        compileRuntime(outputDir, targetTriple, config, clangFlags)
      )
      result <- runtimeResult match
        case Left(error) => IO.pure(error.asLeft)
        case Right(runtimePath) =>
          logPhase(s"Compiling library object with MML runtime", config.printPhases)
          logDebug(s"Input file: $inputFile", config.verbose)
          logDebug(s"Runtime: $runtimePath", config.verbose)
          logDebug(s"Output file: $finalLibraryPath", config.verbose)

          timedStep("llvm-compile-library", recordTiming)(
            executeCommand(
              (List("clang", "-target", targetTriple, "-c") ++ clangFlags ++
                List(inputFile, "-o", finalLibraryPath)).mkString(" "),
              "Failed to compile library object",
              config.outputDir,
              config.verbose
            )
          ).map {
            case Left(error) => error.asLeft
            case Right(_) =>
              val runtimeTargetPath =
                targetDirPath.resolve(mmlRuntimeObjectFilename(targetTriple)).toString
              logInfo(s"Copying runtime to $runtimeTargetPath", config.printPhases)
              try
                Files.copy(
                  Paths.get(runtimePath),
                  Paths.get(runtimeTargetPath),
                  StandardCopyOption.REPLACE_EXISTING
                )
                logInfo(s"Library object generation successful. Exit code: 0", config.printPhases)
                logInfo(
                  s"Note: Link with ${mmlRuntimeObjectFilename(targetTriple)} when using this library.",
                  config.printPhases
                )
                0.asRight
              catch
                case e: Exception =>
                  val error = LlvmCompilationError.RuntimeResourceError(
                    s"Failed to copy runtime object to target: ${e.getMessage}"
                  )
                  logError(error.toString)
                  error.asLeft
          }
    yield result

  private def createOutputDir(outputDir: Path, printPhases: Boolean): IO[Unit] =
    IO {
      val dirPath = outputDir.toAbsolutePath
      if !Files.exists(dirPath) then
        logInfo(s"Creating directory: $dirPath", printPhases)
        Files.createDirectories(dirPath)
    }

  private def detectOsTargetTriple(
    userProvidedTriple: Option[String]
  ): IO[Either[LlvmCompilationError, String]] =
    IO {
      userProvidedTriple match
        case Some(triple) => triple.asRight
        case None =>
          val os   = System.getProperty("os.name")
          val arch = System.getProperty("os.arch").toLowerCase

          if os.startsWith("Mac") then
            if arch == "aarch64" || arch == "arm64" then "aarch64-apple-macosx".asRight
            else "x86_64-apple-macosx".asRight
          else if os.startsWith("Linux") then
            if arch == "aarch64" || arch == "arm64" then "aarch64-pc-linux-gnu".asRight
            else "x86_64-pc-linux-gnu".asRight
          else LlvmCompilationError.UnsupportedOperatingSystem(os).asLeft
    }

  private def executeCommand(
    cmd:        String,
    errorMsg:   String,
    workingDir: Path,
    verbose:    Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    IO.defer {
      val setupDir = IO.blocking {
        val absPath        = workingDir.toAbsolutePath
        val workingDirFile = absPath.toFile
        if !workingDirFile.exists() then
          logDebug(s"Creating working directory: $absPath", verbose)
          workingDirFile.mkdirs()
        logDebug(s"Executing command: $cmd", verbose)
        logDebug(s"In working directory: $absPath", verbose)
        workingDirFile
      }

      setupDir.flatMap { workingDirFile =>
        IO.blocking {
          try
            val exitCode = Process(cmd, workingDirFile).!
            if exitCode != 0 then
              val error = LlvmCompilationError.CommandExecutionError(cmd, errorMsg, exitCode)
              logError(s"Command failed with exit code $exitCode: $error")
              error.asLeft
            else
              logDebug(s"Command completed successfully with exit code $exitCode", verbose)
              exitCode.asRight
          catch
            case e: java.io.IOException =>
              logError(s"Tool execution failed: ${e.getMessage}")
              logError(
                s"Tool ${cmd.split(" ").head} missing, marker file present. Have you uninstalled llvm? Removing marker file."
              )
              logError("Verify your llvm installation and try again")
              logError(llvmInstallInstructions)
              LlvmCompilationError
                .CommandExecutionError(cmd, s"Tool not found: ${e.getMessage}", -1)
                .asLeft
        }.flatMap { result =>
          if result.isLeft && result.left.exists {
              case LlvmCompilationError.CommandExecutionError(_, msg, code) =>
                msg.contains("Tool not found") && code == -1
              case _ => false
            }
          then invalidateToolsMarker(workingDir).as(result)
          else IO.pure(result)
        }
      }
    }

  def gatherLlvmInfo(
    buildDir:    Path,
    verbose:     Boolean,
    printPhases: Boolean
  ): IO[Either[LlvmCompilationError, Unit]] = IO.defer {
    val markerFilePath = buildDir.resolve(llvmInfoFile)
    if Files.exists(markerFilePath) && markerHasTools(markerFilePath, llvmTools) then
      logDebug(s"LLVM tools already verified (marker file exists)", verbose)
      if verbose then {
        try {
          val content = Files.readString(markerFilePath)
          logDebug("Verification details from previous check:", verbose)
          content.split('\n').foreach(line => logDebug(line, verbose))
        } catch {
          case _: Exception => // Ignore errors reading the file
        }
      }
      IO.pure(().asRight)
    else
      for {
        _ <-
          if Files.exists(markerFilePath) then invalidateToolsMarker(buildDir)
          else IO.unit
        _ <- IO(
          logPhase(s"Checking for required LLVM tools: ${llvmTools.mkString(", ")}", printPhases)
        )
        timestamp = java.time.LocalDateTime
          .now()
          .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        result <- collectLlvmToolVersions().flatMap { case ToolInfo(versions, missingTools) =>
          if missingTools.isEmpty then
            IO.blocking {
              try {
                Files.createDirectories(markerFilePath.getParent)
                val content = new StringBuilder()
                content.append(s"LLVM tools verified: $timestamp\n\n")
                versions.foreach { case (tool, version) =>
                  content.append(s"------------ $tool ------------\n")
                  content.append(s"$version\n\n")
                }
                Files.writeString(markerFilePath, content.toString)
                ().asRight
              } catch {
                case e: Exception =>
                  logDebug(s"Warning: Failed to create marker file: ${e.getMessage}", verbose)
                  ().asRight
              }
            }
          else
            IO.pure {
              val toolsMessage = missingTools.mkString(", ")
              logError(s"Required LLVM tools not found: $toolsMessage")
              logError(llvmInstallInstructions)
              LlvmCompilationError.LlvmNotInstalled(missingTools).asLeft[Unit]
            }
        }
      } yield result
  }

  private def markerHasTools(
    markerFilePath: java.nio.file.Path,
    tools:          List[String]
  ): Boolean =
    try
      val content = Files.readString(markerFilePath)
      tools.forall(tool => content.contains(s"------------ $tool ------------"))
    catch case _: Exception => false

  private def logModule(message: String, printPhases: Boolean): Unit =
    if printPhases then println(message)

  private def logInfo(message: String, printPhases: Boolean): Unit =
    if printPhases then println(message)

  private def logError(message: String): Unit =
    println(s"${Console.RED}${message}${Console.RESET}")

  private def logPhase(message: String, printPhases: Boolean): Unit =
    if printPhases then println(s"${Console.BLUE}${message}${Console.RESET}")

  private def logDebug(message: String, verbose: Boolean): Unit =
    if verbose then println(s"  ${message}")

  private def invalidateToolsMarker(buildDir: Path): IO[Unit] = IO.blocking {
    val timestamp = java.time.LocalDateTime
      .now()
      .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      .replace(":", "-")
    val currentMarker  = buildDir.resolve(llvmInfoFile)
    val archivedMarker = buildDir.resolve(s"$llvmInfoFile-$timestamp")
    if Files.exists(currentMarker) then {
      try {
        Files.move(currentMarker, archivedMarker)
        logInfo(
          s"LLVM tools check invalidated. Marker moved to ${archivedMarker.getFileName}",
          true
        )
      } catch {
        case e: Exception =>
          logError(s"Failed to archive LLVM tools marker: ${e.getMessage}")
          try Files.deleteIfExists(currentMarker)
          // TODO: do not swallow exceptions
          catch { case _: Exception => }
      }
    }
  }
