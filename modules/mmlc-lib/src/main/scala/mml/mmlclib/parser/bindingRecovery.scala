package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

/** A name is resolvable only when its source spelling satisfies the binding grammar. */
private[parser] final case class ParsedBindingName(
  location: SrcSpan,
  value:    Option[String],
  error:    Option[ParseTermError]
)

/** A partial parameter prefix supplies lexical scope, but cannot establish callable metadata. */
private[parser] final case class ParsedParameters(
  values: List[FnParam],
  error:  Option[ParseTermError]
):
  def complete: Boolean = error.isEmpty

private val expressionRecovery = Recovery.Context(
  List(";", ",", "/*").map(Recovery.Anchor.Token(_)) ++
    List("if", "then", "elif", "else").map(Recovery.Anchor.Keyword(_)),
  declarationOnlyStops,
  recoverUnterminatedStrings = true
)

/** Preserves the exact failed fragment, including whitespace before the caller's boundary. */
private[parser] def bindingError(
  info:    SourceInfo,
  start:   SrcPoint,
  end:     SrcPoint,
  message: String
): ParseTermError =
  val text = info.slice(start.index, end.index)
  ParseTermError(span(start, end), message, Option.when(text.nonEmpty)(text))

/** Keeps retained syntax traversable before semantic normalization marks its result unavailable. */
private[parser] def appendBindingErrors(expr: Expr, errors: List[ParseTermError]): Expr =
  if errors.isEmpty then expr
  else Expr(expr.source, expr :: errors)

/** Missing required punctuation leaves the next source token available to the owning parser. */
private[parser] def requiredBindingTokenP(info: SourceInfo, token: String)(using
  P[Any]
): P[Option[ParseTermError]] =
  P(token).map(_ => none[ParseTermError]) |
    pointP(info).map(point => bindingError(info, point, point, s"Expected '$token'").some)

private[parser] def parsedBindingNameP(info: SourceInfo)(using P[Any]): P[ParsedBindingName] =
  val context = Recovery.Context(
    List("(", ":", "=", ";", "/*").map(Recovery.Anchor.Token(_)),
    declarationStops ++ List("then", "elif", "else").map(Recovery.Anchor.Keyword(_))
  )

  def recoveredNameP: P[ParsedToken[Option[Either[String, String]]]] =
    P(pointP(info) ~~ recoveryP(info, context)).map { case (start, recovered) =>
      ParsedToken(none, span(start, info.pointAt(recovered.end)))
    }

  P(
    tokenP(info)(bindingIdOrError).map(token => ParsedToken(token.value.some, token.location)) |
      recoveredNameP
  ).map { parsed =>
    parsed.value match
      case Some(Right(name)) => ParsedBindingName(parsed.location, name.some, none)
      case _ =>
        val start = parsed.location.start
        val end   = parsed.location.end
        val text  = info.slice(start.index, end.index)
        val message =
          if text.isEmpty then "Expected binding name" else s"Invalid identifier '$text'"
        ParsedBindingName(parsed.location, none, bindingError(info, start, end, message).some)
  }

/** Recovers the malformed tail of an opened list while preserving its successfully parsed prefix.
  */
private[parser] def parsedParametersP(
  info:        SourceInfo,
  contextType: TypeContext = TypeContext.Parameter
)(using P[Any]): P[ParsedParameters] =
  val context = Recovery.Context(
    List(")", "/*").map(Recovery.Anchor.Token(_)),
    List("=", ";").map(Recovery.Anchor.Token(_)) ++ declarationStops ++
      List("then", "elif", "else").map(Recovery.Anchor.Keyword(_))
  )

  def closingP: P[Option[ParseTermError]] =
    P(")").map(_ => none[ParseTermError]) |
      P(P(pointP(info) ~~ recoveryP(info, context)) ~ ")".?).map { case (start, recovered) =>
        bindingError(info, start, info.pointAt(recovered.end), "Invalid parameter list").some
      }

  P("(" ~ NoCut(fnParamP(info, contextType)).rep(sep = ",") ~ closingP)
    .map { case (params, error) => ParsedParameters(params.toList, error) } |
    pointP(info).map { point =>
      ParsedParameters(Nil, bindingError(info, point, point, "Expected '('").some)
    }

/** A frame excludes caller delimiters and declaration-only introducers, but permits local bindings.
  */
private def expressionBoundaryP(using P[Any]): P[Unit] =
  P(
    End | ";" | "," | ")" | "]" | "}" | "/*" | thenKw | elifKw | elseKw |
      declarationOnlyStartP
  )

/** A term keeps the endpoint of consumed annotations even when AST attachment discards them. */
private[parser] final case class ParsedTerm(value: Term, end: SrcPoint)

/** Optional frames must advance. Required frames may be empty after a consumed introducer. */
private[parser] def bindingFrameP(
  info:       SourceInfo,
  termParser: => P[ParsedTerm],
  required:   Boolean
)(using P[Any]): P[Expr] =
  P(pointP(info) ~ bindingFramePartP(info, termParser).rep ~ pointP(info))
    .filter { case (_, parts, _) => required || parts.nonEmpty }
    .map { case (start, parts, _) =>
      parts.toList match
        case Nil =>
          Expr(span(start, start), List(bindingError(info, start, start, "Expected expression")))
        case single :: Nil => single
        case multiple =>
          Expr(span(start, sourceEnd(multiple.last, start)), multiple.flatMap(_.terms))
    }

/** Each part advances through terms or malformed text, allowing a following `if` to own its
  * clauses. Scanner positions are independent of the shorter successful syntax span.
  */
private def bindingFramePartP(info: SourceInfo, termParser: => P[ParsedTerm])(using
  P[Any]
): P[Expr] =
  P(
    pointP(info) ~ (!expressionBoundaryP ~ NoCut(termParser)).rep ~
      P(pointP(info) ~~ recoveryP(info, expressionRecovery)) ~ pointP(info)
  ).filter { case (_, terms, (scannerStart, recovered), _) =>
    terms.nonEmpty || recovered.end > scannerStart.index
  }.map { case (start, parsedTerms, (scannerStart, recovered), _) =>
    val terms     = parsedTerms.toList.map(_.value)
    val prefixEnd = parsedTerms.lastOption.fold(start)(_.end)
    // Written annotations belong to their terms; a Lambda annotation constrains its return value.
    val prefix = Expr(
      span(start, prefixEnd),
      terms,
      typeSpec = if terms.size == 1 then terms.head.typeSpec else none
    )
    if recovered.end > scannerStart.index then
      val end   = info.pointAt(recovered.end)
      val error = bindingError(info, scannerStart, end, "Invalid expression syntax")
      if terms.isEmpty then Expr(span(start, end), List(error))
      else Expr(span(start, end), List(prefix, error))
    else prefix
  }
