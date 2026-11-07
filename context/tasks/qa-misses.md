# QA misses

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-12
- **Target Branch:** Unassigned
- **External Reference (optional):** None

## Execution Checklist

1. [ ] **planned** — Select a pending item and approve its bounded repair plan.
2. [ ] **planned** — Implement, verify, and review the selected repair.

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
- **Implementation and verification:** [Unify type-property alias resolution](completed/type-property-alias-resolution.md).
- **Source:** [TypeQueries.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/TypeQueries.scala),
  [NativeTypeQueries.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/NativeTypeQueries.scala),
  [TypeMemoryQueries.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/TypeMemoryQueries.scala),
  and [MemoryFunctions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/MemoryFunctions.scala).
- **Problem:** Reducing resolved types to short names permits stdlib-first or first-match lookup
  to select a different declaration. Name-based recursive guards can also conflate declarations.
- **Repair:** Property queries and callers preserve typed AST inputs and resolved IDs. Shared
  declaration resolution replaces name guessing, including native representation queries.
  Shared memory-function selection uses the resolved declaration's owning module; explicit native
  destructors may name stdlib bindings. Struct generation can reference its pending helpers.
- **Evidence:** 23 type-query tests and the full compiler suite pass. Collision tests use constructed
  resolved AST declarations; they do not establish multi-module source-language support.
- **Resolution:** Compiler gates, independent review, and Author signoff are complete.

### QA-002: Grow string builders to fit the complete append

- **Status:** planned
- **Source:** [mml_runtime.c](../../modules/mmlc-lib/src/main/resources/mml_runtime.c),
  `string_builder_append`.
- **Problem:** The append path doubles capacity once, then copies the entire input and writes
  its terminator. A builder with capacity 16 and length 0 appending 100 bytes allocates only
  32 bytes before copying 100 bytes and writing at offset 100.
- **Evidence:** Direct code inspection of the growth, `memcpy`, and terminator-write sequence.
  No runtime reproduction has been run for this finding.
- **Expected outcome:** Capacity growth covers the existing contents, complete append, and
  terminator with checked size arithmetic; a large single-append regression verifies the bound.
- **Scope:** Deferred. Scalar ToString conversions do not use this builder; array conversion is
  also deferred.

### QA-003: Exercise normal CLI reporting in diagnostic agreement tests

- **Status:** complete
- **Implementation and verification:** [Moved-capture and visibility diagnostics](completed/lsp-missing-semantic-diagnostics.md).
- **Source:** [DiagnosticsTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/DiagnosticsTests.scala),
  [CompilerApi.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/api/CompilerApi.scala),
  and [ErrorPrinter.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/ErrorPrinter.scala).
- **Problem:** CLI agreement assertions using `SemanticErrorPrinter` bypass normal command
  reporting through `ErrorPrinter`, whose moved-capture and visibility branches are separate.
  A defect in those normal CLI branches would not fail the secondary-printer assertions.
- **Expected outcome:** Source-driven checks capture normal CLI command output; constructed
  diagnostic payloads use the normal renderer and verify the offending source highlight.
- **Evidence:** Independent review confirmed the dispatch mismatch. The corrected tests use
  `CompilerApi.processAstOnly` for moved capture and `ErrorPrinter` for visibility; both focused
  diagnostic suites pass all 14 tests. Independent review accepts the corrected reporting path.
- **Resolution:** Compiler gates, independent review, and Author signoff are complete.

### QA-004: Isolate diagnostic-specific evidence in CLI assertions

- **Status:** complete
- **Implementation and verification:** [Moved-capture and visibility diagnostics](completed/lsp-missing-semantic-diagnostics.md).
- **Source:** [DiagnosticsTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/DiagnosticsTests.scala).
- **Problem:** Searching combined CLI output for a moved site and highlighted reference lets
  a neighboring use-after-move diagnostic satisfy assertions intended for the moved-capture report.
- **Expected outcome:** Check the capture report's own evidence and verify that this complete
  report appears in normal CLI output.
- **Evidence:** Independent execution confirms that both diagnostics report the same reference
  and move site in the fixture. The correction isolates the typed capture error through
  `ErrorPrinter`; both focused diagnostic suites pass all 14 tests. Independent narrow review
  and its four dedicated checks have no actionable findings.
- **Resolution:** Compiler gates, independent review, and Author signoff are complete.

### QA-005: Describe lowercase operator references in the parser contract

- **Status:** complete
- **Implementation and verification:** [B23 parser repair](completed/parser-parenthesized-expression-performance.md).
- **Source:** [expressions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala),
  `opRefP`, and [identifiers.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/identifiers.scala),
  `operatorIdP`.
- **Problem:** The `opRefP` comment describes symbolic operators only, although `operatorIdP`
  also accepts lowercase names. The plain-reference ordering depends on that behavior to
  preserve annotation ownership.
- **Expected outcome:** The declaration comment names both accepted operator forms.
- **Evidence:** Independent documentation review and a separate claim verifier confirm the
  mismatch. The corrected sentence describes symbolic or lowercase operator references.
  This is a documentation defect; no runtime defect is established.
- **Resolution:** The wording is corrected. Fresh narrow primary review and all four dedicated
  checks have no actionable findings. B23's workstream signoff is complete.

## Working memory

QA-001 implementation and verification are tracked in the linked alias-resolution task.
QA-003/QA-004 are resolved in the completed diagnostic repair task.
QA-005's wording is corrected and independently reviewed as part of B23.
