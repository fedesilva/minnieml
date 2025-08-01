package mml.mmlclib.grammar

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import munit.*

class ModuleTests extends BaseEffFunSuite:

  test("explicit module. name passed, ignored") {
    parseNotFailed(
      """
      module A =
        let a = 1;
      ;
      """,
      "IgnoreThisName".some
    ).map { m =>
      assert(m.name == "A")
      assert(!m.isImplicit)
    }
  }

  test("implicit module, name passed") {
    parseNotFailed(
      """
        let a = 1;
      """,
      "TestModule".some
    ).map(m => {
      assert(clue(m.name) == clue("TestModule"))
      assert(m.isImplicit)
      assert(m.members.size == 1)
      assert(m.members.head.isInstanceOf[Bnd])
    })
  }

  test("fail: implicit module, name NOT  passed") {
    parseFailed(
      """
        let a = 1
      """,
      None
    )
  }

  test("optional semicolon closing module") {
    parseNotFailed(
      """
      module A =
        let a = 1;
        let b = 2;
      """.stripMargin
    )
  }

  test("anon module with rubbish at the end should not abort".ignore) {
    parseNotFailed(
      """
        let a = 1;
        rubbish-at-the-end
      """
    )
  }

  test("module with rubbish at the end should not abort".ignore) {
    parseNotFailed(
      """
        module A = 
          let a = 1;
          rubbish-at-the-end
        ;
        """
    )
  }
