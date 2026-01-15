package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import munit.*

class CondTests extends BaseEffFunSuite:

  test("simple cond") {
    parseNotFailed(
      """
          let a =
            if true then
              1
            else
              2
            end;
        """.stripMargin
    ).map { m =>
      assert(m.members.size == 1)
      m.members.head match
        case bnd: Bnd =>
          assert(bnd.name == "a")
          assert(bnd.value.terms.head.isInstanceOf[Cond])
        // pass
        case other =>
          fail(s"Expected Bnd, got $other")
    }
  }
