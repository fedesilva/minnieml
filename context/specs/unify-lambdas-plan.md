# Unify Lambdas — Implementation Plan

## Context

Tracked item: **#255 Unify lambdas**. Design spec: `context/specs/unify-lambdas.md`
(deep design doc; marked "not yet approved"; carries 6 open questions). Adjacent QA
context: `context/specs/lambdas-work-review.md` (most P1s closed by changelog; one P1
— "stop freeing non-capturing function values as closures" — still open and subsumed
by S4 below).

This plan does two things:

1. Proposes answers to the spec's 6 open questions, so the spec can be marked finalized.
2. Slices the implementation into PR-sized chunks with concrete files, acceptance criteria,
   and ticketing intent.

Status: Reviewed and in progress.


Keep the plan up to date as you work on it. Update status often, here and in tracking.md

---

## Proposed answers to the spec's 6 open questions

Once approved, these answers fold into a `## Decisions` section on
`context/specs/unify-lambdas.md`. Each answer cites the slice that acts on it.

### Q1 — Should `Lambda.captures` keep mixing free-variable facts and materialization fields, or be split?

**Proposed: no split. `Lambda.captures` stays.** Acted on by **S2**.

- `Lambda.captures: List[Capture]` continues to be the single list of bindings the
  lambda references from enclosing scopes. `CaptureAnalyzer` populates it for every
  lambda scope (no change in S2; behavior already covers value-position lambdas).
- The decision "does this lambda need an env built?" is recorded separately on
  `LambdaMeta` (see Q2). When an env *is* built, codegen reads `captures` as the layout
  source. When no env is built (Direct / NullEnv), `captures` still describes the
  semantic references — just nothing materializes them at runtime.
- `CapturedRef` / `CapturedLiteral` and the literal-clone upgrade in ownership stay
  where they are. They are lowering hints carried alongside the semantic facts.

**Why we rejected the split.** A two-field design (`freeVars` + `envFields`) was
considered. It only earns its keep in one case — "lambda has captures but no env" —
which is exactly what the `isDirect` bit on `LambdaMeta` (Q2) already gates. For every
other case the two fields hold the same information, and the split forces every reader
through `Option[List[Capture]]` instead of `List[Capture]`. One field plus a clear gate
on `LambdaMeta` is simpler and equally principled.

### Q2 — Where should materialization requirements be recorded?

**Proposed: one field on `LambdaMeta`, computed by a new pass.** Acted on by **S3**.

```
// LambdaMeta gains:
//   isDirect: Boolean   // never used as a value — scope-only / immediate-application
//
// Lambda.isMove: Boolean   // unchanged — borrow-vs-move marker
// Lambda.captures          // unchanged — list of referenced bindings
```

The 3-way materialization state is **derived**, not stored:

- `meta.isDirect`                       → **Direct**: no env, no fat pointer, scope-only lowering.
- `!isDirect && captures.isEmpty`       → **NullEnv**: first-class value is `{ ptr @entry, ptr null }`.
- `!isDirect && captures.nonEmpty`      → **Materialized**: real env, fat pointer
  `{ ptr @closure_entry, ptr env }`.

A helper (likely on `Lambda` or a shared codegen module) exposes this derivation so
every consumer agrees on the lowering.

`captureMode` as a separate enum is **not** added — `Lambda.isMove: Boolean` already
carries the same information. Per-capture (Mixed) capture mode remains out of scope.

**No `escape` field.** A four-case `Escape` enum
(`NonEscaping | EscapesAsParam | EscapesAsReturn | EscapesToStore`) was considered.
We dropped it once we reframed lambda values as ordinary unique values (see the
"Lambda values are ordinary unique values" subsection in the design spec):

- Move-capturing lambda values are owned heap values. Return, struct-sink, and `~`
  transfer are already discovered by the existing ownership analyzer at the use site.
- Borrow-capturing lambda values are borrowed values. Return as owned, struct-sink,
  and `~` transfer are already rejected by the existing ownership rules
  (`BorrowEscapeViaReturn`, `BorrowedValuePassedToConsumingParam`).
- Non-capturing lambda values have no owned environment to track.

Re-encoding "where did this lambda escape" on `LambdaMeta` would duplicate ownership
analysis the analyzer runs anyway, *and* would push consumers back toward
closure-specific reasoning. The whole point of the unification is to let lambda values
reuse the generic value-ownership machinery. If S11 stack-promotion later needs
lifetime information about the binding that owns a closure, it consults ownership,
not a precomputed escape state.

**Multi-use join rule.** `isDirect` joins by AND across use sites: starts `true` and
flips to `false` on the first value-position use (HO argument, return, store, alias).
Once `isDirect = false`, ownership classification (owned heap vs borrowed) drives the
rest — no further metadata is needed.

New pass `MaterializationAnalyzer` runs between `CaptureAnalyzer` and
`ClosureMemoryFnGenerator`. Ownership analysis and codegen read `LambdaMeta.isDirect`;
escape semantics are handled by the ordinary ownership analyzer at the use site.

**Why:** ownership needs to know whether to insert a `free` at scope end. That decision
falls out of treating the lambda value as an owned heap value (move-capturing,
non-direct) or a borrow (everything else). Encoding `isDirect` as semantic metadata
keeps the materialization gate visible, unit-testable, and one bit wide.

### Q3 — Can immediate lambda application always avoid materialization?

**Proposed: yes, with one principled exception.** Acted on by **S3**.

Operational rule: `meta.isDirect = true` iff every reference to the lambda (or to its
binding) occurs in App.fn position with full arity. Pure `App(Lambda(...), arg)` shape —
and ownership wrappers around it — leave `isDirect` true.

Exception: if the lambda is bound and the binding itself is used as a value
(`let f = { x -> x }; f`), the *binding occurrence* flips `isDirect` to false. Demand
analysis (Q2) computes this.

**Why:** keeps the common case (let / sequencing / direct call) zero-cost and the rule
mechanical.

### Q4 — Should top-level and local functions share exactly the same `BindingMeta`?

**Proposed: same shape, but semantic phases never branch on origin.** Acted on by **S10**.

Keep `origin: BindingOrigin` (TopLevel | Local | Inner) on `BindingMeta` for
diagnostics, source positions, and codegen entry-point naming. *Enforce* that semantic
phases (capture, materialization, ownership, type checker) never branch on `origin` for
behavior — they branch on `LambdaMeta.isDirect`, `LambdaMeta.escape`, and `Lambda.isMove`.

Move `destructorKind` off bindings; it belongs only on env structs, and only when an
env actually exists. Trim `BindingMeta` accordingly.

**Why:** origin is real (source-level distinction) and worth keeping for tooling, but it
must not be a hidden behavioral fork.

