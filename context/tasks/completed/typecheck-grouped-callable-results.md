# Unify and simplify callable-type handling

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-21
- **Completed:** 2026-10-23
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference:** none

## Execution Checklist

1. [x] **complete** — Map divergent callable-type handling and reproduce the grouped-return bug.
2. [x] **complete** — Approve and implement a bounded unification that removes duplicated policy.
3. [x] **complete** — Address review corrections, complete verification and review, obtain signoff.

## Problem

Inline and locally bound returned callables must behave identically. Regression case:

```mml
fn apply(f: Unit -> (Int -> Int -> Int)): Int =
  let q = (f ()) 10;
  q 2;
;
```

Equivalent local-binding control:

```mml
fn apply(f: Unit -> (Int -> Int -> Int)): Int =
  let g = f ();
  let q = g 10;
  q 2;
;
```

The baseline at `d4566a1` rejects the inline case before partial-application elaboration.
Application validation directly matches `TypeFn` for returned callees, while references and
expected-argument inference use a wrapper-aware callable query. The divergent policies make
parenthesized and aliased types behave differently depending on the expression form.

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
- In scope: repair the downstream returned-callee PAP classification and lifetime handling
  exposed by the grouped/aliased regressions.
- Out of scope: parser grouping repairs and LLVM type mapping.

## Plan (Approval Gate)

- [x] Map callable-type decisions in inference and application validation; distinguish necessary
  differences from duplicated policy. Trace traversal and normalization to establish where
  grouped types should be handled.
- [x] Reproduce the inline failure and local-binding control against the current compiler.
- [x] Propose a bounded unification using existing utilities, identifying redundant branches
  to remove and the lookup, inference, and diagnostic behavior to preserve.
- [x] Implement the approved simplification and cover the regression cases listed in Scope.
- [x] Verify and review the repair through the required compiler gates after review corrections.

Approval: granted for shared callable lookup using `Callee` and existing `extractTypeFn`,
consolidated application validation, preserved reference diagnostics and argument-first local
binding inference, semantic and native regressions, compiler gates, and task bookkeeping.
Scope extension approved: downstream returned-callee PAP classification and lifetime handling.

## Review Corrections

F1: call arguments carry ownership evidence established inside their lexical scopes. Consuming
boundaries validate through the shared result analysis; ordinary and staged calls use the same
argument facts for lifetime checks. Duplicate generic sink errors are removed. Regressions cover
inline/bound consuming calls and constructors, rejected borrowed results, and O0/O3 execution.

F2: ownership analysis writes resolved borrowed-capture IDs and clears pending retention IDs.
Invocation cleanup uses the resolved set; contract discovery treats pending retained captures
conservatively. Coverage includes a PAP that consumes a String payload while borrowing its callee.

F3–F6: application diagnostics select reference-specific policy once, ownership-return predicates
use named conditions, the orphan phase label is removed, and capture metadata defines its IDs.
F7–F8: literal-ascription and mismatch assertions use exact resolved type identities.
Conditional-field diagnostics also assert each written field reference's start and end offsets.

Correction implementation and verification are approved. Compiler gates and independent
re-review passed. The LLVM Unit failure is tracked separately in
[Unify Unit parameter lowering](unit-parameter-lowering.md).

## Verification

Baseline at `d4566a1`, 2026-10-23: each embedded example was extracted verbatim and run
with `sbtn "run ast <example>.mml -b <output-dir>"`. The inline example exited 1 with
`cannot apply function of type 'TypeGroup' to argument of type 'Int'` and failure to infer
`q`. The local-binding control exited 0 and emitted its AST.

`TypeResolver.resolveTypeSpecWithMap` traverses `TypeGroup` and preserves its wrapper.
`Simplifier` removes expression grouping, not type grouping. Callable queries must handle
these type wrappers; no missing grouped-type traversal case was found.

The inline scalar consuming-call and constructor regressions in `CallableTypeTests` failed
before F1's correction; their local-binding controls passed. F1's fix uses lexical argument
ownership evidence. Full-suite iterations exposed duplicate generic sink diagnostics;
consuming boundaries report the specific source error once. Conditional borrowed fields
retain one error per invalid branch. The exact diagnostic counts and identities are tested.

Verification after review corrections, 2026-10-23:
Host: arm64 macOS, Homebrew Clang 23.1.1, target `arm64-apple-darwin25.6.0`.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: formatting and linting passed; 1,466 library
  tests and 9 CLI tests passed, with zero failures or errors and no reported ignores.
- `CallExitBlockTests` validates LLVM and executes scalar/heap inline and bound sinks,
  unused cleanup, and a borrowed callee with a consuming String payload under ASan/LSan
  at O0 and O3. These tests are included in the full-suite result.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn mmlcPublishLocal`: passed after smoke verification.
- `make -C benchmark clean` and `make -C benchmark mml`: passed; build verification,
  not performance measurements.
- `./tests/mem/run.sh all`: 52/52 passed ASan+LSan.
- Scoped diff checks, QA enforcement, and whole-record tracking review passed.
- Independent re-review found no compiler correctness, QA, or readability issues. One independently
  confirmed test gap required distinct conditional-field diagnostic spans; those assertions are
  added and the full suite passed again. Fresh narrow re-review and its separate QA, test-quality,
  and readability checks passed. No actionable findings remain.
- No ABI or target-layout changes; Linux ABI verification is not applicable.

## Risks / Notes

This is finding E from the returned-callee PAP review. It is separate from the historical
grouped-return LLVM mapping defect and the [parser grouping task](../parser-parenthesized-lambda-arguments.md).

## Signoff

- Workstream signoff: granted, 2026-10-23.
- Tracked item completion: complete, 2026-10-23.
- Commit authorization: granted for the callable-type repair and associated task bookkeeping.

## Task Working Memory

`TypeChecker` preserves `Callee` and shares callable lookup between inference and validation.
`TypeUtils` owns canonical type resolution across schemes, groups, aliases, and cycles.
Literal ascriptions are resolved before checking; immediate local bindings retain argument-first
inference.

Returned-callee partial applications retain their callee according to lexical ownership.
Argument analysis carries established ownership into call boundaries, and capture analysis
finalizes borrowed IDs before cleanup. Semantic and native regressions cover owned, borrowed,
and static callees, exact diagnostic identities and locations, and O0/O3 cleanup.

Implementation, required verification, independent review, and signoff are complete.
No scoped work remains. LLVM Unit parameter lowering remains planned in
[Unify Unit parameter lowering](unit-parameter-lowering.md).
