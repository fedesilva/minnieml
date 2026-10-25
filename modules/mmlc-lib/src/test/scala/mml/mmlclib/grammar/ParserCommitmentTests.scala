package mml.mmlclib.grammar

import cats.effect.IO
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.parser.{ParserDeadline, SourceInfo}
import mml.mmlclib.test.BaseEffFunSuite

import scala.concurrent.duration.*

class ParserCommitmentTests extends BaseEffFunSuite:

  private val groupedCall = """
    fn add(a: Int, b: Int): Int = a + b;;
    fn example(): Int = { a: Int -> add ({ b: Int -> add b; } a 2); } 1 3;;
  """

  private def parseClean(source: String): IO[Module] =
    parseNotFailed(source).map { module =>
      val state = ParsingErrorChecker.checkModule(
        CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      )
      assertEquals(state.errors, Vector.empty)
      module
    }

  private def terms[A](module: Module)(query: PartialFunction[Term, A]): List[A] =
    module.members
      .collect { case binding: Bnd => binding.value }
      .flatMap(TermTraversal.collect(_)(query))

  test("a grouped lambda application remains an argument with its exact source span") {
    parseClean(groupedCall).map { module =>
      val groups = terms(module) { case group: TermGroup => group }
      assertEquals(groups.size, 1)
      val group = groups.head
      val source =
        group.spanOpt.map(span => groupedCall.substring(span.start.index, span.end.index))
      assertEquals(source, Some("({ b: Int -> add b; } a 2)"))
      assertEquals(group.inner.terms.size, 3)
      assert(group.inner.terms.head.isInstanceOf[Lambda])
      assert(group.inner.terms(1).isInstanceOf[Ref])
      assertEquals(group.inner.terms.collect { case value: LiteralInt => value.value }, List(2L))
    }
  }

  test("grouped lambda applications preserve lexical parameter identities") {
    val localBinding = """
      fn add(a: Int, b: Int): Int = a + b;;
      fn example(): Int =
        { a: Int ->
          let inner = { b: Int -> add b; } a 2;
          add inner;
        } 1 3;
      ;
    """
    for
      grouped <- semNotFailed(groupedCall)
      local <- semNotFailed(localBinding)
    yield List(grouped, local).foreach { module =>
      val parameterId = resolvedBindingId(module, "example", "b")
      val uses = terms(module) { case ref: Ref if ref.resolvedId.contains(parameterId) => ref }
      assert(uses.nonEmpty)
      assert(uses.forall(_.typeSpec.isDefined))
    }
  }

  List("", "1;", "1; 2;", "let a = 1;").foreach { prefix =>
    test(s"a grouped lambda after '$prefix' does not require a selection suffix") {
      val source = s"pub fn main(): Unit = $prefix ({ n: Int -> n });;"
      parseClean(source).map { module =>
        val groups = terms(module) { case group: TermGroup => group }
        assertEquals(groups.size, 1)
        assertEquals(groups.head.inner.terms.count(_.isInstanceOf[Lambda]), 1)
      }
    }
  }

  test("tuple alternatives remain reachable after a lambda inside parentheses") {
    parseClean("let pair = ({ x -> x }, { y -> y });").map { module =>
      val tuples = terms(module) { case tuple: Tuple => tuple }
      assertEquals(tuples.size, 1)
      assertEquals(tuples.head.elements.size, 2)
      assertEquals(terms(module) { case lambda: Lambda => lambda }.size, 2)
    }
  }

  test("genuine dotted selections retain the grouped qualifier and field span") {
    val source = "let value = ({ x -> x }).field;"
    parseClean(source).map { module =>
      val selections = terms(module) { case ref: Ref if ref.qualifier.isDefined => ref }
      assertEquals(selections.size, 1)
      val selection = selections.head
      assert(selection.qualifier.exists(_.isInstanceOf[TermGroup]))
      assertEquals(selection.name, "field")
      assertEquals(selection.spanOpt.map(_.end.index), Some(source.indexOf(';')))
    }
  }

  test("plain term annotations retain their spans when no selection follows") {
    val source = "let value = item: Int;"
    parseClean(source).map { module =>
      val refs = terms(module) { case ref: Ref => ref }
      assertEquals(refs.size, 1)
      assert(refs.head.typeAsc.exists(_.isInstanceOf[TypeRef]))
      assertEquals(refs.head.spanOpt.map(_.start.index), Some(source.indexOf("item")))
      assertEquals(refs.head.spanOpt.map(_.end.index), Some(source.indexOf(':')))
    }
  }

  test("a missing group delimiter remains a parse error and terminates") {
    ParserDeadline
      .run(List("parse", "let value = ({ x -> x };\nlet next = 2;"), 5.seconds)
      .map { result =>
        assert(!result.timedOut, result.output)
        assert(result.stopped, result.output)
        assert(result.exitCode.exists(code => code == 10 || code == 11), result.output)
      }
  }
