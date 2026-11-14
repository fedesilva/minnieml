# Tracker

Read [task-tracking-rules.md](task-tracking-rules.md) before changing task state.
This is the Author's active and near-term focus. The backlog lives in [tasks/](tasks/).
Tasks and their open subitems are listed in priority order: first listed means first priority.

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

### Intrinsic arrays

- **Tracker:** [Intrinsic arrays](tasks/intrinsic-arrays.md)
- **Status:** design
- **Current focus:** Specify the sized-initializer API and call-effect analysis for heap-backed
  intrinsic arrays. Literals use `Sequence`; construction does not clone elements.
  Storage optimization follows as a later subtask.

### Allow globals at owning boundaries without cloning

- **Tracker:** [Allow globals at owning boundaries without cloning](tasks/global-owning-boundaries.md)
- **Status:** design
- **Current focus:** Preserve global modes through owning boundaries and infer ownership
  requirements from explicit drops; replace mandatory global cloning with mode-aware acceptance.

### Struct capture ownership

- **Tracker:** [Struct capture ownership](tasks/struct-capture-ownership.md)
- **Status:** design
- **Current focus:** Revisit borrowing construction and explicit all-field move construction,
  following the lambda capture model.


### Protocol derivation

- **Tracker:** [Protocol derivation](tasks/protocol-derivation.md)
- **Status:** design
- **Current focus:** General derivation after the initial protocol task; targeted Clone
  generation belongs to [explicit Clone](tasks/completed/explicit-clone.md).

### Implicit cloning

- **Tracker:** [Revisit implicit cloning](tasks/implicit-cloning.md)
- **Status:** design
- **Current focus:** Revisit implicit cloning and ownership promotion after the initial
  protocol work, preserving explicit Clone and `^`.
  note: this refers to promotion not cloning 

### Operator partial application

- **Tracker:** [Decide what to do with operator partial application](tasks/decide-operator-partial-application.md)
- **Status:** design
- **Current focus:** Deferred discussion after Protocols: unary/binary selection, annotation
  burden, rewriter changes, and whether to keep both operator application and placeholders.


### Module naming

- **Tracker:** [Prevent module symbol and output-name collisions](tasks/module-name-collisions.md)
- **Status:** planned
- **Current focus:** Define module identity and naming rules that prevent symbol and output collisions.

### Modules, package root, and source inclusion

- **Tracker:** [Modules, package root, and source inclusion](tasks/modules.md)
- **Status:** design
- **Current focus:** Settle remaining module and inclusion semantics and prepare a bounded
  plan for Package and nested modules in one source file, followed by source inclusion.

### Compiler stack safety and traversal cost

- **Tracker:** [Make long local sequences stack-safe and bound traversal cost](tasks/compiler-stack-safety-and-traversal-cost.md)
- **Status:** deferred
- **Current focus:** Draft staged repair using shared functional traversal tools across compiler
  phases, including availability queries and LLVM emission.
