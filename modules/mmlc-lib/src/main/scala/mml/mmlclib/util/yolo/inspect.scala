package mml.mmlclib.util.yolo

import cats.Monad
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import mml.mmlclib.api.{AstApi, ParserApi}
import mml.mmlclib.api.impl.InMemoryAstApi

def printModuleAst(source: String): Unit =
  given Monad[IO]  = cats.effect.IO.asyncForIO
  given AstApi[IO] = InMemoryAstApi

  ParserApi
    .parseModuleString[IO](source)
    .map {
      case Right(ast) => println(s"Parsed AST:\n  $ast")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()
