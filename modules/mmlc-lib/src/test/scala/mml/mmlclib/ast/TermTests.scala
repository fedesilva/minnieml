package mml.mmlclib.ast

import mml.mmlclib.test.BaseEffFunSuite

class TermTests extends BaseEffFunSuite:

  private val span    = SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 1, 0))
  private val intType = TypeRef(span, "Int")

  test("withTypeAsc updates supported terms and preserves synthetic markers") {
    val literal = LiteralInt(span, 1).withTypeAsc(intType)
    val invalid = InvalidExpression(SourceOrigin.Synth, Expr(SourceOrigin.Synth, Nil))
      .withTypeAsc(intType)
    val ctor = DataConstructor(SourceOrigin.Synth).withTypeAsc(intType)

    literal match
      case lit: LiteralInt => assertEquals(lit.typeAsc, Some(intType))
      case other => fail(s"Expected LiteralInt, got $other")

    invalid match
      case expr: InvalidExpression => assertEquals(expr.typeAsc, Some(intType))
      case other => fail(s"Expected InvalidExpression, got $other")

    assertEquals(ctor.typeAsc, None)
  }
