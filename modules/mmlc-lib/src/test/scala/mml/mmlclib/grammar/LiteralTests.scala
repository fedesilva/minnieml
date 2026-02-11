package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.*

class LiteralTests extends BaseEffFunSuite:

  test("String Literal has correct typespec") {
    parseNotFailed(
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
          case Some(TypeRef(_, "String", _, _)) =>
          // pass
          case other =>
            fail(s"Expected `Some(TypeRef(\"String\"))`, got $other")
      case _ => fail("Expected a let")
    }
  }

  test("Int Literal has correct typespec") {
    parseNotFailed(
      """
        let a = 1;
      """.stripMargin
    ).map { m =>
      assert(m.members.head.isInstanceOf[Bnd])
      m.members.head
    }.map {
      case bnd: Bnd =>
        bnd.value.terms.head.typeSpec match
          case Some(TypeRef(_, "Int", _, _)) =>
          // pass
          case other =>
            fail(s"Expected `Some(TypeRef(\"Int\"))`, got $other")
      case _ => fail("Expected a let")
    }
  }

  test("Float literal has correct typespec") {
    parseNotFailed(
      """
        let f = 1.0;
      """.stripMargin
    ).map { m =>
      assert(m.members.head.isInstanceOf[Bnd])
      m.members.head
    }.map {
      case bnd: Bnd =>
        bnd.value.terms.head.typeSpec match
          case Some(TypeRef(_, "Float", _, _)) =>
          case other =>
            fail(s"Expected `Some(TypeRef(\"Float\"))`, got $other \n ${prettyPrintAst(bnd)} ")
      case _ => fail("Expected a let")
    }
  }

  test("Bool literal has correct typespec") {
    parseNotFailed(
      """
        let a = true;
      """.stripMargin
    ).map { m =>
      assert(m.members.head.isInstanceOf[Bnd])
      m.members.head
    }.map {
      case bnd: Bnd =>
        bnd.value.terms.head.typeSpec match
          case Some(TypeRef(_, "Bool", _, _)) =>
          case other =>
            fail(s"Expected `Some(TypeRef(\"Bool\"))`, got $other")
      case _ => fail("Expected a let")
    }
  }

  test("Unit literal has correct typespec") {
    parseNotFailed(
      """
        let a = ();
      """.stripMargin
    ).map { m =>
      assert(m.members.head.isInstanceOf[Bnd])
      m.members.head
    }.map {
      case bnd: Bnd =>
        bnd.value.terms.head.typeSpec match
          case Some(TypeRef(_, "Unit", _, _)) =>
          case other =>
            fail(s"Expected `Some(TypeRef(\"Unit\"))`, got $other")
      case _ => fail("Expected a let")
    }
  }

  test("parses floats correctly") {
    parseNotFailed(
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
          s"Expected 1 term but got ${bnd.value.terms.size}: ${prettyPrintAst(bnd)}"
        )
        assert(bnd.value.terms.head.isInstanceOf[LiteralFloat])
      case _ => fail("Expected a binding")
    }
  }

  test("parses leading-dot float literals as a single float token") {
    parseNotFailed(
      """
        let x = .5;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd =>
        assert(
          clue(bnd.value.terms.size) == clue(1),
          s"Expected 1 term but got ${bnd.value.terms.size}: ${prettyPrintAst(bnd)}"
        )
        bnd.value.terms.head match
          case LiteralFloat(_, value) =>
            assertEqualsFloat(clue(value), clue(0.5f), clue(0.0001f))
          case other =>
            fail(s"Expected LiteralFloat, got: ${prettyPrintAst(other)}")
      case _ => fail("Expected a binding")
    }
  }

  test("parses leading-dot floats combined with stdlib float operator") {
    parseNotFailed(
      """
        let x = .5 +. .25;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd =>
        assertEquals(clue(bnd.value.terms.size), clue(3))
        bnd.value.terms match
          case List(LiteralFloat(_, left), ref: Ref, LiteralFloat(_, right)) =>
            assertEqualsFloat(clue(left), clue(0.5f), clue(0.0001f))
            assertEquals(clue(ref.name), clue("+."))
            assertEqualsFloat(clue(right), clue(0.25f), clue(0.0001f))
          case _ =>
            fail(s"Expected [LiteralFloat, Ref(+.), LiteralFloat], got: ${prettyPrintAst(bnd)}")
      case _ => fail("Expected a binding")
    }
  }
