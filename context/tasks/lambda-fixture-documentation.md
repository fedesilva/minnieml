# Correct lambda fixture and helper descriptions

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:memory-tests`, `component:closure-generation`, `concern:documentation`, `concern:test-conventions`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Correct the four memory fixture headers and stale closure-name comment.
2. [ ] **planned** — Use the repository test convention in FunctionEmitterBorrowClosureValidatorTest and verify it.

## Problem

### T5: correct Direct fixture headers

**Severity:** low. Headers in
`tests/mem/direct-move-{closure,literal-capture,owned-string-capture,owned-struct-capture}.mml`
claim Direct lowering without an environment, but the IR allocates and frees
`__closure_env_N`. Correct the descriptions to the actual contract. The corresponding
codegen pinning tests remain ignored and inventoried; this text correction does not
authorize restoring their representation assertions.

### D11: correct stale comment and test base

Correct the closure-name comment in `ClosureMemoryFnGenerator`, and bring the lone
plain `munit.FunSuite` in `FunctionEmitterBorrowClosureValidatorTest` into the
repository's test convention.

## Outcome

Fixture headers describe actual environments, helper comments match naming, and tests use the repository base.

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
