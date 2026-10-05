package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*

import MmlWhitespace.*

private val invalidLinkMessage =
  "Expected @link[\"library\", \"library\"]; " +
    "with nonempty library names, not paths or linker arguments"

private def memberKeyword(using P[Any]): P[Unit] =
  P(StringIn("let", "fn", "op", "type", "struct", "pub", "prot", "priv", "inline"))

private def memberBoundary(using P[Any]): P[Unit] =
  P(End | "@link" | "/*" | memberKeyword ~~ !CharIn("a-zA-Z0-9_"))

private def validLinkName(name: String): Boolean =
  name.matches("[A-Za-z0-9_][A-Za-z0-9_.+\\-]*")

/** A closed name cannot borrow its closing quote from a following declaration. */
private def closedLinkStringP(info: SourceInfo)(using P[Any]): P[LiteralString] =
  def nameBoundary(using P[Any]): P[Unit] =
    P(
      "," | "\"" |
        "]" ~ ";".? ~ memberBoundary |
        ";" ~ memberBoundary |
        memberBoundary
    )

  litStringP(info).flatMap { name =>
    if validLinkName(name.value) then P(Pass).map(_ => name)
    else if name.value.contains('\n') then P(Fail)
    else P(&(nameBoundary)).map(_ => name)
  }

private def linkEntryP(info: SourceInfo)(using P[Any]): P[LinkEntry] =
  P(spP(info) ~ closedLinkStringP(info) ~ spNoWsP(info))
    .map { case (start, name, end) => LinkEntry(span(start, end), name.value) }

private def linkSyntaxP(info: SourceInfo)(using P[Any]): P[LinkDirective] =
  P(
    spP(info) ~ "@link" ~ "[" ~ linkEntryP(info).rep(1, sep = ",") ~ "]" ~ ";" ~
      spNoWsP(info)
  ).map { case (start, entries, end) =>
    LinkDirective(span(start, end), entries.toList)
  }

/** Recover at the terminator or the next declaration so malformed headers retain members. */
private def failedLinkP(info: SourceInfo)(using P[Any]): P[ParsingMemberError] =
  import fastparse.NoWhitespace.*

  def boundary(using P[Any]): P[Unit] =
    P(";" | memberBoundary)

  def recoveryToken(using P[Any]): P[Unit] =
    P(
      closedLinkStringP(info).map(_ => ()) |
        "\"" ~ CharsWhile(c => !c.isWhitespace && !"\"];,".contains(c), 0) |
        "//" ~ CharsWhile(_ != '\n', 0) |
        !boundary ~ AnyChar
    )

  P(spP(info) ~ "@link" ~ recoveryToken.rep ~ ";".? ~ spNoWsP(info))
    .map { case (start, end) =>
      ParsingMemberError(
        span(start, end),
        invalidLinkMessage,
        info.text.substring(start.index, end.index).some
      )
    }

private[parser] def linkHeaderP(
  info: SourceInfo
)(using P[Any]): P[Either[ParsingMemberError, LinkDirective]] =
  P(
    linkSyntaxP(info).map { directive =>
      if directive.entries.forall(entry => validLinkName(entry.name)) then
        directive.asRight[ParsingMemberError]
      else
        ParsingMemberError(directive.span, invalidLinkMessage, directive.syntax.some)
          .asLeft[LinkDirective]
    } |
      failedLinkP(info).map(_.asLeft[LinkDirective])
  )

private[parser] def misplacedLinkP(info: SourceInfo)(using P[Any]): P[Member] =
  linkHeaderP(info).map {
    case Left(error) => error
    case Right(directive) =>
      ParsingMemberError(
        directive.span,
        "@link must occur once, before all module members",
        directive.syntax.some
      )
  }
