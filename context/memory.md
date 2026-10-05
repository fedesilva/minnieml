# Working Memory

Read [task-tracking-rules.md](task-tracking-rules.md) before changing task state.
This is the Author's active and near-term focus. The backlog lives in [tasks/](tasks/).

## Global Working Memory

- Record QA findings in [QA misses](tasks/qa-misses.md). Select individual items or related
  groups for cleanup; create dedicated tasks when they need their own plans.

## Active Tasks

### Module link directive

- **Tracker:** [Module link directive](tasks/link-directive.md)
- **Status:** planned
- **Current focus:** Implement `@link` for executable linking and static partial linking into
  `.o` output. Rules are settled; implementation plan awaits approval.

### Lambda migration

- **Tracker:** [Unify lambdas](tasks/unify-lambdas.md)
- **Status:** in_progress
- **Current focus:** The [ignored-test restoration plan](tasks/unify-lambdas.md#restore-ignored-regressions)
  accounts for 39 remaining ignores. Direct local-entry and PAP target repairs await
  implementation approval. Overall migration signoff remains pending.

### Error recovery and parser backtracking

- **Tracker:** [Error recovery and parser backtracking](tasks/error-recovery-and-parser-backtracking.md)
- **Status:** planned
- **Current focus:** Next workstream after lambda completion and any necessary correctness
  fixes. Preserve valid expression structure and causal diagnostics, then introduce parser
  commitment with local recovery using the linked design.

### Module naming

- **Tracker:** [Prevent module symbol and output-name collisions](tasks/module-name-collisions.md)
- **Status:** planned
- **Priority:** HIGH
- **Current focus:** Define module identity and naming rules that prevent symbol and output collisions.

### BUG: Conditional ownership hardening

- **Tracker:** [Make conditional ownership explicit in ownership operations](tasks/conditional-ownership-witnesses.md)
- **Status:** planned
- **Current focus:** Planned within Unify lambdas, together with its mixed-ownership
  consuming-transfer bug repair. Retain Boolean witnesses and make cleanup, consumption,
  and return operations consistently respect conditional ownership.
