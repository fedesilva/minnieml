package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*
import mml.mmlclib.errors.CompilationError

enum ParserError extends CompilationError:
  case Failure(message: String)
  case Unknown(message: String)

type ParserResult = Either[ParserError, Module]

object Parser:

  def parseModule(source: String, name: Option[String] = "Anon".some): ParserResult =
    parse(source, moduleP(name, source, _)) match
      case Parsed.Success(result, _) =>
        result.asRight
      case f: Parsed.Failure =>
        ParserError.Failure(f.trace().longMsg).asLeft