package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.*

class TupleTests extends BaseEffFunSuite:

  test("let with tuple") {
    parseNotFailed(
      """
        let a = (2,2);
      """
    ).map { case m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 1,
          s"Expected 1 terms, got ${bnd.value.terms.size}:\n ${prettyPrintAst(bnd)}"
        )
        bnd.value.terms.head
      case _ => fail("Expected a binding")
    }.map {
      case tuple: Tuple =>
        assert(
          tuple.elements.size == 2,
          s"Expected 2 terms but got ${tuple.elements.size}:\n ${prettyPrintAst(tuple)}"
        )
      case x => fail(s"Expected a tuple, go ${prettyPrintAst(x)}")
    }
  }

  test("let with tuple - 3 elem") {
    parseNotFailed(
      """
          let a = (2, 2, 3);
        """
    ).map { case m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd =>
        assert(
          clue(bnd.value.terms.size) == clue(1),
          s"Expected 1 term, got ${bnd.value.terms.size}:\n ${prettyPrintAst(bnd)}"
        )
        bnd.value.terms.head
      case _ => fail("Expected a binding")
    }.map {
      case tuple: Tuple =>
        assert(
          clue(tuple.elements.size) == clue(3),
          s"Expected 2 elements got ${tuple.elements.size}:\n ${prettyPrintAst(tuple)}"
        )
      case x => fail(s"Expected a tuple, go ${prettyPrintAst(x)}")
    }
  }

  test("let with tuple - 3 elem - mixed types") {
    parseNotFailed(
      """
            let a = (2, "2", 3);
          """
    ).map { case m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd =>
        assert(
          clue(bnd.value.terms.size) == clue(1),
          s"Expected 1 term, got ${bnd.value.terms.size}:\n ${prettyPrintAst(bnd)}"
        )
        bnd.value.terms.head
      case _ => fail("Expected a binding")
    }.map {
      case tuple: Tuple =>
        assert(
          clue(tuple.elements.size) == clue(3),
          s"Expected 2 elements got ${tuple.elements.size}:\n ${prettyPrintAst(tuple)}"
        )
      case x => fail(s"Expected a tuple, go ${prettyPrintAst(x)}")
    }
  }
