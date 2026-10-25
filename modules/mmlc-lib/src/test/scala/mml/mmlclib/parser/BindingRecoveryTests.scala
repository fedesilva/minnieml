package mml.mmlclib.parser

import cats.effect.IO
import cats.syntax.all.*
import fastparse.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.test.BaseEffFunSuite

import scala.concurrent.duration.*

import MmlWhitespace.*

class BindingRecoveryTests extends BaseEffFunSuite:

  private def bindings(module: Module): List[Bnd] =
    module.members.collect { case binding: Bnd => binding }

  private def lambda(binding: Bnd): Lambda =
    binding.value.terms match
      case (value: Lambda) :: Nil => value
      case other => fail(s"Expected one lambda, found $other")

  private def errors(term: Term): List[ParseTermError] =
    TermTraversal.collect(term) { case error: ParseTermError => error }

  private def references(term: Term): List[Ref] =
    TermTraversal.collect(term) { case ref: Ref => ref }

  private def integers(term: Term): List[Long] =
    TermTraversal.collect(term) { case value: LiteralInt => value.value }

  private def assertError(source: String, error: ParseTermError, start: Int, end: Int): Unit =
    val text = source.substring(start, end)
    assertEquals(
      error.spanOpt,
      Some(SrcSpan(SourceInfo(source).pointAt(start), SourceInfo(source).pointAt(end)))
    )
    assertEquals(error.failedCode, Option.when(text.nonEmpty)(text))

  private def parseRecovered(source: String, roots: Int): IO[Module] =
    parseNotFailed(source).map { module =>
      val checked = ParsingErrorChecker.checkModule(
        CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      )
      assertEquals(checked.errors.size, roots)
      assertEquals(checked.diagnostics.records.size, roots)
      assertEquals(
        bindings(module).flatMap(binding =>
          TermTraversal.collect(binding.value) { case invalid: InvalidExpression => invalid }
        ),
        Nil
      )
      module
    }

  List("let broken = 1 @; let after = 7;", "fn broken(x: Int): Int = x + 1 @;; let after = 7;")
    .foreach { source =>
      test(s"malformed body suffix retains the prefix and following member: $source") {
        parseRecovered(source, 1).map { module =>
          val values = bindings(module)
          assertEquals(values.size, 2)
          val body =
            if values.head.meta.isDefined then lambda(values.head).body else values.head.value
          body.terms match
            case (prefix: Expr) :: (error: ParseTermError) :: Nil =>
              assertEquals(integers(prefix), List(1L))
              assertEquals(error.message, "Invalid expression syntax")
              assertError(source, error, source.indexOf('@'), source.indexOf(';'))
            case other => fail(s"Expected retained expression and terminal error: $other")
          assertEquals(integers(values(1).value), List(7L))
        }
      }
    }

  List("let broken 1; let after = 7;", "fn broken(x: Int): Int x + 1;; let after = 7;")
    .foreach { source =>
      test(s"missing equals keeps an analyzable body: $source") {
        parseRecovered(source, 1).map { module =>
          val values = bindings(module)
          assertEquals(values.size, 2)
          val error = errors(values.head.value).head
          assertEquals(error.message, "Expected '='")
          val bodyStart =
            if source.startsWith("fn") then source.indexOf("x + 1") else source.indexOf('1')
          assertError(source, error, bodyStart, bodyStart)
          assertEquals(integers(values.head.value), List(1L))
          if source.startsWith("fn") then
            assertEquals(lambda(values.head).params.size, 1)
            assertEquals(references(lambda(values.head).body).map(_.name), List("x", "+"))
            assert(values.head.meta.isDefined)
        }
      }
    }

  test("missing equals after a malformed written result retains both independent failures") {
    val source = "fn broken(x: Int): wrong;; let after = 7;"
    parseNotFailed(source).map { module =>
      val value = bindings(module).head
      assert(value.typeAsc.exists(_.isInstanceOf[ParseTypeError]))
      assert(errors(value.value).exists(_.message == "Expected '='"))
      assertEquals(bindings(module).size, 2)
    }
  }

  test("incomplete parameter lists retain only actual prefix scope and no callable metadata") {
    val source = "fn broken(x: Int, ) = x;; let after = 7;"
    parseRecovered(source, 1).map { module =>
      val binding = bindings(module).head
      val value   = lambda(binding)
      assertEquals(value.params.map(_.name), List("x"))
      assertEquals(references(value.body).map(_.name), List("x"))
      assertEquals(binding.meta, None)
      assertEquals(binding.typeSpec, None)
      assertEquals(binding.typeAsc, None)
      assertEquals(value.typeSpec, None)
      val error = errors(value.body).head
      assertEquals(error.message, "Invalid parameter list")
      assertError(source, error, source.indexOf(','), source.indexOf(')'))
    }
  }

  test("an incomplete parameter list without a real prefix never invents a nullary lambda") {
    val source = "fn broken(, ) = 1;; let after = 7;"
    parseRecovered(source, 1).map { module =>
      val value = bindings(module).head
      assertEquals(TermTraversal.collect(value.value) { case lambda: Lambda => lambda }, Nil)
      assertEquals(value.meta, None)
      assertEquals(value.typeAsc, None)
      assertEquals(integers(value.value), List(1L))
      assertEquals(bindings(module).size, 2)
    }
  }

  List("Bad: String", "@ [nested").foreach { failedParameter =>
    test(s"parameter-tail recovery retains the body after '$failedParameter'") {
      val closing = if failedParameter.startsWith("Bad") then "): Int " else ""
      val source  = s"fn broken(x: Int, $failedParameter$closing= x;; let after = 7;"
      parseRecovered(source, 1).map { module =>
        val values = bindings(module)
        val value  = lambda(values.head)
        assertEquals(value.params.map(_.name), List("x"))
        assertEquals(references(value.body).map(_.name), List("x"))
        assertEquals(values.head.meta, None)
        assertEquals(values.head.typeAsc, None)
        val end = if closing.nonEmpty then source.indexOf(')') else source.indexOf("= x")
        assertError(source, errors(value.body).head, source.indexOf(','), end)
        assertEquals(integers(values(1).value), List(7L))
        if closing.nonEmpty then assert(value.typeAsc.exists(_.isInstanceOf[TypeRef]))
      }
    }
  }

  test("a missing parameter list retains the named failed body") {
    val source = "fn broken = 1;; let after = 7;"
    parseRecovered(source, 1).map { module =>
      val value = bindings(module).head
      assertEquals(TermTraversal.collect(value.value) { case lambda: Lambda => lambda }, Nil)
      assertEquals(errors(value.value).map(_.message), List("Expected '('"))
      assertEquals(integers(value.value), List(1L))
    }
  }

  List("" -> 2, ";" -> 1).foreach { case (bodyTerminator, rootCount) =>
    test(s"a function retains distinct body and declaration terminators after '$bodyTerminator'") {
      val source = s"fn broken(x: Int): Int = x$bodyTerminator /* next */ let after = 7;"
      parseRecovered(source, rootCount).map { module =>
        val values = bindings(module)
        assertEquals(values.size, 2)
        assertEquals(references(lambda(values.head).body).map(_.name), List("x"))
        val found = errors(values.head.value)
        assertEquals(found.map(_.message), List.fill(rootCount)("Expected ';'"))
        found.foreach(error =>
          assertError(source, error, source.indexOf("/*"), source.indexOf("/*"))
        )
        assertEquals(values(1).docComment.map(_.text.trim), Some("next"))
        assertEquals(integers(values(1).value), List(7L))
      }
    }
  }

  test("invalid local names retain the precise spelling and token span") {
    val source = "fn example(): Int = let 123invalid = 1; 2;;"
    parseRecovered(source, 1).map { module =>
      val error = errors(bindings(module).head.value).head
      assertEquals(error.message, "Invalid identifier '123invalid'")
      val start = source.indexOf("123invalid")
      assertError(source, error, start, start + "123invalid".length)
      assertEquals(integers(bindings(module).head.value), List(2L, 1L))
    }
  }

  test("local initializers preserve independent continuations through malformed terms") {
    val source = "fn example(): Int = let broken = missing @; 3 + 4;;"
    parseRecovered(source, 1).map { module =>
      lambda(bindings(module).head).body.terms match
        case (application: App) :: Nil =>
          assertEquals(references(application.arg).map(_.name), List("missing"))
          assertEquals(errors(application.arg).size, 1)
          application.fn match
            case scope: Lambda =>
              assertEquals(scope.params.size, 1)
              assertEquals(integers(scope.body), List(3L, 4L))
              assertEquals(errors(scope.body), Nil)
            case other => fail(s"Expected scope lambda: $other")
        case other => fail(s"Expected local binding application: $other")
    }
  }

  List("", "Broken").foreach { name =>
    test(s"nameless or invalid local '$name' retains both sides through a synthetic statement") {
      val source = s"fn example(): Int = let $name = missing; 3 + 4;;"
      parseRecovered(source, 1).map { module =>
        lambda(bindings(module).head).body.terms match
          case (application: App) :: Nil =>
            assertEquals(application.spanOpt.map(_.start.index), Some(source.indexOf("let")))
            assertEquals(references(application.arg).map(_.name), List("missing"))
            assertEquals(errors(application.arg).size, 1)
            application.fn match
              case scope: Lambda =>
                assert(scope.params.forall(_.source == SourceOrigin.Synth))
                assertEquals(integers(scope.body), List(3L, 4L))
                assertEquals(errors(scope.body), Nil)
              case other => fail(s"Expected synthetic statement scope: $other")
          case other => fail(s"Expected statement application: $other")
      }
    }
  }

  List("let = 1;", "let Broken = 1;", "fn (x: Int) = x;;", "fn Broken(x: Int) = x;;")
    .foreach { failed =>
      test(s"nameless member retains its exact declaration and following member: $failed") {
        val source = s"$failed let after = 7;"
        parseFailedWithErrors(source).map { found =>
          assertEquals(found.size, 1)
          assertEquals(found.head.failedCode, Some(failed))
          val (_, parsed) = Parser.parseModuleWithInfo(source, "Test")
          val module      = parsed.getOrElse(fail("Expected recovered module"))
          assertEquals(bindings(module).size, 1)
          assertEquals(integers(bindings(module).head.value), List(7L))
        }
      }
    }

  test("malformed statement frames retain later statements and nested grouped lambda scope") {
    val source = "fn main(): Int = let add = { a: Int, b: Int -> a + b }; " +
      "(apply { b: Int -> add b @; 8 } 1); 3 + 4;; let after = 7;"
    parseRecovered(source, 1).map { module =>
      val main = lambda(bindings(module).head)
      assertEquals(integers(main.body), List(3L, 4L, 8L, 1L))
      val grouped = TermTraversal.collect(main.body) { case group: TermGroup => group }
      assertEquals(grouped.size, 1)
      val nested = TermTraversal.collect(grouped.head) {
        case value: Lambda if value.params.exists(_.name == "b") => value
      }
      assertEquals(nested.size, 1)
      assertEquals(references(nested.head.body).map(_.name), List("add", "b"))
      val error = errors(main.body).head
      assertError(source, error, source.indexOf('@'), source.indexOf("; 8"))
      assertEquals(bindings(module).size, 2)
    }
  }

  test("frame recovery masks nested delimiters, strings, and comments") {
    val source =
      "fn bad() = 1 @ (\"; then }\" /* ; /* nested */ else */ [2; 3]); 4;; let after = 7;"
    parseRecovered(source, 1).map { module =>
      val body = lambda(bindings(module).head).body
      assertEquals(integers(body), List(4L, 1L))
      assertError(source, errors(body).head, source.indexOf('@'), source.indexOf("; 4"))
      assertEquals(bindings(module).size, 2)
    }
  }

  List(")", "]", "}", ",", "then", "elif", "else").foreach { boundary =>
    test(s"a recovered frame leaves its enclosing '$boundary' boundary untouched") {
      val source = s"1 @ $boundary"
      val info   = SourceInfo(source)
      def frame(using P[Any]): P[(Expr, Int)] = P(exprNoSeqP(info) ~ Index)

      parse(source, frame(using _)) match
        case Parsed.Success((expr, end), _) =>
          assertEquals(end, source.indexOf(boundary))
          assertError(source, errors(expr).head, source.indexOf('@'), source.indexOf(boundary))
        case failure: Parsed.Failure => fail(failure.trace().longMsg)
    }
  }

  test("valid functions preserve modifiers, spans, local lowering, and tuple alternatives") {
    val source = "pub inline fn add(a: Int, b: Int): Int = a + b;; " +
      "fn main(): Int = fn ~local(x: Int): Int = x;; let pair = ({ y: Int -> y }, 2); local 3;;"
    parseRecovered(source, 0).map { module =>
      val values = bindings(module)
      assertEquals(values.head.visibility, Visibility.Public)
      assert(values.head.meta.exists(_.inlineHint))
      assertEquals(
        values.head.spanOpt.map(s => source.substring(s.start.index, s.end.index)),
        Some("add")
      )
      assertEquals(lambda(values.head).params.map(_.name), List("a", "b"))
      val local = TermTraversal.collect(values(1).value) {
        case value: Lambda if value.isMove => value
      }
      assertEquals(local.size, 1)
      assertEquals(local.head.params.map(_.name), List("x"))
      val tuples = TermTraversal.collect(values(1).value) { case tuple: Tuple => tuple }
      assertEquals(tuples.size, 1)
      assertEquals(tuples.head.elements.size, 2)
    }
  }

  test("binding truncations terminate in bounded child processes") {
    List(
      "let",
      "let =",
      "fn",
      "fn broken(",
      "fn broken(x: Int,",
      "fn broken() =",
      "fn outer() = let x ="
    )
      .traverse_ { source =>
        ParserDeadline.run(List("parse", source), 5.seconds).map { result =>
          assert(!result.timedOut, s"$source: ${result.output}")
          assert(result.stopped, result.output)
          assert(result.exitCode.exists(code => code == 10 || code == 11), result.output)
        }
      }
  }
