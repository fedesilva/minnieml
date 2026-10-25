# Simplify ownership diagnostic construction and normalization

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:ownership`, `concern:readability`, `concern:shared-helpers`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Coordinate with the provenance task and settle the smallest shared construction boundary.
2. [ ] **planned** — Refactor without changing emitted diagnostics and run identity/location regressions.

## Problem

Findings F8 and F10:
- `MoveSite` contains a use span although “move site” elsewhere names `movedAt`.
- Explain the self-suppression guard and the normalization stages. Assess a named identity
  instead of the heterogeneous `Either[SemanticError, MoveSite]` deduplication key; the current
  key is not itself a correctness defect.
- Distinguish move-origin variables from current-use source parameters.
- Capture branches build `UseAfterMove` positionally, while `MovedOwner.at` names the fields
  and already encapsulates construction. Reuse the shared mechanism where contracts permit,
  preserving behavior for unresolved identity rather than assuming it away.

## Outcome

Diagnostic construction and normalization expose their invariants through shared helpers and clear names.

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
