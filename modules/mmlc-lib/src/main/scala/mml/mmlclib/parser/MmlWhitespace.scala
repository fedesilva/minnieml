package mml.mmlclib.parser

import fastparse.internal.Msgs
import fastparse.{ParsingRun, Whitespace}

import scala.annotation.tailrec

/** Whitespace syntax that supports // line-comments— but leaves the `/*` and `*/` tokens
  * untouched for the doc-comment parser.
  */
object MmlWhitespace {
  implicit object whitespace extends Whitespace {
    def apply(ctx: ParsingRun[?]): ParsingRun[Unit] = {
      val input = ctx.input

      @tailrec
      def rec(current: Int, state: Int): ParsingRun[Unit] =
        if !input.isReachable(current) then
          if ctx.verboseFailures then ctx.reportTerminalMsg(current, Msgs.empty)
          ctx.freshSuccessUnit(current)
        else
          val currentChar = input(current)
          state match
            // State 0: Normal whitespace handling.
            case 0 =>
              if currentChar == ' ' || currentChar == '\t' ||
                currentChar == '\n' || currentChar == '\r'
              then rec(current + 1, state)
              else if currentChar == '/' then
                if input.isReachable(current + 1) then
                  val next = input(current + 1)
                  if next == '/' then
                    // "//" - line comment, skip both and enter state 1
                    rec(current + 2, 1)
                  else if next == '*' then
                    // "/*" - doc comment start, preserve for docCommentP
                    ctx.freshSuccessUnit(current)
                  else
                    // Single '/' - operator, don't consume
                    ctx.freshSuccessUnit(current)
                else
                  // Single '/' at end of input - operator
                  ctx.freshSuccessUnit(current)
              else if currentChar == '*' then
                // Check for "*/" (doc comment end)
                if input.isReachable(current + 1) && input(current + 1) == '/' then
                  // "*/" - doc comment end, preserve for docCommentP
                  ctx.freshSuccessUnit(current)
                else
                  // Not a doc comment delimiter, don't consume
                  ctx.freshSuccessUnit(current)
              else
                if ctx.verboseFailures then ctx.reportTerminalMsg(current, Msgs.empty)
                ctx.freshSuccessUnit(current)

            // State 1: Handling a line comment started by '//' (but not '//*').
            case 1 =>
              if currentChar == '\n' then rec(current + 1, 0)
              else rec(current + 1, 1)

      rec(current = ctx.index, state = 0)
    }
  }
}
