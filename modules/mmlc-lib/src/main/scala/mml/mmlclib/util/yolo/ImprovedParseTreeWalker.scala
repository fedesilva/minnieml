package mml.mmlclib.util.yolo

import org.antlr.v4.runtime.tree.{ParseTree, TerminalNode}
import org.antlr.v4.runtime.{ParserRuleContext, Token}
import cats.effect.IO
import mml.mmlclib.api.ParserApi

import scala.annotation.tailrec

object ImprovedParseTreeWalker {
  case class Position(line: Int, column: Int)

  sealed trait TreeNode {
    def position: Position
  }

  case class Branch(name: String, children: List[TreeNode], position: Position) extends TreeNode
  case class Leaf(name: String, text: String, position: Position)               extends TreeNode

  private def getPosition(tree: ParseTree): Position = tree match {
    case ctx: ParserRuleContext if ctx.getStart != null =>
      Position(ctx.getStart.getLine, ctx.getStart.getCharPositionInLine)
    case terminal: TerminalNode if terminal.getSymbol != null =>
      Position(terminal.getSymbol.getLine, terminal.getSymbol.getCharPositionInLine)
    case _ => Position(0, 0) // fallback for nodes without position info
  }

  def walk(tree: ParseTree): TreeNode = tree match {
    case ctx: ParserRuleContext =>
      val name     = ctx.getClass.getSimpleName.replaceAll("Context$", "")
      val position = getPosition(ctx)
      val children = (0 until ctx.getChildCount).map(ctx.getChild).map(walk).toList
      Branch(name, children, position)

    case terminal: TerminalNode =>
      Leaf("TerminalNode", terminal.getText, getPosition(terminal))

    case _ =>
      Leaf("UnknownNode", tree.getText, getPosition(tree))
  }

  def prettyPrint(node: TreeNode, indent: String = ""): String = {
    val posStr = s"(${node.position.line}:${node.position.column})"
    node match {
      case Branch(name, children, _) =>
        val childrenStr = children.map(prettyPrint(_, indent + "  ")).mkString("\n")
        s"$indent$name $posStr\n$childrenStr"
      case Leaf(name, text, _) =>
        s"$indent$name: $text $posStr"
    }
  }

  def walkAndPrint(tree: ParseTree): IO[Unit] = IO {
    val rootNode = walk(tree)
    println(prettyPrint(rootNode))
  }

}

def printModuleParseTree(source: String): Unit =
  import cats.effect.unsafe.implicits.global
  ParserApi
    .parseModuleString(source)
    .flatMap(ctx => ImprovedParseTreeWalker.walkAndPrint(ctx.tree))
    .unsafeRunSync()
