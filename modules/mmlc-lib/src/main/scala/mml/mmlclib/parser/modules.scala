package mml.mmlclib.parser

import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

/** Parses a whole source file as a synthetic top-level module.
  *
  * There is no surface `module ...` declaration in source files. The parser simply reads members
  * until end-of-file and wraps them in a [[Module]] named by the caller.
  *
  * Example:
  * {{{
  * let answer = 42;
  *
  * fn inc(x: Int): Int =
  *   x + 1;
  * ;
  * }}}
  */
private[parser] def topLevelModuleP(
  name:       String,
  info:       SourceInfo,
  sourcePath: Option[String]
)(using P[Any]): P[Module] =
  P(
    spP(info) ~
      membersP(info).rep ~
      spNoWsP(info) ~
      spP(info) ~
      End
  ).map { case (start, members, end, _) =>
    val membersList = members.toList
    val filteredMembers = membersList.lastOption match
      case Some(ParsingMemberError(_, _, None)) => membersList.dropRight(1)
      case _ => membersList
    Module(
      source     = SourceOrigin.Loc(span(start, end)),
      name       = name,
      visibility = Visibility.Protected,
      members    = filteredMembers,
      docComment = None,
      sourcePath = sourcePath
    )
  }
