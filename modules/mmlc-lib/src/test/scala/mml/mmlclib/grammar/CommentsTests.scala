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

  test("doc comment before let declaration") {
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

  test("doc comments on multiple declarations") {
    modNotFailed(
      """
        #- Comment on x -#
        let x = 3;
        #- Comment on y -#
        let y = 5;  # Another inline comment
      """
    ).map { m =>
      assertEquals(m.members.size, 2)
      m.members.foreach {
        case d: Decl => assert(d.docComment.isDefined)
        case _ => fail("there should be only declarations")
      }
    }
  }

  test("unclosed doc should fail ") {
    modFailed(
      """
         #- This is an unclosed comment
          let x = 42;
        """
    )
  }

  test("unclosed doc - no module ; - end of the file") {
    modFailed(
      """
      module Test =
        let x = 42;
        #- This is an unclosed comment
      """
    )
  }

  test("unclosed doc - implicit module - end of the file") {
    modFailed(
      """
        let x = 42;
        #- This is an unclosed comment
      """
    )
  }

  test("doc comments") {
    modNotFailed(
      """
        #- This is a doc comment -#
        let x = 42;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("doc comments in fn") {
    modNotFailed(
      """
         #- This is a doc comment -#
         fn func (x) = 42;
       """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("module with doc comment") {
    modNotFailed(
      """
        #-
        This is a doc comment
        -#
        module Test =
          let x = 42;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
      assert(m.docComment.isDefined)
      m.docComment.foreach { doc =>
        assert(
          doc.text.contains("This is a doc comment"),
          s"""
             | Expected doc comment to contain "This is a doc comment"
             | Actual doc comment: ${doc.text}
             | ${prettyPrintAst(m)}
             |""".stripMargin
        )
      }

    }
  }

  test("doc comments with # margin - remove margin".ignore) {
    modNotFailed(
      """
        #-
        #
        # This is a doc comment
        #
        -#
        let x = 42;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
      m.members.foreach {
        case d: Decl =>
          assert(d.docComment.isDefined)
          d.docComment.foreach { doc =>
            assert(
              !doc.text.contains("#"),
              s"""
                 | Expected doc comment to NOT contain "#"
                 | Actual doc comment: ${doc.text}
                 | ${prettyPrintAst(d)}
                 |""".stripMargin
            )
            assert(
              doc.text.contains("This is a doc comment"),
              s"""
                 | Expected doc comment to contain "This is a doc comment"
                 | Actual doc comment: ${doc.text}
                 | ${prettyPrintAst(d)}
                 |""".stripMargin
            )
          }
        case _ => fail("there should be only declarations")
      }
    }
  }
