# Document and encode the ownership rules

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

This is important, urgent even.
While working on lambdas we have drifted a couple of times
from the intended design and are relying on cloning where
we should not.

This needs to be a deeper reference than the lang ref
and less implementation details oriented than the design doc.
It needs to also describe lowering stragegies, where appropriate
but this is secondary related to specifying the behaviour and
describing the model.

This will serve as a focused reference as we continue to develop the model.

* no cloning, erradicate implicit, behind the scenes cloning.
  * and where we do now (globals), how we plan to avoid it
* ownership of regular values
* ownership of lambdas
  * track like a struct
    * particularly if the have move arguments
* lifeline/ownership and escaping

first generate a document out of the current implementation and we can iterate over
it if things are not in good taste or shape.

## Outcome

A focused ownership reference describes the intended rules and relevant lowering strategies, reviewed with the Author.

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
