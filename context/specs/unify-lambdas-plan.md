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

Status: **draft for Author review.** Nothing here is committed to until reviewed.

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

**Proposed: two fields on `LambdaMeta`, computed by a new pass.** Acted on by **S3**.

```
enum Escape:
  case NonEscaping
  case EscapesAsParam     // passed as HO argument
  case EscapesAsReturn    // returned
  case EscapesToStore     // stored in struct / tuple / aggregate

// LambdaMeta gains:
//   isDirect: Boolean        // never used as a value — scope-only / immediate-application
//   escape:   Escape         // meaningful when !isDirect; cached for downstream consumers
//
// Lambda.isMove: Boolean     // unchanged — the borrow-vs-move marker stays here
// Lambda.captures            // unchanged — semantic list of referenced bindings
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

**Multi-use join rule.** When a lambda value has multiple use sites:

- `isDirect` joins by AND: starts `true` and flips to `false` on the first value-position
  use (HO argument, return, store, alias).
- `escape` joins by max on the lattice
  `NonEscaping < EscapesAsParam < EscapesAsReturn < EscapesToStore`.

A lambda used once as a direct call and once stored into long-lived data joins to
`isDirect = false, escape = EscapesToStore` and lowers as a Materialized closure. Phase-1
allocation (S7) reads `Lambda.isMove` only; `escape` is recorded for ownership escape
checks (today's `BorrowClosureEscapeViaReturn` and friends) and for the deferred S11
stack-promotion work.

New pass `MaterializationAnalyzer` runs between `CaptureAnalyzer` and
`ClosureMemoryFnGenerator`. Ownership analysis and codegen read `LambdaMeta.isDirect`
and `LambdaMeta.escape`; neither re-derives.

**Why:** ownership needs to know whether to insert a `free` at scope end (depends on
whether an owning env exists). Encoding this as semantic metadata (not codegen-local
demand analysis) keeps the rules visible and unit-testable without running codegen.
Splitting into two minimal axes (`isDirect`, `escape`) keeps the storage tight: anything
derivable from `captures` is derived, not stored.

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
- `escape` is recorded by S3 and available to consumers (ownership escape checks read
  it), but is **not** consulted by the allocation rule in phase 1.

Stack-promotion for non-escaping move closures (`isMove == true` AND
`escape == NonEscaping` → `alloca`) is the deferred slice S11 — tracked but not
scheduled as part of #255. This keeps unification PRs about *unifying*, not about
optimization.

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

### S0 — Decisions section in design spec
- **Goal:** lock the 6 answers above into the spec.
- **Files:** `context/specs/unify-lambdas.md` (append `## Decisions`).
- **Acceptance:** section exists; each decision cites the slice that acts on it.
- **Tracking:** checklist item on #255.

### S1 — Terminology cleanup
- **Goal:** retire "real closure literal" language; speak the spec's vocabulary across
  docs and comments.
- **Files:** `docs/design/compiler-design.md`, `docs/memory-model.md`,
  `docs/language-reference.md`; comments in `CaptureAnalyzer.scala`,
  `ClosureMemoryFnGenerator.scala`, `OwnershipAnalyzer.scala`.
- **Acceptance:** `rg "real closure"` returns no hits in semantic/codegen comments or
  docs; terminology aligns with `unify-lambdas.md`.
- **Sub-issue?** No — checklist item.

### S2 — AST: add materialization metadata (`isDirect`, `escape`)
- **Goal:** mechanical AST change from Q2. Add the `Escape` enum and two new fields on
  `LambdaMeta` (`isDirect: Boolean`, `escape: Escape`). `Lambda.captures` and
  `Lambda.isMove` stay as they are — no rename, no split. No new analysis logic in this
  slice; defaults of `isDirect = false` and `escape = NonEscaping` mean S2 keeps current
  behavior intact (every lambda still materializes, every closure still escapes safely
  per existing rules). Closure tests should still pass after S2.
- **Files:**
  - AST: `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala` (`LambdaMeta`
    L91–L94 — add `isDirect: Boolean = false`, `escape: Escape = Escape.NonEscaping`;
    new `Escape` enum next to `LambdaMeta`).
  - Optional small derivation helper (e.g. `Lambda.materialization: Materialization`
    method or a top-level helper) that returns Direct / NullEnv / Materialized from
    `(meta.isDirect, captures.isEmpty)`. Add only if S3+ consumers actually need it; do
    not over-engineer here.
  - No reader/writer churn elsewhere — `captures` and `isMove` are untouched.
- **Acceptance:** code compiles end to end; full test suite remains green (this is a
  pure additive change with defaults that preserve current behavior). No new tests in
  this slice.
- **Sub-issue?** No — small, additive.

### S3 — `MaterializationAnalyzer` pass
- **Goal:** Q2 + Q3 + Q5. New pass walks every lambda scope, computes `isDirect` and
  `escape`, and writes them back to `LambdaMeta`. Implements the multi-use join rule
  from Q2 (AND-join on `isDirect`; lattice-max on `escape`). `CaptureAnalyzer` is
  unchanged.
