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

### S4 — Ownership: non-capturing / null-env function values stop being treated as owned heap  *(in progress)*
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
- **S4 landed scope (delta vs Acceptance above):** the lambda-value ownership predicate
  (`isOwnedLambdaValue`) gates on `!isDirect && captures.nonEmpty && isMove` and is used
  at the lambda-arm classifiers (`termReturnsOwned`, `lambdaAllocates`). All other
  type-driven ownership sites route through `isOwnedValueType` (heap OR `TypeFn`) so
  type-only behavior at those sites is preserved. The consuming-param body-end free,
  capture-ownership, and borrow-escape-return all stay on `isOwnedValueType` so the
  materialized move-closure cleanup path (`tests/mem/consume-closure.mml`) is preserved
  and borrow-closure escape through a TypeFn forwarder remains a hard error.
  The "consuming param + non-owned value → no `__free_closure`" and
  "no `BorrowEscapeViaReturn` for borrowed `TypeFn` returns" goals are *deferred to S5*
  because the analyzer needs source-aware lambda-value classification to tell which
  function-values are owned closures versus non-capturing references without
  re-introducing the materialized-closure leak. The direct move-closure leak (`let f =
  ~{...}; f 41`) is the documented S6 carry-over (codegen still mallocs the env).
- **Sub-issue?** Yes.

### S5 — Ownership: treat lambda values as ordinary unique values  *(in progress)*
- **Goal:** kill the structural branches on top-level vs let-bound vs literal *and* the
  parallel closure-specific escape machinery. Lambda values participate in the generic
  ownership analyzer.
- **S5 landed scope (delta vs the rest of this section):**
  - The return-escape walkers (`returnedBorrowedRefs`, `returnedBorrowClosures`) are
    unified at the call site through a single `ReturnEscape` result type. `RefEscape`
    dispatches to `BorrowEscapeViaReturn` (gated on owned return type) and
    `LambdaEscape` to `BorrowClosureEscapeViaReturn` (unconditional).
  - Both walkers now descend through administrative `App` wrappers
    (`let x = …; …` and `fn x …;; …`) via a tightened `returnsBindingParam`. Multi-level
    aliasing patterns like `let x = s; let y = x; y` are flagged at any nesting depth for
    both Ref and Lambda shapes — closing a preexisting analyzer hole the closure walker
    half-fixed and the ref walker missed entirely.
  - New regression tests cover single-level wrap, conditional inside wrap, two-level
    nesting (both Ref and Lambda shapes), and the negative gate.
  - The capture-ownership block at the `analyzeLambda` site is already generic over
    `isOwnedValueType`; verified by inspection, no code change.
- **S4 carry-over (deferred to S6):** tightening the consuming-TypeFn-param body-end free
  so the scheduled `__free_closure(f)` only fires when the value is *known to be* a
  materialized move-closure requires either caller-side cleanup emission or scope-info
  propagation that this slice cannot land cleanly without breaking
  `tests/mem/consume-closure.mml`. The runtime null-guarded no-op behavior is acceptable
  in the interim; S6's codegen rework is the natural home for the change.
- **Lambda value ownership classification:**
  - move-capturing closure value (`!isDirect && captures.nonEmpty && isMove`) →
    owned heap value; ordinary owned-heap rules apply at return / `~` transfer /
    struct-sink / consuming-param sites
  - borrow-capturing closure value (`!isDirect && captures.nonEmpty && !isMove`) →
    borrowed value; ordinary borrowed-value rules apply
  - non-capturing lambda value (`!isDirect && captures.isEmpty`) → borrow-only at the
    ownership layer; no owned env, nothing to free
  - direct lambda (`isDirect`) → scope-only; not a value, never classified
- **Files:** `OwnershipAnalyzer.scala` — collapse the closure-specific entry points
  (`returnedBorrowClosures` L681; capture-heap analysis around L1446 for
  `CapturedBorrowedHeapBinding`; escape rules through `TypeFn` returns;
  `BorrowClosureEscapeViaReturn`) into the generic return-position, consuming-param,
  and struct-sink checks. The lambda value's ownership classification feeds those
  checks the same way any other value's classification does.
