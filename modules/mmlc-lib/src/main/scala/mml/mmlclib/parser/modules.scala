package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*
import cats.syntax.all.*
import MmlWhitespace.*

private[parser] def moduleP(name: Option[String], source: String, p: P[Any]): P[Module] =
  given P[Any] = p
  name.fold(
    explicitModuleP(source)
  ) { n =>
    P(explicitModuleP(source) | implicitModuleP(n, source))
  }

private[parser] def explicitModuleP(source: String)(using P[Any]): P[Module] =
  P(
    Start
      ~ spP(source)
      ~ docCommentP(source)
      ~ modVisibilityP.?
      ~ moduleKw
      ~ typeIdP.!
      ~ defAsKw
      ~ membersP(source).rep
      ~ moduleEndKw
      ~ spP(source)
  ).map { case (start, doc, maybeVis, moduleName, members, end) =>
    Module(
      span       = span(start, end),
      name       = moduleName,
      visibility = maybeVis.getOrElse(ModVisibility.Public),
      members    = members.toList,
      isImplicit = false,
      docComment = doc
    )
  }

private[parser] def implicitModuleP(name: String, source: String)(using P[Any]): P[Module] =
  P(Start ~ spP(source) ~ membersP(source).rep ~ End ~ spP(source))
    .map { case (start, members, end) =>
      Module(
        span       = span(start, end),
        name       = name,
        visibility = ModVisibility.Public,
        members    = members.toList,
        isImplicit = true
      )
    }

private[parser] def modVisibilityP[$: P]: P[ModVisibility] =
  P("pub").map(_ => ModVisibility.Public) |
    P("prot").map(_ => ModVisibility.Protected) |
    P("lex").map(_ => ModVisibility.Lexical)