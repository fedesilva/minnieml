package mml.mmlclib.parser

import cats.data.NonEmptyList
import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

/** Synthetic binder name used when a statement sequence is lowered to nested lambda applications.
  */
private val statementParamName = "__stmt"

/** Extracts the concrete source span from a parser-produced node.
  *
  * Parser lowering only calls this for nodes that came directly from source, not synthetic
  * wrappers.
  */
private def locSpan(node: FromSource): SrcSpan =
  node.source match
    case SourceOrigin.Loc(s) => s
    case SourceOrigin.Synth =>
      throw IllegalStateException("Parser expected source-located node")

/** Lowers semicolon-separated expression statements into nested scoped lambdas.
  *
  * Surface syntax:
  * {{{
  * println "start";
  * work;
  * finish
  * }}}
  *
  * Conceptually becomes a right-associated chain where each earlier statement is evaluated before
  * the rest expression.
  */
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

/** Lowers a local binding into immediate lambda application.
  *
  * Surface syntax:
  * {{{
  * let x = value;
  * rest
  * }}}
  *
  * Conceptual shape:
  * {{{
  * ({ x -> rest } value)
  * }}}
  */
private def mkScopedBinding(
  bindingName:    String,
  bindingNameLoc: SrcSpan,
  bindingTypeAsc: Option[Type],
  bindingExpr:    Expr,
  restExpr:       Expr,
  fullSpan:       SrcSpan
): App =
  val param = FnParam(
    SourceOrigin.Loc(bindingNameLoc),
    Name(bindingNameLoc, bindingName),
    typeAsc = bindingTypeAsc
  )
  val lambda = Lambda(
    span     = fullSpan,
    params   = List(param),
    body     = restExpr,
    captures = Nil
  )
  App(
    span = fullSpan,
    fn   = lambda,
    arg  = bindingExpr
  )

/** Builds the callable type ascription attached to a local `fn` binding when all param types and
  * the return type are available.
  *
  * Example:
  * {{{
  * fn inc(x: Int): Int = x + 1;
  * }}}
  * yields a local binding ascription equivalent to `Int -> Int`.
  */
private def mkFnBindingType(
  source:     SourceOrigin,
  params:     List[FnParam],
  returnType: Option[Type]
): Option[Type] =
  returnType.flatMap { retType =>
    params
      .traverse(_.typeAsc)
      .map(paramTypes => TypeFn(source, canonicalCallableParamTypes(source, paramTypes), retType))
  }

/** Canonicalizes callable parameter lists so nullary callables still use `Unit -> R`. */
private def canonicalCallableParamTypes(
  source:     SourceOrigin,
  paramTypes: List[Type]
): NonEmptyList[Type] =
  NonEmptyList.fromList(paramTypes).getOrElse(NonEmptyList.one(TypeRef(source, "Unit")))

/** Parses a whitespace-trimmed sequence of terms into one [[Expr]] node.
  *
  * Example:
  * {{{
  * add 1 2
  * }}}
  * is parsed as one expression containing multiple terms; later phases decide application and
  * operator structure.
  */
private def exprFromTermsP(info: SourceInfo, termParser: => P[Term])(using P[Any]): P[Expr] =
  P(spP(info) ~ termParser.rep(1) ~ spNoWsP(info) ~ spP(info))
    .map { case (start, ts, end, _) =>
      val termsList = ts.toList
      val (typeSpec, typeAsc) =
        if termsList.size == 1 then (termsList.head.typeSpec, termsList.head.typeAsc)
        else (None, None)
      Expr(span(start, end), termsList, typeAsc, typeSpec)
    }

/** Parses a full expression, including semicolon-separated statement sequencing.
  *
  * Example:
  * {{{
  * log "start";
  * compute x
  * }}}
  */
private[parser] def exprP(info: SourceInfo)(using P[Any]): P[Expr] =
  P(
    exprNoSeqP(info) ~
      (semiKw ~ &(!"/*" ~ exprNoSeqP(info)) ~ exprNoSeqP(info)).rep
  ).map { case (head, tail) =>
    if tail.isEmpty then head else mkStatementChain(head, tail.toList)
  }

/** Parses an expression frame that does not permit semicolon sequencing. */
private[parser] def exprNoSeqP(info: SourceInfo)(using P[Any]): P[Expr] =
  exprFromTermsP(info, termP(info))

/** Parses an expression inside declaration bodies, where member-only variants are allowed. */
private[parser] def exprMemberP(info: SourceInfo)(using P[Any]): P[Expr] =
  exprNoSeqMemberP(info)

/** Parses a non-sequenced expression inside declaration bodies. */
private[parser] def exprNoSeqMemberP(info: SourceInfo)(using P[Any]): P[Expr] =
  exprFromTermsP(info, termMemberP(info))

/** Parses an expression followed by its mandatory `;` terminator.
  *
  * This is used by constructs whose body grammar owns its terminating semicolon.
  */
