# Use semantic identity in ownership assertions

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

Assertions still depend on hardcoded generated names such as `__free_String`,
  `__clone_String`, `__free_User`, `__free_Outer`, `__free_closure`, and `__owns_s`.
  This keeps ownership tests coupled to generated symbol spelling rather than stable semantic
  identity.
- Current helper state: local helpers now use shared test AST extractors and traversal helpers
  (`TXCall1`, `TXRefResolved`, `TXRefNamed`, `existsTerm`, `countTerms`), so the earlier deep
  wildcard-match smell is closed.

## Outcome

Revalidate and address the recorded QA concern within its stated scope.

## Scope

The retained design below defines the proposed scope; implementation requires a bounded plan.

### Retained QA report

Imported from the QA debt record. Counts, locations, freshness, and proposed
fixes describe that review; none have been reverified during this document migration.

- Location: `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala:9-50`
- Recorded status: fresh, but the old `FIXME:QA` markers are gone and helper shape improved.
- Problem: Assertions still depend on hardcoded generated names such as `__free_String`,
  `__clone_String`, `__free_User`, `__free_Outer`, `__free_closure`, and `__owns_s`.
  This keeps ownership tests coupled to generated symbol spelling rather than stable semantic
  identity.
- Current helper state: local helpers now use shared test AST extractors and traversal helpers
  (`TXCall1`, `TXRefResolved`, `TXRefNamed`, `existsTerm`, `countTerms`), so the earlier deep
  wildcard-match smell is closed.
- Smell level: high
- Impact: high regression risk in ownership semantics tests, brittle refactors, potential false
  confidence from name-coupled assertions.
- Suggested direction: replace name-string assertions with resolved-id/type-aware helper predicates
  where stable identity exists. Keep generated-name checks only where the generated name itself is
  the behavior under test.
- Scope decision: file-only now; optional follow-up scan across semantic tests for `__free_`,
  `__clone_`, and `__owns_` hardcoded name assertions.

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
