# Centralize closure layout and capture contracts

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:closure-layout`, `component:destruction`, `concern:shared-contracts`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Define bounded shared contracts for the header, destructor identity, and capture ownership.
2. [ ] **planned** — Migrate producers and consumers with field/identity tests and ownership verification.

## Problem

### D1: centralize closure environment header layout

**Severity:** medium. The `isMove` bit and `.drop(1)` interpretation are repeated across
`ClosureEnvironmentLayout`, `ExpressionCompiler`, `FunctionEmitter`,
`ClosureDestructorBodyGenerator`, and `DestructionValidator`; the layout comment
overstates sharing.

### D2: centralize closure destructor identity

**Severity:** medium. `__free_closure` and `__free_${env}` are re-derived in multiple
phases; `DestructionTargets.named` falls back to any matching name. A rename could
silently omit cleanup in `OwnershipAnalyzer`. Use a typed or centralized contract.

### D7: unify borrowed-capture representation

Define one source of truth for borrowed captures. `LambdaMeta.borrowedCaptures` and
`Capture.BorrowedRef` both encode them, with consistency dependent on phase order.

## Outcome

Environment layout, destructor identity, and borrowed captures have explicit shared contracts.

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
