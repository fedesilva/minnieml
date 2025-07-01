package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class MemberErrorCheckerTests extends BaseEffFunSuite:

  test("Full semantic pipeline passes correct modules") {
    semNotFailed(
      """
      module TestPipeline =
        fn valid(a b) = a + b;
        let x = 42;
      ;
      """
    ).map { module =>
      // Successfully passes through the semantic pipeline
      assert(module.members.size > 0)
    }
  }

  test("Full semantic pipeline integration with MemberErrorChecker") {
    semFailed(
      """
      module TestPipeline =
        fn valid(a b) = a + b;
        let x  ; // Missing expression after =
      ;
      """
    )
  }

  test("MemberErrorChecker should pass a module with no errors") {
    justParse(
      """
      module Test =
        fn valid(a b) = a + b;
        let x = 42;
      ;
      """
    ).map { module =>
      val state = SemanticPhaseState(module, Vector.empty)
      val result = MemberErrorChecker.checkModule(state)
      assert(result.errors.isEmpty)
      assertNoDiff(result.module.toString, module.toString)
    }
  }

  test("MemberErrorChecker should catch member errors as shown in the example") {
    semWithState(
      """
      module TestPartial =
        fn valid(a b) = a + b;
        let a  ; # Missing expression after =
      ;
      """
    ).map { result =>

      // println(prettyPrintAst(result.module))

      assert(clue(result.errors.size) == clue(1))

      result.errors.head match {
        case SemanticError.MemberErrorFound(error, phase) =>
          assert(error.message == "Failed to parse member")
          assert(error.failedCode.exists(_.contains("let a")))
          assert(phase == "mml.mmlclib.semantic.MemberErrorChecker")
        case e => fail(s"Expected MemberErrorFound error, got $e")
      }
    }
  }

  test("MemberErrorChecker should catch multiple member errors") {
    semWithState(
      """
      module TestPartial =
        fn valid(a b) = a + b;
        let a  ; // Missing expression after =
        bnd noLet = 5; # Invalid syntax - 'bnd' instead of 'let'
      ;
      """
    ).map { result =>

      // println(prettyPrintAst(result.module))

      assert(clue(result.errors.size) == clue(2))
      assert(result.errors.forall {
        case SemanticError.MemberErrorFound(_, _) => true
        case _ => false
      })
    }
  }
