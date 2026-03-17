package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class NameSourceOriginTests extends BaseEffFunSuite:

  test("Name(span, value) preserves source location origin"):
    val span = SrcSpan(SrcPoint(1, 2, 3), SrcPoint(1, 5, 6))
    val name = Name(span, "x")

    assertEquals(name.source, SourceOrigin.Loc(span))
    assertEquals(name.source.spanOpt, Some(span))
    assertEquals(name.source.isFromSource, true)

  test("Name.synth uses synthetic origin and no source span"):
    val name = Name.synth("__tmp_0")

    assertEquals(name.source, SourceOrigin.Synth)
    assertEquals(name.source.spanOpt, None)
    assertEquals(name.source.isFromSource, false)
