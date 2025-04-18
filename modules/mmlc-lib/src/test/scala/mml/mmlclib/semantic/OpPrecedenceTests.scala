package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst
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
  //    op ! (a)   95 left  = ???;
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
          assert(clue(bnd.value.terms.size) == clue(3))
        case x =>
          fail(s"Expected a Bnd, got: $x")
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
          assert(clue(bnd.value.terms.size) == clue(3))
          bnd.value.terms.last match
            case e: Expr =>
              // this is the (1 * 2) part
              assert(clue(e.terms.size) == clue(3))
            case x =>
              fail(s"Expected an Expr, got: $x")

        case x =>
          fail(s"Expected a Bnd, got: $x")

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
          assert(clue(bnd.value.terms.size) == clue(3))
          bnd.value.terms.last match
            case e: Expr =>
              // this is the ((1 * 2) / 3) part
              assert(clue(e.terms.size) == clue(3))
              e.terms.head match
                case e: Expr =>
                  // this is the (1 * 2) part
                  assert(clue(e.terms.size) == clue(3))
                case x =>
                  fail(s"Expected an Expr, got: $x")
            case x =>
              fail(s"Expected an Expr, got: $x")

        case x =>
          fail(s"Expected a Bnd, got: $x")

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
          assert(clue(bnd.value.terms.size) == clue(3))
          bnd.value.terms.last match
            case e: Expr =>
              // this is the (1 * (2 ^ 3)) part
              assert(clue(e.terms.size) == clue(3))
              e.terms.last match
                case e: Expr =>
                  // this is the (2 ^ 3) part
                  assert(clue(e.terms.size) == clue(3))
                case x =>
                  fail(s"Expected an Expr, got: $x")
              e.terms match
                case (l: LiteralInt) :: (r: Ref) :: rest =>
                  // this is the (1 * Expr) part
                  assert(clue(e.terms.size) == clue(3))
                case x =>
                  fail(s"Expected an list with a literal 1, a ref then and Expr, got: $x")
            case x =>
              fail(s"Expected an Expr, got: $x")

        case x =>
          fail(s"Expected a Bnd, got: $x")

    }
  }

  test("unary postfix: 4!") {
    // expect: 4!
    semNotFailed(
      """
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
          assert(clue(bnd.value.terms.size) == clue(2))
          bnd.value.terms match
            case (l: LiteralInt) :: (r: Ref) :: Nil if r.name == "!" =>
              assert(clue(bnd.value.terms.size) == clue(2))
            case x =>
              fail(s"Expected an list: [4,!], got: $x")

        case x =>
          fail(s"Expected a Bnd, got: $x")

    }
  }

  test("unary pre, post and binop: +4! - 2!") {
    // expect: ((+ (4!)) - (2!))
    semNotFailed(
      """
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
          assert(clue(bnd.value.terms.size) == clue(3))
          bnd.value.terms match
            // (+ (4!)) - (2!)
            case (e1: Expr) :: (r: Ref) :: (e2: Expr) :: Nil if r.name == "-" =>
              // this is the (+ (4!)) part
              assert(clue(e1.terms.size) == clue(2))
              e1.terms match
                case (r: Ref) :: (e: Expr) :: Nil if r.name == "+" =>
                  // this is the (4!) part
                  assert(clue(e.terms.size) == clue(2))
                case x =>
                  fail(s"Expected an list: [+, (4!)], got: $x")
              // this is the (2!) part
              e2.terms match
                case (l: LiteralInt) :: (r: Ref) :: Nil if r.name == "!" =>
                  assert(clue(e2.terms.size) == clue(2))
                case x =>
                  fail(s"Expected an list: [2,!], got: $x")
            case x =>
              fail(s"Expected an list: [(+(4!)),-,(2!)], got: $x")

        case x =>
          fail(s"Expected a Bnd, got: $x")

    }
  }

  test("functions are people, too: unary pre, post and binop: +4! - 2!") {
    // expect: ((+ (4!)) - (2!))
    semNotFailed(
      """
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
          assert(clue(fnDef.body.terms.size) == clue(3))
          fnDef.body.terms match
            // (+ (4!)) - (2!)
            case (e1: Expr) :: (r: Ref) :: (e2: Expr) :: Nil if r.name == "-" =>
              // this is the (+ (4!)) part
              assert(clue(e1.terms.size) == clue(2))
              e1.terms match
                case (r: Ref) :: (e: Expr) :: Nil if r.name == "+" =>
                  // this is the (4!) part
                  assert(clue(e.terms.size) == clue(2))
                case x =>
                  fail(s"Expected an list: [+, (4!)], got: $x")
              // this is the (2!) part
              e2.terms match
                case (l: LiteralInt) :: (r: Ref) :: Nil if r.name == "!" =>
                  assert(clue(e2.terms.size) == clue(2))
                case x =>
                  fail(s"Expected an list: [2,!], got: $x")
            case x =>
              fail(s"Expected an list: [(+(4!)),-,(2!)], got: $x")

        case x =>
          fail(s"Expected a Bnd, got: $x")

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
          assert(clue(bnd.value.terms.size) == clue(2))
          bnd.value match
            case e: Expr =>
              // this is the -3 part
              assert(clue(e.terms.size) == clue(2))
              e.terms match
                case (r: Ref) :: (l: LiteralInt) :: Nil if r.name == "-" =>
                  assert(clue(e.terms.size) == clue(2))
                case x =>
                  fail(s"Expected an list: [-,3] , got: $x")

        case x =>
          fail(s"Expected a Bnd, got: $x")

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
          assert(clue(bnd.value.terms.size) == clue(3))
          bnd.value.terms match
            case t1 :: t2 :: t3 :: Nil =>
              // this is the -3 part
              t1 match
                case e: Expr =>
                  // this is the -3 part
                  assert(clue(e.terms.size) == clue(2))
                  e.terms match
                    case (r: Ref) :: (l: LiteralInt) :: Nil if r.name == "-" =>
                      assert(clue(e.terms.size) == clue(2))
                    case x =>
                      fail(s"Expected an list: [-,3] , got: $x")
                case x =>
                  fail(s"Expected an Expr, got: $x")
              // this is the - binop
              t2 match
                case r: Ref =>
                  assert(clue(r.name) == clue("-"))
                case x =>
                  fail(s"Expected a Ref, got: $x")
              // this is the -2 part
              t3 match
                case e: Expr =>
                  // this is the -2 part
                  assert(clue(e.terms.size) == clue(2))
                  e.terms match
                    case (r: Ref) :: (l: LiteralInt) :: Nil if r.name == "-" =>
                      assert(clue(e.terms.size) == clue(2))
                    case x =>
                      fail(s"Expected an list: [-,2] , got: $x")
                case x =>
                  fail(s"Expected an Expr, got: $x")

            case x =>
              fail(s"Expected a list of 3 terms, got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")

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
          assert(clue(bnd.value.terms.size) == clue(3))
          bnd.value.terms.head match
            case e: Expr =>
              // this is the (1 + 2) part
              assert(clue(e.terms.size) == clue(3))
              e.terms match
                // this is the (1 + 2) part
                case (l1: LiteralInt) :: (r: Ref) :: (l2: LiteralInt) :: rest if r.name == "+" =>
                  assert(clue(e.terms.size) == clue(3))
                case x =>
                  fail(s"Expected an list: [1,+,2] , got: $x")
            case x =>
              fail(s"Expected an Expr, got: $x")

        case x =>
          fail(s"Expected a Bnd, got: $x")

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
      val bnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )
      bnd match
        case bnd: Bnd =>
          // Expect a flat list of 3 terms:
          // [ Expr([LiteralInt(1), Ref("-"), LiteralInt(2)]), Ref("-"), LiteralInt(3) ]
          bnd.value.terms match
            case (expr: Expr) :: (op: Ref) :: (rightTerm: Term) :: Nil if op.name == "-" =>
              expr.terms match
                case (lit1: LiteralInt) :: (innerOp: Ref) :: (lit2: LiteralInt) :: Nil =>
                  assert(clue(lit1.value) == clue(1))
                  assert(clue(innerOp.name) == clue("-"))
                  assert(clue(lit2.value) == clue(2))
                case x =>
                  fail(s"Expected inner subtraction [1, -, 2], got: $x")
              rightTerm match
                case lit3: LiteralInt =>
                  assert(clue(lit3.value) == 3)
                case expr: Expr =>
                  expr.terms match
                    case (lit3: LiteralInt) :: Nil =>
                      assert(clue(lit3.value) == 3)
                    case x =>
                      fail(s"Expected right term to be literal 3, got: $x")
                case x =>
                  fail(s"Unexpected type for right term: $x")
            case x =>
              fail(s"Expected top-level expression with three terms, got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")
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
      val bnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )
      bnd match
        case bnd: Bnd =>
          bnd.value.terms match
            case leftTerm :: (op: Ref) :: (rightTerm: Term) :: Nil if op.name == "^" =>
              leftTerm match
                case lit: LiteralInt =>
                  assert(clue(lit.value) == 2)
                case expr: Expr =>
                  expr.terms match
                    case (lit: LiteralInt) :: Nil =>
                      assert(clue(lit.value) == 2)
                    case x =>
                      fail(s"Expected left literal 2, got: $x")
                case x =>
                  fail(s"Unexpected left operand: $x")
              rightTerm match
                case expr: Expr =>
                  expr.terms match
                    case (lit: LiteralInt) :: (op2: Ref) :: (lit2: LiteralInt) :: Nil
                        if op2.name == "^" =>
                      assert(clue(lit.value) == 3)
                      assert(clue(lit2.value) == 2)
                    case x =>
                      fail(s"Expected inner exponentiation [3, ^, 2], got: $x")
                case lit: LiteralInt =>
                  fail(s"Expected right operand to be an Expr, got literal $lit")
                case x =>
                  fail(s"Unexpected right operand: $x")
            case x =>
              fail(s"Expected top-level expression of form [Term, '^', Term], got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")
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
      memberBnd match
        case bnd: Bnd =>
          bnd.value match
            case outer: Expr =>
              assert(clue(outer.terms.size) == 3)
              outer.terms match
                case (leftExpr: Term) :: (op: Ref) :: (right: Term) :: Nil if op.name == "^" =>
                  leftExpr match
                    case inner: Expr =>
                      inner.terms match
                        case (unary: Ref) :: (lit: LiteralInt) :: Nil if unary.name == "-" =>
                          assert(clue(lit.value) == 2)
                        case x =>
                          fail(s"Expected unary minus expression [ -, 2 ], got: $x")
                    case x =>
                      fail(s"Expected left operand to be an Expr representing unary minus, got: $x")
                  right match
                    case lit: LiteralInt =>
                      assert(clue(lit.value) == 2)
                    case expr: Expr =>
                      expr.terms match
                        case (lit: LiteralInt) :: Nil =>
                          assert(clue(lit.value) == 2)
                        case x =>
                          fail(s"Unexpected structure for right operand: $x")
                    case x =>
                      fail(s"Unexpected right operand type: $x")
                case x =>
                  fail(s"Expected top-level expression of form [Term, '^', Term], got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")
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
        fail("Member `a` not found")
      )
      memberBnd match
        case bnd: Bnd =>
          bnd.value match
            case outer: Expr =>
              assert(clue(outer.terms.size) == 2)
              outer.terms match
                case (opOuter: Ref) :: (innerExpr: Expr) :: Nil if opOuter.name == "-" =>
                  innerExpr.terms match
                    case (opInner: Ref) :: (lit: LiteralInt) :: Nil if opInner.name == "-" =>
                      assert(clue(lit.value) == 3)
                    case x =>
                      fail(s"Expected inner expression to be [-, 3], got: $x")
                case x =>
                  fail(s"Expected outer unary minus structure [Ref '-', Expr], got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")
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
      val bnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )
      bnd match
        case bnd: Bnd =>
          bnd.value match
            case outer: Expr =>
              // Expect the outer expression to have three terms: [ leftExpr, Ref("/"), rightTerm ]
              outer.terms match
                case leftExpr :: (divOp: Ref) :: (rightTerm: Term) :: Nil if divOp.name == "/" =>
                  // Check that the right operand is the literal 5.
                  rightTerm match
                    case lit5: LiteralInt =>
                      assert(clue(lit5.value) == 5)
                    case expr: Expr =>
                      expr.terms match
                        case (lit5: LiteralInt) :: Nil =>
                          assert(clue(lit5.value) == 5)
                        case x =>
                          fail(s"Expected right term literal 5, got: $x")
                    case x =>
                      fail(s"Unexpected type for right term: $x")
                  // The left operand should be the multiplication of two groups.
                  leftExpr match
                    case leftExprOuter: Expr =>
                      // Expect leftExprOuter to have three terms: [ leftGroup, Ref("*"), rightGroup ]
                      leftExprOuter.terms match
                        case (leftGroup: Expr) :: (mulOp: Ref) :: (rightGroup: Expr) :: Nil
                            if mulOp.name == "*" =>
                          // leftGroup should represent (1 + 2)
                          leftGroup.terms match
                            case (lit1: LiteralInt) :: (plusOp: Ref) :: (lit2: LiteralInt) :: Nil
                                if plusOp.name == "+" =>
                              assert(clue(lit1.value) == 1)
                              assert(clue(lit2.value) == 2)
                            case x =>
                              fail(s"Expected left group to be [1, '+', 2], got: $x")
                          // rightGroup should represent (3 - 4)
                          rightGroup.terms match
                            case (lit3: LiteralInt) :: (minusOp: Ref) :: (lit4: LiteralInt) :: Nil
                                if minusOp.name == "-" =>
                              assert(clue(lit3.value) == 3)
                              assert(clue(lit4.value) == 4)
                            case x =>
                              fail(s"Expected right group to be [3, '-', 4], got: $x")
                        case x =>
                          fail(s"Expected left expression (for '*') to have 3 terms, got: $x")
                    case x =>
                      fail(s"Expected leftExpr to be an Expr, got: $x")
                case x =>
                  fail(s"Expected outer expression to have 3 terms, got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")
    }
  }

  // Consecutive postfix operators (not allowed):
  // let a = 4!!;
  // (Tests applying a postfix operator twice in a row.)
  test("consecutive postfix operators: 4!!") {
    semFailed(
      """
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
        fail("Member `a` not found")
      )
      memberBnd match
        case bnd: Bnd =>
          bnd.value match
            case outer: Expr =>
              assert(clue(outer.terms.size) == 2)
              outer.terms match
                case (plusOp: Ref) :: (inner: Expr) :: Nil if plusOp.name == "+" =>
                  inner.terms match
                    case (minusOp: Ref) :: (lit: LiteralInt) :: Nil if minusOp.name == "-" =>
                      assert(clue(lit.value) == 3)
                    case x =>
                      fail(s"Expected inner expression to be [-, 3], got: $x")
                case x =>
                  fail(s"Expected outer expression to be [+, Expr], got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")
    }
  }

  test("multi-character binary operator: 3 -- -4") {
    semNotFailed(
      """
       op -- (a b) = ???;
       let a = 3 -- -4;
      """
    ).map { m =>
      val bnd = lookupNames("a", m).headOption.getOrElse(
        fail(s"Member `a` not found in module: ${prettyPrintAst(m)}")
      )
      bnd match
        case bnd: Bnd =>
          // Expect the expression to have three parts:
          // [ LiteralInt(3), Ref("--"), Expr representing unary minus on 4 ]
          bnd.value.terms match
            case left :: (op: Ref) :: right :: Nil if op.name == "--" =>
              left match
                case lit: LiteralInt =>
                  assert(clue(lit.value) == 3)
                case x =>
                  fail(s"Expected left operand to be LiteralInt(3), got: $x")
              right match
                case expr: Expr =>
                  expr.terms match
                    case (unary: Ref) :: (lit: LiteralInt) :: Nil if unary.name == "-" =>
                      assert(clue(lit.value) == 4)
                    case x =>
                      fail(s"Expected right operand to be a unary minus expression, got: $x")
                case x =>
                  fail(s"Expected right operand to be an Expr, got: $x")
            case x =>
              fail(s"Expected top-level expression with three terms, got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")
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
          assert(clue(bnd.value.terms.size) == clue(3))
          bnd.value.terms match
            case (expr: Expr) :: (op: Ref) :: (LiteralInt(_, x)) :: Nil if op.name == "+" =>
              expr.terms match
                case (LiteralInt(_, y)) :: (op2: Ref) :: (expr: Expr) :: Nil if op2.name == "+" =>
                  assert(clue(y) == clue(1))
                  expr.terms match
                    case (LiteralInt(_, z)) :: (op3: Ref) :: (LiteralInt(_, w)) :: Nil
                        if op3.name == "^" =>
                      assert(clue(z) == clue(2))
                      assert(clue(w) == clue(3))
                    case x =>
                      fail(
                        s"Expected right term to be an expression of form [LiteralInt, '^', LiteralInt], got: $x"
                      )
                case x =>
                  fail(
                    s"Expected left term to be an expression of form [LiteralInt, '^', Expr], got: $x"
                  )
            case _ =>
              fail(s"Expected expression to be of form [Expr, '+', LiteralInt], got: ${bnd.value}")
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
          assert(clue(bnd.value.terms.size) == clue(3))
          bnd.value.terms match
            case (LiteralInt(_, x)) :: (op: Ref) :: (LiteralInt(_, y)) :: Nil if op.name == "++" =>
              assert(clue(x) == clue(1))
              assert(clue(y) == clue(2))
            case x =>
              fail(s"Expected expression to be of form [LiteralInt, '+', LiteralInt], got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")

      memberBndB match
        case bnd: Bnd =>
          assert(clue(bnd.value.terms.size) == clue(2))
          bnd.value.terms match
            case (op: Ref) :: (LiteralInt(_, x)) :: Nil if op.name == "++" =>
              assert(clue(x) == clue(1))
            case x =>
              fail(s"Expected expression to be of form ['++', LiteralInt], got: $x")
        case x =>
          fail(s"Expected a Bnd, got: $x")

    }
  }
