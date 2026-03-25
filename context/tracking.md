# MML Task Tracking

## CRITICAL Rules

------------------------------------------------------------------------
/!\ /!\ Do not edit without explicit approval or direct command. /!\ /!\
/!\ /!\ Follow rules below strictly                              /!\ /!\
/!\ /!\ COMPLETING A TASK? -> ADD [COMPLETE] TAG. NEVER DELETE.  /!\ /!\
------------------------------------------------------------------------

* *Always read* `context/task-tracking-rules.md` 
  *before* working with this file - even if you read it before, 
  it might have changed in the meantime.

* Follow the rules stated above.
* These rules are mandatory unless the Author explicitly overrides them.

## Active Tasks

### #188 Literal lambdas and captures

* there are issues to address:
  `context/specs/lambdas-work-review.md`

- GitHub: https://github.com/fedesilva/minnieml/issues/188
- Reference: `docs/brainstorming/language/lambda-syntax-design.md`
- Phase 1 — Parser [COMPLETE]
- Phase 2 — Codegen (non-capturing) [COMPLETE]
- Phase 3 — Closures: capturing lambdas + ownership
  - Spec: `context/specs/lambda-step3-closures.md`
  - [x] 3.0 — Update tracking with Phase 3 subtasks
  - [x] 3.1 — CaptureAnalyzer semantic phase
  - [x] 3.2 — Fat pointer calling convention (`{ ptr fn, ptr env }`)
  - [x] 3.3 — Env struct codegen (value-type captures)
  - [ ] 3.4 — Ownership integration (env as owned value) — spec: `context/specs/lambda-step3-ownership.md`
    - [x] 3.4.0 — Stdlib: RawPtr type + mml_free_raw C function
    - [x] 3.4.1 — ClosureMemoryGenerator phase (env TypeStruct + free fn)
    - [x] 3.4.2 — OwnershipAnalyzer: termAllocates, isOwnedType, mkFreeCall for TypeFn
    - [x] 3.4.3 — Codegen: extractEnvPtr from fat pointer for free calls
    - [x] 3.4.4 — TypeChecker: propagate typeSpec to Lambda.captures (pre-existing bug fix)
    - [x] 3.4.5 — captures.mml: compiles, runs, 0 leaks
    - [x] 3.4.6 — Fix: split lambdaAllocates from termAllocates (only let-bound, not arg-position)
    - [x] 3.4.7 — Universal __free_closure with embedded dtor pointer in env struct field 0
    - [x] 3.4.8 — All tests pass: 333 unit, 18/18 mem (including closure-capture 0 leaks)
  - [ ] 3.4-QA — Codegen & ownership review fixes (spec: `context/specs/lambdas-work-review.md`)
    - [ ] 3.4-QA.1 [P1] Keep emitted env setup for let-bound capturing lambdas (`Applications.scala`)
    - [ ] 3.4-QA.2 [P1] Allocate unique symbols for let-bound lambda definitions (`Applications.scala`)
    - [x] 3.4-QA.3 [P1] Size closure env allocations using LLVM struct layout (`ExpressionCompiler.scala`)
    - [x] 3.4-QA.4 [P1] ClosureMemoryFnGenerator uses Lambda structural equality as map key (`ClosureMemoryFnGenerator.scala`)
    - [x] 3.4-QA.5 [P1] Non-deterministic UUIDs in ClosureMemoryFnGenerator (`ClosureMemoryFnGenerator.scala`)
    - [ ] 3.4-QA.6 [P1] Pass real closure env on recursive capturing lambdas (`Applications.scala`)
    - [ ] 3.4-QA.7 [P1] Keep capture support in tail-recursive lambda path (`ExpressionCompiler.scala`)
    - [x] 3.4-QA.8 [P1] Stop freeing non-capturing function values as closures (`OwnershipAnalyzer.scala`)
    - [x] 3.4-QA.9 [P2] Mutable var in compileCapturingLambda — replace with foldLeft (`ExpressionCompiler.scala`)
    - [ ] 3.4-QA.10 [P2] mergeSubState fragile manual field sync (`ExpressionCompiler.scala`)
    - [ ] 3.4-QA.11 [P2] Two codepaths for closure free could diverge (`Applications.scala`)
    - [ ] 3.4-QA.12 [P2] No guard against capturing heap types before phase 3.5 (`ClosureMemoryFnGenerator.scala`)
    - [ ] 3.4-QA.13 [P3] Nullary/Unit thunk unification may mask type errors (`TypeChecker.scala`)
    - [ ] 3.4-QA.14 [P3] TypeFn globally mapped to fat pointer may affect non-closure contexts (`codegen/emitter/package.scala`)
    - [ ] 3.4-QA.15 [P3] OwnershipAnalyzer 5-tuples should be a case class (`OwnershipAnalyzer.scala`)
    - [ ] 3.4-QA.16 [P3] Term.withTypeAsc silently ignores unknown term types (`ast/terms.scala`)
    - [ ] 3.4-QA.17 [P3] FORCE_INLINE on non-hot-path runtime functions (`mml_runtime.c`)
    - [x] 3.4-QA.18 [P3] sizeOfLlvmType ignores struct alignment/padding (`codegen/emitter/package.scala`)
    - [ ] 3.4-QA.19 [P2] Replace shape-coupled/name-coupled lambda semantic tests with semantic extractors (`CaptureAnalyzerTests.scala`, `TypeCheckerTests.scala`, `LambdaLitTests.scala`)
    - [ ] 3.4-QA.20 [P3] Remove new `TODO:QA` by extracting ownership test helpers or tracking them properly (`OwnershipAnalyzerTests.scala`)
    - [ ] 3.4-QA.21 [P2] Mutable traversals + early returns in ClosureMemoryFnGenerator — replace var/builder/return with folds and if-else (`ClosureMemoryFnGenerator.scala`)
    - [ ] 3.4-QA.22 [P2] asInstanceOf cast in ClosureMemoryFnGenerator.tagLambdas breaks no-exceptions rule (`ClosureMemoryFnGenerator.scala:312`)
    - [ ] 3.4-QA.23 [P3] Hardcoded string name matching for closure free dispatch (`Applications.scala:312,316`)
    - [ ] 3.4-QA.24 [P3] Inconsistent Cats syntax in new codegen code — use .asRight/.asLeft/.some/.none (`ExpressionCompiler.scala`, `Applications.scala`)
  - [ ] 3.5 — Heap-type captures (String, structs) + clone/free — spec: `context/specs/lambda-step3-ownership.md`
    - **URGENT**: all capturing lambdas with heap-type captures produce invalid IR — struct is copied
      into env but heap pointer is shared without clone/ownership transfer. Original owner frees the
      buffer, leaving closure with dangling pointer. TCO case works by accident (lifetime coincidence).
      Phase C should be revisited after 3.5 is fixed. See `context/specs/bad-id-nested-llambdas-multicapture.md`.
    - **Findings (2026-03-24, QA P1 analysis):**
      - OwnershipAnalyzer does NOT treat captures as moves. Captures are metadata-only (set by
        CaptureAnalyzer); the ownership pass inherits captured bindings as Owned but never marks
        them Moved in the outer scope.
      - Per-env free functions (`__free___closure_env_N`) only call `mml_free_raw` — they do not
        free heap fields inside the env struct. So heap buffers captured by value are never freed
        via the closure path; only the outer scope's `__free_String` frees them at exit.
      - Currently "works by accident": no double-free because env free doesn't touch heap fields,
        and the outer scope outlives all closures. But the shared `i8*` buffer is unsound if the
        outer binding were moved before the closure finishes.
      - Fix requires either: (a) treat heap-type captures as moves in the analyzer and error on
        re-capture without clone, or (b) auto-insert clones at capture sites and extend env free
        functions to free heap fields.


