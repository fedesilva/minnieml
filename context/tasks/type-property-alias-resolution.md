# Unify type-property alias resolution

## Metadata

- **Owner:** Author
- **Status:** design
- **Kind:** improvement
- **Priority:** LOW
- **Created:** 2026-10-22
- **Target Branch:** unassigned
- **Review:** pending; review after Unify lambdas, before implementation planning.

## Execution Checklist

1. [ ] **design** — Review alias resolution in the four type-property queries and agree on scope.
2. [ ] **planned** — Approve a bounded repair that preserves resolved declaration identity.
3. [ ] **planned** — Implement consistent queries and regression coverage.
4. [ ] **planned** — Run compiler gates and independent review; obtain scoped signoff.

## Problem

`TypeUtils.cloneFnFor` and `isStructWithHeapFields` follow canonical aliases to their
resolved declarations. `freeFnFor` and `isHeapType` reduce the alias target to a name and
look it up again. The queries can select different declarations when short names collide.
All four entry points also accept names, so sharing the alias resolver alone does not
settle the broader identity loss described in [QA-001](qa-misses.md#qa-001-preserve-resolved-type-identity-in-type-utilities).

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

- [ ] Reproduce query disagreement with aliases and colliding names, including stdlib names.
- [ ] Review resolved-type inputs, shared declaration lookup, and handling of unresolved types.
- [ ] Approve the implementation boundary and regression controls before code changes.

Approval: review and planning only; implementation pending.

## Verification

Source inspection confirms the split: `underlyingTypeByName` retains a canonical alias
target's resolved ID, while the alias branches in `freeFnFor` and `isHeapType` call
`getTypeName` and repeat name lookup. No failing runtime example is established here.

Required coverage includes alias chains, same-name nominal declarations, explicit and default
memory-function names, scalar and heap structs, function-containing fields, and recursive types.
Run applicable compiler gates and independent review for the approved implementation.

## Risks / Notes

A shared helper must preserve declaration identity through its callers. Consolidating code
while retaining ambiguous name lookup would leave QA-001 unresolved.

## Signoff

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: not granted

## Task Working Memory

Review pending after Unify lambdas. Compare the four query paths and reproduce any disagreement,
then present a bounded plan. Existing lambda alias regressions provide baseline coverage;
the follow-up needs collision controls and a clear decision on name-based API boundaries.
