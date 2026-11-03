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

1. [ ] **planned** — Compare reconstruction contracts against the shared walker and specify short-list behavior.
2. [ ] **planned** — Consolidate reconstruction callers and verify application order, callee identity, and resulting types.

## Problem

### D9: consolidate application-spine walkers

The shared walkers now live in
[ApplicationQueries](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/ApplicationQueries.scala),
with semantic and codegen callers migrated under
[AST query centralization](centralize-and-unify-ast-queries.md#step-12-callable-structure-and-analysis).
The remaining reconstruction helpers disagree for short lists, although no current caller reaches
that difference. Define one rebuilding contract before consolidating those helpers.

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

The reconstruction concern comes from source inspection and the lambda branch review. Shared
walker verification belongs to the linked AST-query task. Reconstruction contracts are unchanged
and have no implementation or verification claim here; run the applicable checks when selecting
that remaining work.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Deferred at LOW priority. Shared application walkers are implemented under AST query
centralization; this task remains open for reconstruction and short-list behavior. Next action:
prepare that bounded plan when the task is selected. No rebuilding change or closure is authorized.
