package mml.mmlclib.parser

import cats.data.NonEmptyList
import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private val statementParamName = "__stmt"

private def locSpan(node: FromSource): SrcSpan =
  node.source match
    case SourceOrigin.Loc(s) => s
    case SourceOrigin.Synth =>
      throw IllegalStateException("Parser expected source-located node")

private def mkStatementChain(head: Expr, tail: List[Expr]): Expr =
  val statements = head :: tail
  statements.reduceRight { (stmt, rest) =>
    val stmtLoc   = locSpan(stmt)
    val restLoc   = locSpan(rest)
    val stmtSpan  = span(stmtLoc.start, restLoc.end)
    val paramSpan = stmtLoc
    val unitType  = TypeRef(paramSpan, "Unit")
    val param =
      FnParam(SourceOrigin.Synth, Name.synth(statementParamName), typeAsc = Some(unitType))
    val lambda = Lambda(stmtSpan, List(param), rest, captures = Nil)
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

private[parser] def terminatedExprP(info: SourceInfo)(using P[Any]): P[(Expr, SrcPoint)] =
  P(exprP(info) ~ semiKw ~ spNoWsP(info))

private[parser] def terminatedNestedMemberExprP(info: SourceInfo)(using
  P[Any]
): P[(Expr, SrcPoint)] =
  P(exprP(info) ~ semiKw ~ spNoWsP(info))

private def withTypeAsc(info: SourceInfo, termParser: => P[Term])(using P[Any]): P[Term] =
  P(termParser ~ typeAscP(info)).map {
    case (term, Some(asc)) => term.withTypeAsc(asc)
    case (term, None) => term
  }

private[parser] def termP(info: SourceInfo)(using P[Any]): P[Term] =
  withTypeAsc(
    info,
    letExprP(info) |
      litBoolP(info) |
      nativeImplP(info) |
      ifExprP(info) |
      ifSingleBranchExprP(info) |
      litUnitP(info) |
      holeP(info) |
      litStringP(info) |
      selectionTermP(info) |
      numericLitP(info) |
      opRefP(info) |
      groupTermP(info) |
      tupleP(info) |
      lambdaLitP(info) |
      typeRefTermP(info) |
      refP(info) |
      phP(info)
  )

private[parser] def termMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  withTypeAsc(
    info,
    letExprP(info) |
      litBoolP(info) |
      nativeImplP(info) |
      ifExprMemberP(info) |
      ifSingleBranchExprMemberP(info) |
      litUnitP(info) |
      holeP(info) |
      litStringP(info) |
      selectionTermMemberP(info) |
      numericLitP(info) |
      opRefP(info) |
      groupTermMemberP(info) |
      tupleMemberP(info) |
      lambdaLitP(info) |
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
      val qualifierLoc  = locSpan(qualifier)
      val selectionSpan = span(qualifierLoc.start, fieldSpan.end)
      Ref(
        source    = SourceOrigin.Loc(selectionSpan),
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
    spP(info) ~ ifKw ~ exprP(info) ~ thenKw ~ terminatedExprP(info) ~
      (elifKw ~ exprP(info) ~ thenKw ~ terminatedExprP(info)).rep ~
      elseKw ~ terminatedExprP(info) ~ spNoWsP(info)
  ).map { case (start, cond, (ifTrue, _), elsifs, (ifFalse, _), end) =>
    val finalSpan = span(start, end)
    // Build nested Cond from elsif chain (fold right)
    val elseExpr = elsifs.toList.foldRight(ifFalse) { case ((elsifCond, (elsifBody, _)), acc) =>
      val elsifCondLoc = locSpan(elsifCond)
      val accLoc       = locSpan(acc)
      val condSpan     = span(elsifCondLoc.start, accLoc.end)
      Expr(condSpan, List(Cond(condSpan, elsifCond, elsifBody, acc)))
    }
    Cond(finalSpan, cond, ifTrue, elseExpr)
  }

private[parser] def ifSingleBranchExprP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ ifKw ~ exprP(info) ~ thenKw ~ terminatedExprP(info) ~
      (elifKw ~ exprP(info) ~ thenKw ~ terminatedExprP(info)).rep ~
      spNoWsP(info)
  ).map { case (start, cond, (ifTrue, _), elsifs, end) =>
    val finalSpan = span(start, end)
    val unitType  = TypeRef(finalSpan, "Unit")
    val unitSpan  = span(end, end)
    // Synthesize LiteralUnit for the missing else branch
    val unitExpr = Expr(unitSpan, List(LiteralUnit(unitSpan)))
    // Build nested Cond from elsif chain (fold right), ending with unit
    val elseExpr = elsifs.toList.foldRight(unitExpr) { case ((elsifCond, (elsifBody, _)), acc) =>
      val elsifCondLoc = locSpan(elsifCond)
      val accLoc       = locSpan(acc)
      val condSpan     = span(elsifCondLoc.start, accLoc.end)
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
        val param = FnParam(
          SourceOrigin.Loc(span(idStart, idEnd)),
          Name(span(idStart, idEnd), name),
          typeAsc = typeAsc
        )
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

private def lambdaWithParamsP(info: SourceInfo)(using P[Any]): P[(List[FnParam], Expr)] =
  P(fnParamListP(info).filter(_.nonEmpty) ~ arrowKw ~/ terminatedExprP(info))
    .map { case (params, (body, _)) => params -> body }

private def lambdaNoParamsP(info: SourceInfo)(using P[Any]): P[(List[FnParam], Expr)] =
  P(terminatedExprP(info)).map { case (body, _) => Nil -> body }

private[parser] def lambdaLitP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ "{" ~
      (lambdaWithParamsP(info) | lambdaNoParamsP(info)) ~
      "}" ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, (params, body), end, _) =>
    Lambda(span(start, end), params, body, captures = Nil)
  }

private[parser] def groupTermP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ "(" ~ exprP(info) ~ ")" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, expr, end, _) =>
      TermGroup(span(start, end), expr)
    }

