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
    *   an optional name for the module
    * @return
    *   a ParserEffect that, when run, yields either a ParserError or a Module
    */
  def parseModuleString(
    source: String,
    name:   Option[String] = None
  ): ParserEffect[Module] =

    val n = name.map(sanitizeModuleName)
    EitherT(IO.pure(Parser.parseModule(source, n)))

  def sanitizeModuleName(dirName: String): String =
    val words = dirName.split("[-_ ]+").filter(_.nonEmpty)
    words.head.capitalize + words.tail.map(_.capitalize).mkString("")
