package mml.mmlclib.semantic

import cats.effect.IO
import cats.syntax.all.*
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.test.ast.{captureResolvedIds, collectUserLambdas}

class FieldQualifierResolutionTests extends BaseEffFunSuite:

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case b: Bnd if b.name == name => b }
      .getOrElse(fail(s"Expected binding '$name'"))

  private def lambda(binding: Bnd): Lambda =
    binding.value.terms
      .collectFirst { case l: Lambda => l }
      .getOrElse(fail(s"Expected lambda for '${binding.name}'"))

  private def assertFieldSelection(module: Module, fieldName: String): Unit =
    val field = module.members
      .collectFirst { case struct: TypeStruct if struct.name == "Box" => struct }
      .flatMap(_.fields.toList.find(_.name == fieldName))
      .getOrElse(fail(s"Expected Box.$fieldName"))
    val selections = TermTraversal.collect(binding(module, "read").value) {
      case ref: Ref if ref.qualifier.isDefined => ref
    }

    assert(field.id.nonEmpty)
    assertEquals(selections.size, 1)
    assertEquals(selections.head.resolvedId, field.id)
    assert(selections.head.typeSpec.nonEmpty)

  List(
    "function call" -> "predicate flag",
    "operator call" -> "number == 1",
    "combined calls" -> "identity number == 1"
  ).foreach { (label, predicate) =>
    test(s"conditional field qualifier resolves $label") {
      semNotFailed(s"""
        struct Box { value: Int };
        fn predicate(flag: Bool): Bool = flag;;
        fn identity(number: Int): Int = number;;
        fn read(box: Box, flag: Bool, number: Int): Int =
          (if $predicate then box; else box;).value;
        ;
      """).map(assertFieldSelection(_, "value"))
    }
  }

  test("parenthesized function result resolves as a field qualifier") {
    semNotFailed("""
      struct Box { value: Int };
      fn make(number: Int): Box = Box number;;
      fn read(number: Int): Int = (make number).value;;
    """).map(assertFieldSelection(_, "value"))
  }

  private def assertCaptures(closure: Lambda, params: List[FnParam]): Unit =
    assert(params.forall(_.id.nonEmpty))
    assertEquals(captureResolvedIds(closure), params.flatMap(_.id).toSet)
    closure.captures.foreach { capture =>
      val param = params
        .find(_.id == capture.ref.resolvedId)
        .getOrElse(fail(s"Unexpected capture ${capture.ref.resolvedId}"))
      assert(param.typeSpec.nonEmpty)
      assertEquals(capture.ref.typeSpec, param.typeSpec)
    }

  for
    (label, selection) <- List("value" -> "value", "callable" -> "call 7")
    nested <- List(false, true)
  do
    test(s"$label field qualifier preserves captures with nested getter=$nested") {
      val readValue = s"(if invoke { flag } then box; else box;).$selection"
      val body =
        if nested then s"let getter = { $readValue; }; getter ();"
        else s"$readValue;"
      semNotFailed(s"""
        struct Box { value: Int, call: Int -> Int };
        fn invoke(f: Unit -> Bool): Bool = f ();;
        fn read(box: Box, flag: Bool): Int = $body;
      """).map { module =>
        val reader   = binding(module, "read")
        val params   = lambda(reader).params
        val flag     = params.find(_.name == "flag").getOrElse(fail("Expected flag parameter"))
        val closures = collectUserLambdas(reader)
        assertEquals(closures.size, if nested then 2 else 1)
        val predicate = closures
          .find(closure => collectUserLambdas(closure.body).isEmpty)
          .getOrElse(fail("Expected predicate closure"))
        assertCaptures(predicate, List(flag))

        if nested then
          val getter = closures
            .find(closure => collectUserLambdas(closure.body).nonEmpty)
            .getOrElse(fail("Expected getter closure"))
          assertCaptures(getter, params)
      }
    }

  List(
    ("non-Boolean predicate", "(if number then box; else box;).value", "Bool", "Int"),
    ("wrong call argument", "(make true).value", "Int", "Bool")
  ).foreach { (label, selection, expected, actual) =>
    test(s"field qualifier reports a type mismatch for $label") {
      semState(s"""
        struct Box { value: Int };
        fn make(number: Int): Box = Box number;;
        fn read(box: Box, number: Int): Int = $selection;;
      """).map { result =>
        val mismatches = result.errors.collect {
          case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
        }
        assertEquals(mismatches.size, 1, result.errors)
        assertEquals(TypeUtils.getTypeName(mismatches.head.expected), expected.some)
        assertEquals(TypeUtils.getTypeName(mismatches.head.actual), actual.some)
      }
    }
  }

  test("a resolved function-result qualifier reports an unknown selected field") {
    semState("""
      struct Box { value: Int };
      fn make(number: Int): Box = Box number;;
      fn read(number: Int): Int = (make number).missing;;
    """).map { result =>
      val errors = result.errors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.UnknownField) => error
      }
      val box = result.module.members
        .collectFirst { case struct: TypeStruct if struct.name == "Box" => struct }
        .getOrElse(fail("Expected Box structure"))
      assertEquals(errors.size, 1, result.errors)
      assertEquals(errors.head.struct.id, box.id)
      assertEquals(errors.head.ref.name, "missing")
    }
  }

  private def resolveReferences(source: String): IO[CompilerState] =
    parseNotFailed(source).map { parsed =>
      val initial  = CompilerState.empty(parsed, SourceInfo(source), CompilerConfig.default)
      val assigned = IdAssigner.rewriteModule(initial)
      assertEquals(assigned.errors, Vector.empty)
      ResolvablesIndexer.rewriteModule(RefResolver.rewriteModule(assigned))
    }

  private def scopedQualifier(predicate: String): String = s"""
    struct Box { value: Int };
    fn read(box: Box, other: Box): Int =
      (if $predicate then
        let box = other;
        box;
      else
        box;
      ).value;
    ;
  """

  private def assertScopedReferences(module: Module): Unit =
    val reader = lambda(binding(module, "read"))
    val scopes = TermTraversal.collect(reader.body) { case l: Lambda => l }
    assertEquals(scopes.size, 1)
    val local = scopes.head
    assertEquals(local.params.size, 1)
    val localBox = local.params.head
    val outerBox = reader.params.find(_.name == "box").getOrElse(fail("Expected box parameter"))
    assert(localBox.id.nonEmpty)
    assert(outerBox.id.nonEmpty)
    assert(localBox.id != outerBox.id)

    val localRefs = TermTraversal.collect(local.body) { case ref: Ref => ref }
    assertEquals(localRefs.size, 1)
    assertEquals(localRefs.head.resolvedId, localBox.id)

    val allRefs = TermTraversal.collect(reader.body) { case ref: Ref => ref }
    (local.params ++ reader.params).foreach { param =>
      assertEquals(param.id.flatMap(module.resolvables.lookup), param.some)
      val refs = allRefs.filter(_.resolvedId == param.id)
      assertEquals(refs.size, 1)
      assertEquals(refs.head.candidateIds, param.id.toList)
    }

  test("qualifier references retain local shadowing and enclosing parameter identities") {
    resolveReferences(scopedQualifier("true")).map { state =>
      assertEquals(state.errors, Vector.empty)
      assertScopedReferences(state.module)
    }
  }

  test("undefined qualifier predicate is retained while valid sibling references resolve") {
    resolveReferences(scopedQualifier("missing")).map { state =>
      val errors = state.semanticErrors.toList.collect { case e: SemanticError.UndefinedRef => e }
      assertEquals(state.semanticErrors.size, 1)
      assertEquals(errors.map(_.ref.name), List("missing"))

      val invalid = TermTraversal.collect(binding(state.module, "read").value) {
        case expression: InvalidExpression => expression
      }
      assertEquals(invalid.size, 1)
      assertEquals(invalid.head.originalExpr.terms, List(errors.head.ref))
      assertEquals(invalid.head.source, errors.head.ref.source)
      assertScopedReferences(state.module)
    }
  }

  List(
    "direct" -> "box.call 7",
    "conditional" -> "(if true then box; else box;).call 7"
  ).foreach { (label, call) =>
    test(s"$label field call infers a qualifier declared later") {
      semNotFailed(s"""
        struct Box { call: Int -> Int };
        fn read() = $call;;
        let box = Box identity;
        fn identity(number: Int): Int = number;;
      """).map { module =>
        assertFieldSelection(module, "call")
        val reader = binding(module, "read")
        val box    = binding(module, "box")
        val refs = TermTraversal.collect(reader.value) {
          case ref: Ref if ref.resolvedId == box.id => ref
        }
        assert(refs.nonEmpty)
        refs.foreach(ref => assertEquals(ref.typeSpec, box.typeSpec))
        val expected = lambda(binding(module, "identity")).body.typeSpec
          .flatMap(TypeUtils.canonical(_, module.resolvables))
        val actual = lambda(reader).body.typeSpec
          .flatMap(TypeUtils.canonical(_, module.resolvables))
        assert(expected.nonEmpty)
        assertEquals(actual, expected)
      }
    }
  }
