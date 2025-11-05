package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*

private[parser] def spP[$: P](source: String): P[SrcPoint] =
  P(Index).map(index => indexToSourcePoint(index, source))

private[parser] def indexToSourcePoint(index: Int, source: String): SrcPoint =
  val upToIndex = source.substring(0, index)
  val lines     = upToIndex.split('\n')
  val line      = lines.length
  val col       = if lines.isEmpty then index + 1 else lines.last.length + 1
  SrcPoint(line, col, index)

private[parser] def span(start: SrcPoint, end: SrcPoint): SrcSpan =
  SrcSpan(start, end)
