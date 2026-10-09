# Working Memory

Read [task-tracking-rules.md](task-tracking-rules.md) before changing task state.
This is the Author's active and near-term focus. The backlog lives in [tasks/](tasks/).

## Global Working Memory

- Record QA findings in [QA misses](tasks/qa-misses.md). Select individual items or related
  groups for cleanup; create dedicated tasks when they need their own plans.

## Active Tasks

### BUG: Mixed-ownership transfers, cleanup, and returns

- **Tracker:** [Mixed-ownership transfers, cleanup, and returns](tasks/mixed-ownership-transfers.md)
- **Kind:** BUG
- **Priority:** HIGH
- **Status:** planned
- **Current focus:** Investigation complete. Review the stage 1 design for explicit conditional
  ownership, local transfers, and cleanup before implementation. Returns form stage 2;
  capture integration requires a separate scope/design approval. This standalone task owns
  the plan and signoff for the lambda dependency and A* ownership follow-up.

### A* animation correctness bugs

- **Tracker:** [Fix correctness bugs exposed by the animated A* sample](tasks/astar-animation-correctness-bugs.md)
- **Status:** in_progress
- **Current focus:** Finding 5 needs M2 crash evidence and diagnosis before implementation approval.
  Findings 1–4, 6, and 7 are complete and signed off. Finding 7's Linux sanitizer verification
  is explicitly deferred to the [Linux ASan BUG](tasks/linux-asan-internalization.md).
  Ownership follow-ups belong to the standalone [mixed-ownership task](tasks/mixed-ownership-transfers.md).
  Float and string array fills are deferred.

### Lambda migration

- **Tracker:** [Unify lambdas](tasks/unify-lambdas.md)
- **Status:** in_progress
- **Current focus:** The standalone [mixed-ownership repair](tasks/mixed-ownership-transfers.md)
  is the selected dependency. The [ignored-test restoration plan](tasks/unify-lambdas.md#restore-ignored-regressions)
  accounts for 37 remaining ignores. Direct local-entry optimization is complete and signed off.
  PAP target optimization is complete and signed off for capturing and non-capturing targets.
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
