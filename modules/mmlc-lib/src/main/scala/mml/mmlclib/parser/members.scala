package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

/** Parses any top-level module member.
  *
  * This is the entry point for declarations such as:
  * {{{
  * let answer = 42;
  * fn inc(x: Int): Int = x + 1; ;
  * op + (a: Int, b: Int): Int 60 left = a; ;
  * struct Person { name: String };
  * type UserId = Int;
  * }}}
  */
private[parser] def membersP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    misplacedLinkP(info) |
      protocolP(info) |
      instanceP(info) |
      binOpDefP(info) |
      unaryOpP(info) |
      letBindingP(info) |
      fnDefP(info) |
      structDefP(info) |
      typeAliasP(info) |
      nativeTypeDefP(info) |
      failedMemberP(info)
  )

/** Parses a top-level value binding.
  *
  * Example:
  * {{{
  * let answer: Int = 42;
  * }}}
  */
private[parser] def letBindingP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    pointP(info) ~ docCommentP(info) ~ visibilityP.? ~ letKw ~/ parsedBindingNameP(info) ~
      typeAscP(info, TypeContext.BindingHeader) ~ requiredBindingTokenP(info, "=") ~
      bindingFrameP(info, termMemberP(info), required = true) ~ bindingMemberEndP(info) ~
      pointP(info)
  ).map { case (start, doc, vis, parsedName, typeAsc, equalsError, body, (endError, tokenEnd), _) =>
    parsedName.value match
      case None => failedBindingMember(info, start, tokenEnd, parsedName)
      case Some(name) =>
        val expr = appendBindingErrors(body, equalsError.toList ++ endError.toList)
        Bnd(
          visibility = vis.getOrElse(Visibility.Protected),
          source     = SourceOrigin.Loc(span(parsedName.location.start, tokenEnd)),
          nameNode   = Name(parsedName.location, name),
          value      = expr,
          typeSpec   = expr.typeSpec,
          typeAsc    = typeAsc,
          docComment = doc
        )
  }

/** Failed declarations end at their terminator, before inter-member whitespace and comments. */
private def bindingMemberEndP(info: SourceInfo)(using
  P[Any]
): P[(Option[ParseTermError], SrcPoint)] =
  P(requiredBindingTokenP(info, ";") ~~ pointP(info))

/** Nameless declarations retain their entire source fragment without inventing a binding. */
private def failedBindingMember(
  info:         SourceInfo,
  start:        SrcPoint,
  end:          SrcPoint,
  name:         ParsedBindingName,
  functionName: Boolean = false
): Member =
  val invalidId = name.error.flatMap(_.failedCode).getOrElse("")
  val nameRule = "must start with a lowercase letter (a-z) " +
    "followed by letters, digits, or underscores"
  val message =
    if invalidId.isEmpty then "Expected binding name"
    else if functionName then s"Invalid function name '$invalidId'. Function names $nameRule"
    else s"Invalid identifier '$invalidId'. Identifiers $nameRule"
  ParsingIdError(
    span(start, end),
    message,
    info.slice(start.index, end.index).some,
    invalidId
  )

/** Parses one function parameter.
  *
  * Examples:
  * {{{
  * value: Int
  * ~buffer: RawPtr
  * }}}
  */
private[parser] def fnParamP(info: SourceInfo, context: TypeContext = TypeContext.Parameter)(using
  P[Any]
): P[FnParam] =
  P(
    pointP(info) ~ docCommentP(info) ~ "~".!.? ~ tokenP(info)(bindingIdP) ~
      typeAscP(info, context) ~ pointP(info)
  ).map { case (start, doc, tilde, name, t, _) =>
    val end = t.fold(name.location.end)(sourceEnd(_, name.location.end))
    FnParam(
      source     = SourceOrigin.Loc(span(start, end)),
      nameNode   = Name(name.location, name.value),
      typeAsc    = t,
      docComment = doc,
      consuming  = tilde.isDefined
    )
  }

/** Parses the comma-separated parameter list inside `(...)`. */
private[parser] def fnParamListP(info: SourceInfo, context: TypeContext = TypeContext.Parameter)(
  using P[Any]
): P[List[FnParam]] =
  P(fnParamP(info, context).rep(sep = ",")).map(_.toList)

/** Parses a top-level function declaration and lowers it to a [[Bnd]] whose value is a [[Lambda]].
  *
  * Example:
  * {{{
  * fn inc(x: Int): Int =
  *   x + 1;
  * ;
  * }}}
  */
