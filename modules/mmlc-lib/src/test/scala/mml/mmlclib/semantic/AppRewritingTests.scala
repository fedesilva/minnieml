package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.TestExtractors.* // Import the new extractors
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
import munit.*

class AppRewritingTests extends BaseEffFunSuite:

  test("2 arity function") {
    semNotFailed(
      """
      fn mult (a b) = ???;
      let a = mult 2 2;
      """
    ).map { m => // Start of .map block
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
    } // End of .map block
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
    ).map { m => // Start of .map block
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
    } // End of .map block
  }

  test("curried function application should work without boundaries") {
    semNotFailed(
      """
      fn func (a b) = ???;
      let a = func 1 2 3 4;
      """
    ).map { m => // Start of .map block
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
    } // End of .map block
  }

  test("function application with operators should work") {
    semNotFailed(
      """
      fn func (a b) = ???;
      let a = (func 1 1) + 3 - func 1 2 3;
      """
    ).map { m => // Start of .map block
      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      memberBnd match {
        case bnd: Bnd =>
          // Expect: Expr(_, List( Expr(_, List(AssertApp(Ref("func"), List(Lit(1), Lit(1))), Ref("+"), Lit(3)), _, _), Ref("-"), AssertApp(Ref("func"), List(Lit(1), Lit(2), Lit(3))) ), _, _)
          bnd.value match {
            case Expr(
                  _,
                  List(
                    Expr(
                      _,
                      List(
                        TXApp(
                          ref1,
                          _,
                          List(
                            Expr(_, List(LiteralInt(_, a11Val)), _, _),
                            Expr(_, List(LiteralInt(_, a12Val)), _, _)
                          )
                        ),
                        Ref(_, op1, _, _, _, _),
                        LiteralInt(_, a13Val)
                      ),
                      _,
                      _
                    ),
                    Ref(_, op2, _, _, _, _),
                    TXApp(
                      ref2,
                      _,
                      List(
                        Expr(_, List(LiteralInt(_, a21Val)), _, _),
                        Expr(_, List(LiteralInt(_, a22Val)), _, _),
                        Expr(_, List(LiteralInt(_, a23Val)), _, _)
                      )
                    )
                  ),
                  _,
                  _
                ) =>
              assertEquals(clue(ref1.name), "func", "Func 1 name")
              assertEquals(clue(a11Val), 1, "Func 1 Arg 1")
              assertEquals(clue(a12Val), 1, "Func 1 Arg 2")
              assertEquals(clue(op1), "+", "Operator 1")
              assertEquals(clue(a13Val), 3, "Literal 3")
              assertEquals(clue(op2), "-", "Operator 2")
              assertEquals(clue(ref2.name), "func", "Func 2 name")
              assertEquals(clue(a21Val), 1, "Func 2 Arg 1")
              assertEquals(clue(a22Val), 2, "Func 2 Arg 2")
              assertEquals(clue(a23Val), 3, "Func 2 Arg 3")

            case other =>
              fail(s"Expected complex Expr structure, got: \n${prettyPrintAst(other)}")
          }
        case other =>
          fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    } // End of .map block
  }

  test("complex nested function applications with operators should work") {
    semNotFailed(
      """
      fn func (a b) = ???;
      fn apply (f x) = ???;
      fn compose (f g x) = ???;
      
      let a = apply (func 1) 2 + compose func func 3 4 5;
      """
    ).map { m => // Start of .map block
      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      memberBnd match {
        case bnd: Bnd =>
          // Expect: Expr(_, List( AssertApp(Ref("apply"), List(Expr(App(Ref("func"), Lit(1))), Lit(2))), Ref("+"), AssertApp(Ref("compose"), List(Ref("func"), Ref("func"), Lit(3), Lit(4), Lit(5))) ), _, _)
          bnd.value match {
            case Expr(
                  _,
                  List(
                    TXApp(
                      applyRef,
                      _,
                      List(
                        Expr(
                          _,
                          List(
                            App(
                              _,
                              Ref(_, funcFn, _, _, _, _),
                              Expr(_, List(LiteralInt(_, argF1Val)), _, _),
                              _,
                              _
                            )
                          ),
                          _,
                          _
                        ),
                        Expr(_, List(LiteralInt(_, argA2Val)), _, _)
                      )
                    ),
                    Ref(_, op, _, _, _, _),
                    TXApp(
                      composeRef,
                      _,
                      List(
                        Expr(_, List(Ref(_, argC1Name, _, _, _, _)), _, _),
                        Expr(_, List(Ref(_, argC2Name, _, _, _, _)), _, _),
                        Expr(_, List(LiteralInt(_, argC3Val)), _, _),
                        Expr(_, List(LiteralInt(_, argC4Val)), _, _),
                        Expr(_, List(LiteralInt(_, argC5Val)), _, _)
                      )
                    )
                  ),
                  _,
                  _
                ) =>
              // Check left side
              assertEquals(clue(applyRef.name), "apply", "Apply fn name")
              assertEquals(
                clue(funcFn),
                "func",
                "Func fn name"
              ) // Still need to check inner App structure
              assertEquals(clue(argF1Val), 1, "Func arg 1")
              assertEquals(clue(argA2Val), 2, "Apply arg 2")
              // Check operator
              assertEquals(clue(op), "+", "Operator")
              // Check right side
              assertEquals(clue(composeRef.name), "compose", "Compose fn name")
              assertEquals(clue(argC1Name), "func", "Compose arg 1 (func)")
              assertEquals(clue(argC2Name), "func", "Compose arg 2 (func)")
              assertEquals(clue(argC3Val), 3, "Compose arg 3")
              assertEquals(clue(argC4Val), 4, "Compose arg 4")
              assertEquals(clue(argC5Val), 5, "Compose arg 5")

            case other =>
              fail(
                s"Expected complex Expr structure with nested Apps, got: \n${prettyPrintAst(other)}"
              )
          }
        case other =>
          fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    } // End of .map block
  }

  test("zero-arity function") {
    semNotFailed(
      """
      fn func () = ???;
      let a = func ();
      """
    ).map { m => // Start of .map block
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
    } // End of .map block
  }

  test("function application within if/else") {
    semNotFailed(
      """
      fn func (a) = ???;
      let cond = true;
      let a = if cond then func 1 else func 2;
      """
    ).map { m => // Start of .map block
      val memberBnd = lookupNames("a", m).headOption
        .getOrElse(fail(s"Member `a` not found in module: ${prettyPrintAst(m)}"))

      memberBnd match {
        case bnd: Bnd =>
          // Expect: Expr(_, List(Cond(..., Expr(..., Ref(...)), Expr(..., AssertApp(...)), Expr(..., AssertApp(...)))) :: Nil
          // Note: The Ref("cond") check assumes 'true' resolves to a Ref named "cond". This might need adjustment based on actual resolution.
          bnd.value.terms match { // Match on terms list
            case Cond( // Match Cond inside the list
                  _,
                  Expr(_, List(Ref(_, condName, _, _, _, _)), _, _), // cond
                  Expr(
                    _,
                    List(
                      TXApp(thenRef, _, List(Expr(_, List(LiteralInt(_, thenArgVal)), _, _)))
                    ),
                    _,
                    _
                  ), // ifTrue uses AssertApp
                  Expr(
                    _,
                    List(
                      TXApp(elseRef, _, List(Expr(_, List(LiteralInt(_, elseArgVal)), _, _)))
                    ),
                    _,
                    _
                  ), // ifFalse uses AssertApp
                  _,
                  _
                ) :: Nil => // Ensure Cond is the only element in the list
              assertEquals(clue(condName), "cond", "Condition name mismatch")
              assertEquals(clue(thenRef.name), "func", "Then branch function name")
              assertEquals(clue(thenArgVal), 1, "Then branch argument")
              assertEquals(clue(elseRef.name), "func", "Else branch function name")
              assertEquals(clue(elseArgVal), 2, "Else branch argument")
            case other =>
              // Print the list content for better debugging
              fail(
                s"Expected Cond structure as the only term, got: ${other.map(t => prettyPrintAst(t, 0, false, false)).mkString(", ")}" // Use prettyPrintAst on each term 't'
              )
          }
        case other => fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    } // End of .map block
  }

  test("nested function applications with operators") {
    semNotFailed(
      """
      fn func1 (a) = ???;
      fn func2 (b) = ???;
      let a = func1 (func2 1) + 2;
      """
    ).map { m => // Start of .map block
      val memberBnd = lookupNames("a", m).headOption
        .getOrElse(fail(s"Member `a` not found in module: ${prettyPrintAst(m)}"))

      memberBnd match {
        case bnd: Bnd =>
          // Expect: Expr(_, List( AssertApp(Ref("func1"), List(Expr(AssertApp(Ref("func2"), Lit(1))))), Ref("+"), Lit(2) ), _, _)
          bnd.value match {
            // Use AssertApp for the outer application, and check inner AssertApp within the argument list
            case Expr(
                  _,
                  List(
                    TXApp(
                      ref1,
                      _,
                      List(
                        Expr(
                          _,
                          List(
                            TXApp(ref2, _, List(Expr(_, List(LiteralInt(_, arg1Val)), _, _)))
                          ),
                          _,
                          _
                        )
                      )
                    ),
                    Ref(_, op, _, _, _, _),
                    LiteralInt(_, arg2Val)
                  ),
                  _,
                  _
                ) =>
              assertEquals(clue(ref1.name), "func1", "Outer function name")
              assertEquals(clue(ref2.name), "func2", "Inner function name")
              assertEquals(clue(arg1Val), 1, "Inner argument")
              assertEquals(clue(op), "+", "Operator")
              assertEquals(clue(arg2Val), 2, "Outer argument")
            case other =>
              fail(s"Expected Expr structure with nested Apps, got: ${prettyPrintAst(other)}")
          }
        case other => fail(s"Expected Bnd, got: ${prettyPrintAst(other)}")
      }
    } // End of .map block
  }

  test("single-argument function") {
    semNotFailed(
      """
      fn func (a) = ???;
      let a = func 1;
      """
    ).map { m => // Start of .map block
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
    } // End of .map block
  }
