package mml.mmlclib.util.yolo

import cats.effect.unsafe.implicits.global
import cats.syntax.option.*
import mml.mmlclib.api.ParserApi
import mml.mmlclib.ast.*
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst

def printModuleAst(source: String, name: Option[String] = "Anon".some): Unit =
  ParserApi
    .parseModuleString(source, name)
    .value
    .map {
      case Right(ast) => println(s"Parsed AST:\n  ${prettyPrintAst(ast)}")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()

def printModuleAstSimple(source: String, name: Option[String] = "Anon".some): Unit =
  ParserApi
    .parseModuleString(source, name)
    .value
    .map {
      case Right(ast) => println(s"Parsed AST:\n  $ast")
      case Left(error) => println(s"Parse error:\n  $error")
    }
    .unsafeRunSync()

def parseModule(source: String, name: Option[String] = "Anon".some): Option[Module] =
  ParserApi
    .parseModuleString(source, name)
    .value
    .map {
      case Right(ast) => ast.some
      case Left(errors) =>
        println(s"Parse error:\n  $errors")
        none
    }
    .unsafeRunSync()
