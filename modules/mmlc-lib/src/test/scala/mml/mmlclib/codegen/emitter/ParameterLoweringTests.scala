package mml.mmlclib.codegen.emitter

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class ParameterLoweringTests extends BaseEffFunSuite:

  private val source = SourceOrigin.Synth
  private val unit   = TypeRef(source, "Unit")
  private val int    = TypeRef(source, "Int")

  private def parameter(name: String, tpe: Type): FnParam =
    FnParam(source, Name.synth(name), typeSpec = tpe.some, id = s"id-$name".some)

  private def lambda(params: FnParam*): Lambda =
    Lambda(source, params.toList, Expr(source, List(LiteralUnit(source, unit.some))), Nil)

  private def value[A](result: Either[CodeGenError, A]): A =
    result match
      case Right(result) => result
      case Left(error) => fail(error.message)

  test("Unit erasure retains source identity and runtime register positions") {
    val params = List(
      parameter("u", unit),
      parameter("x", int),
      parameter("v", unit),
      parameter("y", int),
      parameter("w", unit)
    )
    val lowered =
      value(ParameterLowering.from(lambda(params*), List("void", "i32", "void", "i32", "void")))
    val scope = value(lowered.scope(List(8, 9)))
    assertEquals(lowered.sourceIndices, List(1, 3))
    assertEquals(lowered.declarations(ResolvablesIndex()), "i32 %0, i32 %1")
    params.foreach { param =>
      assertEquals(scope(param.id.getOrElse(fail("Missing parameter identity"))), scope(param.name))
    }
    assertEquals(scope("x"), ScopeEntry(8, "Int"))
    assertEquals(scope("y"), ScopeEntry(9, "Int"))
    val result =
      value(compileTerm(Ref(source, "renamed", resolvedId = params.head.id), CodeGenState(), scope))
    assertEquals(result.typeName, "Unit")
    assert(result.isLiteral)
    assertEquals(result.state.output, Nil)
    assertEquals(result.state.nextRegister, 0)
  }

  test("nullary signatures do not invent a lexical Unit parameter") {
    val lowered = value(ParameterLowering.from(lambda(), List("void")))
    assertEquals(lowered.parameters, Nil)
    assertEquals(value(lowered.scope(Nil)), Map.empty[String, ScopeEntry])
    assertEquals(lowered.declarations(ResolvablesIndex()), "")
  }

  test("parameter and register count mismatches return codegen errors") {
    val body     = lambda(parameter("u", unit))
    val mismatch = ParameterLowering.from(body, Nil).swap.toOption
    assertEquals(mismatch.flatMap(_.node), body.some)
    val lowered = value(ParameterLowering.from(body, List("void")))
    assert(lowered.scope(List(0)).isLeft)
    assert(ParameterLowering.from(lambda(), List("i32")).isLeft)
  }

  test("computed parameter types take precedence and absent types are diagnosed") {
    val inferred = parameter("x", int).copy(typeAsc = unit.some)
    val lowered  = value(ParameterLowering.from(lambda(inferred), List("i32")))
    assertEquals(value(lowered.scope(List(2)))("x"), ScopeEntry(2, "Int"))
    val ascribed = inferred.copy(typeSpec = none, typeAsc = int.some)
    val fallback = value(ParameterLowering.from(lambda(ascribed), List("i32")))
    assertEquals(value(fallback.scope(List(2)))("x"), ScopeEntry(2, "Int"))
    val missing = inferred.copy(typeSpec = none, typeAsc = none)
    val invalid = value(ParameterLowering.from(lambda(missing), List("i32")))
    assertEquals(invalid.scope(List(2)).swap.toOption.flatMap(_.node), missing.some)
  }

  test("missing local and unresolved references cannot become global loads") {
    val local = parameter("u", unit)
    val state = CodeGenState(resolvables = ResolvablesIndex().updated(local))
    val references = List(
      Ref(source, "u", typeSpec = unit.some, resolvedId = local.id),
      Ref(source, "u", typeSpec = unit.some),
      Ref(source, "u", typeSpec = unit.some, resolvedId = "absent".some)
    )
    references.foreach { ref =>
      val error = compileTerm(ref, state).swap.toOption
      assertEquals(error.flatMap(_.node), ref.some)
      assert(error.exists(_.message.contains("Missing local binding or unresolved reference")))
    }
  }
