package mml.mmlclib.api

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.ParserError
import mml.mmlclib.semantic.*

enum CompilerError:
  case SemanticErrors(errors: List[SemanticError])
  case ParserErrors(errors: List[ParserError])
  case Unknown(msg: String)

object CompilerApi:

  def compileString(
    source: String,
    name:   Option[String] = None
  ): IO[Either[CompilerError, Module]] =
    ParserApi.parseModuleString(source, name).flatMap {
      case Right(module) =>
        SemanticApi.rewriteModule(module)
      case Left(errors) =>
        IO.pure(CompilerError.ParserErrors(List(errors)).asLeft)
    }
