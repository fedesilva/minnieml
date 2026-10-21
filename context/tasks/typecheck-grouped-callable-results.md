# Apply returned callables with parenthesized function types

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-21
- **Target Branch:** unassigned
- **External Reference:** none

## Execution Checklist

1. [ ] **planned** — Reproduce the inline failure and local-binding control.
2. [ ] **planned** — Approve and implement canonical callable-type handling with regressions.
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

## Outcome

Equivalent inline and locally bound returned callables have the same typing and application
behavior, including parenthesized callable types.

## Scope

- In scope: canonical callable-type handling for application results, with grouped-type
  regressions and a local-binding control.
- Out of scope: PAP ownership changes, parser grouping repairs, and LLVM type mapping.

## Plan (Approval Gate)

- [ ] Reproduce the diagnostic and inspect canonical type resolution at application boundaries.
- [ ] Propose a bounded repair using the existing type-resolution utilities.
- [ ] Cover grouped callable results, ordinary callables, and genuinely non-callable results.
- [ ] Verify and review the repair through the required compiler gates.

Approval: pending for implementation.

## Verification

Static inspection at `8984f79` confirms the different application-result and reference paths.
The review reproducer reports `cannot apply function of type 'TypeGroup' to argument of type
'Int'` and failure to infer `q`; a fresh execution of both examples remains pending.

## Risks / Notes

This is finding E from the returned-callee PAP review. It is separate from the historical
grouped-return LLVM mapping defect and the [parser grouping task](parser-parenthesized-lambda-arguments.md).

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted for the repair.

## Task Working Memory

The reproducer and control are embedded above. Next action: reproduce both and propose the
type-checker repair. The [returned-callee PAP work](unify-lambdas.md#b1-partial-application-of-a-returned-callee)
does not include this implementation.
