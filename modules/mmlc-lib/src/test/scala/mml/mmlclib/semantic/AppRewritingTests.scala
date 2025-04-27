package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.TestExtractors.*
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
      fn func (a b) = ???;
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
      fn func (a b) = ???;
      let a = (func 1 1) + 3 - func 1 2 3;
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

              // Verify right side is func 1 2 3
              funcExpr.terms.headOption match
                case Some(TXApp(funcRef2, _, funcArgs)) =>
                  assertEquals(clue(funcRef2.name), "func", "Func 2 name")
                  assertEquals(clue(funcArgs.length), 3, "Func 2 should have 3 args")

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

                  funcArgs(2).terms.headOption match
                    case Some(LiteralInt(_, arg3Val)) =>
                      assertEquals(clue(arg3Val), 3, "Func 2 Arg 3")
                    case Some(other) =>
                      fail(s"Expected literal 3 for arg3, got: ${prettyPrintAst(other)}")
                    case None => fail("Expected literal 3 for arg3, got: None")

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

      memberBnd match {
        case bnd: Bnd =>
          bnd.value.terms.headOption match
            case Some(TXApp(plusRef, _, plusArgs)) =>
              assertEquals(clue(plusRef.name), "+", "Top operation should be +")
              assertEquals(clue(plusArgs.length), 2, "+ should have 2 args")

              // Right side should be compose function call
              plusArgs(1).terms.headOption match
                case Some(TXApp(composeRef, _, composeArgs)) =>
                  assertEquals(clue(composeRef.name), "compose", "Compose function name")
                  assertEquals(clue(composeArgs.length), 5, "Compose should have 5 args")

                  // First arg should be func reference
                  composeArgs(0).terms.headOption match
                    case Some(Ref(_, funcName1, _, _, _, _)) =>
                      assertEquals(clue(funcName1), "func", "First compose arg should be func")
                    case Some(other) =>
                      fail(
                        s"Expected func reference as first compose arg, got: ${prettyPrintAst(other)}"
                      )
                    case None => fail("Expected func reference as first compose arg, got: None")

                  // Second arg should be func reference
                  composeArgs(1).terms.headOption match
                    case Some(Ref(_, funcName2, _, _, _, _)) =>
                      assertEquals(clue(funcName2), "func", "Second compose arg should be func")
                    case Some(other) =>
                      fail(
                        s"Expected func reference as second compose arg, got: ${prettyPrintAst(other)}"
                      )
                    case None => fail("Expected func reference as second compose arg, got: None")

                  // Third arg should be literal 3
                  composeArgs(2).terms.headOption match
                    case Some(LiteralInt(_, val3)) =>
                      assertEquals(clue(val3), 3, "Third compose arg should be 3")
                    case Some(other) =>
                      fail(
                        s"Expected literal 3 as third compose arg, got: ${prettyPrintAst(other)}"
                      )
                    case None => fail("Expected literal 3 as third compose arg, got: None")

                  // Fourth arg should be literal 4
                  composeArgs(3).terms.headOption match
                    case Some(LiteralInt(_, val4)) =>
                      assertEquals(clue(val4), 4, "Fourth compose arg should be 4")
                    case Some(other) =>
                      fail(
                        s"Expected literal 4 as fourth compose arg, got: ${prettyPrintAst(other)}"
                      )
                    case None => fail("Expected literal 4 as fourth compose arg, got: None")

                  // Fifth arg should be literal 5
                  composeArgs(4).terms.headOption match
                    case Some(LiteralInt(_, val5)) =>
                      assertEquals(clue(val5), 5, "Fifth compose arg should be 5")
                    case Some(other) =>
                      fail(
                        s"Expected literal 5 as fifth compose arg, got: ${prettyPrintAst(other)}"
                      )
                    case None => fail("Expected literal 5 as fifth compose arg, got: None")

                case Some(other) =>
                  fail(s"Expected TXApp for compose function, got: ${prettyPrintAst(other)}")
                case None => fail("Expected TXApp for compose function, got: None")

              // Left side should be apply function
              plusArgs(0).terms.headOption match
                case Some(TXApp(applyRef, _, applyArgs)) =>
                  assertEquals(clue(applyRef.name), "apply", "Apply function name")
                  assertEquals(clue(applyArgs.length), 2, "Apply should have 2 args")

                  // First arg should be (func 1)
                  applyArgs(0).terms.headOption match
                    case Some(TXApp(funcRef, _, funcArgs)) =>
                      assertEquals(clue(funcRef.name), "func", "Inner function name")
                      assertEquals(clue(funcArgs.length), 1, "Func should have 1 arg")

                      // The arg should be literal 1
                      funcArgs(0).terms.headOption match
                        case Some(LiteralInt(_, val1)) =>
                          assertEquals(clue(val1), 1, "func arg should be 1")
                        case _ => fail("Expected literal 1 as func arg")

                    case _ => fail("Expected expression with func application")

                  // Second arg should be literal 2
                  applyArgs(1).terms.headOption match
                    case Some(LiteralInt(_, val2)) =>
                      assertEquals(clue(val2), 2, "Second apply arg should be 2")
                    case _ => fail("Expected literal 2 as second apply arg")

                case _ => fail("Expected TXApp for apply function")

            case other => fail("Expected TXApp structure at top level")
        case other =>
          fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    }
  }

  test("zero-arity function") {
    semNotFailed(
      """
      fn func () = ???;
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
      fn func (a) = ???;
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
      fn func1 (a) = ???;
      fn func2 (b) = ???;
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
      fn func (a) = ???;
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
