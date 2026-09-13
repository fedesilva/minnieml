package mml.mmlclib.compiler

import cats.effect.IO
import mml.mmlclib.codegen.{
  ClangTarget,
  LlvmCompilationError,
  LlvmIrEmitter,
  LlvmToolchain,
  PipelineTiming,
  TargetAbi
}
import mml.mmlclib.util.pipe.*

import java.nio.file.{Files, Path}

object CodegenStage:

  /** Pure pipeline: validation only. */
  def validate(state: CompilerState): CompilerState =
    state |> CompilerState.timePhase("codegen", "pre-codegen-validation")(runValidation)

  /** Effectful pipeline: resolve triple + emit IR only. */
  def emitIrOnly(state: CompilerState): IO[CompilerState] =
    IO.pure(state)
      |> CompilerState.timePhaseIO("codegen", "resolve-triple")(resolveTriple)
      |> CompilerState.timePhaseIO("codegen", "llvm-info")(llvmInfo)
      |> CompilerState.timePhaseIO("codegen", "target-attributes")(resolveTarget)
      |> CompilerState.timePhaseIO("codegen", "emit-llvm-ir")(emitIr)

  /** Effectful pipeline: resolve triple + emit IR + native compilation. */
  def processNative(state: CompilerState): IO[CompilerState] =
    IO.pure(state)
      |> CompilerState.timePhaseIO("codegen", "resolve-triple")(resolveTriple)
      |> CompilerState.timePhaseIO("codegen", "llvm-info")(llvmInfo)
      |> CompilerState.timePhaseIO("codegen", "target-attributes")(resolveTarget)
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
      LlvmToolchain
        .resolveTargetTriple(state.config.targetTriple, state.config.outputDir.toString)
        .map {
          case Left(error) => state.addError(error).withCanEmitCode(false)
          case Right(triple) => state.withResolvedTriple(triple)
        }

  private def llvmInfo(state: CompilerState): IO[CompilerState] =
    if !state.canEmitCode then IO.pure(state)
    else
      LlvmToolchain
        .gatherLlvmInfo(state.config.outputDir, state.config.verbose, state.config.printPhases)
        .map {
          case Left(error) => state.addError(error).withCanEmitCode(false)
          case Right(_) => state
        }

  private def emitIr(state: CompilerState): IO[CompilerState] =
    IO.pure {
      if !state.canEmitCode then state
      else
        (state.resolvedTriple, state.clangTarget) match
          case (Some(triple), Some(target)) =>
            val (targetAbi, abiState) = resolveTargetAbi(state.config, state.resolvedTriple, state)
            LlvmIrEmitter.module(
              abiState.module,
              abiState.entryPoint,
              triple,
              targetAbi,
              target.attributes,
              state.config.emitScopedAlias
            ) match
              case Right(result) =>
                // Lift codegen warnings to compiler state
                val stateWithWarnings = result.warnings.foldLeft(abiState)(_.addWarning(_))
                stateWithWarnings.withLlvmIr(result.ir)
              case Left(error) => abiState.addError(error).withCanEmitCode(false)
          case _ => state
    }

  private def resolveTarget(state: CompilerState): IO[CompilerState] =
    state.resolvedTriple match
      case Some(triple) if state.canEmitCode =>
        ClangTarget
          .resolve(state.config.outputDir, LlvmToolchain.clangFlags(state.config, triple))
          .map {
            case Left(error) => state.addError(error).withCanEmitCode(false)
            case Right(target) => state.copy(clangTarget = Some(target))
          }
      case _ => IO.pure(state)

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
    state.clangTarget match
      case Some(target) if state.canEmitCode && state.llvmIr.nonEmpty =>
        val irPath = llvmIrPath(state)

        val compileIo =
          if state.config.showTimings then
            LlvmToolchain.compileWithTimings(irPath, state.config, state.resolvedTriple, target)
          else
            LlvmToolchain
              .compile(irPath, state.config, state.resolvedTriple, target)
              .map(_ -> Vector.empty[PipelineTiming])

        compileIo.map { case (result, stepTimings) =>
          val withSteps = stepTimings.foldLeft(state) { (s, t) =>
            s.addTiming("llvm", t.name, t.durationNanos)
          }
          result match
            case Left(error) => withSteps.addError(error)
            case Right(code) => withSteps.withNativeResult(code)
        }
      case _ => IO.pure(state)
