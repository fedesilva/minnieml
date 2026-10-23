# Tracker

Read [task-tracking-rules.md](task-tracking-rules.md) before changing task state.
This is the Author's active and near-term focus. The backlog lives in [tasks/](tasks/).

## Global

- Record QA findings in [QA misses](tasks/qa-misses.md). Select individual items or related
  groups for cleanup; create dedicated tasks when they need their own plans.
- if you have QA findings, surface them clearly to The Author.

## Active Tasks

### BUG: Linux ASan assembly failure

- **Tracker:** [Fix Linux ASan assembly failure after internalization](tasks/linux-asan-internalization.md)
- **Status:** planned
- **Current focus:** Reconcile the LLVM 20.1.8 reproducer with passing Linux sanitizer suites,
  determine any remaining repair, and plan Linux smoke, benchmark, and memory verification.


### Apply returned callables with parenthesized function types

- **Tracker:** [Apply returned callables with parenthesized function types](tasks/typecheck-grouped-callable-results.md)
- **Status:** planned
- **Current focus:** After Unify lambdas, reproduce the inline failure and local-binding
  control, then plan consistent callable-type resolution.

### Unify type-property alias resolution

- **Tracker:** [Unify type-property alias resolution](tasks/type-property-alias-resolution.md)
- **Status:** design
- **Current focus:** Review after Unify lambdas; reconcile alias resolution across heap
  classification and memory-function queries, then approve a bounded implementation plan.

### Global-origin ownership

- **Tracker:** [Global-origin ownership](tasks/global-origin-ownership.md)
- **Status:** planned
- **Current focus:** Plan a `Global`/`Static` ownership mode that removes implicit cloning
  of globals, preserves their provenance through value flow, and prevents their destruction.


### Checkpoint: Merge into main


### Replace `@native` with `@ffi` and `@llvm`

- **Tracker:** [Replace `@native` with `@ffi` and `@llvm`](tasks/ffi-and-llvm-annotations.md)
- **Status:** planned
- **Current focus:** After Unify lambdas, plan the annotation split: `@ffi` for foreign
  declarations and `@llvm` for inline LLVM templates, preserving existing behavior.


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

### Modules, package root, and source inclusion

- **Tracker:** [Modules, package root, and source inclusion](tasks/modules.md)
- **Status:** design
- **Current focus:** Settle remaining module and inclusion semantics and prepare a bounded
  plan for Package and nested modules in one source file, followed by source inclusion.
