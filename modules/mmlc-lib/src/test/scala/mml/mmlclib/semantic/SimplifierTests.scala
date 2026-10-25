package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class SimplifierTests extends BaseEffFunSuite:

  private val source   = SourceOrigin.Loc(SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 10, 9)))
  private val intType  = TypeRef(source, "Int32", "stdlib::typedef::Int32".some)
  private val intAsc   = TypeRef(source, "Int", "stdlib::typealias::Int".some)
  private val unary    = TypeFn(source, NonEmptyList.one(intType), intType)
  private val unaryAsc = TypeFn(source, NonEmptyList.one(intAsc), intAsc)

  private def argument(value: Int): Expr =
    Expr(
      source,
      List(LiteralInt(source, value, typeSpec = intType.some)),
      typeSpec = intType.some,
      typeAsc  = intAsc.some
    )

  private def grouped(expr: Expr): Expr =
    expr.copy(terms = List(TermGroup(source, expr)))

  test("direct lambda callee preserves parameter and capture identities and annotations") {
    val param = FnParam(
      source,
      Name("x", source),
      typeSpec = intType.some,
      typeAsc  = intAsc.some,
      id       = "test::parameter::x".some
    )
    val result = Ref(source, param.name, typeSpec = intType.some, resolvedId = param.id)
    val capture = Ref(
      source,
      "seed",
      typeSpec   = intType.some,
      resolvedId = "test::parameter::seed".some
    )
    val lambda = Lambda(
      source,
      List(param),
      Expr(source, List(result), typeSpec = intType.some),
      List(Capture.BorrowedRef(capture)),
      typeSpec = unary.some,
      typeAsc  = unaryAsc.some,
      meta     = LambdaMeta(isInvocationParameter = true).some
    )
    val expected = App(
      source,
      lambda,
      argument(7),
      typeSpec = intType.some,
      typeAsc  = intAsc.some
    )

    assertEquals(Simplifier.simplifyTerm(expected.copy(arg = grouped(expected.arg))), expected)
  }

  test("nested application callee simplifies each argument and preserves reference identity") {
    val binary = TypeFn(source, NonEmptyList.of(intType, intType), intType)
    val ref = Ref(
      source,
      "combine",
      typeSpec     = binary.some,
      resolvedId   = "test::binding::combine".some,
      candidateIds = List("test::binding::combine")
    )
    val inner = App(source, ref, argument(11), typeSpec = unary.some, typeAsc = unaryAsc.some)
    val outer = App(source, inner, argument(23), typeSpec = intType.some, typeAsc = intAsc.some)
    val input = outer.copy(
      fn  = inner.copy(arg = grouped(inner.arg)),
      arg = grouped(outer.arg)
    )

    assertEquals(Simplifier.simplifyTerm(input), outer)
  }

  test("qualified callee simplifies its evaluated qualifier without losing field identity") {
    val holderType = TypeRef(source, "Holder", "test::struct::Holder".some)
    val factory = Ref(
      source,
      "make",
      typeSpec   = TypeFn(source, NonEmptyList.one(intType), holderType).some,
      resolvedId = "test::binding::make".some
    )
    val qualifier = App(source, factory, argument(3), typeSpec = holderType.some)
    val field = Ref(
      source,
      "call",
      typeSpec     = unary.some,
      typeAsc      = unaryAsc.some,
      resolvedId   = "test::field::Holder::call".some,
      candidateIds = List("test::field::Holder::call"),
      qualifier    = qualifier.some
    )
    val wrappedQualifier = TermGroup(
      source,
      Expr(source, List(qualifier.copy(arg = grouped(qualifier.arg))))
    )
    val expected = App(source, field, argument(5), typeSpec = intType.some, typeAsc = intAsc.some)
    val input = expected.copy(
      fn  = field.copy(qualifier = wrappedQualifier.some),
      arg = grouped(expected.arg)
    )

    assertEquals(Simplifier.simplifyTerm(input), expected)
  }

  List(
    "computed only" -> (intType.some, none[TypeRef]),
    "declared only" -> (none[TypeRef], intAsc.some),
    "computed and declared" -> (intType.some, intAsc.some),
    "untyped" -> (none[TypeRef], none[TypeRef])
  ).foreach { case (name, (computed, declared)) =>
    test(s"expression simplification preserves $name type annotations") {
      val expected = argument(17).copy(typeSpec = computed, typeAsc = declared)

      assertEquals(Simplifier.simplifyExpr(grouped(expected)), expected)
    }
  }
