# Unify alias resolution

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

there are several implementations most of them private to a phase.
consider something like:

```
def resolveAlias(typeName: String, resolvables: ResolvablesIndex): Option[ResolvableType]
def resolvedTypeName(typeName: String, resolvables: ResolvablesIndex): Option[String]
def sameResolvedTypeName(...)
```

## Outcome

Agree on and implement a shared alias-resolution boundary where the existing phase-local implementations overlap.

## Scope

- In scope: The problem and outcome above; detailed scope is settled in the approved plan.
- Out of scope: Unrelated work and changes not covered by an approved plan.

## Plan (Approval Gate)

- [ ] Confirm a bounded plan and acceptance with the Author before implementation.

Approval: Pending implementation planning; tracking migration approved on 2026-09-10.

## Implementation Checklist

- [ ] Execute and verify the scope once its plan is approved.

## Verification

No new implementation verification during the tracking migration.

## Risks / Notes

Migrated from the former tracker on 2026-09-10. Migration preserves pending scope;
it does not establish implementation progress, approval, or completion.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Not granted for this task by the migration request.

## Task Working Memory

The Author authorized migration into a task file. Implementation is not authorized by
that bookkeeping request. Next: discuss and approve a bounded plan before changing code or docs.
