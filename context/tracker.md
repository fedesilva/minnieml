# Tracker

Read [task-tracking-rules.md](task-tracking-rules.md) before changing task state.
This is the Author's active and near-term focus. The backlog lives in [tasks/](tasks/).

## Global

* QA
  - Record QA findings in [QA misses](tasks/qa-misses.md). Select individual items or related
  groups for cleanup; create dedicated tasks when they need their own plans.
  - if you have QA findings, surface them clearly to The Author.

### Maxim

As a general rule, we want to FIX things not PATCH bugs;
  we are building a compiler from scratch, we have no clients or users 
  and we want to deliver a clean, easy to understand high quality code base.

Narrow fixes that do not address the underlaying problem are to be shunned.

## Active Tasks

### Error recovery and parser backtracking

- **Tracker:** [Error recovery and parser backtracking](tasks/error-recovery-and-parser-backtracking.md)
- **Status:** planned
- **Current focus:** Next workstream after lambda completion and any necessary correctness
  fixes. Preserve valid expression structure and causal diagnostics, then introduce parser
  commitment with local recovery using the linked design.

### Unify type-property alias resolution

- **Tracker:** [Unify type-property alias resolution](tasks/type-property-alias-resolution.md)
- **Status:** design
- **Current focus:** Review after Unify lambdas; reconcile alias resolution across heap
  classification and memory-function queries, then approve a bounded implementation plan.

### Global-origin ownership

- **Tracker:** [Global-origin ownership](tasks/global-origin-ownership.md)
- **Status:** planned
- **Current focus:** Plan a `Global` ownership mode that removes implicit cloning
  of globals, preserves their provenance through value flow, and prevents their destruction.


Note: We need a separate `Static` mode, which is a specialized `Global` that can't be mutated.
  Probably not for now since we don't track mutability or mutation.

### Module naming

- **Tracker:** [Prevent module symbol and output-name collisions](tasks/module-name-collisions.md)
- **Status:** planned
- **Priority:** HIGH
- **Current focus:** Define module identity and naming rules that prevent symbol and output collisions.

### Modules, package root, and source inclusion

- **Tracker:** [Modules, package root, and source inclusion](tasks/modules.md)
- **Status:** design
- **Current focus:** Settle remaining module and inclusion semantics and prepare a bounded
  plan for Package and nested modules in one source file, followed by source inclusion.
