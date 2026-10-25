package mml.mmlclib.semantic

import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.test.BaseEffFunSuite

class GroupedLambdaApplicationTests extends BaseEffFunSuite:

  private val heads: List[(String, String => String)] = List(
    "ungrouped" -> identity[String],
    "grouped" -> (value => s"($value)"),
    "nested groups" -> (value => s"(($value))")
  )

  private def binding(module: Module, name: String = "result"): Bnd =
    module.members
      .collectFirst { case value: Bnd if value.name == name => value }
      .getOrElse(fail(s"Expected binding $name"))

  private def sourceParameter(value: Bnd, source: String, declaration: String): FnParam =
    val position = source.indexOf(declaration)
    assert(position >= 0, s"Missing declaration $declaration")
    val parameters = TermTraversal
      .collect(value.value) { case lambda: Lambda =>
        lambda.params.filter(_.nameNode.spanOpt.exists(_.start.index == position))
      }
      .flatten
    parameters match
      case List(parameter) => parameter
      case other => fail(s"Expected one source parameter at $position, found $other")

  private def sameType(module: Module, actual: Option[Type], expected: Option[Type]): Unit =
    val actualType   = actual.flatMap(TypeUtils.canonical(_, module.resolvables))
    val expectedType = expected.flatMap(TypeUtils.canonical(_, module.resolvables))
    assert(expectedType.isDefined, "Expected a resolved type")
    assertEquals(actualType, expectedType)

  private def parameterUses(module: Module, value: Bnd, parameter: FnParam): Unit =
    val id = parameter.id.getOrElse(fail("Expected source parameter identity"))
    assertEquals(module.resolvables.lookup(id), parameter.some)
    sameType(module, parameter.typeSpec, parameter.typeAsc)
    val uses = TermTraversal.collect(value.value) {
      case ref: Ref if ref.resolvedId.contains(id) => ref
    }
    assert(uses.nonEmpty, s"Expected references to parameter identity $id")
    uses.foreach(ref => sameType(module, ref.typeSpec, parameter.typeSpec))

  private def suppliedIntegers(module: Module, parameter: FnParam): List[Long] =
    val values = CallableValues.fromModule(module)

    def literals(term: Term, seen: Set[String]): List[Long] = term match

      case literal: LiteralInt => List(literal.value)

      case expression: Expr =>
        expression.terms match
          case List(value) => literals(value, seen)
          case other => fail(s"Expected one supplied value, found $other")

      case ref: Ref =>
        val id = ref.resolvedId.getOrElse(fail("Expected resolved argument alias"))
        assert(!seen.contains(id), "Argument aliases must not form a cycle")
        values.bindings
          .getOrElse(id, fail(s"Missing argument source for $id"))
          .flatMap(literals(_, seen + id))

      case other => fail(s"Expected a supplied integer or its alias, found $other")

    val id = parameter.id.getOrElse(fail("Expected source parameter identity"))
    values.bindings
      .getOrElse(id, fail(s"Missing arguments for $id"))
      .flatMap(literals(_, Set(id)))

  heads.foreach { (label, head) =>
    val addition = head("{ x: Int, y: Int -> x + y; }")

    test(s"$label full lambda application retains both parameter identities and types") {
      val source = s"let result = $addition 1 2;"
      semNotFailed(source).map { module =>
        val result = binding(module)
        val first  = sourceParameter(result, source, "x: Int")
        val second = sourceParameter(result, source, "y: Int")
        List(first, second).foreach(parameterUses(module, result, _))
        assertEquals(suppliedIntegers(module, first), List(1L))
        assertEquals(suppliedIntegers(module, second), List(2L))
        sameType(module, result.typeSpec, first.typeSpec)
      }
    }

    test(s"$label partial lambda application retains its supplied and residual parameters") {
      val source = s"let result = $addition 1;"
      semNotFailed(source).map { module =>
        val result   = binding(module)
        val supplied = sourceParameter(result, source, "x: Int")
        val residual = sourceParameter(result, source, "y: Int")
        List(supplied, residual).foreach(parameterUses(module, result, _))
        assertEquals(suppliedIntegers(module, supplied), List(1L))
        result.typeSpec.flatMap(TypeUtils.canonical(_, module.resolvables)) match
          case Some(callable: TypeFn) =>
            assertEquals(callable.paramTypes.size, 1)
            sameType(module, callable.paramTypes.head.some, residual.typeSpec)
            sameType(module, callable.returnType.some, supplied.typeSpec)
          case other => fail(s"Expected a residual callable type, found $other")
      }
    }

    test(s"$label lambda application supplies extra arguments to its returned lambda") {
      val returning = head("{ x: Int -> { y: Int -> x + y; }; }")
      val source    = s"let result = $returning 1 2;"
      semNotFailed(source).map { module =>
        val result = binding(module)
        val first  = sourceParameter(result, source, "x: Int")
        val second = sourceParameter(result, source, "y: Int")
        List(first, second).foreach(parameterUses(module, result, _))
        assertEquals(suppliedIntegers(module, first), List(1L))
        assertEquals(suppliedIntegers(module, second), List(2L))
        sameType(module, result.typeSpec, first.typeSpec)
      }
    }

    test(s"$label overapplication is rejected by type checking") {
      semVisibleState(s"let result = $addition 1 2 3;").map { state =>
        val applications = state.errors.collect {
          case SemanticError.TypeCheckingError(error: TypeError.InvalidApplication) => error
        }
        assertEquals(applications.size, 1)
        assertEquals(state.errors.size, 1, state.errors.toString)
        val argument = applications.head.app.arg
        assertEquals(
          TermTraversal.collect(argument) { case value: LiteralInt => value.value },
          List(3L)
        )
      }
    }

    test(s"$label application reports the wrong second argument at its source span") {
      val source = s"let result = $addition 1 true;"
      semVisibleState(source).map { state =>
        val mismatches = state.errors.collect {
          case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
        }
        assertEquals(mismatches.size, 1)
        assertEquals(state.errors.size, 1, state.errors.toString)
        val mismatch = mismatches.head
        val second   = sourceParameter(binding(state.module), source, "y: Int")
        sameType(state.module, mismatch.expected.some, second.typeAsc)
        assertEquals(TypeUtils.getTypeName(mismatch.actual), "Bool".some)
        val argument = mismatch.node match
          case expression: Expr => expression
          case other => fail(s"Expected an argument expression, found $other")
        assertEquals(
          argument.spanOpt.map(span => (span.start.index, span.end.index)),
          (source.indexOf("true"), source.indexOf("true") + 4).some
        )
      }
    }

    test(s"$label nullary lambda application accepts explicit Unit") {
      semNotFailed(s"let result = ${head("{ 42; }")} ();").map { module =>
        val result   = binding(module)
        val literals = TermTraversal.collect(result.value) { case value: LiteralInt => value }
        assertEquals(literals.map(_.value), List(42L))
        sameType(module, result.typeSpec, literals.head.typeSpec)
      }
    }
  }

  test("a grouped top-level reference applies its eta-expanded parameters") {
    val source = """
      fn add(x: Int, y: Int): Int = x + y;;
      let result = ((add)) 1 2;
    """
    semNotFailed(source).map { module =>
      val target = binding(module, "add")
      val result = binding(module)
      val uses = TermTraversal.collect(result.value) {
        case ref: Ref if ref.resolvedId == target.id => ref
      }
      assertEquals(uses.size, 1)
      val first = sourceParameter(target, source, "x: Int")
      sameType(module, result.typeSpec, first.typeSpec)
    }
  }

  test("a grouped callable parameter retains its resolved identity") {
    val source = """
      fn apply(callee: Int -> Int -> Int): Int = ((callee)) 1 2;;
    """
    semNotFailed(source).map { module =>
      val result = binding(module, "apply")
      val callee = sourceParameter(result, source, "callee: Int")
      parameterUses(module, result, callee)
      val uses = TermTraversal.collect(result.value) {
        case ref: Ref if ref.resolvedId == callee.id => ref
      }
      assertEquals(uses.size, 1)
    }
  }
