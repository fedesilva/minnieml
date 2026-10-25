package mml.mmlclib.parser

import fastparse.*
import fastparse.NoWhitespace.*
import mml.mmlclib.test.BaseEffFunSuite

import scala.concurrent.duration.*

class RecoveryTests extends BaseEffFunSuite:

  import Recovery.*

  private val semicolon  = Anchor.Token(";")
  private val equalsSign = Anchor.Token("=")
  private val thenWord   = Anchor.Keyword("then")

  test("ordinary anchors ignore balanced nested delimiters") {
    val source = "bad ([x; y], { z; }); next"
    val result = Recovery.scan(source, 0, Context(List(semicolon)))

    assertEquals(result, Result(source.lastIndexOf(';'), Boundary.AtAnchor(semicolon)))
    assertEquals(source.drop(result.end), "; next")
  }

  test("hard stops recover through unmatched openers without consuming the boundary") {
    val source  = "bad ([Int = body;"
    val context = Context(List(semicolon), List(equalsSign))

    assertEquals(
      Recovery.scan(source, 0, context),
      Result(source.indexOf('='), Boundary.AtAnchor(equalsSign))
    )
    assertEquals(
      Recovery.scan(source, 0, Context(List(semicolon, equalsSign))),
      Result(source.length, Boundary.EndOfInput)
    )
  }

  test("a hard enclosing delimiter takes precedence over local nesting") {
    val source = "(broken) outer"
    val close  = Anchor.Token(")")

    assertEquals(
      Recovery.scan(source, 0, Context(Nil, List(close))),
      Result(source.indexOf(')'), Boundary.AtAnchor(close))
    )
    assertEquals(
      Recovery.scan(source, 0, Context(Nil)),
      Result(source.length, Boundary.EndOfInput)
    )
  }

  test("strings and comments mask ordinary anchors and hard stops") {
    val source = """bad "=;([then" // =; then )
                   |/* =; then ) /* ;= */ } */ = body""".stripMargin
    val context = Context(List(semicolon, thenWord), List(equalsSign))
    val result  = Recovery.scan(source, 0, context)

    assertEquals(result, Result(source.lastIndexOf('='), Boundary.AtAnchor(equalsSign)))
    assertEquals(source.drop(result.end), "= body")
  }

  test("a backslash does not escape the next quote") {
    val source = "\"text" + '\\' + "\"; tail"
    val result = Recovery.scan(source, 0, Context(List(semicolon)))

    assertEquals(result, Result(source.indexOf(';'), Boundary.AtAnchor(semicolon)))
  }

  test("nested block comments preserve their enclosing comment boundary") {
    val source = "/* first /* nested ; */ still comment ; */ bad; next"
    val result = Recovery.scan(source, 0, Context(List(semicolon)))

    assertEquals(result, Result(source.lastIndexOf(';'), Boundary.AtAnchor(semicolon)))
  }

  test("a configured doc opener preserves the following member's comment") {
    val source = "bad\n/* next member */\nlet next = 1;"
    val doc    = Anchor.Token("/*")
    val result = Recovery.scan(source, 0, Context(List(doc)))

    assertEquals(result, Result(source.indexOf("/*"), Boundary.AtAnchor(doc)))
    assertEquals(source.drop(result.end), "/* next member */\nlet next = 1;")
  }

  test("ordinary doc anchors do not stop at comments inside nested delimiters") {
    val source = "(bad /* ignored /* nested */ comment */) /* next member */"
    val doc    = Anchor.Token("/*")
    val result = Recovery.scan(source, 0, Context(List(doc)))

    assertEquals(result, Result(source.lastIndexOf("/*"), Boundary.AtAnchor(doc)))
  }

  test("doc anchors and hard stops remain hidden inside strings and comments") {
    val source = """"/* then =" (/* then = /* nested */ */) // /* then =
                   |/* next member */""".stripMargin
    val doc     = Anchor.Token("/*")
    val context = Context(List(doc), List(thenWord, equalsSign))
    val result  = Recovery.scan(source, 0, context)

    assertEquals(result, Result(source.lastIndexOf("/*"), Boundary.AtAnchor(doc)))
  }

  test("keyword anchors require both identifier boundaries") {
    val source = "thenElse otherthen _then then_ then2 then next"
    val result = Recovery.scan(source, 0, Context(List(thenWord)))

    assertEquals(result, Result(source.lastIndexOf("then"), Boundary.AtAnchor(thenWord)))
    assertEquals(source.drop(result.end), "then next")
    assertEquals(
      Recovery.scan("otherthen", 5, Context(List(thenWord))),
      Result(9, Boundary.EndOfInput)
    )
  }

  test("context-specific multi-character and declaration anchors remain intact") {
    val arrow  = Anchor.Token("->")
    val member = Anchor.Keyword("fn")

    assertEquals(
      Recovery.scan("bad -> body", 0, Context(List(arrow))),
      Result(4, Boundary.AtAnchor(arrow))
    )
    assertEquals(
      Recovery.scan("(bad\nfn next() = 1;", 0, Context(Nil, List(member))),
      Result(5, Boundary.AtAnchor(member))
    )
  }

  test("numeric anchors preserve raw precedence tokens without stopping inside identifiers") {
    val source = "wrong42 another_60 60left = body"
    val result = Recovery.scan(source, 0, Context(List(Anchor.NumberStart)))

    assertEquals(result, Result(source.indexOf("60left"), Boundary.AtAnchor(Anchor.NumberStart)))
    assertEquals(source.drop(result.end), "60left = body")
  }

  test("unmatched and mismatched closers remain owned by the caller") {
    List("bad) rest", "bad] rest", "bad} rest", "([bad) rest", "{bad] rest").foreach { source =>
      val expectedEnd = source.indexWhere(")]}".contains(_))
      val result      = Recovery.scan(source, 0, Context(Nil))

      assertEquals(
        result,
        Result(expectedEnd, Boundary.ClosingDelimiter(source(expectedEnd))),
        source
      )
    }
  }

  test("matched closers are consumed before a surrounding delimiter") {
    val source = "({[bad]})} outer"
    val result = Recovery.scan(source, 0, Context(Nil))

    assertEquals(result, Result(source.lastIndexOf('}'), Boundary.ClosingDelimiter('}')))
  }

  test("initial boundaries and EOF permit missing fragments of zero width") {
    assertEquals(
      Recovery.scan("; next", 0, Context(List(semicolon))),
      Result(0, Boundary.AtAnchor(semicolon))
    )
    assertEquals(
      Recovery.scan(")", 0, Context(Nil)),
      Result(0, Boundary.ClosingDelimiter(')'))
    )
    assertEquals(Recovery.scan("", 0, Context(Nil)), Result(0, Boundary.EndOfInput))
    assertEquals(Recovery.scan("bad", 3, Context(Nil)), Result(3, Boundary.EndOfInput))
  }

  test("EOF terminates malformed strings, comments, and nested fragments") {
    List("\"unclosed ;", "// comment ;", "/* outer /* inner */ ;", "([bad;").foreach { source =>
      assertEquals(
        Recovery.scan(source, 0, Context(List(semicolon))),
        Result(source.length, Boundary.EndOfInput),
        source
      )
    }
  }

  test("an owning frame can recover after a quote without any later closing quote") {
    val source = "bad [\"unclosed]; next"
    assertEquals(
      Recovery.scan(source, 0, Context(List(semicolon))),
      Result(source.length, Boundary.EndOfInput)
    )
    assertEquals(
      Recovery.scan(source, 0, Context(List(semicolon), recoverUnterminatedStrings = true)),
      Result(source.indexOf(';'), Boundary.AtAnchor(semicolon))
    )
  }

  test("unterminated-string recovery preserves every closed multiline string") {
    val source = """bad ["first;
                   |let inside = 1;
                   |last"]; next""".stripMargin
    val context = Context(List(semicolon), recoverUnterminatedStrings = true)
    assertEquals(
      Recovery.scan(source, 0, context),
      Result(source.lastIndexOf(';'), Boundary.AtAnchor(semicolon))
    )
    assertEquals(
      Recovery.scan(source, 0, context),
      Recovery.scan(source, 0, Context(List(semicolon)))
    )
  }

  test("the parser adapter consumes the exact fragment and leaves the anchor") {
    val source = "prefix:  bad (x; y)  ;  tail"
    val info   = SourceInfo(source)

    def fragment(using P[Any]): P[(Result, Int, String)] =
      P("prefix:" ~ recoveryP(info, Context(List(semicolon))) ~ Index ~ ";" ~ AnyChar.rep.! ~ End)

    parse(source, fragment(using _)) match
      case Parsed.Success((result, boundaryIndex, suffix), end) =>
        assertEquals(result, Result(source.lastIndexOf(';'), Boundary.AtAnchor(semicolon)))
        assertEquals(boundaryIndex, result.end)
        assertEquals(suffix, "  tail")
        assertEquals(end, source.length)

      case failure: Parsed.Failure => fail(failure.trace().longMsg)
  }

  test("long input and deeply nested delimiters complete without recursive stack growth") {
    ParserDeadline.run(List("scan"), 10.seconds).map { result =>
      assert(!result.timedOut, result.output)
      assert(result.stopped, result.output)
      assertEquals(result.exitCode, Some(0), result.output)
    }
  }
