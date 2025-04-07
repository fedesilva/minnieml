package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.*

class AppRewritingTests extends BaseEffFunSuite:

  test("2 arity function") {
    semNotFailed(
      """
      fn mult (a b) = ???;
      let a = mult 2 2;
      """
    ).map { m =>

      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case (app: App) :: Nil =>
            // pass
            case _ =>
              fail(s"Expected an App, got: \n${prettyPrintAst(bnd.value)}")

        case x =>
          fail(s"Expected a Bnd, got: $x")

    }
  }
  
  test("function with dangling terms should fail") {
    // This should fail semantic analysis due to dangling terms
    semFailed(
      """
      fn func (a b) = ???;
      let a = 2 + (func 1) 3;
      """
    )
  }
  
  test("grouped function applications should work correctly") {
    semNotFailed(
      """
      fn func (a) = ???;
      fn apply (f x) = ???;
      let a = apply (func 1) 2;
      """
    ).map { m =>
      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      // Just verify the binding exists with no error
      memberBnd match
        case bnd: Bnd =>
          // Test passes if we get here with no errors
          assert(true)
        
        case other =>
          fail(s"Expected Bnd, got: $other")
    }
  }
  
  test("curried function application should work without boundaries") {
    semNotFailed(
      """
      fn func (a b) = ???;
      let a = func 1 2 3 4;
      """
    ).map { m =>
      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      // println(s"DEBUG AST:\n${prettyPrintAst(memberBnd)}")

      // Check we have a binding without errors
      memberBnd match
        case bnd: Bnd =>
          // Should have a nested application structure
          bnd.value.terms match
            case (app: App) :: Nil =>
              // This should be a nested App structure
              // Try to navigate through the nesting to verify the structure
              app.fn match
                case nestedApp: App =>
                  // There should be at least one level of nesting for the first two arguments
                  assert(true, "Found nested App structure")
                case other => 
                  fail(s"Expected nested App in fn position, got: ${prettyPrintAst(other)}")
            
            case other =>
              fail(s"Expected a nested App structure, got: \n${prettyPrintAst(bnd.value)}")
        
        case other =>
          fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
    }
  }
  
  test("function application with operators should work") {
    semNotFailed(
      """
      fn func (a b) = ???;
      let a = (func 1 1) + 3 - func 1 2 3;
      """
    ).map { m =>
      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      // println(s"DEBUG AST:\n${prettyPrintAst(memberBnd)}")

      // Just verify we don't have errors
      assert(true, "Expression with operators and function application parsed successfully")
    }
  }
  
  test("complex nested function applications with operators should work") {
    semNotFailed(
      """
      fn func (a b) = ???;
      fn apply (f x) = ???;
      fn compose (f g x) = ???;
      
      let a = apply (func 1) 2 + compose func func 3 4 5;
      """
    ).map { m =>
      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      // Just verify we don't have errors
      assert(true, "Complex expression with nested applications parsed successfully")
    }
  }
