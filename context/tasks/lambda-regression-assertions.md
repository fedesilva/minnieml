# Strengthen lambda regression assertions

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:semantic-tests`, `component:memory-tests`, `concern:test-quality`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Add observable result assertions to memory fixtures that only print.
2. [ ] **planned** — Strengthen semantic failure assertions with diagnostic class, binding identity, and source/move locations.
3. [ ] **planned** — Run the changed suites and fixtures without weakening their existing intent.

## Problem

### T2: assert memory fixture results

**Severity:** medium. [tests/mem/run.sh](../../tests/mem/run.sh) checks exit status and
discards output. Seventeen newer lambda fixtures have `Unit` entry points and only print,
so incorrect values can pass. Give those fixtures observable assertions or expected-output
checks while preserving their memory checks. Existing `pap-*`, `mixed-ownership-*`, and
`owned-closure-tail*` fixtures already check results through exit codes.

### T3: assert use-after-move identities and locations

**Severity:** medium. `UseAfterMove` assertions in `PapStructOwnershipTest`,
`PapOwnershipTest`, `MoveCaptureConsumptionTest`, and `DeferredPapOwnershipTest` check
only the error class. Assert binding identity, use site, and `movedAt`, following the
stronger `LambdaLiteralBorrowingTests.assertOnlyMove` pattern. The focused ownership-diagnostic suite already covers the field-alias case; broader assertions remain weak.

### T4: replace bare semantic-failure assertions

**Severity:** low to medium. Bare `semFailed` assertions in `PapOwnershipTest` and
`PapStructOwnershipTest` accept unrelated errors. Assert the intended diagnostic and
source location, including generated-argument source locations.

## Outcome

Existing fixtures establish their intended values, semantic identities, and diagnostics.

## Scope

The component and concerns described above are independent backlog work, outside Unify lambdas
completion and merge requirements. Preserve language semantics, ownership enforcement, evaluation
order, and runtime behavior; any discovered correctness defect needs separate assessment.

## Plan (Approval Gate)

Prepare a bounded implementation plan from the checklist and embedded evidence.
Approval: task definition and deferral authorized; implementation plan pending.

## Verification

The evidence above comes from source inspection and the lambda branch review. No repair or
verification for this task is complete. Run the applicable repository checks when implementing it.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Deferred at LOW priority. Next action: prepare the bounded plan when this task is selected.
