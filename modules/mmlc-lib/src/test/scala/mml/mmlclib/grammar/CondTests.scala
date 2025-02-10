package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyPrintAst
import munit.*
import org.neo4j.internal.helpers.Strings.prettyPrint

class CondTests extends BaseEffFunSuite:

  test("simple cond".only) {
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
      m.members.head
    }.map {
      case bnd: Bnd =>
        assert(bnd.name == "a")
        assert(bnd.value.terms.head.isInstanceOf[Cond])
      // pass
      case other =>
        fail(s"Expected Bnd, got $other")
    }
  }
