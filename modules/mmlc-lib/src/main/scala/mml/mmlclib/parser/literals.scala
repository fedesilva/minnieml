package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def numericLitP(info: SourceInfo)(using P[Any]): P[LiteralValue] =
  import fastparse.NoWhitespace.*
  P(
    (spP(info) ~ CharIn("0-9").rep(1) ~ "." ~ CharIn("0-9").rep(1).! ~ spP(info))
      .map { case (start, s, end) =>
        LiteralFloat(span(start, end), s.toFloat)
      } |
      (spP(info) ~ "." ~ CharIn("0-9").rep(1).! ~ spP(info)).map { case (start, s, end) =>
        LiteralFloat(span(start, end), s.toFloat)
      } |
      P(spP(info) ~ CharIn("0-9").rep(1).! ~ spP(info)).map { case (start, s, end) =>
        LiteralInt(span(start, end), s.toInt)
      }
  )

private[parser] def litStringP(info: SourceInfo)(using P[Any]): P[LiteralString] =
  import fastparse.NoWhitespace.*
  P(spP(info) ~ "\"" ~ CharsWhile(_ != '"', 0).! ~ "\"" ~ spP(info))
    .map { case (start, s, end) => LiteralString(span(start, end), s) }

private[parser] def litBoolP(info: SourceInfo)(using P[Any]): P[LiteralBool] =
  P(spP(info) ~ ("true" | "false").! ~ spP(info))
    .map { (start, b, end) =>
      LiteralBool(span(start, end), b.toBoolean)
    }

private[parser] def litUnitP(info: SourceInfo)(using P[Any]): P[LiteralUnit] =
  P(spP(info) ~ "()" ~ spP(info)).map { case (start, end) =>
    LiteralUnit(span(start, end))
  }

private[parser] def docCommentP[$: P](info: SourceInfo): P[Option[DocComment]] =
  import fastparse.NoWhitespace.*

  def comment: P[String] = P("#-" ~ commentBody ~ "-#")
  def commentBody: P[String] =
    P((comment | (!("-#") ~ AnyChar).!).rep).map(_.mkString.stripMargin('#'))

  P(spP(info) ~ comment.! ~ spP(info)).?.map {
    case Some(start, s, end) =>
      val clean = s.replaceAll("#-", "").replaceAll("-#", "").trim
      DocComment(span(start, end), clean).some
    case None => none
  }
