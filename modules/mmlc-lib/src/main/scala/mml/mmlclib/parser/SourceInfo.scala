package mml.mmlclib.parser

import mml.mmlclib.ast.SrcPoint

import scala.collection.mutable.ArrayBuffer

final case class SourceInfo(text: String, lineStarts: Array[Int]):

  def pointAt(index: Int): SrcPoint =
    val lineIdx = lineIndexFor(index)
    val start   = lineStarts(lineIdx)
    SrcPoint(lineIdx + 1, index - start + 1, index)

  def slice(start: Int, end: Int): String =
    text.substring(start, end)

  private def lineIndexFor(index: Int): Int =
    val pos = java.util.Arrays.binarySearch(lineStarts, index)
    if pos >= 0 then pos
    else
      val insertion = -pos - 1
      (insertion - 1).max(0)

object SourceInfo:
  def apply(text: String): SourceInfo =
    val starts = ArrayBuffer[Int](0)
    text.zipWithIndex.foreach { case (ch, idx) =>
      if ch == '\n' then starts += (idx + 1)
    }
    SourceInfo(text, starts.toArray)
