package mml.mmlclib.api

import cats.data.EitherT
import cats.effect.IO
import mml.mmlclib.api.ParserEffect
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.Parser

object ParserApi:
  /** Parse a source string into a Module
    *
    * @param source
    *   the source string to parse
    * @param name
    *   the module name to associate with the parsed members
    * @param sourcePath
    *   optional source file path for the module
    * @return
    *   a ParserEffect that, when run, yields either a ParserError or a Module
    */
  def parseModuleString(
    source:     String,
    name:       String,
    sourcePath: Option[String] = None
  ): ParserEffect[Module] =

    val sanitized = sanitizeModuleName(name)
    EitherT(IO.pure(Parser.parseModule(source, sanitized, sourcePath)))

  def sanitizeModuleName(dirName: String): String =
    val words = dirName.split("[-_ ]+").filter(_.nonEmpty)
    words.head.capitalize + words.tail.map(_.capitalize).mkString("")
