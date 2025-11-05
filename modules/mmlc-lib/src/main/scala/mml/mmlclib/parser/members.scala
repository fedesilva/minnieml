package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def membersP(source: String)(using P[Any]): P[Member] =
  P(
    binOpDefP(source) |
      unaryOpP(source) |
      letBindingP(source) |
      fnDefP(source) |
      typeAliasP(source) |
      nativeTypeDefP(source) |
      failedMemberP(source)
  )

private[parser] def letBindingP(source: String)(using P[Any]): P[Member] =
  P(
    docCommentP(source)
      ~ memberVisibilityP.?
      ~ letKw
      ~ spP(source)
      ~ bindingIdOrError
      ~ typeAscP(source)
      ~ defAsKw
      ~ exprP(source)
      ~ endKw
      ~ spP(source)
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

private[parser] def fnParamP(source: String)(using P[Any]): P[FnParam] =
  P(
    spP(source) ~ docCommentP(source) ~ bindingIdP ~ typeAscP(source) ~ spP(
      source
    )
  ).map { case (start, doc, name, t, end) =>
    FnParam(
      span       = span(start, end),
      name       = name,
      typeAsc    = t,
      docComment = doc
    )
  }

private[parser] def fnParamListP(source: String)(using P[Any]): P[List[FnParam]] =
  P(fnParamP(source).rep(sep = ",")).map(_.toList)

private[parser] def fnDefP(source: String)(using P[Any]): P[Member] =
  P(
    docCommentP(source)
      ~ memberVisibilityP.?
      ~ fnKw
      ~ spP(source)
      ~ bindingIdOrError
      ~ "("
      ~ fnParamListP(source)
      ~ ")"
      ~ typeAscP(source)
      ~ defAsKw
      ~ exprP(source)
      ~ endKw
      ~ spP(source)
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
        FnDef(
          visibility = vis.getOrElse(MemberVisibility.Protected),
          span       = span(startPoint, endPoint),
          name       = fnName,
          params     = params,
          body       = bodyExpr,
          typeSpec   = bodyExpr.typeSpec,
          typeAsc    = typeAsc,
          docComment = doc
        )
  }

private[parser] def assocP[$: P]: P[Associativity] =
  P("left").map(_ => Associativity.Left) | P("right").map(_ => Associativity.Right)

private[parser] def precedenceP[$: P]: P[Int] =
  P(CharIn("0-9").rep(1).!).map(_.toInt)

private[parser] def binOpDefP(source: String)(using P[Any]): P[Member] =
  P(
    docCommentP(source)
      ~ memberVisibilityP.?
      ~ opKw
      ~ spP(source)
      ~ operatorIdOrError
      ~ "("
      ~ fnParamP(source)
      ~ ","
      ~ fnParamP(source)
      ~ ")"
      ~ typeAscP(source)
      ~ precedenceP.?
      ~ assocP.?
      ~ defAsKw
      ~ exprP(source)
      ~ spP(source)
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
          BinOpDef(
            visibility = vis.getOrElse(MemberVisibility.Protected),
            span       = span(startPoint, endPoint),
            name       = opName,
            param1     = param1,
            param2     = param2,
            precedence = precedence.getOrElse(50),
            assoc      = assoc.getOrElse(Associativity.Left),
            body       = bodyExpr,
            typeSpec   = bodyExpr.typeSpec,
            typeAsc    = typeAsc,
            docComment = doc
          )
      }
  }

private[parser] def unaryOpP(source: String)(using P[Any]): P[Member] =
  P(
    docCommentP(source)
      ~ memberVisibilityP.?
      ~ opKw
      ~ spP(source)
      ~ operatorIdOrError
      ~ "("
      ~ fnParamP(source)
      ~ ")"
      ~ typeAscP(source)
      ~ precedenceP.?
      ~ assocP.?
      ~ defAsKw
      ~ exprP(source)
      ~ endKw
      ~ spP(source)
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
          UnaryOpDef(
            visibility = vis.getOrElse(MemberVisibility.Protected),
            span       = span(startPoint, endPoint),
            name       = opName,
            param      = param,
            precedence = precedence.getOrElse(50),
            assoc      = assoc.getOrElse(Associativity.Right),
            body       = bodyExpr,
            typeAsc    = typeAsc,
            typeSpec   = bodyExpr.typeSpec,
            docComment = doc
          )
      }
  }

private[parser] def failedMemberP(source: String)(using P[Any]): P[Member] =
  P(spP(source) ~ CharsWhile(_ != ';', 0).! ~ endKw ~ spP(source))
    .map { case (start, snippet, end) =>
      ParsingMemberError(
        span       = SrcSpan(start, end),
        message    = "Failed to parse member",
        failedCode = if snippet.trim.nonEmpty then snippet.some else None
      )
    }