### Q5 — How much of direct-entry eligibility should be computed before codegen?

**Proposed: all of it.** Acted on by **S6**.

`LambdaMeta.isDirect` is the bit. Codegen consults it (alongside `captures.isEmpty`) and
never re-derives "is this called directly?". The lowering rules:

- `isDirect`                              → emit direct entry only; no wrapper.
- `!isDirect && captures.isEmpty`         → emit direct entry + closure-entry wrapper;
  first-class users see `{ ptr @entry, ptr null }`.
- `!isDirect && captures.nonEmpty`        → emit closure entry with env param + optional
  direct entry when at least one statically-known direct call site exists.

### Q6 — Should non-escaping move-capturing lambdas stay heap-backed initially?

**Proposed: yes. Stack-promotion is deferred.** Acted on by **S7**, deferred work in **S11**.

Phase-1 allocation rule (S7) is driven by `Lambda.isMove` alone:

- `isMove == false` → env on stack (`alloca`).
- `isMove == true`  → env on heap  (`malloc`).

Stack-promotion for non-escaping move closures is the deferred slice S11 — tracked
but not scheduled as part of #255. When S11 lands, the input is the ownership
analyzer's lifetime conclusion about the binding that holds the closure value (does
its owning binding outlive the current stack frame?). That is a property of the
binding, not of the lambda — and the ownership analyzer already computes it for every
owned heap value. S11 reads ownership; it does not add a precomputed escape field to
`LambdaMeta`.

This keeps unification PRs about *unifying*, not about optimization.

---

## Slice plan

Each slice lists: goal, files to touch, acceptance, and ticketing intent. Slices are
ordered by dependency. Dependencies are linear except where noted.

**Slicing rules.** Each slice is a clean transformation, not a transitional state. No
compatibility shims, no mirror-fields, no dual code paths "until the next slice lands".
Intermediate breakage between slices is acceptable — the codebase need not build cleanly
or pass tests at every slice boundary. Soundness is required at the END of the
workstream, proven by S9. Per-slice acceptance criteria specify what the slice itself
must produce; they do not require IR or behavioral parity with the prior state.

**Rationale.** MML is in active development with no external users. Each iteration can
break and rebuild freely. Compatibility scaffolding (stopgaps, dual code paths, "for now"
flags, mirror fields) adds noise, drift risk, and dead code that has to be removed
later. The goal is a clean, principled, sound implementation — not a chain of
transitional states. If a slice cannot land cleanly without scaffolding, expand the
slice or accept temporary breakage; do not invent a shim.

### S0 — Decisions section in design spec  *(done)*
- **Goal:** lock the 6 answers above into the spec.
- **Files:** `context/specs/unify-lambdas.md` (append `## Decisions`).
- **Acceptance:** section exists; each decision cites the slice that acts on it.
- **Tracking:** checklist item on #255.

### S1 — Terminology cleanup  *(done — commit 27d7f57)*
- **Goal:** retire "real closure literal" language; speak the spec's vocabulary across
  docs and comments.
- **Files:** `docs/design/compiler-design.md`, `docs/memory-model.md`,
  `docs/language-reference.md`; comments in `CaptureAnalyzer.scala`,
  `ClosureMemoryFnGenerator.scala`, `OwnershipAnalyzer.scala`.
- **Acceptance:** `rg "real closure"` returns no hits in semantic/codegen comments or
  docs; terminology aligns with `unify-lambdas.md`.
- **Sub-issue?** No — checklist item.

### S2 — AST: add `isDirect` to `LambdaMeta`  *(done — commit 7ce9711)*
- **Goal:** mechanical AST change from Q2. Add a single field `isDirect: Boolean = false`
  to `LambdaMeta`. `Lambda.captures` and `Lambda.isMove` stay. No `Escape` enum, no
  `escape` field. No new analysis logic in this slice; the default `isDirect = false`
  preserves current behavior (every lambda still materializes as it does today).
- **Files:**
  - AST: `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala` (`LambdaMeta`
    L91–L94 — add `isDirect: Boolean = false`).
  - Optional small derivation helper (e.g. `Lambda.materialization: Materialization`
    method or a top-level helper) that returns Direct / NullEnv / Materialized from
    `(meta.isDirect, captures.isEmpty)`. Add only when S3+ consumers actually need it;
    do not over-engineer here.
  - No reader/writer churn elsewhere — `captures` and `isMove` are untouched.
- **Acceptance:** code compiles end to end; full test suite remains green (pure
  additive change with a default that preserves current behavior). No new tests in
  this slice.
- **Sub-issue?** No — small, additive.

### S3 — `MaterializationAnalyzer` pass  *(done — commit 456a0c4)*
- **Goal:** Q2 + Q3 + Q5. New pass walks every lambda scope, computes `isDirect`, and
  writes it back to `LambdaMeta`. Implements the AND-join multi-use rule (start `true`,
  flip on first value-position use). `CaptureAnalyzer` is unchanged.
