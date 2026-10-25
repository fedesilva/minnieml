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
    case SourceOrigin.Synth | SourceOrigin.Derived(_) =>
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
    val application = mkStatementApplication(stmt, rest)
    Expr(application.source, List(application))
  }

/** Evaluates one statement before its continuation without introducing a source-visible name. */
private def mkStatementApplication(statement: Expr, rest: Expr): App =
  val statementLoc = locSpan(statement)
  val fullSpan     = span(statementLoc.start, locSpan(rest).end)
  val unitType     = TypeRef(statementLoc, "Unit")
  val param  = FnParam(SourceOrigin.Synth, Name.synth(statementParamName), typeAsc = Some(unitType))
  val lambda = Lambda(fullSpan, List(param), rest, captures = Nil)
  App(fullSpan, lambda, statement)

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
private def exprFromTermsP(info: SourceInfo, termParser: => P[ParsedTerm])(using P[Any]): P[Expr] =
  bindingFrameP(info, termParser, required = false)

/** Parses a full expression, including semicolon-separated statement sequencing.
  *
  * Example:
  * {{{
  * log "start";
  * compute x
  * }}}
  */
private[parser] def exprP(info: SourceInfo)(using P[Any]): P[Expr] =
  sequencedExprP(info, exprNoSeqP(info))

/** The owning binding or lambda introducer permits a missing first expression. */
private[parser] def requiredExprP(info: SourceInfo)(using P[Any]): P[Expr] =
  sequencedExprP(info, bindingFrameP(info, termP(info), required = true))

private def sequencedExprP(info: SourceInfo, first: => P[Expr])(using P[Any]): P[Expr] =
  P(
    first ~
      (semiKw ~ &(NoCut(!"/*" ~ exprNoSeqP(info))) ~ exprNoSeqP(info)).rep
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
  P(exprP(info) ~ tokenP(info)(semiKw) ~ pointP(info))
    .map { case (expr, terminator, _) => (expr, terminator.location.end) }

/** Recovers an owned body and its semicolon without consuming the enclosing terminator. */
private[parser] def terminatedBindingExprP(info: SourceInfo)(using P[Any]): P[(Expr, SrcPoint)] =
  P(requiredExprP(info) ~ P(requiredBindingTokenP(info, ";") ~~ pointP(info)))
    .map { case (body, (error, end)) => (appendBindingErrors(body, error.toList), end) }

/** Parses a term and then an optional trailing type ascription.
  *
  * Example:
  * {{{
  * value: Int
  * (fnValue): Int -> Int
  * }}}
  */
private def withTypeAsc(info: SourceInfo, termParser: => P[Term])(using P[Any]): P[ParsedTerm] =
  P(pointP(info) ~ termParser ~ typeAscP(info)).map { case (start, term, annotation) =>
    val end = (term.typeAsc.toList ++ annotation.toList).foldLeft(sourceEnd(term, start)) {
      (end, writtenType) =>
        val annotationEnd = sourceEnd(writtenType, end)
        if annotationEnd.index > end.index then annotationEnd else end
    }
    ParsedTerm(annotation.fold(term)(term.withTypeAsc), end)
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
private[parser] def termP(info: SourceInfo)(using P[Any]): P[ParsedTerm] =
  withTypeAsc(
    info,
    letExprP(info) |
      innerFnExprP(info) |
      litBoolP(info) |
      nativeImplP(info) |
      ifExprP(info) |
      litUnitP(info) |
      holeP(info) |
      litStringP(info) |
      selectionTermP(info) |
      numericLitP(info) |
      opRefP(info) |
      NoCut(groupTermP(info)) |
      tupleP(info) |
      lambdaLitP(info) |
      typeRefTermP(info) |
      refP(info) |
      phP(info)
  )

/** Parses any term variant that is valid inside member bodies. */
private[parser] def termMemberP(info: SourceInfo)(using P[Any]): P[ParsedTerm] =
  withTypeAsc(
    info,
    letExprP(info) |
      innerFnExprP(info) |
      litBoolP(info) |
      nativeImplP(info) |
      ifExprMemberP(info) |
      litUnitP(info) |
      holeP(info) |
      litStringP(info) |
      selectionTermMemberP(info) |
      numericLitP(info) |
      opRefP(info) |
      NoCut(groupTermMemberP(info)) |
      tupleMemberP(info) |
      lambdaLitP(info) |
      typeRefTermP(info) |
      refP(info) |
      phP(info)
  )

/** Parses a selection chain such as `person.name` or `config.db.port`. */
private[parser] def selectionTermP(info: SourceInfo)(using P[Any]): P[Term] =
  NoCut(selectionP(info, selectionBaseP(info)))

/** Parses a selection chain inside member bodies. */
private[parser] def selectionTermMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  NoCut(selectionP(info, selectionBaseMemberP(info)))

/** Parses the set of terms that may appear to the left of a `.` selection in expressions. */
private def selectionBaseP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    NoCut(groupTermP(info)) |
      tupleP(info) |
      typeRefTermP(info) |
      refP(info)
  )

/** Parses the set of terms that may appear to the left of a `.` selection in member bodies. */
private def selectionBaseMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    NoCut(groupTermMemberP(info)) |
      tupleMemberP(info) |
      typeRefTermP(info) |
      refP(info)
  )

