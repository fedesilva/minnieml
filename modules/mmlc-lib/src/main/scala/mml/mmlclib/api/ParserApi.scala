package mml.mmlclib.api

import cats.data.EitherT
import cats.effect.{IO, Sync}
import cats.syntax.all.*
import mml.mmlclib.api.ParserEffect
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.Parser

import java.nio.file.{Files, Path}

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

  /** Parse a file into a Module
    *
    * @param path
    *   the path to the file to parse
    * @return
    *   a ParserEffect that, when run, yields either a ParserError or a Module
    */
  def parseModuleFile(path: Path): ParserEffect[Module] =
    val parentName = sanitizeModuleName(path.getParent.getFileName.toString)
    EitherT(
      Sync[IO]
        .blocking(Files.readString(path))
        .map(src => Parser.parseModule(src, parentName.some))
    )

  def sanitizeModuleName(dirName: String): String =
    val words = dirName.split("[-_ ]+").filter(_.nonEmpty)
    words.head.capitalize + words.tail.map(_.capitalize).mkString("")