#### Nested lambda / N-level nesting workstream — IN PROGRESS

Addresses nested capturing lambdas producing malformed IR (undefined register for fat pointer).
Plan: `.claude/plans/piped-scribbling-parnas.md`

**Workstream A+B+C** (core codegen fixes):
  - [x] Phase A — QA.1: Keep call-site IR for capturing lambdas (`Applications.scala:63-67`)
    - Condition output reset on `res.isLiteral` — capturing lambdas preserve their malloc/GEP/store/insertvalue IR
    - Code change is in place (uncommitted)
  - [x] Phase B — QA.2: Unique symbols for let-bound lambda definitions (`Applications.scala:48-57`)
    - Counter-suffixed naming: `mangleName(name + “_” + nextAnonFnId)` → `@module_loop_0`
    - Code change is in place (uncommitted)
  - [ ] Phase C — QA.7: TCO + captures (full fix, NOT disable-TCO) — CODE IN PLACE, HAS BUGS
    - Recursion is the only looping construct — TCO is mandatory
    - **Done (uncommitted):**
      - Extracted `emitCallSiteEnv` helper from `compileCapturingLambda` (ExpressionCompiler.scala)
        - Resolves capture types, creates env type, emits malloc/store dtor/store captures/insertvalue fat pointer
        - Returns `EnvSetupResult(siteState, fpRegister, envTypeRef, captureTypes)`
      - Extracted `emitCaptureLoads` as shared package-level function (FunctionEmitter.scala)
        - GEP+load for each capture from env struct, returns `(CodeGenState, Map[String, ScopeEntry])`
      - Refactored `compileCapturingLambda` to use both helpers (behavior unchanged)
      - Updated `compileTailRecLambdaLiteral` to accept `functionScope` and dispatch:
        - `lambda.captures.nonEmpty` → `compileTailRecCapturingLambda` (calls `emitCallSiteEnv` + passes captureInfo)
        - else → `compileTailRecNonCapturing` (original behavior)
      - Updated `compileTailRecursiveLambda` (FunctionEmitter.scala):
        - New param `captureInfo: Option[(String, List[(Ref, String)])]`
        - Entry block: emits capture loads before `br label %loop.header`
        - `phiStart` adjusted: `paramCount + 1 + 2 * captureCount`
        - Capture scope merged into body scope
      - Fixed `extractBody` (FunctionEmitter.scala): OwnershipAnalyzer wraps tail calls as
        `let __ownership_result = self_call(); frees; __ownership_result`. Added `extractSelfCallFromAccumulated`
        to recognize this pattern — recursively extracts through the callExpr, reorders frees before back-edge.
    - **Verified:**
      - 336/336 unit tests pass
      - 18/18 ASan memory tests pass
      - Sanity samples (hello, quicksort, astar2) pass
      - `captures.mml` (non-TCO capturing lambda) passes
      - Benchmarks compile
      - `mmlc run -aI mml/samples/nested-lambdas-multicapture.mml` runs correctly with TCO
        (loops without stack overflow, captures work across iterations)
    - **Remaining before marking complete:**
      - Leaks-mode memory tests not yet run
      - Heap-type capture ownership is shared, not cloned — known limitation, tracked as Phase 3.5
        (see `context/specs/bad-id-nested-llambdas-multicapture.md`)
    - **Files changed:**
      - `ExpressionCompiler.scala`: `EnvSetupResult`, `emitCallSiteEnv`, `compileTailRecCapturingLambda`, `compileTailRecNonCapturing`, refactored `compileCapturingLambda`
      - `FunctionEmitter.scala`: `emitCaptureLoads`, `captureInfo` param on `compileTailRecursiveLambda`, `extractSelfCallFromAccumulated`, Ref case in `extractBody`
      - `Applications.scala`: pre-existing Phase A+B changes (unchanged this session)
    - BUGS also in: `context/specs/bad-id-nested-llambdas-multicapture.md`
      - IR GENERATED IS INVALID AND THE CLOSURES ARE NOT WORKING WELL WITH OWNERSHIP

