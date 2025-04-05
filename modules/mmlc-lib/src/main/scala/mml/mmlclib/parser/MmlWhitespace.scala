package mml.mmlclib.parser

import fastparse.internal.Msgs
import fastparse.{ParsingRun, Whitespace}

import scala.annotation.tailrec

/** Whitespace syntax that supports # line-comments— but leaves the `#-` and `-#` tokens untouched
  * for the doc-comment parser.
  */
object MmlWhitespace {
  implicit object whitespace extends Whitespace {
    def apply(ctx: ParsingRun[?]): ParsingRun[Unit] = {
      val input = ctx.input

      @tailrec
      def rec(current: Int, state: Int): ParsingRun[Unit] =
        if (!input.isReachable(current)) then
          if (ctx.verboseFailures) then ctx.reportTerminalMsg(current, Msgs.empty)
          ctx.freshSuccessUnit(current)
        else
          val currentChar = input(current)
          state match
            // State 0: Normal whitespace handling.
            case 0 =>
              if (
                  currentChar == ' ' || currentChar == '\t' || currentChar == '\n' || currentChar == '\r'
                )
              then rec(current + 1, state)
              else if (currentChar == '#') then
                if (input.isReachable(current + 1) && input(current + 1) == '-') then
                  // Preserve "#-" for docCommentP: do not consume.
                  ctx.freshSuccessUnit(current)
                else
                  // Consume the line-comment marker and go to state 1.
                  rec(current + 1, 1)
              else if (currentChar == '-') then
                if (input.isReachable(current + 1) && input(current + 1) == '#') then
                  // Preserve "-#" for docCommentP: do not consume.
                  ctx.freshSuccessUnit(current)
                else
                  // Do NOT consume '-' as whitespace.
                  ctx.freshSuccessUnit(current)
              else
                if (ctx.verboseFailures) then ctx.reportTerminalMsg(current, Msgs.empty)
                ctx.freshSuccessUnit(current)

            // State 1: Handling a line comment started by '#' (but not "#-").
            case 1 =>
              if (currentChar == '\n') then rec(current + 1, 0)
              else rec(current + 1, 1)

      rec(current = ctx.index, state = 0)
    }
  }
}
