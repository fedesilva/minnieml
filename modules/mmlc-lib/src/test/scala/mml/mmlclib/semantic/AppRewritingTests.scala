package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.semantic.lookupNames
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.TestExtractors.*
import mml.mmlclib.util.*
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.*

class AppRewritingTests extends BaseEffFunSuite:

  test("2 arity function") {

    val code =
      """
      fn mult (a: Int, b: Int): Int = ???;
      let a = mult 2 2;
    """

    semNotFailed(code).map { m =>

      // dump the raw module
      // println("dumping raw module")
      // println(m)

      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      memberBnd match {
        case bnd: Bnd =>
          // Expect: AssertApp(Ref(_, "mult", ...), List(Expr(..., Lit(2)), Expr(..., Lit(2))))
          bnd.value.terms match {
            case TXApp(
                  ref,
                  _,
                  List(
                    Expr(_, List(LiteralInt(_, arg1Val)), _, _),
                    Expr(_, List(LiteralInt(_, arg2Val)), _, _)
                  )
                ) :: Nil =>
              assertEquals(clue(ref.name), "mult", "Function name mismatch")
              assertEquals(clue(arg1Val), 2, "First argument mismatch")
              assertEquals(clue(arg2Val), 2, "Second argument mismatch")
            case other =>
              fail(
                s"Expected nested App structure App(App(Ref(mult), Expr(Lit(2))), Expr(Lit(2))), got: \n${other
                    .map(t => prettyPrintAst(t, 0, false, false))
                    .mkString("\n")}"
              )
          }
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
      }
    }
  }

  test("function with dangling terms should fail") {
    // This should fail semantic analysis due to dangling terms
    semFailed(
      """
      fn func (a: Int, b: Int): Int = ???;
      let a = 2 + (func 1) 3;
      """
    )
  }

  test("grouped function applications should work correctly") {
    semNotFailed(
      """
      fn func (a: Int): Int = ???;
      fn apply (f: Int, x: Int): Int = ???;
      let a = apply (func 1) 2;
      """
    ).map { m =>
      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      memberBnd match {
        case bnd: Bnd =>
          // Expect: AssertApp(Ref(_, "apply", ...), List(Expr(..., AssertApp(Ref("func"), Lit(1))), Expr(..., Lit(2))))
          bnd.value.terms match {
            // Use AssertApp for the outer application
            case TXApp(
                  applyRef,
                  _,
                  List(arg1Expr, arg2Expr @ Expr(_, List(LiteralInt(_, arg2Val)), _, _))
                ) :: Nil =>
              assertEquals(clue(applyRef.name), "apply", "Outer function name mismatch")
              // Use AssertApp for the inner App structure within the first argument Expr
              arg1Expr match {
                case Expr(
                      _,
                      List(
                        TXApp(funcRef, _, List(Expr(_, List(LiteralInt(_, arg1Val)), _, _)))
                      ),
                      _,
                      _
                    ) =>
                  assertEquals(clue(funcRef.name), "func", "Inner function name mismatch")
                  assertEquals(clue(arg1Val), 1, "Inner argument mismatch")
                case _ =>
                  fail(
                    s"Expected first argument to contain AssertApp(Ref(func), Expr(Lit(1))), got: ${prettyPrintAst(arg1Expr)}"
                  )
              }
              assertEquals(clue(arg2Val), 2, "Outer argument mismatch")
            case other =>
              fail(
                s"Expected nested App structure App(App(Ref(apply), Expr(App(Ref(func), Expr(Lit(1))))), Expr(Lit(2))), got: \n${other
                    .map(t => prettyPrintAst(t, 0, false, false))
                    .mkString("\n")}"
              )
          }
        case other =>
          fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    }
  }

  test("curried function application should work without boundaries") {
    semNotFailed(
      """
      fn func (a: Int, b: Int, c: Int, d: Int): Int = ???;
      let a = func 1 2 3 4;
      """
    ).map { m =>
      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      memberBnd match {
        case bnd: Bnd =>
          // Expect: AssertApp(Ref(_, "func", ...), List(Expr(..., Lit(1)), Expr(..., Lit(2)), Expr(..., Lit(3)), Expr(..., Lit(4))))
          bnd.value.terms match {
            case TXApp(
                  ref,
                  _,
                  List(
                    Expr(_, List(LiteralInt(_, arg1Val)), _, _),
                    Expr(_, List(LiteralInt(_, arg2Val)), _, _),
                    Expr(_, List(LiteralInt(_, arg3Val)), _, _),
                    Expr(_, List(LiteralInt(_, arg4Val)), _, _)
                  )
                ) :: Nil =>
              assertEquals(clue(ref.name), "func", "Function name mismatch")
              assertEquals(clue(arg1Val), 1, "Arg 1 mismatch")
              assertEquals(clue(arg2Val), 2, "Arg 2 mismatch")
              assertEquals(clue(arg3Val), 3, "Arg 3 mismatch")
              assertEquals(clue(arg4Val), 4, "Arg 4 mismatch")
            case other =>
              fail(s"Expected deeply nested App structure, got: \n${other
                  .map(t => prettyPrintAst(t, 0, false, false))
                  .mkString("\n")}")
          }
        case other =>
          fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    }
  }

  test("function application with operators should work") {
    semNotFailed(
      """
      fn func (a: Int, b: Int): Int = ???;
      let a = (func 1 1) + 3 - func 1 2;
      """
    ).map { m =>
      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      memberBnd match {
        case bnd: Bnd =>
          // The value is now an Expr with App nodes due to operator transformation
          bnd.value.terms.headOption match
            case Some(TXApp(minusRef, _, List(plusExpr, funcExpr))) =>
              // Top level is minus operation
              assertEquals(clue(minusRef.name), "-", "Should be minus operation")

              // Verify right side is func 1 2
              funcExpr.terms.headOption match
                case Some(TXApp(funcRef2, _, funcArgs)) =>
                  assertEquals(clue(funcRef2.name), "func", "Func 2 name")
                  assertEquals(clue(funcArgs.length), 2, "Func 2 should have 2 args")

                  // Check all arguments are correct literals
                  funcArgs(0).terms.headOption match
                    case Some(LiteralInt(_, arg1Val)) =>
                      assertEquals(clue(arg1Val), 1, "Func 2 Arg 1")
                    case Some(other) =>
                      fail(s"Expected literal 1 for arg1, got: ${prettyPrintAst(other)}")
                    case None => fail("Expected literal 1 for arg1, got: None")

                  funcArgs(1).terms.headOption match
                    case Some(LiteralInt(_, arg2Val)) =>
                      assertEquals(clue(arg2Val), 2, "Func 2 Arg 2")
                    case Some(other) =>
                      fail(s"Expected literal 2 for arg2, got: ${prettyPrintAst(other)}")
                    case None => fail("Expected literal 2 for arg2, got: None")

                case Some(other) =>
                  fail(s"Expected TXApp in right side of minus, got: ${prettyPrintAst(other)}")
                case None => fail("Expected TXApp in right side of minus, got: None")

              // Check left side (plus operation)
              plusExpr.terms.headOption match
                case Some(TXApp(plusRef, _, plusArgs)) =>
                  assertEquals(clue(plusRef.name), "+", "Should be plus operation")
                  assertEquals(clue(plusArgs.length), 2, "+ should have 2 args")

                  // Right side of plus should be literal 3
                  plusArgs(1).terms.headOption match
                    case Some(LiteralInt(_, litVal)) =>
                      assertEquals(clue(litVal), 3, "Literal value")
                    case Some(other) => fail(s"Expected literal 3, got: ${prettyPrintAst(other)}")
                    case None => fail("Expected literal 3, got: None")

                  // Left side should be func 1 1
                  plusArgs(0).terms.headOption match
                    case Some(TXApp(funcRef1, _, funcArgs)) =>
                      assertEquals(clue(funcRef1.name), "func", "Func 1 name")
                      assertEquals(clue(funcArgs.length), 2, "Func 1 should have 2 args")

                      // Check both args are literal 1
                      funcArgs(0).terms.headOption match
                        case Some(LiteralInt(_, arg1Val)) =>
                          assertEquals(clue(arg1Val), 1, "Func 1 Arg 1")
                        case Some(other) =>
                          fail(s"Expected literal 1 for arg1, got: ${prettyPrintAst(other)}")
                        case None => fail("Expected literal 1 for arg1, got: None")

                      funcArgs(1).terms.headOption match
                        case Some(LiteralInt(_, arg2Val)) =>
                          assertEquals(clue(arg2Val), 1, "Func 1 Arg 2")
                        case Some(other) =>
                          fail(s"Expected literal 1 for arg2, got: ${prettyPrintAst(other)}")
                        case None => fail("Expected literal 1 for arg2, got: None")

                    case Some(other) =>
                      fail(s"Expected TXApp(func, [1, 1]), got: ${prettyPrintAst(other)}")
                    case None => fail("Expected TXApp(func, [1, 1]), got: None")

                case Some(other) =>
                  fail(s"Expected TXApp for plus operation, got: ${prettyPrintAst(other)}")
                case None => fail("Expected TXApp for plus operation, got: None")

            case Some(other) =>
              fail(s"Expected TXApp structure at top level, got: ${prettyPrintAst(other)}")
            case None => fail("Expected TXApp structure at top level, got: None")
        case other =>
          fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    }
  }

  test("zero-arity function") {
    semNotFailed(
      """
      fn func (): Int = ???;
      let a = func ();
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption
        .getOrElse(fail(s"Member `a` not found in module: ${prettyPrintAst(m)}"))

      memberBnd match {
        case bnd: Bnd =>
          // Expect: AssertApp(Ref(_, "func", ...), List(Expr(..., LiteralUnit)))
          bnd.value.terms match {
            case TXApp(ref, _, List(Expr(_, List(LiteralUnit(_)), _, _))) :: Nil =>
              assertEquals(clue(ref.name), "func", "Function name mismatch")
            case other =>
              fail(
                s"Expected App(Ref(func), Expr(LiteralUnit)), got: ${other.map(t => prettyPrintAst(t, 0, false, false)).mkString(", ")}"
              )
          }
        case other => fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    }
  }

  test("function application within if/else") {
    semNotFailed(
      """
      fn func (a: Int): Int = ???;
      let cond = true;
      let a = if cond then func 1 else func 2;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption
        .getOrElse(fail(s"Member `a` not found in module: ${prettyPrintAst(m)}"))

      memberBnd match {
        case bnd: Bnd =>
          // Expect: Expr(_, List(Cond(..., Expr(..., Ref(...)), Expr(..., TXApp(...)), Expr(..., TXApp(...)))) :: Nil
          bnd.value.terms.headOption match
            case Some(Cond(_, condExpr, thenExpr, elseExpr, _, _)) =>
              // Verify condition is a reference to "cond"
              condExpr.terms.headOption match
                case Some(Ref(_, condName, _, _, _, _)) =>
                  assertEquals(clue(condName), "cond", "Condition name mismatch")
                case Some(other) =>
                  fail(s"Expected Ref in condition expression, got: ${prettyPrintAst(other)}")
                case None => fail("Expected Ref in condition expression, got: None")

              // Verify then branch is func 1
              thenExpr.terms.headOption match
                case Some(TXApp(thenRef, _, thenArgs)) =>
                  assertEquals(clue(thenRef.name), "func", "Then branch function name")
                  assertEquals(clue(thenArgs.length), 1, "Then branch should have 1 arg")

                  // Check argument is literal 1
                  thenArgs(0).terms.headOption match
                    case Some(LiteralInt(_, thenArgVal)) =>
                      assertEquals(clue(thenArgVal), 1, "Then branch argument")
                    case Some(other) =>
                      fail(s"Expected literal 1 in then branch, got: ${prettyPrintAst(other)}")
                    case None => fail("Expected literal 1 in then branch, got: None")
                case Some(other) =>
                  fail(s"Expected TXApp in then branch, got: ${prettyPrintAst(other)}")
                case None => fail("Expected TXApp in then branch, got: None")

              // Verify else branch is func 2
              elseExpr.terms.headOption match
                case Some(TXApp(elseRef, _, elseArgs)) =>
                  assertEquals(clue(elseRef.name), "func", "Else branch function name")
                  assertEquals(clue(elseArgs.length), 1, "Else branch should have 1 arg")

                  // Check argument is literal 2
                  elseArgs(0).terms.headOption match
                    case Some(LiteralInt(_, elseArgVal)) =>
                      assertEquals(clue(elseArgVal), 2, "Else branch argument")
                    case Some(other) =>
                      fail(s"Expected literal 2 in else branch, got: ${prettyPrintAst(other)}")
                    case None => fail("Expected literal 2 in else branch, got: None")
                case Some(other) =>
                  fail(s"Expected TXApp in else branch, got: ${prettyPrintAst(other)}")
                case None => fail("Expected TXApp in else branch, got: None")
            case Some(other) =>
              fail(s"Expected Cond structure as the only term, got: ${prettyPrintAst(other)}")
            case None => fail("Expected Cond structure as the only term, got: None")
        case other => fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    }
  }

  test("nested function applications with operators") {
    semNotFailed(
      """
      fn func1 (a: Int): Int = ???;
      fn func2 (b: Int): Int = ???;
      let a = func1 (func2 1) + 2;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption
        .getOrElse(fail(s"Member `a` not found in module: ${prettyPrintAst(m)}"))

      memberBnd match {
        case bnd: Bnd =>
          // Expression with operator-as-app transformation
          bnd.value.terms.headOption match
            case Some(TXApp(plusRef, _, plusArgs)) =>
              assertEquals(clue(plusRef.name), "+", "Top operation should be +")
              assertEquals(clue(plusArgs.length), 2, "+ should have 2 args")

              // Check the right side (literal 2)
              plusArgs(1).terms.headOption match
                case Some(LiteralInt(_, lit2Val)) =>
                  assertEquals(clue(lit2Val), 2, "Right side should be literal 2")
                case Some(other) =>
                  fail(s"Expected literal 2 on right side, got: ${prettyPrintAst(other)}")
                case None => fail("Expected literal 2 on right side, got: None")

              // Check left side (func1 call)
              plusArgs(0).terms.headOption match
                case Some(TXApp(func1Ref, _, func1Args)) =>
                  assertEquals(clue(func1Ref.name), "func1", "Outer function name")
                  assertEquals(clue(func1Args.length), 1, "func1 should have 1 arg")

                  // The arg should be (func2 1)
                  func1Args(0).terms.headOption match
                    case Some(TXApp(func2Ref, _, func2Args)) =>
                      assertEquals(clue(func2Ref.name), "func2", "Inner function name")
                      assertEquals(clue(func2Args.length), 1, "func2 should have 1 arg")

                      // The arg should be literal 1
                      func2Args(0).terms.headOption match
                        case Some(LiteralInt(_, lit1Val)) =>
                          assertEquals(clue(lit1Val), 1, "func2 arg should be 1")
                        case Some(other) =>
                          fail(s"Expected literal 1 as func2 arg, got: ${prettyPrintAst(other)}")
                        case None => fail("Expected literal 1 as func2 arg, got: None")

                    case Some(other) =>
                      fail(
                        s"Expected expression with func2 application, got: ${prettyPrintAst(other)}"
                      )
                    case None => fail("Expected expression with func2 application, got: None")

                case Some(other) =>
                  fail(s"Expected TXApp for func1 call, got: ${prettyPrintAst(other)}")
                case None => fail("Expected TXApp for func1 call, got: None")

            case Some(other) =>
              fail(s"Expected TXApp structure at top level, got: ${prettyPrintAst(other)}")
            case None => fail("Expected TXApp structure at top level, got: None")
        case other => fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    }
  }

  test("single-argument function") {
    semNotFailed(
      """
      fn func (a: Int): Int = ???;
      let a = func 1;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption
        .getOrElse(fail(s"Member `a` not found in module: ${prettyPrintAst(m)}"))

      memberBnd match {
        case bnd: Bnd =>
          // Expect: AssertApp(Ref(_, "func", ...), List(Expr(..., Lit(1))))
          bnd.value.terms match {
            // Use AssertApp for single argument function
            case TXApp(ref, _, List(Expr(_, List(LiteralInt(_, arg1Val)), _, _))) :: Nil =>
              assertEquals(clue(ref.name), "func", "Function name mismatch")
              assertEquals(clue(arg1Val), 1, "Argument mismatch")
            case other =>
              fail(
                s"Expected App(Ref(func), Expr(Lit(1))), got: ${other.map(t => prettyPrintAst(t, 0, false, false)).mkString(", ")}"
              )
          }
        case other => fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    }
  }
