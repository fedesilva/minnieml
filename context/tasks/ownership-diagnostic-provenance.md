# Consolidate ownership diagnostic provenance

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:ownership`, `component:partial-application`, `concern:diagnostics`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Reproduce the three shapes and agree the use/dependency provenance contract.
2. [ ] **planned** — Implement the shared provenance and normalization mechanism with discriminating regressions.
3. [ ] **planned** — Verify distinct uses/owners, capture paths, CLI/LSP locations, and repository gates.

## Problem

Review findings F1–F3 expose duplicate and cascading diagnostics, not accepted invalid programs.
`OwnershipAnalyzer.normalizeMoveErrors` keys reports by exact use span. Lowered nested
projections and captured qualifiers can represent one written use with different spans.
Field-alias dependency evidence retains the bare owner span, while its primary error uses
the projection span, so exact matching cannot suppress the downstream cascade.

Use this common fixture prefix:

```mml
struct Holder { f: Int -> Int };
struct Outer { inner: Holder };
fn add(a: Int, b: Int): Int = a + b;;
fn make(n: Int): Holder = let p = add n; Holder p;;
fn drop(~h: Holder): Int = 0;;
fn dropo(~o: Outer): Int = 0;;
fn use(f: Int -> Int, n: Int): Int = f n;;
```

Append one entry at a time:

```mml
fn example(): Int = let o = Outer (make 1); let a = dropo o; o.inner.f 2;;
```

F1: reports `inner` at the `o.inner` span and `f` at `o.inner.f`, both for owner `o`
and the same move. `use o.inner.f 2` and binding `let q = o.inner.f` expose the same shape.

```mml
fn example(): Int = let h = make 1; let a = drop h; let c = { h.f 2; }; c ();;
```

F2: reports `f` at `h.f` and captured `h` at its shorter span, both for the same owner/move.

```mml
fn example(): Int = let h = make 1; let a = drop h; let q = h.f; let x = q 2; q 3;;
```

F3: reports use-after-move at `h.f`, `q 2`, and `q 3`, plus a consuming-last-use diagnostic.
The invalid alias initializer has a primary error, but its dependency span does not match it.
The broad normalization claims in `docs/design/compiler-design.md` exceed these behaviors.

## Outcome

One written invalid use has one report per moved owner; an already-invalid dependency
retains its primary error without redundant cascades. Independent uses and owners remain visible.

## Scope

The component and concerns described above are independent backlog work, outside Unify lambdas
completion and merge requirements. Preserve language semantics, ownership enforcement, evaluation
order, and runtime behavior; any discovered correctness defect needs separate assessment.

## Plan (Approval Gate)

Prepare a bounded implementation plan from the checklist and embedded evidence.
Approval: task definition and deferral authorized; implementation plan pending.

## Verification

The 2026-10-21 review executed these shapes through sbtn run ir on the uncommitted B4
implementation at base f439ffa. Probe logs and source tracing agree on the duplicate shapes.
Baseline duplication was reasoned from source, not executed. Reproduce from the embedded
fixtures when implementing; do not collapse all errors merely because they share an application.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Deferred at LOW priority. Next action: prepare the bounded plan when this task is selected.
