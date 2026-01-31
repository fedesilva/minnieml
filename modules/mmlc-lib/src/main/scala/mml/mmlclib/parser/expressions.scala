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
  P(spP(info) ~ termParser.rep(1) ~ spNoWsP(info) ~ spP(info))
    .map { case (start, ts, end, _) =>
      val termsList = ts.toList
      val (typeSpec, typeAsc) =
        if termsList.size == 1 then (termsList.head.typeSpec, termsList.head.typeAsc)
        else (None, None)
      Expr(span(start, end), termsList, typeAsc, typeSpec)
    }

private[parser] def exprP(info: SourceInfo)(using P[Any]): P[Expr] =
  P(
    exprNoSeqP(info) ~
      (semiKw ~ &(!"/*" ~ exprNoSeqP(info)) ~ exprNoSeqP(info)).rep
  ).map { case (head, tail) =>
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
      ifSingleBranchExprP(info) |
      litUnitP(info) |
      holeP(info) |
      litStringP(info) |
      selectionTermP(info) |
      opRefP(info) |
      numericLitP(info) |
      groupTermP(info) |
      tupleP(info) |
      typeRefTermP(info) |
      refP(info) |
      phP(info)
  )

private[parser] def termMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    letExprP(info) |
      litBoolP(info) |
      nativeImplP(info) |
      ifExprMemberP(info) |
      ifSingleBranchExprMemberP(info) |
      litUnitP(info) |
      holeP(info) |
      litStringP(info) |
      selectionTermMemberP(info) |
      opRefP(info) |
      numericLitP(info) |
      groupTermMemberP(info) |
      tupleMemberP(info) |
      typeRefTermP(info) |
      refP(info) |
      phP(info)
  )

private[parser] def selectionTermP(info: SourceInfo)(using P[Any]): P[Term] =
  selectionP(info, selectionBaseP(info))

private[parser] def selectionTermMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  selectionP(info, selectionBaseMemberP(info))

private def selectionBaseP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    groupTermP(info) |
      tupleP(info) |
      typeRefTermP(info) |
      refP(info)
  )

private def selectionBaseMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    groupTermMemberP(info) |
      tupleMemberP(info) |
      typeRefTermP(info) |
      refP(info)
  )

private def selectionFieldP(info: SourceInfo)(using P[Any]): P[(String, SrcSpan)] =
  import fastparse.NoWhitespace.*
  P("." ~ spP(info) ~ bindingIdP ~ spNoWsP(info)).map { case (start, name, end) =>
    name -> span(start, end)
  }

private def selectionP(info: SourceInfo, baseP: => P[Term])(using P[Any]): P[Term] =
  P(
    spP(info) ~ baseP ~ selectionFieldP(info).rep(1) ~ typeAscP(info) ~ spNoWsP(info) ~
      spP(info)
  ).map { case (_, base, fields, typeAsc, _, _) =>
    val fieldList = fields.toList
    val lastIndex = fieldList.size - 1
    fieldList.zipWithIndex.foldLeft(base) { case (qualifier, ((fieldName, fieldSpan), idx)) =>
      val selectionSpan = span(qualifier.span.start, fieldSpan.end)
      Ref(
        span      = selectionSpan,
        name      = fieldName,
        typeAsc   = if idx == lastIndex then typeAsc else None,
        qualifier = Some(qualifier)
      )
    }
  }

private[parser] def tupleP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ "(" ~ exprP(info).rep(sep = ",") ~ ")" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, exprs, end, _) =>
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
  P(spP(info) ~ "(" ~ exprMemberP(info).rep(sep = ",") ~ ")" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, exprs, end, _) =>
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
    spP(info) ~ ifKw ~ exprP(info) ~ thenKw ~ exprP(info) ~
      (elifKw ~ exprP(info) ~ thenKw ~ exprP(info)).rep ~
      elseKw ~ exprP(info) ~ endKw ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, cond, ifTrue, elsifs, ifFalse, end, _) =>
    val finalSpan = span(start, end)
    // Build nested Cond from elsif chain (fold right)
    val elseExpr = elsifs.toList.foldRight(ifFalse) { case ((elsifCond, elsifBody), acc) =>
      val condSpan = span(elsifCond.span.start, acc.span.end)
      Expr(condSpan, List(Cond(condSpan, elsifCond, elsifBody, acc)))
    }
    Cond(finalSpan, cond, ifTrue, elseExpr)
  }

private[parser] def ifSingleBranchExprP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ ifKw ~ exprP(info) ~ thenKw ~ exprP(info) ~
      (elifKw ~ exprP(info) ~ thenKw ~ exprP(info)).rep ~
      endKw ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, cond, ifTrue, elsifs, end, _) =>
    val finalSpan = span(start, end)
    val unitType  = TypeRef(finalSpan, "Unit")
    val unitSpan  = span(end, end)
    // Synthesize LiteralUnit for the missing else branch
    val unitExpr = Expr(unitSpan, List(LiteralUnit(unitSpan)))
    // Build nested Cond from elsif chain (fold right), ending with unit
    val elseExpr = elsifs.toList.foldRight(unitExpr) { case ((elsifCond, elsifBody), acc) =>
      val condSpan = span(elsifCond.span.start, acc.span.end)
      Expr(
        condSpan,
        List(Cond(condSpan, elsifCond, elsifBody, acc, typeSpec = Some(unitType)))
      )
    }
    Cond(finalSpan, cond, ifTrue, elseExpr, typeSpec = Some(unitType), typeAsc = Some(unitType))
  }

