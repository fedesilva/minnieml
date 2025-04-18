package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*

import java.io.{File, InputStream}
import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.sys.process.{Process, ProcessLogger}

case class ToolInfo(versions: Map[String, String], missing: List[String])

enum CompilationMode derives CanEqual:
  case Binary
  case Library

enum LlvmCompilationError derives CanEqual:
  case TemporaryFileCreationError(message: String)
  case UnsupportedOperatingSystem(osName: String)
  case UnsupportedArchitecture(archName: String)
  case CommandExecutionError(command: String, errorMessage: String, exitCode: Int)
  case ExecutableRunError(path: String, exitCode: Int)
  case LlvmNotInstalled(missingTools: List[String])
  case RuntimeResourceError(message: String)

object LlvmOrchestrator:

  /** List of required LLVM tools */
  private val requiredLlvmTools = List("llvm-as", "opt", "llc", "clang")

  /** Marker file name to cache successful LLVM tool checks */
  private val llvmCheckMarkerFile = "llvm-check-ok"

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

  /** Collects version information of the required LLVM tools.
    *
    * @return
    *   An IO effect with ToolInfo containing tool version info and missing tools.
    */
  def collectLlvmToolVersions: IO[ToolInfo] = IO {
    requiredLlvmTools.foldLeft(ToolInfo(Map.empty[String, String], Nil)) {
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

  def compile(
    llvmIr:           String,
    moduleName:       String,
    workingDirectory: String,
    mode:             CompilationMode = CompilationMode.Binary,
    verbose:          Boolean         = false,
    targetTriple:     Option[String]  = None
  ): IO[Either[LlvmCompilationError, Int]] =
    for
      _ <- IO(logPhase(s"Compiling module $moduleName"))
      _ <- IO(logInfo(s"Working directory: $workingDirectory"))
      _ <- IO(logInfo(s"Compilation mode: $mode"))
      _ <- createOutputDir(workingDirectory)
      toolsCheckResult <- checkLlvmTools(workingDirectory, verbose)
      result <- toolsCheckResult match
        case Left(error) =>
          IO.pure(error.asLeft)
        case Right(_) =>
          for
            inputFileResult <- IO {
              try {
                val filePath = Paths.get(workingDirectory).resolve(s"$moduleName.ll")
                Files.writeString(filePath, llvmIr)
                filePath.toFile.asRight
              } catch {
                case e: Exception =>
                  val error = LlvmCompilationError.TemporaryFileCreationError(
                    s"Error writing LLVM IR to file: ${e.getMessage}"
                  )
                  logError(s"Error creating temporary file: ${e.getMessage}")
                  error.asLeft
              }
            }
            result <- inputFileResult match
              case Left(error) => IO.pure(error.asLeft)
              case Right(inputFile) =>
                processLlvmFile(inputFile, workingDirectory, mode, verbose, targetTriple)
          yield result
    yield result

  private def processLlvmFile(
    inputFile:        File,
    workingDirectory: String,
    mode:             CompilationMode,
    verbose:          Boolean,
    targetTriple:     Option[String] = None
  ): IO[Either[LlvmCompilationError, Int]] =
    val programName   = inputFile.getName.stripSuffix(".ll")
    val baseOutputDir = s"$workingDirectory/out"
    val targetDir     = s"$workingDirectory/target"

    for
      targetTripleResult <- detectOsTargetTriple(targetTriple)
      result <- targetTripleResult match
        case Left(error) =>
          IO(logError(s"Error detecting target triple: $error")) *>
            IO.pure(error.asLeft)
        case Right(triple) =>
          val outputDir = s"$baseOutputDir/$triple"
          for
            _ <- createOutputDir(outputDir)
            _ <- createOutputDir(targetDir)
            result <- processWithTargetTriple(
              Right(triple),
              inputFile,
              programName,
              workingDirectory,
              outputDir,
              targetDir,
              mode,
              verbose
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
    verbose:            Boolean
  ): IO[Either[LlvmCompilationError, Int]] = targetTripleResult match
    case Left(error) =>
      IO(logError(s"Error detecting target triple: $error")) *>
        IO.pure(error.asLeft)
    case Right(targetTriple) =>
      logPhase(s"Using target triple: $targetTriple")
      logInfo(s"Compilation mode: $mode")
      logPhase(s"Starting LLVM compilation pipeline for $programName")
      runCompilationPipeline(
        inputFile,
        programName,
        targetTriple,
        workingDirectory,
        outputDir,
        targetDir,
        mode,
        verbose
      )

  private def runCompilationPipeline(
    inputFile:        File,
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    targetDir:        String,
    mode:             CompilationMode,
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    import cats.data.EitherT

    (for
      _ <- EitherT(irToBitcode(inputFile, programName, workingDirectory, outputDir, verbose))
      _ <- EitherT(runOptimization(programName, workingDirectory, outputDir, verbose))
      _ <- EitherT(generateOptimizedIr(programName, workingDirectory, outputDir, verbose))
      _ <- EitherT(
        generateAssembly(programName, targetTriple, workingDirectory, outputDir, verbose)
      )
      result <- EitherT(
        compileForMode(
          programName,
          targetTriple,
          workingDirectory,
          outputDir,
          targetDir,
          mode,
          verbose
        )
      )
    yield result).value

  private def irToBitcode(
    inputFile:        File,
    programName:      String,
    workingDirectory: String,
    outputDir:        String,
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val sourceFile = inputFile.getAbsolutePath
    val outputFile = Paths.get(outputDir).resolve(s"$programName.bc").toAbsolutePath.toString
    logPhase(s"Converting IR to Bitcode")
    logDebug(s"Input file: $sourceFile", verbose)
    logDebug(s"Output file: $outputFile", verbose)
    executeCommand(
      s"llvm-as $sourceFile -o $outputFile",
      "Failed to convert IR to Bitcode",
      workingDirectory,
      verbose
    )

  private def runOptimization(
    programName:      String,
    workingDirectory: String,
    outputDir:        String,
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val inputFile  = Paths.get(outputDir).resolve(s"$programName.bc").toAbsolutePath.toString
    val outputFile = Paths.get(outputDir).resolve(s"${programName}_opt.bc").toAbsolutePath.toString
    logPhase(s"Optimizing IR")
    logDebug(s"Input file: $inputFile", verbose)
    logDebug(s"Output file: $outputFile", verbose)
    executeCommand(
      s"opt -O2 $inputFile -o $outputFile",
      "Failed to optimize IR",
      workingDirectory,
      verbose
    )

  private def generateOptimizedIr(
    programName:      String,
    workingDirectory: String,
    outputDir:        String,
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val inputFile  = Paths.get(outputDir).resolve(s"$programName.bc").toAbsolutePath.toString
    val outputFile = Paths.get(outputDir).resolve(s"${programName}_opt.ll").toAbsolutePath.toString
    logPhase(s"Generating optimized IR")
    logDebug(s"Input file: $inputFile", verbose)
    logDebug(s"Output file: $outputFile", verbose)
    executeCommand(
      s"opt -O2 -S $inputFile -o $outputFile",
      "Failed to generate optimized IR",
      workingDirectory,
      verbose
    )

  private def generateAssembly(
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val inputFile  = Paths.get(outputDir).resolve(s"${programName}_opt.bc").toAbsolutePath.toString
    val outputFile = Paths.get(outputDir).resolve(s"$programName.s").toAbsolutePath.toString
    logPhase(s"Generating assembly")
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
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] = mode match
    case CompilationMode.Binary =>
      compileBinary(programName, targetTriple, workingDirectory, outputDir, targetDir, verbose)
    case CompilationMode.Library =>
      compileLibrary(programName, targetTriple, workingDirectory, outputDir, targetDir, verbose)

  /** Path to the MML runtime file in resources */
  private val mmlRuntimeResourcePath = "mml_runtime.c"

  /** Filename for the MML runtime file */
  private val mmlRuntimeFilename = "mml_runtime.c"

  /** Get the filename for the compiled MML runtime object for a specific target */
  private def mmlRuntimeObjectFilename(targetTriple: String): String =
    s"mml_runtime-$targetTriple.o"

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
    outputDir: String,
    verbose:   Boolean
  ): IO[Either[LlvmCompilationError, String]] = IO.defer {
    val sourcePath = Paths.get(outputDir).resolve(mmlRuntimeFilename).toAbsolutePath.toString

    if Files.exists(Paths.get(sourcePath)) then
      logDebug(s"Runtime source already exists at $sourcePath", verbose)
      IO.pure(sourcePath.asRight)
    else
      IO {
        try {
          logPhase("Extracting MML runtime source")
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
    targetTriple: String
  ): IO[Either[LlvmCompilationError, String]] = IO.defer {
    val runtimeFilename = mmlRuntimeObjectFilename(targetTriple)
    val objPath         = Paths.get(outputDir).resolve(runtimeFilename).toAbsolutePath.toString

    logPhase("Compiling runtime")
    if Files.exists(Paths.get(objPath)) then
      logInfo(s"Runtime object already exists at $objPath, skipping compilation")
      IO.pure(objPath.asRight)
    else
      for
        sourceResult <- extractRuntimeResource(outputDir, verbose)
        result <- sourceResult match
          case Left(error) => IO.pure(error.asLeft)
          case Right(sourcePath) =>
            logPhase("Compiling MML runtime")
            logDebug(s"Input file: $sourcePath", verbose)
            logDebug(s"Output file: $objPath", verbose)

            val cmd = s"clang -target $targetTriple -c -std=c17 -O2 -fPIC -o $objPath $sourcePath"
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

  private def compileBinary(
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    targetDir:        String,
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val targetDirPath = Paths.get(targetDir).toAbsolutePath
    if !Files.exists(targetDirPath) then {
      logDebug(s"Creating target directory: $targetDirPath", verbose)
      Files.createDirectories(targetDirPath)
    }

    val finalExecutablePath = targetDirPath.resolve(s"$programName-$targetTriple").toString
    val inputFile = Paths.get(outputDir).resolve(s"$programName.s").toAbsolutePath.toString

    for
      runtimeResult <- compileRuntime(outputDir, verbose, targetTriple)
      result <- runtimeResult match
        case Left(error) => IO.pure(error.asLeft)
        case Right(runtimePath) =>
          logPhase(s"Compiling and linking with MML runtime")
          logDebug(s"Input file: $inputFile", verbose)
          logDebug(s"Runtime: $runtimePath", verbose)
          logDebug(s"Output file: $finalExecutablePath", verbose)

          executeCommand(
            s"clang -target $targetTriple $inputFile $runtimePath -o $finalExecutablePath",
            "Failed to compile and link",
            workingDirectory,
            verbose
          ).map {
            case Left(error) => error.asLeft
            case Right(_) =>
              logInfo(s"Native code generation successful. Exit code: 0")
              0.asRight
          }
    yield result

  private def compileLibrary(
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    targetDir:        String,
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val targetDirPath = Paths.get(targetDir).toAbsolutePath
    if !Files.exists(targetDirPath) then {
      logDebug(s"Creating target directory: $targetDirPath", verbose)
      Files.createDirectories(targetDirPath)
    }

    val finalLibraryPath = targetDirPath.resolve(s"$programName-$targetTriple.o").toString
    val inputFile        = Paths.get(outputDir).resolve(s"$programName.s").toAbsolutePath.toString

    for
      runtimeResult <- compileRuntime(outputDir, verbose, targetTriple)
      result <- runtimeResult match
        case Left(error) => IO.pure(error.asLeft)
        case Right(runtimePath) =>
          logPhase(s"Compiling library object with MML runtime")
          logDebug(s"Input file: $inputFile", verbose)
          logDebug(s"Runtime: $runtimePath", verbose)
          logDebug(s"Output file: $finalLibraryPath", verbose)

          // For a library, we just compile the assembly to an object file
          // The runtime is included separately but should be linked by the user
          executeCommand(
            s"clang -target $targetTriple -c $inputFile -o $finalLibraryPath",
            "Failed to compile library object",
            workingDirectory,
            verbose
          ).map {
            case Left(error) => error.asLeft
            case Right(_) =>
              // Copy the runtime object to the target directory as well for easy access
              val runtimeTargetPath =
                targetDirPath.resolve(mmlRuntimeObjectFilename(targetTriple)).toString
              logInfo(s"Copying runtime to $runtimeTargetPath")
              try
                // Convert the runtime path string to a Path object
                Files.copy(
                  Paths.get(runtimePath),
                  Paths.get(runtimeTargetPath),
                  StandardCopyOption.REPLACE_EXISTING
                )
                logInfo(s"Library object generation successful. Exit code: 0")
                logInfo(
                  s"Note: Link with ${mmlRuntimeObjectFilename(targetTriple)} when using this library."
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

  private def createOutputDir(outputDir: String): IO[Unit] =
    IO {
      val dirPath = Paths.get(outputDir).toAbsolutePath
      if !Files.exists(dirPath) then {
        logInfo(s"Creating directory: $dirPath")
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
      val setupDir = IO {
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
        IO {
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
    buildDir: String,
    verbose:  Boolean
  ): IO[Either[LlvmCompilationError, Unit]] = IO.defer {
    val markerFilePath = Paths.get(buildDir).resolve(llvmCheckMarkerFile)
    if Files.exists(markerFilePath) then
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
        _ <- IO(logPhase(s"Checking for required LLVM tools: ${requiredLlvmTools.mkString(", ")}"))
        timestamp = java.time.LocalDateTime
          .now()
          .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        result <- collectLlvmToolVersions.flatMap { case ToolInfo(versions, missingTools) =>
          if missingTools.isEmpty then
            IO {
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
            IO {
              val toolsMessage = missingTools.mkString(", ")
              logError(s"Required LLVM tools not found: $toolsMessage")
              logError(llvmInstallInstructions)
              LlvmCompilationError.LlvmNotInstalled(missingTools).asLeft[Unit]
            }
        }
      } yield result
  }

  private def logInfo(message: String): Unit =
    println(message)

  private def logError(message: String): Unit =
    println(s"${Console.RED}${message}${Console.RESET}")

  private def logPhase(message: String): Unit =
    println(s"${Console.BLUE}${message}${Console.RESET}")

  private def logDebug(message: String, verbose: Boolean): Unit =
    if verbose then println(s"  ${message}")

  private def invalidateToolsMarker(workingDirectory: String): IO[Unit] = IO {
    val timestamp = java.time.LocalDateTime
      .now()
      .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      .replace(":", "-")
    val currentMarker  = Paths.get(workingDirectory).resolve(llvmCheckMarkerFile)
    val archivedMarker = Paths.get(workingDirectory).resolve(s"$llvmCheckMarkerFile-$timestamp")
    if Files.exists(currentMarker) then {
      try {
        Files.move(currentMarker, archivedMarker)
        logInfo(s"LLVM tools check invalidated. Marker moved to ${archivedMarker.getFileName}")
      } catch {
        case e: Exception =>
          logError(s"Failed to archive LLVM tools marker: ${e.getMessage}")
          try Files.deleteIfExists(currentMarker)
          // TODO: do not swallow exceptions
          catch { case _: Exception => }
      }
    }
  }