- **Files:**
  - NEW: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/MaterializationAnalyzer.scala`.
  - Pipeline wiring: insert new pass between `CaptureAnalyzer` and
    `ClosureMemoryFnGenerator` (which from S4 onward consumes the metadata to decide
    lowering shape).
  - Tests: new `MaterializationAnalyzerTests.scala` covering Direct (immediate
    application, scope-only let / sequencing), NullEnv (non-capturing fn passed as
    value), Materialized (lambda with captures used as value); each `Escape` variant;
    multi-use join cases; resolved-id-based assertions.
- **Acceptance:** new pass tests pass. Existing closure tests still pass because no
  consumer has been migrated yet — `isDirect`/`escape` are written but ignored. No
  behavioral change observable from outside `MaterializationAnalyzer`.
- **Sub-issue?** Yes.

### S4 — Ownership: non-capturing / null-env function values stop being treated as owned heap
- **Goal:** close the open P1 from `lambdas-work-review.md` ("Stop freeing non-capturing
  function values as closures").
- **Files:** `OwnershipAnalyzer.scala` (TypeFn ownership rule around L256–L259 per the
  QA doc's reference; `analyzeLambda` L1350; consuming-param flows).
- **Rule:** only lambdas with `!isDirect && captures.nonEmpty && isMove` (i.e. real
  materialized move closures) are tracked as owned heap by ownership analysis. Direct
  lambdas, NullEnv values, and borrow-capturing materialized lambdas are not freed at
  scope end and are not passed to `__free_closure`.
- **Acceptance:** new ownership regressions pass — consuming param receives a top-level
  fn ref; consuming param receives a non-capturing lambda literal; HO param receives a
  non-capturing closure; old `__free_closure(f)` crash path no longer triggers. Mem
  tests for owned move-capturing closures (e.g. `closure-capture.mml`,
  `closure-heap-capture.mml`) must still pass; codegen-IR-snapshot tests may shift and
  are refreshed at S6/S7.
- **Sub-issue?** Yes.

### S5 — Ownership: unify borrow/move/escape across lambda forms
- **Goal:** kill the structural branches on top-level vs let-bound vs literal.
- **Files:** `OwnershipAnalyzer.scala` (`returnedBorrowClosures` L681; capture-heap
  analysis around L1446 for `CapturedBorrowedHeapBinding`; escape rules through
  `TypeFn` returns; `BorrowClosureEscapeViaReturn`).
- **Rule:** route every decision through `LambdaMeta.isDirect`, `LambdaMeta.escape`,
  and `Lambda.isMove`; structural shape of the binding does not matter. Existing
  ownership diagnostics (`BorrowClosureEscapeViaReturn`, `CapturedBorrowedHeapBinding`,
  `BorrowedValuePassedToConsumingParam`) stay in the diagnostic set, but their
  *triggers* are derived from `escape` and `captures`, not from structural pattern
  checks on the AST shape.
- **Acceptance:** ownership unit tests pass for all four lambda forms (top-level,
  local-fn, let-bound, literal); existing ownership-error fixtures still produce the
  same error variants on the same input programs. Equivalence tests at S9 are the final
  cross-form gate.
- **Sub-issue?** Yes.

### S6 — Codegen: derive direct-vs-closure entry from demand
- **Goal:** Q5 in codegen. One source of truth replaces the scattered structural
  reasoning.
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
- **Sub-issue?** Yes — large blast radius.

### S7 — Codegen: env allocation rule consumes `isMove` (escape recorded but unused)
- **Goal:** the rule that decides `alloca` vs `malloc` reads `Lambda.isMove` (already
  present), explicitly *not* gated on `escape`. This slice mostly verifies that the
  existing `isMove` plumbing remains the single allocation gate after S3–S6 land, and
  documents the deferred S11 stack-promotion path.
- **Phase-1 allocation rule** (the only rule shipped in this slice):
  - `isMove == false` → `alloca`
  - `isMove == true`  → `malloc`
  - `escape` is recorded by S3 (and read by S5 for ownership escape checks) but does
    not gate allocation here.
  - Stack-promotion for non-escaping Move (`isMove == true` AND
    `escape == NonEscaping` → `alloca`) is deferred to **S11**.
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

### S11 — DEFERRED — Stack-promotion for non-escaping move-capturing lambdas
- Tracked-only. Not part of #255 closure. Re-spec as a follow-up workstream once
  S0–S10 are stable.

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
- Create GH sub-issues for slices marked `Sub-issue: Yes` (S3, S4, S5, S6, S8, S9) and
  add each to project `fedesilva/projects/3` via `bin/gh-issue-*` +
  `bin/gh-project-item-add`. S2 is now a small additive change and does not need its
  own sub-issue.
