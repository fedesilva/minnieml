package mml.mmlclib.parser

import org.antlr.v4.runtime.ParserRuleContext

import mml.mmlclib.util.yolo.ParseTreeInspector
import org.antlr.v4.runtime.tree.ParseTree

import scala.annotation.tailrec

object ParseTreeWalker:

  type Nodes = Seq[ParseTreeInspector.NodeInfo]

  def walk(tree: ParseTree): Nodes =

    import scala.jdk.CollectionConverters._

    @tailrec
    def loop(trees: List[ParseTree], nodes: Nodes): Nodes =
      trees match

        case (t: ParserRuleContext) :: tail if t.getChildCount == 0 =>
          val cls   = t.getClass.getSimpleName
          val depth = t.getRuleContext().depth

          loop(tail, nodes :+ ParseTreeInspector.NodeInfo(depth, cls, t))

        case (t: ParserRuleContext) :: tail if t.getChildCount > 0 =>
          val cls      = t.getClass.getSimpleName
          val depth    = t.getRuleContext().depth
          val children = t.children.asScala.toList

          loop(children ++ tail, nodes :+ ParseTreeInspector.NodeInfo(depth, cls, t))

        case t :: tail =>
          val cls   = t.getClass.getSimpleName
          val depth = 1

          loop(tail, nodes :+ ParseTreeInspector.NodeInfo(depth, cls, t))

        case Nil => nodes

    loop(List(tree), Vector())
