package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.errors.{DiagnosticId, DiagnosticRecord, DiagnosticRelation}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class OwnershipDiagnosticCauseTests extends BaseEffFunSuite:

  private def compile(source: String): IO[CompilerState] =
    FrontEndApi.compile(source, "OwnershipDiagnosticCauseTests").value.map {
      case Right(state) => state
      case Left(error) => fail(s"Frontend failed before ownership: $error")
    }

  private def records[A](state: CompilerState)(
    select: PartialFunction[SemanticError, A]
  ): Vector[(DiagnosticRecord, A)] =
    state.diagnostics.records.flatMap { record =>
      record.error match
        case error: SemanticError => select.lift(error).map(record -> _)
        case _ => None
    }

  private def invalidExpressions(module: Module): List[InvalidExpression] =
    module.members.collect { case binding: Bnd =>
      TermTraversal.collect(binding.value) { case error: InvalidExpression => error }
    }.flatten

  private def assertRootErrors(
    state:    CompilerState,
    record:   DiagnosticRecord,
    expected: Vector[DiagnosticId]
  ): Unit =
    assertEquals(record.relation, DiagnosticRelation.Consequence(expected))
    expected.foreach { id =>
      val root = state.diagnostics.lookup(id).getOrElse(fail("Missing primary source diagnostic"))
      assertEquals(root.relation, DiagnosticRelation.Primary)
      assert(state.visibleErrors.contains(root.error))
    }
    assert(!state.visibleErrors.contains(record.error))
    assert(state.errors.contains(record.error))

  for independent <- List(false, true) do
    test(s"borrowed capture consequences retain the return failure, independent=$independent") {
      val capture =
        if independent then "fn capture(f: Int -> Int -> Int): Int = let g = ~{ f 1 2; }; g ();;"
        else ""
      val result = if independent then "g () + capture (pick2 c)" else "g ()"
      val source = s"""
        fn main(): Int =
          let seed = int_to_str 12345;
          let c = { a: Int, b: Int -> a + b + seed.length; };
          let f = pick2 c;
          let g = ~{ f 10 2; };
          $result;
        ;
        fn pick2(c: Int -> Int -> Int): Int -> Int -> Int = c;;
        $capture
      """
      compile(source).map { state =>
        val returns = records(state) { case error: SemanticError.BorrowEscapeViaReturn => error }
        assertEquals(returns.size, 1, state.errors)
        val (root, error) = returns.head
        assertEquals(error.ref.spanOpt.map(_.start.index), Some(source.indexOf("c;;")))
        assertEquals(root.relation, DiagnosticRelation.Primary)
        val captures = records(state) { case error: SemanticError.CapturedBorrowedHeapBinding =>
          error
        }
        val dependent = captures.filter(_._1.relation != DiagnosticRelation.Primary)
        assert(dependent.nonEmpty, state.errors)
        dependent.foreach { (record, _) => assertRootErrors(state, record, Vector(root.id)) }
        val visible = captures.filter(_._1.relation == DiagnosticRelation.Primary)
        assertEquals(visible.size, if independent then 1 else 0, state.errors)
        if independent then
          assertEquals(
            visible.head._2.ref.spanOpt.map(_.start.index),
            Some(source.indexOf("f 1 2"))
          )
      }
    }

  test("a captured initializer with a rejected and an unknown alternative remains primary") {
    val source = """
      fn pick2(c: Int -> Int -> Int): Int -> Int -> Int = c;;
      fn example(flag: Bool, borrowed: Int -> Int -> Int): Int =
        let selected = if flag then pick2 borrowed; else borrowed;;
        let capture = ~{ selected 1 2; };
        capture ();
      ;
    """
    compile(source).map { state =>
      val returns = records(state) { case error: SemanticError.BorrowEscapeViaReturn => error }
      val captures = records(state) { case error: SemanticError.CapturedBorrowedHeapBinding =>
        error
      }
      assertEquals(returns.size, 1, state.errors)
      assertEquals(captures.size, 1, state.errors)
      val (returned, returnError)  = returns.head
      val (captured, captureError) = captures.head
      assertEquals(returnError.ref.resolvedId, Some(resolvedBindingId(state.module, "pick2", "c")))
      assertEquals(returnError.ref.spanOpt.map(_.start.index), Some(source.indexOf("c;;")))
      assertEquals(
        captureError.ref.resolvedId,
        Some(resolvedBindingId(state.module, "example", "selected"))
      )
      assertEquals(
        captureError.ref.spanOpt.map(_.start.index),
        Some(source.indexOf("selected 1 2"))
      )
      assertEquals(returned.relation, DiagnosticRelation.Primary)
      assertEquals(captured.relation, DiagnosticRelation.Primary)
      assertEquals(state.errors, Vector(returned.error, captured.error))
      assertEquals(state.visibleErrors, state.errors)
    }
  }

  test("a reported invalid capture explains its downstream use without deleting it") {
    val source = """
      fn length(value: String): Int = value.length;;
      fn consume(~value: String): Unit = println value;;
      fn example(): Int =
        let s = int_to_str 42;
        consume s;
        let read = { length s; };
        read ();
      ;
    """
    compile(source).map { state =>
      val moves      = records(state) { case error: SemanticError.UseAfterMove => error }
      val primary    = moves.filter(_._1.relation == DiagnosticRelation.Primary)
      val downstream = moves.filter(_._1.relation != DiagnosticRelation.Primary)
      assertEquals(primary.size, 1, state.errors)
      assertEquals(downstream.size, 1, state.errors)
      val (root, original) = primary.head
      assertEquals(original.ownerId, Some(resolvedBindingId(state.module, "example", "s")))
      assertEquals(
        original.source.spanOpt.map(_.start.index),
        Some(source.indexOf("length s") + "length ".length)
      )
      downstream.foreach { (record, error) =>
        assertEquals(error.ownerId, original.ownerId)
        assertEquals(error.movedAt, original.movedAt)
        assertRootErrors(state, record, Vector(root.id))
      }
    }
  }

  test("independent owners at the same use retain separate visible primary diagnostics") {
    val source = """
      fn take(~text: String): Int = text.length;;
      fn example(): Int =
        let first = int_to_str 1;
        let second = int_to_str 2;
        let read = { first.length + second.length; };
        let a = take first;
        let b = take second;
        read ();
      ;
    """
    compile(source).map { state =>
      val moves = records(state) { case error: SemanticError.UseAfterMove => error }
      assertEquals(moves.size, 2, state.errors)
      assertEquals(moves.map(_._1.id).distinct.size, 2)
      assert(moves.forall(_._1.relation == DiagnosticRelation.Primary))
      assertEquals(moves.map(_._2.source).distinct.size, 1)
      assertEquals(
        moves.flatMap(_._2.ownerId).toSet,
        Set(
          resolvedBindingId(state.module, "example", "first"),
          resolvedBindingId(state.module, "example", "second")
        )
      )
      moves.foreach { (record, _) => assert(state.visibleErrors.contains(record.error)) }
    }
  }

  test("an independent use after move inside an invalid operand remains visible") {
    val source = """
      fn take(~text: String, extra: Int): Int = text.length + extra;;
      fn make(~text: String): Int -> Int = take text;;
      fn example(): Unit =
        let pap = make (int_to_str 123);
        let first = pap 1;
        pap 1;
        ();
      ;
    """
    compile(source).map { state =>
      val moves = records(state) { case error: SemanticError.UseAfterMove => error }
      val mismatches = records(state) {
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
      }
      assertEquals(moves.size, 1, state.errors)
      assert(mismatches.nonEmpty, state.errors)
      assertEquals(moves.head._1.relation, DiagnosticRelation.Primary)
      assert(state.visibleErrors.contains(moves.head._1.error))
      val invalid = invalidExpressions(state.module)
      assert(invalid.exists { error =>
        TermTraversal
          .collect(error.originalExpr) { case ref: Ref => ref.resolvedId }
          .contains(moves.head._2.ref.resolvedId)
      })
    }
  }

  test("a failed lambda result does not move captures out of its enclosing scope") {
    val source = """
      fn take(~text: String): Int = text.length;;
      fn example(): Int =
        let text = int_to_str 123;
        let broken = ~{ a: Int ->
          let observed = text.length;
          take true;
        };
        take text;
      ;
    """
    compile(source).map { state =>
      val mismatches = records(state) {
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
      }
      assertEquals(mismatches.size, 1, state.errors)
      assertEquals(state.visibleErrors, mismatches.map(_._1.error))
      assertEquals(records(state) { case error: SemanticError.UseAfterMove => error }, Vector.empty)
      val invalid = invalidExpressions(state.module)
      assert(invalid.nonEmpty)
    }
  }

  test("a valid continuation still moves its owned value after a failed initializer") {
    val source = """
      fn take(~text: String): Int = text.length;;
      fn example(): Int =
        let broken = take true;
        let text = int_to_str 123;
        let first = take text;
        text.length;
      ;
    """
    compile(source).map { state =>
      val mismatches = records(state) {
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) => error
      }
      val moves    = records(state) { case error: SemanticError.UseAfterMove => error }
      val lastUses = records(state) { case error: SemanticError.ConsumingParamNotLastUse => error }
      assertEquals(mismatches.size, 1, state.errors)
      assertEquals(moves.size, 1, state.errors)
      assertEquals(lastUses.size, 1, state.errors)
      val (record, error) = moves.head
      assertEquals(record.relation, DiagnosticRelation.Primary)
      assertEquals(error.ownerId, Some(resolvedBindingId(state.module, "example", "text")))
      assertEquals(error.source.spanOpt.map(_.start.index), Some(source.lastIndexOf("text.length")))
      assertEquals(lastUses.head._1.relation, DiagnosticRelation.Primary)
      assertEquals(lastUses.head._2.ref.resolvedId, error.ownerId)
      assertEquals(
        lastUses.head._2.ref.spanOpt.map(_.start.index),
        Some(source.indexOf("take text;") + "take ".length)
      )
      assertEquals(
        state.visibleErrors,
        mismatches.map(_._1.error) ++ moves.map(_._1.error) ++ lastUses.map(_._1.error)
      )
    }
  }

  private def beforeOwnership: IO[CompilerState] =
    val source = "fn example(~text: String): Int = text.length;;"
    parseNotFailed(source).map { parsed =>
      val module  = injectCommonFunctions(injectStandardOperators(injectBasicTypes(parsed)))
      val initial = CompilerState.empty(module, SourceInfo(source), CompilerConfig.default)
      val phases = phasesBeforeTypeChecking ++ List(
        TypeChecker.rewriteModule,
        PartialApplicationElaborator.rewriteModule,
        ClosureMemoryFnGenerator.rewriteModule,
        StructDestructorBodyGenerator.rewriteModule
      )
      phases.foldLeft(initial) { (state, phase) =>
        val next = ResolvablesIndexer.rewriteModule(phase(state))
        assertEquals(next.errors, Vector.empty)
        next
      }
    }

  private def replaceBody(state: CompilerState)(transform: Expr => Expr): CompilerState =
    val members = state.module.members.map {
      case binding: Bnd if binding.name == "example" =>
        val terms = binding.value.terms.map {
          case lambda: Lambda => lambda.copy(body = transform(lambda.body))
          case other => other
        }
        binding.copy(value = binding.value.copy(terms = terms))
      case other => other
    }
    ResolvablesIndexer.rewriteModule(state.withModule(state.module.copy(members = members)))

  private def shapeRecords(state: CompilerState): Vector[(DiagnosticRecord, SemanticError)] =
    records(state) {
      case error: SemanticError.InvalidExpression
          if error.phase == "ownership-analyzer" &&
            error.msg == "Ownership analysis requires a single-term expression" =>
        error
    }

  for evidence <- List("known", "missing", "unknown") do
    test(s"only the invalid payload's own proven roots explain its shape failure: $evidence") {
      beforeOwnership.map { initial =>
        val prior = SemanticError.InvalidEntryPoint("Original frontend failure", SourceOrigin.Synth)
        val (diagnostics, roots) = initial.diagnostics.emit(prior)
        val ownRoots = evidence match
          case "known" => roots
          case "missing" => Vector.empty
          case "unknown" => Vector(DiagnosticId(diagnostics.nextId + 100))
          case other => fail(s"Unknown fixture $other")
        val input = replaceBody(initial.copy(diagnostics = diagnostics)) { original =>
          val nested    = InvalidExpression(original.source, original, causes = roots)
          val malformed = original.copy(terms = nested :: original.terms)
          original.copy(terms =
            List(
              InvalidExpression(original.source, malformed, causes = ownRoots)
            )
          )
        }
        val state  = OwnershipAnalyzer.rewriteModule(input)
        val errors = shapeRecords(state)
        assertEquals(errors.size, 1, state.errors)
        val record = errors.head._1
        if evidence == "known" then assertRootErrors(state, record, roots)
        else
          assertEquals(record.relation, DiagnosticRelation.Primary)
          assert(state.visibleErrors.contains(record.error))
      }
    }

  test("a known outer failure does not hide an independent nested shape error") {
    beforeOwnership.map { initial =>
      val prior = SemanticError.InvalidEntryPoint("Outer frontend failure", SourceOrigin.Synth)
      val (diagnostics, roots) = initial.diagnostics.emit(prior)
      val input = replaceBody(initial.copy(diagnostics = diagnostics)) { original =>
        val malformed = original.copy(terms = original.terms ++ original.terms)
        val nested    = original.copy(terms = List(TermGroup(original.source, malformed)))
        original.copy(terms = List(InvalidExpression(original.source, nested, causes = roots)))
      }
      val state  = OwnershipAnalyzer.rewriteModule(input)
      val errors = shapeRecords(state)
      assertEquals(errors.size, 1, state.errors)
      assertEquals(errors.head._1.relation, DiagnosticRelation.Primary)
      assert(state.visibleErrors.contains(errors.head._1.error))
    }
  }
