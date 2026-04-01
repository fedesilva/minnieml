package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*

/** Captures the current source position after normal parser whitespace has been consumed. */
private[parser] def spP[$: P](info: SourceInfo): P[SrcPoint] =
  P(Index).map(index => info.pointAt(index))

/** Captures the current source position without consuming parser whitespace.
  *
  * This is used when delimiters must be measured exactly, for example around `;`, `)`, or `}`.
  */
private[parser] def spNoWsP[$: P](info: SourceInfo): P[SrcPoint] =
  given fastparse.Whitespace with
    def apply(ctx: ParsingRun[?]): ParsingRun[Unit] =
      ctx.freshSuccessUnit(ctx.index)
  val _ = summon[fastparse.Whitespace]
  P(Index).map(index => info.pointAt(index))

/** Builds a source span from the captured start and end points of a parsed fragment. */
private[parser] def span(start: SrcPoint, end: SrcPoint): SrcSpan =
  SrcSpan(start, end)
