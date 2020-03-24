package mml.mmlclib.util.parser

import mml.mmlclib.api.ParseError.TreeError
import mml.mmlclib.api.{ParseContext, ParseError}

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.{ErrorNode, ParseTree}

import scala.annotation.tailrec

object ErrorChecker {

  def isFailed[T <: ParserRuleContext](ctx: ParseContext[T]): Boolean =
    failures(ctx).nonEmpty

  /** Walks the parse tree in search of error nodes and adds them to the syntax errors
   *   present in the context.
   */
  def failures[T <: ParserRuleContext](ctx: ParseContext[T]): Seq[ParseError] = {

    import scala.jdk.CollectionConverters._

    @tailrec
    def loop(trees: List[ParseTree], errors: Seq[ParseError]): Seq[ParseError] =
      trees match {

        case (e: ErrorNode) :: tail =>
          loop(tail, errors :+ TreeError(e))

        case (t: ParserRuleContext) :: tail if t.getChildCount > 0 =>
          val children = t.children.asScala.toList
          loop(children ++ tail, errors)

        case _ :: tail =>
          loop(tail, errors)

        case Nil =>
          errors

      }

    ctx.errors ++ loop(List(ctx.tree), Seq())

  }

}
