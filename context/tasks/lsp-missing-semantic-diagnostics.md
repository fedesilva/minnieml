# Show moved-capture and visibility errors in the editor

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-27
- **Target Branch:** dev/lsp-semantic-diagnostics

## Execution Checklist

1. [x] **complete** — Add a source-driven moved-capture regression and visibility-payload presentation coverage.
2. [x] **complete** — Map both errors to their existing source locations and messages.
3. [x] **complete** — Verify editor/CLI agreement and the applicable compiler checks.
4. [x] **complete** — Make semantic span and message conversion exhaustive; preserve other error-family behavior.
5. [x] **complete** — Verify and review the exhaustive conversion and dispatch regressions.

## Problem

The compiler records `CapturedMovedHeapBinding` when a closure attempts to capture a heap binding
whose value has already been moved. `VisibilityViolation` has a typed payload and CLI renderer,
but no compiler phase emits it; visibility enforcement is outside this repair.
Both variants need editor source-range and message mappings. Without them, the capture-specific
error disappears from editor output even when a separate use-after-move error remains visible;
visibility payloads also produce no editor diagnostic when supplied directly.

The confirmed defect is diagnostic delivery. It does not establish an ownership-checking or
memory-safety defect, and it predates the protocol implementation.

## Outcome and scope

Both errors appear in the editor with the useful existing message and the offending source range.
Use the compiler's existing diagnostic causes and source locations. Do not change ownership or
visibility rules or invent a second diagnostic extraction mechanism.
Semantic span and message conversion must match the closed `SemanticError` enum exhaustively,
so an omitted variant fails compilation under `-Werror`. The open `CompilationError` boundary
retains omission of parser, API-wrapper, backend, toolchain, and other unsupported families.

## Evidence and verification

[Diagnostics.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/Diagnostics.scala)
maps both variants to their reference spans and existing semantic messages.
[DiagnosticsTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/DiagnosticsTests.scala)
checks exact source ranges, messages, normal CLI reporting/highlights, and omission of synthetic
references. The moved-capture fixture uses `CompilerApi.processAstOnly`; the constructed visibility
payload uses `ErrorPrinter` directly.
Visibility coverage constructs a diagnostic from parsed reference and declaration nodes;
it does not establish source-level enforcement. Moved-capture coverage uses full compilation.

### Diagnostic-delivery verification

Verification on macOS arm64 with Clang/LLVM 23.1.1:

- Before the converter changes, both new regressions failed because their diagnostics were absent;
  the existing five tests passed.
- `sbtn "scalafmtAll; scalafixAll; testOnly mml.mmlclib.lsp.DiagnosticsTests mml.mmlclib.lsp.DiagnosticPresentationTests"`
  passed all 12 tests.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn testFull`: 2,525 library and 9 CLI tests passed; no skipped tests in the XML reports.
- `sbtn mmlcPublishLocal`: passed.
- `make -C benchmark clean` and `make -C benchmark mml`: passed; 37 binaries built.
  These are build checks, not benchmark timing measurements.
- QA enforcement, local tracking links, and `git diff --check`: passed.
- Independent review identified a CLI-coverage gap: `SemanticErrorPrinter` is separate from
  normal CLI reporting. The corrected tests use the normal command/renderer paths and pass
  the focused 12-test command above. A follow-up correction isolates capture-report evidence
  from the adjacent use-after-move report; the 12 focused tests pass again.
- Independent primary review and all four dedicated checks are complete: code style/QA,
  test quality, readability, and code documentation. Both confirmed test gaps are corrected;
  the final narrow re-review has no actionable findings. See [QA-003 and QA-004](qa-misses.md#qa-003-exercise-normal-cli-reporting-in-diagnostic-agreement-tests).
- Full-suite, smoke, publishing, and benchmark-build results cover the production change.
  Test-only review corrections received fresh formatting, linting, and focused test runs.
  No interactive editor was exercised; move-site assertions verify presentation of existing
  ownership evidence, not how ownership analysis selects the move site.
- Memory-harness and ABI-container gates do not apply: only diagnostic presentation changes.

### Exhaustive semantic conversion

The family dispatcher sends every `SemanticError` to exhaustive span and message helpers.
Direct `TypeError` values retain their existing exhaustive helpers. Other error families have
explicit omission cases; an open-trait fallback handles extensions outside editor conversion.
Messages are rendered only for available source spans. Regressions cover omitted families,
including a source-bearing backend error, and agreement between direct and wrapped type errors.
`sbtn "scalafmtAll; scalafixAll; testOnly mml.mmlclib.lsp.DiagnosticsTests mml.mmlclib.lsp.DiagnosticPresentationTests"`
passes all 14 tests. The smoke harness passes all eight fixtures. `sbtn testFull` passes 2,527
library and 9 CLI tests, with no skipped tests in the XML reports. Local compiler publication
and clean builds of all 37 benchmark binaries pass. QA enforcement, tracking links, and
`git diff --check` pass. Independent primary review and all four fresh-context dedicated checks
(code style/QA, test quality, readability, and code documentation) are complete with no actionable
findings. The review confirms all 24 semantic variants in both helpers and the `-Werror` build
configuration; no exhaustivity mutation probe was run.

## Plan and signoff

- Task creation: authorized.
- Implementation approval: granted for regressions, existing source-range/message mappings,
  editor/CLI verification, and exhaustive semantic conversion with separate error-family dispatch.
  Visibility enforcement and changes to backend diagnostic presentation are excluded.
- Workstream signoff: granted.
- Tracked item completion: approved; implementation, verification, and independent review complete.
- Commit and shipment authorization: granted for this repair, with the clean-review condition met.

## Task Working Memory

Moved-capture and visibility-payload presentation and exhaustive semantic conversion are complete.
Compiler gates, independent review, and signoff pass. QA-003/QA-004 are resolved with this repair.
There are no remaining implementation, verification, or signoff items.
