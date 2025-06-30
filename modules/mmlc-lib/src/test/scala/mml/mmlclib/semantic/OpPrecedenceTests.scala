package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.TestExtractors.*
import mml.mmlclib.util.prettyprint.ast.{prettyPrintAst, prettyPrintList}
import munit.*

class OpPrecedenceTests extends BaseEffFunSuite:

  //
  // All the tests here assume that the following operators are defined:
  //  module Prelude =
  //    op ^ (a b) 90 right = ???;
  //    op * (a b) 80 left  = ???;
  //    op / (a b) 80 left  = ???;
  //    op + (a b) 60 left  = ???;
  //    op - (a b) 60 left  = ???;
  //    op - (a)   95 right = ???;
  //    op + (a)   95 right = ???;
  //  ;
  //
  //

  test("simple binop") {
    semNotFailed(
      """
       let a = 1 + 1;
      """
    ).map { m =>

      val bnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      bnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(opRef, _, args) :: Nil =>
              assertEquals(clue(opRef.name), clue("+"), "Expected + operator")
              assertEquals(clue(args.size), clue(2), "Expected two arguments")

              args match
                case Expr(_, List(LiteralInt(_, 1)), _, _) ::
                    Expr(_, List(LiteralInt(_, 1)), _, _) :: Nil =>
                // Success
                case _ =>
                  fail(s"Expected arguments to be literals 1, got: ${prettyPrintList(args)}")
            case other =>
              fail(s"Expected TXApp pattern, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("multiple binops: + and *") {
    // expect: 1 + (1 * 2)
    semNotFailed(
      """
       let a = 1 + 1 * 2;
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
            case TXApp(plusRef, _, args) :: Nil =>
              // Check outer operator is +
              assertEquals(clue(plusRef.name), clue("+"), "Expected + operator")

              // Check we have two arguments
              assertEquals(clue(args.size), clue(2), "Expected two arguments")

              // First argument should be literal 1
              args.head match
                case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                  assertEquals(clue(firstVal), clue(1), "First argument should be 1")
                case _ =>
                  fail(
                    s"Expected first argument to be literal 1, got: ${prettyPrintAst(args.head)}"
                  )

              // Second argument should be multiplication: 1 * 2
              args(1) match
                case Expr(_, List(TXApp(timesRef, _, timesArgs)), _, _) =>
                  // Check inner operator is *
                  assertEquals(clue(timesRef.name), clue("*"), "Inner operator should be *")

                  // Check inner arguments
                  assertEquals(clue(timesArgs.size), clue(2), "Inner op should have two arguments")

                  // Check first inner arg is literal 1
                  timesArgs.head match
                    case Expr(_, List(LiteralInt(_, innerFirstVal)), _, _) =>
                      assertEquals(clue(innerFirstVal), clue(1), "Inner first arg should be 1")
                    case _ =>
                      fail(
                        s"Expected inner first arg to be literal 1, got: ${prettyPrintAst(timesArgs.head)}"
                      )

                  // Check second inner arg is literal 2
                  timesArgs(1) match
                    case Expr(_, List(LiteralInt(_, innerSecondVal)), _, _) =>
                      assertEquals(clue(innerSecondVal), clue(2), "Inner second arg should be 2")
                    case _ =>
                      fail(
                        s"Expected inner second arg to be literal 2, got: ${prettyPrintAst(timesArgs(1))}"
                      )
                case _ =>
                  fail(
                    s"Expected second argument to be an App representing multiplication, got: ${prettyPrintAst(args(1))}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for addition, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")

    }

  }

  test("multiple binops: + and * and /") {
    // expect: 1 + ((1 * 2) / 3)
    semNotFailed(
      """
       let a = 1 + 1 * 2 / 3;
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
            case TXApp(plusRef, _, firstArg :: secondArg :: Nil) :: Nil =>
              // Check outer operator is +
              assertEquals(clue(plusRef.name), clue("+"), "Expected + operator")

              // First argument should be literal 1
              firstArg match
                case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                  assertEquals(clue(firstVal), clue(1), "First argument should be 1")
                case _ =>
                  fail(s"Expected first argument to be literal 1, got: ${prettyPrintAst(firstArg)}")

              // Second argument should be division: (1 * 2) / 3
              secondArg match
                case Expr(_, List(TXApp(divRef, _, multExpr :: lit3Expr :: Nil)), _, _) =>
                  // Check inner operator is /
                  assertEquals(clue(divRef.name), clue("/"), "Inner operator should be /")

                  // First argument should be multiplication: 1 * 2
                  multExpr match
                    case Expr(_, List(TXApp(timesRef, _, lit1Expr :: lit2Expr :: Nil)), _, _) =>
                      // Check multiplication operator
                      assertEquals(clue(timesRef.name), clue("*"), "Expected * operator")

                      // Check first mult arg is literal 1
                      lit1Expr match
                        case Expr(_, List(LiteralInt(_, multFirstVal)), _, _) =>
                          assertEquals(
                            clue(multFirstVal),
                            clue(1),
                            "Multiplication first arg should be 1"
                          )
                        case _ =>
                          fail(
                            s"Expected multiplication first arg to be literal 1, got: ${prettyPrintAst(lit1Expr)}"
                          )

                      // Check second mult arg is literal 2
                      lit2Expr match
                        case Expr(_, List(LiteralInt(_, multSecondVal)), _, _) =>
                          assertEquals(
                            clue(multSecondVal),
                            clue(2),
                            "Multiplication second arg should be 2"
                          )
                        case _ =>
                          fail(
                            s"Expected multiplication second arg to be literal 2, got: ${prettyPrintAst(lit2Expr)}"
                          )
                    case _ =>
                      fail(
                        s"Expected first division arg to be multiplication expression, got: ${prettyPrintAst(multExpr)}"
                      )

                  // Second argument should be literal 3
                  lit3Expr match
                    case Expr(_, List(LiteralInt(_, divSecondVal)), _, _) =>
                      assertEquals(clue(divSecondVal), clue(3), "Division second arg should be 3")
                    case _ =>
                      fail(
                        s"Expected division second arg to be literal 3, got: ${prettyPrintAst(lit3Expr)}"
                      )
                case _ =>
                  fail(
                    s"Expected second argument to be a division expression, got: ${prettyPrintAst(secondArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for addition, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("multiple binops: +, *,^ - left and right assoc") {
    // expect: 1 + (1 * (2 ^ 3))
    semNotFailed(
      """
       let a = 1 + 1 * 2 ^ 3;
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
            case TXApp(plusRef, _, firstArg :: secondArg :: Nil) :: Nil =>
              // Check outer operator is +
              assertEquals(clue(plusRef.name), clue("+"), "Expected + operator")

              // First argument should be literal 1
              firstArg match
                case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                  assertEquals(clue(firstVal), clue(1), "First argument should be 1")
                case _ =>
                  fail(s"Expected first argument to be literal 1, got: ${prettyPrintAst(firstArg)}")

              // Second argument should be multiplication: 1 * (2 ^ 3)
              secondArg match
                case Expr(_, List(TXApp(mulRef, _, mulFirstArg :: mulSecondArg :: Nil)), _, _) =>
                  // Check inner operator is *
                  assertEquals(clue(mulRef.name), clue("*"), "Inner operator should be *")

                  // First multiplication argument should be literal 1
                  mulFirstArg match
                    case Expr(_, List(LiteralInt(_, multFirstVal)), _, _) =>
                      assertEquals(
                        clue(multFirstVal),
                        clue(1),
                        "Multiplication first arg should be 1"
                      )
                    case _ =>
                      fail(
                        s"Expected multiplication first arg to be literal 1, got: ${prettyPrintAst(mulFirstArg)}"
                      )

                  // Second multiplication argument should be exponentiation: 2 ^ 3
                  mulSecondArg match
                    case Expr(
                          _,
                          List(TXApp(expRef, _, expFirstArg :: expSecondArg :: Nil)),
                          _,
                          _
                        ) =>
                      // Check innermost operator is ^
                      assertEquals(clue(expRef.name), clue("^"), "Innermost operator should be ^")

                      // First exponentiation arg should be literal 2
                      expFirstArg match
                        case Expr(_, List(LiteralInt(_, expFirstVal)), _, _) =>
                          assertEquals(
                            clue(expFirstVal),
                            clue(2),
                            "Exponentiation first arg should be 2"
                          )
                        case _ =>
                          fail(
                            s"Expected exponentiation first arg to be literal 2, got: ${prettyPrintAst(expFirstArg)}"
                          )

                      // Second exponentiation arg should be literal 3
                      expSecondArg match
                        case Expr(_, List(LiteralInt(_, expSecondVal)), _, _) =>
                          assertEquals(
                            clue(expSecondVal),
                            clue(3),
                            "Exponentiation second arg should be 3"
                          )
                        case _ =>
                          fail(
                            s"Expected exponentiation second arg to be literal 3, got: ${prettyPrintAst(expSecondArg)}"
                          )
                    case _ =>
                      fail(
                        s"Expected second multiplication arg to be exponentiation, got: ${prettyPrintAst(mulSecondArg)}"
                      )
                case _ =>
                  fail(
                    s"Expected second argument to be a multiplication expression, got: ${prettyPrintAst(secondArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for addition, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("unary postfix: 4!") {
    // expect: 4!
    semNotFailed(
      """
       op ! (a) 95 left = ???;
       let a = 4!;
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
            case TXApp(factRef, _, args) :: Nil =>
              // Check operator is !
              assertEquals(clue(factRef.name), clue("!"), "Expected ! operator")

              // Check we have one argument
              assertEquals(clue(args.size), clue(1), "Expected one argument")

              // Argument should be literal 4
              args.head match
                case Expr(_, List(LiteralInt(_, factVal)), _, _) =>
                  assertEquals(clue(factVal), clue(4), "Argument should be 4")
                case _ =>
                  fail(s"Expected argument to be literal 4, got: ${prettyPrintAst(args.head)}")
            case other =>
              fail(s"Expected TXApp pattern for factorial, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("unary pre, post and binop: +4! - 2!") {
    // expect: ((+ (4!)) - (2!))
    semNotFailed(
      """
       op ! (a) 95 left = ???;
       let a = +4! - 2!;
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
            case TXApp(minusRef, _, leftArg :: rightArg :: Nil) :: Nil =>
              // Check outer operator is -
              assertEquals(clue(minusRef.name), clue("-"), "Expected - operator")

              // Left argument should be unary plus on factorial: +4!
              leftArg match
                case Expr(_, List(TXApp(plusRef, _, plusArgs)), _, _) =>
                  // Check plus operator
                  assertEquals(clue(plusRef.name), clue("+"), "Expected + operator")
                  assertEquals(clue(plusArgs.size), clue(1), "Expected one argument to +")

                  // The argument to + should be factorial 4!
                  plusArgs.head match
                    case Expr(_, List(TXApp(factRef, _, factArgs)), _, _) =>
                      // Check factorial operator
                      assertEquals(clue(factRef.name), clue("!"), "Expected ! operator")
                      assertEquals(clue(factArgs.size), clue(1), "Expected one argument to !")

                      // Check factorial argument is 4
                      factArgs.head match
                        case Expr(_, List(LiteralInt(_, factVal)), _, _) =>
                          assertEquals(clue(factVal), clue(4), "Factorial argument should be 4")
                        case _ =>
                          fail(
                            s"Expected factorial argument to be 4, got: ${prettyPrintAst(factArgs.head)}"
                          )
                    case _ =>
                      fail(
                        s"Expected + argument to be factorial expression, got: ${prettyPrintAst(plusArgs.head)}"
                      )
                case _ =>
                  fail(
                    s"Expected left subtraction argument to be +4!, got: ${prettyPrintAst(leftArg)}"
                  )

              // Right argument should be factorial: 2!
              rightArg match
                case Expr(_, List(TXApp(factRef, _, factArgs)), _, _) =>
                  // Check factorial operator
                  assertEquals(clue(factRef.name), clue("!"), "Expected ! operator")
                  assertEquals(clue(factArgs.size), clue(1), "Expected one argument to !")

                  // Check factorial argument is 2
                  factArgs.head match
                    case Expr(_, List(LiteralInt(_, factVal)), _, _) =>
                      assertEquals(clue(factVal), clue(2), "Factorial argument should be 2")
                    case _ =>
                      fail(
                        s"Expected factorial argument to be 2, got: ${prettyPrintAst(factArgs.head)}"
                      )
                case _ =>
                  fail(
                    s"Expected right subtraction argument to be 2!, got: ${prettyPrintAst(rightArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for subtraction, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("functions are people, too: unary pre, post and binop: +4! - 2!") {
    // expect: ((+ (4!)) - (2!))
    semNotFailed(
      """
       op ! (a) 95 left = ???;
       fn a() = +4! - 2!;
      """
    ).map { m =>

      val memberBnd =
        lookupNames("a", m).headOption
          .getOrElse(
            fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
          )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case fnDef: FnDef =>
          fnDef.body.terms match
            case TXApp(minusRef, _, leftArg :: rightArg :: Nil) :: Nil =>
              // Check outer operator is -
              assertEquals(clue(minusRef.name), clue("-"), "Expected - operator")

              // Left argument should be unary plus on factorial: +4!
              leftArg match
                case Expr(_, List(TXApp(plusRef, _, plusArgs)), _, _) =>
                  // Check plus operator
                  assertEquals(clue(plusRef.name), clue("+"), "Expected + operator")
                  assertEquals(clue(plusArgs.size), clue(1), "Expected one argument to +")

                  // The argument to + should be factorial 4!
                  plusArgs.head match
                    case Expr(_, List(TXApp(factRef, _, factArgs)), _, _) =>
                      // Check factorial operator
                      assertEquals(clue(factRef.name), clue("!"), "Expected ! operator")
                      assertEquals(clue(factArgs.size), clue(1), "Expected one argument to !")

                      // Check factorial argument is 4
                      factArgs.head match
                        case Expr(_, List(LiteralInt(_, factVal)), _, _) =>
                          assertEquals(clue(factVal), clue(4), "Factorial argument should be 4")
                        case _ =>
                          fail(
                            s"Expected factorial argument to be 4, got: ${prettyPrintAst(factArgs.head)}"
                          )
                    case _ =>
                      fail(
                        s"Expected + argument to be factorial expression, got: ${prettyPrintAst(plusArgs.head)}"
                      )
                case _ =>
                  fail(
                    s"Expected left subtraction argument to be +4!, got: ${prettyPrintAst(leftArg)}"
                  )

              // Right argument should be factorial: 2!
              rightArg match
                case Expr(_, List(TXApp(factRef, _, factArgs)), _, _) =>
                  // Check factorial operator
                  assertEquals(clue(factRef.name), clue("!"), "Expected ! operator")
                  assertEquals(clue(factArgs.size), clue(1), "Expected one argument to !")

                  // Check factorial argument is 2
                  factArgs.head match
                    case Expr(_, List(LiteralInt(_, factVal)), _, _) =>
                      assertEquals(clue(factVal), clue(2), "Factorial argument should be 2")
                    case _ =>
                      fail(
                        s"Expected factorial argument to be 2, got: ${prettyPrintAst(factArgs.head)}"
                      )
                case _ =>
                  fail(
                    s"Expected right subtraction argument to be 2!, got: ${prettyPrintAst(rightArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for subtraction, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a FnDef, got: ${prettyPrintAst(x)}")
    }
  }

  test("leading unary: -3") {
    // expect: (-3)
    semNotFailed(
      """
       let a = -3;
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
            case TXApp(minusRef, _, args) :: Nil =>
              // Check operator is unary -
              assertEquals(clue(minusRef.name), clue("-"), "Expected - operator")

              // Check we have one argument
              assertEquals(clue(args.size), clue(1), "Expected one argument")

              // Argument should be literal 3
              args.head match
                case Expr(_, List(LiteralInt(_, val3)), _, _) =>
                  assertEquals(clue(val3), clue(3), "Argument should be 3")
                case _ =>
                  fail(s"Expected argument to be literal 3, got: ${prettyPrintAst(args.head)}")
            case other =>
              fail(s"Expected TXApp pattern for unary minus, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("leading unary, binop and unary: -3 - -2") {
    // expect: (-3) - (-2)
    semNotFailed(
      """
       let a = -3 - -2;
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
            case TXApp(minusRef, _, leftArg :: rightArg :: Nil) :: Nil =>
              // Check outer operator is binary -
              assertEquals(clue(minusRef.name), clue("-"), "Expected - operator")

              // Left argument should be unary minus: -3
              leftArg match
                case Expr(_, List(TXApp(unaryMinusRef, _, unaryArgs)), _, _) =>
                  // Check unary minus operator
                  assertEquals(clue(unaryMinusRef.name), clue("-"), "Expected - operator")
                  assertEquals(clue(unaryArgs.size), clue(1), "Expected one argument to unary -")

                  // Check unary minus argument is 3
                  unaryArgs.head match
                    case Expr(_, List(LiteralInt(_, val3)), _, _) =>
                      assertEquals(clue(val3), clue(3), "Unary minus argument should be 3")
                    case _ =>
                      fail(
                        s"Expected unary minus argument to be 3, got: ${prettyPrintAst(unaryArgs.head)}"
                      )
                case _ =>
                  fail(
                    s"Expected left subtraction argument to be -3, got: ${prettyPrintAst(leftArg)}"
                  )

              // Right argument should be unary minus: -2
              rightArg match
                case Expr(_, List(TXApp(unaryMinusRef, _, unaryArgs)), _, _) =>
                  // Check unary minus operator
                  assertEquals(clue(unaryMinusRef.name), clue("-"), "Expected - operator")
                  assertEquals(clue(unaryArgs.size), clue(1), "Expected one argument to unary -")

                  // Check unary minus argument is 2
                  unaryArgs.head match
                    case Expr(_, List(LiteralInt(_, val2)), _, _) =>
                      assertEquals(clue(val2), clue(2), "Unary minus argument should be 2")
                    case _ =>
                      fail(
                        s"Expected unary minus argument to be 2, got: ${prettyPrintAst(unaryArgs.head)}"
                      )
                case _ =>
                  fail(
                    s"Expected right subtraction argument to be -2, got: ${prettyPrintAst(rightArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for subtraction, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("start with group: (1 + 2) * 3 ") {
    // expect: (1 + 2) * 3
    semNotFailed(
      """
       let a = (1 + 2) * 3;
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
            case TXApp(timesRef, _, leftArg :: rightArg :: Nil) :: Nil =>
              // Check outer operator is *
              assertEquals(clue(timesRef.name), clue("*"), "Expected * operator")

              // Left argument should be the result of (1 + 2)
              leftArg match
                case Expr(_, List(TXApp(plusRef, _, plusArgs)), _, _) =>
                  // Check inner operator is +
                  assertEquals(clue(plusRef.name), clue("+"), "Expected + operator inside group")
                  assertEquals(clue(plusArgs.size), clue(2), "Expected two arguments to +")

                  // First argument to + should be 1, second should be 2
                  plusArgs match
                    case firstArg :: secondArg :: Nil =>
                      firstArg match
                        case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                          assertEquals(clue(firstVal), clue(1), "First argument to + should be 1")
                        case _ =>
                          fail(s"Expected first argument to + to be literal 1, got: $firstArg")

                      // Second argument to + should be 2
                      secondArg match
                        case Expr(_, List(LiteralInt(_, secondVal)), _, _) =>
                          assertEquals(clue(secondVal), clue(2), "Second argument to + should be 2")
                        case _ =>
                          fail(
                            s"Expected second argument to + to be literal 2, got: ${prettyPrintAst(secondArg)}"
                          )
                    case _ =>
                      fail(
                        s"Expected two arguments for the + expression, got: ${prettyPrintList(plusArgs)}"
                      )
                case _ =>
                  fail(
                    s"Expected left argument to be addition expression (1 + 2), got: ${prettyPrintAst(leftArg)}"
                  )

              // Right argument should be literal 3
              rightArg match
                case Expr(_, List(LiteralInt(_, val3)), _, _) =>
                  assertEquals(clue(val3), clue(3), "Right argument should be 3")
                case _ =>
                  fail(s"Expected right argument to be literal 3, got: ${prettyPrintAst(rightArg)}")
            case other =>
              fail(s"Expected TXApp pattern for multiplication, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  // Left-associativity of subtraction:
  // let a = 1 - 2 - 3;
  // (Should parse as ((1 - 2) - 3).)
  test("left-associativity of subtraction: 1 - 2 - 3") {
    semNotFailed(
      """
       let a = 1 - 2 - 3;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(outerMinusRef, _, firstArg :: secondArg :: Nil) :: Nil =>
              // Check outer operator is -
              assertEquals(clue(outerMinusRef.name), clue("-"), "Expected outer - operator")

              // First argument should be the result of 1 - 2
              firstArg match
                case Expr(
                      _,
                      List(TXApp(innerMinusRef, _, innerFirstArg :: innerSecondArg :: Nil)),
                      _,
                      _
                    ) =>
                  // Check inner operator is -
                  assertEquals(clue(innerMinusRef.name), clue("-"), "Expected inner - operator")

                  // First inner argument should be literal 1
                  innerFirstArg match
                    case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                      assertEquals(clue(firstVal), clue(1), "First argument should be 1")
                    case _ =>
                      fail(
                        s"Expected first inner argument to be literal 1, got: ${prettyPrintAst(innerFirstArg)}"
                      )

                  // Second inner argument should be literal 2
                  innerSecondArg match
                    case Expr(_, List(LiteralInt(_, secondVal)), _, _) =>
                      assertEquals(clue(secondVal), clue(2), "Second inner argument should be 2")
                    case _ =>
                      fail(
                        s"Expected second inner argument to be literal 2, got: ${prettyPrintAst(innerSecondArg)}"
                      )
                case _ =>
                  fail(
                    s"Expected first argument to be a subtraction expression (1 - 2), got: ${prettyPrintAst(firstArg)}"
                  )

              // Second argument to outer subtraction should be literal 3
              secondArg match
                case Expr(_, List(LiteralInt(_, thirdVal)), _, _) =>
                  assertEquals(clue(thirdVal), clue(3), "Second outer argument should be 3")
                case _ =>
                  fail(
                    s"Expected second outer argument to be literal 3, got: ${prettyPrintAst(secondArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for subtraction, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  // Right-associativity of exponentiation:
  // let a = 2 ^ 3 ^ 2;
  // (Expected as 2 ^ (3 ^ 2).)
  test("right-associativity of exponentiation: 2 ^ 3 ^ 2") {
    semNotFailed(
      """
       let a = 2 ^ 3 ^ 2;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(outerExpRef, _, firstArg :: secondArg :: Nil) :: Nil =>
              // Check outer operator is ^
              assertEquals(clue(outerExpRef.name), clue("^"), "Expected outer ^ operator")

              // First argument should be literal 2
              firstArg match
                case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                  assertEquals(clue(firstVal), clue(2), "First argument should be 2")
                case _ =>
                  fail(s"Expected first argument to be literal 2, got: ${prettyPrintAst(firstArg)}")

              // Second argument should be the result of 3 ^ 2
              secondArg match
                case Expr(
                      _,
                      List(TXApp(innerExpRef, _, innerFirstArg :: innerSecondArg :: Nil)),
                      _,
                      _
                    ) =>
                  // Check inner operator is ^
                  assertEquals(clue(innerExpRef.name), clue("^"), "Expected inner ^ operator")

                  // First inner argument should be literal 3
                  innerFirstArg match
                    case Expr(_, List(LiteralInt(_, thirdVal)), _, _) =>
                      assertEquals(clue(thirdVal), clue(3), "First inner argument should be 3")
                    case _ =>
                      fail(
                        s"Expected first inner argument to be literal 3, got: ${prettyPrintAst(innerFirstArg)}"
                      )

                  // Second inner argument should be literal 2
                  innerSecondArg match
                    case Expr(_, List(LiteralInt(_, fourthVal)), _, _) =>
                      assertEquals(clue(fourthVal), clue(2), "Second inner argument should be 2")
                    case _ =>
                      fail(
                        s"Expected second inner argument to be literal 2, got: ${prettyPrintAst(innerSecondArg)}"
                      )
                case _ =>
                  fail(
                    s"Expected second argument to be an exponentiation expression (3 ^ 2), got: ${prettyPrintAst(secondArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for exponentiation, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  // Unary minus with exponentiation:
  // let a = -2 ^ 2;
  // (Checks that it is parsed as -(2 ^ 2) rather than (-2) ^ 2.)
  test("unary minus with exponentiation: -2 ^ 2") {
    semNotFailed(
      """
       let a = -2 ^ 2;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(expRef, _, firstArg :: secondArg :: Nil) :: Nil =>
              // Check outer operator is ^
              assertEquals(clue(expRef.name), clue("^"), "Expected ^ operator")

              // First argument should be unary minus: -2
              firstArg match
                case Expr(_, List(TXApp(minusRef, _, minusArgs)), _, _) =>
                  // Check unary minus operator
                  assertEquals(clue(minusRef.name), clue("-"), "Expected - operator")
                  assertEquals(clue(minusArgs.size), clue(1), "Expected one argument to unary -")

                  // The argument to unary minus should be literal 2
                  minusArgs.head match
                    case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                      assertEquals(clue(firstVal), clue(2), "Argument to unary - should be 2")
                    case _ =>
                      fail(
                        s"Expected argument to unary - to be literal 2, got: ${prettyPrintAst(minusArgs.head)}"
                      )
                case _ =>
                  fail(
                    s"Expected first argument to be unary - expression, got: ${prettyPrintAst(firstArg)}"
                  )

              // Second argument should be literal 2
              secondArg match
                case Expr(_, List(LiteralInt(_, secondVal)), _, _) =>
                  assertEquals(clue(secondVal), clue(2), "Second argument should be 2")
                case _ =>
                  fail(
                    s"Expected second argument to be literal 2, got: ${prettyPrintAst(secondArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for exponentiation, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  // Chained unary operators:
  // let a = - - 3;
  // (Tests applying multiple prefix operators in sequence.)
  test("chained unary operators: - - 3") {
    semNotFailed(
      """
       let a = - - 3;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(outerMinusRef, _, args) :: Nil =>
              // Check outer operator is unary -
              assertEquals(clue(outerMinusRef.name), clue("-"), "Expected outer - operator")

              // Check we have one argument
              assertEquals(clue(args.size), clue(1), "Expected one argument")

              // The argument should be another unary minus (- 3)
              args.head match
                case Expr(_, List(TXApp(innerMinusRef, _, innerArgs)), _, _) =>
                  // Check inner operator is also -
                  assertEquals(clue(innerMinusRef.name), clue("-"), "Inner operator should be -")
                  assertEquals(
                    clue(innerArgs.size),
                    clue(1),
                    "Inner operator should have one argument"
                  )

                  // Inner argument should be literal 3
                  innerArgs.head match
                    case Expr(_, List(LiteralInt(_, val3)), _, _) =>
                      assertEquals(clue(val3), clue(3), "Inner argument should be 3")
                    case _ =>
                      fail(
                        s"Expected inner argument to be literal 3, got: ${prettyPrintAst(innerArgs.head)}"
                      )
                case _ =>
                  fail(
                    s"Expected argument to be a unary minus expression (- 3), got: ${prettyPrintAst(args.head)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for unary minus, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  // Complex grouping with multiple binops:
  // let a = (1 + 2) * (3 - 4) / 5;
  // (Verifies that grouping changes the default precedence.)
  test("complex grouping with multiple binops: (1 + 2) * (3 - 4) / 5") {
    semNotFailed(
      """
       let a = (1 + 2) * (3 - 4) / 5;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(divRef, _, firstArg :: secondArg :: Nil) :: Nil =>
              // Check outer operator is /
              assertEquals(clue(divRef.name), clue("/"), "Expected outer / operator")

              // First argument should be multiplication: (1 + 2) * (3 - 4)
              firstArg match
                case Expr(_, List(TXApp(mulRef, _, mulFirstArg :: mulSecondArg :: Nil)), _, _) =>
                  // Check multiplication operator
                  assertEquals(clue(mulRef.name), clue("*"), "Inner operator should be *")

                  // First multiplication argument should be (1 + 2)
                  mulFirstArg match
                    case Expr(
                          _,
                          List(TXApp(plusRef, _, plusFirstArg :: plusSecondArg :: Nil)),
                          _,
                          _
                        ) =>
                      // Check plus operator
                      assertEquals(clue(plusRef.name), clue("+"), "Left group operator should be +")

                      // Check literals in the addition
                      plusFirstArg match
                        case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                          assertEquals(
                            clue(firstVal),
                            clue(1),
                            "First argument in addition should be 1"
                          )
                        case _ =>
                          fail(
                            s"Expected first argument in addition to be literal 1, got: ${prettyPrintAst(plusFirstArg)}"
                          )

                      plusSecondArg match
                        case Expr(_, List(LiteralInt(_, secondVal)), _, _) =>
                          assertEquals(
                            clue(secondVal),
                            clue(2),
                            "Second argument in addition should be 2"
                          )
                        case _ =>
                          fail(
                            s"Expected second argument in addition to be literal 2, got: ${prettyPrintAst(plusSecondArg)}"
                          )
                    case _ =>
                      fail(
                        s"Expected first multiplication argument to be (1 + 2), got: ${prettyPrintAst(mulFirstArg)}"
                      )

                  // Second multiplication argument should be (3 - 4)
                  mulSecondArg match
                    case Expr(
                          _,
                          List(TXApp(minusRef, _, minusFirstArg :: minusSecondArg :: Nil)),
                          _,
                          _
                        ) =>
                      // Check minus operator
                      assertEquals(
                        clue(minusRef.name),
                        clue("-"),
                        "Right group operator should be -"
                      )

                      // Check literals in the subtraction
                      minusFirstArg match
                        case Expr(_, List(LiteralInt(_, thirdVal)), _, _) =>
                          assertEquals(
                            clue(thirdVal),
                            clue(3),
                            "First argument in subtraction should be 3"
                          )
                        case _ =>
                          fail(
                            s"Expected first argument in subtraction to be literal 3, got: ${prettyPrintAst(minusFirstArg)}"
                          )

                      minusSecondArg match
                        case Expr(_, List(LiteralInt(_, fourthVal)), _, _) =>
                          assertEquals(
                            clue(fourthVal),
                            clue(4),
                            "Second argument in subtraction should be 4"
                          )
                        case _ =>
                          fail(
                            s"Expected second argument in subtraction to be literal 4, got: ${prettyPrintAst(minusSecondArg)}"
                          )
                    case _ =>
                      fail(
                        s"Expected second multiplication argument to be (3 - 4), got: ${prettyPrintAst(mulSecondArg)}"
                      )
                case _ =>
                  fail(
                    s"Expected first division argument to be a multiplication, got: ${prettyPrintAst(firstArg)}"
                  )

              // Second argument to division should be literal 5
              secondArg match
                case Expr(_, List(LiteralInt(_, fifthVal)), _, _) =>
                  assertEquals(clue(fifthVal), clue(5), "Second division argument should be 5")
                case _ =>
                  fail(
                    s"Expected second division argument to be literal 5, got: ${prettyPrintAst(secondArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for division, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  // Consecutive postfix operators (not allowed):
  // let a = 4!!;
  // (Tests applying a postfix operator twice in a row.)
  test("consecutive postfix operators: 4!!") {
    semFailed(
      """
       op ! (a) 95 left = ???;
       let a = 4!!;
      """
    )
  }

  // Mix of unary prefix operators:
  // let a = + - 3;
  // (Ensures that combining a positive and negative unary operator on the same literal is handled correctly.)
  test("mix of unary prefix operators: + - 3") {
    semNotFailed(
      """
       let a = + - 3;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(plusRef, _, args) :: Nil =>
              // Check outer operator is unary +
              assertEquals(clue(plusRef.name), clue("+"), "Expected outer + operator")

              // Check we have one argument
              assertEquals(clue(args.size), clue(1), "Expected one argument")

              // The argument should be unary minus (- 3)
              args.head match
                case Expr(_, List(TXApp(minusRef, _, innerArgs)), _, _) =>
                  // Check inner operator is -
                  assertEquals(clue(minusRef.name), clue("-"), "Inner operator should be -")
                  assertEquals(
                    clue(innerArgs.size),
                    clue(1),
                    "Inner operator should have one argument"
                  )

                  // Inner argument should be literal 3
                  innerArgs.head match
                    case Expr(_, List(LiteralInt(_, val3)), _, _) =>
                      assertEquals(clue(val3), clue(3), "Inner argument should be 3")
                    case _ =>
                      fail(
                        s"Expected inner argument to be literal 3, got: ${prettyPrintAst(innerArgs.head)}"
                      )
                case _ =>
                  fail(
                    s"Expected argument to be a unary minus expression (- 3), got: ${prettyPrintAst(args.head)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for unary plus, got: $other")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("multi-character binary operator: 3 -- -4") {
    semNotFailed(
      """
       op -- (a b) = ???;
       let a = 3 -- -4;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(doubleMinusRef, _, firstArg :: secondArg :: Nil) :: Nil =>
              // Check operator is --
              assertEquals(clue(doubleMinusRef.name), clue("--"), "Expected -- operator")

              // First argument should be literal 3
              firstArg match
                case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                  assertEquals(clue(firstVal), clue(3), "First argument should be 3")
                case _ =>
                  fail(s"Expected first argument to be literal 3, got: $firstArg")

              // Second argument should be unary minus: -4
              secondArg match
                case Expr(_, List(TXApp(minusRef, _, minusArgs)), _, _) =>
                  // Check unary minus operator
                  assertEquals(clue(minusRef.name), clue("-"), "Expected - operator")
                  assertEquals(clue(minusArgs.size), clue(1), "Expected one argument to unary -")

                  // Check unary minus argument is 4
                  minusArgs.head match
                    case Expr(_, List(LiteralInt(_, val4)), _, _) =>
                      assertEquals(clue(val4), clue(4), "Unary minus argument should be 4")
                    case _ =>
                      fail(
                        s"Expected unary minus argument to be 4, got: ${prettyPrintAst(minusArgs.head)}"
                      )
                case _ =>
                  fail(s"Expected second argument to be -4, got: ${prettyPrintAst(secondArg)}")
            case other =>
              fail(s"Expected TXApp pattern for -- operator, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  // === RECOMMENDED ADDITIONAL TESTS FOR OPERATOR PRECEDENCE ===

// Test mixed associativity without parentheses
// let a = 1 + 2 ^ 3 + 4;  // Should parse as: 1 + (2 ^ 3) + 4
// Verifies correct handling of mixed left and right associative operators in sequence

  test("mixed associativity without parentheses: 1 + 2 ^ 3 + 4") {
    semNotFailed(
      """
       let a = 1 + 2 ^ 3 + 4;
      """
    ).map { m =>
      val memberBnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )

      // println(prettyPrintAst(memberBnd))

      memberBnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(outerPlusRef, _, firstArg :: secondArg :: Nil) :: Nil =>
              // Check outer operator is +
              assertEquals(clue(outerPlusRef.name), clue("+"), "Expected outer + operator")

              // First argument should be addition: 1 + (2 ^ 3)
              firstArg match
                case Expr(
                      _,
                      List(TXApp(innerPlusRef, _, innerFirstArg :: innerSecondArg :: Nil)),
                      _,
                      _
                    ) =>
                  // Check inner operator is also +
                  assertEquals(clue(innerPlusRef.name), clue("+"), "Inner operator should be +")

                  // First inner argument should be literal 1
                  innerFirstArg match
                    case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                      assertEquals(clue(firstVal), clue(1), "First argument to inner + should be 1")
                    case _ =>
                      fail(
                        s"Expected first argument to inner + to be literal 1, got: $innerFirstArg"
                      )

                  // Second inner argument should be exponentiation: 2 ^ 3
                  innerSecondArg match
                    case Expr(
                          _,
                          List(TXApp(expRef, _, expFirstArg :: expSecondArg :: Nil)),
                          _,
                          _
                        ) =>
                      // Check exponentiation operator
                      assertEquals(clue(expRef.name), clue("^"), "Expected ^ operator")

                      // Check exponentiation arguments
                      expFirstArg match
                        case Expr(_, List(LiteralInt(_, secondVal)), _, _) =>
                          assertEquals(clue(secondVal), clue(2), "First argument to ^ should be 2")
                        case _ =>
                          fail(s"Expected first argument to ^ to be literal 2, got: $expFirstArg")

                      expSecondArg match
                        case Expr(_, List(LiteralInt(_, thirdVal)), _, _) =>
                          assertEquals(clue(thirdVal), clue(3), "Second argument to ^ should be 3")
                        case _ =>
                          fail(s"Expected second argument to ^ to be literal 3, got: $expSecondArg")
                    case _ =>
                      fail(
                        s"Expected second argument to inner + to be exponentiation (2 ^ 3), got: $innerSecondArg"
                      )
                case _ =>
                  fail(
                    s"Expected first argument to outer + to be addition (1 + (2 ^ 3)), got: $firstArg"
                  )

              // Second argument to outer addition should be literal 4
              secondArg match
                case Expr(_, List(LiteralInt(_, fourthVal)), _, _) =>
                  assertEquals(clue(fourthVal), clue(4), "Second argument to outer + should be 4")
                case _ =>
                  fail(s"Expected second argument to outer + to be literal 4, got: $secondArg")
            case other =>
              fail(s"Expected TXApp pattern for addition, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

// Test operators with the same symbol but different arity
// op ++ (a) = a + 1;
// op ++ (a b) = a + b;
// let a = 1 ++ 2;  // Binary usage
// let b = ++1;     // Unary prefix usage
// Verifies disambiguation between unary and binary variants of the same operator

  test("Test operators with the same symbol but different arity") {
    semNotFailed(
      """
       op ++ (a) = a + 1;
       op ++ (a b) = a + b;
       let a = 1 ++ 2;
       let b = ++1;
      """
    ).map { m =>

      val memberBndA = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )

      val memberBndB = lookupNames("b", m).headOption.getOrElse(
        fail(s"Member `b` not found in module: ${prettyPrintAst(m)}")
      )

      // println(prettyPrintAst(m))

      memberBndA match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(binaryPlusPlusRef, _, firstArg :: secondArg :: Nil) :: Nil =>
              // Check operator is ++
              assertEquals(clue(binaryPlusPlusRef.name), clue("++"), "Expected ++ operator")

              // First argument should be literal 1
              firstArg match
                case Expr(_, List(LiteralInt(_, firstVal)), _, _) =>
                  assertEquals(clue(firstVal), clue(1), "First argument should be 1")
                case _ =>
                  fail(s"Expected first argument to be literal 1, got: ${prettyPrintAst(firstArg)}")

              // Second argument should be literal 2
              secondArg match
                case Expr(_, List(LiteralInt(_, secondVal)), _, _) =>
                  assertEquals(clue(secondVal), clue(2), "Second argument should be 2")
                case _ =>
                  fail(
                    s"Expected second argument to be literal 2, got: ${prettyPrintAst(secondArg)}"
                  )
            case other =>
              fail(s"Expected TXApp pattern for binary ++, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")

      memberBndB match
        case bnd: Bnd =>
          bnd.value.terms match
            case TXApp(unaryPlusPlusRef, _, args) :: Nil =>
              // Check operator is ++
              assertEquals(clue(unaryPlusPlusRef.name), clue("++"), "Expected ++ operator")

              // Check we have one argument
              assertEquals(clue(args.size), clue(1), "Expected one argument")

              // Argument should be literal 1
              args.head match
                case Expr(_, List(LiteralInt(_, val1)), _, _) =>
                  assertEquals(clue(val1), clue(1), "Argument should be 1")
                case _ =>
                  fail(s"Expected argument to be literal 1, got: ${prettyPrintAst(args.head)}")
            case other =>
              fail(s"Expected TXApp pattern for unary ++, got: ${prettyPrintList(other)}")
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }
