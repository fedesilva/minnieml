package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

/** Parses numeric literals.
  *
  * Examples:
  * {{{
  * 42
  * 3.14
  * .5
  * }}}
  */
private[parser] def numericLitP(info: SourceInfo)(using P[Any]): P[LiteralValue] =
  import fastparse.NoWhitespace.*
  P(
    (spP(info) ~ (CharIn("0-9").rep(1) ~ "." ~ CharIn("0-9").rep(1)).! ~ spNoWsP(info) ~ spP(info))
      .map { case (start, s, end, _) =>
        LiteralFloat(span(start, end), s.toFloat)
      } |
      (spP(info) ~ ("." ~ CharIn("0-9").rep(1)).! ~ spNoWsP(info) ~ spP(info)).map {
        case (start, s, end, _) =>
          LiteralFloat(span(start, end), s.toFloat)
      } |
      P(spP(info) ~ CharIn("0-9").rep(1).! ~ spNoWsP(info) ~ spP(info)).map {
        case (start, s, end, _) =>
          LiteralInt(span(start, end), s.toInt)
      }
  )

/** Parses string literals enclosed in double quotes.
  *
  * Example:
  * {{{
  * "hola"
  * }}}
  */
private[parser] def litStringP(info: SourceInfo)(using P[Any]): P[LiteralString] =
  import fastparse.NoWhitespace.*
  P(spP(info) ~ "\"" ~ CharsWhile(_ != '"', 0).! ~ "\"" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, s, end, _) => LiteralString(span(start, end), s) }

/** Parses boolean literals.
  *
  * Examples:
  * {{{
  * true
  * false
  * }}}
  */
private[parser] def litBoolP(info: SourceInfo)(using P[Any]): P[LiteralBool] =
  P(spP(info) ~ ("true" | "false").! ~ spNoWsP(info) ~ spP(info))
    .map { (start, b, end, _) =>
      LiteralBool(span(start, end), b.toBoolean)
    }

/** Parses the unit literal `()`. */
private[parser] def litUnitP(info: SourceInfo)(using P[Any]): P[LiteralUnit] =
  P(spP(info) ~ "()" ~ spNoWsP(info) ~ spP(info)).map { case (start, end, _) =>
    LiteralUnit(span(start, end))
  }

/** Parses a block doc comment that attaches to the following declaration or parameter.
  *
  * Example:
  * {{{
  * /* Adds one to its input. */
  * fn inc(x: Int): Int =
  *   x + 1;
  * ;
  * }}}
  */
private[parser] def docCommentP[$: P](info: SourceInfo): P[Option[DocComment]] =
  import fastparse.NoWhitespace.*

  def comment: P[String] = P("/*" ~ commentBody ~ "*/")
  def commentBody: P[String] =
    P((comment | (!("*/") ~ AnyChar).!).rep).map(_.mkString.stripMargin('*'))

  P(spP(info) ~ comment.! ~ spNoWsP(info) ~ spP(info)).?.map {
    case Some(start, s, end, _) =>
      val clean = s.replaceAll("/\\*", "").replaceAll("\\*/", "").stripMargin('*').trim
      DocComment(span(start, end), clean).some
    case None => none
  }