private[parser] def letExprP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ letKw ~ spP(info) ~ bindingIdOrError ~ spNoWsP(info) ~ spP(info) ~
      typeAscP(info) ~ defAsKw ~ exprNoSeqP(info) ~ semiKw ~ exprP(info) ~ spNoWsP(info) ~
      spP(info)
  ).map { case (start, idStart, idOrError, idEnd, _, typeAsc, bindingExpr, restExpr, end, _) =>
    idOrError match
      case Left(invalidId) =>
        TermError(
          span       = span(start, end),
          message    = s"Invalid identifier '$invalidId'",
          failedCode = Some(invalidId)
        )
      case Right(name) =>
        val param = FnParam(span(idStart, idEnd), name, typeAsc = typeAsc)
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
  P(spP(info) ~ "(" ~ exprP(info) ~ ")" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, expr, end, _) =>
      TermGroup(span(start, end), expr)
    }

private[parser] def ifExprMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ ifKw ~ exprMemberP(info) ~ thenKw ~ exprMemberP(info) ~
      (elifKw ~ exprMemberP(info) ~ thenKw ~ exprMemberP(info)).rep ~
      elseKw ~ exprMemberP(info) ~ endKw ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, cond, ifTrue, elsifs, ifFalse, end, _) =>
    val finalSpan = span(start, end)
    // Build nested Cond from elsif chain (fold right)
    val elseExpr = elsifs.toList.foldRight(ifFalse) { case ((elsifCond, elsifBody), acc) =>
      val condSpan = span(elsifCond.span.start, acc.span.end)
      Expr(condSpan, List(Cond(condSpan, elsifCond, elsifBody, acc)))
    }
    Cond(finalSpan, cond, ifTrue, elseExpr)
  }

private[parser] def ifSingleBranchExprMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ ifKw ~ exprMemberP(info) ~ thenKw ~ exprMemberP(info) ~
      (elifKw ~ exprMemberP(info) ~ thenKw ~ exprMemberP(info)).rep ~
      endKw ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, cond, ifTrue, elsifs, end, _) =>
    val finalSpan = span(start, end)
    val unitType  = TypeRef(finalSpan, "Unit")
    val unitSpan  = span(end, end)
    // Synthesize LiteralUnit for the missing else branch
    val unitExpr = Expr(unitSpan, List(LiteralUnit(unitSpan)))
    // Build nested Cond from elsif chain (fold right), ending with unit
    val elseExpr = elsifs.toList.foldRight(unitExpr) { case ((elsifCond, elsifBody), acc) =>
      val condSpan = span(elsifCond.span.start, acc.span.end)
      Expr(
        condSpan,
        List(Cond(condSpan, elsifCond, elsifBody, acc, typeSpec = Some(unitType)))
      )
    }
    Cond(finalSpan, cond, ifTrue, elseExpr, typeSpec = Some(unitType), typeAsc = Some(unitType))
  }

private[parser] def groupTermMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ "(" ~ exprMemberP(info) ~ ")" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, expr, end, _) =>
      TermGroup(span(start, end), expr)
    }

private[parser] def refP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ bindingIdP ~ typeAscP(info) ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, typeAsc, end, _) =>
      Ref(span(start, end), id, typeAsc = typeAsc)
    }

private[parser] def typeRefTermP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ typeIdP ~ typeAscP(info) ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, typeAsc, end, _) =>
      Ref(span(start, end), id, typeAsc = typeAsc)
    }

private[parser] def opRefP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ operatorIdP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, end, _) =>
      Ref(span(start, end), id)
    }

private[parser] def phP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ placeholderKw ~ spNoWsP(info) ~ spP(info))
    .map { case (start, end, _) =>
      Placeholder(span(start, end), None)
    }

private[parser] def holeP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ holeKw ~ spNoWsP(info) ~ spP(info))
    .map { case (start, end, _) =>
      Hole(span(start, end))
    }

private[parser] def nativeImplP(info: SourceInfo)(using P[Any]): P[NativeImpl] =

  def memEffectP: P[MemEffect] =
    P("alloc").map(_ => MemEffect.Alloc) | P("static").map(_ => MemEffect.Static)

  def tplAttrP: P[String]    = P("tpl" ~ "=" ~ "\"" ~ CharsWhile(_ != '"', 0).! ~ "\"")
  def memAttrP: P[MemEffect] = P("mem" ~ "=" ~ memEffectP)

  // Parse [tpl="...", mem=alloc] or [mem=alloc, tpl="..."] or just one
  def attrsP: P[(Option[String], Option[MemEffect])] =
    P(
      "[" ~ (
        (tplAttrP ~ ("," ~ memAttrP).?).map { case (t, m) => (Some(t), m) } |
          (memAttrP ~ ("," ~ tplAttrP).?).map { case (m, t) => (t, Some(m)) }
      ) ~ "]"
    ).?.map(_.getOrElse((None, None)))

  P(spP(info) ~ nativeKw ~ attrsP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, (tpl, mem), end, _) =>
      NativeImpl(span(start, end), nativeTpl = tpl, memEffect = mem)
    }