- **Files:**
  - NEW: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/MaterializationAnalyzer.scala`.
  - Pipeline wiring: insert new pass between `CaptureAnalyzer` and
    `ClosureMemoryFnGenerator` (which from S4 onward consumes the metadata to decide
    lowering shape).
  - Tests: new `MaterializationAnalyzerTests.scala` covering Direct (immediate
    application, scope-only let / sequencing), NullEnv (non-capturing fn passed as
    value), Materialized (lambda with captures used as value); multi-use join cases
    (one direct call + one value use → not direct); resolved-id-based assertions.
- **Acceptance:** new pass tests pass. Existing closure tests still pass because no
  consumer has been migrated yet — `isDirect` is written but ignored. No behavioral
  change observable from outside `MaterializationAnalyzer`.
- **Sub-issue?** Yes.

### S4 — Ownership: non-capturing / null-env function values stop being treated as owned heap  *(done)*
- **Goal:** close the open P1 from `lambdas-work-review.md` ("Stop freeing non-capturing
  function values as closures").
- **Files:** `OwnershipAnalyzer.scala` (TypeFn ownership rule around L256–L259 per the
  QA doc's reference; `analyzeLambda` L1350; consuming-param flows).
- **Rule:** classify lambda values via `(meta.isDirect, captures.isEmpty, isMove)`.
  Only `!isDirect && captures.nonEmpty && isMove` lambdas (real materialized move
  closures) are owned heap values. Direct lambdas, NullEnv values, and borrow-capturing
  materialized lambdas are not freed at scope end and are not passed to
  `__free_closure`.
- **Acceptance:** new ownership regressions pass — consuming param receives a top-level
  fn ref; consuming param receives a non-capturing lambda literal; HO param receives a
  non-capturing closure; old `__free_closure(f)` crash path no longer triggers. Mem
  tests for owned move-capturing closures (e.g. `closure-capture.mml`,
  `closure-heap-capture.mml`) must still pass; codegen-IR-snapshot tests may shift and
  are refreshed at S6/S7.
- **Landed scope:** the lambda-value ownership predicate (`isOwnedLambdaValue`) gates on
  `!isDirect && captures.nonEmpty && isMove` and is used at the lambda-arm classifiers
  (`termReturnsOwned`, `lambdaAllocates`). Type-driven ownership sites that consume an
  upstream ownership classification route through `isOwnedValueType` (heap OR `TypeFn`)
  so materialized move-closure cleanup stays intact. Direct lambdas, null-env values,
  and borrow-capturing materialized lambdas are not caller-owned heap values at the
  ownership layer. Consuming `TypeFn` params still schedule callee-side
  `__free_closure(f)` cleanup; the universal closure free null-guards non-capturing
  values and dispatches materialized env destructors.
- **Regression coverage:** ownership tests cover top-level, inline, and let-bound
  non-capturing function values as higher-order arguments; top-level and inline
  non-capturing function values passed to consuming higher-order params; and
  materialized move-capturing closure values still scheduling cleanup. The pinned
  direct move-closure mem regression is green after S6's direct lowering.
- **Sub-issue?** Yes.

### S5 — Ownership: treat lambda values as ordinary unique values  *(done)*
- **Goal:** kill the structural branches on top-level vs let-bound vs literal *and* the
  parallel closure-specific escape machinery. Lambda values participate in the generic
  ownership analyzer.
- **Landed scope:**
  - Return-position escape discovery is a single tagged walker returning `ReturnEscape`.
    `RefEscape` dispatches to `BorrowEscapeViaReturn` when the declared return type is
    owned; `LambdaEscape` dispatches to `BorrowClosureEscapeViaReturn`.
  - The walker descends through administrative `App` wrappers (`let x = …; …` and
    `fn x …;; …`) via a tightened `returnsBindingParam`. Multi-level aliasing patterns
    like `let x = s; let y = x; y` are flagged at any nesting depth for both Ref and
    Lambda shapes.
  - Regression tests cover single-level wrap, conditional inside wrap, two-level
    nesting, borrow-capturing lambda return, and the shadowing/negative gates.
  - Capture ownership at the `analyzeLambda` site is generic over `isOwnedValueType`.
    `CapturedMovedHeapBinding` and `CapturedBorrowedHeapBinding` remain specialized
    diagnostic renderings of the generic capture-time ownership check.
  - Consuming `TypeFn` body-end cleanup remains callee-side and universal. It is the
    ordinary ownership cleanup for a consuming function-value param; non-capturing
    function values carry a null env, while materialized move closures carry an env
    destructor at field 0.
- **Lambda value ownership classification:**
  - move-capturing closure value (`!isDirect && captures.nonEmpty && isMove`) →
    owned heap value; ordinary owned-heap rules apply at return / `~` transfer /
    struct-sink / consuming-param sites
  - borrow-capturing closure value (`!isDirect && captures.nonEmpty && !isMove`) →
    borrowed value; ordinary borrowed-value rules apply
  - non-capturing lambda value (`!isDirect && captures.isEmpty`) → borrow-only at the
    ownership layer; no owned env, nothing to free
  - direct lambda (`isDirect`) → scope-only; not a value, never classified
- **Files:** `OwnershipAnalyzer.scala` — return-position escape discovery, capture-heap
  analysis, consuming-param checks, and struct-sink checks. The lambda value's ownership
  classification feeds those checks the same way any other value's classification does.
- **Rule:** structural shape of the binding does not matter; classification of the
  lambda value drives the analyzer. Existing diagnostics
  (`BorrowClosureEscapeViaReturn`, `CapturedBorrowedHeapBinding`,
  `BorrowedValuePassedToConsumingParam`) stay in the diagnostic set as **specialized
  rendering** of the generic ownership errors — the user still sees closure-specific
  phrasing where it helps, but the underlying check is the generic one.
- **Acceptance:** ownership unit tests pass for the covered lambda forms; existing
  ownership-error fixtures still produce the same error variants on the same input
  programs; closure-specific diagnostics remain only as specialized renderings of
  generic ownership checks. Equivalence tests at S9 are the final cross-form gate.
- **Sub-issue?** Yes.

### S6 — Codegen: derive direct-vs-closure entry from demand  *(done)*
- **Goal:** Q5 in codegen. One source of truth replaces the scattered structural
  reasoning.
- **S6 landed scope (Phase 6.2):**
  - `Materialization` enum + `Lambda.materialization` helper (`ast/terms.scala`) — single source
    of truth derived from `(meta.isDirect, captures.isEmpty)`. Reads: `compileBoundLambdaArg`
    and `ClosureMemoryFnGenerator.collectCapturingLambdas`.
  - Direct lowering: `compileDirectLambda` emits one deferred LLVM fn with signature
    `(userParams..., captureTypes...)`, no env ptr, no wrapper. At the binder site, `param` is
    bound to a `DirectCallable(entryName, captureOperands)` scope entry; `compileApp` dispatches
    to `compileDirectCall` which appends captures as trailing args at every call site.
  - Captures that are themselves direct-callable bindings or top-level fn refs
    (`Capture.CapturedLiteral`) are filtered out of the runtime trailing-args list:
    direct-callable captures propagate via the inherited `functionScope`; top-level fn captures
    resolve through the global symbol table at the body call site.
  - `ClosureMemoryFnGenerator.collectCapturingLambdas` gated on
    `materialization == Materialized` — Direct lambdas don't get env structs synthesized.
  - `compileLambdaLiteral` errors if it sees a Direct lambda (analyzer/codegen disagreement is a
    bug, not a fallback).
  - **Pinned regression `tests/mem/direct-move-closure.mml` passes under ASan+LSan**; full mem
    harness 23/23 green. All 216 semantic tests, tail-rec tests, and the three smoke samples
    (hola, quicksort, astar2) pass.
- **Tail-rec carve-out (deferred to S8):** tail-recursive Direct lambdas (e.g. `factorial_tco`)
  still flow through the wrapper-based lowering. The Direct path doesn't yet implement
  tail-call loopification. **S8** ("Tail-recursion follow-up under unified model") is the
  natural home — its acceptance criterion already calls for
  "immediate-application tail-recursive lambdas validated against the unified pipeline." S8
  will route tail-rec Direct lambdas through `compileDirectLambda` with loopified bodies and
  no wrapper. Current carve-out is sound (matches test corpus) but emits a redundant wrapper
  for tail-rec lambdas that are only ever called directly.
- **Phase 6.2 fixups — Codex review findings (must land before 6.3/6.4):**
  Two real issues uncovered by `/codex:review` of the Phase 6.2 working tree. Each is
  an independent restartable step; P1a is the larger refactor and grounds the
  trailing-param layout that P1b also uses, so 6.2.b lands before 6.2.c.

  - **Phase 6.2.a — Always synthesize env metadata for capturing lambdas (P2). *(moot)***
    Codex flagged `ClosureMemoryFnGenerator.collectCapturingLambdas` (line 58) as
    gated on `materialization == Materialized`, which would have skipped env struct
    synthesis for Direct lambdas and broken the tail-rec wrapper carve-out. On
    inspection the gate is and has always been `captures.nonEmpty` (since
    2026-03-24) — Direct capturing lambdas already get an env struct + tag. No
    code change. Step retained in the plan for traceability.

  - **Phase 6.2.b — Thread direct-callable captures through nested Direct lambdas (P1a). *(done)***
    `ExpressionCompiler.computeDirectTrailing` replaces `valueShapedCaptures`. Each
    direct-callable `CapturedRef` is expanded into one trailing slot per operand of
    the enclosing-scope `DirectCallable`; the nested body gets a rebound
    `DirectCallable` whose operands point at the new inner-slot registers. Outer
    and inner views (`evaluateDirectCaptures` / `compileDirectLambda`) share the
    same layout. Regression: `FunctionSignatureTest` *"nested Direct lambda threads
    outer Direct callable's captures"*.

  - **Phase 6.2.c — Keep CapturedLiteral captures in the Direct call shape (P1b). *(done)***
    `DirectTrailingSlot.Value` carries an optional `cloneFnId`; `CapturedLiteral`
    captures now contribute a value slot. `evaluateDirectCaptures` returns
    `(CodeGenState, ops)` and emits the ABI-lowered clone at the binder site for any
    clone-bearing slot; the cloned operand flows through the lambda's trailing param.
    The clone-call shape is factored into `emitCaptureCloneCall`, shared with the
    env-materialization path. Regression: `FunctionSignatureTest` *"Direct move
    lambda capturing a heap literal clones at the binder site"*. Known follow-up:
    the cloned heap value has no free site at the call frame — leak deferred until
    Direct-move ownership lands (S6.x / S11).

- **Phase 6.3 — make universal closure free visible to the optimizer. *(done)***
  Source-aware `__free_closure(f)` elision at consuming-`TypeFn` param sites was dropped
  as the implementation target for this slice: the source lambda materialization fact
  exists at producer/call sites, but a generic consuming param only sees a `TypeFn`
  value unless the callee is specialized or cleanup moves to the caller. Instead, the
  generated universal `__free_closure` remains the semantic backstop and is marked
  `alwaysinline`, exposing its null-env guard and destructor dispatch to LLVM. The
  `closure-free-shapes.mml` sample pins the null-env + materialized-env inspection case;
  optimized IR (`*_opt.ll`) inlines the universal helper away and reduces materialized-env
  cleanup to a direct `mml_free_raw` call when the surrounding call path is visible.

- **Phase 6.3.d — fix Direct callable capture boundary. *(done)***
  `mmlc -maI mml/samples/raytracer3_p6.mml` exposed invalid LLVM IR:
  `store { ptr, ptr } %0, ptr %54` where `%0` was a `float` in `raytracer3p6_main`.
  The root cause was that materialized closure envs were built from raw `lambda.captures`.
  A closure capturing a Direct sibling tried to store that sibling as a first-class
  `{ ptr, ptr }` value even though the sibling only exists as a `DirectCallable`.

  The fix introduces one lowered capture layout for Direct trailing params and
  materialized env fields. Captured Direct callables are expanded into their own
  value-shaped operand slots; zero-capture Direct callables contribute no fields but
  still rebind as `DirectCallable` entries in generated bodies. The `raytracer3_p6`
  `compute_row` env now stores the `camera` operands needed by `pixel_ray`, and
  `compute_row` calls the `pixel_ray` Direct entry with loaded operands instead of
  capturing a fat pointer.

  `TbaaEmissionTest`'s captured-function fixture was re-aimed at a deliberately
  non-Direct local function value so it continues to test TBAA for real fat-pointer
  env fields after Direct lowering.

- **Phase 6.3.d follow-up — refresh Direct ABI IR tests. *(done)***
  Full-suite verification exposed the stale Phase 6.4 IR-shape assertions that were
  still expecting closure-entry wrappers or env allocation for Direct lambdas. The
  refreshed `ClosureCodegenTest` and `FunctionSignatureTest` assertions now pin the
  plain Direct entry ABI and keep materialized-env coverage on deliberately non-Direct
  function-value paths.

  The same run exposed a real tail-recursive codegen gap: bound statements inside the
  loopified lowering still compiled let-bound Direct lambdas through `compileExpr`,
  which sent them to the value-position lambda path. `FunctionEmitter` now lowers that
  shape the same way as normal scoped lambda bindings: emit the Direct entry and bind a
  `DirectCallable` in the local scope.

- **Phase 6.4 — complete closure codegen cleanup. *(done)***
  `ClosureCodegenTest`'s local move-capturing closure cleanup regression now runs and
  pins the locally known env-specific destructor path. The codegen helper formerly
  named `isDirectCallableRef` is retired in favor of `resolvesToNamedFunctionSymbol`,
  because this call-site decision is about whether a ref can lower to an emitted
  function symbol, not whether the producing lambda is `Materialization.Direct`.
  `FunctionSignatureTest` covers a top-level function used both directly and as a
  higher-order value, preserving the distinct direct-call and fat-pointer
  materialization shapes.

- **Phase 6.5 — deduplicate named-function closure thunks. *(done)***
  Codegen hygiene only: when a named function is materialized as a first-class value,
  reuse one closure-entry thunk per `(original resolved function symbol, closure ABI
  signature)` instead of emitting a fresh anonymous forwarding thunk at every
  materialization site. For example, every first-class use of
  `functionlambdacombinations_inc : Int -> Int` should reuse:

  ```llvm
  define internal i64 @functionlambdacombinations_inc__closure_entry(i64 %x, ptr %env) {
    %r = call i64 @functionlambdacombinations_inc(i64 %x)
    ret i64 %r
  }
  ```

  Materialization sites then use `{ ptr @functionlambdacombinations_inc__closure_entry,
  ptr null }` rather than producing equivalent anonymous wrappers such as
  `@functionlambdacombinations__anon_0` and `@functionlambdacombinations__anon_12`.
  The same rule applies to other named functions such as
  `functionlambdacombinations_tail_inc_until`.

  This must not change runtime semantics, closure representation, capture handling, or
  direct-call lowering. Direct calls continue to call the plain named function symbol;
  only first-class named-function values use the shared closure-entry thunk with a null
  environment. Validation target: unoptimized IR is more stable and inspectable while
  optimized IR remains equivalent.

  Landed implementation: `CodeGenState` carries a named closure-entry cache keyed by
  target symbol and closure ABI signature. `ExpressionCompiler` recognizes non-capturing
  eta-forwarding lambdas produced for named-function values and routes them through the
  stable `@<fn>__closure_entry` symbol. Repeated higher-order uses reuse the cached
  deferred definition; direct calls still target the plain named function symbol.
- **Files:** `ExpressionCompiler.scala` (`compileLambdaLiteral` L151,
  `compileCapturingLambda` L662, `compileNonCapturingLambda` L381, `emitCallSiteEnv`
  L518); `Applications.scala` (`compileIndirectCall` L555, `staticNullEnvClosureTarget`,
  `emitClosureFreeViaEnvDtor`); `FunctionEmitter.scala` (`renderFunctionLines` L166,
  closure-entry wrapper at L244, `emitCaptureLoads` L143).
- **Lowering rules:** as in Q5 (driven by `(isDirect, captures.isEmpty)`).
- **Acceptance:** `ClosureCodegenTest`, `TbaaEmissionTest`, `FunctionSignatureTest`
  refreshed against the new IR shapes and pass; first-class top-level fn passed as HO
  argument lowers as `{ ptr @entry, ptr null }`; direct call to a local lambda with
  statically known args lowers as a direct call without going through the fat pointer.
  IR snapshots may shift — refresh as needed.
- **S4 carry-over closed at S6 sign-off:**
  - `ClosureCodegenTest` "local move capturing closures free through their specific env
    destructor" runs and pins the non-direct move-closure cleanup path.
  - Direct move-capturing lambdas used only in saturated calls lower as direct entries:
    no closure wrapper, no env materialization, no `malloc`.
  - `tests/mem/direct-move-closure.mml` is a green regression for the S4/S6 bridge.
- **Sub-issue?** Yes — large blast radius.

### S7 — Codegen: env allocation rule consumes `isMove` *(done)*
- **Goal:** the rule that decides `alloca` vs `malloc` reads `Lambda.isMove` (already
  present). This slice verifies that the existing `isMove` plumbing remains the single
  allocation gate after S3–S6 land.
- **Phase-1 allocation rule** (the only rule shipped in this slice):
  - `isMove == false` → `alloca`
  - `isMove == true`  → `malloc`
- Stack-promotion for non-escaping move closures is a later slice (**S11**); phase-1
  ships the simple `isMove` rule.
- **Files:** `ExpressionCompiler.scala` (`emitCallSiteEnv` L518–L659);
  `FunctionEmitter.scala` (entry-block prologue path L166–L170, `emitEnvHeapFieldFrees`
  L203–L243); `ClosureMemoryFnGenerator.scala` (`mkEnvStruct` L142–L180,
  `mkFreeFunction` L189–L257).
- **Acceptance:** mem tests for borrow and move closures pass; the allocation gate
  remains `Lambda.isMove` and is centralized in the call-site env emission paths.
- **Landed scope:** `ClosureEnvAllocation` is the model-level spelling for the
  derived env allocation shape. Codegen now consumes that classifier for call-site
  `malloc` vs `alloca`, capture field offsets, destructor-field layout, and env free
  generation. The tail-recursive Direct wrapper carve-out still locally materializes
  the lambda until S8 handles Direct loopification under the unified path.
- **Tests:** `ClosureCodegenTest` and `TbaaEmissionTest` pin materialized borrow-env
  stack layout, materialized move-env heap/dtor layout, Direct move-capturing no-env
  behavior, and borrow/move TBAA offsets.
- **Sub-issue?** Optional — can fold into S6 if blast radius stays manageable.

### S7.5 — Push env allocation classification onto the lambda model *(done)*
- **Goal:** promote the closure-env allocation classification out of scattered codegen
  branches and into a shared model-level derivation after S7 proves the current
  behavior. S7 keeps the simple `isMove` gate; S7.5 gives later phases one named
  classification to consume.
- **Candidate shape:** add a small derived classifier near `Lambda.materialization`
  that combines materialization and capture mode, for example:
  - `Direct` / `NullEnv` → no env allocation
  - `Materialized && !isMove` → stack borrow env
  - `Materialized && isMove` → heap move env
- **Design constraint:** this should remain derived from existing facts
  (`materialization`, `captures`, `isMove`) rather than becoming a second source of
  truth. S11 stack-promotion can extend the classification with ownership/lifetime
  facts, but S7.5 should not implement stack-promotion.
- **Files:** likely `terms.scala` for the derived classifier, plus the current
  codegen consumers in `ExpressionCompiler.scala`, `FunctionEmitter.scala`, and
  `ClosureMemoryFnGenerator.scala`.
- **Acceptance:** codegen reads the shared classifier for env allocation, field
  offset, destructor-field presence, and free-function generation; S7's borrow/move
  mem and IR coverage stays green.
- **Landed scope:** `ClosureEnvAllocation.NoEnv`, `StackBorrowEnv`, and
  `HeapMoveEnv` live beside `Materialization`. `Lambda.closureEnvAllocation` is
  derived only from existing materialization and move-capture facts.
- **Sub-issue?** No — small model cleanup after S7.

### S7.6 — Decide whether to pull S11 stack-promotion forward *(done — leave S11 separate)*
- **Goal:** after S7 and S7.5, decide whether stack-promotion should stay as S11 or be
  folded into the immediate post-S7 work. The decision point exists because S7.5's
  allocation classifier is the natural hook for the frame-local move-closure case.
- **Decision criteria:**
  - Pull forward if S7.5 exposes a clean ownership/lifetime input and the change stays
    localized to env allocation plus cleanup.
  - Leave as S11 if deriving frame-local ownership requires broader ownership-analysis
    changes, new escape-state plumbing, or substantial mem-harness expansion.
- **Acceptance:** record the decision in this plan before starting implementation. If
  pulled forward, move S11's files and acceptance criteria into the new post-S7 slice;
  if left as S11, keep S7.5 purely structural and avoid stack-promotion behavior.
- **Decision:** leave stack-promotion as S11. S7.5 is structural cleanup only; it does
  not change move-closure allocation behavior.
- **S11 design note:** ownership analysis supplies the frame-local fact, and codegen
  consumes it through the allocation classifier rather than re-inferring escape/lifetime
  from the final AST. The first S11 implementation should likely add a `StackMoveEnv`
  classification case. Because `closure-mem-gen` runs before ownership, S11 should
  initially reuse the move-env struct layout and change only the call-site allocation /
  cleanup choice; ownership should not reshape env structs earlier in the pipeline.
- **Sub-issue?** No — planning gate only.

### S8 — Tail-recursion follow-up under unified model *(done)*
- **Goal:** TCO/loopification consults materialization metadata.
- **Files:** `FunctionEmitter.scala` (`findTailRecBody` L1005, `extractBody` L1018,
  `isSelfRef` L1127, `extractSelfCallFromAccumulated` L1077); `ExpressionCompiler.scala`
  (`compileTailRecLambdaLiteral` L201, `compileTailRecCapturingLambda` L300,
  `emitRecursiveSelfClosure` L446, `lambdaReferencesBinding` L466).
- **Acceptance:** TCO unit tests and loopified borrow-closure regressions pass against
  the post-S6/S7 IR (snapshots refreshed as needed); `compileTailRecCapturingLambda`
  reuses the shared env setup; immediate-application tail-recursive lambdas validated
  against the unified pipeline.
- **Landed scope:** Direct tail-recursive local lambdas now lower through the Direct
  pipeline. `compileLambdaLiteral` rejects all Direct lambdas, including tail-recursive
  ones. The loopified plain-direct ABI accepts Direct trailing captures as stable entry
  parameters while user parameters remain loop PHIs. Materialized tail-recursive closure
  values still use the shared env setup and closure-entry ABI. Closure env synthesis
  collects only lambdas whose allocation classifier reports an env, so Direct capturing
  lambdas do not get unused env structs.
- **Sub-issue?** Yes.

### S8.5 — Direct partial-application env lifetime and TBAA hardening
- **Goal:** harden Direct partial-application closure generation before S9. A Direct
  callable can still produce a first-class function value when an application is
  undersaturated; that generated partial-application closure needs the same lifetime
  and metadata discipline as other closure envs.
- **Correctness issue:** generated partial-application envs must not always use
  `alloca`. A local single-use partial application such as `let from5 = factorial_tco 5`
  can use stack lifetime, but a partial-application closure returned from a function,
  stored, or otherwise escaping the current frame must not point at stack storage.
- **Implementation direction:** add an explicit allocation decision for generated
  partial-application envs instead of letting codegen assume stack lifetime. The
  conservative first implementation may heap-allocate generated partial-application
  envs until ownership/lifetime facts can safely identify frame-local cases. Do not
  fold this into S11 stack-promotion; S8.5 is about making generated partial
  applications correct and metadata-complete.
- **TBAA parity:** generated partial-application env struct fields should get TBAA
  nodes and field tags for both stores at the creation site and loads in the generated
  partial-application entry, matching materialized closure-env load/store behavior.
- **Tests:** add an escaping partial-application regression, e.g. a function returns
  `factorial_tco n` and the caller invokes the returned function later; it must print
  or return `120` and pass ASan/LSan. Add IR assertions that escaped generated
  partial-application envs do not use stack storage and that generated env load/store
  operations carry TBAA metadata.
- **Landed scope:** Direct partial application now builds generated PAP closures without
  materializing the full-arity closure wrapper. Returned/escaping PAP envs are
  heap-allocated, use the same destructor-at-field-0 convention as heap closure envs,
  and are dropped via `__free_closure`. The generated PAP entry reads captured values
  from field 1+, preserving field 0 for the env destructor. The mem harness includes an
  accumulating escaping-PAP regression under ASan/LSan. Generated PAP env stores and
  loads carry field-specific TBAA metadata as of S8.5b.
- **Remaining:** Frame-local PAP env stack allocation is a later optimization once
  ownership or lifetime facts can classify non-escaping PAP values.
- **Out of scope:** direct-call elision for single-use generated partial applications
  (`insertvalue` immediately followed by `extractvalue`) is an optimization only; keep
  the uniform `{ ptr, ptr }` value form until correctness and metadata are settled.
- **Sub-issue?** No — immediate hardening follow-up before S9.

### S8.6 — Review findings to clear before S9
- **Goal:** resolve (or consciously accept) the items from the static review of this
  branch against `dev-2026-03-21-lambdas` (2026-05-31) before the S9 soundness gate
  runs. Items already tracked elsewhere are linked, not repeated.

- **Correctness:**
  - [x] Direct move-lambda owned-heap captures leak (reframed from "heap literal").
    A Direct `~` move lambda that captures ANY owned heap value never frees it: a string
    literal (clone leaks), an owned `String` from `int_to_str` (the value leaks, no clone),
    and an owned struct with heap fields (whole struct leaks). Confirmed by IR: `main`
    builds/clones the value, calls the lambda, and returns with no free. **Broad fix
    landed in commit `0bf58f78`** (see S8.6.1 below) — frees every owned heap capture
    at the Direct binder's scope exit. S8.6.1a resolves the escaping Direct partial
    application ownership follow-up required to close this item.

#### S8.6.1 — Broad owned-heap-capture free at Direct binder scope (landed — commit 0bf58f78)
- **Mechanism:** new `DirectCaptureCleanup(operand, llvmType, mmlTypeName)` returned as a
  third element from `evaluateDirectCaptures`; a shared `emitDirectCaptureFrees` helper
  (mirrors `emitEnvHeapFieldFrees`, gated on `isNativeMemFn` so generated struct destructors
  are not re-declared); wired into the sequence-let path (`compileBoundLambdaArg`) and the
  loopified path (`compileBoundStatements` → `compileDirectBoundStatement`, with a
  `pendingCleanups` accumulator threaded through `compileTailRecBody` and freed at the
  terminating leaves so each runtime path frees exactly once before its terminator/back-edge).
  Extracted `isNativeMemFn` out of `resolveMemFnLlvmName`.
- **Files:**
  `codegen/emitter/package.scala` (DirectCaptureCleanup),
  `codegen/emitter/ExpressionCompiler.scala` (evaluateDirectCaptures + emitDirectCaptureFrees),
  `codegen/emitter/expression/Applications.scala` (Path 1),
  `codegen/emitter/FunctionEmitter.scala` (isNativeMemFn, Path 2),
  `test/.../codegen/ClosureCodegenTest.scala` (4 IR tests),
  `tests/mem/direct-move-literal-capture.mml`, `direct-move-owned-string-capture.mml`,
  `direct-move-owned-struct-capture.mml` (3 pinned ASan+LSan regressions, thunk + loop forms).
- **Verification done:** full suite 447/447 (1 ignored); ClosureCodegenTest 15/15; mem harness
  27/27 ASan+LSan; smoke samples + benchmarks green; scalafmt/scalafix clean; qa-enforcer pass
  (only low/style nits). This covers the non-escaping case fully.

#### S8.6.1a — Direct PAP heap-payload ownership: escaping use-after-free (do not land as-is)
- **Discovered by:** Codex review of the S8.6.1 working tree (2026-05-31). Confirmed real with
  ASan.
- **The bug:** when a Direct move lambda with a heap capture is UNDERSATURATED, the call site
  builds a heap partial-application (PAP) env via `emitDirectPartialClosure`
  (`codegen/emitter/expression/Applications.scala:252`). That env stores the captured operand
  **borrowed**, and the PAP closure value can ESCAPE the binder scope (be returned / outlive it).
  The S8.6.1 binder-scope free then frees the capture while the escaped PAP still points at it
  → heap-use-after-free. Before S8.6.1 this same shape merely LEAKED; S8.6.1 turns the leak into
  a UAF, which is worse. The mem harness missed it because `tests/mem/escaping-paps.mml` captures
  only `Int`, never a heap value.
- **ASan evidence:**
  - In-scope PAP — `let msg = "hello"; let f = ~{ x: Int, y: Int -> println msg; }; let g = f 1; g 2;`
    inside `main`. ASan CLEAN (prints `hello`, exit 0). My free frees the binder clone; the env
    free and the data free hit different allocations; PAP consumed before scope exit.
  - Escaping PAP — `fn make(): Int -> Unit = let msg = "hello"; let f = ~{ x: Int, y: Int -> println msg; }; f 1; ;`
    then `main`: `let g = make (); g 2;`. ASan ABORTS with heap-use-after-free: `make` frees the
    clone at its scope exit, then `main` calls the escaped `g`.
- **Root cause detail:** `renderDirectPartialEnvFree`
  (`codegen/emitter/expression/Applications.scala:449`) is only
  `call void @mml_free_raw(ptr %0)` — it frees the env buffer but NEVER deep-frees the heap
  capture fields stored in the env. So the PAP env does not own its captures; ownership is
  ambiguously shared with the binder scope. The escaping PAP closure IS dropped by the caller
  (`papescape___free_closure(%3)` in the escaping case's `main`), so a destructor-side deep-free
  WOULD run.
- **Rejected implementation direction — clone-per-PAP:**
  -- IMPORTANT: this should have never happened and needs to be rectified asap.
    - this happened for ignorig the language reference, memory model and related documents.
  1. Keep S8.6.1's binder-scope free unchanged (the binder owns its single capture clone).
  2. In `emitDirectPartialClosure`, deep-CLONE each heap-typed payload (`__clone_T`) into the
     PAP env instead of storing the borrowed operand, so each PAP owns an independent copy.
     This applies to heap captures and heap applied arguments.
  3. In the PAP env destructor (`renderDirectPartialEnvFree`), deep-free the heap payload fields
     (GEP + load + `__free_T`, ABI-lowered like `emitEnvHeapFieldFrees`) before `mml_free_raw`.
     The destructor renderer threads `CodeGenState` for ABI lowering, free-fn declarations, and
     register numbering instead of returning a static string.
- **Author direction:** clone-per-PAP is wrong. PAP creation must not insert hidden heap clones.
  S8.6.1b replaces this approach with explicit PAP ownership/lifetime rules.

#### S8.6.1b — PAP ownership without implicit cloning (landed)
- **Status:** Landed. This replaces the clone-per-PAP direction in S8.6.1a. PAP creation does not
  silently clone heap payloads into a PAP env.
- **Spec:** `context/specs/pap-ownership-model.md`.
- **Bug:** generated PAP envs currently lack an explicit ownership/lifetime model for heap
  payloads. A PAP over a borrowed heap argument or borrowed Direct trailing payload is valid only
  while the owner is alive. If the PAP escapes, the borrow escapes. Cloning the payload hides that
  lifetime error and violates the source-level ownership model.
- **Rules to implement:**
  - Non-consuming parameters are borrowed. A PAP storing such a heap argument may not escape the
    owner scope.
  - Consuming `~` parameters are moved. A PAP storing an already-applied consuming heap argument may
    own that value only through the explicit move.
  - Partial application with any remaining consuming parameter stays rejected.
  - No implicit clone may be inserted by PAP creation to make ownership work.
- **Landed scope:**
  1. Direct PAP clone-per-payload behavior is removed. PAP env creation stores the actual borrowed
     or moved operand.
  2. Ownership analysis records borrowed heap env state for lambda/PAP values and rejects escaping
     PAPs that contain borrowed heap payloads.
  3. Already-applied consuming heap arguments move into the PAP env; the source binding is marked
     moved at PAP creation.
  4. Direct PAP env destructors free owned heap fields when the PAP is dropped before full
     application. When the PAP is fully applied, owned fields are forwarded to the consuming callee
     and the env destructor slot is rewritten to raw-env-only cleanup.
  5. Partial application with any remaining consuming parameter stays rejected. Scalar payload
     behavior and saturated Direct calls are unchanged.
- **Tests:**
  - Non-escaping PAP over borrowed heap argument accepted when proven local.
  - Escaping PAP over borrowed heap argument rejected.
  - Escaping Direct PAP over borrowed heap trailing payload rejected.
  - Partial application with a remaining consuming parameter still rejected.
  - If moved already-applied heap arguments are accepted, use-after-move and exactly-once destructor
    behavior are pinned.
  - Generated PAP creation IR contains no implicit heap clone calls.
- **Acceptance:** S8.6.1a's borrowed escaping UAF shape is rejected at semantic time unless
  rewritten so the PAP owns the heap payload through explicit `~` ownership transfer. PAP creation
  IR contains no implicit heap clone calls, and owned PAP env fields are freed exactly once across
  both drop-before-call and full-application paths.

- **Build / QA:**
  - [ ] Wrap lines over 100 cols added by this workstream; scalafmt does not reflow long
    string/comment literals. Known spots: the Direct-lambda bug-guard message in
    `ExpressionCompiler.scala` (~154 cols), the TypeChecker-bug messages in
    `Conditionals.scala`, and the `TypeVariable.constraints` comment in `types.scala`.
    Run the style pass and fix the rest by hand.

- **Coverage to fold into S9:**
  - [ ] Over-application of a Direct callable. Supplying more args than the callable's
    arity falls through to `compileDirectCall`; add a case proving it lowers and runs.
  - [ ] A `NullEnv` function value passed to a consuming `~f` param. Ownership schedules
    `__free_closure`, which the null-env guard turns into a no-op. Pin a test so this
    stays sound.

- **Docs:**
  - [x] Reconcile S8.5. Its "Remaining" listed PAP-env TBAA parity as open, but the
    changelog marks S8.5b complete (commit 5da9c68). Update S8.5 to match.

- **Already tracked, not repeated here:** `__stmt` sequence-lambda marker (#265).
- **Sub-issue?** No — review cleanup gate before S9.

#### Nullary lambda heads in immediate application (landed)
- **Status:** Landed. `App(Lambda(params = Nil, body), ())` is accepted by the
  immediate-lambda typechecking path.
- **Bug:** nullary lambda heads in immediate application fell through to normal application,
  where `determineApplicationType` has no `Lambda` arm and reported `InvalidApplication`.
- **Landed scope:**
  1. `TypeChecker.scala` dispatches every lambda head to immediate-lambda checking.
  2. Nullary lambdas check their explicit `Unit` argument and receive a `Unit -> T` type.
  3. Parameterized immediate lambdas keep their existing argument-first inference behavior.
- **Tests:** `MaterializationAnalyzerTests.scala` runs the
  `"nullary lambda literal in immediate application is direct"` regression.
- **Sub-issue?** No — small bugfix before S9.

#### Items Pending Review

- [ ] **Nullary immediate lambdas need codegen support.** Typechecking accepts
  `({ 42; } ())` by routing every `App(Lambda(...), ...)` through immediate-lambda
  checking, including zero-parameter lambdas. Codegen still sends lambda heads through
  `compileLambdaApp`, whose supported shape is one lambda parameter and one argument.
  Either codegen must lower nullary immediate lambdas correctly, or typechecking must
  reject the shape until codegen supports it.

- [ ] **Direct partial-application arity must count non-void applied arguments.**
  Direct callables with `Unit` parameters currently compare source argument count
  against the non-void LLVM parameter count. Since `compileArgs` drops `Unit`
  arguments, a partial call such as `f ()` for `Unit -> Int -> Int` can be treated as
  saturated instead of building a PAP closure. The partial-call decision must use the
  same non-void argument accounting as lowering.

- [ ] **PAP field ownership must align with non-void params.** Direct PAP creation zips
  already-compiled arguments, which have had `Unit` arguments removed, with the full
  source parameter list. After a leading `Unit` parameter, a stored heap argument can
  be paired with the wrong `FnParam`, so consuming ownership metadata and destructor
  generation can be assigned to the wrong field. Filter or otherwise align the
  parameter metadata to the compiled non-void argument list before building PAP fields.

### S9 — Equivalence test pass
- **Goal:** the spec's success criterion — same MML expressed as top-level fn / local
  fn / let-bound lambda / lambda literal produces equivalent type, ownership, IR, and
  runtime behavior.
- **Files:** NEW test files under
  `modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/` and
  `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/`; new sample MML under
  `mml/samples/lambda-forms/` (one shape per form).
- **Acceptance:** pair-wise equivalence assertions for at least 4 canonical shapes;
  tests fail meaningfully if a future change reintroduces a structural branch.
  **S9 is the workstream's soundness gate**: full test suite (`sbtn test`) green; mem
  tests (`./tests/mem/run.sh all`) green; benchmarks (`make -C benchmark mml`) build.
  Failures here block #255 closure.
- **Sub-issue?** Yes.

### S10 — `BindingMeta` reduction
- **Goal:** Q4's trim. Remove `destructorKind` from `BindingMeta`; relocate destructor
  info onto env-struct metadata only.
- **Files:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala`
  (`BindingMeta` L174–L183); every reader of `destructorKind`.
