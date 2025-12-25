package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def membersP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    binOpDefP(info) |
      unaryOpP(info) |
      letBindingP(info) |
      fnDefP(info) |
      typeAliasP(info) |
      nativeTypeDefP(info) |
      failedMemberP(info)
  )

private[parser] def letBindingP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    docCommentP(info)
      ~ memberVisibilityP.?
      ~ letKw
      ~ spP(info)
      ~ bindingIdOrError
      ~ typeAscP(info)
      ~ defAsKw
      ~ exprMemberP(info)
      ~ endKw
      ~ spP(info)
  )
    .map { case (doc, vis, startPoint, idOrError, typeAsc, expr, endPoint) =>
      idOrError match
        case Left(invalidId) =>
          ParsingIdError(
            span = span(startPoint, endPoint),
            message =
              s"Invalid identifier '$invalidId'. Identifiers must start with a lowercase letter (a-z) followed by letters, digits, or underscores",
            failedCode = Some(invalidId),
            invalidId  = invalidId
          )
        case Right(name) =>
          Bnd(
            visibility = vis.getOrElse(MemberVisibility.Protected),
            span(startPoint, endPoint),
            name,
            expr,
            expr.typeSpec,
            typeAsc,
            doc
          )
    }

private[parser] def fnParamP(info: SourceInfo)(using P[Any]): P[FnParam] =
  P(
    spP(info) ~ docCommentP(info) ~ bindingIdP ~ typeAscP(info) ~ spP(
      info
    )
  ).map { case (start, doc, name, t, end) =>
    FnParam(
      span       = span(start, end),
      name       = name,
      typeAsc    = t,
      docComment = doc
    )
  }

private[parser] def fnParamListP(info: SourceInfo)(using P[Any]): P[List[FnParam]] =
  P(fnParamP(info).rep(sep = ",")).map(_.toList)

private[parser] def fnDefP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    docCommentP(info)
      ~ memberVisibilityP.?
      ~ fnKw
      ~ spP(info)
      ~ bindingIdOrError
      ~ "("
      ~ fnParamListP(info)
      ~ ")"
      ~ typeAscP(info)
      ~ defAsKw
      ~ exprP(info)
      ~ endKw
      ~ spP(info)
  ).map { case (doc, vis, startPoint, idOrError, params, typeAsc, bodyExpr, endPoint) =>
    idOrError match
      case Left(invalidId) =>
        ParsingIdError(
          span = span(startPoint, endPoint),
          message =
            s"Invalid function name '$invalidId'. Function names must start with a lowercase letter (a-z) followed by letters, digits, or underscores",
          failedCode = Some(invalidId),
          invalidId  = invalidId
        )
      case Right(fnName) =>
        val fnSpan = span(startPoint, endPoint)
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
          mangledName   = fnName
        )
        val lambda = Lambda(
          span     = fnSpan,
          params   = params,
          body     = bodyExpr,
          captures = Nil,
          typeSpec = bodyExpr.typeSpec,
          typeAsc  = typeAsc
        )
        Bnd(
          visibility = vis.getOrElse(MemberVisibility.Protected),
          span       = fnSpan,
          name       = fnName,
          value      = Expr(fnSpan, List(lambda)),
          typeSpec   = bodyExpr.typeSpec,
          typeAsc    = typeAsc,
          docComment = doc,
          meta       = Some(meta)
        )
  }

private[parser] def assocP[$: P]: P[Associativity] =
  P("left").map(_ => Associativity.Left) | P("right").map(_ => Associativity.Right)

private[parser] def precedenceP[$: P]: P[Int] =
  P(CharIn("0-9").rep(1).!).map(_.toInt)