/** Parses one `.field` step in a selection chain. */
private def selectionFieldP(info: SourceInfo)(using P[Any]): P[(String, SrcSpan)] =
  import fastparse.NoWhitespace.*
  P("." ~ tokenP(info)(bindingIdP)).map(token => token.value -> token.location)

/** Parses and lowers a dotted selection chain to nested qualified [[Ref]] nodes.
  *
  * Example:
  * {{{
  * person.address.city
  * }}}
  */
private def selectionP(info: SourceInfo, baseP: => P[Term])(using P[Any]): P[Term] =
  P(
    pointP(info) ~ baseP ~ selectionFieldP(info).rep(1) ~ typeAscP(info) ~ pointP(info)
  ).map { case (_, base, fields, typeAsc, _) =>
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
  P(pointP(info) ~ "(" ~ exprP(info).rep(sep = ",") ~ tokenP(info)(")") ~ pointP(info))
    .map { case (start, exprs, closer, _) =>
      val end = closer.location.end
      NonEmptyList
        .fromList(exprs.toList)
        .fold(
          ParseTermError(
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
  P(pointP(info) ~ "(" ~ exprMemberP(info).rep(sep = ",") ~ tokenP(info)(")") ~ pointP(info))
    .map { case (start, exprs, closer, _) =>
      val end = closer.location.end
      NonEmptyList
        .fromList(exprs.toList)
        .fold(
          ParseTermError(
            span       = span(start, end),
            message    = "Tuple must have at least one element",
            failedCode = info.slice(start.index, end.index).some
          )
        ) { elements =>
          Tuple(span(start, end), elements)
        }

    }

/** One conditional clause retains its condition and true branch independently. */
private final case class ConditionalClause(condition: Expr, body: Expr, end: SrcPoint)

/** Parses an expression-position conditional, whose condition permits statement sequencing. */
private[parser] def ifExprP(info: SourceInfo)(using P[Any]): P[Term] =
  conditionalP(info, requiredExprP(info))

/** Shares clause ownership and lowering while keeping each caller's condition grammar explicit. */
private def conditionalP(info: SourceInfo, conditionParser: => P[Expr])(using P[Any]): P[Term] =
  P(
    pointP(info) ~ ifKw ~/ conditionalClauseP(info, conditionParser) ~
      (elifKw ~/ conditionalClauseP(info, conditionParser)).rep ~
      (elseKw ~/ terminatedBindingExprP(info)).? ~ pointP(info)
  ).map { case (start, initial, elifs, explicitElse, _) =>
    val end       = explicitElse.map(_._2).getOrElse(elifs.lastOption.getOrElse(initial).end)
    val finalSpan = span(start, end)
    val unitType  = Option.when(explicitElse.isEmpty)(TypeRef(finalSpan, "Unit"))
    val finalElse = explicitElse.map(_._1).getOrElse {
      val unitSpan = span(end, end)
      Expr(unitSpan, List(LiteralUnit(unitSpan)))
    }
    val elseExpr = elifs.toList.foldRight(finalElse) { (clause, rest) =>
      val clauseSpan = span(locSpan(clause.condition).start, end)
      Expr(
        clauseSpan,
        List(Cond(clauseSpan, clause.condition, clause.body, rest, typeSpec = unitType))
      )
    }
    Cond(
      finalSpan,
      initial.condition,
      initial.body,
      elseExpr,
      typeSpec = unitType,
      typeAsc  = unitType
    )
  }

/** A missing `then` leaves parsed terms in the condition and owns at most one semicolon. */
private def conditionalClauseP(info: SourceInfo, conditionParser: => P[Expr])(using
  P[Any]
): P[ConditionalClause] =
  def bodyP: P[(Expr, SrcPoint)] =
    P(thenKw ~ terminatedBindingExprP(info)) |
      P(pointP(info) ~ tokenP(info)(semiKw).?).map { case (point, terminator) =>
        val error = bindingError(info, point, point, "Expected 'then'")
        (Expr(error.source, List(error)), terminator.fold(point)(_.location.end))
      }

  P(conditionParser ~ bodyP).map { case (condition, (body, end)) =>
    ConditionalClause(condition, body, end)
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
    pointP(info) ~ letKw ~/ parsedBindingNameP(info) ~ pointP(info) ~
      typeAscP(info, TypeContext.BindingHeader) ~ requiredBindingTokenP(info, "=") ~
      bindingFrameP(info, termP(info), required = true) ~ requiredBindingTokenP(info, ";") ~
      requiredExprP(info) ~ pointP(info)
  ).map { case (start, parsedName, _, typeAsc, equalsError, initializer, endError, rest, _) =>
    val bindingExpr = appendBindingErrors(
      initializer,
      parsedName.error.toList ++ equalsError.toList ++ endError.toList
    )
    parsedName.value match
      case None =>
        val statement =
          bindingExpr.copy(
            source  = SourceOrigin.Loc(span(start, locSpan(bindingExpr).end)),
            typeAsc = typeAsc
          )
        mkStatementApplication(statement, rest)
      case Some(name) =>
        mkScopedBinding(
          bindingName    = name,
          bindingNameLoc = parsedName.location,
          bindingTypeAsc = typeAsc,
          bindingExpr    = bindingExpr,
          restExpr       = rest,
          fullSpan       = span(start, sourceEnd(rest, start))
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
    pointP(info) ~ fnKw ~/ "~".!.? ~ parsedBindingNameP(info) ~ pointP(info) ~
      parsedParametersP(info) ~ typeAscP(info, TypeContext.BindingHeader) ~
      requiredBindingTokenP(info, "=") ~ terminatedBindingExprP(info) ~
      requiredBindingTokenP(info, ";") ~ requiredExprP(info) ~ pointP(info)
  ).map {
    case (
          start,
          tilde,
          parsedName,
          lambdaStart,
          parameters,
          typeAsc,
          equalsError,
          (bodyExpr, bodyEnd),
          endError,
          restExpr,
          _
        ) =>
      val lambdaSpan = span(lambdaStart, bodyEnd)
      val body = appendBindingErrors(
        bodyExpr,
        parsedName.error.toList ++ parameters.error.toList ++ equalsError.toList ++ endError.toList
      )
      val malformedResult = typeAsc.filter(TypeUtils.containsInvalidType(_, ResolvablesIndex()))
      val bindingExpr =
        if parameters.complete || parameters.values.nonEmpty then
          val lambda = Lambda(
            span     = lambdaSpan,
            params   = parameters.values,
            body     = body,
            captures = Nil,
            typeSpec = None,
            typeAsc  = malformedResult,
            meta     = None,
            isMove   = tilde.isDefined
          )
          Expr(lambdaSpan, List(lambda), None, None)
        else body.copy(typeAsc = malformedResult)
      val bindingTypeAsc =
        if parameters.complete then
          mkFnBindingType(SourceOrigin.Loc(lambdaSpan), parameters.values, typeAsc)
        else none

      parsedName.value match
        case None =>
          val statement =
            bindingExpr.copy(source = SourceOrigin.Loc(span(start, locSpan(bindingExpr).end)))
          mkStatementApplication(statement, restExpr)
        case Some(name) =>
          mkScopedBinding(
            bindingName    = name,
            bindingNameLoc = parsedName.location,
            bindingTypeAsc = bindingTypeAsc,
            bindingExpr    = bindingExpr,
            restExpr       = restExpr,
            fullSpan       = span(start, sourceEnd(restExpr, start))
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
  P(requiredExprP(info) ~ (semiKw | &("}")) ~ pointP(info)).map { case (expr, _) => expr }

/** Parses a lambda literal with one or more parameters.
  *
  * Example:
  * {{{
  * { x, y -> x + y }
  * }}}
  */
private def lambdaWithParamsP(info: SourceInfo)(using P[Any]): P[(List[FnParam], Expr)] =
  P(
    fnParamListP(info, TypeContext.LambdaParameter).filter(_.nonEmpty) ~ arrowKw ~/
      lambdaBodyExprP(info)
  )
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
    pointP(info) ~ "~".!.? ~ "{" ~
      (lambdaWithParamsP(info) | lambdaNoParamsP(info)) ~
      tokenP(info)("}") ~ pointP(info)
  ).map { case (start, tilde, (params, body), closer, _) =>
    Lambda(
      span(start, closer.location.end),
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
  P(pointP(info) ~ "(" ~ exprP(info) ~ tokenP(info)(")") ~ pointP(info))
    .map { case (start, expr, closer, _) =>
      TermGroup(span(start, closer.location.end), expr)
    }

/** Parses a member-body conditional, whose condition excludes statement sequencing. */
private[parser] def ifExprMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  conditionalP(info, bindingFrameP(info, termMemberP(info), required = true))

/** Parses a parenthesized expression group inside member bodies. */
private[parser] def groupTermMemberP(info: SourceInfo)(using P[Any]): P[Term] =
  P(pointP(info) ~ "(" ~ exprMemberP(info) ~ tokenP(info)(")") ~ pointP(info))
    .map { case (start, expr, closer, _) =>
      TermGroup(span(start, closer.location.end), expr)
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
  P(tokenP(info)(bindingIdP) ~ typeAscP(info) ~ pointP(info))
    .map { case (name, typeAsc, _) =>
      Ref(SourceOrigin.Loc(name.location), name.value, typeAsc = typeAsc)
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
  P(tokenP(info)(typeIdP) ~ typeAscP(info) ~ pointP(info))
    .map { case (name, typeAsc, _) =>
      Ref(SourceOrigin.Loc(name.location), name.value, typeAsc = typeAsc)
    }

/** Parses a symbolic operator reference used in expression position.
  *
  * Example:
  * {{{
  * +
  * }}}
  */
private[parser] def opRefP(info: SourceInfo)(using P[Any]): P[Term] =
  P(tokenP(info)(operatorIdP) ~ pointP(info))
    .map { case (name, _) =>
      Ref(SourceOrigin.Loc(name.location), name.value)
    }

/** Parses the `_` placeholder term. */
private[parser] def phP(info: SourceInfo)(using P[Any]): P[Term] =
  P(tokenP(info)(placeholderKw) ~ pointP(info))
    .map { case (token, _) =>
      Placeholder(token.location, None)
    }

/** Parses the `???` typed-hole term. */
private[parser] def holeP(info: SourceInfo)(using P[Any]): P[Term] =
  P(tokenP(info)(holeKw) ~ pointP(info))
    .map { case (token, _) =>
      Hole(token.location)
    }

/** Parses foreign bindings and inline LLVM templates in expression position.
  *
  * Examples:
  * {{{
  * @metal:tpl["add %type %operand1, %operand2"]
  * @metal:ffi[name="malloc",mem=alloc]
  * }}}
  */
private[parser] def nativeImplP(info: SourceInfo)(using P[Any]): P[NativeImpl] =

  enum Attr:
    case MemAttr(eff: MemEffect)
    case NameAttr(name: String)

  def memEffectP: P[MemEffect] =
    P("alloc").map(_ => MemEffect.Alloc) | P("static").map(_ => MemEffect.Static)

  def quotedStringP: P[String] = P("\"" ~ CharsWhile(_ != '"', 0).! ~ "\"")
  def memAttrP:      P[Attr]   = P("mem" ~ "=" ~ memEffectP).map(Attr.MemAttr(_))
  def nameAttrP:     P[Attr]   = P("name" ~ "=" ~ quotedStringP).map(Attr.NameAttr(_))
  def attrP:         P[Attr]   = P(memAttrP | nameAttrP)

  final case class ParsedImplementation(
    template:   Option[String],
    attributes: Seq[Attr],
    end:        SrcPoint
  )

  def attributesP: P[(Seq[Attr], SrcPoint)] =
    P("[" ~ attrP.rep(sep = ",", min = 1) ~ tokenP(info)("]"))
      .map { case (attrs, closer) => (attrs, closer.location.end) }

  def implementationP: P[ParsedImplementation] =
    P(
      (tokenP(info)(ffiKw) ~ attributesP.?).map { case (keyword, attributes) =>
        val (attrs, end) = attributes.getOrElse((Seq.empty, keyword.location.end))
        ParsedImplementation(none, attrs, end)
      } |
        (tplKw ~ "[" ~ quotedStringP ~ ("," ~ attrP).rep ~ tokenP(info)("]")).map {
          case (tpl, attrs, closer) => ParsedImplementation(tpl.some, attrs, closer.location.end)
        }
    )

  P(pointP(info) ~ implementationP ~ pointP(info))
    .map { case (start, implementation, _) =>
      val mem  = implementation.attributes.collectFirst { case Attr.MemAttr(e) => e }
      val name = implementation.attributes.collectFirst { case Attr.NameAttr(n) => n }
      NativeImpl(
        span(start, implementation.end),
        nativeTpl    = implementation.template,
        memEffect    = mem,
        nativeSymbol = name
      )
    }