private[parser] def terminatedExprP(info: SourceInfo)(using P[Any]): P[(Expr, SrcPoint)] =
  P(exprP(info) ~ semiKw ~ spNoWsP(info))

/** Parses a nested member expression followed by its mandatory `;` terminator. */
private[parser] def terminatedNestedMemberExprP(info: SourceInfo)(using
  P[Any]
): P[(Expr, SrcPoint)] =
  P(exprP(info) ~ semiKw ~ spNoWsP(info))

/** Parses a term and then an optional trailing type ascription.
  *
  * Example:
  * {{{
  * value: Int
  * (fnValue): Int -> Int
  * }}}
  */
private def withTypeAsc(info: SourceInfo, termParser: => P[Term])(using P[Any]): P[Term] =
  P(termParser ~ typeAscP(info)).map {
    case (term, Some(asc)) => term.withTypeAsc(asc)
    case (term, None) => term
  }

/** Parses any expression-position term.
  *
  * Examples include:
  * {{{
  * let x = 1; x
  * if cond then yes; else no;
  * { x -> x + 1 }
  * person.name
  * }}}
  */
private[parser] def termP(info: SourceInfo)(using P[Any]): P[Term] =
  withTypeAsc(
    info,
    letExprP(info) |
      innerFnExprP(info) |
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

/** Parses any term variant that is valid inside member bodies. */
private[parser] def termMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  withTypeAsc(
    info,
    letExprP(info) |
      innerFnExprP(info) |
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

/** Parses a selection chain such as `person.name` or `config.db.port`. */
private[parser] def selectionTermP(info: SourceInfo)(using P[Any]): P[Term] =
  selectionP(info, selectionBaseP(info))

/** Parses a selection chain inside member bodies. */
private[parser] def selectionTermMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  selectionP(info, selectionBaseMemberP(info))

/** Parses the set of terms that may appear to the left of a `.` selection in expressions. */
private def selectionBaseP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    groupTermP(info) |
      tupleP(info) |
      typeRefTermP(info) |
      refP(info)
  )

/** Parses the set of terms that may appear to the left of a `.` selection in member bodies. */
private def selectionBaseMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    groupTermMemberP(info) |
      tupleMemberP(info) |
      typeRefTermP(info) |
      refP(info)
  )

/** Parses one `.field` step in a selection chain. */
private def selectionFieldP(info: SourceInfo)(using P[Any]): P[(String, SrcSpan)] =
  import fastparse.NoWhitespace.*
  P("." ~ spP(info) ~ bindingIdP ~ spNoWsP(info)).map { case (start, name, end) =>
    name -> span(start, end)
  }

/** Parses and lowers a dotted selection chain to nested qualified [[Ref]] nodes.
  *
  * Example:
  * {{{
  * person.address.city
  * }}}
  */
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

/** Parses tuple syntax.
  *
  * Example:
  * {{{
  * (x, y, z)
  * }}}
  */
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

/** Parses tuple syntax inside declaration bodies. */
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

/** Parses a full `if / elif* / else` expression.
  *
  * Example:
  * {{{
  * if cond then yes;
  * elif other then maybe;
  * else no;
  * }}}
  */
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

/** Parses an `if` expression with no explicit `else`, synthesizing `Unit` as the false branch.
  *
  * Example:
  * {{{
  * if ready then emit value;
  * }}}
  */
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

/** Parses a local `let` binding and lowers it to immediate lambda application.
  *
  * Surface syntax:
  * {{{
  * let x = compute;
  * use x
  * }}}
  */
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
        mkScopedBinding(
          bindingName    = name,
          bindingNameLoc = span(idStart, idEnd),
          bindingTypeAsc = typeAsc,
          bindingExpr    = bindingExpr,
          restExpr       = restExpr,
          fullSpan       = span(start, end)
        )
  }

/** Parses a local `fn` binding and lowers it to a scoped lambda binding.
  *
  * Surface syntax:
  * {{{
  * fn helper(x: Int): Int = x + 1;
  * helper 41
  * }}}
  */
private[parser] def innerFnExprP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ fnKw ~ "~".!.? ~ spP(info) ~ bindingIdOrError ~ spNoWsP(info) ~ spP(info) ~
      "(" ~ fnParamListP(info) ~ ")" ~ typeAscP(info) ~ defAsKw ~ terminatedExprP(info) ~
      semiKw ~ exprP(info) ~ spNoWsP(info) ~ spP(info)
  ).map {
    case (
          start,
          tilde,
          nameStart,
          idOrError,
          nameEnd,
          lambdaStart,
          params,
          typeAsc,
          (bodyExpr, bodyEnd),
          restExpr,
          end,
          _
        ) =>
      idOrError match
        case Left(invalidId) =>
          TermError(
            span       = span(start, end),
            message    = s"Invalid identifier '$invalidId'",
            failedCode = Some(invalidId)
          )
        case Right(name) =>
          val lambdaSpan = span(lambdaStart, bodyEnd)
          val bindingTypeAsc =
            mkFnBindingType(SourceOrigin.Loc(lambdaSpan), params, typeAsc)
          val lambda = Lambda(
            span     = lambdaSpan,
            params   = params,
            body     = bodyExpr,
            captures = Nil,
            typeSpec = None,
            typeAsc  = None,
            meta     = None,
            isMove   = tilde.isDefined
          )
          val bindingExpr = Expr(lambdaSpan, List(lambda), None, None)
          mkScopedBinding(
            bindingName    = name,
            bindingNameLoc = span(nameStart, nameEnd),
            bindingTypeAsc = bindingTypeAsc,
            bindingExpr    = bindingExpr,
            restExpr       = restExpr,
            fullSpan       = span(start, end)
          )
  }