- **Acceptance:** S9's full-suite green state is preserved; `BindingMeta` field count
  drops by one; `rg "destructorKind"` shows no reads in semantic phases (env-struct
  metadata is the only home).
- **Sub-issue?** No — small, mechanical.

### S11 — Stack-promotion for non-escaping move-capturing lambdas
- **Goal:** when a move-capturing closure value is owned by a binding that does not
  outlive the current function's stack frame, allocate its environment on the stack
  (`alloca`) instead of the heap. Phase-1 allocation (S7) keeps `malloc` as the default
  for `isMove == true`; this slice introduces the stack-promotion optimization on top.
- **Why now (vs deferred):** with S5 in place, the ownership analyzer already
  classifies lambda values via the generic ownership rules and discovers each binding's
  fate (return, struct-sink, `~` transfer, etc.) at the use site. The frame-local
  property is a direct read of that analysis. Doing this inside #255 keeps the
  unification's mechanical follow-throughs together; deferring it leaves dead `malloc`
  in code that already has the analysis to choose better.
- **Frame-local property:** an owned heap binding is *frame-local* iff none of its
  use sites transfer ownership out of the current function — no return, no struct
  sink, no `~` transfer to an outbound parameter, no escaping store. Exposed by
  `OwnershipAnalyzer` either as a flag on the binding's ownership state or as a
  query method consulted by codegen.
