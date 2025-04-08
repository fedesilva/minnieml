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
      MemberErrorChecker.checkModule(module) match {
        case Right(m) => assertNoDiff(m.toString, module.toString)
        case Left(errors) => fail(s"Expected Right but got Left($errors)")
      }
    }
  }

  test("MemberErrorChecker should catch member errors as shown in the example") {
    justParse(
      """
      module Anon =
        fn mult(a b) = a * b;
        let a  ;
      ;
      """
    ).map { module =>
      MemberErrorChecker.checkModule(module) match {
        case Left(errors) =>
          assert(errors.size == 1)
          errors.head match {
            case SemanticError.MemberErrorFound(error) =>
              assert(error.message == "Failed to parse member")
              assert(error.failedCode.exists(_.contains("let a")))
            case other => fail(s"Expected MemberErrorFound but got $other")
          }
        case Right(_) => fail("Expected Left with errors but got Right")
      }
    }
  }

  test("MemberErrorChecker should report multiple member errors") {
    justParse(
      """
      module Anon =
        fn mult(a b) = a * b;
        let a  ;
        let b  ;
      ;
      """
    ).map { module =>
      MemberErrorChecker.checkModule(module) match {
        case Left(errors) =>
          assert(errors.size == 2)
          assert(errors.forall {
            case SemanticError.MemberErrorFound(_) => true
            case _ => false
          })
        case Right(_) => fail("Expected Left with errors but got Right")
      }
    }
  }
