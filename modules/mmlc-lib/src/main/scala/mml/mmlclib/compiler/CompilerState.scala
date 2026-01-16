package mml.mmlclib.compiler

import cats.effect.IO
import mml.mmlclib.ast.Module
import mml.mmlclib.errors.{CompilationError, CompilerWarning}
import mml.mmlclib.parser.{ParserError, SourceInfo}
import mml.mmlclib.semantic.SemanticError

final case class Timing(stage: String, name: String, durationNanos: Long)

case class CompilerState(
  module:         Module,
  sourceInfo:     SourceInfo,
  config:         CompilerConfig,
  errors:         Vector[CompilationError],
  warnings:       Vector[CompilerWarning],
  timings:        Vector[Timing],
  entryPoint:     Option[String] = None,
  canEmitCode:    Boolean        = false,
  llvmIr:         Option[String] = None,
  nativeResult:   Option[Int]    = None,
  resolvedTriple: Option[String] = None
):
  def addErrors(newErrors: List[CompilationError]): CompilerState =
    copy(errors = errors ++ newErrors)

  def addError(error: CompilationError): CompilerState =
    copy(errors = errors :+ error)

  def addWarnings(newWarnings: List[CompilerWarning]): CompilerState =
    copy(warnings = warnings ++ newWarnings)

  def addWarning(warning: CompilerWarning): CompilerState =
    copy(warnings = warnings :+ warning)

  def withModule(newModule: Module): CompilerState =
    copy(module = newModule)

  def withSourceInfo(info: SourceInfo): CompilerState =
    copy(sourceInfo = info)

  def withConfig(newConfig: CompilerConfig): CompilerState =
    copy(config = newConfig)

  def withEntryPoint(name: String): CompilerState =
    copy(entryPoint = Some(name))

  def withCanEmitCode(can: Boolean): CompilerState =
    copy(canEmitCode = can)

  def withLlvmIr(ir: String): CompilerState =
    copy(llvmIr = Some(ir))

  def withNativeResult(code: Int): CompilerState =
    copy(nativeResult = Some(code))

  def withResolvedTriple(triple: String): CompilerState =
    copy(resolvedTriple = Some(triple))

  def addTiming(stage: String, name: String, durationNanos: Long): CompilerState =
    copy(timings = timings :+ Timing(stage, name, durationNanos))

  def semanticErrors: Vector[SemanticError] =
    errors.collect { case err: SemanticError => err }

  def parserErrors: Vector[ParserError] =
    errors.collect { case err: ParserError => err }

  def hasErrors: Boolean =
    errors.nonEmpty

object CompilerState:
  def empty(module: Module, sourceInfo: SourceInfo, config: CompilerConfig): CompilerState =
    CompilerState(
      module     = module,
      sourceInfo = sourceInfo,
      config     = config,
      errors     = Vector.empty,
      warnings   = Vector.empty,
      timings    = Vector.empty
    )

  def timed[A](
    stage: String,
    name:  String
  )(
    f: CompilerState => (CompilerState, A)
  ): CompilerState => (CompilerState, A) =
    state =>
      val start       = System.nanoTime()
      val (next, res) = f(state)
      val duration    = System.nanoTime() - start
      (next.addTiming(stage, name, duration), res)

  def timePhase(
    stage: String,
    name:  String
  )(
    f: CompilerState => CompilerState
  ): CompilerState => CompilerState =
    state =>
      val start    = System.nanoTime()
      val next     = f(state)
      val duration = System.nanoTime() - start
      next.addTiming(stage, name, duration)

  def timePhaseIO(
    stage: String,
    name:  String
  )(
    f: CompilerState => IO[CompilerState]
  ): CompilerState => IO[CompilerState] =
    state =>
      val start = System.nanoTime()
      f(state).map { next =>
        val duration = System.nanoTime() - start
        next.addTiming(stage, name, duration)
      }
