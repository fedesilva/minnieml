package mml.mmlclib.errors

import mml.mmlclib.ast.{Ref, SourceOrigin, SrcSpan}
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.{SemanticError, TypeError, UnresolvableTypeContext}
import mml.mmlclib.test.BaseEffFunSuite

class DiagnosticStoreTests extends BaseEffFunSuite:

  private case class FixtureError(subject: String, source: SourceOrigin = SourceOrigin.Synth)
      extends CompilationError:
    def message: String = "Diagnostic store fixture"

  private def failure(subject: String): CompilationError = FixtureError(subject)

  private val locations = SourceInfo("broken    use     another")

  private def origin(start: Int, end: Int): SourceOrigin =
    SourceOrigin.Loc(SrcSpan(locations.pointAt(start), locations.pointAt(end)))

  private def specific(name: String, source: SourceOrigin): CompilationError =
    SemanticError.TypeCheckingError(TypeError.UntypedHoleInBinding(name, source, "fixture"))

  private def unresolved(source: SourceOrigin, phase: String = "fixture"): CompilationError =
    SemanticError.TypeCheckingError(
      TypeError.UnresolvableType(
        Ref(source, "value"),
        Some(UnresolvableTypeContext.NamedValue("value")),
        phase
      )
    )

  test("distinct written uses remain visible while repeated consequences at one use coalesce") {
    val root           = specific("broken", origin(0, 6))
    val firstUse       = unresolved(origin(10, 13))
    val laterUse       = unresolved(origin(17, 24))
    val (first, roots) = DiagnosticStore().emit(root)
    val (second, _)    = first.emit(firstUse, roots)
    val (third, _)     = second.emit(unresolved(origin(10, 13), "later phase"), roots)
    val (last, _)      = third.emit(laterUse, roots)

    assertEquals(last.errors.size, 4)
    assertEquals(last.visibleErrors, Vector(root, firstUse, laterUse))
    assert(last.records.tail.forall(_.causes == roots))
  }

  test("a specific failure covers its own generic fallback without hiding an independent cause") {
    val local                = specific("local", origin(10, 17))
    val elsewhere            = specific("elsewhere", origin(0, 6))
    val unknown              = unresolved(origin(10, 13))
    val (first, localRoots)  = DiagnosticStore().emit(local)
    val (second, otherRoots) = first.emit(elsewhere)
    val (third, _)           = second.emit(unknown, localRoots)
    val (last, _)            = third.emit(unknown, otherRoots)

    assertEquals(last.visibleErrors, Vector(local, elsewhere, unknown))
    assertEquals(last.visibleRecords.last.causes, otherRoots)
    assertEquals(last.records.size, 4)
  }

  test("a smaller use covers its enclosing generic fallback regardless of emission order") {
    val root           = specific("broken", origin(0, 6))
    val enclosing      = unresolved(origin(10, 24))
    val use            = unresolved(origin(10, 13))
    val (first, roots) = DiagnosticStore().emit(root)
    val (second, _)    = first.emit(enclosing, roots)
    val (last, _)      = second.emit(use, roots)

    assertEquals(last.visibleErrors, Vector(root, use))
    assertEquals(last.errors, Vector(root, enclosing, use))
  }

  test("derived recovery locations do not replace a written use at the same span") {
    val root           = specific("broken", origin(0, 6))
    val written        = unresolved(origin(10, 13))
    val recovery       = unresolved(origin(10, 13).derived)
    val (first, roots) = DiagnosticStore().emit(root)
    val (second, _)    = first.emit(recovery, roots)
    val (last, _)      = second.emit(written, roots)

    assertEquals(last.visibleErrors, Vector(root, written))
    assertEquals(last.errors, Vector(root, recovery, written))
  }

  test("a repeated primary message is covered only when it carries that same cause") {
    val error               = specific("broken", origin(0, 6))
    val (first, roots)      = DiagnosticStore().emit(error)
    val (second, _)         = first.emit(error, roots)
    val (last, independent) = second.emit(error)

    assertEquals(last.visibleRecords.map(_.id), roots ++ independent)
    assertEquals(last.errors, Vector(error, error, error))
  }

  test("diagnostic occurrences keep original payloads and emission order") {
    val source                  = SourceInfo("bad")
    val origin                  = SourceOrigin.Loc(SrcSpan(source.pointAt(0), source.pointAt(3)))
    val firstError              = FixtureError("Repeated failure", origin)
    val secondError             = failure("Independent failure")
    val (first, firstRoots)     = DiagnosticStore().emit(firstError)
    val (second, repeatedRoots) = first.emit(firstError)
    val (third, lastRoots)      = second.emit(secondError)

    assertEquals(third.errors, Vector(firstError, firstError, secondError))
    assertEquals(third.records.map(_.id), firstRoots ++ repeatedRoots ++ lastRoots)
    assertEquals(third.records.map(_.id).distinct.size, 3)
    assert(third.records.forall(_.relation == DiagnosticRelation.Primary))
    assertEquals(third.visibleErrors, third.errors)
    assertEquals(third.lookup(firstRoots.head).map(_.error), Some(firstError))
    assertEquals(third.lookup(repeatedRoots.head).map(_.error), Some(firstError))
  }

  test("consequence roots flatten and deduplicate in original diagnostic order") {
    val firstError            = failure("First root")
    val secondError           = failure("Second root")
    val consequence           = failure("Depends on both roots")
    val later                 = failure("Depends on an already attributed result")
    val (first, firstRoots)   = DiagnosticStore().emit(firstError)
    val (second, secondRoots) = first.emit(secondError)
    val (third, combined)     = second.emit(consequence, secondRoots ++ firstRoots ++ secondRoots)
    val (fourth, flattened)   = third.emit(later, Vector(third.records.last.id) ++ firstRoots)
    val expected              = firstRoots ++ secondRoots

    assertEquals(combined, expected)
    assertEquals(flattened, expected)
    assertEquals(third.records.last.relation, DiagnosticRelation.Consequence(expected))
    assertEquals(fourth.records.last.relation, DiagnosticRelation.Consequence(expected))
    assertEquals(fourth.errors, Vector(firstError, secondError, consequence, later))
    assertEquals(fourth.visibleErrors, Vector(firstError, secondError))
    assertEquals(fourth.roots(Vector(fourth.records.last.id)), Some(expected))
  }

  test("compiler state exposes raw diagnostics separately from presentation") {
    parseNotFailed("let value = 1;").map { module =>
      val primary          = failure("Original source failure")
      val consequence      = failure("Proven consequence")
      val (first, roots)   = DiagnosticStore().emit(primary)
      val (diagnostics, _) = first.emit(consequence, roots)
      val state = CompilerState
        .empty(module, SourceInfo("let value = 1;"), CompilerConfig.default)
        .copy(diagnostics = diagnostics)

      assertEquals(state.errors, Vector(primary, consequence))
      assertEquals(state.visibleErrors, Vector(primary))
    }
  }

  test("unknown causal evidence leaves the new diagnostic primary and visible") {
    val primary               = failure("Original source failure")
    val independent           = failure("Incomplete causal evidence")
    val (first, roots)        = DiagnosticStore().emit(primary)
    val unknown               = DiagnosticId(99L)
    val (second, resultRoots) = first.emit(independent, roots :+ unknown)

    assertEquals(first.roots(roots :+ unknown), None)
    assertEquals(second.records.last.relation, DiagnosticRelation.Primary)
    assertEquals(resultRoots, Vector(second.records.last.id))
    assertEquals(second.visibleErrors, Vector(primary, independent))
  }

  for malformed <- List("empty", "unknown", "consequence", "self") do
    test(s"malformed $malformed root relation remains visible") {
      val primary          = failure("Original source failure")
      val validConsequence = failure("Attributed consequence")
      val malformedError   = failure("Unproven relation")
      val (first, roots)   = DiagnosticStore().emit(primary)
      val (second, _)      = first.emit(validConsequence, roots)
      val id               = DiagnosticId(second.nextId)
      val invalidRoots = malformed match
        case "empty" => Vector.empty
        case "unknown" => roots :+ DiagnosticId(99L)
        case "consequence" => Vector(second.records.last.id)
        case "self" => Vector(id)
        case other => fail(s"Unknown fixture $other")
      val record = DiagnosticRecord(
        id,
        malformedError,
        DiagnosticRelation.Consequence(invalidRoots)
      )
      val malformedStore = second.copy(
        records = second.records :+ record,
        nextId  = second.nextId + 1
      )

      assertEquals(malformedStore.visibleErrors, Vector(primary, malformedError))
      assertEquals(malformedStore.errors, Vector(primary, validConsequence, malformedError))
      assertEquals(malformedStore.roots(Vector(id)), None)
    }
