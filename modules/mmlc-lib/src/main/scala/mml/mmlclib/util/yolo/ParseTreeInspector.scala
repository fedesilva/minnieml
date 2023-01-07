package mml.mmlclib.util.yolo


import mml.mmlclib.api.*
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.{ParseTree, TerminalNode, ErrorNode}

import scala.annotation.tailrec
import mml.mmlclib.parser.antlr.MinnieMLParser.{IdContext, TpIdContext}

object ParseTreeInspector:

  case class NodeInfo(
    depth: Int,
    cls:   String,
    tree:  ParseTree
  )

  type Nodes = Seq[NodeInfo]

  def flatten(tree: ParseTree): Nodes =

    import scala.jdk.CollectionConverters._

    @tailrec
    def loop(trees: List[ParseTree], nodes: Nodes): Nodes =
      
      trees match

        case (t: ParserRuleContext) :: tail if t.getChildCount == 0 =>
          val cls = t.getClass.getSimpleName
          val depth = t.getRuleContext().depth

          loop(tail, nodes :+ NodeInfo(depth, cls, t))

        case (t: ParserRuleContext) :: tail if t.getChildCount > 0 =>
          val cls = t.getClass.getSimpleName
          val depth = t.getRuleContext().depth
          val children = t.children.asScala.toList

          loop(children ++ tail, nodes :+ NodeInfo(depth, cls, t))

        case (t: TerminalNode) :: tail =>
          val cls = t.getClass.getSimpleName
          val depth = parentDepth(t) + 1

          loop(tail, nodes :+ NodeInfo(depth, cls, t))

        case t :: tail =>
          val cls = t.getClass.getSimpleName
          val depth = parentDepth(t) + 1

          loop(tail, nodes :+ NodeInfo(depth, cls, t))

        case Nil => nodes


    @tailrec
    def parentDepth(current: ParseTree): Int =
      Option(current.getParent) match
        case Some(p: ParserRuleContext) => p.getRuleContext().depth()
        case Some(pt: ParseTree) => parentDepth(pt)
        case None => 0

    loop(List(tree), Vector())
 

  def print(nodes: Nodes): Unit = nodes foreach {

    case NodeInfo(depth, cls, t: ErrorNode) =>
      println(s" ${" " * depth} $cls : ${t.getText} - ${t.getPayload.toString} ")

    case NodeInfo(depth, cls, t: TerminalNode) =>
      println(s" ${" " * depth} ${t.getSymbol.getText} ${t.getSymbol}")

    case NodeInfo(depth, cls, t: IdContext) =>
      println(s" ${" " * depth} $cls : ${t.getRuleContext().getText}")

    case NodeInfo(depth, cls, t: TpIdContext) =>
      println(s" ${" " * depth} $cls : ${t.getRuleContext().getText}")

    case NodeInfo(depth, cls, _) =>
      println(s" ${" " * depth} $cls")

  }

  def flattenAndPrint[T <: ParserRuleContext](ctx: ParseContext[T]): Unit =
    if ctx.errors.isEmpty then
      flatten(ctx.tree) |> print
    else
      flatten(ctx.tree) |> print
      ctx.errors foreach println

  /** Apply an effectful partial function to the list of nodes `orElse` use a default
    *
    * @param nodes
    * @param pf
    */
  def inspectCustom(nodes: Nodes)(pf: PartialFunction[NodeInfo, Unit]): Unit =

    val default: PartialFunction[NodeInfo, Unit] =

      case NodeInfo(depth, cls, t: TerminalNode) =>
        println(s" ${" " * depth}  $depth -> $cls : ${t.getSymbol.getText}")

      case NodeInfo(depth, cls, t: ParserRuleContext) =>
        println(s" ${" " * depth}  $depth -> $cls : ${t.toStringTree()}")

      case NodeInfo(depth, cls, _) =>
        println(s" ${" " * depth}  $depth -> $cls")


    val processor = pf orElse default

    nodes foreach processor


