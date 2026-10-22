# Consolidate application traversal and reconstruction

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:callable-analysis`, `component:ast`, `concern:shared-helpers`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Compare existing walker/rebuilder contracts and specify the short-list behavior.
2. [ ] **planned** — Consolidate callers and verify application order, callee identity, and resulting types.

## Problem

### D9: consolidate application-spine walkers

`CallableValues.application` and `Applications.collectArgsAndFunction` duplicate
traversal; the two rebuilders disagree for short lists, although no current caller
reaches that difference. Consolidate the walkers and define one rebuilding contract.

## Outcome

Call analysis and reconstruction share one contract, including short argument lists.

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
