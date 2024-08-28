package mml.mmlclib.util.yolo

import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.translator.AntlrTranslator
import mml.mmlclib.api.impl.ast.InMemoryAstApi
import mml.mmlclib.api.AstApi
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.Monad

def printModuleAst(source: String): Unit = {
  given Monad[IO]  = cats.effect.IO.asyncForIO
  given AstApi[IO] = InMemoryAstApi

  val translator = AntlrTranslator.moduleTranslator[IO]
  ParserApi
    .parseModuleString(source)    
    .flatMap(ctx => translator.translate(ctx.tree))
    .map(ast => println(s"Parsed AST: $ast"))
    .unsafeRunSync()
}
