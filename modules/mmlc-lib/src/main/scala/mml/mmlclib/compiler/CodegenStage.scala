package mml.mmlclib.compiler

import cats.effect.IO
import mml.mmlclib.codegen.{
  CompilationMode,
  LlvmCompilationError,
  LlvmIrEmitter,
  LlvmToolchain,
  PipelineTiming,
  TargetAbi
}
import mml.mmlclib.util.pipe.*

import java.nio.file.{Files, Path}

object CodegenStage:

  // 
  // LlvmToolchain.checkLlvmTools(workingDirectory, verbose, printPhases) 

  /** Pure pipeline: validation only. */
  def validate(state: CompilerState): CompilerState =
    state |> CompilerState.timePhase("codegen", "pre-codegen-validation")(runValidation)

  /** Effectful pipeline: resolve triple + emit IR only. */
  def emitIrOnly(state: CompilerState): IO[CompilerState] =
    IO.pure(state)
      |> CompilerState.timePhaseIO("codegen", "resolve-triple")(resolveTriple)
      |> CompilerState.timePhaseIO("codegen", "emit-llvm-ir")(emitIr)

  /** Effectful pipeline: resolve triple + emit IR + native compilation. */
  def processNative(state: CompilerState): IO[CompilerState] =
    IO.pure(state)
      |> CompilerState.timePhaseIO("codegen", "resolve-triple")(resolveTriple)
      |> CompilerState.timePhaseIO("codegen", "emit-llvm-ir")(emitIr)
      |> CompilerState.timePhaseIO("codegen", "write-llvm-ir")(writeIr)
      |> compileNative

  // --- Private pipeline steps ---

  private def runValidation(state: CompilerState): CompilerState =
    if state.hasErrors then state.withCanEmitCode(false)
    else
      val validated = PreCodegenValidator.validate(state.config.mode)(state)
      if validated.hasErrors then validated
      else validated.withCanEmitCode(true)

  private def resolveTriple(state: CompilerState): IO[CompilerState] =
    if !state.canEmitCode then IO.pure(state)
    else
      val outputDir = state.config.outputDir.toString
      LlvmToolchain
        .resolveTargetTriple(state.config.targetTriple, outputDir)
        .map {
          case Left(error) => state.addError(error).withCanEmitCode(false)
          case Right(triple) => state.withResolvedTriple(triple)
        }

  private def emitIr(state: CompilerState): IO[CompilerState] =
    IO.pure {
      if !state.canEmitCode then state
      else
        state.resolvedTriple match
          case None => state
          case Some(triple) =>
            val (targetAbi, abiState) = resolveTargetAbi(state.config, state.resolvedTriple, state)
            val targetCpu             = resolveTargetCpu(state.config)
            LlvmIrEmitter.module(
              abiState.module,
              abiState.entryPoint,
              triple,
              targetAbi,
              targetCpu,
              state.config.emitScopedAlias
            ) match
              case Right(result) =>
                // Lift codegen warnings to compiler state
                val stateWithWarnings = result.warnings.foldLeft(abiState)(_.addWarning(_))
                stateWithWarnings.withLlvmIr(result.ir)
              case Left(error) => abiState.addError(error).withCanEmitCode(false)
    }

  /** Determine the target CPU for IR emission.
    *
    * Logic:
    *   - If --cpu is explicitly provided, use that
    *   - If --target is provided but no --cpu, omit (cross-compiling, let LLVM decide)
    *   - If neither, use host CPU from marker file (local build)
    */
  private def resolveTargetCpu(config: CompilerConfig): Option[String] =
    config.targetCpu match
      case Some(cpu) => Some(cpu) // Explicit --cpu flag
      case None =>
        if config.targetTriple.isDefined then None // Cross-compiling, omit
        else LlvmToolchain.readHostCpu(config.outputDir.toString) // Local build

  private def resolveTargetAbi(
    config:         CompilerConfig,
    resolvedTriple: Option[String],
    state:          CompilerState
  ): (TargetAbi, CompilerState) =
    val hint      = config.targetTriple.orElse(resolvedTriple)
    val targetAbi = TargetAbi.fromHint(hint)
    (targetAbi, state)

  private def llvmIrPath(state: CompilerState): Path =
    val triple = state.resolvedTriple.getOrElse("unknown")
    state.config.outputDir.resolve(s"${state.module.name}-$triple.ll")

  private def writeIr(state: CompilerState): IO[CompilerState] =
    state.llvmIr match
      case None => IO.pure(state)
      case _ if !state.canEmitCode => IO.pure(state)
      case Some(ir) =>
        val path = llvmIrPath(state)
        IO.blocking {
          val parent = path.getParent
          if parent != null then Files.createDirectories(parent)
          Files.writeString(path, ir)
        }.attempt
          .map {
            case Right(_) => state
            case Left(err) =>
              state
                .addError(
                  LlvmCompilationError.TemporaryFileCreationError(
                    s"Error writing LLVM IR to file: ${err.getMessage}"
                  )
                )
                .withCanEmitCode(false)
          }

  private def compileNative(state: CompilerState): IO[CompilerState] =
    if !state.canEmitCode || state.llvmIr.isEmpty then IO.pure(state)
    else
      val irPath         = llvmIrPath(state)
      val outputDir      = state.config.outputDir.toString
      val verbose        = state.config.verbose
      val triple         = state.resolvedTriple
      val noStackCheck   = state.config.noStackCheck
      val mode           = state.config.mode
      val showTimings    = state.config.showTimings
      val emitOptIr      = state.config.emitOptIr
      val outputName     = state.config.outputName
      val explicitTriple = state.config.targetTriple
      val printPhases    = state.config.printPhases
      val optLevel       = state.config.optLevel
      val targetCpu      = resolveTargetCpu(state.config)

      val compileIo = selectCompileOperation(
        irPath,
        outputDir,
        mode,
        verbose,
        triple,
        noStackCheck,
        emitOptIr,
        showTimings,
        outputName,
        explicitTriple,
        printPhases,
        optLevel,
        targetCpu
      )

      compileIo.map { case (result, stepTimings) =>
        val withSteps = stepTimings.foldLeft(state) { (s, t) =>
          s.addTiming("llvm", t.name, t.durationNanos)
        }
        result match
          case Left(error) => withSteps.addError(error)
          case Right(code) => withSteps.withNativeResult(code)
      }

  private def selectCompileOperation(
    irPath:         Path,
    outputDir:      String,
    mode:           CompilationMode,
    verbose:        Boolean,
    triple:         Option[String],
    noStackCheck:   Boolean,
    emitOptIr:      Boolean,
    showTimings:    Boolean,
    outputName:     Option[String],
    explicitTriple: Option[String],
    printPhases:    Boolean,
    optLevel:       Int,
    targetCpu:      Option[String]
  ): IO[(Either[LlvmCompilationError, Int], Vector[PipelineTiming])] =
    val emptyTimings: Vector[PipelineTiming] = Vector.empty
    if showTimings then
      LlvmToolchain.compileWithTimings(
        irPath,
        outputDir,
        mode,
        verbose,
        triple,
        noStackCheck,
        emitOptIr,
        outputName,
        explicitTriple,
        printPhases,
        optLevel,
        targetCpu
      )
    else
      LlvmToolchain
        .compile(
          irPath,
          outputDir,
          mode,
          verbose,
          triple,
          noStackCheck,
          emitOptIr,
          outputName,
          explicitTriple,
          printPhases,
          optLevel,
          targetCpu
        )
        .map(_ -> emptyTimings)
