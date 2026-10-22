# Unify ownership state predicates and joins

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:ownership`, `concern:identity`, `concern:shared-helpers`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Coordinate the identity boundary with semantic-id-lookups.md and the completed B3 repair.
2. [ ] **planned** — Unify owned-environment predicates and scope joins while preserving ownership state and diagnostics.

## Problem

### D4: share the owned-environment predicate

Centralize the owned heap-environment rule repeated and negated across
`OwnershipAnalyzer` and `CaptureTransfers`.

### D6: remove name-keyed ownership scope paths

`OwnershipScope` and its move/use checks mix names and IDs. B3 owns the demonstrated
recursive-shadowing repair; the broader [semantic-ID task](semantic-id-lookups.md)
owns pipeline-wide identity migration. Remove the remaining name-keyed ownership paths
as part of the agreed boundary between those repairs.

### D8: unify scope joins

Unify duplicated scope-join plumbing and replace hand-picked copied scope fields with
an explicit contract.

## Outcome

Ownership predicates, stable identities, and scope joins follow one explicit contract.

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
