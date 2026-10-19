# Working Memory

Read [task-tracking-rules.md](task-tracking-rules.md) before changing task state.
This is the Author's active and near-term focus. The backlog lives in [tasks/](tasks/).

## Active Tasks

### Lambda migration

- **Tracker:** [Unify lambdas](tasks/unify-lambdas.md)
- **Status:** in_progress
- **Current focus:** [Ignored-test restoration](tasks/unify-lambdas.md#restore-ignored-regressions)
  has 36 remaining cases. Further repairs and assertion replacements need bounded plans.
  Overall migration signoff remains pending.

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

### BUG: Linux ASan assembly failure

- **Tracker:** [Fix Linux ASan assembly failure after internalization](tasks/linux-asan-internalization.md)
- **Status:** planned
- **Current focus:** Isolate the LLVM 20.1.8 sanitizer/internalization failure on both Linux
  architectures and prepare a bounded repair plan.

## Global Working Memory

- Record QA findings in [QA misses](tasks/qa-misses.md). Select individual items or related
  groups for cleanup; create dedicated tasks when they need their own plans.
