package mml.mmlclib.grammar

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.parser.SourceInfo
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

  test("a missing semicolon retains its binding and the next unambiguous member") {
    val source =
      """
        let ooopsie = "missing semicolon"

        pub let finally:String = "we are done";
      """

    semVisibleState(source, name = "MissingSemicolon").map { result =>
      val parsingErrors = result.errors.collect {
        case SemanticError.ParseTermErrorFound(error, _) => error
      }
      assertEquals(parsingErrors.length, 1)
      assertEquals(parsingErrors.head.message, "Expected ';'")
      assertEquals(parsingErrors.head.failedCode, None)
      val point = SourceInfo(source).pointAt(source.indexOf("pub"))
      assertEquals(parsingErrors.head.spanOpt, Some(SrcSpan(point, point)))
      assertEquals(result.errors.size, 1)

      // Filter out stdlib injected bindings (functions/operators have meta)
      val bindingNames = result.module.members.collect {
        case b: Bnd if b.meta.isEmpty => b.name
      }
      assertEquals(clue(bindingNames), List("ooopsie", "finally"))
    }
  }

  test("a bare let after an unterminated value remains a local-expression alternative") {
    val source = "let first = 1\nlet second = 2;"
    parseNotFailed(source).map { module =>
      val bindings = module.members.collect { case binding: Bnd => binding }
      assertEquals(bindings.map(_.name), List("first"))
      val locals = TermTraversal
        .collect(bindings.head.value) { case lambda: Lambda =>
          lambda.params.filter(_.source != SourceOrigin.Synth)
        }
        .flatten
      assertEquals(locals.map(_.name), List("second"))
      val state = ParsingErrorChecker.checkModule(
        CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      )
      assertEquals(state.diagnostics.records.size, 2)
      assertEquals(
        state.errors.collect { case SemanticError.ParseTermErrorFound(error, _) =>
          error.message
        }.toSet,
        Set("Expected expression", "Expected ';'")
      )
    }
  }
