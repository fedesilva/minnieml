package mml.mmlclib.errors

import mml.mmlclib.ast.{SourceOrigin, SrcSpan}
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.semantic.SemanticError
import mml.mmlclib.test.BaseEffFunSuite

class DiagnosticStoreTests extends BaseEffFunSuite:

  private def failure(message: String): CompilationError =
    SemanticError.InvalidEntryPoint(message, SourceOrigin.Synth)

  test("diagnostic occurrences keep original payloads and emission order") {
    val source                  = SourceInfo("bad")
    val origin                  = SourceOrigin.Loc(SrcSpan(source.pointAt(0), source.pointAt(3)))
    val firstError              = SemanticError.InvalidEntryPoint("Same message and source", origin)
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
