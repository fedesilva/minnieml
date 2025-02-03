package mml.mmlclib.grammar

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*
import org.neo4j.internal.helpers.Strings.prettyPrint

class BasicTests extends BaseFunSuite:

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

  test("simple fn") {
    modNotFailed(
      """
        module A =
          fn sum a b = a sum b;
        ;
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
          s"Expected 2 params but got ${fn.params.size}: ${prettyPrint(fn)} "
        )
        assert(
          fn.body.terms.size == 3,
          s"Expected 3 terms but got ${fn.body.terms.size}: ${prettyPrint(fn)} "
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
         fn sum a b = a + b;
         let x = sum a b;
       ;
       """
    )
  }

  test("app with id and lit") {
    modNotFailed(
      """
        let a = b + 3
      """
    )
  }

  test("fn and let") {
    modNotFailed(
      """
        let a = 1
        let b = 2
        fn sum a b = a + b
        let x = sum a b
      """
    )
  }
