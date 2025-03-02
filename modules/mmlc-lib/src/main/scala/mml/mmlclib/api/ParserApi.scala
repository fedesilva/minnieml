package mml.mmlclib.api

import cats.effect.IO
import cats.effect.Sync
import cats.syntax.all.*
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.Parser
import mml.mmlclib.parser.ParserError
import mml.mmlclib.parser.ParserResult

import java.nio.file.Files
import java.nio.file.Path

object ParserApi:

  def parseModuleString(
    source: String,
    name:   Option[String] = None
  ): IO[ParserResult] =
    val n = name.map(sanitizeModuleName)
    IO.pure(
      Parser.parseModule(source, n)
    )

  def parseModuleFile(path: Path): IO[ParserResult] =
    val parentName = sanitizeModuleName(path.getParent.getFileName.toString)
    Sync[IO]
      .blocking(Files.readString(path))
      .flatMap(src =>
        Sync[IO].pure(
          Parser.parseModule(src, parentName.some)
        )
      )

  def sanitizeModuleName(dirName: String): String =
    val words = dirName.split("[-_ ]+").filter(_.nonEmpty)
    words.head.capitalize + words.tail.map(_.capitalize).mkString("")
