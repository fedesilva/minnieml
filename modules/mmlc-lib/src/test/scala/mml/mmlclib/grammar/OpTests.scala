package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.{prettyPrintAst, prettyPrintTypeSpec}
import munit.*

class OpTests extends BaseEffFunSuite:

  test("let with simple binop") {
    parseNotFailed(
      """
        op + (a, b) = sum a b;
      """
    )
  }

  test("op with function type ascription") {
    parseNotFailed(
      """
        op apply (f: Int -> Int, x: Int): Int = f x;
      """
    ).map { m =>
      assert(
        clue(m.members.size) == clue(1),
        s"Expected 1 member, got: ${m.members.size}"
      )
      m.members.head
    }.map {
      case bnd: Bnd
          if bnd.meta
            .exists(m => m.origin == BindingOrigin.Operator && m.arity == CallableArity.Binary) =>
        bnd.value.terms.head match
          case lambda: Lambda =>
            val param1 = lambda.params.head
            param1.typeAsc match
              case Some(TypeFn(_, params, returnType)) =>
                assertEquals(params.size, 1)
                params.head match
                  case TypeRef(_, "Int", _, _) => // pass
                  case other =>
                    fail(s"Expected Int param type, got ${prettyPrintAst(other)}")
                returnType match
                  case TypeRef(_, "Int", _, _) => // pass
                  case other =>
                    fail(s"Expected Int return type, got ${prettyPrintAst(other)}")
              case other =>
                fail(s"Expected function type ascription, got ${prettyPrintTypeSpec(other)}")
          case _ => fail("Expected Lambda inside Bnd")
      case x =>
        fail(s"Expected binary operator Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("let with left assoc unary op") {
    parseNotFailed(
      """
        op - (a) left = neg a;
      """
    ).map { m =>
      assert(
        clue(m.members.size) == clue(1),
        s"Expected 1 member, got: ${m.members.size}"
      )
      m.members.head match
        case bnd: Bnd
            if bnd.meta
              .exists(m => m.origin == BindingOrigin.Operator && m.arity == CallableArity.Unary) =>
          val meta = bnd.meta.get
          assert(
            clue(meta.originalName) == clue("-"),
            s"Expected op: -, got: ${meta.originalName}"
          )
          assert(
            clue(meta.associativity) == clue(Some(Associativity.Left)),
            s"Expected assoc: Left, got: ${meta.associativity}"
          )
        case x =>
          fail(s"Expected unary operator Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("let with right assoc unary op") {
    parseNotFailed(
      """
        op - (a) right = neg a right;
      """
    ).map { m =>
      assert(
        clue(m.members.size) == clue(1),
        s"Expected 1 member, got: ${m.members.size}"
      )
      m.members.head match
        case bnd: Bnd
            if bnd.meta
              .exists(m => m.origin == BindingOrigin.Operator && m.arity == CallableArity.Unary) =>
          val meta = bnd.meta.get
          assert(
            clue(meta.originalName) == clue("-"),
            s"Expected op: -, got: ${meta.originalName}"
          )
          assert(
            clue(meta.associativity) == clue(Some(Associativity.Right)),
            s"Expected assoc: Right, got: ${meta.associativity}"
          )
        case x =>
          fail(s"Expected unary operator Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("unary op with invalid name") {
    parseFailedWithErrors(
      """
        op 123invalid (a) = a;
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

  test("binop with invalid name") {
    parseFailedWithErrors(
      """
        op 123invalid (a, b) = a;
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

  test("alphabetic operators: and, or, not") {
    parseNotFailed(
      """
        let x = not a or b and c;
      """
    ) // Just check if it parses without error
  }

  test("unary right assoc with precedence") {
    parseNotFailed(
      """
        op - (a) 2 right = neg a;
      """
    ).map { m =>
      assert(
        clue(m.members.size) == clue(1),
        s"Expected 1 member, got: ${m.members.size}"
      )
      m.members.head match
        case bnd: Bnd
            if bnd.meta
              .exists(m => m.origin == BindingOrigin.Operator && m.arity == CallableArity.Unary) =>
          val meta = bnd.meta.get
          assert(
            clue(meta.originalName) == clue("-"),
            s"Failed test ${prettyPrintAst(bnd)}"
          )
          assert(
            clue(meta.associativity) == clue(Some(Associativity.Right)),
            s"Failed test ${prettyPrintAst(bnd)}"
          )
          assert(
            clue(meta.precedence) == clue(2),
            s"Failed test ${prettyPrintAst(bnd)}"
          )
        case x =>
          fail(s"Expected unary operator Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("unary left assoc with precedence") {
    parseNotFailed(
      """
          op - (a) 2 left  = neg a;
        """
    ).map { m =>
      assert(
        clue(m.members.size) == clue(1),
        s"Expected 1 member, got: ${m.members.size}"
      )
      m.members.head match
        case bnd: Bnd
            if bnd.meta
              .exists(m => m.origin == BindingOrigin.Operator && m.arity == CallableArity.Unary) =>
          val meta = bnd.meta.get
          assert(
            clue(meta.originalName) == clue("-"),
            s"Failed test ${prettyPrintAst(bnd)}"
          )
          assert(
            clue(meta.associativity) == clue(Some(Associativity.Left)),
            s"Failed test ${prettyPrintAst(bnd)}"
          )
          assert(
            clue(meta.precedence) == clue(2),
            s"Failed test ${prettyPrintAst(bnd)}"
          )
        case x =>
          fail(s"Expected unary operator Bnd, got: ${prettyPrintAst(x)}")
    }
  }
