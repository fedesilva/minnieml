package mml.mmlclib.parser

import mml.mmlclib.ast.SrcPoint

/** Maps raw character offsets back to line/column source locations for parser spans and errors. */
final case class SourceInfo(text: String, lineStarts: Array[Int]):

  /** Converts a character offset into a 1-based [[SrcPoint]]. */
  def pointAt(index: Int): SrcPoint =
    val lineIdx = lineIndexFor(index)
    val start   = lineStarts(lineIdx)
    SrcPoint(lineIdx + 1, index - start + 1, index)

  /** Returns the exact source slice used for parse diagnostics or recovery snippets. */
  def slice(start: Int, end: Int): String =
    text.substring(start, end)

  private def lineIndexFor(index: Int): Int =
    val pos = java.util.Arrays.binarySearch(lineStarts, index)
    if pos >= 0 then pos
    else
      val insertion = -pos - 1
      (insertion - 1).max(0)

object SourceInfo:
  /** Precomputes line starts for one source buffer.
    *
    * Example:
    * {{{
    * val info = SourceInfo("let x = 1;\nlet y = 2;")
    * }}}
    */
  def apply(text: String): SourceInfo =
    val starts =
      text.iterator.zipWithIndex
        .foldLeft(List(0)) { case (acc, (ch, idx)) =>
          if ch == '\n' then (idx + 1) :: acc else acc
        }
    SourceInfo(text, starts.reverse.toArray)
