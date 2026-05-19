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

**Proposed: split.** Acted on by **S2**.

- Add `Lambda.freeVars: List[Ref]` — pure semantic free-variable facts. Populated by
  `CaptureAnalyzer` for every lambda scope, regardless of whether an env is ever
  materialized.
- Rename current `captures: List[Capture]` → `envFields: Option[List[Capture]]`.
  Populated by demand analysis (S3): `None` when no env needed (immediate application,
  null-env materialization), `Some(_)` when an env must be built. `CapturedRef` /
  `CapturedLiteral` live only inside `envFields` (literal-clone is a lowering hint,
  not a semantic fact).

**Why:** today `captures` is read by both ownership (semantic) and codegen (lowering).
The mix forces ownership to peek at lowering hints (`CapturedLiteral`) it does not care
about, and forces codegen to re-derive semantic facts each time. Splitting kills both
leaks.

### Q2 — Where should materialization requirements be recorded?

**Proposed: new semantic metadata on `LambdaMeta`, computed by a new pass.** Acted on by **S3**.

Four orthogonal axes, each on its own:

```
enum Materialization:
  case Direct        // never used as a value; immediate application only
  case NullEnv       // used as a value but no FVs
  case Materialized  // FVs present; an env must be built

enum Escape:
  case NonEscaping
  case EscapesAsParam
  case EscapesAsReturn
  case EscapesToStore

enum CaptureMode:
  case Borrow
  case Move
// Per-lambda for now (mirrors current Lambda.isMove). Per-capture (Mixed) is a
// possible future refactor, out of scope here.

// LambdaMeta gains:
//   materialization: Materialization
//   escape:          Escape       // meaningful when materialization == Materialized
//   captureMode:     CaptureMode  // meaningful when freeVars.nonEmpty
// Lambda gains (from Q1):
//   envFields: Option[List[Capture]]  // Some only when materialization == Materialized
```

**Multi-use join rule.** When a lambda value has multiple use sites, materialization and
escape are joined conservatively (take the maximum on these lattices):

```
materialization:  Direct  <  NullEnv  <  Materialized
escape:           NonEscaping  <  EscapesAsParam  <  EscapesAsReturn  <  EscapesToStore
```

So a lambda used once as a direct call and once stored into long-lived data joins to
`Materialized` + `EscapesToStore`. Phase-1 allocation (S7) only reads the two-valued
projection `NonEscaping vs Escapes-any`; the finer lattice is forward-looking for
stack-promotion (S11) and any later policy splits.

New pass `MaterializationAnalyzer` runs between `CaptureAnalyzer` and
`ClosureMemoryFnGenerator`. Ownership analysis and codegen read the result; neither
re-derives.

**Why:** ownership needs to know whether to insert a `free` at scope end (depends on
whether there is an owning env). Encoding this in semantic metadata (not codegen-local
demand analysis) keeps the rules visible and unit-testable without running codegen — and
gives ownership a clean source of truth. Splitting into four axes keeps each one
decidable on its own and avoids overloading a single enum constructor with mixed
concerns.

### Q3 — Can immediate lambda application always avoid materialization?

**Proposed: yes, with one principled exception.** Acted on by **S3**.

Operational rule: a lambda needs materialization iff *any* reference to its binding (or
to it directly) occurs in non-application position, is passed as a HO argument, or
appears in escaping position. Immediate-application shape `App(Lambda(...), arg)` is
preserved through ownership wrappers (the wrapper wraps the *result*, not the lambda).

Exception: if the lambda is bound and the binding itself is used as a value
(`let f = { x -> x }; f`), the *binding occurrence* triggers materialization, not the
lambda literal. Demand analysis (Q2) computes this.

**Why:** keeps the common case (let / sequencing / direct call) zero-cost and the rule
mechanical.

### Q4 — Should top-level and local functions share exactly the same `BindingMeta`?

**Proposed: same shape, but semantic phases never branch on origin.** Acted on by **S10**.

