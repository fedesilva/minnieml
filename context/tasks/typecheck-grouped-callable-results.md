# Unify and simplify callable-type handling

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-21
- **Target Branch:** unassigned
- **External Reference:** none

## Execution Checklist

1. [ ] **planned** — Map divergent callable-type handling and reproduce the grouped-return bug.
2. [ ] **planned** — Approve and implement a bounded unification that removes duplicated policy.
3. [ ] **planned** — Verify typing, native behavior, and compiler gates; obtain signoff.

## Problem

Applying an application result whose declared type is a parenthesized function type fails:

```mml
fn apply(f: Unit -> (Int -> Int -> Int)): Int =
  let q = (f ()) 10;
  q 2;
;
```

The equivalent explicit local binding passes type checking:

```mml
fn apply(f: Unit -> (Int -> Int -> Int)): Int =
  let g = f ();
  let q = g 10;
  q 2;
;
```

In [TypeChecker.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala),
`determineApplicationType` matches an `App` or `Lambda` callee's type directly against
`TypeFn`, rejecting `TypeGroup`. The reference path resolves the callable through
`extractTypeFn`. This occurs before partial-application elaboration.

Expected-argument inference uses `extractTypeFn` for references, applications, and lambdas,
while application validation uses different rules for these callee forms. The helper unwraps
groups, schemes, and aliases; the direct match bypasses that resolution. These paths duplicate
the decision about whether a type is callable and can diverge.

## Outcome

Callable-type resolution and application checking share one consistent policy across references,
returned functions, and lambdas. Remove redundant handling while preserving necessary lookup,
contextual inference, and diagnostic behavior. Equivalent inline and locally bound returned
callables behave identically as a consequence of this unification.

Completion requires simplification of the divergent paths as well as passing regressions.
An isolated `TypeGroup` case that leaves duplicated resolution policy in place is insufficient.

## Scope

- In scope: unify and simplify callable-type resolution and application checking in the type
  checker, including expected-argument inference and existing type-resolution utilities.
  Preserve legitimate differences in reference lookup and diagnostics.
- Regression coverage: references, returned functions, and lambdas; grouped, aliased, and
  scheme-wrapped callable types; ordinary callables and genuinely non-callable values.
- Out of scope: PAP ownership changes, parser grouping repairs, and LLVM type mapping.

## Plan (Approval Gate)

- [ ] Map callable-type decisions in inference and application validation; distinguish necessary
  differences from duplicated policy. Trace traversal and normalization to establish where
  grouped types should be handled.
- [ ] Reproduce the inline failure and local-binding control against the current compiler.
- [ ] Propose a bounded unification using existing utilities, identifying redundant branches
  to remove and the lookup, inference, and diagnostic behavior to preserve.
- [ ] Implement the approved simplification and cover the regression cases listed in Scope.
- [ ] Verify and review the repair through the required compiler gates.

Approval: pending for implementation.

## Verification

Static inspection at `8984f79` confirms the different application-result and reference paths.
The review reproducer reports `cannot apply function of type 'TypeGroup' to argument of type
'Int'` and failure to infer `q`; a fresh execution of both examples remains pending.

Static inspection at `8e69b2e` confirms that `expectedArgumentType` uses `extractTypeFn`
for all three callee forms, but `determineApplicationType` directly matches `TypeFn` for
applications and lambdas. `TypeResolver.resolveTypeSpecWithMap` traverses `TypeGroup` and
preserves its wrapper. Whether an earlier normalization step should remove that wrapper
remains to be established; no missing traversal case is confirmed.

## Risks / Notes

This is finding E from the returned-callee PAP review. It is separate from the historical
grouped-return LLVM mapping defect and the [parser grouping task](parser-parenthesized-lambda-arguments.md).

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted for the repair.

## Task Working Memory

The design direction is unification and simplification of callable-type handling; the
grouped-return bug is a regression case for that shared policy. Next action: map the divergent
paths and normalization contract, reproduce both embedded examples, and propose a bounded
implementation plan. Implementation approval remains pending.

The [returned-callee PAP work](unify-lambdas.md#b1-partial-application-of-a-returned-callee)
is complete and does not include this implementation.
