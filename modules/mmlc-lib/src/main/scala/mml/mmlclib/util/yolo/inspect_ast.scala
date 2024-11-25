package mml.mmlclib.util.yolo

import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.translator.AntlrTranslator
import mml.mmlclib.api.AstApi
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.Monad
import mml.mmlclib.api.impl.InMemoryAstApi

def printModuleAst(source: String): Unit = {
  given Monad[IO]  = cats.effect.IO.asyncForIO
  given AstApi[IO] = InMemoryAstApi

  val translator = AntlrTranslator.moduleTranslator[IO]
  ParserApi
    .parseModuleString(source) // type: IO[ParseResult[ModuleContext]]
    .flatMap(ctx => translator.translate(ctx.tree)) // type: IO[AstNode]
    .map(ast => println(s"Parsed AST: $ast - errors: ")) // type: IO[Unit]
    .unsafeRunSync() // type: Unit
}
