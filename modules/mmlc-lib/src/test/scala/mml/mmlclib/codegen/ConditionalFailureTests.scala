package mml.mmlclib.codegen

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.codegen.emitter.*
import mml.mmlclib.codegen.emitter.expression.compileCond
import mml.mmlclib.test.BaseEffFunSuite

class ConditionalFailureTests extends BaseEffFunSuite:

  test("an untyped literal guard returns its typed error before compiling branches") {
    val guard       = Expr(SourceOrigin.Synth, Nil)
    val branch      = Expr(SourceOrigin.Synth, List(LiteralInt(SourceOrigin.Synth, 1, None)))
    val conditional = Cond(SourceOrigin.Synth, guard, branch, branch)
    val result = compileCond(
      conditional,
      CodeGenState(),
      Map.empty,
      (expr, state, _) =>
        assertEquals(expr, guard)
        CompileResult(0, state, true, "Int").asRight
    )
    assertEquals(
      result.left.toOption,
      CodeGenError(CodeGenReason.MissingConditionType, guard.some).some
    )
  }

  test("a guard's unresolved LLVM type survives the conditional boundary") {
    val typ         = TypeRef(SourceOrigin.Synth, "Absent")
    val guard       = Expr(SourceOrigin.Synth, Nil, typeSpec = typ.some)
    val branch      = Expr(SourceOrigin.Synth, List(LiteralInt(SourceOrigin.Synth, 1, None)))
    val conditional = Cond(SourceOrigin.Synth, guard, branch, branch)
    val result = compileCond(
      conditional,
      CodeGenState(),
      Map.empty,
      (expr, state, _) =>
        assertEquals(expr, guard)
        CompileResult(0, state, true, "Absent").asRight
    )
    val expected =
      getLlvmType(typ, CodeGenState()).left.toOption.getOrElse(fail("Expected unresolved type"))
    assertEquals(expected.reason, CodeGenReason.UnresolvedType("Absent"))
    assertEquals(result.left.toOption, expected.some)
  }
