# Clarify compound borrowing diagnostic wording

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:ownership`, `concern:diagnostics`, `concern:wording`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Choose wording for direct uses and compound borrowed expressions.
2. [ ] **planned** — Share the message contract across CLI/LSP and add exact wording/identity assertions.

## Problem

Finding F5: `use (if true then q; else q;) (drop h)` highlights the conditional but says
`Use of 'h' after move`; that span contains only alias `q`. A bare `use q (drop h)` names `q`.
`borrowedUseErrors` retains the owner reference when the expression is not a single reference.
`OwnershipDiagnosticTests` explicitly checks that owner identity. This is a presentation choice,
not a demonstrated ownership-enforcement defect.

## Outcome

Compound-use wording distinguishes the highlighted expression from the moved backing owner.

## Scope

The component and concerns described above are independent backlog work, outside Unify lambdas
completion and merge requirements. Preserve language semantics, ownership enforcement, evaluation
order, and runtime behavior; any discovered correctness defect needs separate assessment.

## Plan (Approval Gate)

Prepare a bounded implementation plan from the checklist and embedded evidence.
Approval: task definition and deferral authorized; implementation plan pending.

## Verification

The existing conditional regression executes and checks the distinct spans and owner identity.
Suggested wording is “This expression depends on moved value h”; the final wording is undecided.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Deferred at LOW priority. Next action: prepare the bounded plan when this task is selected.
