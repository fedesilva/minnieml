package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class TypeCompatibilityTests extends BaseEffFunSuite:

  private val source = SourceOrigin.Synth

  test("rigid variables compare by binder identity, including nested function arguments"):
    val variable = TypeVariable(source, "'T", "first-binder".some)
    val renamed  = variable.copy(name = "'U")
    val shadowed = variable.copy(resolvedId = "second-binder".some)
    val index    = ResolvablesIndex()
    def function(tpe: Type): TypeFn = TypeFn(source, NonEmptyList.one(tpe), variable)

    assert(TypeCompatibility.same(variable, renamed, index))
    assert(TypeCompatibility.same(function(variable), function(renamed), index))
    assert(!TypeCompatibility.same(variable, shadowed, index))
    assert(!TypeCompatibility.same(function(variable), function(shadowed), index))
    assert(!TypeCompatibility.same(variable, NativePrimitive(source, "i32"), index))
    assert(!TypeCompatibility.same(variable.copy(resolvedId = None), variable, index))

  test("fixed type constructor applications compare both constructor and argument identities"):
    val constructor = TypeDef(
      source   = source,
      nameNode = Name.synth("Array"),
      typeSpec = None,
      id       = "array".some
    )
    val other    = constructor.copy(id = "another-array".some)
    val variable = TypeVariable(source, "'T", "binder".some)
    val application = TypeApplication(
      source,
      TypeRef(source, constructor.name, constructor.id),
      List(variable)
    )
    val index = ResolvablesIndex().updatedType(constructor).updatedType(other)

    assert(
      TypeCompatibility.same(
        application,
        application.copy(args = List(variable.copy(name = "'Renamed"))),
        index
      )
    )
    assert(!TypeCompatibility.same(application, application.copy(args = Nil), index))
    assert(
      !TypeCompatibility.same(
        application,
        application.copy(args = List(variable.copy(resolvedId = "shadow".some))),
        index
      )
    )
    assert(
      !TypeCompatibility.same(
        application,
        application.copy(base = TypeRef(source, other.name, other.id)),
        index
      )
    )

  test("an alias has its target's concrete identity"):
    val number = TypeDef(
      source   = source,
      nameNode = Name.synth("Number"),
      typeSpec = NativePrimitive(source, "i32").some,
      id       = "number".some
    )
    val reference = TypeRef(source, number.name, number.id)
    val alias = TypeAlias(
      source   = source,
      nameNode = Name.synth("Count"),
      typeRef  = reference,
      id       = "count".some
    )
    val index = ResolvablesIndex().updatedType(number).updatedType(alias)

    assert(TypeCompatibility.same(reference, TypeRef(source, alias.name, alias.id), index))

  test("same-named nominal declarations with different IDs are incompatible"):
    val first = TypeDef(
      source   = source,
      nameNode = Name.synth("Number"),
      typeSpec = NativePrimitive(source, "i32").some,
      id       = "first.number".some
    )
    val second = first.copy(id = "second.number".some)
    val index  = ResolvablesIndex().updatedType(first).updatedType(second)

    assert(
      !TypeCompatibility.same(
        TypeRef(source, first.name, first.id),
        TypeRef(source, second.name, second.id),
        index
      )
    )

  test("Unit syntax and the built-in Unit declaration share identity"):
    val unit = TypeDef(
      source   = source,
      nameNode = Name.synth("Unit"),
      typeSpec = NativePrimitive(source, "void").some,
      id       = "stdlib::typedef::Unit".some
    )
    val index = ResolvablesIndex().updatedType(unit)

    assert(TypeCompatibility.same(TypeUnit(source), TypeRef(source, unit.name, unit.id), index))

  test("nested native primitives remain comparable in function and tuple signatures"):
    val integer = NativePrimitive(source, "i32")
    val flag    = NativePrimitive(source, "i1")
    val function = TypeFn(
      source,
      NonEmptyList.one(TypeTuple(source, List(integer, flag))),
      integer
    )
    val equivalent = function.copy(source =
      SourceOrigin.Derived(
        SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 2, 1))
      )
    )
    val different = function.copy(returnType = flag)

    assert(TypeCompatibility.same(function, equivalent, ResolvablesIndex()))
    assert(!TypeCompatibility.same(function, different, ResolvablesIndex()))
