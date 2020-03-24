package mml.mmlclib.util.yolo

import mml.mmlclib.api.ParseContext
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.{ParseTree, TerminalNode}

import scala.annotation.tailrec

object ParseTreeInspector {

  case class NodeInfo(
    depth: Int,
    cls: String,
    tree: ParseTree
  )

  type Nodes = Seq[NodeInfo]

  def flatten(tree: ParseTree): Nodes = {

    import scala.jdk.CollectionConverters._

    //noinspection ScalaUnnecessaryParentheses
    @tailrec
    def loop(trees: List[ParseTree], nodes: Nodes): Nodes =
      trees match {

        case (t: ParserRuleContext) :: tail if t.getChildCount == 0 =>
          val cls = t.getClass.getName
          val depth = t.getRuleContext().depth

          loop(tail, nodes :+ NodeInfo(depth, cls, t))

        case (t: ParserRuleContext) :: tail if t.getChildCount > 0 =>
          val cls = t.getClass.getName
          val depth = t.getRuleContext().depth
          val children = t.children.asScala.toList

          loop(children ++ tail, nodes :+ NodeInfo(depth, cls, t))

        case (t: TerminalNode) :: tail =>
          val cls = t.getClass.getName + " " + t.getSymbol
          val depth = parentDepth(t) + 1

          loop(tail, nodes :+ NodeInfo(depth, cls, t))

        case t :: tail =>
          val cls = t.getClass.getName
          val depth = parentDepth(t) + 1

          loop(tail, nodes :+ NodeInfo(depth, cls, t))

        case Nil => nodes

      }

    @tailrec
    def parentDepth(current: ParseTree): Int = {
      Option(current.getParent) match {
        case Some(p: ParserRuleContext) => p.getRuleContext().depth()
        case Some(pt: ParseTree)        => parentDepth(pt)
        case None                       => 0
      }
    }

    loop(List(tree), Vector())

  }

  def print(nodes: Nodes): Unit = nodes foreach {

    case NodeInfo(depth, cls, t: TerminalNode) =>
      println(s" ${" " * depth} |-- $cls, ${t.getSymbol}")

    case NodeInfo(depth, cls, _) =>
      println(s" ${" " * depth} |-- $cls")

  }

  def flattenAndPrint[T <: ParserRuleContext](ctx: ParseContext[T]): Unit =
    flatten(ctx.tree) |> print

  def inspectCustom(nodes: Nodes)(pf: PartialFunction[NodeInfo, Unit]): Unit = {

    val default: PartialFunction[NodeInfo, Unit] = {

      case NodeInfo(depth, cls, t: TerminalNode) =>
        println(s" ${" " * depth}  $depth -> $cls : ${t.getSymbol}")

      case NodeInfo(depth, cls, _) =>
        println(s" ${" " * depth}  $depth -> $cls")

    }

    val processor = pf orElse default

    nodes foreach processor

  }

}
