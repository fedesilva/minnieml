package mml.mmlclib.codegen

import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.{CodeGenState, compileTerm}
import mml.mmlclib.test.BaseEffFunSuite

class LiteralTermCodegenTest extends BaseEffFunSuite:

  test("compileTerm handles synthetic bool literals"):
    val boolType = Some(TypeRef(SourceOrigin.Synth, "Bool"))
    val trueLit  = LiteralBool(SourceOrigin.Synth, value = true, typeSpec = boolType)
    val falseLit = LiteralBool(SourceOrigin.Synth, value = false, typeSpec = boolType)

    compileTerm(trueLit, CodeGenState()) match
      case Left(err) =>
        fail(s"Expected synthetic bool literal to compile, got error: ${err.message}")
      case Right(result) =>
        assertEquals(result.isLiteral, true)
        assertEquals(result.typeName, "Bool")
        assertEquals(result.register, 1)

    compileTerm(falseLit, CodeGenState()) match
      case Left(err) =>
        fail(s"Expected synthetic bool literal to compile, got error: ${err.message}")
      case Right(result) =>
        assertEquals(result.isLiteral, true)
        assertEquals(result.typeName, "Bool")
        assertEquals(result.register, 0)