- **Lowering change:** for move closures owning a frame-local binding, codegen emits
  the env struct on the stack (`alloca`), runs per-field destructors at scope end,
  and skips the env `free`. Heap (`malloc`) lowering is retained for everything else
  — same destructor invocation, plus `free`.
- **Files:**
  - `OwnershipAnalyzer.scala`: derive frame-local property for owned heap bindings;
    add a public reader for codegen.
  - `ExpressionCompiler.scala` (`emitCallSiteEnv` L518–L659): branch on frame-local
    when emitting move-closure env allocation.
  - `FunctionEmitter.scala` (`emitEnvHeapFieldFrees` L203–L243): stack-promoted move
    closures still run per-field destructors but skip the env `free`.
  - `ClosureMemoryFnGenerator.scala` (`mkFreeFunction` L189–L257): per-env destructor
    generation does not need to change; the difference is only the call-site choice
    between `free(env)` and "just run destructors."
  - NEW mem tests under `tests/mem/`: stack-promoted move closure passes ASan/LSan;
    same closure pattern that escapes still uses `malloc` and survives.
- **Acceptance:**
  - all existing mem tests (`./tests/mem/run.sh all`) pass
  - new stack-promotion mem tests pass under ASan and LSan
  - IR snapshots updated for stack-promoted move-closure paths
  - benchmarks (`make -C benchmark mml`) build and run
  - `rg "malloc.*closure"` in codegen shows the heap path only when frame-local is
    false
- **Sub-issue?** Yes — non-trivial optimization with mem-safety implications.

---

## Open follow-ups after approval

- Add a `## Decisions` section to `context/specs/unify-lambdas.md` mirroring the
  approved answers above.
- Create GH sub-issues for slices marked `Sub-issue: Yes` (S3, S4, S5, S6, S8, S9, S11)
  and add each to project `fedesilva/projects/3` via `bin/gh-issue-*` +
  `bin/gh-project-item-add`. S2 and S10 are small mechanical changes and do not need
  their own sub-issues.
