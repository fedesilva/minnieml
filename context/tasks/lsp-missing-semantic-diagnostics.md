# Show moved-capture and visibility errors in the editor

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-27
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Add editor-diagnostic regressions for moved captures and visibility violations.
2. [ ] **planned** — Map both errors to their existing source locations and messages.
3. [ ] **planned** — Verify editor/CLI agreement and the applicable compiler checks.

## Problem

The compiler records `CapturedMovedHeapBinding` when a closure attempts to capture a heap binding
whose value has already been moved. It also records `VisibilityViolation` for inaccessible references.
Both errors have CLI diagnostics, but the editor diagnostic source-span mapping omits them, so
the editor can show no error for the same invalid source.

The confirmed defect is diagnostic delivery. It does not establish an ownership-checking or
memory-safety defect, and it predates the protocol implementation.

## Outcome and scope

Both errors appear in the editor with the useful existing message and the offending source range.
Use the compiler's existing diagnostic causes and source locations. Do not change ownership or
visibility rules or invent a second diagnostic extraction mechanism.

## Evidence and verification

Source tracing confirms the missing cases in
[Diagnostics.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/Diagnostics.scala)
and the corresponding CLI handling in
[SemanticErrorPrinter.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/SemanticErrorPrinter.scala).
Focused editor regressions and repair verification remain pending. See also the short discovery
notes in [Bug notes](bug-notes.md), entries for moved-capture and visibility diagnostics.

## Plan and signoff

- Task creation: authorized.
- Implementation approval: pending; this repair is outside immediate protocol changes.
- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: granted for the task definition only.

## Task Working Memory

Next: choose ordinary source fixtures that already produce the two semantic errors and assert
their editor messages and ranges before changing the shared diagnostic mapping.
