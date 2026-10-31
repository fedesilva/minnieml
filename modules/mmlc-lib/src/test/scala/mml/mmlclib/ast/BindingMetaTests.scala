package mml.mmlclib.ast

import mml.mmlclib.test.BaseEffFunSuite

import scala.compiletime.testing.typeChecks

class BindingMetaTests extends BaseEffFunSuite:

  test("operator metadata requires concrete associativity") {
    assert(typeChecks("""
      import mml.mmlclib.ast.*
      BindingKind.Operator(associativity = Associativity.Right)
    """))
    assert(!typeChecks("""
      import mml.mmlclib.ast.*
      BindingKind.Operator(associativity = None)
    """))
  }

  test("operator constructor defaults match binary source defaults") {
    assertEquals(
      BindingKind.Operator(),
      BindingKind.Operator(OperatorDefaults.precedence, OperatorDefaults.associativity(2))
    )
  }
