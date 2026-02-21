package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class LiteralSourceOriginTests extends BaseEffFunSuite:

  private val span      = SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 2, 1))
  private val locOrigin = SourceOrigin.Loc(span)
  private val synth     = SourceOrigin.Synth

  test("literal extractors are total for source and synthetic origins"):
    val locInt   = LiteralInt(span, 42)
    val synthInt = LiteralInt(synth, 42, Some(TypeRef(synth, "Int")), None)
    assertEquals(LiteralInt.unapply(locInt), Some((locOrigin, 42)))
    assertEquals(LiteralInt.unapply(synthInt), Some((synth, 42)))

    val locString   = LiteralString(span, "hello")
    val synthString = LiteralString(synth, "hello", Some(TypeRef(synth, "String")), None)
    assertEquals(LiteralString.unapply(locString), Some((locOrigin, "hello")))
    assertEquals(LiteralString.unapply(synthString), Some((synth, "hello")))

    val locBool   = LiteralBool(span, value = true)
    val synthBool = LiteralBool(synth, value = true, typeSpec = Some(TypeRef(synth, "Bool")))
    assertEquals(LiteralBool.unapply(locBool), Some((locOrigin, true)))
    assertEquals(LiteralBool.unapply(synthBool), Some((synth, true)))

    val locFloat   = LiteralFloat(span, 1.25f)
    val synthFloat = LiteralFloat(synth, 1.25f, Some(TypeRef(synth, "Float")), None)
    assertEquals(LiteralFloat.unapply(locFloat), Some((locOrigin, 1.25f)))
    assertEquals(LiteralFloat.unapply(synthFloat), Some((synth, 1.25f)))

    val locUnit   = LiteralUnit(span)
    val synthUnit = LiteralUnit(synth, Some(TypeRef(synth, "Unit")), None)
    assertEquals(LiteralUnit.unapply(locUnit), Some(locOrigin))
    assertEquals(LiteralUnit.unapply(synthUnit), Some(synth))
