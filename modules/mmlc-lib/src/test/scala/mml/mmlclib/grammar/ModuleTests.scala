package mml.mmlclib.grammar

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*
import org.neo4j.internal.helpers.Strings.prettyPrint

class ModuleTests extends BaseFunSuite:

  test("explicit module. name passed, ignored") {
    modNotFailed(
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
    modNotFailed(
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
    modFailed(
      """
        let a = 1
      """,
      None
    )
  }

  test("optional semicolon closing module") {
    modNotFailed(
      """
      module A =
        let a = 1;
        let b = 2;
      """.stripMargin
    )
  }
