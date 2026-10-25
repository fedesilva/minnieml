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
    tokenP(info)((CharIn("0-9").rep(1) ~ "." ~ CharIn("0-9").rep(1)).!)
      .map { token =>
        LiteralFloat(token.location, token.value.toFloat)
      } |
      tokenP(info)(("." ~ CharIn("0-9").rep(1)).!).map { token =>
        LiteralFloat(token.location, token.value.toFloat)
      } |
      tokenP(info)(CharIn("0-9").rep(1).!).flatMap { token =>
        token.value.toLongOption.filter(_ <= 2147483648L) match
          case Some(value) => Pass(LiteralInt(token.location, value))
          case None => Fail
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
  tokenP(info)(P("\"" ~ CharsWhile(_ != '"', 0).! ~ "\""))
    .map(token => LiteralString(token.location, token.value))

/** Parses boolean literals.
  *
  * Examples:
  * {{{
  * true
  * false
  * }}}
  */
private[parser] def litBoolP(info: SourceInfo)(using P[Any]): P[LiteralBool] =
  P(tokenP(info)(("true" | "false").!) ~ pointP(info))
    .map { (token, _) =>
      LiteralBool(token.location, token.value.toBoolean)
    }

/** Parses the unit literal `()`. */
private[parser] def litUnitP(info: SourceInfo)(using P[Any]): P[LiteralUnit] =
  P(tokenP(info)(P("()")) ~ pointP(info)).map { case (token, _) =>
    LiteralUnit(token.location)
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

  tokenP(info)(comment.!).?.map {
    case Some(token) =>
      val clean = token.value.replaceAll("/\\*", "").replaceAll("\\*/", "").stripMargin('*').trim
      DocComment(token.location, clean).some
    case None => none
  }
