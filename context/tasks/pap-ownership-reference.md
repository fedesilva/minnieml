# Integrate the PAP ownership tutorial into the language reference

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:language-reference`, `concern:documentation`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Adapt the tutorial into a step-by-step reference explanation and correct the consuming-PAP contradiction.
2. [ ] **planned** — Check examples and ownership claims against the language/memory contracts and relevant tests.

## Problem

- [ ] Adapt the [PAP ownership tutorial](../../mml/samples/pap-ownership.mml) into the
  [language reference](../../docs/language-reference.md) at a later stage of the lambda work.
  Preserve its concrete, step-by-step explanation of owning, borrowing, and consuming PAPs,
  following values through creation, calls, and cleanup.

The ownership-errors list in `docs/language-reference.md` also says partial application with
consuming parameters is forbidden, contradicting its consuming-parameter rules and passing
supplied/deferred consuming-PAP tests. Correct that blanket statement as part of the same topic.

## Outcome

The language reference explains owning, borrowing, and consuming PAPs consistently with tested behavior.

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
