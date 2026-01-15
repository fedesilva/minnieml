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

  def parseModuleWithInfo(
    source:     String,
    name:       String,
    sourcePath: Option[String] = None
  ): (SourceInfo, ParserResult) =
    val info = SourceInfo(source)
    val result =
      parse(source, p => topLevelModuleP(name, info, sourcePath)(using p)) match
        case Parsed.Success(result, _) =>
          result.asRight
        case f: Parsed.Failure =>
          ParserError.Failure(f.trace().longMsg).asLeft
    (info, result)

  def parseModule(
    source:     String,
    name:       String,
    sourcePath: Option[String] = None
  ): ParserResult =
    parseModuleWithInfo(source, name, sourcePath)._2
