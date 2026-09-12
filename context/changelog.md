# MML Changelog

Durable product changes belong here. Task administration belongs in task working memory.

## 2026-09-12

- Define paragraph-style spacing for Scala and code review, with compact simple case sequences
  permitted when they remain easy to read.
- Preserve generated-local counters through scoped call arguments so subsequent arguments
  receive distinct temporary and condition names. Name direct lambda-application analysis
  explicitly in the ownership pass.

## 2026-09-11

- Support nested consuming-PAP calls by analyzing arguments once in source order, preserving
  ownership moves and temporary cleanup. Reject later uses and argument borrows invalidated
  by those moves; destroy borrowed inline move-closure temporaries after their call.
- Add a commented nested-call sample and regressions for consumption, closure cleanup, and
  argument evaluation order.
- Preserve branch ownership for conditional arguments: free only owned results, transfer
  consuming branches individually, and reject borrowed sources. Ordinary calls, inline calls,
  and let bindings share condition decisions so effectful predicates run once.

## 2026-09-10

- Allow consuming arguments to be supplied after partial application, preserving ownership
  through aliases, returns, higher-order calls, and staged application. PAPs that retain
  their captures accept fresh consuming arguments repeatedly; transferred payloads remain
  call-once.
- Evaluate partial-application arguments once at creation, in source order. Track borrowed
  payload lifetimes and explicit ownership transfers through aliases and higher-order calls;
  release owned payloads exactly once when dropped or forwarded by a consuming call.
- Document PAP ownership rules and implementation limitations, with a commented sample
  contrasting reusable owning closures, borrowing PAPs, and consuming PAPs.
- Clarify review recovery after automated interruptions, preserving confirmed findings and
  distinguishing incomplete coverage from a completed review.
- Clarify semicolon termination, expression sequencing, and local binding scope in the
  language reference; illustrate attached and standalone terminator placement in the style guide.
- Port the local collaboration workflow from y0y: use task files and selected working memory,
  retain useful designs with their tasks, and preserve older plans and tracking in history.
- Remove the GitHub helper scripts and the retired tracking/specs layout.
- Define `finish task` (also `complete task`) to verify, log, mark complete, and commit;
  `finish errand` verifies, logs, and commits untracked work. Push only when requested.

## History imported on 2026-09-10