private[parser] def binOpDefP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    docCommentP(info)
      ~ memberVisibilityP.?
      ~ opKw
      ~ spP(info)
      ~ operatorIdOrError
      ~ "("
      ~ fnParamP(info)
      ~ ","
      ~ fnParamP(info)
      ~ ")"
      ~ typeAscP(info)
      ~ precedenceP.?
      ~ assocP.?
      ~ defAsKw
      ~ exprP(info)
      ~ spP(info)
      ~ endKw
  ).map {
    case (
          doc,
          vis,
          startPoint,
          idOrError,
          param1,
          param2,
          typeAsc,
          precedence,
          assoc,
          bodyExpr,
          endPoint
        ) =>
      idOrError match {
        case Left(invalidId) =>
          ParsingIdError(
            span = span(startPoint, endPoint),
            message =
              s"Invalid operator name '$invalidId'. Operator names must be symbolic or follow binding identifier rules.",
            failedCode = Some(invalidId),
            invalidId  = invalidId
          )
        case Right(opName) =>
          val opSpan      = span(startPoint, endPoint)
          val opPrec      = precedence.getOrElse(50)
          val opAssoc     = assoc.getOrElse(Associativity.Left)
          val mangledName = OpMangling.mangleOp(opName, 2)
          val meta = BindingMeta(
            origin        = BindingOrigin.Operator,
            arity         = CallableArity.Binary,
            precedence    = opPrec,
            associativity = Some(opAssoc),
            originalName  = opName,
            mangledName   = mangledName
          )
          val lambda = Lambda(
            span     = opSpan,
            params   = List(param1, param2),
            body     = bodyExpr,
            captures = Nil,
            typeSpec = bodyExpr.typeSpec,
            typeAsc  = typeAsc
          )
          Bnd(
            visibility = vis.getOrElse(MemberVisibility.Protected),
            span       = opSpan,
            name       = mangledName,
            value      = Expr(opSpan, List(lambda)),
            typeSpec   = bodyExpr.typeSpec,
            typeAsc    = typeAsc,
            docComment = doc,
            meta       = Some(meta)
          )
      }
  }

private[parser] def unaryOpP(info: SourceInfo)(using P[Any]): P[Member] =
  P(
    docCommentP(info)
      ~ memberVisibilityP.?
      ~ opKw
      ~ spP(info)
      ~ operatorIdOrError
      ~ "("
      ~ fnParamP(info)
      ~ ")"
      ~ typeAscP(info)
      ~ precedenceP.?
      ~ assocP.?
      ~ defAsKw
      ~ exprP(info)
      ~ endKw
      ~ spP(info)
  ).map {
    case (
          doc,
          vis,
          startPoint,
          idOrError,
          param,
          typeAsc,
          precedence,
          assoc,
          bodyExpr,
          endPoint
        ) =>
      idOrError match {
        case Left(invalidId) =>
          ParsingIdError(
            span = span(startPoint, endPoint),
            message =
              s"Invalid operator name '$invalidId'. Operator names must be symbolic or follow binding identifier rules.",
            failedCode = Some(invalidId),
            invalidId  = invalidId
          )
        case Right(opName) =>
          val opSpan      = span(startPoint, endPoint)
          val opPrec      = precedence.getOrElse(50)
          val opAssoc     = assoc.getOrElse(Associativity.Right)
          val mangledName = OpMangling.mangleOp(opName, 1)
          val meta = BindingMeta(
            origin        = BindingOrigin.Operator,
            arity         = CallableArity.Unary,
            precedence    = opPrec,
            associativity = Some(opAssoc),
            originalName  = opName,
            mangledName   = mangledName
          )
          val lambda = Lambda(
            span     = opSpan,
            params   = List(param),
            body     = bodyExpr,
            captures = Nil,
            typeSpec = bodyExpr.typeSpec,
            typeAsc  = typeAsc
          )
          Bnd(
            visibility = vis.getOrElse(MemberVisibility.Protected),
            span       = opSpan,
            name       = mangledName,
            value      = Expr(opSpan, List(lambda)),
            typeSpec   = bodyExpr.typeSpec,
            typeAsc    = typeAsc,
            docComment = doc,
            meta       = Some(meta)
          )
      }
  }

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
