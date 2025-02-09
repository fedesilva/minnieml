package mml.mmlclib.grammar

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*

class FnTests extends BaseEffFunSuite:

  test("simple fn") {
    modNotFailed(
      """
          fn sum (a b) = a sum b;
      """
    ).map { m =>
      {
        assert(m.members.size == 1)
        m.members.head
      }
    }.map {
      case fn: FnDef =>
        assert(
          fn.params.size == 2,
          s"Expected 2 params but got ${fn.params.size}: ${prettyPrintAst(fn)} "
        )
        assert(
          fn.body.terms.size == 3,
          s"Expected 3 terms but got ${fn.body.terms.size}: ${prettyPrintAst(fn)} "
        )
      case _ => fail("Expected a function")
    }
  }

  test("fn and let") {
    modNotFailed(
      """
       module A =
         let a = 1;
         let b = 2;
         fn sum (a b) = a + b;
         let x = sum a b;
       ;
       """
    )
  }

  test("app with id and lit") {
    modNotFailed(
      """        
        fn sum (a b) = b + 3;
      """
    )
  }

  test("Meh is accepted as a term") {
    modNotFailed(
      """        
          fn plusA (a) = sum a _;
        """
    )
  }

  test("fn with hole for body") {
    modNotFailed(
      """
        fn hole (h) = ???;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case fn: FnDef =>
        assert(
          fn.body.terms.size == 1,
          s"Expected 1 term but got ${fn.body.terms.size} : ${prettyPrintAst(fn)}"
        )
      case _ => fail("Expected a FnDef")
    }
  }

  test("fn with type spec") {
    modNotFailed(
      """
        fn sum (a: Int b: Int) = a + b;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case fn: FnDef =>
        assert(
          fn.params.size == 2,
          s"Expected 2 params but got ${fn.params.size}: ${prettyPrintAst(fn)} "
        )
        assert(
          fn.body.terms.size == 3,
          s"Expected 3 terms but got ${fn.body.terms.size}: ${prettyPrintAst(fn)} "
        )
        fn.params
      case _ => fail("Expected a function")
    }.map { params =>
      assert(
        params.forall(_.typeSpec.isDefined),
        s"Expected all params to have a type spec: ${params.map(p => prettyPrintAst(p))}"
      )
    }
  }

  test("grouped expression") {
    modNotFailed(
      """
        fn compute (a b) = 1 + (2 * 3);
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: FnDef =>
        assert(
          bnd.body.terms.size == 3,
          s"Expected a body with 3 terms but got ${bnd.body.terms.size}: ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a binding")
    }
  }
