package mml.mmlclib.parser

import cats.data.NonEmptyList
import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private val statementParamName = "__stmt"

private def mkStatementChain(head: Expr, tail: List[Expr]): Expr =
  val statements = head :: tail
  statements.reduceRight { (stmt, rest) =>
    val stmtSpan  = span(stmt.span.start, rest.span.end)
    val paramSpan = stmt.span
    val unitType  = TypeRef(paramSpan, "Unit")
    val param     = FnParam(paramSpan, statementParamName, typeAsc = Some(unitType))
    val lambda    = Lambda(stmtSpan, List(param), rest, captures = Nil)
    Expr(stmtSpan, List(App(stmtSpan, lambda, stmt)))
  }

private def exprFromTermsP(info: SourceInfo, termParser: => P[Term])(using P[Any]): P[Expr] =
  P(spP(info) ~ termParser.rep(1) ~ spP(info))
    .map { case (start, ts, end) =>
      val termsList = ts.toList
      val (typeSpec, typeAsc) =
        if termsList.size == 1 then (termsList.head.typeSpec, termsList.head.typeAsc)
        else (None, None)
      Expr(span(start, end), termsList, typeAsc, typeSpec)
    }

private[parser] def exprP(info: SourceInfo)(using P[Any]): P[Expr] =
  P(exprNoSeqP(info) ~ (endKw ~ &(exprNoSeqP(info)) ~ exprNoSeqP(info)).rep)
    .map { case (head, tail) =>
      if tail.isEmpty then head else mkStatementChain(head, tail.toList)
    }

private[parser] def exprNoSeqP(info: SourceInfo)(using P[Any]): P[Expr] =
  exprFromTermsP(info, termP(info))

private[parser] def exprMemberP(info: SourceInfo)(using P[Any]): P[Expr] =
  exprNoSeqMemberP(info)

private[parser] def exprNoSeqMemberP(info: SourceInfo)(using P[Any]): P[Expr] =
  exprFromTermsP(info, termMemberP(info))

private[parser] def termP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    letExprP(info) |
      litBoolP(info) |
      nativeImplP(info) |
      ifExprP(info) |
      litUnitP(info) |
      holeP(info) |
      litStringP(info) |
      opRefP(info) |
      numericLitP(info) |
      groupTermP(info) |
      tupleP(info) |
      refP(info) |
      phP(info)
  )

private[parser] def termMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    letExprP(info) |
      litBoolP(info) |
      nativeImplP(info) |
      ifExprMemberP(info) |
      litUnitP(info) |
      holeP(info) |
      litStringP(info) |
      opRefP(info) |
      numericLitP(info) |
      groupTermMemberP(info) |
      tupleMemberP(info) |
      refP(info) |
      phP(info)
  )

private[parser] def tupleP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ "(" ~ exprP(info).rep(sep = ",") ~ ")" ~ spP(info))
    .map { case (start, exprs, end) =>
      NonEmptyList
        .fromList(exprs.toList)
        .fold(
          TermError(
            span       = span(start, end),
            message    = "Tuple must have at least one element",
            failedCode = info.slice(start.index, end.index).some
          )
        ) { elements =>
          Tuple(span(start, end), elements)
        }

    }

private[parser] def tupleMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ "(" ~ exprMemberP(info).rep(sep = ",") ~ ")" ~ spP(info))
    .map { case (start, exprs, end) =>
      NonEmptyList
        .fromList(exprs.toList)
        .fold(
          TermError(
            span       = span(start, end),
            message    = "Tuple must have at least one element",
            failedCode = info.slice(start.index, end.index).some
          )
        ) { elements =>
          Tuple(span(start, end), elements)
        }

    }

private[parser] def ifExprP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ ifKw ~ exprP(info) ~ thenKw ~ exprP(info) ~ elseKw ~ exprP(info) ~ spP(
      info
    )
  )
    .map { case (start, cond, ifTrue, ifFalse, end) =>
      Cond(span(start, end), cond, ifTrue, ifFalse)
    }

private[parser] def letExprP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ letKw ~ bindingIdOrError ~ typeAscP(info)
      ~ defAsKw ~ exprNoSeqP(info) ~ endKw ~ exprP(info) ~ spP(info)
  ).map { case (start, idOrError, typeAsc, bindingExpr, restExpr, end) =>
    idOrError match
      case Left(invalidId) =>
        TermError(
          span       = span(start, end),
          message    = s"Invalid identifier '$invalidId'",
          failedCode = Some(invalidId)
        )
      case Right(name) =>
        val param = FnParam(span(start, end), name, typeAsc = typeAsc)
        val lambda = Lambda(
          span     = span(start, end),
          params   = List(param),
          body     = restExpr,
          captures = Nil
        )
        App(
          span = span(start, end),
          fn   = lambda,
          arg  = bindingExpr
        )
  }

private[parser] def groupTermP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ "(" ~ exprP(info) ~ ")" ~ spP(info))
    .map { case (start, expr, end) =>
      TermGroup(span(start, end), expr)
    }

private[parser] def ifExprMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ ifKw ~ exprMemberP(info) ~ thenKw ~ exprMemberP(info) ~ elseKw ~ exprMemberP(
      info
    ) ~ spP(info)
  )
    .map { case (start, cond, ifTrue, ifFalse, end) =>
      Cond(span(start, end), cond, ifTrue, ifFalse)
    }

private[parser] def groupTermMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ "(" ~ exprMemberP(info) ~ ")" ~ spP(info))
    .map { case (start, expr, end) =>
      TermGroup(span(start, end), expr)
    }

private[parser] def refP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ bindingIdP ~ typeAscP(info) ~ spP(info))
    .map { case (start, id, typeAsc, end) =>
      Ref(span(start, end), id, typeAsc = typeAsc)
    }

private[parser] def opRefP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ operatorIdP ~ spP(info))
    .map { case (start, id, end) =>
      Ref(span(start, end), id)
    }

private[parser] def phP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ placeholderKw ~ spP(info))
    .map { case (start, end) =>
      Placeholder(span(start, end), None)
    }

private[parser] def holeP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ holeKw ~ spP(info))
    .map { case (start, end) =>
      Hole(span(start, end))
    }

private[parser] def nativeImplP(info: SourceInfo)(using P[Any]): P[NativeImpl] =

  def nativeOpP: P[Option[String]] =
    P("[" ~ "op" ~ "=" ~ CharsWhileIn("a-zA-Z0-9_", 1).! ~ "]").?

  P(spP(info) ~ nativeKw ~ nativeOpP ~ spP(info))
    .map { case (start, op, end) =>
      NativeImpl(span(start, end), nativeOp = op)
    }
