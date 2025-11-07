package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*

private[parser] def spP[$: P](info: SourceInfo): P[SrcPoint] =
  P(Index).map(index => info.pointAt(index))

private[parser] def span(start: SrcPoint, end: SrcPoint): SrcSpan =
  SrcSpan(start, end)
