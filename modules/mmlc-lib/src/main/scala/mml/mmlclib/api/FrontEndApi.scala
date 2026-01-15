package mml.mmlclib.api

import cats.data.EitherT
import cats.effect.IO
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, IngestStage, SemanticStage}
import mml.mmlclib.errors.CompilationError
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.SemanticError

enum CompilerError extends CompilationError:
  case SemanticErrors(errors: List[SemanticError])
  case ParserErrors(errors: List[ParserError])
  case Unknown(msg: String)

  def message: String = this match
    case SemanticErrors(errors) =>
      if errors.size == 1 then errors.head.message
      else s"${errors.size} errors: ${errors.map(_.message).mkString("; ")}"
    case ParserErrors(errors) =>
      if errors.size == 1 then errors.head.message
      else s"${errors.size} parse errors: ${errors.map(_.message).mkString("; ")}"
    case Unknown(msg) => msg

object FrontEndApi:
  /** Run ingest + semantic pipeline and return the final state. */
  def compile(
    source:     String,
    name:       String,
    config:     CompilerConfig = CompilerConfig.default,
    sourcePath: Option[String] = None
  ): CompilerEffect[CompilerState] =
    EitherT.liftF(
      IO.blocking {
        val ingestState = IngestStage.fromSource(source, name, config, sourcePath)
        SemanticStage.rewrite(ingestState)
      }
    )
