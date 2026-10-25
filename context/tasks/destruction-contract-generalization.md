# Generalize destruction classification and type resolution

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:destruction`, `component:type-resolution`, `concern:extensibility`, `concern:shared-helpers`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Define the storage-only destruction contract beyond the fixed stdlib ID list.
2. [ ] **planned** — Cover a non-String capture and reuse canonical type resolution in destruction validation.
3. [ ] **planned** — Verify cleanup behavior and tail-call preservation for affected types.

## Problem

### D3: generalize storage-only destruction

**Severity:** medium. `StorageOnlyDestruction` hardcodes seven stdlib deallocator IDs.
Use a contract that also covers new or user-native heap types. Add a non-String capture
case; missing an ID can lose tail-call preservation.

### D10: reuse canonical type resolution

Reuse `TypeUtils.canonical` in `DestructionValidator`; the local copy already differs
for unresolved references.

## Outcome

Storage-only classification covers supported native heap types and validation uses canonical types.

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
