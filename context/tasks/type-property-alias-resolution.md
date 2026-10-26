# Unify type-property alias resolution

## Metadata

- **Owner:** Author
- **Status:** complete
- **Kind:** improvement
- **Priority:** LOW
- **Created:** 2026-10-22
- **Target Branch:** codex/type-property-alias-resolution
- **Review:** compiler gates and independent review pass; signed off.

## Execution Checklist

1. [x] **complete** — Review alias resolution in the four type-property queries and agree on scope.
2. [x] **complete** — Approve a bounded repair that preserves resolved declaration identity.
3. [x] **complete** — Implement consistent queries and regression coverage.
4. [x] **complete** — Run compiler gates and independent review; obtain scoped signoff.

## Problem

Type-property queries must preserve declaration identity through aliases and their callers.
Reducing a resolved type to a short name permits another declaration to be selected when names
collide, including with stdlib declarations. Name-based recursion guards can also conflate
separate recursive types. This is the scope of
[QA-001](qa-misses.md#qa-001-preserve-resolved-type-identity-in-type-utilities).

## Outcome

Heap classification and memory-function selection agree on the resolved nominal declaration,
including through alias chains. Distinct declarations retain their identity, and erroneous
or unresolved types do not silently select a declaration with the same short name.

## Scope

- Review `cloneFnFor`, `isStructWithHeapFields`, `freeFnFor`, and `isHeapType` in
  [TypeUtils.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeUtils.scala),
  their shared resolution helpers, and the identity available at their callers.
- Establish the smallest consistent repair and its relationship to the broader QA-001 work.
- Preserve explicit free functions, nominal type distinctions, recursive-struct termination,
  and the prohibition on cloning function-containing structs.
- Review after [Unify lambdas](completed/unify-lambdas.md). This is a follow-up, not a lambda-completion gate.

## Plan (Approval Gate)

- [x] Review identity loss in type-property queries and their callers.
- [x] Approve the implementation boundary and regression controls.
- [x] Centralize resolved declaration queries across heap, destruction, clone, native representation,
  and pointer properties; preserve IDs through callers and recursive traversal.
- [x] Consolidate memory-function selection, including generated struct helpers before registration.
- [x] Cover aliases, collisions, recursion, missing references and functions, and explicit destructors.
- [x] Run compiler gates and independent review.

Approval: implementation and associated task bookkeeping approved. Preserve language semantics.
Larger opportunities require separate PROPOSAL tasks.

## Verification

Baseline source inspection established stdlib-first short-name lookup and identity loss in
callers, alias branches, and recursive heap classification. Regression tests construct resolved
AST declarations with colliding names to isolate those query contracts; they do not establish
source-language support for multiple modules.

Verification on 2026-10-25:

- `sbtn "scalafmtAll; scalafixAll; testFull"` passes: 1,955 library tests and 9 CLI tests.
- `TypeUtilsTests` includes 23 passing tests, covering alias chains, nominal and stdlib-name
  collisions, recursive struct identities, alias cycles, unresolved and failed types, explicit
  destructors, missing registrations, and pending generated helpers.
- `./tests/smoke/run.sh all` passes all 8 fixtures.
- `sbtn mmlcPublishLocal` succeeds.
- `make -C benchmark clean` and `make -C benchmark mml` succeed: 37 benchmark builds.
  This is compilation evidence, not a performance measurement.
- `./tests/mem/run.sh all` passes 52/52 fixtures with ASan and LSan (71 seconds).
- Independent primary review and separate fresh-context code-style/QA, test-quality, and
  readability checks report no actionable findings. No candidate required claim verification.
- Host verification used macOS arm64, Clang 23.1.1, GraalVM Java 25.0.4.1, and sbt 2.0.10
  through `sbtn`. No ABI change is included; no Linux-container or cloud verification was run.
- Author signoff is complete.

## Risks / Notes

A shared helper must preserve declaration identity through its callers. Consolidating code
while retaining ambiguous name lookup would leave QA-001 unresolved.

## Signoff

- Workstream signoff: complete
- Tracked item completion: complete
- Commit authorization: granted for the scoped implementation and closure records; push not authorized.

## Task Working Memory

Typed queries, caller migration, and regression coverage are implemented. Shared memory-function
selection distinguishes registered bindings from struct helpers being generated in their owning
module. Formatting, lint, full tests, smoke, publication, benchmark builds, and memory checks pass.
Independent primary review and all three dedicated checks pass with no actionable findings.
Signoff is complete. No implementation or verification work remains. Local commit is authorized;
push is not authorized.
