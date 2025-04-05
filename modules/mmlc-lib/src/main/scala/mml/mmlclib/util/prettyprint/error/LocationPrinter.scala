package mml.mmlclib.util.prettyprint.error

import mml.mmlclib.ast.{SrcPoint, SrcSpan}

/** Utility for printing source locations */
object LocationPrinter:

  /** Print a source point
    *
    * @param point
    *   The source point to print
    * @return
    *   A string representation of the source point
    */
  def printPoint(point: SrcPoint): String =
    s"[${point.line}:${point.col}]"

  /** Print a source span
    *
    * @param span
    *   The source span to print
    * @return
    *   A string representation of the source span
    */
  def printSpan(span: SrcSpan): String =
    s"${printPoint(span.start)}-${printPoint(span.end)}"
