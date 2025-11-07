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
      ~ exprP(info)
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
