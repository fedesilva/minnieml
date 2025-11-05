package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.*

class OpTests extends BaseEffFunSuite:

  test("let with simple binop") {
    parseNotFailed(
      """
        op + (a, b) = sum a b;
      """
    )
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
        case op: UnaryOpDef =>
          assert(
            clue(op.name) == clue("-"),
            s"Expected op: -, got: ${op.name}"
          )
          assert(
            clue(op.assoc) == clue(Associativity.Left),
            s"Expected assoc: Left, got: ${op.assoc}"
          )
        case x =>
          fail(s"Expected UnaryOpDef, got: $x")
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
        case op: UnaryOpDef =>
          assert(
            clue(op.name) == clue("-"),
            s"Expected op: -, got: ${op.name}"
          )
          assert(
            clue(op.assoc) == clue(Associativity.Right),
            s"Expected assoc: Right, got: ${op.assoc}"
          )
        case x =>
          fail(s"Expected UnaryOpDef, got: ${prettyPrintAst(x)}")
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
        case op: UnaryOpDef =>
          assert(
            clue(op.name) == clue("-"),
            s"Failed test ${prettyPrintAst(op)}"
          )
          assert(
            clue(op.assoc) == clue(Associativity.Right),
            s"Failed test ${prettyPrintAst(op)}"
          )
          assert(
            clue(op.precedence) == clue(2),
            s"Failed test ${prettyPrintAst(op)}"
          )
        case x =>
          fail(s"Expected UnaryOpDef, got: ${prettyPrintAst(x)}")
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
        case op: UnaryOpDef =>
          assert(
            clue(op.name) == clue("-"),
            s"Failed test ${prettyPrintAst(op)}"
          )
          assert(
            clue(op.assoc) == clue(Associativity.Left),
            s"Failed test ${prettyPrintAst(op)}"
          )
          assert(
            clue(op.precedence) == clue(2),
            s"Failed test ${prettyPrintAst(op)}"
          )
        case x =>
          fail(s"Expected UnaryOpDef, got: ${prettyPrintAst(x)}")
    }
  }