private[parser] def ifExprMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ ifKw ~ exprMemberP(info) ~ thenKw ~ terminatedNestedMemberExprP(info) ~
      (elifKw ~ exprMemberP(info) ~ thenKw ~ terminatedNestedMemberExprP(info)).rep ~
      elseKw ~ terminatedNestedMemberExprP(info) ~ spNoWsP(info)
  ).map { case (start, cond, (ifTrue, _), elsifs, (ifFalse, _), end) =>
    val finalSpan = span(start, end)
    // Build nested Cond from elsif chain (fold right)
    val elseExpr = elsifs.toList.foldRight(ifFalse) { case ((elsifCond, (elsifBody, _)), acc) =>
      val elsifCondLoc = locSpan(elsifCond)
      val accLoc       = locSpan(acc)
      val condSpan     = span(elsifCondLoc.start, accLoc.end)
      Expr(condSpan, List(Cond(condSpan, elsifCond, elsifBody, acc)))
    }
    Cond(finalSpan, cond, ifTrue, elseExpr)
  }

private[parser] def ifSingleBranchExprMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ ifKw ~ exprMemberP(info) ~ thenKw ~ terminatedNestedMemberExprP(info) ~
      (elifKw ~ exprMemberP(info) ~ thenKw ~ terminatedNestedMemberExprP(info)).rep ~
      spNoWsP(info)
  ).map { case (start, cond, (ifTrue, _), elsifs, end) =>
    val finalSpan = span(start, end)
    val unitType  = TypeRef(finalSpan, "Unit")
    val unitSpan  = span(end, end)
    // Synthesize LiteralUnit for the missing else branch
    val unitExpr = Expr(unitSpan, List(LiteralUnit(unitSpan)))
    // Build nested Cond from elsif chain (fold right), ending with unit
    val elseExpr = elsifs.toList.foldRight(unitExpr) { case ((elsifCond, (elsifBody, _)), acc) =>
      val elsifCondLoc = locSpan(elsifCond)
      val accLoc       = locSpan(acc)
      val condSpan     = span(elsifCondLoc.start, accLoc.end)
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
      Ref(SourceOrigin.Loc(span(start, end)), id, typeAsc = typeAsc)
    }

private[parser] def typeRefTermP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ typeIdP ~ typeAscP(info) ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, typeAsc, end, _) =>
      Ref(SourceOrigin.Loc(span(start, end)), id, typeAsc = typeAsc)
    }

private[parser] def opRefP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ operatorIdP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, end, _) =>
      Ref(SourceOrigin.Loc(span(start, end)), id)
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

  enum Attr:
    case TplAttr(tpl: String)
    case MemAttr(eff: MemEffect)
    case NameAttr(name: String)

  def memEffectP: P[MemEffect] =
    P("alloc").map(_ => MemEffect.Alloc) | P("static").map(_ => MemEffect.Static)

  def quotedStringP: P[String] = P("\"" ~ CharsWhile(_ != '"', 0).! ~ "\"")
  def tplAttrP:      P[Attr]   = P("tpl" ~ "=" ~ quotedStringP).map(Attr.TplAttr(_))
  def memAttrP:      P[Attr]   = P("mem" ~ "=" ~ memEffectP).map(Attr.MemAttr(_))
  def nameAttrP:     P[Attr]   = P("name" ~ "=" ~ quotedStringP).map(Attr.NameAttr(_))

  def attrsP: P[(Option[String], Option[MemEffect], Option[String])] =
    P("[" ~ (tplAttrP | memAttrP | nameAttrP).rep(sep = ",", min = 1) ~ "]").?.map {
      case None => (None, None, None)
      case Some(attrs) =>
        val tpl  = attrs.collectFirst { case Attr.TplAttr(t) => t }
        val mem  = attrs.collectFirst { case Attr.MemAttr(e) => e }
        val name = attrs.collectFirst { case Attr.NameAttr(n) => n }
        (tpl, mem, name)
    }

  P(spP(info) ~ nativeKw ~ attrsP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, (tpl, mem, name), end, _) =>
      NativeImpl(span(start, end), nativeTpl = tpl, memEffect = mem, nativeSymbol = name)
    }
