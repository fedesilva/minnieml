package mml.mmlclib.parser

import fastparse.{ParsingRun, Whitespace}
import fastparse.internal.Util
import fastparse.internal.Msgs
import scala.annotation.tailrec

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
            // **State 0: Normal Whitespace Handling**
            case 0 =>
              if currentChar == ' ' || currentChar == '\t' || currentChar == '\n' || currentChar == '\r'
              then rec(current + 1, state)
              else if currentChar == '#' then
                if input.isReachable(current + 1) && input(current + 1) == '-' then
                  ctx.freshSuccessUnit(current) // **Preserve `#-` for `docCommentP`**
                else rec(current + 1, 1) // Line comment starts (`#`)
              else if currentChar == '-' then
                if input.isReachable(current + 1) && input(current + 1) == '#' then
                  ctx.freshSuccessUnit(current) // **Preserve `-#` for `docCommentP`**
                else rec(current + 1, 0) // Normal character, continue parsing
              else
                if ctx.verboseFailures then ctx.reportTerminalMsg(current, Msgs.empty)
                ctx.freshSuccessUnit(current)

            // **State 1: Handling Line Comments (`# ...`)**
            case 1 =>
              if currentChar == '\n' then rec(current + 1, 0)
              else rec(current + 1, 1)

      rec(current = ctx.index, state = 0)
    }
  }
}
