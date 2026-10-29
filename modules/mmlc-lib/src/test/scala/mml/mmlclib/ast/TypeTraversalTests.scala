package mml.mmlclib.ast

import cats.data.{NonEmptyList, State}
import cats.syntax.all.*
import mml.mmlclib.errors.DiagnosticId
import mml.mmlclib.test.BaseEffFunSuite

class TypeTraversalTests extends BaseEffFunSuite:

  private val source   = SourceOrigin.Loc(SrcSpan(SrcPoint(1, 1, 0), SrcPoint(1, 2, 1)))
  private val variable = TypeVariable(source, "'T")
  private val intType  = TypeRef(source, "Int", "int".some)
  private val boolType = TypeRef(source, "Bool", "bool".some)

  test("queries visit the root and nested signature types in declaration order"):
    val parameter = TypeTuple(source, List(variable, intType))
    val result    = TypeGroup(source, List(boolType))
    val function  = TypeFn(source, NonEmptyList.one(parameter), result)

    assertEquals(TypeTraversal.children(function), List(parameter, result))
    assertEquals(
      TypeTraversal.collect(function) { case tpe => tpe },
      List(function, parameter, variable, intType, result, boolType)
    )
    assert(TypeTraversal.exists(function)(_ == function))
    assert(TypeTraversal.exists(function)(_ == boolType))
    assert(!TypeTraversal.exists(function)(_.isInstanceOf[TypeApplication]))

  test("effectful child traversal visits parameters before the result without descending"):
    type Visited[A] = State[List[Type], A]

    val parameter = TypeTuple(source, List(variable, boolType))
    val function  = TypeFn(source, NonEmptyList.of(parameter, boolType), variable)
    val traversal = TypeTraversal.traverseChildren[Visited](function) { child =>
      State(visited => (visited :+ child, intType))
    }
    val (visited, rewritten) = traversal.run(Nil).value

    assertEquals(visited, List(parameter, boolType, variable))
    assertEquals(
      rewritten,
      function.copy(paramTypes = NonEmptyList.of(intType, intType), returnType = intType)
    )

  test("application children put the base before arguments and rewrite nested types"):
    val argument = TypeScheme(
      source,
      Nil,
      Union(source, List(variable, Intersection(source, List(boolType, variable))))
    )
    val application = TypeApplication(source, variable, List(argument, intType))
    val rewritten = TypeTraversal.rewrite(application) {
      case value: TypeVariable if value.name == variable.name => intType
    }

    assertEquals(TypeTraversal.children(application), List(variable, argument, intType))
    assertEquals(
      TypeTraversal.collect(application) { case value: TypeVariable => value },
      List(variable, variable, variable)
    )
    assertEquals(
      rewritten,
      TypeApplication(
        source,
        intType,
        List(
          TypeScheme(
            source,
            Nil,
            Union(source, List(intType, Intersection(source, List(boolType, intType))))
          ),
          intType
        )
      )
    )

  test("rewriting aggregate fields preserves nominal identity and field metadata"):
    val record = TypeOpenRecord(source, List("value" -> variable))
    val native = NativeStruct(
      source,
      List("payload" -> TypeGroup(source, List(variable))),
      MemEffect.Alloc.some,
      "release".some
    )
    val first  = Field(source, Name("record", source), record, "holder.record".some)
    val second = Field(source, Name("native", source), native, "holder.native".some)
    val struct = TypeStruct(
      source,
      None,
      Visibility.Private,
      Name("Holder", source),
      Vector(first, second),
      "holder".some
    )
    val rewritten = TypeTraversal.rewrite(struct) {
      case value: TypeVariable if value.name == variable.name => intType
    }

    assertEquals(TypeTraversal.children(struct), List(record, native))
    assertEquals(
      rewritten,
      struct.copy(fields =
        Vector(
          first.copy(typeSpec = record.copy(fields = List("value" -> intType))),
          second.copy(typeSpec =
            native.copy(
              fields = List("payload" -> TypeGroup(source, List(intType)))
            )
          )
        )
      )
    )

  test("top-down rewriting does not descend into a replacement"):
    val original    = TypeTuple(source, List(variable, intType))
    val replacement = TypeFn(source, NonEmptyList.one(variable), boolType)
    val rewritten = TypeTraversal.rewrite(original) {
      case _: TypeTuple => replacement
      case _: TypeVariable => intType
    }

    assertEquals(rewritten, replacement)

  test("failed-reference evidence is outside the structural type tree"):
    val original = TypeRef(source, "Missing")
    val candidate = TypeStruct(
      source,
      None,
      Visibility.Protected,
      Name("Candidate", source),
      Vector(Field(source, Name("value", source), variable)),
      "candidate".some
    )
    val failure = AmbiguousTypeError(source, original, List(candidate), Vector(DiagnosticId(1)))
    val rewritten = TypeTraversal.rewrite(failure) {
      case _: TypeRef => intType
      case _: TypeVariable => boolType
    }

    assertEquals(TypeTraversal.children(failure), Nil)
    assertEquals(TypeTraversal.collect(failure) { case ref: TypeRef => ref }, Nil)
    assert(!TypeTraversal.exists(failure)(_.isInstanceOf[TypeVariable]))
    assertEquals(rewritten, failure)

  test("refinement expressions stay outside type traversal and retain invalid-type scanning"):
    val missing    = TypeRef(source, "Missing")
    val failure    = UndefinedTypeError(source, missing, Vector(DiagnosticId(1)))
    val expression = Expr(source, List(Hole(source, typeAsc = failure.some)))
    val refinement = TypeRefinement(source, None, expression)
    val rewritten = TypeTraversal.rewrite(refinement) { case _: TypeResolutionError =>
      intType
    }

    assertEquals(TypeTraversal.children(refinement), Nil)
    assertEquals(
      TypeTraversal.collect(refinement) { case error: TypeResolutionError => error },
      Nil
    )
    assertEquals(rewritten, refinement)
    assert(TypeUtils.containsInvalidType(refinement, Set.empty[String]))
