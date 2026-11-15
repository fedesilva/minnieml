package mml.mmlclib.ast

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.test.BaseEffFunSuite

class TypeSubstitutionTests extends BaseEffFunSuite:

  private val source   = SourceOrigin.Loc(SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 3, 2)))
  private val outer    = TypeVariable(source, "'T", "outer-variable".some)
  private val inner    = TypeVariable(source, "'T", "inner-variable".some)
  private val concrete = TypeRef(SourceOrigin.Synth, "Value", "nominal-value".some)

  test("substitution matches resolved identities and preserves unrelated and unresolved variables"):
    val unresolved = outer.copy(resolvedId = None)
    val input      = TypeTuple(source, List(outer, inner, unresolved))
    assertEquals(
      TypeSubstitution(input, Map(identityOf(outer) -> concrete)),
      input.copy(elements = List(concrete, inner, unresolved))
    )

  test("nested schemes protect their binders while substituting free enclosing variables"):
    val body   = TypeFn(source, NonEmptyList.of(inner, outer), outer)
    val scheme = TypeScheme(source, List(inner), body)
    val result = TypeSubstitution(
      scheme,
      Map(
        identityOf(outer) -> concrete,
        identityOf(inner) -> TypeUnit(source)
      )
    )
    assertEquals(
      result,
      scheme.copy(bodyType =
        body.copy(
          paramTypes = NonEmptyList.of(inner, concrete),
          returnType = concrete
        )
      )
    )

  test("simultaneous substitutions never revisit replacement variables"):
    val input = TypeTuple(source, List(outer, inner))
    assertEquals(
      TypeSubstitution(input, Map(identityOf(outer) -> inner, identityOf(inner) -> concrete)),
      input.copy(elements = List(inner, concrete))
    )

  test(
    "substitution in applications and aggregate fields preserves nominal and source evidence"
  ):
    val application = TypeApplication(source, concrete, List(outer))
    val record      = TypeOpenRecord(source, List("item" -> application))
    val field       = Field(source, Name.synth("data"), record, "field-id".some)
    val struct =
      TypeStruct(source, None, Visibility.Private, Name.synth("Box"), Vector(field), "box-id".some)
    val replacement = TypeGroup(source, List(concrete))
    val expected = struct.copy(fields =
      Vector(
        field.copy(typeSpec =
          record.copy(fields = List("item" -> application.copy(args = List(replacement))))
        )
      )
    )
    assertEquals(TypeSubstitution(struct, Map(identityOf(outer) -> replacement)), expected)

  private def identityOf(variable: TypeVariable): String =
    variable.resolvedId.getOrElse(fail("Expected a resolved fixture variable"))
