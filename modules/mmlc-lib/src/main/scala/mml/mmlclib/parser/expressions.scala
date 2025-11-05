package mml.mmlclib.parser

import cats.data.NonEmptyList
import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def exprP(source: String)(using P[Any]): P[Expr] =
  P(spP(source) ~ termP(source).rep ~ spP(source))
    .map { case (start, ts, end) =>
      val termsList = ts.toList
      val (typeSpec, typeAsc) =
        if termsList.size == 1 then (termsList.head.typeSpec, termsList.head.typeAsc)
        else (None, None)
      Expr(span(start, end), termsList, typeAsc, typeSpec)
    }

private[parser] def termP(source: String)(using P[Any]): P[Term] =
  P(
    litBoolP(source) |
      nativeImplP(source) |
      ifExprP(source) |
      litUnitP(source) |
      holeP(source) |
      litStringP(source) |
      opRefP(source) |
      numericLitP(source) |
      groupTermP(source) |
      tupleP(source) |
      refP(source) |
      phP(source)
  )

private[parser] def tupleP(source: String)(using P[Any]): P[Term] =
  P(spP(source) ~ "(" ~ exprP(source).rep(sep = ",") ~ ")" ~ spP(source))
    .map { case (start, exprs, end) =>
      NonEmptyList
        .fromList(exprs.toList)
        .fold(
          TermError(
            span       = span(start, end),
            message    = "Tuple must have at least one element",
            failedCode = source.substring(start.index, end.index).some
          )
        ) { elements =>
          Tuple(span(start, end), elements)
        }

    }

private[parser] def ifExprP(source: String)(using P[Any]): P[Term] =
  P(
    spP(source) ~ ifKw ~/ exprP(source) ~ thenKw ~/ exprP(source) ~ elseKw ~/ exprP(
      source
    ) ~ spP(source)
  )
    .map { case (start, cond, ifTrue, ifFalse, end) =>
      Cond(span(start, end), cond, ifTrue, ifFalse)
    }

private[parser] def groupTermP(source: String)(using P[Any]): P[Term] =
  P(spP(source) ~ "(" ~ exprP(source) ~ ")" ~ spP(source))
    .map { case (start, expr, end) =>
      TermGroup(span(start, end), expr)
    }

private[parser] def refP(source: String)(using P[Any]): P[Term] =
  P(spP(source) ~ bindingIdP ~ typeAscP(source) ~ spP(source))
    .map { case (start, id, typeAsc, end) =>
      Ref(span(start, end), id, typeAsc = typeAsc)
    }

private[parser] def opRefP(source: String)(using P[Any]): P[Term] =
  P(spP(source) ~ operatorIdP ~ spP(source))
    .map { case (start, id, end) =>
      Ref(span(start, end), id)
    }

private[parser] def phP(source: String)(using P[Any]): P[Term] =
  P(spP(source) ~ placeholderKw ~ spP(source))
    .map { case (start, end) =>
      Placeholder(span(start, end), None)
    }

private[parser] def holeP(source: String)(using P[Any]): P[Term] =
  P(spP(source) ~ holeKw ~ spP(source))
    .map { case (start, end) =>
      Hole(span(start, end))
    }

private[parser] def nativeImplP(source: String)(using P[Any]): P[NativeImpl] =

  def nativeOpP: P[Option[String]] =
    P("[" ~ "op" ~ "=" ~ CharsWhileIn("a-zA-Z0-9_", 1).! ~ "]").?

  P(spP(source) ~ nativeKw ~ nativeOpP ~ spP(source))
    .map { case (start, op, end) =>
      NativeImpl(span(start, end), nativeOp = op)
    }
