package mml.mmlclib.ast.queries

import mml.mmlclib.ast.SrcSpan

/** Position facts over source spans, independent of node visibility or editor recovery. */
object SourceQueries:

  /** Tests a 1-based line and column against the span's inclusive start and exclusive end. */
  def containsPosition(span: SrcSpan, line: Int, col: Int): Boolean =
    val afterStart = line > span.start.line ||
      (line == span.start.line && col >= span.start.col)
    val beforeEnd = line < span.end.line ||
      (line == span.end.line && col < span.end.col)
    afterStart && beforeEnd

  /** Both endpoints have positive source coordinates; the span may still be empty. */
  def hasCoordinates(span: SrcSpan): Boolean =
    span.start.line > 0 && span.start.col > 0 && span.end.line > 0 && span.end.col > 0
