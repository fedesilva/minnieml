package mml.mmlclib.util.yolo

import cats.Monad
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import mml.mmlclib.api.impl.InMemoryAstApi
import mml.mmlclib.api.{AstApi, ParserApi}
import mml.mmlclib.util.prettyPrintAst

def printModuleAst(source: String, name: Option[String] = None): Unit =
  given Monad[IO]  = cats.effect.IO.asyncForIO
  given AstApi[IO] = InMemoryAstApi

  ParserApi
    .parseModuleString[IO](source, name)
    .map {
      case Right(ast) => println(s"Parsed AST:\n  ${prettyPrintAst(ast)}")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()
