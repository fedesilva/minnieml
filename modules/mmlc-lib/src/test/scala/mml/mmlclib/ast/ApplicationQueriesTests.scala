package mml.mmlclib.ast

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.queries.ApplicationQueries
import mml.mmlclib.test.BaseEffFunSuite

class ApplicationQueriesTests extends BaseEffFunSuite:

  private val source  = SourceOrigin.Synth
  private val integer = TypeRef(source, "Int")
  private val unary   = TypeFn(source, NonEmptyList.one(integer), integer)

  private def expression(term: Term): Expr = Expr(source, List(term), typeSpec = term.typeSpec)

  test("a chain retains every supplied boundary and its argument order") {
    val callee        = Ref(source, "target")
    val firstArgument = expression(LiteralInt(source, 1, integer.some))
    val lastArgument  = expression(LiteralInt(source, 2, integer.some))
    val first         = App(source, callee, firstArgument, typeSpec = unary.some)
    val last          = App(source, first, lastArgument, typeSpec = integer.some)

    assertEquals(ApplicationQueries.applicationNodes(last), (callee, List(first, last)))
    assertEquals(ApplicationQueries.application(last), (callee, List(firstArgument, lastArgument)))
    assertEquals(ApplicationQueries.applications(last), List(last))
  }

  test("application traversal includes callee bodies and arguments without repeating prefixes") {
    val number       = expression(LiteralInt(source, 1, integer.some))
    val bodyCall     = App(source, Ref(source, "body"), number)
    val callee       = Lambda(source, Nil, expression(bodyCall), Nil)
    val argumentCall = App(source, Ref(source, "argument"), number)
    val first        = App(source, callee, expression(argumentCall))
    val last         = App(source, first, number)

    assertEquals(ApplicationQueries.applications(last), List(last, bodyCall, argumentCall))
  }

  test("signature fallback requires identity and never overrides a present callee type") {
    val target = FnParam(
      source,
      Name.synth("target"),
      typeSpec = unary.some,
      id       = "callable-parameter".some
    )
    val index  = ResolvablesIndex().updated(target)
    val ref    = Ref(source, target.name, resolvedId = target.id)
    val binary = TypeFn(source, NonEmptyList.of(integer, integer), integer)

    assertEquals(ApplicationQueries.signature(ref, index), unary.some)
    assertEquals(ApplicationQueries.signature(ref.copy(resolvedId = None), index), None)
    assertEquals(ApplicationQueries.signature(ref.copy(resolvedId = "missing".some), index), None)
    assertEquals(ApplicationQueries.signature(ref.copy(typeSpec = integer.some), index), None)
    assertEquals(ApplicationQueries.signature(ref.copy(typeSpec = binary.some), index), binary.some)
    assertEquals(
      ApplicationQueries.signature(ref.copy(typeSpec = unary.some, resolvedId = None), index),
      unary.some
    )
  }

  test("signature lookup follows canonical aliases and does not recover from an alias cycle") {
    val alias = TypeAlias(
      source   = source,
      nameNode = Name.synth("Callable"),
      typeRef  = TypeGroup(source, List(unary)),
      id       = "callable-alias".some
    )
    val aliasRef = TypeRef(source, alias.name, alias.id)
    val callee   = Ref(source, "target", typeSpec = aliasRef.some)
    val index    = ResolvablesIndex().updatedType(alias)

    assertEquals(ApplicationQueries.signature(callee, index), unary.some)
    assertEquals(
      ApplicationQueries.signature(callee, index.updatedType(alias.copy(typeRef = aliasRef))),
      None
    )
  }
