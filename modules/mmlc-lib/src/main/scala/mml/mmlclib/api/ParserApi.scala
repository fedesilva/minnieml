package mml.mmlclib.api

import cats.effect.IO
import fastparse.P
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.Parser

import java.nio.file.{Files, Path}
import scala.io.Source

object ParserApi:
  def parseModuleString[F[_]: AstApi](source: String): IO[Either[String, F[Module]]] =
    IO(Parser.parseModule(source))

  def parseModuleFile[F[_]: AstApi](path: Path): IO[Either[String, F[Module]]] =
    IO.blocking {
      val source = Files.readString(path)
      Parser.parseModule(source)
    }