Keep `origin: BindingOrigin` (TopLevel | Local | Inner) on `BindingMeta` for
diagnostics, source positions, and codegen entry-point naming. *Enforce* that semantic
phases (capture, materialization, ownership, type checker) never branch on `origin` for
behavior — they branch on demand/escape/materialization facts.

Move `destructorKind` off bindings; it belongs only on env structs, and only when an
env actually exists. Trim `BindingMeta` accordingly.

**Why:** origin is real (source-level distinction) and worth keeping for tooling, but it
must not be a hidden behavioral fork.

### Q5 — How much of direct-entry eligibility should be computed before codegen?

**Proposed: all of it.** Acted on by **S6**.

`LambdaMeta.materialization` (and, for capturing lambdas, `captureMode`) is what codegen
consults. The lowering rules become:

- `Materialization.Direct` → emit direct entry only; no wrapper.
- `Materialization.NullEnv` → emit direct entry + closure-entry wrapper (so first-class
  use works); first-class users see `{ ptr @entry, ptr null }`.
- `Materialization.Materialized` → emit closure entry with env param + optional direct
  entry when at least one statically-known direct call site exists.

Codegen never re-derives "is this called directly?".

### Q6 — Should non-escaping move-capturing lambdas stay heap-backed initially?

**Proposed: yes. Stack-promotion is deferred.** Acted on by **S7**, deferred work in **S11**.

Phase-1 allocation rule (S7) is driven by `captureMode` alone:

- `captureMode == Borrow` → env on stack (`alloca`).
- `captureMode == Move`   → env on heap  (`malloc`).
- `escape` is recorded by S3 and available to consumers, but is **not** consulted by the
  allocation rule in phase 1.

