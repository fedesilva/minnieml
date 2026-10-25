package mml.mmlclib.semantic

import cats.effect.IO
import mml.mmlclib.api.FrontEndApi
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.{CompilerConfig, CompilerState}
import mml.mmlclib.errors.{DiagnosticRecord, DiagnosticRelation}
import mml.mmlclib.parser.SourceInfo
import mml.mmlclib.test.BaseEffFunSuite

class TypeCheckerCauseTests extends BaseEffFunSuite:

  private def checkedState(source: String): IO[CompilerState] =
    FrontEndApi.compile(source, "TypeCheckerCauses").value.map {
      case Right(state) => state
      case Left(error) => fail(error.message)
    }

  private def binding(module: Module, name: String): Bnd =
    module.members
      .collectFirst { case value: Bnd if value.name == name => value }
      .getOrElse(fail(s"Expected fixture binding $name"))

  private def mismatch(record: DiagnosticRecord): Boolean = record.error match
    case SemanticError.TypeCheckingError(_: TypeError.TypeMismatch) => true
    case _ => false

  private def invalidNodes(value: Expr): List[InvalidExpression] =
    TermTraversal.collect(value) { case invalid: InvalidExpression => invalid }

  test("an invalid application keeps its result type and original primary error through aliases"):
    checkedState("""
      fn identity(value: Int): Int = value;;
      let broken = identity true;
      let alias = broken;
      let downstream = identity alias;
    """).map { state =>
      val visible = state.diagnostics.visibleRecords
      assertEquals(visible.size, 1, state.errors)
      assert(mismatch(visible.head), state.errors)
      val root         = visible.head.id
      val broken       = binding(state.module, "broken")
      val alias        = binding(state.module, "alias")
      val downstream   = binding(state.module, "downstream")
      val causes       = DiagnosticCauses.fromModule(state.module)
      val availability = ValueAvailability.fromModule(state.module)
      List(broken, alias, downstream).foreach { value =>
        assertEquals(causes.knownRootsOf(value.value), Vector(root))
        assert(!availability.isAvailable(value.value))
        assert(value.typeSpec.isDefined)
      }
      assert(invalidNodes(broken.value).exists { invalid =>
        invalid.causes.contains(root) && invalid.typeSpec == broken.typeSpec &&
        TermTraversal.collect(invalid.originalExpr) { case app: App => app }.nonEmpty
      })
      val consequences = state.diagnostics.records.filter(_.relation != DiagnosticRelation.Primary)
      assert(consequences.nonEmpty)
      assert(consequences.forall(_.causes == Vector(root)))
      assert(state.hasErrors)
    }

  test("discarding a failed initializer leaves an independent continuation available"):
    checkedState("""
      fn identity(value: Int): Int = value;;
      let continued = let broken = identity true; 42;
    """).map { state =>
      assertEquals(state.diagnostics.visibleRecords.count(mismatch), 1, state.errors)
      assertEquals(state.visibleErrors.size, 1, state.errors)
      val continued = binding(state.module, "continued")
      assert(invalidNodes(continued.value).nonEmpty)
      assertEquals(
        DiagnosticCauses.fromModule(state.module).knownRootsOf(continued.value),
        Vector.empty
      )
      assert(ValueAvailability.fromModule(state.module).isAvailable(continued.value))
    }

  test("an erroneous predicate cannot hide an independent branch mismatch"):
    checkedState("let result = if 1 then 2; else false; ;").map { state =>
      val visible = state.diagnostics.visibleRecords
      assertEquals(visible.count(mismatch), 1, state.errors)
      val branchErrors = visible.filter(_.error match
        case SemanticError.TypeCheckingError(_: TypeError.ConditionalBranchTypeMismatch) => true
        case _ => false)
      assertEquals(branchErrors.size, 1, state.errors)
      assertEquals(visible.size, 2, state.errors)
      val roots = DiagnosticCauses
        .fromModule(state.module)
        .knownRootsOf(binding(state.module, "result").value)
      assertEquals(roots.toSet, visible.map(_.id).toSet)
    }

  test("two independently failed branch results retain both original mismatch roots"):
    checkedState("""
      fn identity(value: Int): Int = value;;
      let result = if true then identity false; else identity "bad"; ;
    """).map { state =>
      val primaries = state.diagnostics.visibleRecords
      assertEquals(primaries.size, 2, state.errors)
      assert(primaries.forall(mismatch), state.errors)
      val roots = DiagnosticCauses
        .fromModule(state.module)
        .knownRootsOf(binding(state.module, "result").value)
      assertEquals(roots.toSet, primaries.map(_.id).toSet)
      val branchConsequences = state.diagnostics.records.filter(_.error match
        case SemanticError.TypeCheckingError(_: TypeError.ConditionalBranchTypeUnknown) => true
        case _ => false)
      assertEquals(branchConsequences.size, 1)
      assertEquals(branchConsequences.head.causes.toSet, primaries.map(_.id).toSet)
    }

  test("contextual branch inference discards provisional hole diagnostics"):
    checkedState("let result = if true then 1; else ???; ;").map { state =>
      assertEquals(state.errors, Vector.empty)
      assertEquals(state.diagnostics.records, Vector.empty)
      assert(
        ValueAvailability
          .fromModule(state.module)
          .isAvailable(binding(state.module, "result").value)
      )
    }

  test("valid annotated recursion remains usable while its body is checked"):
    semNotFailed("""
      fn descend(value: Int): Int =
        if value == 0 then 0; else descend (value - 1);;
      ;
      let result = descend 3;
    """).map { module =>
      assert(ValueAvailability.fromModule(module).isAvailable(binding(module, "result").value))
    }

  List(
    "self" -> "type Recursive = Recursive -> Int;",
    "mutual" -> "type Recursive = (Nested -> Int); type Nested = Recursive;"
  ).foreach { (label, aliases) =>
    test(s"failed-type inspection terminates for $label structural aliases"):
      val source = s"""
        $aliases
        fn ignore(value: Recursive): Int = 1;;
        let kept = ignore;
      """

      parseNotFailed(source).map { parsed =>
        val initial = CompilerState.empty(
          injectBasicTypes(parsed),
          SourceInfo(source),
          CompilerConfig.default
        )
        val phases: List[CompilerState => CompilerState] = List(
          IdAssigner.rewriteModule,
          TypeResolver.rewriteModule,
          RefResolver.rewriteModule,
          TypeChecker.rewriteModule
        )
        val checked = phases.foldLeft(initial) { (state, phase) =>
          ResolvablesIndexer.rewriteModule(phase(state))
        }
        assertEquals(checked.errors, Vector.empty)
        val original   = binding(checked.module, "ignore")
        val kept       = binding(checked.module, "kept")
        val references = TermTraversal.collect(kept.value) { case ref: Ref => ref }
        assertEquals(references.map(_.resolvedId), List(original.id))
        assertEquals(kept.typeSpec, original.typeSpec)
        assert(invalidNodes(kept.value).isEmpty)
        assertEquals(
          DiagnosticCauses.fromModule(checked.module).knownRootsOf(kept.value),
          Vector.empty
        )
        assert(ValueAvailability.fromModule(checked.module).isAvailable(kept.value))
      }
  }

  test("an undefined callee retains an independent argument mismatch"):
    checkedState("""
      fn identity(value: Int): Int = value;;
      let result = missing (identity true);
    """).map { state =>
      val visible = state.diagnostics.visibleRecords
      assertEquals(visible.count(_.error.isInstanceOf[SemanticError.UndefinedRef]), 1, state.errors)
      assertEquals(visible.count(mismatch), 1, state.errors)
      assertEquals(visible.size, 2, state.errors)
      val result = binding(state.module, "result")
      val roots  = DiagnosticCauses.fromModule(state.module).knownRootsOf(result.value)
      assertEquals(roots.toSet, visible.map(_.id).toSet)
      assert(invalidNodes(result.value).exists { invalid =>
        TermTraversal
          .collect(invalid.originalExpr) { case literal: LiteralBool =>
            literal
          }
          .nonEmpty
      })
    }

  List(
    ("complete call", "x: Int", "Int"),
    ("partial call", "x: Int, y: Int", "Int -> Int")
  ).foreach { (label, parameters, resultType) =>
    test(s"a failed body retains its independent argument constraint for a $label"):
      checkedState(s"""
        fn target($parameters): Int = missing;;
        fn caller(): $resultType = target "wrong";;
      """).map { state =>
        val visible    = state.diagnostics.visibleRecords
        val undefined  = visible.filter(_.error.isInstanceOf[SemanticError.UndefinedRef])
        val mismatches = visible.filter(mismatch)
        assertEquals(undefined.size, 1, state.errors.map(_.message))
        assertEquals(mismatches.size, 1, state.errors.map(_.message))
        assertEquals(visible.size, 2, state.errors.map(_.message))
        mismatches.head.error match
          case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) =>
            error.node match
              case argument: Expr =>
                assert(
                  TermTraversal
                    .collect(argument) { case literal: LiteralString =>
                      literal
                    }
                    .nonEmpty
                )
              case other => fail(s"Expected the mismatched argument expression, found $other")
          case other => fail(s"Expected the argument mismatch, found $other")
        val caller = binding(state.module, "caller")
        assertEquals(
          DiagnosticCauses.fromModule(state.module).knownRootsOf(caller.value).toSet,
          visible.map(_.id).toSet
        )
        assert(!ValueAvailability.fromModule(state.module).isAvailable(caller.value))
        assert(invalidNodes(caller.value).exists(_.typeSpec.isDefined))
        assert(
          TermTraversal
            .collect(caller.value) {
              case lambda: Lambda if lambda.meta.exists(_.isPartialApplication) => lambda
            }
            .isEmpty
        )
        assert(state.diagnostics.records.exists { record =>
          record.causes == Vector(undefined.head.id) && (record.error match
            case SemanticError.TypeCheckingError(_: TypeError.UnresolvableType) => true
            case _ => false)
        })
      }
  }

  List("x", "x: Missing").foreach { parameter =>
    test(s"an unusable parameter signature $parameter cannot establish an argument mismatch"):
      checkedState(s"""
        fn target($parameter): Int = missing;;
        fn caller(): Int = target "wrong";;
      """).map { state =>
        val visible = state.diagnostics.visibleRecords
        assert(visible.exists(_.error.isInstanceOf[SemanticError.UndefinedRef]))
        assert(visible.exists(_.error match
          case _: SemanticError.UndefinedTypeRef => true
          case SemanticError.TypeCheckingError(_: TypeError.MissingParameterType) => true
          case _ => false))
        assert(!state.diagnostics.records.exists(mismatch), state.errors.map(_.message))
        val caller = binding(state.module, "caller")
        assert(!ValueAvailability.fromModule(state.module).isAvailable(caller.value))
      }
  }

  test("annotated forward callees are checked before callers rely on their result"):
    checkedState("""
      fn caller(): Bool = failed();;
      fn failed(): Int = true;;
    """).map { state =>
      val visible = state.diagnostics.visibleRecords
      val evidence =
        state.diagnostics.records.map(record => (record.id, record.error.message, record.relation))
      assertEquals(visible.size, 1, evidence)
      assert(mismatch(visible.head))
      val failed = binding(state.module, "failed")
      visible.head.error match
        case SemanticError.TypeCheckingError(error: TypeError.TypeMismatch) =>
          error.node match
            case owner: Bnd => assertEquals(owner.id, failed.id)
            case other => fail(s"Expected the callee binding, found $other")
        case other => fail(s"Expected the callee's original result mismatch: $other")
      val caller = binding(state.module, "caller")
      assertEquals(
        DiagnosticCauses.fromModule(state.module).knownRootsOf(caller.value),
        Vector(visible.head.id)
      )
    }

  test("branch rechecking preserves only roots emitted by the surviving traversal"):
    checkedState("""
      fn identity(value: Int): Int = value;;
      let result = if true then
        let discarded = identity false;
        1;
      else ???; ;
    """).map { state =>
      val visible = state.diagnostics.visibleRecords
      assertEquals(visible.size, 1, state.errors.map(_.message))
      assert(mismatch(visible.head))
      assert(!state.errors.exists {
        case SemanticError.TypeCheckingError(_: TypeError.UntypedHoleInBinding) => true
        case _ => false
      })
      val result   = binding(state.module, "result")
      val retained = invalidNodes(result.value).flatMap(_.causes)
      assert(retained.nonEmpty)
      assertEquals(retained.toSet, visible.map(_.id).toSet)
      assert(
        retained.forall(id =>
          state.diagnostics
            .lookup(id)
            .exists(_.relation == DiagnosticRelation.Primary)
        )
      )
      assertEquals(
        DiagnosticCauses.fromModule(state.module).knownRootsOf(result.value),
        Vector.empty
      )
    }