**Deferred workstream D+E** (follow-up):
  - [ ] Phase D — QA.8: Null-guard in `__free_closure` for non-capturing function values
    - Emit `icmp eq ptr %env, null` + branch around dtor call in `emitClosureFreeViaEnvDtor`
  - [ ] Phase E — QA.6: Self-reference in env for recursive capturing lambdas
    - Backpatch pattern: add `{ ptr, ptr }` field at end of env struct for closure's own fat pointer
    - Thread `bindingParam` to `compileCapturingLambda` to detect self-referencing lambdas

**IR feedback notes** (not blocking, cleanup later):
  - Closure type naming inconsistent (`%closure_env_*` vs `%struct.__closure_env_*` + TBAA mismatch)
  - Destructor slot is untyped/raw (convention-based, fine for now)
  - Function pointer typing very loose (raw ptr called as function)



### Update language ref and memory model docs. [COMPLETE]

* lambdas are in, need to update.
* updates to the type checker (type annotations are not mandatory for lambdas but they might still be needed)
* see the changelog below and the git history.
* lang ref needs a link to the memory model doc.


### Update the design doc

* there are new phases in the semantic stage


### QA Test infra

* add comments to each test, describing in plain english what they do, what they expect, etc
* move all the newer extractors to the test extractors module (or new subppackage)
  - prefix them with TX
  - are we still using TXApp and where not why? and if we can use them.

### QA Parser

* Add commentary with examples to the parsers

## Recent Changes

- 2026-03-24: #188 3.4-QA P1 batch — QA.3, QA.4, QA.5, QA.8
  - QA.3: `sizeOfLlvmStruct` replaces naive sum with alignment-padded struct sizing for env malloc.
  - QA.4: `IdentityHashMap` for lambda→envStructName (reference equality, not structural).
  - QA.4: Refactored `collectCapturingLambdas` from mutable vars to pure fold.
  - QA.5: Removed UUID from `paramId`; counter-based env names already ensure uniqueness.
  - QA.8: Null guard in `emitClosureFreeViaEnvDtor` — non-capturing fns (null env) skip dtor.
  - QA.8: `exitBlock` on `CompileResult` so tail-rec PHI tracks through null-guard blocks.
  - Also closes QA.18 (same root cause as QA.3).
  - 336/336 tests, 18/18 mem tests, benchmarks compile.
