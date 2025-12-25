package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.{prettyPrintAst, prettyPrintTypeSpec}
import munit.*

class FnTests extends BaseEffFunSuite:

  test("simple fn") {
    parseNotFailed(
      """
          fn sum (a, b) = a sum b;
      """
    ).map { m =>
      {
        assert(m.members.size == 1)
        m.members.head
      }
    }.map {
      case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Function) =>
        bnd.value.terms.head match
          case lambda: Lambda =>
            assert(
              lambda.params.size == 2,
              s"Expected 2 params but got ${lambda.params.size}: ${prettyPrintAst(bnd)} "
            )
            assert(
              lambda.body.terms.size == 3,
              s"Expected 3 terms but got ${lambda.body.terms.size}: ${prettyPrintAst(bnd)} "
            )
          case _ => fail("Expected a Lambda inside Bnd")
      case _ => fail("Expected a function (Bnd with meta)")
    }
  }

  test("fn and let") {
    parseNotFailed(
      """
        let a = 1;
        let b = 2;
        fn sum (a, b) = a + b;
        let x = sum a b;
       """
    )
  }

  test("app with id and lit") {
    parseNotFailed(
      """        
        fn sum (a, b) = b + 3;
      """
    )
  }

  test("Placeholder is accepted as a term") {
    parseNotFailed(
      """        
          fn plusA (a) = sum a _;
        """
    )
  }

  test("fn with hole for body") {
    parseNotFailed(
      """
        fn hole (h) = ???;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.last
    }.map {
      case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Function) =>
        bnd.value.terms.head match
          case lambda: Lambda =>
            assert(
              lambda.body.terms.size == 1,
              s"Expected 1 term but got ${lambda.body.terms.size} : ${prettyPrintAst(bnd)}"
            )
          case _ => fail("Expected a Lambda inside Bnd")
      case _ => fail("Expected a function (Bnd with meta)")
    }
  }

  test("fn with type spec") {
    parseNotFailed(
      """
        fn sum (a: Int, b: Int) = a + b;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Function) =>
        bnd.value.terms.head match
          case lambda: Lambda =>
            assert(
              lambda.params.size == 2,
              s"Expected 2 params but got ${lambda.params.size}: ${prettyPrintAst(bnd)} "
            )
            assert(
              lambda.body.terms.size == 3,
              s"Expected 3 terms but got ${lambda.body.terms.size}: ${prettyPrintAst(bnd)} "
            )
            lambda.params
          case _ => fail("Expected a Lambda inside Bnd")
      case _ => fail("Expected a function")
    }.map { params =>
      assert(
        params.forall(_.typeAsc.isDefined),
        s"Expected all params to have a type spec: ${params.map(p => prettyPrintAst(p))}"
      )
    }
  }

  test("fn with function type return ascription") {
    parseNotFailed(
      """
        fn wrap (f: String -> String): String -> String = f;
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Function) =>
        // For Bnd(Lambda), the type ascription is on the lambda or the bnd
        val typeAsc = bnd.value.terms.head match
          case lambda: Lambda => lambda.typeAsc.orElse(bnd.typeAsc)
          case _ => bnd.typeAsc
        typeAsc match
          case Some(TypeFn(_, params, returnType)) =>
            assertEquals(params.size, 1)
            params.head match
              case TypeRef(_, "String", _) => // pass
              case other =>
                fail(s"Expected String param type, got ${prettyPrintAst(other)}")
            returnType match
              case TypeRef(_, "String", _) => // pass
              case other =>
                fail(s"Expected String return type, got ${prettyPrintAst(other)}")
          case other =>
            fail(s"Expected function return type, got ${prettyPrintTypeSpec(other)}")
      case other => fail(s"Expected a function, got ${prettyPrintAst(other)}")
    }
  }

  test("grouped expression") {
    parseNotFailed(
      """
        fn compute (a, b) = 1 + (2 * 3);
      """
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head
    }.map {
      case bnd: Bnd if bnd.meta.exists(_.origin == BindingOrigin.Function) =>
        bnd.value.terms.head match
          case lambda: Lambda =>
            assert(
              lambda.body.terms.size == 3,
              s"Expected a body with 3 terms but got ${lambda.body.terms.size}: ${prettyPrintAst(bnd)}"
            )
          case _ => fail("Expected a Lambda inside Bnd")
      case _ => fail("Expected a binding")
    }
  }

  test("cant use a keyword as a name") {
    parseFailed(
      """
        fn let (a) = 1;
      """
    )
  }

  test("fn with invalid name") {
    parseFailedWithErrors(
      """
        fn 123invalid (a) = a;
      """
    ).map { errors =>
      assert(errors.size == 1, s"Expected 1 error but got ${errors.size}")
      errors.head match {
        case e: ParsingIdError =>
          assertEquals(e.invalidId, "123invalid")
        case e => fail(s"Expected a ParsingIdError but got $e")
      }
    }
  }
