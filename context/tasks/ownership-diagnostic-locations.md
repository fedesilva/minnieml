# Preserve ownership diagnostic source locations

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:ownership`, `component:source-origins`, `concern:diagnostics`
- **Created:** 2026-10-21
- **Target Branch:** dev-2026-03-21-lambdas

## Execution Checklist

1. [x] **complete** — Implement, verify, review, and sign off the source-location repair.

## Problem

Generated temporaries can lose diagnostic locations, and retained dependency references can
point an error at an earlier valid use. The B4 implementation and verification are preserved below.

## Outcome

Generated local references retain diagnostic origins, and moved-owner evidence is separate
from the current use. The scoped repair is verified and signed off; diagnostic polish remains
independent of Unify lambdas completion.

## Scope

The component and concerns described above are independent backlog work, outside Unify lambdas
completion and merge requirements. Preserve language semantics, ownership enforcement, evaluation
order, and runtime behavior; any discovered correctness defect needs separate assessment.

## Plan (Approval Gate)

Approval: the B4 shared source-origin repair and its tests are implemented, verified, and
signed off. The additional diagnostic and maintenance findings are explicitly deferred to
standalone LOW-priority tasks. Their implementation requires separate bounded plans.

## Verification

- **Implementation approval:** granted for shared source-origin preservation in local bindings,
  separation of current uses from moved-owner evidence, and exact diagnostic regression coverage.
- **Plan:** migrate PAP and ownership temporaries, including returned callees, to shared
  source-aware reference construction. Centralize move evidence and report each owner violation
  at its current use. Verify semantic identities, use/move locations, CLI and LSP output, and
  existing returned-callee diagnostics; run compiler gates and independent review.
- **Baseline (`f439ffa`):** both cases below reproduce through `sbtn "run ir ..."`.
  The field alias produces an extra error at the earlier `h.f`; the inline PAP produces a
  consuming-parameter error at `[synthetic]`. Removing `drop h` permits IR generation;
  explicitly binding the PAP reports the argument error at `p` in `borrow p`.
- **Signoff:** complete for the implemented source-location repair. Local commit authorized;
  push is not authorized. The linked follow-up tasks remain open.
- **Baseline cases:** after `let q = h.f; let a = drop h; q 2`, a second `Use of 'h' after move`
  points at the earlier, valid `h` reference. In `borrow (take (int_to_str 123))`,
  a diagnostic points at `[synthetic]` rather than the source call.
- **Baseline cause:** [OwnershipAnalyzer.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala)
  reuses a stored dependency `Ref` for the first error;
  [LocalBindings.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/LocalBindings.scala)
  gives the hoisted PAP binding a synthetic origin for the second.
- **Outcome:** preserve the offending source use and source origin through dependency
  and temporary lowering. Assert diagnostic class, definition identity, use site, and
  move site where applicable.

- **Implementation:** `LocalBindings` preserves replaced-expression spans on generated references
  and expression wrappers. `SourceOrigin.Derived` carries those diagnostic spans while editor
  tokens and symbol lookup distinguish them from written references. PAP payloads, returned
  callees, ownership arguments, saved results, and early-cleanup arguments share the constructor.
  Move diagnostics carry the current use, moved-owner identity, and dependency evidence separately.
  Shared normalization preserves primary errors, merges repeated evidence for one owner/use,
  and prefers written reference identities when lowering also produces an error at that use.
- **Coverage:** `OwnershipDiagnosticTests` checks field aliases, alias chains, ordinary and staged
  borrowing arguments, valid uses before destruction, distinct uses and owners, inline/bound PAP
  locations, and CLI/LSP rendering. Local-binding tests check generated origins and identities;
  editor tests check tokens, hover, and definition lookup. Existing shadowing, capture, and
  returned-callee tests remain active.
- **Verification (2026-10-21):** `sbtn "scalafmtAll; scalafixAll; testFull"` passes:
  1,347 library tests and nine CLI tests, with 36 existing ignores and no compiler warnings.
  Existing capture and staged-call identity regressions pass alongside the new diagnostics tests.
  `./tests/smoke/run.sh all` passes 8/8 before `sbtn mmlcPublishLocal` succeeds.
  `make -C benchmark clean` and `make -C benchmark mml` pass all 13 MML builds;
  these are build checks, not performance measurements. The published compiler reports one
  field-alias error at `q 2`, locates the inline PAP contract error at `take (int_to_str 123)`,
  retains the bound-PAP diagnostic at `p`, and generates IR for the valid field-alias control.
  `./tests/mem/run.sh all` passes ASan+LSan checks for 52/52 fixtures on macOS arm64
  with LLVM/Clang 23.1.1. Generated MML IR lacks full ASan instrumentation (T1);
  these results retain that coverage limit. No Linux execution or performance result is claimed.
  Local QA and focused tracking review have no actionable findings.
- **Independent review:** style/QA and readability checks found no actionable issues. An
  independently confirmed test gap required different reference and diagnostic spans in a renderer
  regression. A conditional borrowing-argument fixture asserts that distinction and checks both
  CLI printers and the LSP range; all ten diagnostics tests pass. A parenthesized-reference
  fixture did not distinguish spans, and an initial conditional fixture had a syntax error;
  both test-development failures are resolved. The final full-suite result above includes the
  corrected fixture. Review also passed 85 focused tests and 55 staged-call tests before this
  addition. No implementation defect was found.
  Fresh narrow re-review completed all three dedicated checks with no actionable findings.
  A separate review established additional diagnostic and test-quality gaps; those are deferred
  in the linked follow-up tasks below and do not establish an ownership-enforcement defect.

## Signoff

- Workstream signoff: complete for the implemented location repair.
- Tracked item completion: complete; additional review findings are explicitly deferred.
- Commit authorization: granted for the repair and associated task extraction; push not authorized.

## Task Working Memory

The implemented source-location repair is verified and signed off on the recorded branch.
No additional diagnostic work blocks Unify lambdas completion.
Follow-ups: [provenance](ownership-diagnostic-provenance.md),
[wording](ownership-diagnostic-wording.md), [editor tests](lsp-generated-reference-regressions.md),
[contracts](compiler-diagnostic-contract-documentation.md), and
[normalization readability](ownership-diagnostic-maintainability.md).
