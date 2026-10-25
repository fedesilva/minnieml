package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*

/** Captures the raw cursor. Surrounding combinators determine whether leading trivia is skipped. */
private[parser] def pointP[$: P](info: SourceInfo): P[SrcPoint] =
  P(Index).map(index => info.pointAt(index))

private[parser] final case class ParsedToken[A](value: A, location: SrcSpan)

/** Captures a token before trailing trivia. The caller handles leading whitespace.
  *
  * The token must end at its owned syntax; a child that consumes trailing trivia cannot be measured
  * by this wrapper. Capture before whitespace-consuming validation as well.
  */
private[parser] def tokenP[A](info: SourceInfo)(token: => P[A])(using P[Any]): P[ParsedToken[A]] =
  P(Index ~~ token ~~ Index).map { case (start, value, end) =>
    ParsedToken(value, span(info.pointAt(start), info.pointAt(end)))
  }

/** Builds a source span from the captured start and end points of a parsed fragment. */
private[parser] def span(start: SrcPoint, end: SrcPoint): SrcSpan =
  SrcSpan(start, end)

/** Uses a child's owned endpoint, or the caller's boundary when the child has no source. */
private[parser] def sourceEnd(node: FromSource, fallback: SrcPoint): SrcPoint =
  node.spanOpt.fold(fallback)(_.end)