private[parser] def fnDefP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    pointP(info) ~ docCommentP(info) ~ visibilityP.? ~ inlineKw.!.? ~ fnKw ~/
      parsedBindingNameP(info) ~ pointP(info) ~ parsedParametersP(info) ~
      typeAscP(info, TypeContext.BindingHeader) ~ requiredBindingTokenP(info, "=") ~
      terminatedBindingExprP(info) ~ bindingMemberEndP(info) ~
      pointP(info)
  ).map {
    case (
          start,
          doc,
          vis,
          inl,
          parsedName,
          lambdaStart,
          parameters,
          typeAsc,
          equalsError,
          (bodyExpr, bodyEnd),
          (endError, tokenEnd),
          _
        ) =>
      parsedName.value match
        case None => failedBindingMember(info, start, tokenEnd, parsedName, functionName = true)
        case Some(fnName) =>
          val lambdaSpan = span(lambdaStart, bodyEnd)
          val body = appendBindingErrors(
            bodyExpr,
            parameters.error.toList ++ equalsError.toList ++ endError.toList
          )
          val arity = CallableArity.fromParameterCount(parameters.values.size)
          val meta = Option.when(parameters.complete)(
            BindingMeta(
              origin        = BindingOrigin.Function,
              arity         = arity,
              precedence    = Precedence.Function,
              associativity = None,
              originalName  = fnName,
              mangledName   = fnName,
              inlineHint    = inl.isDefined
            )
          )
          val value =
            if parameters.complete || parameters.values.nonEmpty then
              val lambda = Lambda(
                span     = lambdaSpan,
                params   = parameters.values,
                body     = body,
                captures = Nil,
                typeSpec = if parameters.complete then body.typeSpec else none,
                typeAsc  = typeAsc
              )
              Expr(lambdaSpan, List(lambda))
            else
              body.copy(typeAsc =
                typeAsc.filter(
                  TypeUtils.containsInvalidType(_, ResolvablesIndex())
                )
              )
          Bnd(
            visibility = vis.getOrElse(Visibility.Protected),
            source     = SourceOrigin.Loc(parsedName.location),
            nameNode   = Name(parsedName.location, fnName),
            value      = value,
            typeSpec   = if parameters.complete then body.typeSpec else none,
            typeAsc    = if parameters.complete then typeAsc else none,
            docComment = doc,
            meta       = meta
          )
  }

/** Parses the associativity marker that follows an operator precedence.
  *
  * Example:
  * {{{
  * left
  * right
  * }}}
  */
private[parser] def assocP[$: P]: P[Associativity] =
  P("left").map(_ => Associativity.Left) | P("right").map(_ => Associativity.Right)

/** Parses an operator precedence level such as `50` or `120`. */
private[parser] def precedenceP[$: P]: P[Int] =
  P(CharIn("0-9").rep(1).!).map(_.toInt)

/** Parses a binary operator declaration.
  *
  * Example:
  * {{{
  * op + (a: Int, b: Int): Int 60 left =
  *   a;
  * ;
  * }}}
  */
private[parser] def binOpDefP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    docCommentP(info)
      ~ visibilityP.?
      ~ inlineKw.!.?
      ~ opKw
      ~ tokenP(info)(operatorIdOrError)
      ~ pointP(info)
      ~ "("
      ~ fnParamP(info)
      ~ ","
      ~ fnParamP(info)
      ~ ")"
      ~ typeAscP(info, TypeContext.Operator)
      ~ precedenceP.?
      ~ assocP.?
      ~ defAsKw
      ~ terminatedExprP(info)
      ~ tokenP(info)(semiKw)
      ~ pointP(info)
  ).map {
    case (
          doc,
          vis,
          inl,
          name,
          lambdaStart,
          param1,
          param2,
          typeAsc,
          precedence,
          assoc,
          (bodyExpr, bodyEnd),
          terminator,
          _
        ) =>
      name.value match {
        case Left(invalidId) =>
          ParsingIdError(
            span = span(name.location.start, terminator.location.end),
            message =
              s"Invalid operator name '$invalidId'. Operator names must be symbolic or follow binding identifier rules.",
            failedCode = Some(invalidId),
            invalidId  = invalidId
          )
        case Right(opName) =>
          val bndSpan     = name.location
          val lambdaSpan  = span(lambdaStart, bodyEnd)
          val opPrec      = precedence.getOrElse(OperatorDefaults.precedence)
          val opAssoc     = assoc.getOrElse(OperatorDefaults.associativity(2))
          val mangledName = OpMangling.mangleOp(opName, 2)
          val nameN       = Name(name.location, mangledName)
          val meta = BindingMeta(
            origin        = BindingOrigin.Operator,
            arity         = CallableArity.Binary,
            precedence    = opPrec,
            associativity = Some(opAssoc),
            originalName  = opName,
            mangledName   = mangledName,
            inlineHint    = inl.isDefined
          )
          val lambda = Lambda(
            span     = lambdaSpan,
            params   = List(param1, param2),
            body     = bodyExpr,
            captures = Nil,
            typeSpec = bodyExpr.typeSpec,
            typeAsc  = typeAsc
          )
          Bnd(
            visibility = vis.getOrElse(Visibility.Protected),
            source     = SourceOrigin.Loc(bndSpan),
            nameNode   = nameN,
            value      = Expr(lambdaSpan, List(lambda)),
            typeSpec   = bodyExpr.typeSpec,
            typeAsc    = typeAsc,
            docComment = doc,
            meta       = Some(meta)
          )
      }
  }

