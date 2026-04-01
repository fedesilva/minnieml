package mml.mmlclib.parser

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
    docCommentP(info)
      ~ visibilityP.?
      ~ letKw
      ~ spP(info)
      ~ bindingIdOrError
      ~ spNoWsP(info)
      ~ typeAscP(info)
      ~ defAsKw
      ~ exprMemberP(info)
      ~ semiKw
      ~ spNoWsP(info)
      ~ spP(info)
  )
    .map { case (doc, vis, nameStart, idOrError, nameEnd, typeAsc, expr, endPoint, _) =>
      idOrError match
        case Left(invalidId) =>
          ParsingIdError(
            span = span(nameStart, endPoint),
            message =
              s"Invalid identifier '$invalidId'. Identifiers must start with a lowercase letter (a-z) followed by letters, digits, or underscores",
            failedCode = Some(invalidId),
            invalidId  = invalidId
          )
        case Right(nameStr) =>
          Bnd(
            visibility = vis.getOrElse(Visibility.Protected),
            SourceOrigin.Loc(span(nameStart, endPoint)),
            Name(span(nameStart, nameEnd), nameStr),
            expr,
            expr.typeSpec,
            typeAsc,
            doc
          )
    }

/** Parses one function parameter.
  *
  * Examples:
  * {{{
  * value: Int
  * ~buffer: RawPtr
  * }}}
  */
private[parser] def fnParamP(info: SourceInfo)(using P[Any]): P[FnParam] =
  P(
    spP(info) ~ docCommentP(info) ~ "~".!.? ~ spP(info) ~ bindingIdP ~ spNoWsP(info) ~
      typeAscP(info) ~ spNoWsP(info) ~ spP(info)
  ).map { case (start, doc, tilde, nameStart, nameStr, nameEnd, t, end, _) =>
    FnParam(
      source     = SourceOrigin.Loc(span(start, end)),
      nameNode   = Name(span(nameStart, nameEnd), nameStr),
      typeAsc    = t,
      docComment = doc,
      consuming  = tilde.isDefined
    )
  }

/** Parses the comma-separated parameter list inside `(...)`. */
private[parser] def fnParamListP(info: SourceInfo)(using P[Any]): P[List[FnParam]] =
  P(fnParamP(info).rep(sep = ",")).map(_.toList)

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
    docCommentP(info)
      ~ visibilityP.?
      ~ inlineKw.!.?
      ~ fnKw
      ~ spP(info)
      ~ bindingIdOrError
      ~ spNoWsP(info)
      ~ spP(info)
      ~ "("
      ~ fnParamListP(info)
      ~ ")"
      ~ typeAscP(info)
      ~ defAsKw
      ~ terminatedExprP(info)
      ~ semiKw
      ~ spNoWsP(info)
      ~ spP(info)
  ).map {
    case (
          doc,
          vis,
          inl,
          nameStart,
          idOrError,
          nameEnd,
          lambdaStart,
          params,
          typeAsc,
          (bodyExpr, bodyEnd),
          endPoint,
          _
        ) =>
      idOrError match
        case Left(invalidId) =>
          ParsingIdError(
            span = span(nameStart, endPoint),
            message =
              s"Invalid function name '$invalidId'. Function names must start with a lowercase letter (a-z) followed by letters, digits, or underscores",
            failedCode = Some(invalidId),
            invalidId  = invalidId
          )
        case Right(fnName) =>
          val bndSpan    = span(nameStart, nameEnd)
          val nameN      = Name(span(nameStart, nameEnd), fnName)
          val lambdaSpan = span(lambdaStart, bodyEnd)
          val arity = params.size match
            case 0 => CallableArity.Nullary
            case 1 => CallableArity.Unary
            case 2 => CallableArity.Binary
            case n => CallableArity.Nary(n)
          val meta = BindingMeta(
            origin        = BindingOrigin.Function,
            arity         = arity,
            precedence    = Precedence.Function,
            associativity = None,
            originalName  = fnName,
            mangledName   = fnName,
            inlineHint    = inl.isDefined
          )
          val lambda = Lambda(
            span     = lambdaSpan,
            params   = params,
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
      ~ spP(info)
      ~ operatorIdOrError
      ~ spNoWsP(info)
      ~ spP(info)
      ~ "("
      ~ fnParamP(info)
      ~ ","
      ~ fnParamP(info)
      ~ ")"
      ~ typeAscP(info)
      ~ precedenceP.?
      ~ assocP.?
      ~ defAsKw
      ~ terminatedExprP(info)
      ~ spP(info)
      ~ semiKw
      ~ spNoWsP(info)
      ~ spP(info)
  ).map {
    case (
          doc,
          vis,
          inl,
          nameStart,
          idOrError,
          nameEnd,
          lambdaStart,
          param1,
          param2,
          typeAsc,
          precedence,
          assoc,
          (bodyExpr, bodyEnd),
          _,
          endPoint,
          _
        ) =>
      idOrError match {
        case Left(invalidId) =>
          ParsingIdError(
            span = span(nameStart, endPoint),
            message =
              s"Invalid operator name '$invalidId'. Operator names must be symbolic or follow binding identifier rules.",
            failedCode = Some(invalidId),
            invalidId  = invalidId
          )
        case Right(opName) =>
          val bndSpan     = span(nameStart, nameEnd)
          val lambdaSpan  = span(lambdaStart, bodyEnd)
          val opPrec      = precedence.getOrElse(50)
          val opAssoc     = assoc.getOrElse(Associativity.Left)
          val mangledName = OpMangling.mangleOp(opName, 2)
          val nameN       = Name(span(nameStart, nameEnd), mangledName)
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
      ~ spP(info)
      ~ operatorIdOrError
      ~ spNoWsP(info)
      ~ spP(info)
      ~ "("
      ~ fnParamP(info)
      ~ ")"
      ~ typeAscP(info)
      ~ precedenceP.?
      ~ assocP.?
      ~ defAsKw
      ~ terminatedExprP(info)
      ~ semiKw
      ~ spNoWsP(info)
      ~ spP(info)
  ).map {
    case (
          doc,
          vis,
          inl,
          nameStart,
          idOrError,
          nameEnd,
          lambdaStart,
          param,
          typeAsc,
          precedence,
          assoc,
          (bodyExpr, bodyEnd),
          endPoint,
          _
        ) =>
      idOrError match {
        case Left(invalidId) =>
          ParsingIdError(
            span = span(nameStart, endPoint),
            message =
              s"Invalid operator name '$invalidId'. Operator names must be symbolic or follow binding identifier rules.",
            failedCode = Some(invalidId),
            invalidId  = invalidId
          )

        case Right(opName) =>
          val bndSpan     = span(nameStart, nameEnd)
          val lambdaSpan  = span(lambdaStart, bodyEnd)
          val opPrec      = precedence.getOrElse(50)
          val opAssoc     = assoc.getOrElse(Associativity.Right)
          val mangledName = OpMangling.mangleOp(opName, 1)
          val nameN       = Name(span(nameStart, nameEnd), mangledName)
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
    spP(info) ~
      CharsWhile(_ != '\n', min = 1).! ~
      ("\n" ~ spP(info)).?
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
