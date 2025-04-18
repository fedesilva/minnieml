package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst

class AlphaOpTests extends BaseEffFunSuite:

  test("custom binary alphabetic operator: xor") {
    semNotFailed(
      """
       op xor (a b) 35 left = ???;
       let a = true;
       let b = false;
       let c = true;
       let d = false;
       let x = a and b xor c or d;
      """
    ).map { m =>
      val bnd = lookupNames("x", m).headOption.getOrElse(
        fail(s"Member `x` not found in module: ${prettyPrintAst(m)}")
      )

      bnd match
        case bnd: Bnd =>
          // Expected: ((a and b) xor c) or d
          // This verifies xor's precedence between and (40) and or (30)

          bnd.value.terms match
            case (leftExpr: Expr) :: (orOp: Ref) :: (dRef: Ref) :: Nil
                if orOp.name == "or" && dRef.name == "d" =>

              // Check left side: (a and b) xor c
              leftExpr.terms match
                case (innerExpr: Expr) :: (xorOp: Ref) :: (cRef: Ref) :: Nil
                    if xorOp.name == "xor" && cRef.name == "c" =>

                  // Check (a and b)
                  innerExpr.terms match
                    case (aRef: Ref) :: (andOp: Ref) :: (bRef: Ref) :: Nil
                        if andOp.name == "and" && aRef.name == "a" && bRef.name == "b" =>
                    // Verified correct structure
                    case x =>
                      fail(
                        s"Expected 'a and b' structure, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                      )

                case x =>
                  fail(
                    s"Expected '(a and b) xor c' structure, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                  )

            case x =>
              fail(
                s"Expected top-level structure '((a and b) xor c) or d', got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
              )

        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("custom unary alphabetic operator: negate") {
    semNotFailed(
      """
       op negate (a) 96 right = ???;
       let a = true;
       let b = false;
       let x = negate a and not b;
      """
    ).map { m =>
      val bnd = lookupNames("x", m).headOption.getOrElse(
        fail(s"Member `x` not found in module: ${prettyPrintAst(m)}")
      )

      bnd match
        case bnd: Bnd =>
          // Expected: (negate a) and (not b)
          // This verifies that negate (96) has higher precedence than and (40)

          bnd.value.terms match
            case (leftExpr: Expr) :: (andOp: Ref) :: (rightExpr: Expr) :: Nil
                if andOp.name == "and" =>

              // Check left: negate a
              leftExpr.terms match
                case (negateOp: Ref) :: (aRef: Ref) :: Nil
                    if negateOp.name == "negate" && aRef.name == "a" =>
                // Verified left side structure
                case x =>
                  fail(
                    s"Expected 'negate a' structure, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                  )

              // Check right: not b
              rightExpr.terms match
                case (notOp: Ref) :: (bRef: Ref) :: Nil
                    if notOp.name == "not" && bRef.name == "b" =>
                // Verified right side structure
                case x =>
                  fail(
                    s"Expected 'not b' structure, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                  )

            case x =>
              fail(
                s"Expected structure '(negate a) and (not b)', got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
              )

        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("operator with only precedence specified") {
    semNotFailed(
      """
       op implies (a b) 45 = ???;
       let a = true;
       let b = false;
       let x = a implies b;
      """
    ).map { m =>
      val bnd = lookupNames("x", m).headOption.getOrElse(
        fail(s"Member `x` not found in module: ${prettyPrintAst(m)}")
      )

      bnd match
        case bnd: Bnd =>
          // Verify the operator definition was parsed correctly with default Left associativity
          val opDef = m.members
            .collectFirst {
              case op: BinOpDef if op.name == "implies" => op
            }
            .getOrElse(fail("Operator 'implies' not defined"))

          assert(clue(opDef.precedence) == clue(45), "Precedence should be 45")
          assert(
            clue(opDef.assoc) == clue(Associativity.Left),
            "Default associativity should be Left"
          )

          // Verify expression structure
          bnd.value.terms match
            case (aRef: Ref) :: (impliesOp: Ref) :: (bRef: Ref) :: Nil
                if impliesOp.name == "implies" && aRef.name == "a" && bRef.name == "b" =>
            // Verified basic structure
            case x =>
              fail(
                s"Expected 'a implies b' structure, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
              )
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("operator with only associativity specified") {
    semNotFailed(
      """
       op unless (a b) right = ???;
       let a = true;
       let b = false;
       let x = a unless b;
      """
    ).map { m =>
      val bnd = lookupNames("x", m).headOption.getOrElse(
        fail(s"Member `x` not found in module: ${prettyPrintAst(m)}")
      )

      bnd match
        case bnd: Bnd =>
          // Verify the operator definition was parsed correctly with default precedence
          val opDef = m.members
            .collectFirst {
              case op: BinOpDef if op.name == "unless" => op
            }
            .getOrElse(fail("Operator 'unless' not defined"))

          assert(clue(opDef.precedence) == clue(50), "Default precedence should be 50")
          assert(clue(opDef.assoc) == clue(Associativity.Right), "Associativity should be Right")

          // Verify expression structure
          bnd.value.terms match
            case (aRef: Ref) :: (unlessOp: Ref) :: (bRef: Ref) :: Nil
                if unlessOp.name == "unless" && aRef.name == "a" && bRef.name == "b" =>
            // Verified basic structure
            case x =>
              fail(
                s"Expected 'a unless b' structure, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
              )
        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("mixed custom and built-in alphabetic operators") {
    semNotFailed(
      """
       op xor (a b) 35 left = ???;
       op negate (a) 96 right = ???;
       let a = true;
       let b = false;
       let c = true;
       let x = not a xor negate b and c;
      """
    ).map { m =>
      val bnd = lookupNames("x", m).headOption.getOrElse(
        fail(s"Member `x` not found in module: ${prettyPrintAst(m)}")
      )

      bnd match
        case bnd: Bnd =>
          // Expected: (not a) xor ((negate b) and c)
          // This tests interaction between all operators with different precedence

          bnd.value.terms match
            case (leftExpr: Expr) :: (xorOp: Ref) :: (rightExpr: Expr) :: Nil
                if xorOp.name == "xor" =>

              // Check left: not a
              leftExpr.terms match
                case (notOp: Ref) :: (aRef: Ref) :: Nil
                    if notOp.name == "not" && aRef.name == "a" =>
                // Verified left side is "not a"
                case x =>
                  fail(
                    s"Expected 'not a' structure, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                  )

              // Check right: (negate b) and c
              rightExpr.terms match
                case (negateBExpr: Expr) :: (andOp: Ref) :: (cRef: Ref) :: Nil
                    if andOp.name == "and" && cRef.name == "c" =>

                  // Check (negate b)
                  negateBExpr.terms match
                    case (negateOp: Ref) :: (bRef: Ref) :: Nil
                        if negateOp.name == "negate" && bRef.name == "b" =>
                    // Verified negate b structure
                    case x =>
                      fail(
                        s"Expected 'negate b' structure, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                      )

                case x =>
                  fail(
                    s"Expected '(negate b) and c' structure, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                  )

            case x =>
              fail(
                s"Expected structure '(not a) xor ((negate b) and c)', got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
              )

        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("alpha operators precedence: not a or not b and not a or c") {
    semNotFailed(
      """
       let a = true;
       let b = false;
       let c = true;
       let x = not a or not b and not a or c;
      """
    ).map { m =>
      val bnd = lookupNames("x", m).headOption.getOrElse(
        fail(s"Member `x` not found in module: ${prettyPrintAst(m)}")
      )

      bnd match
        case bnd: Bnd =>
          // The expression should be structured like:
          // ((not a) or ((not b) and (not a))) or c
          // This reflects proper precedence: not > and > or

          // First level: outer or operation
          bnd.value.terms match
            case (leftExpr: Expr) :: (orOp: Ref) :: (rightTerm: Term) :: Nil
                if orOp.name == "or" && orOp.resolvedAs.isDefined =>

              // Right side should be just c
              rightTerm match
                case ref: Ref if ref.name == "c" =>
                // Verified right side is 'c'
                case x =>
                  fail(s"Expected rightmost term to be 'c', got: ${prettyPrintAst(x)}")

              // Left side should be ((not a) or ((not b) and (not a)))
              leftExpr.terms match
                case (leftLeftExpr: Expr) :: (innerOrOp: Ref) :: (rightLeftExpr: Expr) :: Nil
                    if innerOrOp.name == "or" && innerOrOp.resolvedAs.isDefined =>

                  // Left of inner or: (not a)
                  leftLeftExpr.terms match
                    case (notOp: Ref) :: (aRef: Ref) :: Nil
                        if notOp.name == "not" && aRef.name == "a" =>
                    // Verified left of inner or is 'not a'
                    case x =>
                      fail(
                        s"Expected 'not a' for leftmost part, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                      )

                  // Right of inner or: ((not b) and (not a))
                  rightLeftExpr.terms match
                    case (notBExpr: Expr) :: (andOp: Ref) :: (notAExpr: Expr) :: Nil
                        if andOp.name == "and" && andOp.resolvedAs.isDefined =>

                      // Left of and: (not b)
                      notBExpr.terms match
                        case (notOp: Ref) :: (bRef: Ref) :: Nil
                            if notOp.name == "not" && bRef.name == "b" =>
                        // Verified left of and is 'not b'
                        case x =>
                          fail(
                            s"Expected 'not b' in the and expression, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                          )

                      // Right of and: (not a)
                      notAExpr.terms match
                        case (notOp: Ref) :: (aRef: Ref) :: Nil
                            if notOp.name == "not" && aRef.name == "a" =>
                        // Verified right of and is 'not a'
                        case x =>
                          fail(
                            s"Expected 'not a' in the and expression, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                          )

                    case x =>
                      fail(
                        s"Expected 'not b and not a' expression, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                      )

                case x =>
                  fail(
                    s"Expected inner or expression, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
                  )

            case x =>
              fail(
                s"Expected top-level or expression, got: ${x.map(term => prettyPrintAst(term)).mkString(", ")}"
              )

        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }
