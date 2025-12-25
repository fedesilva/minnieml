package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite
import munit.*

class ModuleTests extends BaseEffFunSuite:

  test("top-level module uses provided name") {
    parseNotFailed(
      """
        let a = 1;
      """,
      "TopLevel"
    ).map { m =>
      assertEquals(m.name, "TopLevel")
      assertEquals(m.members.size, 1)
      assert(m.members.head.isInstanceOf[Bnd])
    }
  }

  test("top-level modules can have multiple members") {
    parseNotFailed(
      """
        let a = 1;
        let b = 2;
      """,
      "ManyMembers"
    ).map { m =>
      assertEquals(m.members.count(_.isInstanceOf[Bnd]), 2)
    }
  }

  test("top-level module tolerates rubbish at the end without aborting") {
    parseFailedWithErrors(
      """
        let a = 1;
        rubbish-at-the-end
      """,
      "Rubbish"
    ).map { errors =>
      errors.lastOption match
        case Some(_: ParsingMemberError) => ()
        case other => fail(s"Expected trailing ParsingMemberError, got $other")
    }
  }

  test("malformed module should be detected, not abort parsing: 1") {
    parseFailedWithErrors(
      """
     let a = 1;

    ;
 
    """
    ).map { errors =>
      assert(!errors.isEmpty)
    }
  }

  test("malformed module should be detected, not abort parsing: 2") {
    parseFailedWithErrors(
      """
      let a = 1;

      ,

      ;
      ;
      ;
      ;
      ;
      ;
 
    """
    ).map { errors =>
      assert(!errors.isEmpty)
    }
  }

  test("malformed module should be detected, not abort parsing: 3") {
    parseFailedWithErrors(
      """
      let a = 1;;

      let b = "y u no work?";;;;;;

      let c = true;
      """
    ).map { errors =>
      assert(!errors.isEmpty)
    }
  }

  test("missing semicolon emits member error but continues parsing") {
    val source =
      """
        let ooopsie = "missing semicolon"

        let finally:String = "we are done";
      """

    semState(source, name = "MissingSemicolon").map { result =>
      val parsingErrors = result.errors.collect {
        case SemanticError.MemberErrorFound(member: ParsingMemberError, _) => member
      }
      assertEquals(parsingErrors.length, 1)
      assertEquals(parsingErrors.head.failedCode, Some("let ooopsie = \"missing semicolon\""))

      // Filter out stdlib injected bindings (functions/operators have meta)
      val bindingNames = result.module.members.collect {
        case b: Bnd if b.meta.isEmpty => b.name
      }
      assertEquals(clue(bindingNames), List("finally"))
    }
  }
