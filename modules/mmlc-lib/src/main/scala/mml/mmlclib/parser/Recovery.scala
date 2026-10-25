package mml.mmlclib.parser

import cats.syntax.all.*
import fastparse.*

import scala.annotation.tailrec

/** Finds the source boundary owned by the parser surrounding a malformed fragment. */
private[parser] object Recovery:

  enum Anchor:
    case Token(value: String)
    case Keyword(value: String)
    case NumberStart

  /** Ordinary anchors apply outside nested delimiters. Hard stops apply at every depth and must
    * identify syntax excluded from the fragment being recovered. An owning expression frame may
    * recover after a quote with no later closing quote. Closed strings always mask anchors, even
    * across lines; a later quote can therefore conservatively extend the recovered fragment.
    */
  final case class Context(
    anchors:                    List[Anchor],
    hardStops:                  List[Anchor] = Nil,
    recoverUnterminatedStrings: Boolean      = false
  )

  enum Boundary:
    case AtAnchor(anchor: Anchor)
    case ClosingDelimiter(value: Char)
    case EndOfInput

  final case class Result(end: Int, boundary: Boundary)

  private enum Mode:
    case Normal, Quoted, LineComment
    case BlockComment(depth: Int)

  private def isIdentifierChar(value: Char): Boolean =
    value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' ||
      value >= '0' && value <= '9' || value == '_'

  private def matches(source: String, index: Int, anchor: Anchor): Boolean =
    anchor match

      case Anchor.Token(value) =>
        value.nonEmpty && source.startsWith(value, index)

      case Anchor.Keyword(value) =>
        val end = index + value.length

        value.nonEmpty && source.startsWith(value, index) &&
        (index == 0 || !isIdentifierChar(source(index - 1))) &&
        (end == source.length || !isIdentifierChar(source(end)))

      case Anchor.NumberStart =>
        source(index) >= '0' && source(index) <= '9' &&
        (index == 0 || !isIdentifierChar(source(index - 1)))

  /** Scans from a valid source offset, leaving the boundary unconsumed.
    *
    * Configured anchors take precedence at lexical openers, allowing a doc-comment opener to mark
    * the next member. Closed strings, line comments, and nested block comments hide all anchors and
    * delimiters. Strings end at the next quote, matching the language's string syntax; contexts may
    * opt into recovering the remaining text when no closing quote exists. An unmatched or
    * mismatched closing delimiter belongs to the caller. A result at `start`, including EOF, is
    * valid: callers must consume an introducer or boundary before repeating recovery.
    */
  def scan(source: String, start: Int, context: Context): Result =

    @tailrec
    def loop(index: Int, closers: List[Char], mode: Mode): Result =
      if index >= source.length then Result(source.length, Boundary.EndOfInput)
      else
        val current = source(index)

        mode match

          case Mode.Quoted =>
            val nextMode = if current == '"' then Mode.Normal else Mode.Quoted
            loop(index + 1, closers, nextMode)

          case Mode.LineComment =>
            val nextMode = if current == '\n' then Mode.Normal else Mode.LineComment
            loop(index + 1, closers, nextMode)

          case Mode.BlockComment(depth) =>
            if source.startsWith("/*", index) then
              loop(index + 2, closers, Mode.BlockComment(depth + 1))
            else if source.startsWith("*/", index) then
              val nextMode = if depth == 1 then Mode.Normal else Mode.BlockComment(depth - 1)
              loop(index + 2, closers, nextMode)
            else loop(index + 1, closers, mode)

          case Mode.Normal =>
            val anchor = context.hardStops.find(matches(source, index, _)).orElse {
              if closers.isEmpty then context.anchors.find(matches(source, index, _))
              else none[Anchor]
            }

            anchor match

              case Some(value) => Result(index, Boundary.AtAnchor(value))

              case None =>
                if current == '"' then
                  val unclosed = context.recoverUnterminatedStrings &&
                    source.indexOf('"', index + 1) < 0
                  val nextMode = if unclosed then Mode.Normal else Mode.Quoted
                  loop(index + 1, closers, nextMode)
                else if source.startsWith("//", index) then
                  loop(index + 2, closers, Mode.LineComment)
                else if source.startsWith("/*", index) then
                  loop(index + 2, closers, Mode.BlockComment(1))
                else
                  current match

                    case '(' => loop(index + 1, ')' :: closers, mode)
                    case '[' => loop(index + 1, ']' :: closers, mode)
                    case '{' => loop(index + 1, '}' :: closers, mode)

                    case ')' | ']' | '}' =>
                      closers match

                        case expected :: rest if expected == current =>
                          loop(index + 1, rest, mode)

                        case _ => Result(index, Boundary.ClosingDelimiter(current))

                    case _ => loop(index + 1, closers, mode)

    loop(start, Nil, Mode.Normal)

/** Consumes exactly the recovered fragment, including its whitespace, and leaves its boundary. */
private[parser] def recoveryP(info: SourceInfo, context: Recovery.Context)(using
  P[Any]
): P[Recovery.Result] =
  import fastparse.NoWhitespace.*

  P(Index).flatMap { start =>
    val result = Recovery.scan(info.text, start, context)

    P(AnyChar.rep(exactly = result.end - start)).map(_ => result)
  }