Stack-promotion for non-escaping move-capturing lambdas (`captureMode == Move` AND
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

### S2 — AST: split `Lambda.captures` into `freeVars` + `envFields`; add materialization metadata
- **Goal:** mechanical AST change from Q1 + Q2. No new analysis logic in this slice.
  S2 is **prep, not standalone-valuable** — closure semantics and codegen will be
  intentionally broken between S2 and S3 (every lambda sees `envFields = None` and
  default metadata). Do not try to make S2 pass closure tests in isolation.
- **Files:**
  - AST: `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala` (`Lambda` case
    class L96–L105 — add `freeVars: List[Ref]`, rename `captures` → `envFields:
    Option[List[Capture]]`; `LambdaMeta` L91–L94 — add `materialization:
    Materialization`, `escape: Escape`, `captureMode: CaptureMode`; new
    `Materialization` / `Escape` / `CaptureMode` enums).
  - Semantic readers: `CaptureAnalyzer.scala` (writes `freeVars`; `envFields` stays
    `None` — populated by S3); `OwnershipAnalyzer.scala` (`analyzeLambda` L1350,
    `returnedBorrowClosures` L681, all `.captures` reads renamed to `.envFields`);
    `TypeChecker.scala` (lambda inference paths).
  - Codegen readers: `ClosureMemoryFnGenerator.scala`, `ExpressionCompiler.scala`,
    `Applications.scala`, `FunctionEmitter.scala` — all renamed to read `envFields`.
  - Tests: `TXAstExtractors.scala`, `TXLambdaHelpers.scala` (`captureResolvedIds`)
    updated.
- **Acceptance:** code compiles end to end. Closure-related test failures are EXPECTED
  here and unblock at S3; do not paper over them with shims, defaults, or placeholder
  logic. No new tests in this slice.
- **Sub-issue?** Yes — cross-cuts AST and every consumer.

### S3 — `MaterializationAnalyzer` pass
- **Goal:** Q2 + Q3 + Q5. New pass computes the three metadata axes (`materialization`,
  `escape`, `captureMode`) for every lambda and populates `envFields` when
  `materialization == Materialized`. Implements the multi-use join rule from Q2.
  `CaptureAnalyzer` is reduced to free-variable computation only.
- **Files:**
  - NEW: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/MaterializationAnalyzer.scala`.
  - `CaptureAnalyzer.scala`: simplified — only computes `freeVars`; no env logic.
  - Pipeline wiring: insert new pass between `CaptureAnalyzer` and
    `ClosureMemoryFnGenerator` (which from this slice onward consumes demand to decide
    which lambdas get env structs).
  - Tests: new `MaterializationAnalyzerTests.scala` covering Direct / NullEnv /
    Materialized; each `Escape` variant; both `CaptureMode` values; multi-use join
    cases; resolved-id-based assertions.
- **Acceptance:** new pass tests pass. Closure-related semantic / codegen tests that
  rely on consumers migrated off structural shape (`Lambda.isMove`, "captures is the
  source of truth") may still fail until S4–S7 land; that is expected here. No
  full-suite green claim in this slice.
- **Sub-issue?** Yes.

### S4 — Ownership: non-capturing / null-env function values stop being treated as owned heap
- **Goal:** close the open P1 from `lambdas-work-review.md` ("Stop freeing non-capturing
  function values as closures").
- **Files:** `OwnershipAnalyzer.scala` (TypeFn ownership rule around L256–L259 per the
  QA doc's reference; `analyzeLambda` L1350; consuming-param flows).
- **Rule:** only lambdas with `materialization == Materialized` AND `captureMode == Move`
  are tracked as owned heap by ownership analysis. `Direct`, `NullEnv`, and
  borrow-capturing materialized lambdas are not freed at scope end and are not passed
  to `__free_closure`.
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
- **Rule:** route every decision through `LambdaMeta.materialization` + `Escape`;
  structural shape of the binding does not matter. Existing ownership diagnostics
  (`BorrowClosureEscapeViaReturn`, `CapturedBorrowedHeapBinding`,
  `BorrowedValuePassedToConsumingParam`) stay in the diagnostic set, but their
  *triggers* are derived from demand/escape, not from structural pattern checks on
  the AST shape.
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
- **Lowering rules:** as in Q5.
- **Acceptance:** `ClosureCodegenTest`, `TbaaEmissionTest`, `FunctionSignatureTest`
  refreshed against the new IR shapes and pass; first-class top-level fn passed as HO
  argument lowers as `{ ptr @entry, ptr null }`; direct call to a local lambda with
  statically known args lowers as a direct call without going through the fat pointer.
  IR snapshots may shift — refresh as needed.
- **Sub-issue?** Yes — large blast radius.

### S7 — Codegen: env allocation rule consumes `captureMode` (escape recorded but unused)
- **Goal:** the rule that decides `alloca` vs `malloc` reads `LambdaMeta.captureMode`,
  replacing the structural `Lambda.isMove` check on the AST node. `escape` is recorded
  by S3 and available, but **not** consulted by allocation in phase 1.
- **Phase-1 allocation rule** (the only rule shipped in this slice):
  - `captureMode == Borrow` → `alloca`
  - `captureMode == Move`   → `malloc`
  - `escape` is observed (so call sites can read it) but does not gate allocation.
  - Stack-promotion for non-escaping Move (`captureMode == Move` AND
    `escape == NonEscaping` → `alloca`) is deferred to **S11**.
- **Files:** `ExpressionCompiler.scala` (`emitCallSiteEnv` L518–L659);
  `FunctionEmitter.scala` (entry-block prologue path L166–L170, `emitEnvHeapFieldFrees`
  L203–L243); `ClosureMemoryFnGenerator.scala` (`mkEnvStruct` L142–L180,
  `mkFreeFunction` L189–L257).
- **Acceptance:** mem tests for borrow and move closures pass; `rg "Lambda.isMove"` in
  allocation paths returns no hits (all callers route through `captureMode`);
  `Lambda.isMove` is consumed only by S3 (mapped into `captureMode`) and as a
  source-level surface marker.
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
- Create GH sub-issues for slices marked `Sub-issue: Yes` (S2, S3, S4, S5, S6, S8, S9)
  and add each to project `fedesilva/projects/3` via `bin/gh-issue-*` +
  `bin/gh-project-item-add`.
