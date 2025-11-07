package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private[parser] def topLevelModuleP(name: String, source: String)(using P[Any]): P[Module] =
  P(
    spP(source) ~
      membersP(source).rep ~
      End ~
      spP(source)
  ).map { case (start, members, end) =>
    val membersList = members.toList
    val filteredMembers = membersList.lastOption match
      case Some(ParsingMemberError(_, _, None)) => membersList.dropRight(1)
      case _ => membersList
    Module(
      span       = span(start, end),
      name       = name,
      visibility = ModVisibility.Protected,
      members    = filteredMembers,
      docComment = None
    )
  }
