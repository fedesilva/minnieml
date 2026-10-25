package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.option.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class ValueAvailabilityTests extends BaseEffFunSuite:

  private val source  = SourceOrigin.Synth
  private val intType = NativePrimitive(source, "i32")

  private def integer: Expr =
    Expr(source, List(LiteralInt(source, 1, intType.some)), typeSpec = intType.some)

  private def failed: Expr =
    Expr(source, List(InvalidExpression(source, integer, typeSpec = intType.some)))

  private def reference(id: String, tpe: Option[Type] = intType.some): Expr =
    Expr(source, List(Ref(source, "value", typeSpec = tpe, resolvedId = id.some)))

  private def binding(id: String, value: Expr, tpe: Option[Type] = intType.some): Bnd =
    Bnd(
      source   = source,
      nameNode = Name.synth("value"),
      value    = value,
      typeSpec = tpe,
      id       = id.some
    )

  private def module(bindings: List[Bnd], index: ResolvablesIndex): Module =
    Module(source, "Availability", Visibility.Public, bindings, resolvables = index)

  test("checked prefixes retain available values and propagate a later failed definition") {
    val first     = binding("first", integer)
    val later     = binding("later", integer)
    val forward   = binding("forward", reference(later.id.get))
    val index     = ResolvablesIndex().updatedAll(List(first, forward, later))
    val prefix    = module(List(first), index)
    val initial   = ValueAvailability.fromModule(prefix)
    val extended  = module(List(first, forward), index)
    val available = initial.updatedModule(extended, forward)
    assert(available.unavailable.isEmpty)
    assert(available.isAvailable(forward.value))

    val broken   = later.copy(value = failed)
    val complete = module(List(first, forward, broken), index.updated(broken))
    val result   = available.updatedModule(complete, broken)
    assertEquals(result.unavailable, Set(forward.id.get, later.id.get))
    assert(result.isAvailable(first.value))
    assert(!result.isAvailable(forward.value))
  }

  test("a checked definition can establish a previously unavailable forward reference") {
    val later   = binding("later", integer, None)
    val forward = binding("forward", reference(later.id.get, None))
    val index   = ResolvablesIndex().updatedAll(List(forward, later))
    val initial = ValueAvailability.fromModule(module(List(forward), index))
    assertEquals(initial.unavailable, forward.id.toSet)

    val checked  = later.copy(typeSpec = intType.some)
    val complete = module(List(forward, checked), index.updated(checked))
    val result   = initial.updatedModule(complete, checked)
    assert(result.unavailable.isEmpty)
    assert(result.isAvailable(forward.value))
  }

  test("discarded failed local initializers do not taint an independent checked result") {
    val local   = FnParam(source, Name.synth("local"), typeSpec = intType.some, id = "local".some)
    val body    = LocalBindings.bind(local, failed, integer)
    val checked = binding("checked", Expr(source, List(body), typeSpec = intType.some))
    val index   = ResolvablesIndex().updatedAll(List(local, checked))
    val result  = ValueAvailability.empty.updatedModule(module(List(checked), index), checked)
    assertEquals(result.unavailable, local.id.toSet)
    assert(result.isAvailable(checked.value))
    assert(!result.isAvailable(reference(local.id.get)))
  }

  test("failed declaration types invalidate earlier references despite an available literal") {
    val later   = binding("later", integer)
    val forward = binding("forward", reference(later.id.get, None))
    val index   = ResolvablesIndex().updatedAll(List(forward, later))
    val initial = ValueAvailability.fromModule(module(List(forward), index))
    assert(initial.unavailable.isEmpty)

    val missing  = TypeRef(source, "Missing")
    val checked  = later.copy(typeSpec = UndefinedTypeError(source, missing).some)
    val complete = module(List(forward, checked), index.updated(checked))
    val result   = initial.updatedModule(complete, checked)
    assertEquals(result.unavailable, forward.id.toSet)
    assert(result.isAvailable(checked.value))
    assert(!result.isAvailable(forward.value))
  }

  test("named callable availability preserves recursion and rejects independent alias cycles") {
    val first  = TypeRef(source, "First", "first-type".some)
    val second = TypeRef(source, "Second", "second-type".some)
    val cycle  = TypeRef(source, "Cycle", "cycle-type".some)
    val firstAlias = TypeAlias(
      source   = source,
      nameNode = Name.synth(first.name),
      typeRef  = TypeFn(source, NonEmptyList.one(second), intType),
      id       = first.resolvedId
    )
    val secondAlias = TypeAlias(
      source   = source,
      nameNode = Name.synth(second.name),
      typeRef  = TypeTuple(source, List(first, intType)),
      id       = second.resolvedId
    )
    val cycleAlias = TypeAlias(
      source   = source,
      nameNode = Name.synth(cycle.name),
      typeRef  = cycle,
      id       = cycle.resolvedId
    )
    val index   = ResolvablesIndex().updatedAllTypes(List(firstAlias, secondAlias, cycleAlias))
    val initial = ValueAvailability.fromModule(module(Nil, index))
    def usable(tpe: Type, availability: ValueAvailability): Boolean =
      availability.isAvailable(LiteralInt(source, 1, tpe.some))

    assert(usable(first, initial))
    assert(usable(second, initial))
    assert(usable(TypeFn(source, NonEmptyList.one(first), second), initial))
    assert(!usable(cycle, initial))
    assert(!usable(TypeFn(source, NonEmptyList.one(first), cycle), initial))

    val broken      = secondAlias.copy(typeRef = TypeTuple(source, List(first, cycle)))
    val failedIndex = index.updatedType(broken)
    val failed      = ValueAvailability.fromModule(module(Nil, failedIndex), initial)
    assert(!usable(first, failed))
    assert(!usable(second, failed))

    val recovered = ValueAvailability.fromModule(module(Nil, index), failed)
    assert(usable(first, recovered))
    assert(usable(second, recovered))
  }
