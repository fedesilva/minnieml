package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.DiagnosticRelation
import mml.mmlclib.test.BaseEffFunSuite
import mml.mmlclib.util.error.print.diagnosticMessage

class ProtocolConflictEvidenceTests extends BaseEffFunSuite:

  List(
    (List("15" -> "Int", "true" -> "Bool"), Nil),
    (List("15" -> "Int", "1.5" -> "Float"), List("Float")),
    (List("1.5" -> "Float", "15" -> "Int"), List("Float")),
    (List("1.5" -> "Float", "\"text\"" -> "String"), List("Float", "String")),
    (
      List("15" -> "Int", "16" -> "Int", "true" -> "Bool", "1.5" -> "Float"),
      List("Float")
    )
  ).foreach { (calls, missing) =>
    test(s"conflicting calls retain their types, locations, and missing instances: $calls"):
      val body = calls.map((value, _) => s"selected $value;").mkString("\n")
      checkConflict(renderInstances, s"let selected = render \"prefix\"; $body") {
        (source, state, error) =>
          assertEquals(error.bindingName, "selected")
          assertEquals(error.conflicts.size, 1)
          val conflict = error.conflicts.head
          val protocol = renderProtocol(state)
          assertEquals(conflict.protocol.id, protocol.id)
          assertRequirements(source, state, conflict, calls)
          assertEquals(
            conflict.missingInstances.map(requirement => typeKey(requirement.concreteType, state)),
            missing.map(namedKey(_, state))
          )
          assertEquals(excerpt(source, error.source), "render")
      }
  }

  test("a simple alias retains the call sites and the original binding name"):
    checkConflict(
      renderInstances,
      """
        let selected = render "prefix";
        let alias = selected;
        alias 15;
        selected true;
      """
    ) { (source, state, error) =>
      assertEquals(error.bindingName, "selected")
      assertRequirements(source, state, error.conflicts.head, List("15" -> "Int", "true" -> "Bool"))
    }

  test("a typed consumer identifies its actual use rather than the consumer declaration"):
    val declarations = renderInstances + "fn consume(f: Int -> Unit): Unit = f 1;;"
    checkConflict(
      declarations,
      """
        let selected = render "prefix";
        consume selected;
        selected true;
      """
    ) { (source, state, error) =>
      assertRequirements(
        source,
        state,
        error.conflicts.head,
        List("selected" -> "Int", "true" -> "Bool")
      )
      val first = error.conflicts.head.requirements.head.spanOpt
        .getOrElse(fail("Expected consumer argument location"))
      assertEquals(first.start.index, source.indexOf("consume selected") + "consume ".length)
    }

  test("a consumer's parameter and result produce one requirement at the same use site"):
    val declarations = """
      protocol Identity 'T = fn identity(value: 'T): 'T; ;
      instance Identity Int = fn identity(value) = value;; ;
      instance Identity Bool = fn identity(value) = value;; ;
      fn consume(f: Int -> Int): Int = f 1;;
    """
    checkConflict(
      declarations,
      "let selected = identity; let consumed = consume selected; let result = selected true; ();"
    ) { (source, state, error) =>
      assertEquals(error.conflicts.size, 1)
      val conflict = error.conflicts.head
      assertRequirements(source, state, conflict, List("selected" -> "Int", "true" -> "Bool"))
      assertEquals(conflict.missingInstances, Nil)
      val location = conflict.requirements.head.spanOpt
        .getOrElse(fail("Expected consumer argument location"))
      assertEquals(location.start.index, source.indexOf("consume selected") + "consume ".length)
      val note = s"  ${location.start.line}:${location.start.col}: requires Identity Int"
      assertEquals(diagnosticMessage(error).linesIterator.count(_ == note), 1)
    }

  test("written binding constraints retain both conflict evidence and an independent mismatch"):
    val source = renderInstances + """
      fn example(): Unit =
        let selected: String -> Int -> Unit = render;
        selected "prefix" true;
      ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)
      val state = TypeChecker.rewriteModule(initial)
      val conflicts = state.visibleErrors.collect {
        case SemanticError.ProtocolErrorFound(error: ProtocolError.ConflictingUses, _) => error
      }
      assertEquals(conflicts.size, 1)
      assertRequirements(
        source,
        state,
        conflicts.head.conflicts.head,
        List("String -> Int -> Unit" -> "Int", "true" -> "Bool")
      )
      assertEquals(excerpt(source, conflicts.head.source), "render")
      val mismatches = state.visibleErrors.collect {
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
      }
      assertEquals(mismatches.size, 1)
      assertEquals(state.visibleErrors.size, 3)
    }

  test("an incompatible fixed prefix does not produce a misleading protocol conflict"):
    val declarations = renderInstances + """
      protocol Other 'T = fn render(label: Bool, value: 'T): Unit; ;
      instance Other Int = fn render(label, value) = ();; ;
    """
    checkConflict(declarations, "let selected = render \"prefix\"; selected 15; selected true;") {
      (_, state, error) =>
        val expected = renderProtocol(state)
        assertEquals(error.conflicts.map(_.protocol.id).toList, List(expected.id))
    }

  test("an independent error remains visible beside a local protocol conflict"):
    val source = renderInstances + """
      fn example(): Unit =
        let selected = render "prefix";
        selected 15;
        selected true;
        absent;
      ;
    """
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      val result  = TypeChecker.rewriteModule(initial)
      assertEquals(result.visibleErrors.size, 4)
      assertEquals(result.visibleErrors.count(_.isInstanceOf[SemanticError.UndefinedRef]), 1)
      assertEquals(
        result.visibleErrors.count {
          case SemanticError.ProtocolErrorFound(_: ProtocolError.ConflictingUses, _) => true
          case _ => false
        },
        1
      )
    }

  private val renderInstances = """
    protocol Render 'T = fn render(label: String, value: 'T): Unit; ;
    instance Render Int = fn render(label, value) = ();; ;
    instance Render Bool = fn render(label, value) = ();; ;
  """

  /** Checks one primary conflict, its missing-instance reports, and their retained consequences.
    * Fixtures with independent errors need separate assertions.
    */
  private def checkConflict(declarations: String, body: String)(
    check: (String, CompilerState, ProtocolError.ConflictingUses) => Unit
  ) =
    val source = declarations + s"fn example(): Unit = $body ;"
    parseNotFailed(source).map { parsed =>
      val initial = prepareForTypeChecking(source, parsed)
      assertEquals(initial.errors, Vector.empty)
      val result = TypeChecker.rewriteModule(initial)
      val conflicts = result.visibleErrors.collect {
        case SemanticError.ProtocolErrorFound(error: ProtocolError.ConflictingUses, _) => error
      }
      assertEquals(conflicts.size, 1, result.visibleErrors)
      val error   = conflicts.head
      val primary = result.diagnostics.records.filter(_.relation == DiagnosticRelation.Primary)
      assertEquals(primary.size, 1 + error.conflicts.toList.flatMap(_.missingInstances).size)
      val consequences = result.diagnostics.records.filter(_.causes.nonEmpty)
      assert(consequences.nonEmpty)
      assert(consequences.forall(_.causes == primary.map(_.id)))
      check(source, result, error)
    }

  private def assertRequirements(
    source:   String,
    state:    CompilerState,
    conflict: ProtocolUseConflict,
    expected: List[(String, String)]
  ): Unit =
    val actual = conflict.requirements.toList.map { requirement =>
      excerpt(source, requirement.source) -> typeKey(requirement.concreteType, state)
    }
    assertEquals(actual, expected.map((text, name) => text -> namedKey(name, state)))

  private def typeKey(tpe: Type, state: CompilerState): ProtocolTypeKey =
    ProtocolRegistry
      .typeKey(tpe, state.module.resolvables)
      .fold(error => fail(s"Expected concrete type identity, got $error"), identity)

  private def namedKey(name: String, state: CompilerState): ProtocolTypeKey =
    typeKey(typeReference(name, state.module.resolvables), state)

  private def renderProtocol(state: CompilerState): Protocol =
    state.module.members
      .collectFirst { case value: Protocol if value.name == "Render" => value }
      .getOrElse(fail("Expected source Render protocol"))

  private def excerpt(source: String, origin: SourceOrigin): String =
    val span = origin.spanOpt.getOrElse(fail("Expected written requirement span"))
    source.substring(span.start.index, span.end.index)
