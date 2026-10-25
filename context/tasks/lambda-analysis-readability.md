# Improve lambda analysis and lowering readability

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:capture-analysis`, `component:codegen`, `component:partial-application`, `concern:readability`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Bound the capture-processing and positional-state refactors against qa-readability.md.
2. [ ] **planned** — Apply the named stages/records and focused spacing cleanup; verify affected compiler paths.

## Problem

### D5: simplify capture analysis

Split `OwnershipAnalyzer.analyzeLambda` capture processing into named stages and remove
its duplicated clone-target block. The fold threads an opaque `(scope, errors, captures)`
triple through deeply nested branches.

### D12: name positional state

Use named records for positional environment and application state in
`FunctionEmitter`, `ExpressionCompiler`, and `PartialApplicationElaborator`.

### D13: apply spacing and line-length rules

Apply the repository's vertical-spacing rule to the reviewed destructor, validator,
emitter, and PAP elaborator blocks; wrap the identified lines over 100 characters in
`PartialApplicationElaborator`, `FunctionEmitter`, and `OwnershipAnalyzer`.

Coordinate overlapping work with [QA readability](qa-readability.md).

## Outcome

Named capture stages and state records make transformations readable without changing behavior.

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
