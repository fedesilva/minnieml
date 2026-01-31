package mml.mmlclib.semantic

import mml.mmlclib.compiler.{CompilerConfig, CompilerState, ParsingErrorChecker}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class ParsingErrorCheckerTests extends BaseEffFunSuite:

  test("Full semantic pipeline passes correct modules") {
    semNotFailed(
      """
      fn valid(a: Int, b: Int): Int = a + b;
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
      fn valid(a: Int, b: Int): Int = a + b;
      let x  ; // Missing expression after =
      """
    )
  }

  test("MemberErrorChecker should pass a module with no errors") {
    justParse(
      """
      fn valid(a: Int, b: Int): Int = a + b;
      let x = 42;
      """
    ).map { module =>
      val state  = CompilerState.empty(module, SourceInfo(""), CompilerConfig.default)
      val result = ParsingErrorChecker.checkModule(state)
      assert(result.errors.isEmpty)
      assertNoDiff(result.module.toString, module.toString)
    }
  }

  test("MemberErrorChecker should catch member errors as shown in the example") {
    semState(
      """
        fn valid(a: Int, b: Int): Int = a + b;
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
        fn valid(a: Int, b: Int): Int = a + b;
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
