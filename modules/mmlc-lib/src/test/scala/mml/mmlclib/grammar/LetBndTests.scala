package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.*

class LetBndTests extends BaseEffFunSuite:

  test("simple let") {

    parseNotFailed("""
        let a = 1;
        let b = 2;
        let c = "tres";
      """).map(m => assert(m.members.size == 3))

  }

  test("let with app") {

    parseNotFailed(
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

    parseNotFailed(
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

    parseNotFailed(
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

    parseNotFailed(
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

    parseNotFailed(
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

    parseNotFailed(
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
    parseNotFailed(
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
    parseNotFailed(
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
    parseNotFailed(
      """
        let a = b + 3;
      """
    )
  }

  test("grouped expression") {
    parseNotFailed(
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
      parseFailed(
        """
          let let = 1;
        """
      )
    }

  }

  test("let with type name ascription") {
    parseNotFailed(
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
          case Some(ts: TypeRef) => assert(clue(ts.name) == clue("Int"))
          case _ => fail("Expected a type ascription but got ${prettyPrintAst(bnd.typeAsc)}")

      case x => fail(s"Expected a binding but got: ${prettyPrintAst(x)}")
    }
  }

  test("let with 2 minus 2") {
    parseNotFailed(
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

  test("let with invalid identifier should create ParsingIdError") {
    parseFailedWithErrors(
      """
        let 123invalid = 5;
      """
    ).map { errors =>
      assert(errors.size == 1, s"Expected 1 error but got ${errors.size}")
      errors.head match {
        case error: ParsingIdError =>
          assert(
            error.invalidId == "123invalid",
            s"Expected '123invalid' but got '${error.invalidId}'"
          )
          assert(
            error.message.contains("Invalid identifier"),
            s"Error message should explain the rules: ${error.message}"
          )
          assert(
            error.message.contains("lowercase letter"),
            s"Error message should explain binding rules: ${error.message}"
          )
        case other =>
          fail(s"Expected ParsingIdError but got: ${other.getClass.getSimpleName}")
      }
    }
  }
