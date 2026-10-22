# Simplify owned-closure cleanup placement

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:ownership`, `component:cleanup`, `concern:readability`, `concern:optimization`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Approve a bounded classification/insertion refactor using the acceptance criteria below.
2. [ ] **planned** — Implement it and verify conditional timing, multiple late obligations, native ownership, and sanitizer controls.

## Problem

- **Problem:** `OwnershipAnalyzer.placeCleanup` constructs a trial rewrite to classify each
  binding, discards that rewrite, then repeats the storage-only check and cleanup-term
  construction while applying the result. Per-binding late cleanup also nests one
  `__ownership_result` binding per retained obligation. The names `early` and `remaining`
  obscure that a binding can be early on one branch and late on another.
- **Outcome:** make cleanup classification and insertion easier to follow without changing
  evaluation order, branch-specific release timing, ownership witnesses, or generated behavior.
- **Scope:** compute each binding's storage-only and cleanup-term evidence once, and avoid
  constructing a discarded rewrite for eligibility. Distinguish bindings eligible for early
  release on every path, on only some paths, and on no path. Preserve branch-specific insertion;
  batch only obligations proven late on every path into one scope-end cleanup wrapper, preserving
  their cleanup order. Do not batch the mixed-path group as uniformly late.
- **Acceptance:**
  - [ ] A focused regression keeps early release on an eligible conditional branch while
    retaining late cleanup on the other branch.
  - [ ] Two late obligations use one result wrapper and preserve their destruction order.
  - [ ] Existing single-evaluation, tail-loop, ownership, and sanitizer regressions pass.
- **Implementation approval:** pending a bounded repair plan. Signoff and commit are separate.

## Outcome

Cleanup classification reuses evidence and batches only uniformly late obligations, preserving all release ordering.

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
