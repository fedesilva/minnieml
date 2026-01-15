package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.errors.CompilationError

import java.io.{File, InputStream}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.sys.process.{Process, ProcessLogger}

case class ToolInfo(versions: Map[String, String], missing: List[String])

case class PipelineTiming(name: String, durationNanos: Long)

enum CompilationMode derives CanEqual:
  case Binary
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
  private val baseLlvmTools = List("llvm-as", "llvm-link", "opt", "llc", "clang")
  private val optIrTools    = List("llvm-dis")

  private def requiredLlvmTools(emitOptIr: Boolean): List[String] =
    if emitOptIr then baseLlvmTools ++ optIrTools else baseLlvmTools

  private type TimingRecorder = PipelineTiming => Unit

  private def clangCpuFlags(
    userProvidedTriple: Boolean,
    targetArch:         Option[String],
    targetCpu:          Option[String]
  ): List[String] =
    if userProvidedTriple then
      List(
        targetArch.map(arch => s"-march=$arch"),
        targetCpu.map(cpu => s"-mcpu=$cpu")
      ).flatten
    else List("-march=native")

  private def clangStackProbeFlags(noStackCheck: Boolean): List[String] =
    if noStackCheck then List("-fno-stack-check") else Nil

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

  /** Marker file name to cache successful LLVM tool checks */
  private val llvmCheckMarkerFile = "llvm-check-ok"

  /** File name to cache the local target triple */
  private val localTargetTripleFile = "local-target-triple"
  private val hostCpuPrefix         = "Host CPU:"

  /** Installation instructions for different platforms */
  val llvmInstallInstructions: String =
    """
      |Installation instructions:
      |
      |- macOS: brew install llvm
      |- Ubuntu/Debian: apt-get install llvm clang
      |
      |Make sure the tools are in your PATH.
    """.stripMargin

  /** Collects version information of the requested LLVM tools.
    *
    * @return
    *   An IO effect with ToolInfo containing tool version info and missing tools.
    */
  def collectLlvmToolVersions(
    tools: List[String] = baseLlvmTools
  ): IO[ToolInfo] = IO.blocking {
    tools.foldLeft(ToolInfo(Map.empty[String, String], Nil)) {
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

  /** Parse Host CPU from the llvm-check-ok marker if available. */
  def readHostCpu(buildDir: String): Option[String] =
    def findMarker(path: Path): Option[Path] =
      if path == null then None
      else
        val candidate = path.resolve(llvmCheckMarkerFile)
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
    llvmIrPath:       Path,
    workingDirectory: String,
    mode:             CompilationMode = CompilationMode.Binary,
    verbose:          Boolean         = false,
    targetTriple:     Option[String]  = None,
    targetArch:       Option[String]  = None,
    targetCpu:        Option[String]  = None,
    noStackCheck:     Boolean         = false,
    emitOptIr:        Boolean         = false,
    outputName:       Option[String]  = None,
    explicitTriple:   Option[String]  = None,
    printPhases:      Boolean         = false,
    optLevel:         Int             = 3
  ): IO[Either[LlvmCompilationError, Int]] =
    compileInternal(
      llvmIrPath,
      workingDirectory,
      mode,
      verbose,
      targetTriple,
      targetArch,
      targetCpu,
      noStackCheck,
      emitOptIr,
      outputName,
      explicitTriple,
      recordTiming = None,
      printPhases  = printPhases,
      optLevel     = optLevel
    )

  def compileWithTimings(
    llvmIrPath:       Path,
    workingDirectory: String,
    mode:             CompilationMode = CompilationMode.Binary,
    verbose:          Boolean         = false,
    targetTriple:     Option[String]  = None,
    targetArch:       Option[String]  = None,
    targetCpu:        Option[String]  = None,
    noStackCheck:     Boolean         = false,
    emitOptIr:        Boolean         = false,
    outputName:       Option[String]  = None,
    explicitTriple:   Option[String]  = None,
    printPhases:      Boolean         = false,
    optLevel:         Int             = 3
  ): IO[(Either[LlvmCompilationError, Int], Vector[PipelineTiming])] =
    val timings = Vector.newBuilder[PipelineTiming]
    val record: TimingRecorder = timing => timings += timing
    compileInternal(
      llvmIrPath,
      workingDirectory,
      mode,
      verbose,
      targetTriple,
      targetArch,
      targetCpu,
      noStackCheck,
      emitOptIr,
      outputName,
      explicitTriple,
      recordTiming = Some(record),
      printPhases  = printPhases,
      optLevel     = optLevel
    ).map(result => result -> timings.result())

  private def compileInternal(
    llvmIrPath:       Path,
    workingDirectory: String,
    mode:             CompilationMode,
    verbose:          Boolean,
    targetTriple:     Option[String],
    targetArch:       Option[String],
    targetCpu:        Option[String],
    noStackCheck:     Boolean,
    emitOptIr:        Boolean,
    outputName:       Option[String],
    explicitTriple:   Option[String],
    recordTiming:     Option[TimingRecorder],
    printPhases:      Boolean,
    optLevel:         Int
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
        _ <- IO(logModule(s"Compiling module $moduleName", printPhases))
        _ <- IO(logInfo(s"Working directory: $workingDirectory", printPhases))
        _ <- IO(logInfo(s"Compilation mode: $mode", printPhases))
        _ <- createOutputDir(workingDirectory, printPhases)
        toolsCheckResult <-
          timedStep("llvm-check-tools", recordTiming)(
            checkLlvmTools(workingDirectory, verbose, requiredLlvmTools(emitOptIr), printPhases)
          )
        result <- toolsCheckResult match
          case Left(error) =>
            IO.pure(error.asLeft)
          case Right(_) =>
            processLlvmFile(
              inputFile,
              workingDirectory,
              mode,
              verbose,
              targetTriple,
              targetArch,
              targetCpu,
              noStackCheck,
              emitOptIr,
              outputName,
              explicitTriple,
              recordTiming,
              printPhases,
              optLevel
            )
      yield result

  private def processLlvmFile(
    inputFile:        File,
    workingDirectory: String,
    mode:             CompilationMode,
    verbose:          Boolean,
    targetTriple:     Option[String],
    targetArch:       Option[String],
    targetCpu:        Option[String],
    noStackCheck:     Boolean,
    emitOptIr:        Boolean,
    outputName:       Option[String],
    explicitTriple:   Option[String],
    recordTiming:     Option[TimingRecorder],
    printPhases:      Boolean,
    optLevel:         Int
  ): IO[Either[LlvmCompilationError, Int]] =
    val programName   = programNameFrom(inputFile.toPath)
    val baseOutputDir = s"$workingDirectory/out"
    val targetDir     = s"$workingDirectory/target"

    for
      targetTripleResult <- detectOsTargetTriple(targetTriple)
      result <- targetTripleResult match
        case Left(error) =>
          IO(logError(s"Error detecting target triple: $error")) *>
            IO.pure(error.asLeft)
        case Right(triple) =>
          val outputDir          = s"$baseOutputDir/$triple"
          val userProvidedTriple = targetTriple.nonEmpty
          for
            _ <- createOutputDir(outputDir, printPhases)
            _ <- createOutputDir(targetDir, printPhases)
            result <- processWithTargetTriple(
              Right(triple),
              inputFile,
              programName,
              workingDirectory,
              outputDir,
              targetDir,
              mode,
              verbose,
              userProvidedTriple,
              targetArch,
              targetCpu,
              noStackCheck,
              emitOptIr,
              outputName,
              explicitTriple,
              recordTiming,
              printPhases,
              optLevel
            )
          yield result
    yield result

  private def processWithTargetTriple(
    targetTripleResult: Either[LlvmCompilationError, String],
    inputFile:          File,
    programName:        String,
    workingDirectory:   String,
    outputDir:          String,
    targetDir:          String,
    mode:               CompilationMode,
    verbose:            Boolean,
    userProvidedTriple: Boolean,
    targetArch:         Option[String],
    targetCpu:          Option[String],
    noStackCheck:       Boolean,
    emitOptIr:          Boolean,
    outputName:         Option[String],
    explicitTriple:     Option[String],
    recordTiming:       Option[TimingRecorder],
    printPhases:        Boolean,
    optLevel:           Int
  ): IO[Either[LlvmCompilationError, Int]] =
    targetTripleResult match
      case Left(error) =>
        IO(logError(s"Error detecting target triple: $error")) *>
          IO.pure(error.asLeft)
      case Right(targetTriple) =>
        logPhase(s"Starting LLVM compilation pipeline for $programName", printPhases)
        logInfo(s"Compilation mode: $mode", printPhases)
        logInfo(s"Using target triple: $targetTriple", printPhases)
        runCompilationPipeline(
          inputFile,
          programName,
          targetTriple,
          workingDirectory,
          outputDir,
          targetDir,
          mode,
          verbose,
          userProvidedTriple,
          targetArch,
          targetCpu,
          noStackCheck,
          emitOptIr,
          outputName,
          explicitTriple,
          recordTiming,
          printPhases,
          optLevel
        )

  private def runCompilationPipeline(
    inputFile:          File,
    programName:        String,
    targetTriple:       String,
    workingDirectory:   String,
    outputDir:          String,
    targetDir:          String,
    mode:               CompilationMode,
    verbose:            Boolean,
    userProvidedTriple: Boolean,
    targetArch:         Option[String],
    targetCpu:          Option[String],
    noStackCheck:       Boolean,
    emitOptIr:          Boolean,
    outputName:         Option[String],
    explicitTriple:     Option[String],
    recordTiming:       Option[TimingRecorder],
    printPhases:        Boolean,
    optLevel:           Int
  ): IO[Either[LlvmCompilationError, Int]] =
    import cats.data.EitherT

    val programBitcode = Paths.get(outputDir).resolve(s"$programName.bc").toAbsolutePath.toString
    val clangFlags = clangCpuFlags(userProvidedTriple, targetArch, targetCpu) ++
      clangStackProbeFlags(noStackCheck)

    (for
      _ <- EitherT(
        timedStep("llvm-as", recordTiming)(
          irToBitcode(inputFile, programName, workingDirectory, outputDir, verbose, printPhases)
        )
      )
      optInputFile <- EitherT(
        if mode == CompilationMode.Binary then
          linkRuntimeBitcode(
            programName,
            targetTriple,
            workingDirectory,
            outputDir,
            verbose,
            clangFlags,
            recordTiming,
            printPhases,
            optLevel
          )
        else IO.pure(programBitcode.asRight)
      )
      _ <- EitherT(
        timedStep("llvm-opt", recordTiming)(
          runOptimization(
            optInputFile,
            programName,
            workingDirectory,
            outputDir,
            verbose,
            printPhases,
            optLevel
          )
        )
      )
      _ <- EitherT(
        if emitOptIr then
          timedStep("llvm-opt-ir", recordTiming)(
            generateOptimizedIr(programName, workingDirectory, outputDir, verbose, printPhases)
          )
        else IO.pure(0.asRight)
      )
      _ <- EitherT(
        timedStep("llvm-llc", recordTiming)(
          generateAssembly(
            programName,
            targetTriple,
            workingDirectory,
            outputDir,
            verbose,
            printPhases
          )
        )
      )
      result <- EitherT(
        compileForMode(
          programName,
          targetTriple,
          workingDirectory,
          outputDir,
          targetDir,
          mode,
          verbose,
          clangFlags,
          outputName,
          explicitTriple,
          recordTiming,
          printPhases,
          optLevel
        )
      )
    yield result).value

  private def irToBitcode(
    inputFile:        File,
    programName:      String,
    workingDirectory: String,
    outputDir:        String,
    verbose:          Boolean,
    printPhases:      Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val sourceFile = inputFile.getAbsolutePath
    val outputFile = Paths.get(outputDir).resolve(s"$programName.bc").toAbsolutePath.toString
    logPhase(s"Converting IR to Bitcode", printPhases)
    logDebug(s"Input file: $sourceFile", verbose)
    logDebug(s"Output file: $outputFile", verbose)
    executeCommand(
      s"llvm-as $sourceFile -o $outputFile",
      "Failed to convert IR to Bitcode",
      workingDirectory,
      verbose
    )

  private def runOptimization(
    inputFile:        String,
    programName:      String,
    workingDirectory: String,
    outputDir:        String,
    verbose:          Boolean,
    printPhases:      Boolean,
    optLevel:         Int
  ): IO[Either[LlvmCompilationError, Int]] =
    val outputFile =
      Paths.get(outputDir).resolve(s"${programName}_opt.bc").toAbsolutePath.toString
    logPhase(s"Optimizing Bitcode", printPhases)
    logDebug(s"Input file: $inputFile", verbose)
    logDebug(s"Output file: $outputFile", verbose)
    executeCommand(
      s"opt -O$optLevel $inputFile -o $outputFile",
      "Failed to optimize bitcode",
      workingDirectory,
      verbose
    )

  private def generateOptimizedIr(
    programName:      String,
    workingDirectory: String,
    outputDir:        String,
    verbose:          Boolean,
    printPhases:      Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val inputFile  = Paths.get(outputDir).resolve(s"${programName}_opt.bc").toAbsolutePath.toString
    val outputFile = Paths.get(outputDir).resolve(s"${programName}_opt.ll").toAbsolutePath.toString
    logPhase(s"Generating optimized IR", printPhases)
    logDebug(s"Input file: $inputFile", verbose)
    logDebug(s"Output file: $outputFile", verbose)
    executeCommand(
      s"llvm-dis $inputFile -o $outputFile",
      "Failed to generate optimized IR",
      workingDirectory,
      verbose
    )

  private def generateAssembly(
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    verbose:          Boolean,
    printPhases:      Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val inputFile  = Paths.get(outputDir).resolve(s"${programName}_opt.bc").toAbsolutePath.toString
    val outputFile = Paths.get(outputDir).resolve(s"$programName.s").toAbsolutePath.toString
    logPhase(s"Generating assembly", printPhases)
    logDebug(s"Input file: $inputFile", verbose)
    logDebug(s"Output file: $outputFile", verbose)
    executeCommand(
      s"llc -mtriple=$targetTriple $inputFile -o $outputFile",
      "Failed to convert Bitcode to Assembly",
      workingDirectory,
      verbose
    )

  private def compileForMode(
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    targetDir:        String,
    mode:             CompilationMode,
    verbose:          Boolean,
    clangFlags:       List[String],
    outputName:       Option[String],
    explicitTriple:   Option[String],
    recordTiming:     Option[TimingRecorder],
    printPhases:      Boolean,
    optLevel:         Int
  ): IO[Either[LlvmCompilationError, Int]] = mode match
    case CompilationMode.Binary =>
      compileBinary(
        programName,
        targetTriple,
        workingDirectory,
        outputDir,
        targetDir,
        verbose,
        clangFlags,
        outputName,
        explicitTriple,
        recordTiming,
        printPhases,
        optLevel
      )
    case CompilationMode.Library =>
      compileLibrary(
        programName,
        targetTriple,
        workingDirectory,
        outputDir,
        targetDir,
        verbose,
        clangFlags,
        outputName,
        explicitTriple,
        recordTiming,
        printPhases,
        optLevel
      )
    case CompilationMode.Ast | CompilationMode.Ir | CompilationMode.Dev =>
      // No compilation needed for these modes
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

  /** Extracts the MML runtime source from resources to the output directory.
    *
    * @param outputDir
    *   The directory where the runtime source should be extracted
    * @param verbose
    *   Enable verbose logging
    * @return
    *   Either an error or the path to the extracted source file
    */
  private def extractRuntimeResource(
    outputDir:   String,
    verbose:     Boolean,
    printPhases: Boolean
  ): IO[Either[LlvmCompilationError, String]] = IO.defer {
    val sourcePath = Paths.get(outputDir).resolve(mmlRuntimeFilename).toAbsolutePath.toString

    if Files.exists(Paths.get(sourcePath)) then
      logDebug(s"Runtime source already exists at $sourcePath", verbose)
      IO.pure(sourcePath.asRight)
    else
      IO.blocking {
        try {
          logPhase("Extracting MML runtime source", printPhases)
          logDebug(s"Destination: $sourcePath", verbose)

          // Try different class loaders and resource paths
          val resourceStream = {
            val classLoader = getClass.getClassLoader

            // Try various resource paths
            val paths = List(
              mmlRuntimeResourcePath,
              s"/${mmlRuntimeResourcePath}",
              s"modules/mmlc-lib/src/main/resources/${mmlRuntimeResourcePath}",
              s"/modules/mmlc-lib/src/main/resources/${mmlRuntimeResourcePath}"
            )

            val stream = paths.foldLeft[Option[InputStream]](None) { (acc, path) =>
              acc.orElse {
                val s = Option(classLoader.getResourceAsStream(path))
                if s.isDefined then logDebug(s"Found resource at path: $path", verbose)
                s
              }
            }

            stream.getOrElse {
              // If we can't find it in resources, try reading it directly from the file system
              val localPath =
                Paths.get("modules/mmlc-lib/src/main/resources", mmlRuntimeResourcePath)
              logDebug(s"Trying to read from file system at: $localPath", verbose)

              if Files.exists(localPath) then
                logDebug(s"Found file at: $localPath", verbose)
                Files.newInputStream(localPath)
              else
                throw new Exception(
                  s"Could not find resource: $mmlRuntimeResourcePath (tried multiple paths)"
                )
            }
          }

          Files.copy(
            resourceStream,
            Paths.get(sourcePath),
            StandardCopyOption.REPLACE_EXISTING
          )
          resourceStream.close()

          logDebug(s"Successfully extracted runtime source to: $sourcePath", verbose)
          sourcePath.asRight
        } catch {
          case e: Exception =>
            val error = LlvmCompilationError.RuntimeResourceError(
              s"Failed to extract runtime source: ${e.getMessage}"
            )
            logError(error.toString)
            error.asLeft
        }
      }
  }

  /** Compiles the MML runtime source to an object file if it doesn't already exist.
    *
    * @param outputDir
    *   The directory where the runtime files should be placed
    * @param verbose
    *   Enable verbose logging
    * @return
    *   Either an error or the path to the compiled object file
    */
  private def compileRuntime(
    outputDir:    String,
    verbose:      Boolean,
    targetTriple: String,
    clangFlags:   List[String],
    printPhases:  Boolean,
    optLevel:     Int
  ): IO[Either[LlvmCompilationError, String]] = IO.defer {
    val runtimeFilename = mmlRuntimeObjectFilename(targetTriple)
    val objPath         = Paths.get(outputDir).resolve(runtimeFilename).toAbsolutePath.toString

    logPhase("Compiling runtime", printPhases)
    if Files.exists(Paths.get(objPath)) then
      logInfo(s"Runtime object already exists at $objPath, skipping compilation", printPhases)
      IO.pure(objPath.asRight)
    else
      for
        sourceResult <- extractRuntimeResource(outputDir, verbose, printPhases)
        result <- sourceResult match
          case Left(error) => IO.pure(error.asLeft)
          case Right(sourcePath) =>
            logPhase("Compiling MML runtime", printPhases)
            logDebug(s"Input file: $sourcePath", verbose)
            logDebug(s"Output file: $objPath", verbose)

            val cmd = (List(
              "clang",
              "-target",
              targetTriple,
              "-c",
              "-std=c17",
              s"-O$optLevel",
              "-flto"
            ) ++ clangFlags ++ List("-fPIC", "-o", objPath, sourcePath)).mkString(" ")
            executeCommand(
              cmd,
              "Failed to compile MML runtime",
              new File(outputDir).getParent, // Use the parent of outputDir as the working directory
              verbose
            ).map {
              case Left(error) => error.asLeft
              case Right(_) => objPath.asRight
            }
      yield result
  }

  private def compileRuntimeBitcode(
    outputDir:    String,
    verbose:      Boolean,
    targetTriple: String,
    clangFlags:   List[String],
    printPhases:  Boolean,
    optLevel:     Int
  ): IO[Either[LlvmCompilationError, String]] = IO.defer {
    val runtimeFilename = mmlRuntimeBitcodeFilename(targetTriple)
    val bcPath          = Paths.get(outputDir).resolve(runtimeFilename).toAbsolutePath.toString

    logPhase("Compiling runtime bitcode", printPhases)
    if Files.exists(Paths.get(bcPath)) then
      logInfo(s"Runtime bitcode present, skipping", printPhases)
      IO.pure(bcPath.asRight)
    else
      for
        sourceResult <- extractRuntimeResource(outputDir, verbose, printPhases)
        result <- sourceResult match
          case Left(error) => IO.pure(error.asLeft)
          case Right(sourcePath) =>
            logPhase("Compiling runtime bitcode", printPhases)
            logDebug(s"Input file: $sourcePath", verbose)
            logDebug(s"Output file: $bcPath", verbose)

            val cmd = (List(
              "clang",
              "-target",
              targetTriple,
              "-emit-llvm",
              "-c",
              "-std=c17",
              s"-O$optLevel"
            ) ++ clangFlags ++ List("-fPIC", "-o", bcPath, sourcePath)).mkString(" ")
            executeCommand(
              cmd,
              "Failed to compile MML runtime bitcode",
              new File(outputDir).getParent,
              verbose
            ).map {
              case Left(error) => error.asLeft
              case Right(_) => bcPath.asRight
            }
      yield result
  }

  private def linkRuntimeBitcode(
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    verbose:          Boolean,
    clangFlags:       List[String],
    recordTiming:     Option[TimingRecorder],
    printPhases:      Boolean,
    optLevel:         Int
  ): IO[Either[LlvmCompilationError, String]] =
    val programBitcode = Paths.get(outputDir).resolve(s"$programName.bc").toAbsolutePath.toString
    val linkedBitcode =
      Paths.get(outputDir).resolve(s"${programName}_linked.bc").toAbsolutePath.toString

    for
      runtimeResult <- timedStep("llvm-runtime-bitcode", recordTiming)(
        compileRuntimeBitcode(outputDir, verbose, targetTriple, clangFlags, printPhases, optLevel)
      )
      result <- runtimeResult match
        case Left(error) => IO.pure(error.asLeft)
        case Right(runtimePath) =>
          logPhase("Linking MML runtime bitcode", printPhases)
          logDebug(s"Program bitcode: $programBitcode", verbose)
          logDebug(s"Runtime bitcode: $runtimePath", verbose)
          logDebug(s"Output file: $linkedBitcode", verbose)

          timedStep("llvm-link", recordTiming)(
            executeCommand(
              s"llvm-link $programBitcode $runtimePath -o $linkedBitcode",
              "Failed to link runtime bitcode",
              workingDirectory,
              verbose
            )
          ).map {
            case Left(error) => error.asLeft
            case Right(_) => linkedBitcode.asRight
          }
    yield result

  private def compileBinary(
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    targetDir:        String,
    verbose:          Boolean,
    clangFlags:       List[String],
    outputName:       Option[String],
    explicitTriple:   Option[String],
    recordTiming:     Option[TimingRecorder],
    printPhases:      Boolean,
    optLevel:         Int
  ): IO[Either[LlvmCompilationError, Int]] =
    val targetDirPath = Paths.get(targetDir).toAbsolutePath
    if !Files.exists(targetDirPath) then {
      logDebug(s"Creating target directory: $targetDirPath", verbose)
      Files.createDirectories(targetDirPath)
    }

    val finalExecutablePath = outputName match
      case Some(path) => Paths.get(path).toAbsolutePath.toString
      case None =>
        val baseName  = programName.toLowerCase
        val finalName = if explicitTriple.isDefined then s"$baseName-$targetTriple" else baseName
        targetDirPath.resolve(finalName).toString
    val inputFile = Paths.get(outputDir).resolve(s"$programName.s").toAbsolutePath.toString

    // Ensure parent directory exists for custom output paths
    val outputPath = Paths.get(finalExecutablePath)
    val parentDir  = outputPath.getParent
    if parentDir != null && !Files.exists(parentDir) then Files.createDirectories(parentDir)

    logPhase(s"Compiling and linking executable", printPhases)
    logDebug(s"Input file: $inputFile", verbose)
    logDebug(s"Output file: $finalExecutablePath", verbose)

    timedStep("llvm-compile-binary", recordTiming)(
      executeCommand(
        (List(
          "clang",
          "-target",
          targetTriple,
          s"-O$optLevel"
        ) ++ clangFlags ++ List(inputFile, "-o", finalExecutablePath)).mkString(" "),
        "Failed to compile and link",
        workingDirectory,
        verbose
      )
    ).map {
      case Left(error) => error.asLeft
      case Right(_) =>
        logInfo(s"Native code generation successful. Exit code: 0", printPhases)
        0.asRight
    }

  private def compileLibrary(
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    targetDir:        String,
    verbose:          Boolean,
    clangFlags:       List[String],
    outputName:       Option[String],
    explicitTriple:   Option[String],
    recordTiming:     Option[TimingRecorder],
    printPhases:      Boolean,
    optLevel:         Int
  ): IO[Either[LlvmCompilationError, Int]] =
    val targetDirPath = Paths.get(targetDir).toAbsolutePath
    if !Files.exists(targetDirPath) then {
      logDebug(s"Creating target directory: $targetDirPath", verbose)
      Files.createDirectories(targetDirPath)
    }

    val finalLibraryPath = outputName match
      case Some(path) =>
        val p = Paths.get(path).toAbsolutePath.toString
        if p.endsWith(".o") then p else s"$p.o"
      case None =>
        val baseName  = programName.toLowerCase
        val finalName = if explicitTriple.isDefined then s"$baseName-$targetTriple" else baseName
        targetDirPath.resolve(s"$finalName.o").toString
    val inputFile = Paths.get(outputDir).resolve(s"$programName.s").toAbsolutePath.toString

    // Ensure parent directory exists for custom output paths
    val outputPath = Paths.get(finalLibraryPath)
    val parentDir  = outputPath.getParent
    if parentDir != null && !Files.exists(parentDir) then Files.createDirectories(parentDir)

    for
      runtimeResult <- timedStep("llvm-runtime-object", recordTiming)(
        compileRuntime(outputDir, verbose, targetTriple, clangFlags, printPhases, optLevel)
      )
      result <- runtimeResult match
        case Left(error) => IO.pure(error.asLeft)
        case Right(runtimePath) =>
          logPhase(s"Compiling library object with MML runtime", printPhases)
          logDebug(s"Input file: $inputFile", verbose)
          logDebug(s"Runtime: $runtimePath", verbose)
          logDebug(s"Output file: $finalLibraryPath", verbose)

          // For a library, we just compile the assembly to an object file
          // The runtime is included separately but should be linked by the user
          timedStep("llvm-compile-library", recordTiming)(
            executeCommand(
              (List("clang", "-target", targetTriple, "-c") ++ clangFlags ++
                List(inputFile, "-o", finalLibraryPath)).mkString(" "),
              "Failed to compile library object",
              workingDirectory,
              verbose
            )
          ).map {
            case Left(error) => error.asLeft
            case Right(_) =>
              // Copy the runtime object to the target directory as well for easy access
              val runtimeTargetPath =
                targetDirPath.resolve(mmlRuntimeObjectFilename(targetTriple)).toString
              logInfo(s"Copying runtime to $runtimeTargetPath", printPhases)
              try
                // Convert the runtime path string to a Path object
                Files.copy(
                  Paths.get(runtimePath),
                  Paths.get(runtimeTargetPath),
                  StandardCopyOption.REPLACE_EXISTING
                )
                logInfo(s"Library object generation successful. Exit code: 0", printPhases)
                logInfo(
                  s"Note: Link with ${mmlRuntimeObjectFilename(targetTriple)} when using this library.",
                  printPhases
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

  private def createOutputDir(outputDir: String, printPhases: Boolean): IO[Unit] =
    IO {
      val dirPath = Paths.get(outputDir).toAbsolutePath
      if !Files.exists(dirPath) then {
        logInfo(s"Creating directory: $dirPath", printPhases)
        Files.createDirectories(dirPath)
      }
    }

  private def detectOsTargetTriple(
    userProvidedTriple: Option[String] = None
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
    cmd:              String,
    errorMsg:         String,
    workingDirectory: String,
    verbose:          Boolean = false
  ): IO[Either[LlvmCompilationError, Int]] =
    IO.defer {
      val setupDir = IO.blocking {
        val workingDirAbsolute = Paths.get(workingDirectory).toAbsolutePath.toString
        val workingDirFile     = new File(workingDirAbsolute)
        if !workingDirFile.exists() then {
          logDebug(s"Creating working directory: $workingDirAbsolute", verbose)
          workingDirFile.mkdirs()
        }
        logDebug(s"Executing command: $cmd", verbose)
        logDebug(s"In working directory: $workingDirAbsolute", verbose)
        workingDirFile
      }

      setupDir.flatMap { workingDirFile =>
        IO.blocking {
          try {
            val exitCode = Process(cmd, workingDirFile).!
            if exitCode != 0 then {
              val error = LlvmCompilationError.CommandExecutionError(cmd, errorMsg, exitCode)
              logError(s"Command failed with exit code $exitCode: $error")
              error.asLeft
            } else {
              logDebug(s"Command completed successfully with exit code $exitCode", verbose)
              exitCode.asRight
            }
          } catch {
            case e: java.io.IOException =>
              logError(s"Tool execution failed: ${e.getMessage}")
              logError(
                s"Tool ${cmd.split(" ").head} missing, marker file present. Have you uninstalled llvm? Removing marker file."
              )
              logError("Verify your llvm installation and try again")
              logError(llvmInstallInstructions)
              LlvmCompilationError
                .CommandExecutionError(
                  cmd,
                  s"Tool not found: ${e.getMessage}",
                  -1
                )
                .asLeft
          }
        }.flatMap { result =>
          if result.isLeft && result.left.exists {
              case LlvmCompilationError.CommandExecutionError(_, msg, code) =>
                msg.contains("Tool not found") && code == -1
              case _ => false
            }
          then invalidateToolsMarker(workingDirectory).as(result)
          else IO.pure(result)
        }
      }
    }

  private def checkLlvmTools(
    buildDir:    String,
    verbose:     Boolean,
    tools:       List[String],
    printPhases: Boolean
  ): IO[Either[LlvmCompilationError, Unit]] = IO.defer {
    val markerFilePath = Paths.get(buildDir).resolve(llvmCheckMarkerFile)
    if Files.exists(markerFilePath) && markerHasTools(markerFilePath, tools) then
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
        _ <- IO(logPhase(s"Checking for required LLVM tools: ${tools.mkString(", ")}", printPhases))
        timestamp = java.time.LocalDateTime
          .now()
          .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        result <- collectLlvmToolVersions(tools).flatMap { case ToolInfo(versions, missingTools) =>
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

  private def invalidateToolsMarker(workingDirectory: String): IO[Unit] = IO.blocking {
    val timestamp = java.time.LocalDateTime
      .now()
      .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      .replace(":", "-")
    val currentMarker  = Paths.get(workingDirectory).resolve(llvmCheckMarkerFile)
    val archivedMarker = Paths.get(workingDirectory).resolve(s"$llvmCheckMarkerFile-$timestamp")
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
