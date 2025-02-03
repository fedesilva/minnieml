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
      def rec(current: Int, state: Int, nesting: Int): ParsingRun[Unit] =
        if !input.isReachable(current) then
          if state == 0 || state == 1 then
            if ctx.verboseFailures then ctx.reportTerminalMsg(current, Msgs.empty)
            ctx.freshSuccessUnit(current)
          else if state == 2 && nesting == 0 then
            if ctx.verboseFailures then ctx.reportTerminalMsg(current, Msgs.empty)
            ctx.freshSuccessUnit(current - 1)
          else
            ctx.cut = true
            val res = ctx.freshFailure(current)
            if ctx.verboseFailures then ctx.reportTerminalMsg(current, () => Util.literalize("-#"))
            res
        else
          val currentChar = input(current)
          state match
            // **State 0: Normal Whitespace Handling**
            case 0 =>
              if currentChar == ' ' || currentChar == '\t' || currentChar == '\n' || currentChar == '\r'
              then rec(current + 1, state, nesting)
              else if currentChar == '#' then
                if input.isReachable(current + 1) && input(current + 1) == '-' then
                  rec(current + 2, 2, nesting + 1) // Multiline comment starts (`#-`)
                else rec(current + 1, 1, nesting) // Line comment starts (`#`)
              else
                if ctx.verboseFailures then ctx.reportTerminalMsg(current, Msgs.empty)
                ctx.freshSuccessUnit(current)

            // **State 1: Handling Line Comments (`# ...`)**
            case 1 =>
              if currentChar == '\n' then rec(current + 1, 0, nesting)
              else rec(current + 1, 1, nesting)

            // **State 2: Handling Multiline Comments (`#- ... -#`)**
            case 2 =>
              if input.isReachable(current + 1) then
                val nextChar = input(current + 1)
                if currentChar == '#' && nextChar == '-' then
                  rec(current + 2, 2, nesting + 1) // Nested multiline comment (`#- ... #-`)
                else if currentChar == '-' && nextChar == '#' then
                  if nesting == 1 then rec(current + 2, 0, 0) // End of multiline comment (`-#`)
                  else rec(current + 2, 2, nesting - 1) // Decrease nesting
                else rec(current + 1, 2, nesting)
              else rec(current + 1, 2, nesting)

      rec(current = ctx.index, state = 0, nesting = 0)
    }
  }
}
