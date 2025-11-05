package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def moduleP(name: Option[String], source: String, p: P[Any]): P[Module] =
  given P[Any] = p
  name.fold(
    // Needs to be a named module, we
    namedModuleP(source)
  ) { n =>
    P(Start ~ (namedModuleP(source) | anonModuleP(n, source)))
  }

private[parser] def namedModuleP(source: String)(using P[Any]): P[Module] =
  P(
    spP(source)
      ~ docCommentP(source)
      ~ modVisibilityP.?
      ~ moduleKw
      ~ typeIdP.!
      ~ defAsKw
      ~ membersP(source).rep
      ~ moduleEndKw
      ~ spP(source)
  ).map { case (start, doc, maybeVis, moduleName, members, end) =>
    val membersList = members.toList
    val filteredMembers = membersList.lastOption match
      case Some(ParsingMemberError(_, _, None)) => membersList.dropRight(1)
      case _ => membersList
    Module(
      span       = span(start, end),
      name       = moduleName,
      visibility = maybeVis.getOrElse(ModVisibility.Public),
      members    = filteredMembers,
      isImplicit = false,
      docComment = doc
    )
  }

private[parser] def anonModuleP(name: String, source: String)(using P[Any]): P[Module] =
  P(
    spP(source) ~
      membersP(source).rep ~
      moduleEndKw ~
      spP(source)
  )
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
