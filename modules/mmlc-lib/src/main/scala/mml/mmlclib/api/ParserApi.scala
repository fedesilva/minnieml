package mml.mmlclib.api

import java.nio.file.{Files, Path}
import cats.Monad
import cats.effect.Sync
import cats.syntax.all._
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.Parser
import mml.mmlclib.api.AstApi

object ParserApi:
  def parseModuleString[F[_]: AstApi: Monad](
    source: String,
    name:   Option[String] = None
  ): F[Either[String, Module]] = {
    val n = name.map(sanitizeModuleName)
    Parser.parseModule[F](source, n)
  }

  def parseModuleFile[F[_]: AstApi: Sync](path: Path): F[Either[String, Module]] = {
    val parentName = sanitizeModuleName(path.getParent.getFileName.toString)
    Sync[F].blocking(Files.readString(path)).flatMap(src => Parser.parseModule[F](src))
  }

  private def sanitizeModuleName(dirName: String): String = {
    val words = dirName.split("[-_ ]+").filter(_.nonEmpty)
    words.head.capitalize + words.tail.map(_.capitalize).mkString("")
  }
