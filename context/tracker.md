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

Narrow fixes that do not address the underlying problem are to be shunned.

## Active Tasks

### Protocols

- **Tracker:** [Implement protocols](tasks/protocols.md)
- **Status:** design
- **Current focus:** Settle protocol semantics and the initial implementation scope.

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
