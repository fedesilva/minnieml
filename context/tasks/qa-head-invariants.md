# Make nonempty collection invariants explicit

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

`.head` on `List`/`Seq` throws `NoSuchElementException` on empty input. Many sites have
  an upstream invariant, but the invariant is implicit at the call site.

## Outcome

Revalidate and address the recorded QA concern within its stated scope.

## Scope

The retained design below defines the proposed scope; implementation requires a bounded plan.

### Retained QA report

Imported from the QA debt record. Counts, locations, freshness, and proposed
fixes describe that review; none have been reverified during this document migration.

- Recorded status: fresh; the older entry undercounted current usage.
- Recorded scan: 38 `.head` sites in main sources.
- Locations:
  - API/parser/AST: `api/FrontEndApi.scala:17,20`, `api/CompilerApi.scala:219`,
    `api/ParserApi.scala:32`, `ast/TypeUtils.scala:37`, `parser/expressions.scala:130`.
  - Semantic: `LoweredCaptureLayout.scala:29`, `DuplicateNameChecker.scala:37,44,51,212`,
    `TailRecursionDetector.scala:48`, `TypeChecker.scala:190,803,1122,1124,1126,1283`,
    `ExpressionRewriter.scala:506,585`, `MaterializationAnalyzer.scala:78,202,250`,
    `OwnershipAnalyzer.scala:851,1337,1559`.
  - Codegen: `PreCodegenValidator.scala:106`, `LlvmToolchain.scala:895`,
    `emitter/expression/Applications.scala:46,47`, `emitter/package.scala:191,215`,
    `emitter/NominalTypeNameResolver.scala:13`.
  - LSP/printing: `lsp/AstLookup.scala:625,753`, `lsp/SemanticTokens.scala:340`,
    `util/prettyprint/ast/Term.scala:105`.
- Problem: `.head` on `List`/`Seq` throws `NoSuchElementException` on empty input. Many sites have
  an upstream invariant, but the invariant is implicit at the call site.
- Smell level: medium-high in semantic/codegen paths, lower in pretty-printers and explicitly
  guarded parser paths.
- Impact: medium. A future refactor that relaxes an invariant can produce an opaque crash instead
  of a typed compiler error.
- Suggested direction:
  - For sites where emptiness is possible, switch to `headOption` and propagate through existing
    `Either`/`Option` plumbing.
  - For sites where emptiness is structurally impossible, encode the invariant in the type, such as
    `NonEmptyList`/`NonEmptyChain`, or destructure with pattern matching near the source of truth.
- Scope decision: triage per file; bundle by module rather than fixing all sites at once.

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
