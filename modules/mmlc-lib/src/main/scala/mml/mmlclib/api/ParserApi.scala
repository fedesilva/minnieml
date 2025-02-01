package mml.mmlclib.api

import java.nio.file.{Files, Path}
import cats.Monad
import cats.effect.Sync
import cats.syntax.all._
import mml.mmlclib.ast.Module
import mml.mmlclib.parser.Parser
import mml.mmlclib.api.AstApi

object ParserApi:
  def parseModuleString[F[_]: AstApi: Monad](source: String): F[Either[String, Module]] =
    Parser.parseModule[F](source)

  def parseModuleFile[F[_]: AstApi: Sync](path: Path): F[Either[String, Module]] =
    Sync[F].blocking(Files.readString(path)).flatMap(src => Parser.parseModule[F](src))
