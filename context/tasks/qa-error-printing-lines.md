# Improve error-printing line lengths

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

`coding-rules.md` mandates max line length 100. `scalafmt` should normally enforce this;
  persistent violations suggest either a config gap or long string interpolations that scalafmt
  cannot break.

## Outcome

Revalidate and address the recorded QA concern within its stated scope.

## Scope

The retained design below defines the proposed scope; implementation requires a bounded plan.

### Retained QA report

Imported from the QA debt record. Counts, locations, freshness, and proposed
fixes describe that review; none have been reverified during this document migration.

- Recorded status: fresh, bottom priority.
- Locations (top offenders):
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/SemanticErrorPrinter.scala` -
    30 lines over 100 chars.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/ErrorPrinter.scala` -
    19 lines over 100 chars.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/yolo/inspect.scala` - 7 lines.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Member.scala` - 7 lines.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Type.scala` - 5 lines.
  - Smaller clusters remain in parser, codegen emitter, pretty-printer, and toolchain files.
- Problem: `coding-rules.md` mandates max line length 100. `scalafmt` should normally enforce this;
  persistent violations suggest either a config gap or long string interpolations that scalafmt
  cannot break.
- Smell level: low
- Impact: low. This is readability and rule conformance, not a behavior risk.
- Suggested direction: extract long interpolation pieces into named values or multiline
  `stripMargin` strings; verify `.scalafmt.conf` `maxColumn` is 100 and printers are not excluded.
- Scope decision: lowest priority; bundle as a single formatting pass when touching printers.

## Plan (Approval Gate)

- [ ] Revalidate the draft against current code and agree on a bounded implementation plan.

Approval: Spec-to-task migration authorized on 2026-09-10; implementation pending.

## Implementation Checklist

- [ ] Implement and verify the approved scope.

## Verification

Document migration only; no implementation checks run.

## Risks / Notes

Imported design proposals are not evidence of implemented behavior. Recheck source-location
and baseline claims when starting this task.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Not granted.

## Task Working Memory

Created from the retained spec during context cleanup. Not selected for active memory.
Next: discuss scope when the Author selects this task.
