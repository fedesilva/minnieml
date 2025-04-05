package mml.mmlclib.codegen

import cats.effect.IO
import cats.syntax.all.*

import java.io.File
import java.nio.file.{Files, Paths}
import scala.sys.process.{Process, ProcessLogger}

case class ToolInfo(versions: Map[String, String], missing: List[String])

enum CompilationMode derives CanEqual:
  case Binary
  case Library

enum LlvmCompilationError derives CanEqual:
  case TemporaryFileCreationError(message: String)
  case UnsupportedOperatingSystem(osName: String)
  case CommandExecutionError(command: String, errorMessage: String, exitCode: Int)
  case ExecutableRunError(path: String, exitCode: Int)
  case LlvmNotInstalled(missingTools: List[String])

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
    verbose:          Boolean         = false
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
              case Right(inputFile) => processLlvmFile(inputFile, workingDirectory, mode, verbose)
          yield result
    yield result

  private def processLlvmFile(
    inputFile:        File,
    workingDirectory: String,
    mode:             CompilationMode,
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    val programName = inputFile.getName.stripSuffix(".ll")
    val outputDir   = s"$workingDirectory/out"
    val targetDir   = s"$workingDirectory/target"

    for
      _ <- createOutputDir(outputDir)
      _ <- createOutputDir(targetDir)
      targetTripleResult <- detectOsTargetTriple()
      result <- processWithTargetTriple(
        targetTripleResult,
        inputFile,
        programName,
        workingDirectory,
        outputDir,
        targetDir,
        mode,
        verbose
      )
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

  private def compileBinary(
    programName:      String,
    targetTriple:     String,
    workingDirectory: String,
    outputDir:        String,
    targetDir:        String,
    verbose:          Boolean
  ): IO[Either[LlvmCompilationError, Int]] =
    import cats.data.EitherT

    val targetDirPath = Paths.get(targetDir).toAbsolutePath
    if !Files.exists(targetDirPath) then {
      logDebug(s"Creating target directory: $targetDirPath", verbose)
      Files.createDirectories(targetDirPath)
    }

    val finalExecutablePath = targetDirPath.resolve(s"$programName-$targetTriple").toString

    (for
      _ <- EitherT({
        val inputFile = Paths.get(outputDir).resolve(s"$programName.s").toAbsolutePath.toString
        logPhase(s"Compiling and linking")
        logDebug(s"Input file: $inputFile", verbose)
        logDebug(s"Output file: $finalExecutablePath", verbose)
        executeCommand(
          s"clang -target $targetTriple $inputFile -o $finalExecutablePath",
          "Failed to compile and link",
          workingDirectory,
          verbose
        )
      })
      exitCode <- EitherT(runExecutable(finalExecutablePath, verbose))
    yield exitCode).value

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
    {
      val inputFile = Paths.get(outputDir).resolve(s"$programName.s").toAbsolutePath.toString
      logPhase(s"Compiling library object")
      logDebug(s"Input file: $inputFile", verbose)
      logDebug(s"Output file: $finalLibraryPath", verbose)
      executeCommand(
        s"clang -target $targetTriple -c $inputFile -o $finalLibraryPath",
        "Failed to compile library object",
        workingDirectory,
        verbose
      )
    }.map {
      case Left(error) => error.asLeft
      case Right(_) => 0.asRight
    }

  private def createOutputDir(outputDir: String): IO[Unit] =
    IO {
      val dirPath = Paths.get(outputDir).toAbsolutePath
      if !Files.exists(dirPath) then {
        logInfo(s"Creating directory: $dirPath")
        Files.createDirectories(dirPath)
      }
    }

  private def detectOsTargetTriple(): IO[Either[LlvmCompilationError, String]] =
    IO {
      val os = System.getProperty("os.name")
      if os.startsWith("Mac") then "x86_64-apple-macosx".asRight
      else if os.startsWith("Linux") then "x86_64-pc-linux-gnu".asRight
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

  private def runExecutable(path: String, verbose: Boolean): IO[Either[LlvmCompilationError, Int]] =
    IO {
      logPhase("Running executable")
      val exitCode = Process(path).!
      logDebug(s"Program executed with exit code: $exitCode", verbose)
      if exitCode != 0 then {
        logError(s"Execution failed with exit code: $exitCode")
        LlvmCompilationError.ExecutableRunError(path, exitCode).asLeft
      } else exitCode.asRight
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
          catch { case _: Exception => }
      }
    }
  }