- **Rule:** structural shape of the binding does not matter; classification of the
  lambda value drives the analyzer. Existing diagnostics
  (`BorrowClosureEscapeViaReturn`, `CapturedBorrowedHeapBinding`,
  `BorrowedValuePassedToConsumingParam`) stay in the diagnostic set as **specialized
  rendering** of the generic ownership errors — the user still sees closure-specific
  phrasing where it helps, but the underlying check is the generic one.
- **Acceptance:** ownership unit tests pass for all four lambda forms (top-level,
  local-fn, let-bound, literal); existing ownership-error fixtures still produce the
  same error variants on the same input programs (closure-specific phrasing
  preserved); no closure-specific entry point remains in `OwnershipAnalyzer.scala`
  that is not also a thin specialization of a generic check. Equivalence tests at S9
  are the final cross-form gate.
- **Sub-issue?** Yes.

### S6 — Codegen: derive direct-vs-closure entry from demand  *(in progress)*
- **Goal:** Q5 in codegen. One source of truth replaces the scattered structural
  reasoning.
- **S6 landed scope so far (Phase 6.2):**
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
- **S4 carry-over (mandatory at S6 sign-off):**
  - Un-ignore `ClosureCodegenTest` "local move capturing closures free through their
    specific env destructor". The fixture exercises a non-direct move-closure and must
    pass against the reshaped closure-call lowering.
  - Close the direct move-closure leak introduced by S4's `isOwnedLambdaValue`
    predicate. S4 gates owned-env tracking on `!isDirect`, so `let f = ~{...}; f 41;`
    no longer registers an owned env at the analyzer level; the current codegen still
    materializes and mallocs the env, so the allocation leaks. S6's lowering rule for
    `isDirect` must emit *no env materialization* (direct entry only, no wrapper, no
    `malloc`) so the analyzer and codegen agree.
  - Move `tests/mem/direct-move-closure.mml` from "expected to fail under ASan/LSan"
    to a green pass. This file is the pinned regression for the bridge between S4
    and S6; the mem harness (`./tests/mem/run.sh all`) is the verification gate. While
    this file remains in the harness with the leak intact, mem runs report a 1-test
    failure — that failure disappears the moment S6 lands its lowering rule for
    direct lambdas.
- **Sub-issue?** Yes — large blast radius.

### S7 — Codegen: env allocation rule consumes `isMove`
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
- **Sub-issue?** Optional — can fold into S6 if blast radius stays manageable.

### S8 — Tail-recursion follow-up under unified model
- **Goal:** TCO/loopification consults materialization metadata.
- **Files:** `FunctionEmitter.scala` (`findTailRecBody` L1005, `extractBody` L1018,
  `isSelfRef` L1127, `extractSelfCallFromAccumulated` L1077); `ExpressionCompiler.scala`
  (`compileTailRecLambdaLiteral` L201, `compileTailRecCapturingLambda` L300,
  `emitRecursiveSelfClosure` L446, `lambdaReferencesBinding` L466).
- **Acceptance:** TCO unit tests and loopified borrow-closure regressions pass against
  the post-S6/S7 IR (snapshots refreshed as needed); `compileTailRecCapturingLambda`
  reuses the shared env setup; immediate-application tail-recursive lambdas validated
  against the unified pipeline.
- **Sub-issue?** Yes.

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

## Out of scope

- Implementation of any slice (this doc is the plan, not the work).
- Resolving items in `context/specs/lambdas-work-review.md` beyond noting which slice
  subsumes which. Most P1s already closed in changelog; remaining P1 "Stop freeing
  non-capturing function values as closures" is closed by S4; P2/P3 items not addressed
  unless they fall naturally into a slice.
- GH project mutations (creating sub-issues / project-add). Held until Author approval.

## Open follow-ups after approval

- Add a `## Decisions` section to `context/specs/unify-lambdas.md` mirroring the
  approved answers above.
- Create GH sub-issues for slices marked `Sub-issue: Yes` (S3, S4, S5, S6, S8, S9, S11)
  and add each to project `fedesilva/projects/3` via `bin/gh-issue-*` +
  `bin/gh-project-item-add`. S2 and S10 are small mechanical changes and do not need
  their own sub-issues.