/** Parses the body of a lambda literal.
  *
  * Both forms below are valid:
  * {{{
  * { x -> x + 1 }
  * { x -> x + 1; }
  * }}}
  */
private def lambdaBodyExprP(info: SourceInfo)(using P[Any]): P[Expr] =
  P(exprP(info) ~ (semiKw | &("}")) ~ spNoWsP(info)).map { case (expr, _) => expr }

/** Parses a lambda literal with one or more parameters.
  *
  * Example:
  * {{{
  * { x, y -> x + y }
  * }}}
  */
private def lambdaWithParamsP(info: SourceInfo)(using P[Any]): P[(List[FnParam], Expr)] =
  P(fnParamListP(info).filter(_.nonEmpty) ~ arrowKw ~/ lambdaBodyExprP(info))
    .map { case (params, body) => params -> body }

/** Parses a parameterless lambda body.
  *
  * Example:
  * {{{
  * { println "hi" }
  * }}}
  */
private def lambdaNoParamsP(info: SourceInfo)(using P[Any]): P[(List[FnParam], Expr)] =
  P(lambdaBodyExprP(info)).map(Nil -> _)

/** Parses a lambda literal.
  *
  * Examples:
  * {{{
  * { x -> x + 1 }
  * ~{ file -> close file }
  * { println "hello" }
  * }}}
  */
private[parser] def lambdaLitP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    spP(info) ~ "~".!.? ~ "{" ~
      (lambdaWithParamsP(info) | lambdaNoParamsP(info)) ~
      "}" ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, tilde, (params, body), end, _) =>
    Lambda(
      span(start, end),
      params,
      body,
      captures = Nil,
      typeSpec = None,
      typeAsc  = None,
      meta     = None,
      isMove   = tilde.isDefined
    )
  }

/** Parses a parenthesized expression group.
  *
  * Example:
  * {{{
  * (a + b)
  * }}}
  */
private[parser] def groupTermP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ "(" ~ exprP(info) ~ ")" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, expr, end, _) =>
      TermGroup(span(start, end), expr)
    }

/** Parses a full `if / elif* / else` expression inside member bodies. */
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

/** Parses a member-body `if` expression with an implicit `else ()`. */
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

/** Parses a parenthesized expression group inside member bodies. */
private[parser] def groupTermMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ "(" ~ exprMemberP(info) ~ ")" ~ spNoWsP(info) ~ spP(info))
    .map { case (start, expr, end, _) =>
      TermGroup(span(start, end), expr)
    }

/** Parses a lowercase value reference.
  *
  * Examples:
  * {{{
  * value
  * map
  * acc_1
  * }}}
  */
private[parser] def refP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ bindingIdP ~ typeAscP(info) ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, typeAsc, end, _) =>
      Ref(SourceOrigin.Loc(span(start, end)), id, typeAsc = typeAsc)
    }

/** Parses an uppercase identifier in term position.
  *
  * This covers constructor-like and type-like references that are represented as [[Ref]] terms in
  * the AST.
  *
  * Example:
  * {{{
  * None
  * Person
  * }}}
  */
private[parser] def typeRefTermP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ typeIdP ~ typeAscP(info) ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, typeAsc, end, _) =>
      Ref(SourceOrigin.Loc(span(start, end)), id, typeAsc = typeAsc)
    }

/** Parses a symbolic operator reference used in expression position.
  *
  * Example:
  * {{{
  * +
  * }}}
  */
private[parser] def opRefP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ operatorIdP ~ spNoWsP(info) ~ spP(info))
    .map { case (start, id, end, _) =>
      Ref(SourceOrigin.Loc(span(start, end)), id)
    }

/** Parses the `_` placeholder term. */
private[parser] def phP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ placeholderKw ~ spNoWsP(info) ~ spP(info))
    .map { case (start, end, _) =>
      Placeholder(span(start, end), None)
    }

/** Parses the `???` typed-hole term. */
private[parser] def holeP(info: SourceInfo)(using P[Any]): P[Term] =
  P(spP(info) ~ holeKw ~ spNoWsP(info) ~ spP(info))
    .map { case (start, end, _) =>
      Hole(span(start, end))
    }

/** Parses an `@native[...]` implementation marker in expression position.
  *
  * Examples:
  * {{{
  * @native[tpl="%0 = add i64 %1, %2"]
  * @native[name="malloc",mem=alloc]
  * }}}
  */
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
