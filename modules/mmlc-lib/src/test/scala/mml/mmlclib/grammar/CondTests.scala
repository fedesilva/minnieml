package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*

class CondTests extends BaseEffFunSuite:

  test("simple cond") {
    modNotFailed(
      """
          let a = 
            if true then 
              1 
            else 
              2
            ;
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
