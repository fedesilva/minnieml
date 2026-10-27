# Revisit implicit cloning

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** improvement
- **Priority:** unassigned
- **Created:** 2026-10-26
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Review implicit cloning boundaries and settle promotion semantics.
2. [ ] **planned** — Prepare a bounded implementation and migration plan for approval.
3. [ ] **planned** — Implement and verify the approved changes.

## Problem

Implicit cloning supplies owned storage at some literal/global ownership boundaries.
Removing it requires distinguishing duplication from ownership promotion and transfer of
mixed values with their cleanup witnesses.

## Outcome

A clear policy for implicit cloning and promotion that preserves valid lifetimes and
exactly-once cleanup, with explicit cloning available through `Clone` and `^`.

## Scope

- Review literal/global transfers, owning returns, aggregate fields, and closure captures.
- Distinguish mixed-value transfers that retain ownership witnesses from boundaries that
  require unconditional ownership. Stack, heap, and static storage alone do not determine
  the ownership contract.
- Keep explicit `Clone` and `^`. Their implementation and the explicit global-cloning
  requirement belong to [Implement protocols](protocols.md).

## Plan (Approval Gate)

Inventory the remaining implicit clone paths after the initial protocol work. Decide which
require explicit cloning and which should preserve or promote ownership without duplication.
Define migration cases and verification before implementation.

Approval: design and implementation pending.

## Verification

Pending the bounded plan. Cover owned, literal, global, borrowed, and mixed alternatives;
verify cleanup, lifetime safety, and whether each accepted case copies storage.

## Risks / Notes

[Mixed ownership](../../docs/memory-model.md#mixed-ownership) retains a local cleanup witness;
[owning returns](../../docs/memory-model.md#mixed-return-branches) currently establish ownership
on every return path. Removing a clone must not silently change either contract.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Follow-up to [Implement protocols](protocols.md). Next action: inspect the remaining implicit
cloning and promotion boundaries, then propose the policy and bounded plan.
