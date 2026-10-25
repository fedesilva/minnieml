# Remove provenance heuristics from lambda scope analysis

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-11
- **Target Branch:** Unassigned
- **External Reference (optional):** None

## Problem

Semantic passes use source-origin equality to recognize scoped lambda applications.
`TypeChecker.isSimpleLetBinding` uses this test when deciding whether inference traverses
a binding body and propagates aliases. `DuplicateNameChecker.isScopedBindingApp` uses it
when collecting bindings for duplicate-name checks. All `SourceOrigin.Synth` values compare
equal, so source provenance participates in classification instead of merely locating diagnostics.

Source evidence inspected on 2026-09-11:

- [TypeChecker.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala):
  `isSimpleLetBinding`, lines 118–125; inference branch, lines 184–194.
- [DuplicateNameChecker.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DuplicateNameChecker.scala):
  `walkScopeExpr`, lines 159–180; `isScopedBindingApp`, lines 222–223.
- [common.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala):
  `SourceOrigin`, lines 53–63.

These are observed implementation facts and a behavioral risk, not a demonstrated runtime bug.
The [sequence-lambda metadata task](sequence-lambda-metadata.md) separately tracks magic
`__stmt` recognition. Its proposed metadata/shared-marker solution needs reconciliation with
this task before implementation to avoid duplicate work or conflicting representations.

## Outcome

Scope analysis derives behavior from lambda/application structure and binding relationships
where possible. Equivalent constructs do not receive different inference or scope treatment
merely because their source locations differ or they are compiler-generated. Source locations
remain available for diagnostics. Any necessary distinction that cannot be derived has an
explicit, justified representation consistent with the unified lambda model.

## Scope

- In scope: source-origin classification in inference and duplicate-name analysis; directly
  related scope/sequence recognition; reconciliation of magic-name handling with the existing
  sequence-lambda task; focused semantic regressions and affected documentation.
- Out of scope: binding identity/local-construction consolidation, ownership-transfer fixes,
  and a compiler-wide AST phase redesign. Do not introduce `ScopeLambda` versus
  `CallableLambda` semantic categories.

## Plan (Approval Gate)

- [ ] Identify the intended distinction and phase requirements at each affected classifier.
- [ ] Reconcile scope and acceptance with `sequence-lambda-metadata.md`; assign each shared
  change to one task and obtain Author approval for changes to that task's meaning.
- [ ] Present a bounded design deriving behavior from structure/identity first; justify any
  metadata that remains necessary and obtain approval before implementation.

Approval: Task creation authorized by the Author on 2026-09-11. Implementation planning pending.

## Implementation Checklist

- [ ] Replace the approved provenance and magic-name heuristics through the agreed mechanism.
- [ ] Verify inference, alias handling, shadowing/duplicate-name diagnostics, sequencing,
  and direct lambda application across the affected phases.
- [ ] Verify classification under varied source spans and synthetic origins for equivalent
  constructs, distinguishing expected diagnostic-location changes from semantic behavior.
- [ ] Update affected design documentation and reconcile completion evidence with the
  sequence-lambda task without claiming its completion automatically.
- [ ] Run applicable compiler gates and independent review.

## Verification

Creation is based on targeted source inspection only. No new compiler tests or runtime
reproductions were run for this task; implementation and behavioral verification are pending.

## Risks / Notes

Follow-up to the lambda workstream's
[binding identity and local-construction step](unify-lambdas.md#establish-binding-identity-and-local-construction-invariants).
That step establishes construction guarantees; it does not resolve these classification choices.
The [lambda semantic goals](unify-lambdas.md#semantic-goals) require a regular lambda model.
Explicit metadata is a design option requiring justification, not a predetermined solution.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Tracking-only local commit authorized by `finish errand` on 2026-09-11;
  implementation remains pending.

## Task Working Memory

The Author requested a separate follow-up linked from the intermediate lambda item on
2026-09-11. This task remains in the backlog; it has not been selected for active memory.
Next: reconcile the overlapping sequence-lambda task and approve a bounded implementation plan.
