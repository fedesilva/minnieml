package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*

private[parser] def spP[$: P](info: SourceInfo): P[SrcPoint] =
  P(Index).map(index => info.pointAt(index))

private[parser] def spNoWsP[$: P](info: SourceInfo): P[SrcPoint] =
  given fastparse.Whitespace with
    def apply(ctx: ParsingRun[?]): ParsingRun[Unit] =
      ctx.freshSuccessUnit(ctx.index)
  val _ = summon[fastparse.Whitespace]
  P(Index).map(index => info.pointAt(index))

private[parser] def span(start: SrcPoint, end: SrcPoint): SrcSpan =
  SrcSpan(start, end)
