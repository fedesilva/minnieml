# Use immutable state for topological ordering

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Execution Checklist

1. [x] complete — Revalidate the recorded mutation concern against the implementation.
2. [x] complete — Record the existing resolution and archive the task; no code change needed.

## Problem

The retained QA report identifies uses of `mutable.Map`, `mutable.ListBuffer`, `mutable.Queue`, and
  `while queue.nonEmpty do` inside a core semantic pass. This is not one of the allowed mutable
  boundaries in `qa-rules-and-coding-style.md` rule 2.

## Outcome

The concern is resolved by the existing immutable implementation of `topologicalOrder`.
Dependency counts use immutable maps; the ready list and sorted vector are passed through
a tail-recursive ordering helper. No mutable collections or `while` loop remain in the function.
No compiler change is required for this task.

## Scope

Function-local mutation in `TypeChecker.topologicalOrder`. Completion is based on source
inspection of the existing implementation, not a new implementation or behavioral verification.

### Retained QA report

Imported from the QA debt record. Counts, locations, freshness, and proposed
fixes describe that historical review. Current findings are recorded under Verification.

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

## Verification

Source inspection on 2026-10-25 at commit `bda341e2e318d25354c0969db74d60664079d204`:

- [TypeChecker.scala](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala),
  `topologicalOrder` (lines 320–388), uses immutable `Map`, `List`, and `Vector` state,
  `foldLeft`, and an `@tailrec` ordering helper. The reported mutable collections and loop
  are absent. This directly resolves the recorded QA concern.
- [TypeCheckerTests.scala](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeCheckerTests.scala)
  contains tests for forward references, dependency chains, mutual recursion, and source-order
  preservation. Tests were inspected but not executed for this documentation-only disposition.

## Risks / Notes

The historical report does not describe the inspected implementation. Archival records the
existing resolution; it does not establish broader algorithm correctness or performance.

## Signoff

- Disposition: Approved complete and archived based on the existing immutable implementation.
- Commit authorization: Granted for the archive record and incoming-link repair.

## Task Working Memory

The recorded mutation concern is resolved. No implementation work remains within this scope.
