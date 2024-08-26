package mml.mmlclib.util.yolo

import java.nio.file.Path
import mml.mmlclib.api._
import cats.effect.unsafe.implicits.global


def printModuleString(source: String): Unit =
  ParserApi
    .parseModuleString(source)
    .flatMap(ParseTreeInspector.flattenAndPrint)
    .unsafeRunSync()


def printModuleFile(source: Path): Unit =
  ParserApi
    .parseModuleFile(source)
    .flatMap(ParseTreeInspector.flattenAndPrint)
    .unsafeRunSync()

def printScriptString(source: String): Unit =
  ParserApi
    .parseScriptString(source)
    .flatMap(ParseTreeInspector.flattenAndPrint)
    .unsafeRunSync()

def printScriptFile(source: Path): Unit =
  ParserApi
    .parseScriptFile(source)
    .flatMap(ParseTreeInspector.flattenAndPrint)
    .unsafeRunSync()