/** Parses a unary operator declaration.
  *
  * Example:
  * {{{
  * op ! (value: Boolean): Boolean 90 right =
  *   value;
  * ;
  * }}}
  */
private[parser] def unaryOpP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    docCommentP(info)
      ~ visibilityP.?
      ~ inlineKw.!.?
      ~ opKw
      ~ tokenP(info)(operatorIdOrError)
      ~ pointP(info)
      ~ "("
      ~ fnParamP(info)
      ~ ")"
      ~ typeAscP(info, TypeContext.Operator)
      ~ precedenceP.?
      ~ assocP.?
      ~ defAsKw
      ~ terminatedExprP(info)
      ~ tokenP(info)(semiKw)
      ~ pointP(info)
  ).map {
    case (
          doc,
          vis,
          inl,
          name,
          lambdaStart,
          param,
          typeAsc,
          precedence,
          assoc,
          (bodyExpr, bodyEnd),
          terminator,
          _
        ) =>
      name.value match {
        case Left(invalidId) =>
          ParsingIdError(
            span = span(name.location.start, terminator.location.end),
            message =
              s"Invalid operator name '$invalidId'. Operator names must be symbolic or follow binding identifier rules.",
            failedCode = Some(invalidId),
            invalidId  = invalidId
          )

        case Right(opName) =>
          val bndSpan     = name.location
          val lambdaSpan  = span(lambdaStart, bodyEnd)
          val opPrec      = precedence.getOrElse(OperatorDefaults.precedence)
          val opAssoc     = assoc.getOrElse(OperatorDefaults.associativity(1))
          val mangledName = OpMangling.mangleOp(opName, 1)
          val nameN       = Name(name.location, mangledName)
          val meta = BindingMeta(
            origin        = BindingOrigin.Operator,
            arity         = CallableArity.Unary,
            precedence    = opPrec,
            associativity = Some(opAssoc),
            originalName  = opName,
            mangledName   = mangledName,
            inlineHint    = inl.isDefined
          )
          val lambda = Lambda(
            span     = lambdaSpan,
            params   = List(param),
            body     = bodyExpr,
            captures = Nil,
            typeSpec = bodyExpr.typeSpec,
            typeAsc  = typeAsc
          )
          Bnd(
            visibility = vis.getOrElse(Visibility.Protected),
            source     = SourceOrigin.Loc(bndSpan),
            nameNode   = nameN,
            value      = Expr(lambdaSpan, List(lambda)),
            typeSpec   = bodyExpr.typeSpec,
            typeAsc    = typeAsc,
            docComment = doc,
            meta       = Some(meta)
          )
      }
  }

/** Parses one unrecognized top-level line as a recoverable parser error member.
  *
  * This keeps the surrounding module parse alive so later members can still be analyzed.
  */
private[parser] def failedMemberP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    pointP(info) ~
      CharsWhile(_ != '\n', min = 1).! ~
      ("\n" ~ pointP(info)).?
  ).map { case (start, raw, maybeEndPoint) =>
    val endPoint = maybeEndPoint.getOrElse {
      val endIndex = start.index + raw.length
      info.pointAt(endIndex)
    }

    val trimmed = raw.trim
    val failedSnippet =
      if trimmed.isEmpty then None else Some(trimmed)

    ParsingMemberError(
      span       = SrcSpan(start, endPoint),
      message    = "Failed to parse member",
      failedCode = failedSnippet
    )
  }
