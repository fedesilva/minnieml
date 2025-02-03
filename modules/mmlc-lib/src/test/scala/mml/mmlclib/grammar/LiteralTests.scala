package mml.mmlclib.grammar

import cats.syntax.all.*
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
          case Some(_: LiteralStringType.type) =>
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
          case Some(_: LiteralIntType.type) =>
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
          case Some(_: LiteralIntType.type) =>
          case other =>
            fail(s"Expected `Some(LiteralIntType)`, got $other")
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
          case Some(_: LiteralBoolType.type) =>
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
          case Some(_: LiteralUnitType.type) =>
          case other =>
            fail(s"Expected `Some(LiteralUnitType)`, got $other")
      case _ => fail("Expected a let")
    }
  }
