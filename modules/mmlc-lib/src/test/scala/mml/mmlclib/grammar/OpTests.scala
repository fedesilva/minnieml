package mml.mmlclib.grammar

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*

class OpTests extends BaseEffFunSuite:

  test("let with simple binop") {
    modNotFailed(
      """
        op + (a b) = sum a b;
      """
    )
  }

  test("let with left assoc unary op") {
    modNotFailed(
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
    modNotFailed(
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

  test("unary right assoc with precedence") {
    modNotFailed(
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
    modNotFailed(
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
