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
  case class Leaf(name: String, text: String, position: Position) extends TreeNode

  private def getPosition(tree: ParseTree): Position = tree match {
    case ctx: ParserRuleContext if ctx.getStart != null =>
      Position(ctx.getStart.getLine, ctx.getStart.getCharPositionInLine)
    case terminal: TerminalNode if terminal.getSymbol != null =>
      Position(terminal.getSymbol.getLine, terminal.getSymbol.getCharPositionInLine)
    case _ => Position(0, 0) // fallback for nodes without position info
  }

  def walk(tree: ParseTree): TreeNode = {
    @tailrec
    def walkHelper(stack: List[ParseTree], acc: TreeNode): TreeNode = stack match {
      case Nil => acc
      case (node: ParserRuleContext) :: rest =>
        val name      = node.getClass.getSimpleName.replaceAll("Context$", "")
        val position  = getPosition(node)
        val children  = (0 until node.getChildCount).map(node.getChild).toList
        val newBranch = Branch(name, List(), position)
        walkHelper(children ++ rest, addChild(acc, newBranch))
      case (leaf: TerminalNode) :: rest =>
        val newLeaf = Leaf(leaf.getClass.getSimpleName, leaf.getText, getPosition(leaf))
        walkHelper(rest, addChild(acc, newLeaf))
      case node :: rest =>
        val newLeaf = Leaf(node.getClass.getSimpleName, node.getText, getPosition(node))
        walkHelper(rest, addChild(acc, newLeaf))
    }

    def addChild(parent: TreeNode, child: TreeNode): TreeNode = parent match {
      case Branch(name, children, pos) => Branch(name, children :+ child, pos)
      case leaf => leaf // This case should not occur in practice
    }

    walkHelper(List(tree), Branch("Root", List(), Position(0, 0)))
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

def improvedPrintModuleString(source: String): Unit = {
  import ImprovedParseTreeWalker._
  import cats.effect.unsafe.implicits.global  // Import the global executor

  ParserApi
    .parseModuleString(source)
    .flatMap(ctx => walkAndPrint(ctx.tree))
    .unsafeRunSync()
}