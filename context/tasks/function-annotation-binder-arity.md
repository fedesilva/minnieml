# Disambiguate function annotation arity by the binder

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

`Int -> Int -> Int` is intentionally valid for the common uncurried definition-site
case: a two-param binding reads it as `fn(Int, Int): Int`. The bug is that the checker
also commits to arity 2 from the arrow count before looking at how many parameters the
lambda actually binds.

That makes this shape fail even though the produced value type exists:

```mml
let make_fac: Int -> Int -> Int =
  { n: Int -> factorial_tco n }
;
```

`make_fac` binds one parameter, so the annotation should peel one arrow segment and
leave `Int -> Int` as the return type. Downstream, `make_fac 5 : Int -> Int` already
matches the language's partial-application behavior.

Expected fix: keep uncurried-by-default definition semantics, but reconcile function
annotations against the lambda's binder arity. A two-param `factorial_tco:
Int -> Int -> Int` consumes two arrow segments and returns `Int`; a one-param
`make_fac: Int -> Int -> Int` consumes one segment and returns `Int -> Int`.
The bug is having arrow-count and binder-count act as independent arity sources that
can disagree.

## Outcome

Function annotations consume arrow segments according to binder arity while preserving uncurried-by-default definitions.

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
