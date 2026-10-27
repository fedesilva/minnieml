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
- **Status:** in_progress
- **Current focus:** Implement P1 parsing using the signed-off AST and shared lookup API.

### Operator partial application

- **Tracker:** [Decide what to do with operator partial application](tasks/decide-operator-partial-application.md)
- **Status:** design
- **Current focus:** Deferred discussion after Protocols: unary/binary selection, annotation
  burden, rewriter changes, and whether to keep both operator application and placeholders.

### Protocol derivation

- **Tracker:** [Protocol derivation](tasks/protocol-derivation.md)
- **Status:** design
- **Current focus:** General derivation after the initial protocol task; targeted Clone
  generation belongs to that initial task.

### Implicit cloning

- **Tracker:** [Revisit implicit cloning](tasks/implicit-cloning.md)
- **Status:** design
- **Current focus:** Revisit implicit cloning and ownership promotion after the initial
  protocol work, preserving explicit Clone and `^`.

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
