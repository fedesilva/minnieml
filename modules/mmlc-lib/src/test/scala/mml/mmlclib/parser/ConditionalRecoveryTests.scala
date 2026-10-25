package mml.mmlclib.parser

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.parser.ParserDeadlineProbe.{ConditionalOutcome, Structure}
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite

import scala.concurrent.duration.*

class ConditionalRecoveryTests extends BaseEffFunSuite:

  private def binding(module: Module, name: String = "value"): Bnd =
    module.members
      .collectFirst:
        case value: Bnd if value.name == name => value
      .getOrElse(fail(s"Expected binding $name"))

  private def conditional(value: Expr): Cond = value.terms match
    case (result: Cond) :: Nil => result
    case other => fail(s"Expected one conditional, found $other")

  private def function(value: Expr): Lambda = value.terms match
    case (result: Lambda) :: Nil => result
    case other => fail(s"Expected one lambda, found $other")

  private def grouped(value: Expr): Expr = value.terms match
    case (result: TermGroup) :: Nil => result.inner
    case other => fail(s"Expected one group, found $other")

  private def firstOfTuple(value: Expr, sibling: Long): Expr = value.terms match
    case (tuple: Tuple) :: Nil =>
      assertEquals(tuple.elements.size, 2)
      assertEquals(integers(tuple.elements.last), List(sibling))
      tuple.elements.head

    case other => fail(s"Expected tuple, found $other")

  private def errors(value: Term): List[ParseTermError] =
    TermTraversal.collect(value):
      case error: ParseTermError => error

  private def integers(value: Term): List[Long] =
    TermTraversal
      .collect(value):
        case literal: LiteralInt => literal
      .sortBy(_.spanOpt.map(_.start.index))
      .map(_.value)

  private def booleans(value: Expr): List[Boolean] =
    value.terms.collect:
      case literal: LiteralBool => literal.value

  private def span(source: String, start: Int, end: Int): SrcSpan =
    val info = SourceInfo(source)
    SrcSpan(info.pointAt(start), info.pointAt(end))

  private def startOf(source: String, fragment: String): Int =
    val start = source.indexOf(fragment)
    assert(start >= 0, s"Missing fixture fragment: $fragment")
    start

  private def assertError(
    source:  String,
    error:   ParseTermError,
    start:   Int,
    end:     Int,
    message: String = "Invalid expression syntax"
  ): Unit =
    assertEquals(error.spanOpt, span(source, start, end).some)
    assertEquals(error.failedCode, Option.when(start < end)(source.substring(start, end)))
    assertEquals(error.message, message)

  private def check(source: String, module: Module): CompilerState =
    ParsingErrorChecker.checkModule(
      CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
    )

  private def parseClean(source: String): IO[Module] =
    parseNotFailed(source).map: module =>
      assertEquals(check(source, module).errors.size, 0)
      module

  private def assertRoots(source: String, parsed: Module, expected: Int): Unit =
    val original = errors(binding(parsed).value)
    val checked  = check(source, parsed)
    val stamped  = errors(binding(checked.module).value)
    val reported = checked.semanticErrors.collect:
      case SemanticError.ParseTermErrorFound(error, _) => error

    assertEquals(original.size, expected)
    assert(original.forall(_.causes.isEmpty))
    assertEquals(checked.errors.size, expected)
    assertEquals(reported.toList, original)
    assertEquals(stamped.map(_.copy(causes = Vector.empty)), original)
    assertEquals(stamped.map(_.causes.size), List.fill(expected)(1))
    assertEquals(stamped.flatMap(_.causes).distinct.size, expected)
    assertEquals(stamped.flatMap(_.causes).toVector, checked.diagnostics.records.map(_.id))
    assertEquals(
      checked.diagnostics.records.map(_.relation),
      Vector.fill(expected)(DiagnosticRelation.Primary)
    )

  private def assertUnitType(value: Option[Type], expectedSpan: SrcSpan): Unit =
    assertEquals(value, TypeRef(expectedSpan, "Unit").some)

  private def assertImplicitElse(root: Cond, clauses: List[Cond], last: Cond): Unit =
    val rootSpan = root.spanOpt.getOrElse(fail("Expected conditional span"))
    val unitSpan = SrcSpan(rootSpan.end, rootSpan.end)
    assertUnitType(root.typeAsc, rootSpan)
    assertUnitType(root.typeSpec, rootSpan)
    clauses.foreach: clause =>
      assertEquals(clause.typeAsc, None)
      assertUnitType(clause.typeSpec, rootSpan)

    assertEquals(last.ifFalse.spanOpt, unitSpan.some)
    assertEquals(last.ifFalse.typeAsc, None)
    assertEquals(last.ifFalse.typeSpec, None)
    assertEquals(last.ifFalse.terms, List(LiteralUnit(unitSpan)))

  private def sequence(value: Expr): (Expr, Expr) = value.terms match
    case (app: App) :: Nil =>
      app.fn match
        case scope: Lambda =>
          assertEquals(scope.params.size, 1)
          assertEquals(scope.params.head.source, SourceOrigin.Synth)
          (app.arg, scope.body)
        case other => fail(s"Expected statement continuation, found $other")
    case other => fail(s"Expected statement sequence, found $other")

  List(false, true).foreach: inLambda =>
    def wrap(fragment: String): String =
      if inLambda then s"let value = { $fragment };"
      else s"let value = $fragment;"

    def root(module: Module): Cond =
      val value = binding(module).value
      conditional(if inLambda then function(value).body else value)

    test(s"full conditional retains branches and source extent, expression context = $inLambda"):
      val fragment = "if true then 11;else 22;"
      val source   = wrap(fragment)
      parseClean(source).map: module =>
        val value = root(module)
        val start = startOf(source, fragment)
        assertEquals(value.spanOpt.map(_.start), SourceInfo(source).pointAt(start).some)
        assert(value.spanOpt.exists(_.end.index >= start + fragment.length))
        assertEquals(booleans(value.cond), List(true))
        assertEquals(integers(value.ifTrue), List(11L))
        assertEquals(integers(value.ifFalse), List(22L))
        assertEquals(value.typeAsc, None)
        assertEquals(value.typeSpec, None)

    test(s"single branch synthesizes exact Unit metadata, expression context = $inLambda"):
      val source = wrap("if true then ();\n  ")
      parseClean(source).map: module =>
        val value = root(module)
        assertImplicitElse(value, Nil, value)
        assertEquals(booleans(value.cond), List(true))
        assertEquals(value.ifTrue.terms.size, 1)
        assert(value.ifTrue.terms.head.isInstanceOf[LiteralUnit])

    List(false, true).foreach: explicitElse =>
      test(s"elif lowering retains clause spans, expression = $inLambda, else = $explicitElse"):
        val suffix = if explicitElse then "else 44;" else ""
        val source = wrap(
          "if true then 11;\n  elif false then 22;\n  elif true then 33;\n  " + suffix
        )
        parseClean(source).map: module =>
          val first   = root(module)
          val second  = conditional(first.ifFalse)
          val third   = conditional(second.ifFalse)
          val clauses = List(first, second, third)

          assertEquals(
            clauses.map(clause => integers(clause.ifTrue)),
            List(List(11L), List(22L), List(33L))
          )
          List(second, third).foreach: clause =>
            assertEquals(clause.spanOpt.map(_.start), clause.cond.spanOpt.map(_.start))
          assertEquals(second.spanOpt.map(_.start.index), startOf(source, "false then").some)
          assertEquals(third.spanOpt.map(_.start.index), startOf(source, "true then 33").some)
          assertEquals(first.ifFalse.spanOpt, second.spanOpt)
          assertEquals(second.ifFalse.spanOpt, third.spanOpt)

          if explicitElse then
            assert(clauses.forall(clause => clause.typeAsc.isEmpty && clause.typeSpec.isEmpty))
            assertEquals(integers(third.ifFalse), List(44L))
            val bodyEnd   = SourceInfo(source).pointAt(startOf(source, "44;") + 2)
            val clauseEnd = SourceInfo(source).pointAt(startOf(source, "44;") + 3)
            assertEquals(third.ifFalse.spanOpt.map(_.end), bodyEnd.some)
            clauses.foreach(clause => assertEquals(clause.spanOpt.map(_.end), clauseEnd.some))
          else assertImplicitElse(first, List(second, third), third)

  test("compact conditional spans exclude its enclosing group and member terminator"):
    val fragment = "if true then 11;elif false then 22;else 33;"
    val source   = s"let value=($fragment);"
    parseClean(source).map: module =>
      val value = conditional(grouped(binding(module).value))
      val start = startOf(source, fragment)
      assertEquals(value.spanOpt, span(source, start, start + fragment.length).some)

  test("expression conditions retain semicolon sequencing"):
    val source = "let value = { if (); true then 11; else 22; };"
    parseClean(source).map: module =>
      val value         = conditional(function(binding(module).value).body)
      val (first, last) = sequence(value.cond)
      assertEquals(first.terms.size, 1)
      assert(first.terms.head.isInstanceOf[LiteralUnit])
      assertEquals(booleans(last), List(true))
      assertEquals(integers(value), List(11L, 22L))

  test("member conditions do not accept expression-condition sequencing"):
    val source = "let value = if (); true then 11; else 22;;"
    justParse(source).map: module =>
      assert(check(source, module).errors.nonEmpty)

  test("both branches of a member conditional retain ordinary expression sequencing"):
    val source = "let value = if true then (); 11; else (); 22;;"
    parseClean(source).map: module =>
      val value = conditional(binding(module).value)
      List(value.ifTrue -> 11L, value.ifFalse -> 22L).foreach: (branch, expected) =>
        val (first, last) = sequence(branch)
        assertEquals(first.terms.size, 1)
        assert(first.terms.head.isInstanceOf[LiteralUnit])
        assertEquals(integers(last), List(expected))

  private case class MissingCase(
    name:     String,
    fragment: String,
    target:   Cond => Expr,
    boundary: String,
    retained: List[Long],
    message:  String = "Expected expression"
  )

  private val missingCases = List(
    MissingCase("if condition", "if then 11;else 22;", _.cond, "then", List(11L, 22L)),
    MissingCase(
      "elif condition",
      "if true then 11;elif then 22;else 33;",
      value => conditional(value.ifFalse).cond,
      "then 22",
      List(11L, 22L, 33L)
    ),
    MissingCase("if body", "if true then ;else 22;", _.ifTrue, ";else", List(22L)),
    MissingCase(
      "elif body",
      "if true then 11;elif false then ;else 33;",
      value => conditional(value.ifFalse).ifTrue,
      ";else",
      List(11L, 33L)
    ),
    MissingCase("else body", "if true then 11;else ;", _.ifFalse, ";)", List(11L)),
    MissingCase(
      "if semicolon",
      "if true then 11 else 22;",
      _.ifTrue,
      "else",
      List(11L, 22L),
      "Expected ';'"
    ),
    MissingCase(
      "elif semicolon",
      "if true then 11;elif false then 22 else 33;",
      value => conditional(value.ifFalse).ifTrue,
      "else",
      List(11L, 22L, 33L),
      "Expected ';'"
    ),
    MissingCase(
      "else semicolon",
      "if true then 11;else 22",
      _.ifFalse,
      ")",
      List(11L, 22L),
      "Expected ';'"
    ),
    MissingCase(
      "if then boundary",
      "if true 11 else 22;",
      _.ifTrue,
      "else",
      List(11L, 22L),
      "Expected 'then'"
    ),
    MissingCase(
      "elif then boundary",
      "if true then 11;elif false 22 else 33;",
      value => conditional(value.ifFalse).ifTrue,
      "else",
      List(11L, 22L, 33L),
      "Expected 'then'"
    )
  )

  missingCases.foreach: fixture =>
    test(s"missing ${fixture.name} retains its clause and one exact diagnostic root"):
      val source = s"let value=(${fixture.fragment});let after=77;"
      parseNotFailed(source).map: module =>
        val value        = conditional(grouped(binding(module).value))
        val targetErrors = errors(fixture.target(value))
        assertEquals(targetErrors.size, 1)
        val boundary = startOf(source, fixture.boundary)
        assertError(source, targetErrors.head, boundary, boundary, fixture.message)
        assertEquals(integers(value), fixture.retained)
        assertEquals(integers(binding(module, "after").value), List(77L))
        assertRoots(source, module, 1)

  List(" ", ";").foreach: separator =>
    test(s"missing then retains all condition terms and an optional semicolon: $separator"):
      val source = s"let value=(if true 11${separator}else 22;);"
      parseNotFailed(source).map: module =>
        val value = conditional(grouped(binding(module).value))
        assertEquals(value.cond.terms.size, 2)
        assert(value.cond.terms.head.isInstanceOf[LiteralBool])
        assertEquals(integers(value.cond), List(11L))
        assertEquals(value.ifTrue.terms.size, 1)
        val missing = errors(value.ifTrue)
        assertEquals(missing.size, 1)
        val boundary = startOf(source, if separator == ";" then ";else" else "else")
        assertError(source, missing.head, boundary, boundary, "Expected 'then'")
        assertEquals(integers(value.ifFalse), List(22L))
        assertRoots(source, module, 1)

  test("independent failures in initial, elif, and else clauses retain distinct roots"):
    val source = "let value=if then 11;elif false then ;else @bad;;let after=77;"
    parseNotFailed(source).map: module =>
      val first       = conditional(binding(module).value)
      val second      = conditional(first.ifFalse)
      val firstError  = errors(first.cond)
      val secondError = errors(second.ifTrue)
      val finalError  = errors(second.ifFalse)
      assertEquals(List(firstError.size, secondError.size, finalError.size), List(1, 1, 1))
      val missingCondition = startOf(source, "then 11")
      val missingBody      = startOf(source, ";else")
      val malformed        = startOf(source, "@bad")
      assertError(
        source,
        firstError.head,
        missingCondition,
        missingCondition,
        "Expected expression"
      )
      assertError(source, secondError.head, missingBody, missingBody, "Expected expression")
      assertError(source, finalError.head, malformed, malformed + "@bad".length)
      assertEquals(integers(first.ifTrue), List(11L))
      assertEquals(integers(binding(module, "after").value), List(77L))
      assertRoots(source, module, 3)

  test("a nested conditional owns its elif and else after a malformed outer branch fragment"):
    val source = "let value=if true then @bad if false then 11;" +
      "elif true then 22;else 33;;else 44;;let after=77;"
    parseNotFailed(source).map: module =>
      val outer = conditional(binding(module).value)
      val inner = outer.ifTrue.terms.collect:
        case value: Cond => value
      assertEquals(inner.size, 1)
      val alternate = conditional(inner.head.ifFalse)
      assertEquals(integers(inner.head.ifTrue), List(11L))
      assertEquals(integers(alternate.ifTrue), List(22L))
      assertEquals(integers(alternate.ifFalse), List(33L))
      assertEquals(integers(outer.ifFalse), List(44L))
      val malformed  = startOf(source, "@bad")
      val innerStart = startOf(source, "if false")
      assertError(source, errors(outer.ifTrue).head, malformed, innerStart)
      assertRoots(source, module, 1)

  test("a nested missing else body cannot consume the outer else clause"):
    val source = "let value=if true then if false then 11;else ;;else 44;;"
    parseNotFailed(source).map: module =>
      val outer   = conditional(binding(module).value)
      val inner   = conditional(outer.ifTrue)
      val missing = errors(inner.ifFalse)
      assertEquals(missing.size, 1)
      val boundary = startOf(source, ";;else")
      assertError(source, missing.head, boundary, boundary, "Expected expression")
      assertEquals(integers(inner.ifTrue), List(11L))
      assertEquals(integers(outer.ifFalse), List(44L))
      assertRoots(source, module, 1)

  List(
    "group" -> "let value=(if true then @bad;else 22;);let after=77;",
    "tuple" -> "let value=(if true then @bad;else 22;,33);let after=77;",
    "lambda" -> "let value={x: Int -> if true then @bad;else 22;};let after=77;"
  ).foreach: (owner, source) =>
    test(s"conditional recovery preserves its enclosing $owner delimiter"):
      parseNotFailed(source).map: module =>
        val value = binding(module).value
        val nested = owner match

          case "group" => grouped(value)

          case "tuple" => firstOfTuple(value, 33L)

          case "lambda" =>
            val lambda = function(value)
            assertEquals(lambda.params.size, 1)
            lambda.body

          case other => fail(s"Unexpected fixture owner $other")

        assertEquals(integers(conditional(nested).ifFalse), List(22L))
        assertEquals(integers(binding(module, "after").value), List(77L))
        assertRoots(source, module, 1)

  test("a recovered conditional inside a parameterized lambda leaves tuple alternatives reachable"):
    val source = "let value=({x: Int -> if true then ;else x;},33);"
    parseNotFailed(source).map: module =>
      val lambda = function(firstOfTuple(binding(module).value, 33L))
      assertEquals(lambda.params.size, 1)
      assertEquals(errors(conditional(lambda.body).ifTrue).size, 1)
      assertRoots(source, module, 1)

  test("strings and line comments preserve valid keyword-looking branch contents"):
    val source = "let value=if true // then elif else ;\n" +
      "then \"if then elif else ; () {}\";else \"else\";;"
    parseClean(source).map: module =>
      val value = conditional(binding(module).value)
      val strings = TermTraversal.collect(value):
        case literal: LiteralString => literal.value
      assertEquals(strings, List("if then elif else ; () {}", "else"))

  List(
    "@bad \"then elif else ; () {}\" ",
    "@bad // then elif else ; () {}\n  ",
    "@bad (/* then /* elif */ else ; () {} */) ",
    "@bad iffy "
  ).foreach: fragment =>
    test(s"failed condition recovery masks keywords inside $fragment"):
      val source = s"let value=if ${fragment}then 11;else 22;;let after=77;"
      parseNotFailed(source).map: module =>
        val value  = conditional(binding(module).value)
        val failed = errors(value.cond)
        assertEquals(failed.size, 1)
        val start = startOf(source, fragment)
        assertError(source, failed.head, start, start + fragment.length)
        assertEquals(integers(value.ifTrue), List(11L))
        assertEquals(integers(value.ifFalse), List(22L))
        assertEquals(integers(binding(module, "after").value), List(77L))
        assertRoots(source, module, 1)

  private case class TruncatedCase(
    fragment:        String,
    expected:        Int => Structure,
    diagnosticCount: Int
  )

  private def missingAtEnd(end: Int, message: String): Structure =
    Structure.ParseError((end, end).some, message, none[String])

  private def implicitUnitAtEnd(end: Int): List[Structure] =
    List(Structure.UnitValue((end, end).some))

  private val truncated = List(
    TruncatedCase(
      "if",
      end =>
        Structure.Conditional(
          List(missingAtEnd(end, "Expected expression")),
          List(missingAtEnd(end, "Expected 'then'")),
          implicitUnitAtEnd(end)
        ),
      3
    ),
    TruncatedCase(
      "if true",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(missingAtEnd(end, "Expected 'then'")),
          implicitUnitAtEnd(end)
        ),
      2
    ),
    TruncatedCase(
      "if true then",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(missingAtEnd(end, "Expected expression"), missingAtEnd(end, "Expected ';'")),
          implicitUnitAtEnd(end)
        ),
      3
    ),
    TruncatedCase(
      "if true then 11",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(Structure.Integer(11), missingAtEnd(end, "Expected ';'")),
          implicitUnitAtEnd(end)
        ),
      2
    ),
    TruncatedCase(
      "if true then 11;elif",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(Structure.Integer(11)),
          List(
            Structure.Conditional(
              List(missingAtEnd(end, "Expected expression")),
              List(missingAtEnd(end, "Expected 'then'")),
              implicitUnitAtEnd(end)
            )
          )
        ),
      3
    ),
    TruncatedCase(
      "if true then 11;elif false",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(Structure.Integer(11)),
          List(
            Structure.Conditional(
              List(Structure.Bool(false)),
              List(missingAtEnd(end, "Expected 'then'")),
              implicitUnitAtEnd(end)
            )
          )
        ),
      2
    ),
    TruncatedCase(
      "if true then 11;elif false then",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(Structure.Integer(11)),
          List(
            Structure.Conditional(
              List(Structure.Bool(false)),
              List(missingAtEnd(end, "Expected expression"), missingAtEnd(end, "Expected ';'")),
              implicitUnitAtEnd(end)
            )
          )
        ),
      3
    ),
    TruncatedCase(
      "if true then 11;elif false then 22",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(Structure.Integer(11)),
          List(
            Structure.Conditional(
              List(Structure.Bool(false)),
              List(Structure.Integer(22), missingAtEnd(end, "Expected ';'")),
              implicitUnitAtEnd(end)
            )
          )
        ),
      2
    ),
    TruncatedCase(
      "if true then 11;else",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(Structure.Integer(11)),
          List(missingAtEnd(end, "Expected expression"), missingAtEnd(end, "Expected ';'"))
        ),
      3
    ),
    TruncatedCase(
      "if true then 11;else 22",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(Structure.Integer(11)),
          List(Structure.Integer(22), missingAtEnd(end, "Expected ';'"))
        ),
      2
    ),
    TruncatedCase(
      "if true then if false then 11;else",
      end =>
        Structure.Conditional(
          List(Structure.Bool(true)),
          List(
            Structure.Conditional(
              List(Structure.Bool(false)),
              List(Structure.Integer(11)),
              List(missingAtEnd(end, "Expected expression"), missingAtEnd(end, "Expected ';'"))
            ),
            missingAtEnd(end, "Expected ';'")
          ),
          implicitUnitAtEnd(end)
        ),
      4
    )
  )

  truncated.foreach: fixture =>
    test(s"conditional truncation retains exact clauses and errors: ${fixture.fragment}"):
      val source = s"let value=${fixture.fragment}"
      val end    = source.length
      val expected = ConditionalOutcome(
        List(Structure.Binding(List(fixture.expected(end), missingAtEnd(end, "Expected ';'")))),
        fixture.diagnosticCount
      )
      ParserDeadline
        .run(List("conditional", source), 15.seconds)
        .map: result =>
          assert(!result.timedOut, result.output)
          assert(result.stopped, result.output)
          assertEquals(result.exitCode, 10.some, result.output)
          val summaries = result.output.linesIterator.filter(_.startsWith("{")).toList
          assertEquals(summaries.size, 1, result.output)
          assertEquals(upickle.default.read[ConditionalOutcome](summaries.head), expected)

  List(
    "let value={if (); true then",
    "let value=if @bad ( true then 11;else 22;",
    "let value=if true then @bad [ elif false then 22;else 33;",
    "let value=if true then @bad \"elif else ;",
    "let value=if true then @bad /* elif /* else */"
  ).foreach: source =>
    test(s"opaque unfinished fragments finish within a child-process deadline: $source"):
      ParserDeadline
        .run(List("parse", source), 15.seconds)
        .map: result =>
          assert(!result.timedOut, result.output)
          assert(result.stopped, result.output)
          assert(result.exitCode.exists(code => code == 10 || code == 11), result.output)
