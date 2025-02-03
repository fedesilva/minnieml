package mml.mmlclib.grammar

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*

class CommentsTests extends BaseEffFunSuite:

  test("line comment before declaration") {
    modNotFailed(
      """
        # line comment
        let a = 1;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("line comments between tokens") {
    modNotFailed(
      """
        let # comment between tokens
        x   # another comment
        =   # inline assignment comment
        3   # end of statement
        ;   # trailing semicolon comment
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("multiline comment before declaration") {
    modNotFailed(
      """
        #-
        This is a multiline comment
        It spans multiple lines
        -#
        let x = 42;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("multiline comment between tokens") {
    modNotFailed(
      """
        let x = #-
        This is an inline multiline comment
        -# 10;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("nested multiline comments") {
    modNotFailed(
      """
        #- Outer comment
        #- Nested comment #- inside -# outer -#
        -#
        let a = 1;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("comments should not interfere with parsing multiple declarations") {
    modNotFailed(
      """
        let x = 3;  #- Comment at the end of a line -#
        let y = 5;  # Another inline comment
      """
    ).map { m =>
      assertEquals(m.members.size, 2)
    }
  }

  test("unclosed multiline comment should fail") {
    modFailed(
      """
        let x = 42;
        #- This is an unclosed comment
      """
    )
  }
