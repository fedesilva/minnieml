package mml.mmlclib.semantic

import cats.syntax.option.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class ParsingErrorCheckerTests extends BaseEffFunSuite:

  test("Full semantic pipeline passes correct modules") {
    semNotFailed(
      """
      fn valid(a: Int, b: Int): Int = a + b;;
      let x = 42;
      """
    ).map { module =>
      // Successfully passes through the semantic pipeline
      assert(module.members.size > 0)
    }
  }

  test("Full semantic pipeline integration with MemberErrorChecker") {
    semFailed(
      """
      fn valid(a: Int, b: Int): Int = a + b;;
      let x  ; // Missing expression after =
      """
    )
  }

  test("MemberErrorChecker should pass a module with no errors") {
    justParse(
      """
      fn valid(a: Int, b: Int): Int = a + b;;
      let x = 42;
      """
    ).map { module =>
      val state  = CompilerState.empty(module, SourceInfo(""), CompilerConfig.default)
      val result = ParsingErrorChecker.checkModule(state)
      assert(result.errors.isEmpty)
      assertNoDiff(result.module.toString, module.toString)
    }
  }

  test("MemberErrorChecker should catch term errors nested inside expressions") {
    val code =
      """
      let x =
        let 123invalid = 5;
        6;
      ;
      """

    justParse(code).map { module =>
      val state  = CompilerState.empty(module, SourceInfo(code), CompilerConfig.default)
      val result = ParsingErrorChecker.checkModule(state)

      val errors = result.errors.collect { case SemanticError.TermErrorFound(error, _) => error }
      assertEquals(errors.size, 1)
      val error = errors.head
      assertEquals(error.message, "Invalid identifier '123invalid'")
      assertEquals(error.failedCode, "123invalid".some)

      val span = error.spanOpt.getOrElse(fail("Expected nested error source span"))
      assertEquals(span.start, state.sourceInfo.pointAt(code.indexOf("let 123invalid")))
      val recoveredSource = state.sourceInfo.slice(span.start.index, span.end.index)
      assertEquals(
        recoveredSource.linesIterator.map(_.trim).mkString("\n"),
        "let 123invalid = 5;\n6"
      )

      val memberErrors = result.errors.collect { case SemanticError.MemberErrorFound(error, _) =>
        error
      }
      assertEquals(memberErrors.map(_.failedCode).toList, List(";".some))
      assertEquals(result.errors.size, 2)
      assertEquals(result.module, module)
    }
  }

  test("ParsingErrorChecker accumulates nested branch errors with existing diagnostics") {
    val code =
      """
      fn broken(flag: Bool): Int =
        if flag then
          let 123invalid = 5;
          6;
        else
          let 456invalid = 7;
          8;
        ;
      ;
      let 789invalid = 9;
      let after = 10;
      """

    justParse(code).map { module =>
      val existing = SemanticError.InvalidEntryPoint("existing diagnostic", SourceOrigin.Synth)
      val state = CompilerState
        .empty(module, SourceInfo(code), CompilerConfig.default)
        .addError(existing)
      val result = ParsingErrorChecker.checkModule(state)
      assertEquals(result.errors.head, existing)

      val nestedErrors = result.errors.collect { case SemanticError.TermErrorFound(error, _) =>
        error
      }
      assertEquals(
        nestedErrors.map(_.failedCode).toList,
        List("123invalid".some, "456invalid".some)
      )

      val idErrors = result.errors.collect { case SemanticError.ParsingIdErrorFound(error, _) =>
        error
      }
      assertEquals(idErrors.map(_.invalidId).toList, List("789invalid"))

      assertEquals(result.errors.size, 4)
      assertEquals(result.module, module)
      assert(module.members.exists {
        case binding: Bnd => binding.name == "after"
        case _ => false
      })
    }
  }

  test("Full semantic pipeline rejects invalid local function names at their source") {
    val code =
      """
      fn outer(): Int =
        fn 123invalid(x: Int): Int = x;;
        6;
      ;
      """

    semState(code).map { result =>
      val nestedErrors = result.errors.collect { case SemanticError.TermErrorFound(error, _) =>
        error
      }
      nestedErrors match
        case List(error) =>
          assertEquals(error.message, "Invalid identifier '123invalid'")
          assertEquals(error.failedCode, "123invalid".some)

          val span = error.spanOpt.getOrElse(fail("Expected local function source span"))
          assertEquals(span.start, SourceInfo(code).pointAt(code.indexOf("fn 123invalid")))
        case other => fail(s"Expected one nested invalid function name, got $other")
    }
  }

  test("MemberErrorChecker should catch member errors as shown in the example") {
    semState(
      """
        fn valid(a: Int, b: Int): Int = a + b;;
        let a  ; # Missing expression after = 
        """,
      name = "Test"
    ).map { result =>

      // println(prettyPrintAst(result.module))

      // if result.errors.nonEmpty then
      //   println(result.errors)

      assert(
        result.errors.nonEmpty,
        s"Expected semantic errors but found none. ${result.module}"
      )
      assert(clue(result.errors.size) == clue(1))

      result.errors.head match {
        case SemanticError.MemberErrorFound(error, phase) =>
          assert(error.message == "Failed to parse member")
          assert(error.failedCode.exists(_.contains("let a")))
        case e => fail(s"Expected MemberErrorFound error, got $e")
      }
    }
  }

  test("MemberErrorChecker should catch multiple member errors") {
    semState(
      """
        fn valid(a: Int, b: Int): Int = a + b;;
        let a  ; # Missing expression after = 
        bnd noLet = 5; # Invalid syntax - 'bnd' instead of 'let'
        """,
      name = "Test"
    ).map { result =>
      // import mml.mmlclib.util.prettyprint.ast.*
      // println(prettyPrintAst(result.module))

      assert(
        result.errors.nonEmpty,
        s"Expected semantic errors but found none. ${result.module}"
      )
      assert(clue(result.errors.size) == clue(2))
      assert(result.errors.forall {
        case SemanticError.MemberErrorFound(_, _) => true
        case _ => false
      })
    }
  }
