package mml.mmlclib.util.yolo

import mml.mmlclib.util._
import java.nio.file.Path

import mml.mmlclib.api.ParserApi

object InspectModuleParseTree {

  def printModuleString(source: String): Unit =
    ParserApi().parseModuleString(source) |>
      ParseTreeInspector.flattenAndPrint

  def printModuleFile(source: Path): Unit =
    ParserApi().parseModuleFile(source) |>
      ParseTreeInspector.flattenAndPrint

}

object InspectScriptParseTree {

  def printScriptString(source: String): Unit =
    ParserApi().parseScriptString(source) |>
      ParseTreeInspector.flattenAndPrint

  def printScriptFile(source: Path): Unit =
    ParserApi().parseScriptFile(source) |>
      ParseTreeInspector.flattenAndPrint

}
