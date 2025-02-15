package mml.mmlclib.grammar

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*

class LetBndTests extends BaseEffFunSuite:

  test("simple let") {

    modNotFailed("""
        let a = 1;
        let b = 2;
        let c = "tres";
      """).map(m => assert(m.members.size == 3))

  }

  test("let with app") {

    modNotFailed(
      """
      module A =
        let c = a sum b;
      ;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref") {

    modNotFailed(
      """
        module A =
          let c = a + b;
        ;
        """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref and NO SPACES") {

    modNotFailed(
      """
          module A =
            let c = a+b;
          ;
          """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with symbolic ref multichar and no spaces") {

    modNotFailed(
      """
            module A =
              let c = a++b;
            ;
            """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref prefix") {

    modNotFailed(
      """
        module A =
          let c = +b;
        ;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 2,
          s"Expected 2 terms but got ${bnd.value.terms.size} : ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref postfix") {

    modNotFailed(
      """
        module A =
          let c = b!;
        ;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 2,
          s"Expected 2 terms but got ${bnd.value.terms.size} : ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a let")
    }

  }

  test("let with app with symbolic ref mix") {
    modNotFailed(
      """
        module A =
          let c = 5! + 3;
        ;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 4,
          s"Expected 2 terms but got ${bnd.value.terms.size} : ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a let")
    }
  }

  test("let with hole for body") {
    modNotFailed(
      """
        let c = ???;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 1,
          s"Expected 1 term but got ${bnd.value.terms.size} : ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a let")
    }
  }

  test("app with id and lit") {
    modNotFailed(
      """
        let a = b + 3;
      """
    )
  }

  test("grouped expression") {
    modNotFailed(
      """
        let a = (1 + 2) * 3;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size}: ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a binding")
    }

    test("cant use a keyword as a name") {
      modFailed(
        """
          let let = 1;
        """
      )
    }

  }

  test("let with type name ascription") {
    modNotFailed(
      """
        let a: Int = 1;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 1,
          s"Expected 1 term but got ${bnd.value.terms.size}: ${prettyPrintAst(bnd)}"
        )
        bnd.typeAsc match
          case Some(ts: TypeName) => assert(clue(ts.name) == clue("Int"))
          case _ => fail("Expected a type ascription but got ${prettyPrintAst(bnd.typeAsc)}")

      case x => fail(s"Expected a binding but got: ${prettyPrintAst(x)}")
    }
  }

  test("let with 2 minus 2") {
    modNotFailed(
      """
        let a = 2 - 2;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd =>
        assert(
          bnd.value.terms.size == 3,
          s"Expected 3 terms but got ${bnd.value.terms.size}:\n ${prettyPrintAst(bnd)}"
        )
      case _ => fail("Expected a binding")
    }
  }
