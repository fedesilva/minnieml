# Use immutable state for topological ordering

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

Uses `mutable.Map`, `mutable.ListBuffer`, `mutable.Queue`, and
  `while queue.nonEmpty do` inside a core semantic pass. This is not one of the allowed mutable
  boundaries in `qa-rules-and-coding-style.md` rule 2.

## Outcome

Revalidate and address the recorded QA concern within its stated scope.

## Scope

The retained design below defines the proposed scope; implementation requires a bounded plan.

### Retained QA report

Imported from the QA debt record. Counts, locations, freshness, and proposed
fixes describe that review; none have been reverified during this document migration.

- Location: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala:241-272`
  (`topologicalOrder`).
- Recorded status: fresh.
- Problem: Uses `mutable.Map`, `mutable.ListBuffer`, `mutable.Queue`, and
  `while queue.nonEmpty do` inside a core semantic pass. This is not one of the allowed mutable
  boundaries in `qa-rules-and-coding-style.md` rule 2.
- Smell level: medium
- Impact: medium. It works today, but normalizes mutable algorithms inside the semantic stage and
  complicates reasoning about pass purity and re-entrancy.
- Suggested direction: rewrite as tail-recursive Kahn's algorithm over immutable
  `Map[String, Int]` and an immutable queue/list, returning the sorted member IDs and cycle tail.
  If a mutable variant is genuinely justified for performance, leave a one-line boundary comment.
- Scope decision: function-local; no test contract changes expected.

## Plan (Approval Gate)

- [ ] Revalidate the draft against current code and agree on a bounded implementation plan.

Approval: Spec-to-task migration authorized on 2026-09-10; implementation pending.

## Implementation Checklist

- [ ] Implement and verify the approved scope.

## Verification

Document migration only; no implementation checks run.

## Risks / Notes

Imported design proposals are not evidence of implemented behavior. Recheck source-location
and baseline claims when starting this task.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Not granted.

## Task Working Memory

Created from the retained spec during context cleanup. Not selected for active memory.
Next: discuss scope when the Author selects this task.
