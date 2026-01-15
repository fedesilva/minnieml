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
import mml.mmlclib.errors.*
import mml.mmlclib.util.pipe.*

import java.nio.file.{Files, Path}

object CodegenStage:

  /** Pure pipeline: validation only. */
  def process(state: CompilerState): CompilerState =
    state |> CompilerState.timePhase("codegen", "pre-codegen-validation")(validate)

  /** Effectful pipeline: resolve triple + emit IR only. */
  def processIrOnly(state: CompilerState): IO[CompilerState] =
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

  private def validate(state: CompilerState): CompilerState =
    if state.hasErrors then state.withCanEmitCode(false)
    else
      val validated = PreCodegenValidator.validate(state.config.mode)(state)
      if validated.hasErrors then validated.withCanEmitCode(false)
      else validated

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
            LlvmIrEmitter.module(abiState.module, abiState.entryPoint, triple, targetAbi) match
              case Right(result) =>
                // Lift codegen warnings to compiler state
                val stateWithWarnings = result.warnings.foldLeft(abiState)(_.addWarning(_))
                stateWithWarnings.withLlvmIr(result.ir)
              case Left(error) => abiState.addError(error).withCanEmitCode(false)
    }

  private def resolveTargetAbi(
    config:         CompilerConfig,
    resolvedTriple: Option[String],
    state:          CompilerState
  ): (TargetAbi, CompilerState) =
    val explicitTriple = config.targetTriple
    val explicitArch   = config.targetArch
    val explicitHint   = explicitTriple.orElse(explicitArch)
    val hint           = explicitHint.orElse(resolvedTriple)
    val targetAbi      = TargetAbi.fromHint(hint)
    val archFromTriple = TargetAbi.archFromHint(explicitTriple)
    val archFromArch   = TargetAbi.archFromHint(explicitArch)
    val shouldWarn = archFromTriple.nonEmpty &&
      archFromArch.nonEmpty &&
      archFromTriple != archFromArch
    val nextState =
      (shouldWarn, explicitTriple, explicitArch) match
        case (true, Some(triple), Some(arch)) =>
          val message = s"Conflicting target hints: targetTriple=$triple targetArch=$arch"
          state.addWarning(CompilerWarning.Generic(message))
        case _ => state
    (targetAbi, nextState)

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
      val targetArch     = state.config.targetArch
      val targetCpu      = state.config.targetCpu
      val noStackCheck   = state.config.noStackCheck
      val mode           = state.config.mode
      val showTimings    = state.config.showTimings
      val emitOptIr      = state.config.emitOptIr
      val outputName     = state.config.outputName
      val explicitTriple = state.config.targetTriple
      val printPhases    = state.config.printPhases
      val optLevel       = state.config.optLevel

      val compileIo = selectCompileOperation(
        irPath,
        outputDir,
        mode,
        verbose,
        triple,
        targetArch,
        targetCpu,
        noStackCheck,
        emitOptIr,
        showTimings,
        outputName,
        explicitTriple,
        printPhases,
        optLevel
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
    targetArch:     Option[String],
    targetCpu:      Option[String],
    noStackCheck:   Boolean,
    emitOptIr:      Boolean,
    showTimings:    Boolean,
    outputName:     Option[String],
    explicitTriple: Option[String],
    printPhases:    Boolean,
    optLevel:       Int
  ): IO[(Either[LlvmCompilationError, Int], Vector[PipelineTiming])] =
    val emptyTimings: Vector[PipelineTiming] = Vector.empty
    if showTimings then
      LlvmToolchain.compileWithTimings(
        irPath,
        outputDir,
        mode,
        verbose,
        triple,
        targetArch,
        targetCpu,
        noStackCheck,
        emitOptIr,
        outputName,
        explicitTriple,
        printPhases,
        optLevel
      )
    else
      LlvmToolchain
        .compile(
          irPath,
          outputDir,
          mode,
          verbose,
          triple,
          targetArch,
          targetCpu,
          noStackCheck,
          emitOptIr,
          outputName,
          explicitTriple,
          printPhases,
          optLevel
        )
        .map(_ -> emptyTimings)
