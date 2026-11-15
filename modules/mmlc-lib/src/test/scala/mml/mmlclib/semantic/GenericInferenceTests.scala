package mml.mmlclib.semantic

import cats.data.NonEmptyList
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class GenericInferenceTests extends BaseEffFunSuite:

  private val source  = SourceOrigin.Synth
  private val first   = TypeVariable(source, "'T", "first".some)
  private val second  = TypeVariable(source, "'U", "second".some)
  private val outer   = TypeVariable(source, "'T", "outer".some)
  private val integer = NativePrimitive(source, "i32")
  private val decimal = NativePrimitive(source, "double")
  private val index   = ResolvablesIndex()

  private def function(inputs: NonEmptyList[Type], result: Type): TypeFn =
    TypeFn(source, inputs, result)

  private def infer(
    signature:  TypeFn,
    parameters: List[TypeVariable] = List(first),
    arguments:  List[Option[Type]] = Nil,
    expected:   Option[Type]       = None,
    explicit:   Option[List[Type]] = None
  ): Either[GenericInference.Failure, Map[String, Type]] =
    GenericInference.infer(signature, parameters, arguments, expected, explicit, index)

  test("argument and explicit inference agree on the complete substitution"):
    val signature    = function(NonEmptyList.one(first), first)
    val fromArgument = infer(signature, arguments = List(integer.some))
    val fromExplicit = infer(signature, explicit = List(integer).some)
    assertEquals(fromArgument, Right(Map("first" -> integer)))
    assertEquals(fromExplicit, fromArgument)

  test("repeated variables reject inconsistent argument evidence"):
    val signature = function(NonEmptyList.of(first, first), first)
    assertEquals(
      infer(signature, arguments = List(integer.some, decimal.some)),
      Left(GenericInference.Failure.ConflictingArgument(first, integer, decimal))
    )

  test("expected remaining function types jointly constrain partial applications"):
    val signature = function(NonEmptyList.of(first, second), first)
    val remaining = function(NonEmptyList.one(decimal), integer)
    assertEquals(
      infer(signature, List(first, second), List(integer.some), remaining.some),
      Right(Map("first" -> integer, "second" -> decimal))
    )

  test("expected function values constrain parameters without supplying value arguments"):
    val signature = function(NonEmptyList.one(first), first)
    val expected  = function(NonEmptyList.one(integer), integer)
    assertEquals(infer(signature, expected = expected.some), Right(Map("first" -> integer)))

  test("nested tuple and function arguments preserve variable relationships"):
    val nested    = TypeTuple(source, List(first, function(NonEmptyList.one(first), second)))
    val actual    = TypeTuple(source, List(integer, function(NonEmptyList.one(integer), decimal)))
    val signature = function(NonEmptyList.one(nested), second)
    assertEquals(
      infer(signature, List(first, second), List(actual.some), decimal.some),
      Right(Map("first" -> integer, "second" -> decimal))
    )

  test("fixed type constructors match structurally with the same nominal identity"):
    val constructor = TypeDef(
      source   = source,
      nameNode = Name.synth("Box"),
      typeSpec = None,
      id       = "box".some
    )
    val indexed   = index.updatedType(constructor)
    val base      = TypeRef(source, "Box", constructor.id)
    val signature = function(NonEmptyList.one(TypeApplication(source, base, List(first))), first)
    val actual    = TypeApplication(source, base, List(integer))
    assertEquals(
      GenericInference.infer(signature, List(first), List(actual.some), None, None, indexed),
      Right(Map("first" -> integer))
    )
    val unrelated = constructor.copy(id = "other-box".some)
    val wrong     = actual.copy(base = base.copy(resolvedId = unrelated.id))
    assert(
      GenericInference
        .infer(signature, List(first), List(wrong.some), None, None, indexed.updatedType(unrelated))
        .isLeft
    )

  test("abstract caller variables remain rigid and can satisfy repeated occurrences"):
    val signature = function(NonEmptyList.of(first, first), first)
    assertEquals(
      infer(signature, arguments = List(outer.some, outer.some), expected = outer.some),
      Right(Map("first" -> outer))
    )
    val rigid = function(NonEmptyList.one(outer), first)
    assertEquals(
      infer(rigid, arguments = List(integer.some), expected = integer.some),
      Left(GenericInference.Failure.IncompatibleTypes(outer, integer))
    )

  test("explicit arguments supply all variables in declaration order"):
    val signature = function(NonEmptyList.of(first, second), first)
    assertEquals(
      infer(signature, List(first, second), explicit = List(integer, decimal).some),
      Right(Map("first" -> integer, "second" -> decimal))
    )
    assertEquals(
      infer(signature, List(first, second), explicit = List(integer).some),
      Left(GenericInference.Failure.TypeArgumentCount(2, 1))
    )

  test("explicit choices are checked against value and expected-result evidence"):
    val signature = function(NonEmptyList.one(first), first)
    assertEquals(
      infer(signature, arguments = List(decimal.some), explicit = List(integer).some),
      Left(GenericInference.Failure.ConflictingArgument(first, integer, decimal))
    )
    assertEquals(
      infer(signature, expected = function(NonEmptyList.one(integer), decimal).some),
      Left(GenericInference.Failure.ConflictingArgument(first, integer, decimal))
    )

  test("missing evidence reports only unsolved variables"):
    val signature = function(NonEmptyList.of(first, second), first)
    assertEquals(
      infer(signature, List(first, second), List(integer.some, None)),
      Left(GenericInference.Failure.MissingArguments(List(second)))
    )

  test("partial inference exposes a solved callback input while its result awaits evidence"):
    val callback  = function(NonEmptyList.one(first), second)
    val signature = function(NonEmptyList.of(callback, first), second)
    val partial = GenericInference.inferPartial(
      signature,
      List(first, second),
      List(None, integer.some),
      None,
      None,
      index
    )
    assertEquals(partial, Right(GenericInference.Partial(Map("first" -> integer), List(second))))
    assertEquals(
      partial.flatMap(_.complete),
      Left(GenericInference.Failure.MissingArguments(List(second)))
    )
    assertEquals(
      infer(
        signature,
        List(first, second),
        List(function(NonEmptyList.one(integer), integer).some, integer.some)
      ),
      Right(Map("first" -> integer, "second" -> integer))
    )

  test("partial inference retains evidence before an unknown returned-call boundary"):
    val callback  = function(NonEmptyList.one(first), second)
    val signature = function(NonEmptyList.of(callback, first), second)
    val partial = GenericInference.inferPartial(
      signature,
      List(first, second),
      List(None, integer.some, decimal.some),
      None,
      None,
      index
    )
    assertEquals(partial, Right(GenericInference.Partial(Map("first" -> integer), List(second))))
    assertEquals(
      partial.flatMap(_.complete),
      Left(GenericInference.Failure.MissingArguments(List(second)))
    )

  test("known returned callable substitutions preserve invocation boundaries"):
    val signature = function(NonEmptyList.one(first), first)
    val callable  = function(NonEmptyList.one(integer), decimal)
    assertEquals(
      infer(signature, arguments = List(callable.some, integer.some), expected = decimal.some),
      Right(Map("first" -> callable))
    )
    assertEquals(
      infer(signature, arguments = List(integer.some, integer.some)),
      Left(GenericInference.Failure.NotCallable(integer))
    )

  test("unresolved binder identities cannot introduce name-based substitutions"):
    val unresolved = first.copy(resolvedId = None)
    val signature  = function(NonEmptyList.one(unresolved), unresolved)
    assertEquals(
      infer(signature, List(unresolved), List(integer.some)),
      Left(GenericInference.Failure.UnresolvedParameter(unresolved))
    )

  test("callee annotations infer result-only variables before value arguments"):
    val unit       = TypeUnit(source)
    val signature  = function(NonEmptyList.one(unit), first)
    val annotation = function(NonEmptyList.one(unit), integer)
    assertEquals(
      GenericInference.infer(
        signature,
        List(first),
        List(unit.some),
        None,
        None,
        index,
        List(List(annotation))
      ),
      Right(Map("first" -> integer))
    )

  test("annotations at later application boundaries constrain remaining function types"):
    val signature  = function(NonEmptyList.of(first, second), second)
    val annotation = function(NonEmptyList.one(decimal), decimal)
    assertEquals(
      GenericInference.infer(
        signature,
        List(first, second),
        List(integer.some, None),
        None,
        None,
        index,
        List(Nil, List(annotation))
      ),
      Right(Map("first" -> integer, "second" -> decimal))
    )

  test("all annotations at an application boundary must agree"):
    val signature = function(NonEmptyList.one(first), first)
    val integerFn = function(NonEmptyList.one(integer), integer)
    val decimalFn = function(NonEmptyList.one(decimal), decimal)
    assertEquals(
      GenericInference.infer(
        signature,
        List(first),
        List(None),
        None,
        None,
        index,
        List(List(integerFn, decimalFn))
      ),
      Left(GenericInference.Failure.ConflictingArgument(first, integer, decimal))
    )

  test("generic function arguments jointly infer a callee result from another argument"):
    val input     = function(NonEmptyList.one(first), second)
    val signature = function(NonEmptyList.of(input, first), second)
    val identity  = TypeScheme(source, List(outer), function(NonEmptyList.one(outer), outer))
    assertEquals(
      infer(signature, List(first, second), List(identity.some, integer.some)),
      Right(Map("first" -> integer, "second" -> integer))
    )

  test("each occurrence of a generic argument has an independent instantiation"):
    val input     = function(NonEmptyList.one(first), first)
    val other     = function(NonEmptyList.one(second), second)
    val signature = function(NonEmptyList.of(input, other, first, second), first)
    val identity  = TypeScheme(source, List(outer), function(NonEmptyList.one(outer), outer))
    assertEquals(
      infer(
        signature,
        List(first, second),
        List(identity.some, identity.some, integer.some, decimal.some)
      ),
      Right(Map("first" -> integer, "second" -> decimal))
    )

  test("recursive callers retain rigid identities shared with their callee declaration"):
    val signature = function(NonEmptyList.one(first), first)
    assertEquals(
      infer(signature, arguments = List(first.some), expected = first.some),
      Right(Map("first" -> first))
    )

  test("generic argument relationships cannot equate distinct rigid caller variables"):
    val signature =
      function(NonEmptyList.of(function(NonEmptyList.one(first), second), first), second)
    val identity = TypeScheme(source, List(outer), function(NonEmptyList.one(outer), outer))
    assert(
      infer(signature, List(first, second), List(identity.some, first.some), second.some).isLeft
    )

  test("recursive type equations from generic arguments fail without cyclic substitutions"):
    val signature = function(NonEmptyList.one(function(NonEmptyList.one(first), first)), first)
    val recursive = TypeScheme(
      source,
      List(outer),
      function(NonEmptyList.one(outer), function(NonEmptyList.one(outer), outer))
    )
    assertEquals(
      infer(signature, arguments = List(recursive.some)),
      Left(
        GenericInference.Failure.ConflictingArgument(
          first,
          outer,
          function(NonEmptyList.one(outer), outer)
        )
      )
    )

  test("unsolved generic argument binders do not escape in a callee substitution"):
    val signature = function(NonEmptyList.one(first), first)
    val identity  = TypeScheme(source, List(outer), function(NonEmptyList.one(outer), outer))
    infer(signature, arguments = List(identity.some)) match
      case Left(GenericInference.Failure.MissingArguments(parameters)) =>
        assert(parameters.contains(first))
        assert(parameters.contains(outer))
      case other => fail(s"Expected missing type evidence, got $other")

  test("partial completion rejects unsolved binders from generic argument schemes"):
    val signature = function(NonEmptyList.one(first), first)
    val identity  = TypeScheme(source, List(outer), function(NonEmptyList.one(outer), outer))
    GenericInference.inferPartial(
      signature,
      List(first),
      List(identity.some),
      None,
      None,
      index
    ) match
      case Right(partial) =>
        assertEquals(partial.substitution, Map.empty[String, Type])
        assertEquals(partial.missing.toSet, Set(first, outer))
        assertEquals(
          partial.complete,
          Left(GenericInference.Failure.MissingArguments(partial.missing))
        )
      case other => fail(s"Expected partial inference with unsolved binders, got $other")
