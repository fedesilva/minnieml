package mml.mmlclib.semantic

import mml.mmlclib.ast.*
import mml.mmlclib.compiler.CompilerState
import mml.mmlclib.errors.{DiagnosticRelation, diagnosticSources}
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
          val protocol = protocolNamed(state, "Render")
          assertEquals(conflict.protocol.id, protocol.id)
          assertRequirements(source, state, conflict, calls)
          assertEquals(
            conflict.missingInstances.map(requirement => typeKey(requirement.concreteType, state)),
            missing.map(namedKey(_, state))
          )
          assertEquals(excerpt(source, error.source), "render")
      }
  }

  test("a stored generic member locates conflicting receiver arguments"):
    checkStoredReceivers(
      """protocol Probe 'T = fn probe(key: 'T, flag: 'U): Unit; ;
         instance Probe Int = fn probe(key, flag) = ();; ;""",
      "probe",
      "1 true",
      "1.5 true"
    )

  test("a stored generic member locates receiver arguments before a returned callable"):
    checkStoredReceivers(
      """protocol Probe 'T = fn probe(key: 'T, ~f: 'U): 'U; ;
         instance Probe Int = fn probe(key, ~f) = f;; ;
         fn consume(x: Int): Unit = ();;""",
      "probe",
      "1 consume 1",
      "1.5 consume 1"
    )

  test("a stored generic member locates receiver types supplied by generic callbacks"):
    checkStoredReceivers(
      """protocol Probe 'T = fn probe(f: 'U -> 'T): Unit; ;
         instance Probe Int = fn probe(f) = ();; ;
         fn whole(x: 'A): Int = 1;;
         fn fraction(x: 'A): Float = 1.5;;""",
      "probe Int",
      "whole",
      "fraction",
      genericCallbackBinder = Some("'A")
    )

  test("a repeated receiver inside one callback retains both requirements at its argument"):
    val declarations = """
      protocol Probe 'T = fn probe(f: 'T -> 'T, marker: 'U): Unit; ;
      instance Probe Int = fn probe(f, marker) = ();; ;
      fn whole(x: Int): Int = x;;
      fn fraction(x: Int): Float = 1.5;;
    """
    checkConflict(
      declarations,
      "let selected = probe; selected whole true; selected fraction true;"
    ) { (source, state, error) =>
      val group = error.conflicts.head
      assertRequirements(
        source,
        state,
        group,
        List("whole" -> "Int", "fraction" -> "Int", "fraction" -> "Float")
      )
      assertCallbackSites(source, group, List("whole", "fraction", "fraction"))
      assertEquals(
        group.missingInstances.map(r => excerpt(source, r.source)),
        List("selected fraction true")
      )
    }

  for input <- List("'A", "Int") do
    test(s"a nested callback with input $input locates its incompatible receiver types"):
      val declarations = s"""
        protocol Probe 'T = fn probe(f: ('T -> 'T) -> 'T, tag: 'U): Unit; ;
        instance Probe Int = fn probe(f, tag) = ();; ;
        fn candidate(f: $input -> Int): Float = 1.5;;
        fn compatible(f: $input -> Int): Int = 1;;
      """
      val call = "selected candidate true"
      val valid = semNotFailed(
        declarations + "fn example(): Unit = let selected = probe; selected compatible true;;"
      )
      val conflict = checkConflict(declarations, s"let selected = probe; $call;") {
        (source, state, error) =>
          val probe = protocolNamed(state, "Probe")
          assertEquals(error.conflicts.toList.map(_.protocol.id), List(probe.id))
          val group = error.conflicts.head
          assertRequirements(
            source,
            state,
            group,
            List("candidate" -> "Int", "candidate" -> "Float")
          )
          assertCallbackSites(source, group, List("candidate", "candidate"))
          assertMissingReports(
            source,
            state,
            List((probe, namedKey("Float", state), source.indexOf(call), call))
          )
      }
      valid.flatMap(_ => conflict)

  test("receiver conflict recovery rules out a call with an incompatible fixed argument"):
    val source = """
      protocol Probe 'T = fn probe(key: 'T, marker: 'U, flag: Int): Unit; ;
      instance Probe Int = fn probe(key, marker, flag) = ();; ;
      fn example(): Unit = let selected = probe; selected 1 true 1; selected 1.5 true false;;
    """
    val incompatible = semVisibleState(source).map { state =>
      val origins = state.errors.map {
        case SemanticError.TypeCheckingError(
              TypeError.GenericInferenceFailed(
                ref,
                GenericInference.Failure.ConflictingArgument(variable, _, _),
                _
              )
            ) =>
          (variable.name, excerpt(source, ref.source))
        case SemanticError.TypeCheckingError(TypeError.UnresolvableType(node: Ref, _, _)) =>
          ("use", excerpt(source, node.source))
        case error => fail(s"Unexpected diagnostic: ${diagnosticMessage(error)}")
      }
      assertEquals(origins, List("'T" -> "probe", "use" -> "selected", "use" -> "selected"))
    }
    val control = source.replace("1.5 true false", "1.5 true 2")
    val compatible = semVisibleState(control).map { state =>
      val protocols = state.errors.collect {
        case SemanticError.ProtocolErrorFound(e: ProtocolError.ConflictingUses, _) =>
          e.conflicts.toList.map(_.protocol.name)
      }
      assertEquals(protocols, List(List("Probe")))
      val missing = state.errors.collect {
        case SemanticError.ProtocolErrorFound(e: ProtocolError.MissingInstance, _) =>
          excerpt(control, e.source)
      }
      assertEquals(missing, List("selected 1.5 true 2"))
    }
    incompatible.flatMap(_ => compatible)

  for callbacks <- List(
      List("fraction" -> "Float", "identity" -> "Int"),
      List("identity" -> "Int", "fraction" -> "Float")
    )
  do
    test(s"receiver conflict recovery shares member-local constraints in call order $callbacks"):
      val declarations = """
        protocol Probe 'T = fn probe(f: 'U -> 'T): Unit; ;
        instance Probe Float = fn probe(f) = ();; ;
        fn fraction(x: Int): Float = 1.5;;
        fn identity(~x: 'A): 'A = x;;
      """
      val calls        = callbacks.map((name, _) => s"selected $name;").mkString(" ")
      checkConflict(declarations, s"let selected = probe; $calls") { (source, state, error) =>
        val group = error.conflicts.head
        assertRequirements(source, state, group, callbacks)
        assertCallbackSites(source, group, callbacks.map(_._1))
        assertEquals(
          group.missingInstances.map(r => excerpt(source, r.source)),
          List("selected identity")
        )
      }

  for returnedFirst <- List(true, false) do
    test(s"returned receiver types retain all three sites with callable first = $returnedFirst"):
      val declarations = """
        protocol Probe 'T = fn probe(marker: 'U): 'T; ;
        instance Probe Int = fn probe(marker) = 1;; ;
      """
      val values       = "let a: Int = selected true; let b: Float = selected true;"
      val invoke       = "let c: Int = selected true 1;"
      val uses         = if returnedFirst then s"$invoke $values" else s"$values $invoke"
      checkConflict(declarations, s"let selected = probe Bool; $uses ();") {
        (source, state, error) =>
          val group    = error.conflicts.head
          val int      = namedKey("Int", state)
          val function = ProtocolTypeKey.Function(List(int), int)

          def position(assignment: String, token: String) =
            source.indexOf(assignment) + assignment.indexOf(token)

          val expected = List(
            position("let a: Int = selected true;", "selected") -> int,
            position("let b: Float = selected true;", "selected") -> namedKey("Float", state),
            position(invoke, "1") -> function
          ).sortBy(_._1)
          val actual = group.requirements.toList.map(r =>
            r.spanOpt.map(_.start.index) -> typeKey(r.concreteType, state)
          )
          assertEquals(actual, expected.map((site, tpe) => Some(site) -> tpe))
          val probe = protocolNamed(state, "Probe")
          assertMissingReports(
            source,
            state,
            List(
              (probe, function, position(invoke, "selected"), "selected true 1"),
              (
                probe,
                namedKey("Float", state),
                position("let b: Float = selected true;", "selected"),
                "selected true"
              )
            )
          )

      }

  for printable <- List(false, true) do
    test(
      s"receiver conflicts include eligible members with Printable Bool = $printable"
    ):
      val declarations = """
        protocol Printable 'T = fn show(x: 'T): Unit; ;
        protocol Base 'T = fn probe(f: 'U -> 'T): Unit; ;
        protocol Limited 'T = fn probe(f: 'U -> 'T): Unit where 'U: Printable; ;
        instance Base Float = fn probe(f) = ();; ;
        instance Limited Float = fn probe(f) = ();; ;
        fn fraction(x: Bool): Float = 1.5;;
        fn whole(x: 'A): Int = 1;;
      """ + (if printable then "instance Printable Bool = fn show(x) = ();; ;" else "")
      checkConflict(declarations, "let selected = probe; selected fraction; selected whole;") {
        (source, state, error) =>
          val expected = if printable then List("Base", "Limited") else List("Base")
          assertEquals(
            error.conflicts.toList.flatMap(_.protocol.id).sorted,
            expected.flatMap(name => protocolNamed(state, name).id).sorted
          )
          val missingCall = "selected whole"
          assertMissingReports(
            source,
            state,
            expected.map { name =>
              (
                protocolNamed(state, name),
                namedKey("Int", state),
                source.indexOf(missingCall),
                missingCall
              )
            }
          )
          error.conflicts.toList.foreach { group =>
            assertRequirements(
              source,
              state,
              group,
              List("fraction" -> "Float", "whole" -> "Int")
            )
            assertEquals(
              group.missingInstances.map(r => excerpt(source, r.source)),
              List("selected whole")
            )
          }
      }

  // The outer identity fixes 'U to 'T -> 'T. The endofunction callback fixes 'U to 'T.
  // Replacing the Float callback in each pair leaves 'U consistent while the receivers disagree.
  for
    (callback, callbackType, independent) <- List(
      ("identity", "('T -> 'T) -> 'U", "fixed"),
      ("endo", "('T -> 'U) -> Bool", "independent")
    )
    callbackFirst <- List(true, false)
  do
    test(s"$callback preserves the shared nested type with callback first = $callbackFirst"):
      val parameter = s"f: $callbackType"
      val parameters =
        if callbackFirst then s"$parameter, marker: 'T" else s"marker: 'T, $parameter"
      val names = if callbackFirst then "f, marker" else "marker, f"
      val declarations = s"""
        protocol Probe 'T = fn probe($parameters): Unit; ;
        instance Probe Int = fn probe($names) = ();; ;
        fn identity(~x: 'A): 'A = x;;
        fn endo(f: 'A -> 'A): Bool = true;;
        fn int_identity(x: Int): Int = x;;
        fn fixed(x: 'A): Int -> Int = int_identity;;
        fn independent(f: 'A -> 'B): Bool = true;;
      """

      def call(fn: String, marker: String) =
        if callbackFirst then s"selected $fn $marker" else s"selected $marker $fn"

      def body(calls: List[String]) = "let selected = probe; " + calls.mkString("; ") + "; ();"

      def program(calls: List[String]) = declarations + s"fn example(): Unit = ${body(calls)};"

      def inconsistent(firstMarker: (String, String), secondMarker: (String, String)) =
        val calls  = List(firstMarker, secondMarker).map((marker, _) => call(callback, marker))
        val source = program(calls)
        semVisibleState(source).map { state =>
          val errors = state.errors
          val index  = state.module.resolvables

          val conflicts = errors.collect {
            case SemanticError.TypeCheckingError(
                  TypeError.GenericInferenceFailed(
                    ref,
                    GenericInference.Failure.ConflictingArgument(_, first, second),
                    _
                  )
                ) =>
              (
                ref.spanOpt.map(_.start.index),
                excerpt(source, ref.source),
                ProtocolRegistry.typeKey(first, index),
                ProtocolRegistry.typeKey(second, index)
              )
          }
          val initializer = source.indexOf("let selected = probe;") + "let selected = ".length
          val firstType   = ProtocolRegistry.typeKey(typeReference(firstMarker._2, index), index)
          val secondType  = ProtocolRegistry.typeKey(typeReference(secondMarker._2, index), index)
          assertEquals(
            conflicts,
            List((Some(initializer), "probe", firstType, secondType))
          )
          assert(!errors.exists {
            case SemanticError.ProtocolErrorFound(
                  _: ProtocolError.ConflictingUses | _: ProtocolError.MissingInstance,
                  _
                ) =>
              true
            case _ => false
          })
        }

      def consistentLocals(reverse: Boolean) =
        val whole   = call(callback, "1")
        val missing = call(independent, "1.5")
        val markers = List((whole, "1", "Int"), (missing, "1.5", "Float"))
        val ordered = if reverse then markers.reverse else markers
        checkConflict(declarations, body(ordered.map(_._1))) { (source, state, error) =>
          val probe = protocolNamed(state, "Probe")
          assertEquals(error.conflicts.toList.map(_.protocol.id), List(probe.id))

          val markerSites = error.conflicts.head.requirements.toList.collect {
            case demand if Set("1", "1.5")(excerpt(source, demand.source)) =>
              (
                demand.spanOpt.map(span => span.start.index -> span.end.index),
                typeKey(demand.concreteType, state)
              )
          }
          val expectedSites = ordered.map { (written, marker, tpe) =>
            val start = source.indexOf(written) + written.indexOf(marker)
            (Some(start -> (start + marker.length)), namedKey(tpe, state))
          }
          assertEquals(markerSites, expectedSites)
          assertMissingReports(
            source,
            state,
            List((probe, namedKey("Float", state), source.indexOf(missing), missing))
          )
        }

      for
        _ <- semNotFailed(program(List(call(callback, "1"), call(callback, "2"))))
        _ <- inconsistent("1" -> "Int", "1.5" -> "Float")
        _ <- inconsistent("1.5" -> "Float", "1" -> "Int")
        _ <- consistentLocals(false)
        _ <- consistentLocals(true)
      yield ()

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
        val expected = protocolNamed(state, "Render")
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

  /** Checks the conflict on the initializer, the Int and Float requirements on each call's
    * single-token first operand, and the missing Probe Float instance on the entire second call.
    * Supplying genericCallbackBinder expects the first call's only operand to be a bare callback
    * and checks its dependent diagnostic at that name.
    */
  private def checkStoredReceivers(
    declarations:          String,
    initializer:           String,
    first:                 String,
    second:                String,
    genericCallbackBinder: Option[String] = None
  ) =
    val body = s"let selected = $initializer; selected $first; selected $second;"
    checkConflict(declarations, body) { (source, state, error) =>
      val probe = protocolNamed(state, "Probe")
      assertEquals(error.bindingName, "selected")
      assertEquals(excerpt(source, error.source), "probe")
      assertEquals(
        error.source.spanOpt.map(_.start.index),
        Some(source.indexOf(s"= $initializer;") + 2)
      )
      assertEquals(error.conflicts.map(_.protocol.id).toList, List(probe.id))
      assertRequirements(
        source,
        state,
        error.conflicts.head,
        List(first.takeWhile(_ != ' ') -> "Int", second.takeWhile(_ != ' ') -> "Float")
      )
      val starts = error.conflicts.head.requirements.toList.map(_.spanOpt.map(_.start.index))
      assertEquals(
        starts,
        List(first, second).map(args =>
          Some(source.indexOf(s"selected $args;") + "selected ".length)
        )
      )
      val firstCall  = source.indexOf(s"selected $first;")
      val secondCall = source.indexOf(s"selected $second;")
      assertMissingReports(
        source,
        state,
        List((probe, namedKey("Float", state), secondCall, s"selected $second"))
      )
      val expected = List(
        (diagnosticMessage(error), source.indexOf(s"= $initializer;") + 2, "probe"),
        (
          "No instance of protocol 'Probe' for Float provides 'probe'",
          secondCall,
          s"selected $second"
        ),
        ("Cannot resolve type for 'selected'", firstCall, "selected")
      ) ++ genericCallbackBinder.map { binder =>
        (s"Generic function '$first': cannot infer $binder", firstCall + "selected ".length, first)
      }
      val visible = state.visibleErrors.map { issue =>
        val sites = diagnosticSources(issue)
        assertEquals(sites.size, 1)
        val site = sites.head
        (diagnosticMessage(issue), site.spanOpt.map(_.start.index), excerpt(source, site))
      }
      assertEquals(visible.toList, expected.map((msg, start, token) => (msg, Some(start), token)))
    }

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
      assertEquals(
        primary.size,
        1 + error.conflicts.toList.flatMap(_.missingInstances).size,
        primary.map(r => diagnosticMessage(r.error))
      )
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

  /** Checks the protocol, type and full written call of every visible missing-instance report.
    * Reports are ordered by site and protocol, keeping their relative order within a tied group.
    */
  private def assertMissingReports(
    source:   String,
    state:    CompilerState,
    expected: List[(Protocol, ProtocolTypeKey, Int, String)]
  ): Unit =
    val reports = state.visibleErrors.collect {
      case SemanticError.ProtocolErrorFound(e: ProtocolError.MissingInstance, _) =>
        (
          e.protocol.id,
          typeKey(e.concreteType, state),
          e.source.spanOpt.map(_.start.index),
          excerpt(source, e.source)
        )
    }.toList
    val wanted = expected.map((p, t, site, call) => (p.id, t, Option(site), call))
    assertEquals(reports.sortBy(r => (r._3, r._1)), wanted.sortBy(r => (r._3, r._1)))

  /** Checks each receiver requirement's start offset against the named callback operand in order.
    * Each name identifies one occurrence. Repeated names denote multiple requirements there.
    */
  private def assertCallbackSites(
    source: String,
    group:  ProtocolUseConflict,
    names:  List[String]
  ): Unit =
    val expected = names.map(name => Some(source.indexOf(s"selected $name") + "selected ".length))
    assertEquals(group.requirements.toList.map(_.spanOpt.map(_.start.index)), expected)

  private def typeKey(tpe: Type, state: CompilerState): ProtocolTypeKey =
    ProtocolRegistry
      .typeKey(tpe, state.module.resolvables)
      .fold(error => fail(s"Expected concrete type identity, got $error"), identity)

  private def namedKey(name: String, state: CompilerState): ProtocolTypeKey =
    typeKey(typeReference(name, state.module.resolvables), state)

  private def protocolNamed(state: CompilerState, name: String): Protocol =
    state.module.members
      .collectFirst { case value: Protocol if value.name == name => value }
      .getOrElse(fail(s"Expected source $name protocol"))

  private def excerpt(source: String, origin: SourceOrigin): String =
    val span = origin.spanOpt.getOrElse(fail("Expected written requirement span"))
    source.substring(span.start.index, span.end.index)