- 2026-03-24: #188 Nested lambda workstream A+B+C — capturing lambdas + TCO
  - QA.1 (Phase A): Capturing lambdas preserve call-site IR; output reset conditioned on `isLiteral`.
  - QA.2 (Phase B): Counter-suffixed unique symbols for let-bound lambda definitions.
  - QA.7 (Phase C): TCO for capturing lambdas.
    - Extracted `emitCallSiteEnv` + `EnvSetupResult` (shared env setup for regular and tail-rec paths).
    - Extracted `emitCaptureLoads` as package-level function in FunctionEmitter.
    - `compileTailRecursiveLambda`: loads captures in entry block, adjusted phi start, merges capture scope.
    - `extractSelfCallFromAccumulated`: handles OwnershipAnalyzer `__ownership_result` wrapper in tail position.
  - 336/336 unit tests, 18/18 ASan mem tests pass. Leaks-mode pending.
  - Known limitation: heap-type captures share buffer pointer (Phase 3.5).
- 2026-03-22: #188 3.4-QA.9 — Replace mutable vars in compileCapturingLambda with foldLeft.
- 2026-03-22: Update language reference and memory model docs for lambdas/closures.
- 2026-03-22: #188 Phase 3.4 — closure ownership integration
  - ClosureMemoryFnGenerator: synthesizes env TypeStruct (with embedded dtor ptr at field 0) + per-env free functions + universal `__free_closure`.
  - OwnershipAnalyzer: capturing lambdas tracked as owned values, free calls inserted at scope exit.
  - Codegen: env struct includes dtor pointer; universal free dispatches via dtor; env ptr extracted from fat pointer at call site.
  - Stdlib: `RawPtr` type, `mml_free_raw` function. C runtime: `mml_free_raw`.
  - TypeChecker fix: propagate typeSpec to Lambda.captures (pre-existing bug).
  - All mem tests pass, including closure-capture (0 leaks).
- 2026-03-22: #244 bottom-up lambda param inference
  - TypeChecker: infer still-untyped lambda params from monomorphic body usage sites.
  - Supports simple let-alias propagation and capture-assisted anchors.
  - Adds dedicated conflict / no-anchor lambda inference errors and LSP/error-printer plumbing.
  - Tests cover operator/function anchors, alias chains, captures, conflicts, and top-down priority.
  - Sample: `mml/samples/lambda-infer-args.mml` now demonstrates unannotated lambda param inference.
- 2026-03-21: runtime — add FORCE_INLINE to string/IO functions.
  - `readline`, `print`, `println`, `concat`, `substring`, `free_string`,
    `string_builder_append`, `string_builder_finalize`, `to_cstr`.
- 2026-03-21: #188 Phase 2 QA — let-bound lambdas: stable names, TCO, direct self-calls.
  - TypeResolver: resolve param typeAsc in expression-level lambdas (4 cases missed params).
  - Stable names: `mangleName(param.name)` replaces `allocAnonFnName`.
  - TailRecursionDetector: traverse let-binding chains, detect self-recursion via binding param.
  - Codegen: TCO deferred emission, generalized `isSelfRef`/`findTailRecBody`.
- 2026-03-21: #188 Phase 2 QA — type ascription, recursive lets, codegen fixes.
  - General term-level type ascription in parser (`Term.withTypeAsc`).
  - Lambda `}: Type` return ascription flows as expected type for body.
  - RefResolver: let-binding name in scope during arg resolution.
  - TypeChecker: pre-seed binding type from lambda typeAsc for recursive lets.
  - Codegen: pre-allocate anon fn name for recursive let-bound lambda self-calls.
- 2026-03-21: #188 Phase 2 — lambda codegen for non-capturing lambdas.
  - TypeChecker: infer lambda param types from call-site expectedType.
  - `getLlvmType(TypeFn)` → `"ptr"` (opaque function pointer).
  - `compileLambdaLiteral`: expression-position lambdas to internal LLVM functions.
  - `compileIndirectCall`: call through function pointers (TypeFn in scope).
  - `CodeGenState`: `deferredDefinitions` + `nextAnonFnId`.
  - Runtime: `str_to_int` panics on invalid input, `mml_panic` helper.
- 2026-03-21: #188 Phase 1 — parser support for literal lambdas.
  - `arrowKw` keyword, `lambdaLitP` parser combinator in `expressions.scala`.
  - Wired into `termP`/`termMemberP`. Reuse `arrowKw` in type arrow parsing.
  - Guard `->` from operator parsing in `identifiers.scala`.
- 2026-03-21: Fix #243: `isMoveOnRebind` now moves native heap types
  - `isMoveOnRebind` uses `TypeUtils.isHeapType` instead of `isStructWithHeapFields`.
