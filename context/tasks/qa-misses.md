# QA misses

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-12
- **Target Branch:** Unassigned
- **External Reference (optional):** None

## Purpose

Collect concrete QA findings without interrupting ongoing work to fix each one. Select items
individually, group related items into a cleanup session, or create a dedicated task when an
item needs its own plan. A cleanup campaign can work through several approved groups.

## Recording and selection

- Give each finding a stable ID, a descriptive title, source links, the problem, and an
  expected outcome. Distinguish observed behavior from suspected consequences.
- Recording an item does not start its implementation. Agree on a bounded plan when selecting
  an item or group; follow the normal verification and signoff rules for the affected code.
- When an item becomes a separate task, link it here and keep implementation detail there.
- Mark an item resolved only with verification evidence. Keep its ID and a concise resolution
  so later references remain meaningful.

## Items

### QA-001: Preserve resolved type identity in type utilities

- **Status:** complete
- **Implementation and verification:** [Unify type-property alias resolution](type-property-alias-resolution.md).
- **Source:** [TypeUtils.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeUtils.scala)
  and [MemoryFunctions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/MemoryFunctions.scala).
- **Problem:** Reducing resolved types to short names permits stdlib-first or first-match lookup
  to select a different declaration. Name-based recursive guards can also conflate declarations.
- **Repair:** Property queries and callers preserve typed AST inputs and resolved IDs. Shared
  declaration resolution replaces name guessing, including native representation queries.
  Shared memory-function selection uses the resolved declaration's owning module; explicit native
  destructors may name stdlib bindings. Struct generation can reference its pending helpers.
- **Evidence:** 23 type-query tests and the full compiler suite pass. Collision tests use constructed
  resolved AST declarations; they do not establish multi-module source-language support.
- **Resolution:** Compiler gates, independent review, and Author signoff are complete.

## Working memory

QA-001 implementation and verification are tracked in the linked alias-resolution task.
