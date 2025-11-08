package mml.mmlclib.api

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.api.CompilerEffect
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.*

enum CompilerError:
  case SemanticErrors(errors: List[SemanticError])
  case ParserErrors(errors: List[ParserError])
  case Unknown(msg: String)

object CompilerApi:
  /** Run parsing + semantic pipeline and return the final state */
  def compileState(
    source: String,
    name:   String
  ): CompilerEffect[SemanticPhaseState] =
    for
      // Use leftMap to convert ParserError to CompilerError
      parsedModule <- ParserApi
        .parseModuleString(source, name)
        .leftMap(error => CompilerError.ParserErrors(List(error)))
      state <- SemanticApi.rewriteModule(parsedModule)
    yield state

  /** Compile a string source into a Module
    *
    * Runs `compileState` and only returns the rewritten Module when no semantic errors were
    * accumulated. If semantic errors exist, they are surfaced as `CompilerError.SemanticErrors`.
    */
  def compileString(
    source: String,
    name:   String
  ): CompilerEffect[Module] =
    compileState(source, name).flatMap { state =>
      if state.errors.isEmpty
      then EitherT.rightT[IO, CompilerError](state.module)
      else EitherT.leftT[IO, Module](CompilerError.SemanticErrors(state.errors.toList))
    }
