package mml.mmlclib.util.yolo

import cats.effect.unsafe.implicits.global
import cats.syntax.option.*
import mml.mmlclib.api.ParserApi
import mml.mmlclib.util.prettyPrintAst

def printModuleAst(source: String, name: Option[String] = "Anon".some): Unit =
  ParserApi
    .parseModuleString(source, name)
    .map {
      case Right(ast) => println(s"Parsed AST:\n  ${prettyPrintAst(ast)}")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()

def printModuleAstSimple(source: String, name: Option[String] = "Anon".some): Unit =
  ParserApi
    .parseModuleString(source, name)
    .map {
      case Right(ast) => println(s"Parsed AST:\n  $ast")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()
