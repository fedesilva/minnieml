# Working Memory

Read [task-tracking-rules.md](task-tracking-rules.md) before changing task state.
This is the Author's active and near-term focus. The backlog lives in [tasks/](tasks/).

## Global Working Memory

- Record QA findings in [QA misses](tasks/qa-misses.md). Select individual items or related
  groups for cleanup; create dedicated tasks when they need their own plans.

## Active Tasks

### Lambda migration

- **Tracker:** [Unify lambdas](tasks/unify-lambdas.md)
- **Status:** in_progress
- **Current focus:** Urgent, immediate next:
  [elaboration error recovery](tasks/unify-lambdas.md#bug-continue-elaboration-after-independent-errors).
  Continue valid regions after independent errors and prevent false downstream diagnostics.

### BUG: Conditional ownership hardening

- **Tracker:** [Make conditional ownership explicit in ownership operations](tasks/conditional-ownership-witnesses.md)
- **Status:** planned
- **Current focus:** Planned within Unify lambdas, together with its mixed-ownership
  consuming-transfer bug repair. Retain Boolean witnesses and make cleanup, consumption,
  and return operations consistently respect conditional ownership.
