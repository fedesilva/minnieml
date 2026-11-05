package mml.mmlclib.parser

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.test.BaseEffFunSuite

import scala.concurrent.duration.*

class ParenthesizedExpressionWorkTests extends BaseEffFunSuite:

  private def bindingValue(module: Module): Expr =
    module.members match
      case (binding: Bnd) :: Nil => binding.value
      case other => fail(s"Expected one binding, found $other")

  private def integers(term: Term): List[Long] =
    TermTraversal.collect(term):
      case literal: LiteralInt => literal.value

  /** Checks clean, stable parser output and fixture structure within a source-size work budget. */
  private def measuredClean(source: String)(check: Expr => Unit): IO[Unit] =
    parseNotFailed(source).map: module =>
      val checked = ParsingErrorChecker.checkModule(
        CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      )
      assertEquals(checked.errors, Vector.empty)

      val (_, result, collector) = Parser.parseModuleInstrumented(source, "Test")
      val instrumented = result.fold(error => fail(s"Instrumented parse failed: $error"), identity)
      assertEquals(instrumented, module)
      check(bindingValue(module))

      val invocations = collector
        .toCounters("parser")
        .find(_.name == "invocations")
        .map(_.value)
        .getOrElse(fail("Expected parser invocation counter"))
      // A source-size budget permits grammar growth while rejecting repeated nested parsing.
      val budget = source.length.toLong * 200L
      assert(invocations <= budget, s"$invocations parser invocations exceed budget $budget")

  private def assertNestedArithmetic(value: Expr): Unit =
    val groups = TermTraversal.collect(value):
      case group: TermGroup => group
    val operators = TermTraversal.collect(value):
      case reference: Ref => reference.name

    assertEquals(integers(value), List(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L))
    assertEquals(groups.size, 10)
    assertEquals(groups.map(_.inner.terms.size), List.fill(10)(3))
    assertEquals(groups.map(group => integers(group.inner).head), (2L to 11L).toList)
    assertEquals(operators, List.fill(11)("+"))

  private def assertNestedTuples(value: Expr): Unit =
    val tuples = TermTraversal.collect(value):
      case tuple: Tuple => tuple
    val groups = TermTraversal.collect(value):
      case group: TermGroup => group

    assertEquals(integers(value), List(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L))
    assertEquals(tuples.size, 7)
    assertEquals(tuples.map(_.elements.size), List.fill(7)(2))
    assertEquals(
      tuples.map(tuple => integers(tuple.elements.last)),
      List(List(8L), List(7L), List(6L), List(5L), List(4L), List(3L), List(2L))
    )
    assertEquals(groups, Nil)

  private def assertSelectedGroups(source: String, value: Expr): Unit =
    val selections = TermTraversal.collect(value):
      case reference: Ref if reference.qualifier.nonEmpty => reference
    val tuples = TermTraversal.collect(value):
      case tuple: Tuple => tuple
    val groups = TermTraversal.collect(value):
      case group: TermGroup => group

    assertEquals(
      selections.map(_.name),
      List("tail", "next", "next", "next", "next", "next", "value", "head")
    )
    assertEquals(groups.size, 7)
    assertEquals(tuples.size, 1)
    assertEquals(tuples.head.elements.size, 2)
    val tupleReferences = TermTraversal.collect(tuples.head):
      case reference: Ref => reference.name
    assertEquals(tupleReferences, List("left", "right"))
    assertEquals(selections.tail.flatMap(_.typeAsc), Nil)
    selections.head.typeAsc match
      case Some(annotation: TypeRef) => assertEquals(annotation.name, "Int")
      case other => fail(s"Expected the final selection annotation, found $other")

    val location = selections.head.spanOpt.getOrElse(fail("Expected a selection span"))
    assertEquals(location.end.index, source.indexOf(": Int"))
    assertEquals(source.charAt(location.start.index), '(')

  test("nested arithmetic in a function preserves grouping within a linear work budget"):
    val source = """
      fn value(): Int =
        1 + (2 + (3 + (4 + (5 + (6 + (7 + (8 + (9 + (10 + (11 + 12))))))))));
      ;
    """

    measuredClean(source): value =>
      val functions = TermTraversal.collect(value):
        case lambda: Lambda => lambda
      assertEquals(functions.size, 1)
      assertNestedArithmetic(value)

  test("nested arithmetic in a member preserves grouping within a linear work budget"):
    val source = """
      let value =
        1 + (2 + (3 + (4 + (5 + (6 + (7 + (8 + (9 + (10 + (11 + 12))))))))));
    """

    measuredClean(source)(assertNestedArithmetic)

  test("nested first tuple elements in a member retain element order within the work budget"):
    val source = """
      let value = (((((((1, 2), 3), 4), 5), 6), 7), 8);
    """

    measuredClean(source)(assertNestedTuples)

  test("nested first tuple elements in a function retain element order within the work budget"):
    val source = """
      fn value() = (((((((1, 2), 3), 4), 5), 6), 7), 8);;
    """

    measuredClean(source)(assertNestedTuples)

  test("selected member groups retain tuple qualifiers and the annotation within the work budget"):
    val source = """
      let value =
        ((((((((left, right).head).value).next).next).next).next).next).tail: Int;
    """

    measuredClean(source)(assertSelectedGroups(source, _))

  test("selected function groups retain tuple qualifiers and annotations within the work budget"):
    val source = """
      fn value() =
        ((((((((left, right).head).value).next).next).next).next).next).tail: Int;
      ;
    """

    measuredClean(source)(assertSelectedGroups(source, _))

  List(
    "member" -> "let value = item: Int: String; let after = 77;",
    "function" -> "fn value() = item: Int: String;; let after = 77;"
  ).foreach: (owner, source) =>
    test(s"a duplicate plain-reference annotation in a $owner retains its error and next binding"):
      parseNotFailed(source).map: module =>
        val info = SourceInfo(source)
        val checked = ParsingErrorChecker.checkModule(
          CompilerState.empty(module, info, CompilerConfig.default)
        )
        assertEquals(checked.errors.size, 1)
        assertEquals(checked.diagnostics.records.size, 1)

        val bindings = module.members.collect:
          case binding: Bnd => binding
        assertEquals(bindings.map(_.name), List("value", "after"))
        assertEquals(integers(bindings.last.value), List(77L))

        val errors = TermTraversal.collect(bindings.head.value):
          case error: ParseTermError => error
        assertEquals(errors.size, 1)
        val start = source.indexOf(": String")
        val end   = source.indexOf(';')
        assertEquals(errors.head.failedCode, Some(": String"))
        assertEquals(errors.head.spanOpt, Some(SrcSpan(info.pointAt(start), info.pointAt(end))))

        val references = TermTraversal.collect(bindings.head.value):
          case reference: Ref => reference
        assertEquals(references.map(_.name), List("item"))
        references.head.typeAsc match
          case Some(annotation: TypeRef) => assertEquals(annotation.name, "Int")
          case other => fail(s"Expected the first reference annotation, found $other")

  test("thirty-two nested arithmetic terms parse cleanly within a bounded process deadline"):
    val source = """
      fn value(): Int =
        1 + (2 + (3 + (4 + (5 + (6 + (7 + (8 +
          (9 + (10 + (11 + (12 + (13 + (14 + (15 + (16 +
            (17 + (18 + (19 + (20 + (21 + (22 + (23 + (24 +
              (25 + (26 + (27 + (28 + (29 + (30 + (31 + 32
        ))))))))))))))))))))))))))))));
      ;
    """

    ParserDeadline
      .run(List("parse", source), 5.seconds)
      .map: result =>
        assert(!result.timedOut, result.output)
        assert(result.stopped, result.output)
        assertEquals(result.exitCode, Some(0), result.output)
