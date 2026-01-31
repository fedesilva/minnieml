package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.*

class CommentsTests extends BaseEffFunSuite:

  test("line comment before declaration") {
    parseNotFailed(
      """
        // line comment
        let a = 1;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("line comments between tokens") {
    parseNotFailed(
      """
        let // comment between tokens
        x   // another comment
        =   // inline assignment comment
        3   // end of statement
        ;   // trailing semicolon comment
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("doc comment before let declaration") {
    parseNotFailed(
      """
        /*
        This is a multiline comment
        It spans multiple lines
        */
        let x = 42;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("nested multiline comments") {
    parseNotFailed(
      """
        /* Outer comment
        /* Nested comment /* inside */ outer */
        */
        let a = 1;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("doc comments on multiple declarations") {
    parseNotFailed(
      """
        /* Comment on x */
        let x = 3;
        /* Comment on y */
        let y = 5;  // Another inline comment
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
    parseFailed(
      """
         /* This is an unclosed comment
          let x = 42;
        """
    )
  }

  test("unclosed doc - no module ; - end of the file") {
    parseFailed(
      """
        let x = 42;
        /* This is an unclosed comment
      """
    )
  }

  test("unclosed doc - implicit module - end of the file") {
    parseFailed(
      """
        let x = 42;
        /* This is an unclosed comment
      """
    )
  }

  test("doc comments") {
    parseNotFailed(
      """
        /* This is a doc comment */
        let x = 42;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("doc comments in fn") {
    parseNotFailed(
      """
         /* This is a doc comment */
         fn func (x) = 42;
       """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("file-level doc comment attaches to first member") {
    parseNotFailed(
      """
        /*
        This is a doc comment
        */
        let x = 42;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
      assert(m.docComment.isEmpty)
      m.members.head match
        case decl: Decl =>
          val doc = decl.docComment.getOrElse(fail("expected doc comment on first member"))
          assert(
            doc.text.contains("This is a doc comment"),
            s"""
               | Expected doc comment to contain "This is a doc comment"
               | Actual doc comment: ${doc.text}
               | ${prettyPrintAst(decl)}
               |""".stripMargin
          )
        case other => fail(s"expected declaration, got $other")
    }
  }

  test("doc comments with * margin - remove margin") {
    parseNotFailed(
      """
        /*
        *
        * This is a doc comment
        *
        */
        let x = 42;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
      m.members.foreach {
        case d: Decl =>
          assert(d.docComment.isDefined)
          d.docComment.foreach { doc =>
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

  test("single slash is not a comment (division operator)") {
    parseNotFailed(
      """
        let x = 10 / 2;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }

  test("triple slash is a comment") {
    parseNotFailed(
      """
        /// this is also a comment
        let x = 42;
      """
    ).map { m =>
      assertEquals(m.members.size, 1)
    }
  }