The entries below preserve the former tracker's change log verbatim. They include claims
from earlier branches, including the abandoned lambda implementation; they do not establish
completion on `dev-lambda-unify`. For that branch's evidence, use the
[lambda task](tasks/unify-lambdas.md) and [migration handoff](tasks/unify-lambdas.md#migration-handoff).
Historical paths and issue references describe their original context.

- 2026-06-18: #255 unify-lambdas — accept nullary lambda heads in immediate application
  - `TypeChecker.scala`: immediately-applied nullary lambdas now use the lambda application path,
    check the explicit `Unit` argument, and compute a `Unit -> T` function type for the lambda.
  - `MaterializationAnalyzerTests.scala`: un-ignored the nullary immediate-application regression
    so arity-0 direct-call materialization remains pinned.

- 2026-06-07: #255 unify-lambdas S8.6.1b — PAP ownership without implicit cloning
  - `OwnershipAnalyzer.scala` / `ExpressionRewriter.scala`: PAPs and lambda values now carry
    struct-like env ownership metadata. Borrowed heap payloads reject escaping returns, while
    already-applied consuming heap arguments move into the generated PAP env.
  - `Applications.scala` and Direct-call metadata: Direct PAP creation now stores borrowed or
    moved operands directly, with no hidden heap clone calls. PAP env destructors free only owned
    fields, and full application of owned payloads switches later cleanup to raw-env-only.
  - Error printers and tests: added the borrowed-PAP escape diagnostic and regressions for
    escaping/non-escaping borrowed PAPs, moved heap payloads, no implicit clones, and owned-field
    destructor behavior.
  - Docs/specs/mem programs: removed the temporary PAP implementation note from
    `docs/memory-model.md`, updated the PAP ownership spec/plan, and rewrote Direct PAP mem
    samples to use explicit `~` ownership transfer instead of clone-based ownership.
  - `context/dev-tools.md` / `context/coding-rules.md`: documented that `sbtn` commands must not
    be run in parallel; batch tasks into one `sbtn` invocation or run them sequentially.

- 2026-06-02: #255 unify-lambdas S8.6.1a/S8.6.1b — Direct PAP heap-payload ownership
  - S8.6.1a identified the escaping Direct PAP heap-payload bug, but the clone-per-PAP
    implementation direction is rejected because PAP creation must not insert hidden heap clones.
  - `context/specs/pap-ownership-model.md`: added the accepted PAP ownership model: borrowed heap
    PAP payloads may not escape their owner scope; moved heap PAP payloads require explicit `~`
    ownership transfer; partial application with remaining consuming parameters stays rejected.
  - `context/specs/unify-lambdas-plan.md` / `context/tracking.md`: added S8.6.1b as the next bug
    subtask to replace clone-per-PAP with explicit borrowed-vs-owned PAP payload rules.

- 2026-05-31: #255 unify-lambdas S8.6.1 — free Direct move-lambda owned-heap captures
  - `ExpressionCompiler.scala` / `codegen/emitter/package.scala`: `evaluateDirectCaptures`
    returns `DirectCaptureCleanup`s for the owned heap captures of a move Direct lambda; new
    `emitDirectCaptureFrees` emits one `__free_<T>` per capture at the binder's scope exit,
    gated on `isNativeMemFn` so module-defined struct destructors are not re-declared.
  - `FunctionEmitter.scala`: extracted `isNativeMemFn` from `resolveMemFnLlvmName`; threaded
    capture cleanups through `compileBoundStatements` / `compileDirectBoundStatement` and
    `compileTailRecBody` so loopified paths free per iteration before the back-edge.
  - `expression/Applications.scala`: the sequence-let Direct binder frees its owned heap
    captures after the body.
  - `ClosureCodegenTest.scala`: IR coverage for literal, owned-String, owned-struct, and
    loopified Direct captures.
  - `tests/mem/direct-move-literal-capture.mml` / `direct-move-owned-string-capture.mml` /
    `direct-move-owned-struct-capture.mml`: pinned ASan+LSan regressions.
  - `context/specs/unify-lambdas-plan.md`: recorded S8.6.1 and S8.6.1a — the escaping
    Direct partial-application heap-capture use-after-free this fix exposes, open and to be
    resolved before the S8.6 correctness item is closed.

- 2026-05-31: #255 unify-lambdas S8.5b — Direct partial-application env TBAA parity
  - `Applications.scala` / `ExpressionCompiler.scala` / `FunctionEmitter.scala`:
    Direct callable capture operands now carry semantic TBAA type names through nested
    Direct captures, Direct-call lowering, and Direct partial-application generation.
  - `Applications.scala`: Direct partial-application env stores and PAP-entry env loads
    carry field-specific `!tbaa` metadata, including the destructor slot and payload
    fields for applied args and captured operands.
  - `TailRecursionLoopificationTest.scala`: pinned escaped and captured Direct PAP env
    layouts, TBAA offsets, and load/store tags.

- 2026-05-31: QA follow-up — expression-oriented Direct lambda guard
  - `ExpressionCompiler.scala`: rewrote the Direct-lambda value-position guard in
    `compileLambdaLiteral` without an early `return`.

- 2026-05-31: #255 unify-lambdas S8.5a — Direct partial-application env lifetime/drop hardening
  - `Applications.scala` / `OwnershipAnalyzer.scala` / `ClosureMemoryFnGenerator.scala`:
    generated Direct partial-application closures now use heap envs with destructor slot
    0 when they become first-class function values, and returned function values are
    dropped through `__free_closure`.
  - `ExpressionCompiler.scala` / `FunctionEmitter.scala`: Direct callable scope entries
    carry enough signature information for undersaturated Direct calls while preserving
    saturated Direct calls that return function values.
  - `TailRecursionLoopificationTest.scala` / `tests/mem/escaping-paps.mml`: pinned the
    PAP env layout, capture offset, destructor dispatch, and ASan/LSan escaping-PAP
    regression.
  - `mml/samples/partial-fac1.mml` / `context/coding-rules.md`: kept the original
    local tail-recursive partial-application sample in the mandatory smoke list.

- 2026-05-31: #255 unify-lambdas follow-up planning — Direct partial-application env hardening
  - `context/specs/unify-lambdas-plan.md` / `context/tracking.md`: added S8.5 to
    harden Direct partial-application closure envs before S9. The next slice should
    prove escaping partial-application closures do not dangle, stop treating every
    generated partial-application env as stack-local, and add TBAA metadata parity
    for generated partial-application env load/store fields.

- 2026-05-31: #255 unify-lambdas S8 — direct loopification for tail-recursive lambdas
  - `ExpressionCompiler.scala` / `Applications.scala`: Direct tail-recursive scoped
    bindings stay on the Direct lowering path; `compileLambdaLiteral` now rejects every
    Direct lambda that reaches value-position lowering.
  - `FunctionEmitter.scala`: loopified plain-direct entries can carry Direct trailing
    captures as stable entry parameters while user parameters remain loop PHIs.
  - `ClosureMemoryFnGenerator.scala`: closure env structs are synthesized only for
    lambdas whose allocation classifier reports a real env.
  - `TailRecursionLoopificationTest.scala` / `FunctionSignatureTest.scala` /
    `TbaaEmissionTest.scala`: refreshed assertions for Direct loopified capture params
    and retained materialized-env coverage on non-Direct closure paths.

- 2026-05-31: #255 unify-lambdas S7/S7.5 — centralize closure env allocation classification
  - `ast/terms.scala`: added `ClosureEnvAllocation` and `Lambda.closureEnvAllocation` as
    the model-level derivation for no-env, stack borrow-env, and heap move-env shapes.
  - `ExpressionCompiler.scala` / `FunctionEmitter.scala` / `ClosureMemoryFnGenerator.scala`:
    routed call-site env allocation, capture field offsets, destructor-field layout, and
    env free generation through the shared classifier while preserving the tail-recursive
    Direct wrapper carve-out until S8.
  - `ClosureCodegenTest.scala` / `TbaaEmissionTest.scala`: pinned materialized borrow-env
    stack layout, materialized move-env heap/dtor layout, and move-env TBAA offsets.
  - `context/specs/unify-lambdas-plan.md`: recorded the S7.5/S7.6 outcome and left
    stack-promotion as S11 with ownership metadata as the future input.

- 2026-05-31: #255 unify-lambdas S4/S5 — close ownership classification and return-escape cleanup
  - `OwnershipAnalyzer.scala`: collapsed return-position borrowed-ref and borrow-closure
    discovery into a single tagged `ReturnEscape` walker while preserving closure-specific
    diagnostics as renderings of generic ownership checks.
  - `OwnershipAnalyzerTests.scala`: added consuming higher-order parameter regressions for
    top-level and inline non-capturing function values, keeping caller-side closure cleanup
    suppressed while callee-side consuming cleanup remains universal.
  - `context/specs/unify-lambdas-plan.md` / `tests/mem/direct-move-closure.mml`: marked
    S4/S5/S6 status current and replaced stale direct-move-closure failure notes with the
    passing direct-lowering behavior.

- 2026-05-31: #255 unify-lambdas S6.5 — deduplicate named-function closure thunks
  - `ExpressionCompiler.scala` / `codegen/emitter/package.scala`: added a named closure-entry cache so repeated first-class uses of the same named function reuse `@<fn>__closure_entry` instead of fresh anonymous forwarding thunks.
  - `FunctionSignatureTest.scala`: pinned stable named-function closure entries and added repeated higher-order named-function coverage.
  - `TailRecursionLoopificationTest.scala`: refreshed the tail-recursive named-function value assertion to the stable closure-entry shape.

- 2026-05-31: #255 unify-lambdas S6 Phase 6.4 — complete closure codegen cleanup
  - `ClosureCodegenTest.scala`: un-ignored the local move-capturing closure cleanup regression that pins direct calls to the generated env destructor.
  - `codegen/emitter/expression/package.scala` / `ExpressionCompiler.scala`: replaced the misleading `isDirectCallableRef` helper with `resolvesToNamedFunctionSymbol`, making the direct-call fallback explicitly about emitted named-function symbols.
  - `FunctionSignatureTest.scala`: added mixed direct and higher-order top-level function coverage so each use site keeps the correct call representation.

- 2026-05-30: #255 unify-lambdas — fix zero-field closure-env TBAA emission
  - `codegen/emitter/package.scala`: zero-field TBAA struct layouts now skip metadata emission; non-empty struct metadata is built from operand lists to avoid dangling separators.
  - `TbaaEmissionTest.scala`: added `lambda-factorial`-shaped regression coverage for zero-field closure-env TBAA suppression.
  - `context/coding-rules.md`: added `mml/samples/lambda-factorial.mml` to the mandatory post-task smoke checks.

- 2026-05-25: #255 unify-lambdas S6 Phase 6.3.d — completed Direct callable capture boundary
  - `FunctionEmitter.scala`: tail-recursive bound statements now lower let-bound Direct lambdas as `DirectCallable` entries instead of sending them through value-position closure materialization.
  - `ClosureCodegenTest.scala` / `FunctionSignatureTest.scala`: refreshed stale Direct ABI IR assertions and kept materialized-env coverage on deliberately non-Direct function-value paths.
  - Verification: full `sbtn test` passes after scalafmt/scalafix.

- 2026-05-24: #255 unify-lambdas S6 Phase 6.3.d — fix Direct callable capture boundary
  - `LoweredCaptureLayout.scala`: added `LambdaBindingIndex` plus a lowered capture-layout helper that expands captured Direct callables into the value operands their entries need.
  - `ClosureMemoryFnGenerator.scala`: synthesized closure env structs from lowered capture slots instead of raw `lambda.captures`, so env fields match the runtime representation generated by codegen.
  - `ExpressionCompiler.scala` / `FunctionEmitter.scala`: shared the lowered capture layout between Direct trailing params and materialized closure env setup/load; closure bodies now rebuild captured Direct sibling entries from loaded operands rather than treating them as first-class `{ ptr, ptr }` values.
  - `FunctionSignatureTest.scala`: added a regression for a materialized local helper that captures a Direct sibling helper, asserting the env stores the sibling's operands and calls the Direct entry.
  - `TbaaEmissionTest.scala`: re-aimed the captured-function TBAA fixture at a deliberately non-Direct function value so it still exercises real fat-pointer env fields under Direct lowering.

- 2026-05-24: #255 unify-lambdas S6 Phase 6.3 — make universal closure free optimizer-visible
  - `FunctionEmitter.scala`: generated universal closure destructors now use a dedicated LLVM attribute group.
  - `Module.scala`: emits that group as `alwaysinline`, keeping the generated `__free_closure` as the semantic cleanup backstop while exposing its null-env guard and destructor dispatch to LLVM.
  - `mml/samples/closure-free-shapes.mml`: added a retained sample covering both a non-capturing function value and a move-capturing closure with runtime input, so optimized cleanup shapes can be inspected without constant-folding the whole program.
  - Plan updated: the source-aware consuming-param elision idea is dropped for this slice because a generic consuming `TypeFn` param cannot see call-site lambda materialization without specialization or caller-side cleanup emission. Phase 6.3 is now the inline-for-optimizer request; remaining S6 work is Phase 6.4's IR-shape refresh.

- 2026-05-20: #255 unify-lambdas S6 Phase 6.2.c — keep `CapturedLiteral` captures in the Direct call shape
  - `ExpressionCompiler.scala`: `DirectTrailingSlot.Value` gains `cloneFnId: Option[String]`. `computeDirectTrailing` now treats `Capture.CapturedLiteral` as a value-shaped slot carrying the clone fn id; the slot's `outerOperand` falls back to `@<name>` when the capture isn't in the enclosing function scope (top-level binding case).
  - `evaluateDirectCaptures` returns `(CodeGenState, List[(op, ty)])`: for each clone-bearing slot it emits an ABI-lowered `__clone_<T>` call at the binder site and threads the cloned operand as the trailing argument. `Applications.compileBoundLambdaArg` Direct case threads the post-clone state into the body compile call.
  - Factored the clone-call shape into `emitCaptureCloneCall`; the env-materialization path (`emitCallSiteEnv`) now delegates to the same helper instead of inlining the ABI-lowered call.
  - Closes the P1b Codex finding: a Direct move lambda capturing a string literal no longer references an undefined outer SSA register; the cloned value flows through the lambda's own trailing param. End-to-end smoke (`let msg = "hello"; let greet = ~{ println msg; }; greet ();`) compiles and prints `hello`.
  - `FunctionSignatureTest`: new regression `Direct move lambda capturing a heap literal clones at the binder site` asserts (1) `main` emits `__clone_String`, (2) `greet`'s signature carries `%struct.String`, (3) `greet`'s body consumes its own trailing param.
  - Full mem harness 23/23. Smoke samples hola/quicksort/astar2 green. Test count 425 → 426; same 9 pre-existing Phase 6.4 stale-IR failures.
  - **Known open issue (deferred):** the cloned heap value passed to a Direct move lambda has no free site emitted at the call frame; for `String` captures this is a leak. Pinned for follow-up under S6.x / S11 once the Direct-move ownership model is decided. Not exercised by the existing mem harness fixtures.

- 2026-05-20: #255 unify-lambdas S6 Phase 6.2.b — thread direct-callable captures through nested Direct lambdas
  - `ExpressionCompiler.scala`: replaced `valueShapedCaptures` with `computeDirectTrailing` — for each `CapturedRef` whose enclosing-scope entry is a `DirectCallable`, expand the callable's `captureOperands` into fresh trailing slots and rebind the inner `DirectCallable` to point at the new inner-slot registers. `compileDirectLambda` and `evaluateDirectCaptures` both consume the unified trailing layout, keeping the call-site outer-operand order aligned with the inner LLVM signature.
  - Closes the P1a Codex finding: nested case `outer(a) { let f = { x -> x + a }; let g = { y -> f y }; g 1 }` no longer reuses g's `%0` (= `y`) as `f`'s captured `a` operand; `a` is threaded as a fresh trailing param of `g`.
  - `FunctionSignatureTest`: new regression `nested Direct lambda threads outer Direct callable's captures` asserts (1) `g`'s signature is `(i64 %0, i64 %1)`, (2) `g` calls `f` with `(%0, %1)` not `(%0, %0)`, (3) `outer` passes its own `%0` as `g`'s trailing arg.
  - Full mem harness 23/23. Test count rises 424 → 425; same 9 pre-existing stale-IR failures (Phase 6.4 work).

- 2026-05-20: #255 unify-lambdas S6 Phase 6.2 — Direct lowering: no env, no wrapper, no malloc
  - `ast/terms.scala`: `Materialization` enum (`Direct | NullEnv | Materialized`) + `Lambda.materialization` helper derived from `(meta.isDirect, captures.isEmpty)`. Single source of truth for lowering shape.
  - `codegen/emitter/package.scala`: `ScopeEntry` extended with `directCallable: Option[DirectCallable]`. `DirectCallable(entryName, captureOperands)` records the entry symbol + ordered trailing-arg operands.
  - `ExpressionCompiler.scala`: new `compileDirectLambda` emits one deferred LLVM fn with signature `(userParams..., captureTypes...)` — no env ptr, no closure-entry wrapper. Recursive self-references resolve through a self-DirectCallable injected into the body scope; `valueShapedCaptures` filters out direct-callable captures (propagated via inherited scope) and `Capture.CapturedLiteral` (top-level fn refs, resolved via global symbol). `compileLambdaLiteral` errors on non-tail-rec Direct lambdas (analyzer/codegen disagreement is a bug, not a fallback).
  - `Applications.scala`: `compileBoundLambdaArg` dispatches on `Materialization` — Direct uses `compileDirectLambda` + DirectCallable scope entry; NullEnv and Materialized keep the wrapper-based path. `compileApp` consults `ScopeEntry.directCallable` before any indirect-call decision and routes through new `compileDirectCall` (`call @entry(args..., captureOps...)`).
  - `ClosureMemoryFnGenerator.scala`: `collectCapturingLambdas` gated on `materialization == Materialized` — Direct lambdas never get env structs synthesized.
  - `docs/design/compiler-design.md`: documented the closure-materialization runtime null-guard as the closure analog of the `__owns_*` heap-conditional-join witness. Logged the open design question of replacing the runtime backstop with stricter static reasoning.
  - **Pinned regression `tests/mem/direct-move-closure.mml` passes under ASan+LSan**: the S4→S6 carry-over leak is closed. Full mem harness 23/23. 216 semantic tests + tail-rec tests + 3 smoke samples (hola/quicksort/astar2) all green.
  - **Tail-rec carve-out (deferred to S8)**: tail-recursive Direct lambdas (e.g. `factorial_tco`) continue to flow through the wrapper-based lowering. S8 ("Tail-recursion follow-up under unified model") already targets "immediate-application tail-recursive lambdas validated against the unified pipeline" — Direct + loopification lands there.
  - **IR-shape test refresh deferred to Phase 6.4**: `ClosureCodegenTest` (8), `FunctionSignatureTest` (1), `TbaaEmissionTest` (1) — assertions are stale w.r.t. the new Direct lowering. No functional regression; surfaced as expected drift.
  - Plan: marked S6 in progress with the Phase 6.2 landed-scope delta + carve-out + remaining work (Phases 6.3 / 6.4).

- 2026-05-20: #255 unify-lambdas S5 — unify return-escape walkers; close admin-wrapper aliasing hole
  - `OwnershipAnalyzer.scala`: collapsed the two return-escape walkers
    (`returnedBorrowedRefs`, `returnedBorrowClosures`) at the call site through a single
    `ReturnEscape` sum. `RefEscape` dispatches to `BorrowEscapeViaReturn` (gated on owned
    return type); `LambdaEscape` to `BorrowClosureEscapeViaReturn` (unconditional). The
    `escapeErrors` flatMap in `analyzeLambda` replaces the prior two-list concatenation.
  - Tightened `returnedBorrowedRefs` with administrative-`App`-wrapper descent (the same
    shape the closure walker already had). Tightened `returnsBindingParam` with admin-
    wrapper descent, closing a preexisting analyzer hole both walkers shared at depth
    ≥ 2: patterns like `let x = s; let y = x; y` (and the lambda-shaped analog) now
    raise the correct escape diagnostic. No existing fixture exercised this shape, so
    no behavior change on the test corpus.
  - Shadowing-safe descent: when descending into a wrapper body, the wrapper's own
    params are masked from the descent scope so the body's Ref-by-name lookups can't
    accidentally hit a shadowed outer binding. The body-returns-ours arm of
    `returnsBindingParam` is gated on the wrapper not shadowing OUR param's name, so
    the name-equality leaf check can't misread the wrapper's own param as a Ref to
    ours. Codex P2 — `let s = "static"; s` inside `fn f(s: String): String` no longer
    false-positives.
  - `OwnershipAnalyzerTests.scala`: 5 new regression guards — single-level let-wrap +
    Ref escape, let + Cond return + Ref escape, two-level nested let + Ref escape,
    two-level nested let + Lambda escape, and a negative gate (let body returning a
    static value is accepted).
  - Capture-ownership block (`analyzeLambda`) verified by inspection to already gate on
    `isOwnedValueType`; no code change. `CapturedMovedHeapBinding` and
    `CapturedBorrowedHeapBinding` remain specialized renderings of the generic
    capture-time ownership check.
  - Phase A of the original S5 plan (source-aware consuming-TypeFn-param body-end free)
    deferred to S6: the callee cannot statically know the caller's binding-source
    `freeFn`, and closing the gap cleanly requires either caller-side cleanup emission
    or a scope-info propagation that breaks `tests/mem/consume-closure.mml` if landed
    in isolation. S6's codegen rework is the natural home.
  - Plan: marked S5 in progress with a landed-scope delta paragraph and the S4→S6
    carry-over for the consuming-param free.

- 2026-05-20: #255 unify-lambdas S4 — heap-only `isOwnedType`; lambda-value ownership predicate
  - `OwnershipAnalyzer.scala`: `isOwnedType` is heap-only (`TypeFn` removed from the
    type-identity arm). Introduced `isOwnedLambdaValue(lambda)`
    = `!meta.isDirect && captures.nonEmpty && isMove` and used it at the lambda-arm
    classifiers (`termReturnsOwned`, `lambdaAllocates`). Introduced
    `isOwnedValueType` (heap OR `TypeFn`) and used it at every site that propagates an
    already-classified function value (return-ownership discovery, allocation propagation,
    owned-binding scope frees including the consuming-param body-end free, conditional
    cross-branch frees, capture classification, borrow-escape-return check, and final
    cleanup). Net observable behavior change at the analyzer: a direct move-capturing
    binding (`isDirect = true`) no longer registers an owned env via `lambdaAllocates` —
    this is the documented S6 carry-over (codegen still mallocs the env until S6
    consults `isDirect`).
  - `OwnershipAnalyzerTests.scala`: one new regression guard — let-bound non-capturing
    lambda passed as a HO arg schedules no `__free_closure` and is borrowed.
  - `tests/mem/consume-closure.mml`: new ASan+LSan regression — caller move-on-rebinds a
    materialized move-closure into a consuming `~f: Int -> Int` param across 1000
    iterations. Guards that the body-end `__free_closure(f)` cleanup keeps releasing the
    env malloc'd by `makeAdder`.
  - `tests/mem/direct-move-closure.mml`: new pinned regression — `let f = ~{x -> x+a}; f 41`
    leaks 16 bytes from the closure env malloc because the analyzer no longer registers
    direct move-closures as owned, while codegen still materializes their env. Expected
    to fail under ASan/LSan until S6's lowering rule for `isDirect` lambdas stops
    materializing the env. Mem harness reports 22/23 with this regression in place.
  - `ClosureCodegenTest.scala`: rewrote the "local move capturing closures free through
    their specific env destructor" fixture to use a value-position binding (`apply f 41`)
    and marked it `.ignore` pending the S6 IR-snapshot refresh.
  - `context/qa-rules-and-coding-style.md`: added section 9 (`Comments`) — code comments
    describe current code in present tense; no "no longer / was / previously"; no
    slice/plan references in source; slice notes live in plan/tracking docs.
  - Plan / tracking: marked S3 done, S4 in progress; recorded the un-ignore item against
    S6; recorded the "consuming-TypeFn-param + non-owned value → no `__free_closure`"
    tightening as an S5 carry-over (needs source-aware flow analysis).

- 2026-05-20: #255 unify-lambdas S3 — `MaterializationAnalyzer` computes `isDirect`
  - New `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/MaterializationAnalyzer.scala`:
    three-pass walker (collect lambda bindings → discover non-direct ids via saturation-
    depth tracking → rewrite `LambdaMeta.isDirect`). Saturation floor `max(arity, 1)` keeps
    arity-0 thunks sound. Handles top-level `Bnd` lambdas and parser-lowered scoped-binding
    `App(Lambda(params=[binder], ...), arg)` shape.
  - `SemanticStage.scala`: wired between `CaptureAnalyzer` and `TypeChecker` via the
    standard `timePhase` wrapper.
  - `prettyprint/ast/Term.scala`: `LambdaMeta` rendering now includes `isDirect`.
  - `MaterializationAnalyzerTests.scala`: 9 tests covering top-level direct, recursive
    self-call, let-bound direct/value-position, lambda-literal immediate application,
    nullary top-level invoked vs used as value, let-bound nullary as value. The nullary
    lambda-literal immediate-application case is ignored pending the `TypeChecker.scala:784`
    `lambda.params.nonEmpty` guard accepting nullary heads.

- 2026-05-19: #255 unify-lambdas S1 — terminology cleanup
  - `docs/design/compiler-design.md`: retired "ordinary closure literal" / "real closure
    literal" phrasing in the local-`let` and CaptureAnalyzer phase sections; aligned with
    the spec's "scoped-binding lambda" vs "value-position lambda" vocabulary.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CaptureAnalyzer.scala`: comments
    and scaladoc updated to use "value-position lambda" / "scoped-binding lambda";
    behaviour unchanged.

- 2026-05-19: #255 unify-lambdas spec reframe + S2 AST addition
  - `context/specs/unify-lambdas.md`: rewrote the `## Decisions` section. Q2 collapsed to
    a single `isDirect: Boolean` field on `LambdaMeta` (3-way materialization derived
    from `(isDirect, captures.isEmpty)`); dropped the `freeVars`/`envFields` split, the
    `Escape` enum, and the `CaptureMode` enum. Added a "Lambda values are ordinary unique
    values" subsection framing the unification thesis. Extended the implementation-notes
    slice list with stack-promotion as goal 7.
  - `context/specs/unify-lambdas-plan.md`: rewrote the Q1/Q2/Q4/Q5/Q6 proposed answers
    and the matching slice descriptions to match the simplified metadata. S2 reduced to
    a single additive field; S5 reframed as "treat lambda values as ordinary unique
    values"; S11 promoted from DEFERRED to an in-scope slice with concrete files and
    acceptance criteria. Sub-issue list updated.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala`: added
    `isDirect: Boolean = false` to `LambdaMeta`. Pure additive change; no consumer reads
    it yet, behaviour preserved by the default.

- 2026-05-18: Mem evolution layer 4 — shared refs, `&` and `^` operators
  - `docs/brainstorming/mem/mem-evolution.md`: added Layer 4 (shared refs, `&T` type,
    opt-in refcount, resources as Drop without Clone); added "`&` is a primitive, `^`
    is a protocol operator" subsection; extended hierarchy ladder with `&T`; updated
    summary table and the uniqueness-tradeoff paragraph to point at Layer 4.
  - `docs/brainstorming/mem/shared-refs.md`: added aliasing arm of `&`, clone-out via
    `^` as Clone-protocol surface syntax, resources example, and the primitive-vs-protocol
    distinction; resolved open questions 2/4/5 against Layer 3+4; updated the current-
    direction ladder to use `^`.

- 2026-05-15: Direct lambda-head application with partial application semantics
  - ExpressionRewriter: direct lambda-head calls now lower multi-parameter lambdas into nested
    unary immediate-lambda applications, preserve partial direct application as residual lambdas,
    and route extra adjacent args through normal application/type-checking.
  - Tests: added parser and semantic regressions for direct lambda-head application, move lambda
    preservation, partial application, higher-order extra args, over-application diagnostics, and
    lambda literals as atomic arguments.

- 2026-05-15: #261 local TCO ABI materialization follow-up
  - Codegen: non-capturing let-bound tail-recursive lambdas now emit a plain loopified direct entry
    plus a closure-entry wrapper for first-class materialization.
  - Tests: added `lambda-factorial`-shaped coverage for the direct entry, wrapper forwarding, and
    `{ ptr @wrapper, ptr null }` materialized value.

- 2026-05-15: #261 separate direct-call ABI from closure-call ABI
  - Codegen: direct-callable non-capturing functions, including loopified tail-recursive functions,
    now use plain entry signatures while first-class function values keep the uniform `{ fn, env }`
    closure representation.
  - Codegen: non-capturing callable values use closure-entry wrappers where needed, capturing
    closures stay on env-bearing entries, and static null-env closure calls now lower to direct
    closure-entry calls instead of extract+indirect sequences.
  - Tests/samples: added regression coverage for direct loopified calls, higher-order loopified
    function values, dead recursive self-binding elimination, static null-env direct calls, and the
    combined function/lambda ABI sample.

- 2026-04-03: Local duplicate-name check for sequential `let` rebindings
  - Semantic duplicate checking now walks parser-lowered scoped-binding chains so repeated local
    binders in the same user scope are rejected while synthetic statement wrappers are ignored and
    real nested shadowing remains legal.
  - Tests/samples: added focused duplicate-name regressions for local rebinding and nested
    shadowing, updated closure codegen coverage to use legal nested shadowing, and rewrote the
    `astar`, `astar2`, and `astar3` heap-sift locals to avoid the old loophole.

- 2026-04-02: Runtime strip-margin helper
  - Runtime/stdlib: added `str_strip_margin` to the C runtime and injected it as a standard
    library string helper that returns a freshly allocated `String`.
  - Samples: updated `mml/samples/astar3.mml` so `main` parses command-line wall arguments and the
    help text is rendered through `str_strip_margin`; added
    `mml/samples/str_strip_margin.mml` as a focused sample for the helper.

- 2026-04-01: #253 QA test infra cleanup
  - Tests: moved shared AST-shape helpers into `mml.mmlclib.test.extractors`, documented the
    `TX*` helpers with syntax-oriented Scaladoc/examples, and added small inline comments where the
    test-only traversal logic was non-obvious.
  - Tests: migrated the parser/semantic ownership-heavy suites to direct
    `mml.mmlclib.test.extractors.*` imports, extracted shared lambda/traversal helpers, and
    removed repeated local AST walkers from ownership-oriented tests.
  - Tests: removed the obsolete `mml.mmlclib.test.TestExtractors` compatibility shim after the
    direct-import migration, leaving the shared extractor package as the single supported test
    helper entry point.

- 2026-04-01: #252 QA Parser
  - Parser docs: added syntax-oriented Scaladoc across the full parser package so the grammar
    entry points, helper combinators, and support utilities read as a guided parser walkthrough.
  - Examples: documented top-level members, expressions, local `let` / inner `fn` lowering,
    lambdas, selections, conditionals, tuples, native syntax, literals, and type syntax with
    concrete MML snippets next to the combinators that parse them.

- 2026-04-01: #251 update compiler design doc for current AST and semantic pipeline
  - Docs: rewrote `docs/design/compiler-design.md` to describe the current single-AST pipeline,
    parser lowering for lambdas, local bindings, and statement sequencing, plus the actual
    semantic phase order now that capture analysis, closure env synthesis, and final reindexing are
    part of the flow.
  - Docs: refreshed the closure / ownership sections to match current borrow-vs-move closure
    semantics, immediate lambda application behavior, and the current diagnostic/escape rules.
  - Docs: tightened the prose and terminology in the design doc so it reads as technical design
    documentation rather than a stale phase inventory.

- 2026-04-01: Refresh closure docs after borrow-by-default capture changes
  - Docs: updated `docs/memory-model.md` to describe borrow closures vs move closures, stack vs
    heap closure environments, borrow-closure escape restrictions, and the current `~` closure
    syntax for both lambda literals and inner functions.
  - Docs: trimmed `docs/language-reference.md` so it documents the surface closure syntax and
    points to the memory-model doc for detailed ownership semantics, while removing stale
    closure-capture wording that no longer matches the implementation.

- 2026-03-31: #258 fix bogus borrow-escape on scalar returns in non-escaping local fns
  - OwnershipAnalyzer: scoped local helpers now derive their effective return type from the
    computed `TypeFn` result instead of treating the whole callable type as a heap-return signal,
    removing the bogus `Cannot return borrowed value 'count'` path for parser-lowered inner `fn`
    and let-bound lambdas.
  - Ownership diagnostics: added `BorrowedValuePassedToConsumingParam` and rewired the printer /
    LSP / source-snippet paths so borrowed captured heap bindings passed to consuming params get a
    specific error instead of the misleading `must be the last use` message.
  - Tests/samples: added ownership regressions for inner-`fn` and let-bound scalar-return parity,
    updated constructor/borrowed-capture expectations to the new diagnostic, and simplified
    `mml/samples/nqueens.mml` so it compiles without unnecessary `~` annotations.

- 2026-03-31: #257 LSP callable semantic token coloring
  - LSP: semantic token classification now colors source-level callable values as `function`
    based on effective `TypeFn` type, covering inner functions, callable let-bindings, and
    higher-order callable params.
  - LSP: fixed `elif` token emission so nested lowered `Cond` nodes no longer paint the first two
    characters of the following term as a keyword token.
  - Tests: added focused `SemanticTokensTests` coverage for inner-function refs, callable locals,
    callable params, and the `elif` coloring regression.

- 2026-03-29: #188 close loopified borrow-closure validator follow-ups
  - Codegen: loopified borrow-closure validation now clears rebound active names, respects lambda binder shadowing, and permits immediately-invoked borrow lambdas while keeping the borrow-closure forwarding/reuse checks.
  - Tests: added loopified regression coverage for rebinding shadowing, lambda-parameter shadowing, and the direct-invocation validator path.

- 2026-03-29: #188 loopified borrow-closure env hoist with tracked validator follow-ups pending
  - Codegen: function emission now supports an entry-block prologue so borrow closure env `alloca`s created on loopified paths can be hoisted out of repeated execution while move closures keep their existing heap/destructor flow.
  - Loopified borrow-closure validation: added conservative codegen-time checks for borrow closures that may survive across iterations; the current shadowing / immediate-invocation regressions are tracked above and remain pending.
  - Tests: added closure codegen coverage for entry-block hoisting and for rejecting loop-carried borrow closures.

- 2026-03-29: #188 stabilize forward-reference reorder and close borrow-closure return-wrapper escape
  - TypeChecker: topological reordering now derives queue seeding, dependency release order, and cycle fill from source-ordered member ids so unrelated bindings keep source order while forward references still reorder correctly.
  - OwnershipAnalyzer: `returnedBorrowClosures` now follows returned scoped-binding wrappers only when the wrapper body actually returns the bound value, covering parser-lowered `let` / local-`fn` `App(Lambda(...), arg)` escape cases without flagging non-escaping borrow closures.
  - Tests: added regression coverage for stable reorder preservation and for borrow-capturing closure escape through both `let` bindings and local inner-function returns.

- 2026-03-29: #188 Reject borrow-capturing closures that escape via return
  - OwnershipAnalyzer: added `returnedBorrowClosures` to detect borrow-capturing lambda literals in return position; unconditional check (not gated behind return type).
  - New error: `BorrowClosureEscapeViaReturn` with full printer/LSP/source-snippet wiring.
  - Tests: added ownership tests for borrow-escape rejection, move-escape acceptance, and non-capturing acceptance.
  - Sample: `mml/samples/mem/borrow-closure-escape.mml` demonstrates the error.

- 2026-03-29: Lambda body trailing semicolon now optional
  - Parser: `lambdaBodyExprP` accepts either `;` or lookahead `}` as body terminator.
  - `{ x -> x }` and `{ x -> x; }` are both valid; `}` acts as implicit terminator.

- 2026-03-29: #188 Borrow-by-default captures with explicit `~` move syntax
  - AST: added `Lambda.isMove: Boolean = false`; default = borrow captures, `true` = move.
  - Parser: `~` removed from operator charset; `~{...}` parses as move lambda, `fn ~name(...)` as move inner function.
  - ClosureMemoryFnGenerator: borrow env structs have no `__dtor` field; destructors and `__free_closure` generated only for move lambdas.
  - OwnershipAnalyzer: borrow captures leave outer binding owned (multiple closures can share); move captures transfer ownership (current behavior). Escape analysis extended to cover `TypeFn` return types.
  - Codegen: borrow closures use `alloca` (stack env, no malloc/free); move closures use `malloc` with destructors. Capture field offset parameterized (0 for borrow, 1 for move).
  - Tests: updated ownership, codegen, TBAA tests for borrow-by-default; added borrow sharing and alloca codegen tests.
  - Samples/mem: escaping closures updated to `~{...}`; new `borrow-capture.mml` mem test (21/21 ASan pass).

- 2026-03-29: #188 3.5.1 Literal heap capture auto-cloning
  - AST: added `Capture` enum (`CapturedRef` / `CapturedLiteral`) to distinguish plain captures from literals that need cloning; `Lambda.captures` changed from `List[Ref]` to `List[Capture]`.
  - Ownership: `Literal` state heap captures are no longer rejected; the ownership analyzer resolves the clone function ID and upgrades them to `CapturedLiteral`.
  - Codegen: `emitCallSiteEnv` emits an ABI-lowered `__clone_T` call for `CapturedLiteral` captures before storing into the env struct.
  - Mechanical: `CaptureAnalyzer`, `ClosureMemoryFnGenerator`, `TypeChecker`, `FunctionEmitter`, and tests updated for `Capture` unwrapping.

- 2026-03-29: TypeNameResolver nominal-metadata cleanup
  - Codegen metadata: deleted `TypeNameResolver.scala`, added a standalone top-level nominal-name helper, and rewired emitter call sites so TBAA, alias-scope, and related metadata paths derive names from nominal AST types instead of reverse-mapping raw LLVM layouts.
  - Correctness: metadata name resolution now fails fast when a bare `NativePrimitive` or `NativePointer` reaches a path that requires a nominal type name, exposing real upstream type leaks instead of guessing aliases such as `Int` or `Bool`.
  - Tests: updated `TbaaEmissionTest` fixtures to use legal nominal refs/resolvables rather than illegal bare-native struct fields so the coverage matches parser-produced AST shapes.

- 2026-03-28: #188 lambda/codegen QA batch — 3.4-QA.19, 3.4-QA.20, 3.4-QA.21, 3.4-QA.26, 3.4-QA.27, 3.4-QA.28
  - Test infra / lambda semantics: added shared `TX*` lambda/test extractors in `TestExtractors.scala` and rewrote the lambda grammar, capture-analysis, typechecker, and ownership tests to assert semantic intent through shared extractors/resolved ids instead of brittle shape- and name-coupled traversal helpers.
  - Closure memory generation: `ClosureMemoryFnGenerator` now traverses tuples and selection qualifiers, builds capture type maps with folds instead of mutable builders/early returns, and tags lambdas nested under qualifiers with semantic `envStructName` metadata; added focused coverage in `ClosureMemoryFnGeneratorTests`.
  - Closure env codegen/TBAA: capture-site env setup now resolves and uses the semantic closure env `TypeStruct`, emits stores against `%struct.<env-name>` rather than ad-hoc local names, and attaches field-level TBAA metadata to destructor/capture stores; `ClosureCodegenTest` now covers semantic env naming and capture-site TBAA tagging.
  - Type-name resolution: removed the old package-level `getMmlTypeName` helper, routed emitter call sites through `TypeNameResolver`, added single-element `TypeGroup` handling and broader fallback cases there, and preserved alias identity for `TypeAlias` refs so metadata paths keep distinct MML names such as `MyInt`, `Int`, and `SizeT`; `TbaaEmissionTest` now asserts aliased struct-field metadata preserves alias identity.
  - AST/tests: `Term.withTypeAsc` now spells out the ignored term cases explicitly, and `TermTests` pins the current behavior for supported literals/invalid expressions versus constructor passthrough.

- 2026-03-28: #188 QA cleanup batch — 3.4-QA.15, 3.4-QA.17, 3.4-QA.22, 3.4-QA.24
  - Ownership: replaced the anonymous 5-tuple free-tracking payload with an `OwnedBinding` case class in `OwnershipAnalyzer`.
  - Closure/codegen/runtime: removed the `asInstanceOf` cast from `ClosureMemoryFnGenerator.tagLambdas`, normalized the new closure/codegen paths onto Cats `.asRight`/`.asLeft`/`.some`/`.none` style, and dropped `FORCE_INLINE` from non-hot runtime string/IO helpers.


- 2026-03-28: #188 3.4-QA.14 TypeFn non-closure function-value lowering
  - Semantics/codegen: bare callable refs in argument position now eta-expand into first-class function values only when undersaturated, preserving local shadowing, and call lowering now routes non-direct callable refs through the shared indirect fat-pointer path instead of assuming every `TypeFn` ref is a direct symbol.
  - Tests/samples: added semantic and codegen coverage for higher-order named function arguments, shadowed local callable parameters, and global function-valued bindings; added `mml/samples/typefn-nonclosure-values.mml` as a focused sample.

- 2026-03-28: #188 3.4-QA.13 nullary callable canonicalization
  - Types/parser/typechecker: callable types now use a non-empty parameter list, nullary callables are canonicalized as `Unit -> R`, and the previous special compatibility path between zero-arity and `Unit` thunks was removed.
  - Codegen/LSP/printing: `TypeFn` consumers now traverse `NonEmptyList` parameter types consistently, and user-facing formatting/docs now present nullary callable signatures as `Unit -> ...`.
  - Tests: added semantic coverage for nullary function references and nullary lambdas having `Unit -> R` types, and updated affected grammar/codegen expectations.


- 2026-03-28: #188 3.4-QA.30 duplicate heap capture rejection
  - Ownership: capturing the same owned heap binding into a second closure now fails during ownership analysis with a dedicated moved-capture diagnostic instead of slipping through to runtime double-free.
  - Tests/samples: added `OwnershipAnalyzerTests` coverage for duplicate-vs-borrowed helper capture flows, and updated `mml/samples/raytracer3.mml` so `render_rows` is the sole heap-state owner while sibling helpers take `buf`/row arrays by parameter.

- 2026-03-28: #188 Raytracer 3 sample follow-up in progress
  - Samples: added `mml/samples/raytracer3.mml` as the fully local-helper follow-up to `raytracer2`, keeping the original `raytracer2` header comments as historical context and documenting the current ownership/codegen caveats inline in the new sample.
  - Tracking: added QA.29 under the lambda/codegen follow-ups for the newly exposed P1 bug where capturing a whole struct value in local-helper closures can emit invalid LLVM IR (`raytracer3` whole-`Camera` capture case).


- 2026-03-28: #188 3.4-QA.11 closure free dispatch consolidation
  - Codegen: closure destructors now carry explicit `DestructorKind` metadata, closure free calls adapt fat pointers to env pointers once, and `__free_closure` now emits its null-guarded dtor dispatch in the generated function body instead of inlining it at each call site.
  - Closure memory generation: generated per-env and universal closure destructors are tagged explicitly, removing raw string-prefix coupling from codegen dispatch and closing the paired QA.23 naming-coupling debt.
  - Tests: added `ClosureCodegenTest` coverage for escaped closures freeing through generated `__free_closure` and local capturing closures freeing through their specific env destructor.


- 2026-03-28: #188 3.4-QA.10 mergeSubState deferred-state sync
  - Codegen: deferred lambda-body state merge now preserves the sub-run `CodeGenState` wholesale and restores only the parent output/register context, removing the manual field-sync maintenance hazard in `ExpressionCompiler`.
  - Tests: added `ClosureCodegenTest` coverage that exercises deferred lambda-body string/TBAA emission so metadata produced inside deferred bodies must survive into the final module IR.


- 2026-03-28: #188 3.4-QA.6 real closure env on recursive capturing lambdas
  - Codegen: recursive let-bound capturing lambdas now rebuild their self fat-pointer from the live hidden `%env` parameter inside the deferred function body instead of self-calling through `{ ptr @fn, ptr null }`.
  - Applications/codegen plumbing: recursive let-binding preallocation now reserves the non-capturing self stub only for non-capturing lambdas; capturing lambdas defer self binding until function-body codegen.
  - Tests/samples: added `ClosureCodegenTest` coverage for the null-env regression and added `mml/samples/recursive-tail-inner-captures-sibling.mml` alongside the existing non-tail sibling-capture sample.


- 2026-03-27: #188 3.4-QA.25 TypeFn closure env TBAA lowering
  - Codegen/TBAA: `TypeNameResolver` now gives `TypeFn` a stable MML type name (`Function`) so closure env structs with captured function values lower through TBAA/type-name resolution instead of failing.
  - Struct layout: closure env TBAA generation now accepts captured `TypeFn` fields in env structs, including the sibling-local-helper shape exercised by inner functions.
  - Tests/samples: added `TbaaEmissionTest` coverage for closure env fat-pointer fields and a focused sample for the inner-function sibling capture case.

- 2026-03-27: #188 Ptr parsing and lowering
  - Parser/native types: `@native[t=ptr]` now parses as `NativePrimitive("ptr")`; `@native[t=*...]` remains `NativePointer(...)`.
  - Stdlib/codegen: `RawPtr` now lowers as opaque `ptr`; pointer-like classification and native `noalias` checks now include opaque pointers.
  - LLVM emission: load/store helper paths now emit opaque-pointer operands as `ptr`, avoiding invalid `ptr*` IR in raw-pointer flows.
  - Tests: added grammar/codegen coverage for `t=ptr`, opaque-pointer `noalias`, and closure env/TBAA expectations for raw-pointer slots.
  -

- 2026-03-27: #245 Inner function syntax
  - Parser: added local `fn name(...): Ret = ... ; expr` surface syntax in `expressions.scala`.
  - Tests: grammar, capture analysis, and typechecker coverage added for typed and recursive inner functions.
  - Docs: language reference now documents inner functions as sugar for local let-bound lambdas.
  - Samples: added `mml/samples/readline-loop-inner-fn.mml`; `mml/samples/raytrace2.mml` now documents
    the shipped sample compromise and the deferred fully-local-helper `TypeFn` closure bug.
  - Verification already completed in-session: targeted tests, fast samples, full suite, publish,
    benchmarks, installed `mmlc` recursive inner-function check, and direct runs of the new
    readline sample / built `raytrace2`.

- 2026-03-26: No-`end` syntax
  - Parser: removed `end` from conditional syntax; nested parser frames now close with semicolons only.
  - Parser: function, operator, conditional, and lambda bodies now require their own final expression `;`.
  - Tests/tooling: migrated Scala parser/semantic/codegen/LSP fixtures to the new semicolon ownership rules.
  - Repo-wide `.mml`: migrated samples, benchmarks, mem harness inputs, and intentional negative fixtures so
    positives compile and negatives fail for their intended reason instead of stale syntax.
  - Docs: refreshed syntax documentation, including `docs/language-reference.md`.
  - Verification: `sbtn "scalafmtAll; scalafixAll; test; mmlcPublishLocal"` passed (`337/337`);
    `make -C benchmark mml` passed; `tests/mem/run.sh` passed (`19/19`);
    positive standalone front-end sweep passed for all compileable checked-in `.mml` files.

- 2026-03-25: #246 LLVM IR VS Code extension
  - Added a standalone VS Code extension under `tooling/vscode-llvm-ir` for `.ll` files.
  - Implemented editor-side symbol indexing, hover, go-to-definition, find-references, and
    document outline support for LLVM IR symbols.
  - Added explicit LLVM IR output/context-menu commands and a separate installer script
    `tooling/install-vscode-llvm-ir-extension.sh`.
- 2026-03-24: #188 Phase 3.5 — heap-type capture ownership (move semantics + env field free)
  - OwnershipAnalyzer: heap-type captures move into env; borrowed captures → error.
  - Captured heap bindings set to Borrowed inside lambda body (env owns them).
  - Codegen: env destructor emits GEP+load+free for each heap field, ABI-lowered args.
  - `sizeOfLlvmStructResolved`: state-aware struct sizing resolves named types for env malloc.
  - New `CapturedBorrowedHeapBinding` error + printer/LSP/extractor wiring.
  - New mem test: `tests/mem/closure-heap-capture.mml` (19/19 ASan+LSan pass).
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
