package mml.mmlclib.util.yolo

import cats.Monad
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import mml.mmlclib.api.{AstApi, ParserApi}
import mml.mmlclib.api.impl.InMemoryAstApi
import mml.mmlclib.ast.translator.AntlrTranslator

def printModuleAst(source: String): Unit = {
  given Monad[IO]  = cats.effect.IO.asyncForIO
  given AstApi[IO] = InMemoryAstApi

  val translator = AntlrTranslator.moduleTranslator[IO]
  ParserApi
    .parseModuleString(source) // type: IO[ParseResult[ModuleContext]]
    .flatMap(ctx => translator.translate(ctx.tree)) // type: IO[AstNode]
    .map(ast => println(s"Parsed AST:\n  $ast \n errors: ")) // type: IO[Unit]
    .unsafeRunSync() // type: Unit
}
