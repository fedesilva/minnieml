package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.lsp.{Diagnostics, Range}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.{ErrorPrinter, SemanticErrorPrinter}

class OwnershipDiagnosticTests extends BaseEffFunSuite:

  private val holders = """
    struct Holder { f: Int -> Int };
    fn add(a: Int, b: Int): Int = a + b;;
    fn make(n: Int): Holder = let p = add n; Holder p;;
    fn drop(~h: Holder): Int = 0;;
    fn use(f: Int -> Int, n: Int): Int = f n;;
    fn staged(f: Int -> Int): Int -> Int = add (f 1);;
  """

  private def member(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case binding: Bnd if binding.name == name => binding }
      .getOrElse(fail(s"Expected member $name"))

  private def sourceRef(module: Module, source: String, marker: String): Ref =
    val index = source.lastIndexOf(marker)
    assert(index >= 0, s"Expected fixture marker $marker")
    val refs = TermTraversal.collect(member(module, "example").value) {
      case ref: Ref if ref.spanOpt.exists(_.start.index == index) => ref
    }
    assertEquals(refs.size, 1)
    refs.head

  private def assertRendered(
    error:  SemanticError,
    source: String,
    origin: SourceOrigin
  ): Unit =
    val span       = origin.spanOpt.getOrElse(fail("Expected a source diagnostic"))
    val diagnostic = Diagnostics.fromError(error).getOrElse(fail("Expected an LSP diagnostic"))
    assertEquals(diagnostic.range, Range.fromSrcSpan(span))
    val outputs = List(
      ErrorPrinter.prettyPrintSemanticErrors(List(error), Some(SourceInfo(source))),
      SemanticErrorPrinter.prettyPrint(List(error), Some(SourceInfo(source)))
    )
    val snippet = source.substring(span.start.index, span.end.index)
    outputs.foreach { output =>
      assert(output.contains(ErrorPrinter.formatLocation(span)), output)
      assert(!output.contains("[synthetic]"), output)
      assert(output.contains(s"${Console.RED}${Console.BOLD}$snippet${Console.RESET}"), output)
    }

  List(
    ("field alias", "let q = h.f; let a = drop h; q 2;", "q 2"),
    ("alias chain", "let q = h.f; let r = q; let a = drop h; r 2;", "r 2"),
    ("borrowing argument", "let q = h.f; use q (drop h);", "q (drop"),
    ("staged borrowing argument", "let q = h.f; staged q (drop h);", "q (drop")
  ).foreach { (label, body, marker) =>
    test(s"$label reports the current use and moved owner once") {
      val source = holders + s"fn example(): Int = let h = make 1; $body ;"
      for
        parsed <- parseNotFailed(source)
        result <- semState(source)
      yield
        val used   = sourceRef(parsed, source, marker)
        val errors = result.errors.collect { case error: SemanticError.UseAfterMove => error }
        assertEquals(errors.size, 1, result.errors.toString)
        assertEquals(result.errors.size, 1, result.errors.toString)
        val error = errors.head
        assertEquals(error.ownerId, Some(resolvedBindingId(result.module, "example", "h")))
        assertEquals(
          error.ref.resolvedId,
          Some(resolvedBindingId(result.module, "example", used.name))
        )
        assertEquals(error.source, used.source)
        val moveIndex = source.lastIndexOf("drop h") + "drop ".length
        assertEquals(error.movedAt.spanOpt.map(_.start.index), Some(moveIndex))
        assertRendered(error, source, used.source)
    }
  }

  test("rendered borrowing errors use the argument span independently of the reference span") {
    val source = holders + """
      fn example(): Int =
        let h = make 1;
        let q = h.f;
        use (if true then q; else q;) (drop h);
      ;
    """
    semState(source).map { result =>
      val errors = result.errors.collect { case error: SemanticError.UseAfterMove => error }
      assertEquals(result.errors.size, 1, result.errors.toString)
      assertEquals(errors.size, 1)
      val error = errors.head
      val span  = error.source.spanOpt.getOrElse(fail("Expected argument source location"))
      assertEquals(
        source.substring(span.start.index, span.end.index).trim,
        "if true then q; else q;"
      )
      assertNotEquals(error.source.spanOpt, error.ref.spanOpt)
      assertEquals(error.ref.resolvedId, Some(resolvedBindingId(result.module, "example", "h")))
      assertEquals(error.ownerId, Some(resolvedBindingId(result.module, "example", "h")))
      assertRendered(error, source, error.source)
    }
  }

  test("a valid field borrow before owner destruction remains accepted") {
    semNotFailed(holders + """
      fn example(): Int =
        let h = make 1;
        let q = h.f;
        let answer = q 2;
        let ignored = drop h;
        answer;
      ;
    """).void
  }

  test("separate uses of one moved owner retain separate diagnostics") {
    val source = holders + """
      fn example(): Int =
        let h = make 1;
        let q = h.f;
        let ignored = drop h;
        let first = q 2;
        q 3;
      ;
    """
    for
      parsed <- parseNotFailed(source)
      result <- semState(source)
    yield
      val errors = result.errors.collect { case error: SemanticError.UseAfterMove => error }
      assertEquals(result.errors.size, 2, result.errors.toString)
      assertEquals(errors.size, 2)
      assertEquals(
        errors.map(_.source).toSet,
        Set(
          sourceRef(parsed, source, "q 2").source,
          sourceRef(parsed, source, "q 3").source
        )
      )
      assertEquals(
        errors.flatMap(_.ownerId).toSet,
        Set(resolvedBindingId(result.module, "example", "h"))
      )
  }

  test("one use depending on two moved owners retains both identities") {
    val source = """
      fn take(~text: String): Int = text.length;;
      fn example(): Int =
        let first = int_to_str 1;
        let second = int_to_str 2;
        let read = { first.length + second.length; };
        let a = take first;
        let b = take second;
        read ();
      ;
    """
    for
      parsed <- parseNotFailed(source)
      result <- semState(source)
    yield
      val errors = result.errors.collect { case error: SemanticError.UseAfterMove => error }
      assertEquals(result.errors.size, 2, result.errors.toString)
      assertEquals(errors.size, 2)
      assertEquals(errors.map(_.source).toSet, Set(sourceRef(parsed, source, "read ()").source))
      assertEquals(
        errors.flatMap(_.ownerId).toSet,
        Set(
          resolvedBindingId(result.module, "example", "first"),
          resolvedBindingId(result.module, "example", "second")
        )
      )
  }

  private val consuming = """
    fn take(~text: String, n: Int): Int = text.length + n;;
    fn borrow(f: Int -> Int): Int = f 1;;
  """

  List(
    ("inline", "borrow (take (int_to_str 123));", "take (int_to_str 123)"),
    ("bound", "let p = take (int_to_str 123); borrow p;", "p;")
  ).foreach { (label, body, marker) =>
    test(s"$label call-once argument retains its source location") {
      val source = consuming + s"fn example(): Int = $body ;"
      semState(source).map { result =>
        val errors = result.errors.collect { case error: SemanticError.InvalidExpression => error }
        assertEquals(result.errors.size, 2, result.errors.toString)
        assertEquals(errors.size, 2)
        val argumentErrors =
          errors.filter(_.msg == "A call-once function requires a consuming parameter")
        assertEquals(argumentErrors.size, 1)
        val error = argumentErrors.head
        val span  = error.expr.spanOpt.getOrElse(fail("Expected source argument location"))
        assertEquals(span.start.index, source.lastIndexOf(marker))
        assertEquals(
          source.substring(span.start.index, span.end.index).trim,
          marker.stripSuffix(";")
        )
        assertRendered(error, source, error.expr.source)
      }
    }
  }
