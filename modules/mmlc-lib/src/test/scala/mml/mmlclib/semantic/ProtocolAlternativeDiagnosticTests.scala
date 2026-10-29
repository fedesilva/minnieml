package mml.mmlclib.semantic

import mml.mmlclib.lsp.Diagnostics
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.SemanticErrorPrinter

class ProtocolAlternativeDiagnosticTests extends BaseEffFunSuite:

  test("an unresolved signature without registered instances has no invented alternatives"):
    checkDiagnostic("protocol Choice 'T = fn choose(value: 'T): 'T; ; let selected = choose;") {
      case error @ ProtocolError.InsufficientTypeInformation(_, alternatives) =>
        assertEquals(alternatives, Nil)
        assert(!error.message.contains("Alternatives:"))
      case other => fail(s"Expected insufficient information, got $other")
    }

  List(1, 5, 6, 12).foreach { count =>
    test(s"insufficient information displays all $count alternatives"):
      checkDiagnostic(choices(count) + "let selected = choose;") {
        case error @ ProtocolError.InsufficientTypeInformation(_, alternatives) =>
          assertChoices(error, alternatives, count)
        case other => fail(s"Expected insufficient information, got $other")
      }
  }

  List(2, 5, 6, 12).foreach { count =>
    test(s"ambiguity displays all $count applicable alternatives"):
      checkDiagnostic(choices(count) + "let selected = choose 42;") {
        case error @ ProtocolError.AmbiguousSelection(_, alternatives) =>
          assertChoices(error, alternatives, count)
        case other => fail(s"Expected ambiguous selection, got $other")
      }
  }

  test("known incompatible parameter types are excluded from displayed alternatives"):
    val source = (1 to 6).map { index =>
      val input = if index <= 4 then "Int" else "Bool"
      s"""
        protocol Choice$index 'T = fn choose(seed: $input): 'T; ;
        instance Choice$index Int = fn choose(seed) = 0;; ;
      """
    }.mkString + "let selected = choose true;"
    checkDiagnostic(source) {
      case error @ ProtocolError.InsufficientTypeInformation(_, alternatives) =>
        assertEquals(alternatives.flatMap(_.protocolName), List("Choice5", "Choice6"))
        assert(error.message.contains("Alternatives:"))
        assertEquals(
          alternatives.map(_.description),
          List(
            "Choice5.choose: (Bool) -> Int",
            "Choice6.choose: (Bool) -> Int"
          )
        )
      case other => fail(s"Expected insufficient information, got $other")
    }

  test("ambiguity excludes concrete implementations rejected by the supplied argument"):
    val source = choices(2) + """
      instance Choice1 Bool = fn choose(value) = value;; ;
      instance Choice2 Bool = fn choose(value) = value;; ;
      let selected = choose 42;
    """
    checkDiagnostic(source) {
      case error @ ProtocolError.AmbiguousSelection(_, alternatives) =>
        assertChoices(error, alternatives, 2)
      case other => fail(s"Expected ambiguous selection, got $other")
    }

  test("CLI and LSP retain all twelve alternatives and the reference span"):
    val source = choices(12) + "let selected = choose;"
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)
      val result = TypeChecker.rewriteModule(initial)
      result.visibleErrors match
        case Vector(error @ SemanticError.ProtocolErrorFound(protocolError, _)) =>
          val diagnostic = Diagnostics.fromError(error).getOrElse(fail("Expected LSP diagnostic"))
          assertEquals(diagnostic.message, protocolError.message)
          val cli = SemanticErrorPrinter.prettyPrint(List(error))
          choiceDescriptions(12).foreach { description =>
            assert(cli.contains(description))
          }
          val span = protocolError.source.spanOpt.getOrElse(fail("Expected source span"))
          assertEquals(source.substring(span.start.index, span.end.index), "choose")
        case other => fail(s"Expected one selection diagnostic, got $other")
    }

  private def choices(count: Int): String =
    (1 to count).map { index =>
      s"""
        protocol Choice$index 'T = fn choose(value: 'T): 'T; ;
        instance Choice$index Int = fn choose(value) = value;; ;
      """
    }.mkString

  private def assertChoices(
    error:        ProtocolError,
    alternatives: List[ProtocolAlternative],
    count:        Int
  ): Unit =
    val expected = choiceDescriptions(count)
    assertEquals(alternatives.map(_.description), expected)
    assertEquals(
      error.message.linesIterator.drop(1).toList,
      "Alternatives:" :: expected.map(description => s"  $description")
    )

  private def choiceDescriptions(count: Int): List[String] =
    (1 to count).map(index => s"Choice$index.choose: (Int) -> Int").toList.sorted

  private def checkDiagnostic(source: String)(check: ProtocolError => Unit) =
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)
      val result = TypeChecker.rewriteModule(initial)
      result.visibleErrors match
        case Vector(SemanticError.ProtocolErrorFound(error, _)) => check(error)
        case other => fail(s"Expected one protocol diagnostic, got $other")
    }
