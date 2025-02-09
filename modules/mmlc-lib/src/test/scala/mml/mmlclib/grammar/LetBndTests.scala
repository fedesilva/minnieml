package mml.mmlclib.grammar

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*
import org.neo4j.internal.helpers.Strings.prettyPrint

class LetBndTests extends BaseEffFunSuite:

  test("simple let") {

    modNotFailed("""
      module A =
        let a = 1;
        let b = 2;
        let c = "tres";
      ;
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
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
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
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
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
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
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
          s"Expected 3 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
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
          s"Expected 2 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
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
          s"Expected 2 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
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
          s"Expected 2 terms but got ${bnd.value.terms.size} : ${prettyPrint(bnd)}"
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
          s"Expected 3 terms but got ${bnd.value.terms.size}: ${prettyPrint(bnd)}"
        )
      case _ => fail("Expected a binding")
    }
  }
