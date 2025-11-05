package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def numericLitP(source: String)(using P[Any]): P[LiteralValue] =
  import fastparse.NoWhitespace.*
  P(
    // Try float patterns first
    // N.N
    (spP(source) ~ CharIn("0-9").rep(1) ~ "." ~ CharIn("0-9").rep(1).! ~ spP(
      source
    )).map { case (start, s, end) =>
      LiteralFloat(span(start, end), s.toFloat)
    } |
      // .N
      (spP(source) ~ "." ~ CharIn("0-9").rep(1).! ~ spP(source)).map { case (start, s, end) =>
        LiteralFloat(span(start, end), s.toFloat)
      } |
      // If there's no dot, parse as integer
      P(spP(source) ~ CharIn("0-9").rep(1).! ~ spP(source)).map { case (start, s, end) =>
        LiteralInt(span(start, end), s.toInt)
      }
  )

private[parser] def litStringP(source: String)(using P[Any]): P[LiteralString] =
  import fastparse.NoWhitespace.*
  P(spP(source) ~ "\"" ~/ CharsWhile(_ != '"', 0).! ~ "\"" ~ spP(source))
    .map { case (start, s, end) => LiteralString(span(start, end), s) }

private[parser] def litBoolP(source: String)(using P[Any]): P[LiteralBool] =
  P(spP(source) ~ ("true" | "false").! ~ spP(source))
    .map { (start, b, end) =>
      LiteralBool(span(start, end), b.toBoolean)
    }

private[parser] def litUnitP(source: String)(using P[Any]): P[LiteralUnit] =
  P(spP(source) ~ "()" ~ spP(source)).map { case (start, end) =>
    LiteralUnit(span(start, end))
  }

private[parser] def docCommentP[$: P](src: String): P[Option[DocComment]] =
  import fastparse.NoWhitespace.*

  def comment: P[String] = P("#-" ~ commentBody ~ "-#")
  def commentBody: P[String] =
    P((comment | (!("-#") ~ AnyChar).!).rep).map(_.mkString.stripMargin('#'))

  P(spP(src) ~ comment.! ~ spP(src)).?.map {
    case Some(start, s, end) =>
      val clean = s.replaceAll("#-", "").replaceAll("-#", "").trim
      DocComment(span(start, end), clean).some
    case None => none
  }
