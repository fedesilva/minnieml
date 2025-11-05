package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.TestExtractors.TXApp
import mml.mmlclib.util.prettyprint.ast.prettyPrintAst

class AlphaOpTests extends BaseEffFunSuite:

  test("custom binary alphabetic operator: xor") {
    semNotFailed(
      """
       op xor (a: Bool, b: Bool): Bool 35 left = ???;
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
            case List(TXApp(orRef, _, List(xorExpr, dExpr))) if orRef.name == "or" =>

              // Check d reference
              dExpr.terms match
                case List(dRef: Ref) if dRef.name == "d" =>
                // Verified d reference
                case other =>
                  fail(s"Expected d reference, got: ${prettyPrintAst(dExpr)}")

              // Check xor expression
              xorExpr.terms match
                case List(TXApp(xorRef, _, List(andExpr, cExpr))) if xorRef.name == "xor" =>

                  // Check c reference
                  cExpr.terms match
                    case List(cRef: Ref) if cRef.name == "c" =>
                    // Verified c reference
                    case other =>
                      fail(s"Expected c reference, got: ${prettyPrintAst(cExpr)}")

                  // Check and expression
                  andExpr.terms match
                    case List(TXApp(andRef, _, List(aExpr, bExpr))) if andRef.name == "and" =>

                      // Check a reference
                      aExpr.terms match
                        case List(aRef: Ref) if aRef.name == "a" =>
                        // Verified a reference
                        case other =>
                          fail(s"Expected a reference, got: ${prettyPrintAst(aExpr)}")

                      // Check b reference
                      bExpr.terms match
                        case List(bRef: Ref) if bRef.name == "b" =>
                        // Verified b reference
                        case other =>
                          fail(s"Expected b reference, got: ${prettyPrintAst(bExpr)}")

                    case other =>
                      fail(s"Expected (a and b) expression, got: ${prettyPrintAst(andExpr)}")

                case other =>
                  fail(s"Expected ((a and b) xor c) expression, got: ${prettyPrintAst(xorExpr)}")

            case other =>
              fail(
                s"Expected (((a and b) xor c) or d) app structure, got: ${other.map(term => prettyPrintAst(term)).mkString(", ")}"
              )

        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("custom unary alphabetic operator: negate") {
    semNotFailed(
      """
       op negate (a: Bool): Bool 96 right = ???;
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
            case List(TXApp(andRef, _, List(negateExpr, notExpr))) if andRef.name == "and" =>

              // Check left: negate a
              negateExpr.terms match
                case List(TXApp(negateRef, _, List(aExpr))) if negateRef.name == "negate" =>

                  // Check a reference
                  aExpr.terms match
                    case List(aRef: Ref) if aRef.name == "a" =>
                    // Verified a reference
                    case other =>
                      fail(s"Expected a reference, got: ${prettyPrintAst(aExpr)}")

                case other =>
                  fail(s"Expected 'negate a' structure, got: ${prettyPrintAst(negateExpr)}")

              // Check right: not b
              notExpr.terms match
                case List(TXApp(notRef, _, List(bExpr))) if notRef.name == "not" =>

                  // Check b reference
                  bExpr.terms match
                    case List(bRef: Ref) if bRef.name == "b" =>
                    // Verified b reference
                    case other =>
                      fail(s"Expected b reference, got: ${prettyPrintAst(bExpr)}")

                case other =>
                  fail(s"Expected 'not b' structure, got: ${prettyPrintAst(notExpr)}")

            case other =>
              fail(
                s"Expected '(negate a) and (not b)' app structure, got: ${other.map(term => prettyPrintAst(term)).mkString(", ")}"
              )

        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }

  test("operator with only precedence specified") {
    semNotFailed(
      """
       op implies (a: Bool, b: Bool): Bool 45 = ???;
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
            case List(TXApp(impliesRef, _, List(aExpr, bExpr))) if impliesRef.name == "implies" =>

              // Check a reference
              aExpr.terms match
                case List(aRef: Ref) if aRef.name == "a" =>
                // Verified a reference
                case other =>
                  fail(s"Expected a reference, got: ${prettyPrintAst(aExpr)}")

              // Check b reference
              bExpr.terms match
                case List(bRef: Ref) if bRef.name == "b" =>
                // Verified b reference
                case other =>
                  fail(s"Expected b reference, got: ${prettyPrintAst(bExpr)}")

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
       op unless (a: Bool, b: Bool): Bool right = ???;
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
            case List(TXApp(unlessRef, _, List(aExpr, bExpr))) if unlessRef.name == "unless" =>

              // Check a reference
              aExpr.terms match
                case List(aRef: Ref) if aRef.name == "a" =>
                // Verified a reference
                case other =>
                  fail(s"Expected a reference, got: ${prettyPrintAst(aExpr)}")

              // Check b reference
              bExpr.terms match
                case List(bRef: Ref) if bRef.name == "b" =>
                // Verified b reference
                case other =>
                  fail(s"Expected b reference, got: ${prettyPrintAst(bExpr)}")

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
       op xor (a: Bool, b: Bool): Bool 35 left = ???;
       op negate (a: Bool): Bool 96 right = ???;
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
            case List(TXApp(xorRef, _, List(notExpr, andExpr))) if xorRef.name == "xor" =>

              // Check left: not a
              notExpr.terms match
                case List(TXApp(notRef, _, List(aExpr))) if notRef.name == "not" =>

                  // Check a reference
                  aExpr.terms match
                    case List(aRef: Ref) if aRef.name == "a" =>
                    // Verified a reference
                    case other =>
                      fail(s"Expected a reference, got: ${prettyPrintAst(aExpr)}")

                case other =>
                  fail(s"Expected 'not a' structure, got: ${prettyPrintAst(notExpr)}")

              // Check right: (negate b) and c
              andExpr.terms match
                case List(TXApp(andRef, _, List(negateExpr, cExpr))) if andRef.name == "and" =>

                  // Check negate b
                  negateExpr.terms match
                    case List(TXApp(negateRef, _, List(bExpr))) if negateRef.name == "negate" =>

                      // Check b reference
                      bExpr.terms match
                        case List(bRef: Ref) if bRef.name == "b" =>
                        // Verified b reference
                        case other =>
                          fail(s"Expected b reference, got: ${prettyPrintAst(bExpr)}")

                    case other =>
                      fail(s"Expected 'negate b' structure, got: ${prettyPrintAst(negateExpr)}")

                  // Check c reference
                  cExpr.terms match
                    case List(cRef: Ref) if cRef.name == "c" =>
                    // Verified c reference
                    case other =>
                      fail(s"Expected c reference, got: ${prettyPrintAst(cExpr)}")

                case other =>
                  fail(s"Expected '(negate b) and c' structure, got: ${prettyPrintAst(andExpr)}")

            case other =>
              fail(
                s"Expected structure '(not a) xor ((negate b) and c)', got: ${other.map(term => prettyPrintAst(term)).mkString(", ")}"
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

          bnd.value.terms match
            case List(TXApp(outerOrRef, _, List(innerOrExpr, cExpr))) if outerOrRef.name == "or" =>

              // Check rightmost part (c)
              cExpr.terms match
                case List(cRef: Ref) if cRef.name == "c" =>
                // Verified c reference
                case other =>
                  fail(s"Expected rightmost term to be 'c', got: ${prettyPrintAst(cExpr)}")

              // Check left part ((not a) or ((not b) and (not a)))
              innerOrExpr.terms match
                case List(TXApp(innerOrRef, _, List(notAExpr1, andExpr)))
                    if innerOrRef.name == "or" =>

                  // Check leftmost (not a)
                  notAExpr1.terms match
                    case List(TXApp(notRef1, _, List(aExpr1))) if notRef1.name == "not" =>

                      // Check a reference
                      aExpr1.terms match
                        case List(aRef1: Ref) if aRef1.name == "a" =>
                        // Verified a reference
                        case other =>
                          fail(s"Expected a reference, got: ${prettyPrintAst(aExpr1)}")

                    case other =>
                      fail(s"Expected 'not a' for leftmost part, got: ${prettyPrintAst(notAExpr1)}")

                  // Check (not b) and (not a)
                  andExpr.terms match
                    case List(TXApp(andRef, _, List(notBExpr, notAExpr2)))
                        if andRef.name == "and" =>

                      // Check not b
                      notBExpr.terms match
                        case List(TXApp(notRef2, _, List(bExpr))) if notRef2.name == "not" =>

                          // Check b reference
                          bExpr.terms match
                            case List(bRef: Ref) if bRef.name == "b" =>
                            // Verified b reference
                            case other =>
                              fail(s"Expected b reference, got: ${prettyPrintAst(bExpr)}")

                        case other =>
                          fail(
                            s"Expected 'not b' in the and expression, got: ${prettyPrintAst(notBExpr)}"
                          )

                      // Check not a (right side of and)
                      notAExpr2.terms match
                        case List(TXApp(notRef3, _, List(aExpr2))) if notRef3.name == "not" =>

                          // Check a reference
                          aExpr2.terms match
                            case List(aRef2: Ref) if aRef2.name == "a" =>
                            // Verified a reference
                            case other =>
                              fail(s"Expected a reference, got: ${prettyPrintAst(aExpr2)}")

                        case other =>
                          fail(
                            s"Expected 'not a' in the and expression, got: ${prettyPrintAst(notAExpr2)}"
                          )

                    case other =>
                      fail(
                        s"Expected 'not b and not a' expression, got: ${prettyPrintAst(andExpr)}"
                      )

                case other =>
                  fail(s"Expected inner or expression, got: ${prettyPrintAst(innerOrExpr)}")

            case other =>
              fail(
                s"Expected top-level or expression, got: ${other.map(term => prettyPrintAst(term)).mkString(", ")}"
              )

        case x =>
          fail(s"Expected a Bnd, got: ${prettyPrintAst(x)}")
    }
  }
