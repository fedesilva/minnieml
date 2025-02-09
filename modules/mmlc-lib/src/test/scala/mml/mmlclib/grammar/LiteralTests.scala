package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*
import org.neo4j.internal.helpers.Strings.prettyPrint

class LiteralTests extends BaseEffFunSuite:

  test("String Literal has correct typespec") {
    modNotFailed(
      """
        let a = "hello";
      """.stripMargin
    ).map { m =>
      prettyPrintAst(m)
      assert(m.members.head.isInstanceOf[Bnd])
      m.members.head
    }.map {
      case bnd: Bnd =>
        bnd.value.terms.head.typeSpec match
          case Some(LiteralStringType(_)) =>
          // pass
          case other =>
            fail(s"Expected `Some(LiteralStringType)`, got $other")
      case _ => fail("Expected a let")
    }
  }

  test("Int Literal has correct typespec") {
    modNotFailed(
      """
        let a = 1;
      """.stripMargin
    ).map { m =>
      assert(m.members.head.isInstanceOf[Bnd])
      m.members.head
    }.map {
      case bnd: Bnd =>
        bnd.value.terms.head.typeSpec match
          case Some(LiteralIntType(_)) =>
          // pass
          case other =>
            fail(s"Expected `Some(LiteralIntType)`, got $other")
      case _ => fail("Expected a let")
    }
  }

  test("Float literal has correct typespec") {
    modNotFailed(
      """
        let f = 1.0;
      """.stripMargin
    ).map { m =>
      assert(m.members.head.isInstanceOf[Bnd])
      m.members.head
    }.map {
      case bnd: Bnd =>
        bnd.value.terms.head.typeSpec match
          case Some(LiteralFloatType(_)) =>
          case other =>
            fail(s"Expected `Some(LiteralIntType)`, got $other \n ${prettyPrintAst(bnd)} ")
      case _ => fail("Expected a let")
    }
  }

  test("Bool literal has correct typespec") {
    modNotFailed(
      """
        let a = true;
      """.stripMargin
    ).map { m =>
      assert(m.members.head.isInstanceOf[Bnd])
      m.members.head
    }.map {
      case bnd: Bnd =>
        bnd.value.terms.head.typeSpec match
          case Some(LiteralBoolType(_)) =>
          case other =>
            fail(s"Expected `Some(LiteralBoolType)`, got $other")
      case _ => fail("Expected a let")
    }
  }

  test("Unit literal has correct typespec") {
    modNotFailed(
      """
        let a = ();
      """.stripMargin
    ).map { m =>
      assert(m.members.head.isInstanceOf[Bnd])
      m.members.head
    }.map {
      case bnd: Bnd =>
        bnd.value.terms.head.typeSpec match
          case Some(LiteralUnitType(_)) =>
          case other =>
            fail(s"Expected `Some(LiteralUnitType)`, got $other")
      case _ => fail("Expected a let")
    }
  }

  test("parses floats correctly") {
    modNotFailed(
      """
        let a = 1.0;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd =>
        assert(
          clue(bnd.value.terms.size) == clue(1),
          s"Expected 1 term but got ${bnd.value.terms.size}: ${prettyPrint(bnd)}"
        )
        assert(bnd.value.terms.head.isInstanceOf[LiteralFloat])
      case _ => fail("Expected a binding")
    }
  }
