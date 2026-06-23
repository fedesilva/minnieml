# Bug: Direct lambda lowering forwards a nested sibling's captures without deduping against the parent's own captures

**Where:** codegen, Direct-lambda capture lowering — `computeCodegenCaptureLayout` in
`modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/ExpressionCompiler.scala`.
**Severity:** code bloat. No known behavioral change for borrow captures; the move/ownership
path is not yet verified (see "Severity" below).
**Found in:** `astar3` IR (`solve_4`).

There is no separate lambda-lifting pass in mmlc. The compiler keeps one AST from parse to
codegen, and `CaptureAnalyzer` already dedupes each lambda's capture list by resolved id. The
duplication described here is introduced later, at codegen, by the Direct-lambda lowering path.

## Summary

A Direct lambda's LLVM signature is `(userParams..., captureTypes...)` — captures are passed as
trailing arguments. When a Direct lambda references a nested **Direct sibling**, codegen threads
that sibling's captures through as extra trailing params so it can call the sibling.

The problem: those forwarded captures are **appended** to the parent's own captures with no
dedup. Any binding captured by both the parent and the sibling ends up as two trailing params
carrying the same value.

## Evidence

In `astar3`, `solve` and `visit_neighbors` are sibling inner functions inside `astar`. `solve`
calls `visit_neighbors`. Source `solve` takes one argument (`h_size`); everything else is captured
from the `astar` scope. After lowering, `solve_4` has **10** parameters:

```
solve_4(i64      %0,   ; h_size
        MinHeap  %1,   ; open_set
        i64      %2,   ; goal_idx
        IntArray %3,   ; closed
        Grid     %4,   ; grid
        IntArray %5,   ; closed     <-- duplicate of %3
        IntArray %6,   ; g_score
        IntArray %7,   ; came_from
        i64      %8,   ; goal_idx   <-- duplicate of %2
        MinHeap  %9)   ; open_set   <-- duplicate of %1
```

The call site in `astar` passes identical values into both slots:

```
call ... @astar3_solve_4(
  i64 %18,                 ; h_size
  %struct.MinHeap %9,      ; -> %1
  i64 %2,                  ; -> %2  (goal)
  %struct.IntArray %8,     ; -> %3  (closed)
  %struct.Grid %0,         ; -> %4
  %struct.IntArray %8,     ; -> %5  (closed,    again)
  %struct.IntArray %7,     ; -> %6  (g_score)
  %struct.IntArray %3,     ; -> %7  (came_from)
  i64 %2,                  ; -> %8  (goal,      again)
  %struct.MinHeap %9)      ; -> %9  (open_set,  again)
```

`solve_4`'s params are two capture sets laid end to end:

- `solve`'s own captures:           `{ open_set, goal_idx, closed }`
- `visit_neighbors`'s captures:      `{ grid, closed, g_score, came_from, goal_idx, open_set }`

The three captured by both appear twice; the three only `visit_neighbors` touches appear once.
The leaf `visit_neighbors_3` is clean (6 distinct captures), so the defect is specifically in how
a parent re-forwards a nested sibling's captures.

## Root cause

`computeCodegenCaptureLayout` folds over `lambda.captures`:

- a plain capture becomes one value slot;
- a capture that resolves to a nested `DirectCallable` sibling is **expanded** into one slot per
  operand of that callable (`captureOperands`).

The expanded slots are appended with no check against the value slots already produced, so a
binding present in both lists gets a slot twice. This layout drives both the Direct trailing
params and the materialized-closure env fields, so any fix must hold for both.

## Why it matters

- Pure bloat for borrow captures: the same value flows into both slots; output is unaffected.
- The duplicated captures are not all scalars — `MinHeap` is `{IntArray, IntArray, i64}` (40 bytes
  by value) passed twice; `Grid` is 24. No standard LLVM pass merges two identical-valued formal
  params of an internal function, so the redundancy survives to codegen.
- It compounds with nesting depth: each tier re-forwards the tier below, so in a deeper closure
  tower the duplicates themselves get re-forwarded and the redundancy multiplies. The `astar`
  scope is shallow; a 3+ level nest would show it worse.

## Obstacle: binding identity is not threaded through

A correct dedup must key on the source binding, not on name or type (to avoid merging two
distinct bindings that happen to share a type). But by the time captures are forwarded, the
binding id is already gone: a forwarded operand is a `DirectOperand(operand, llvmType,
tbaaTypeName)` — an SSA string plus types, no `resolvedId`. So the first step of any fix is to
carry the source binding id on `DirectOperand` (or otherwise recover it) so the layout can dedup
on it.

## Expected behavior

When a parent's capture layout absorbs a nested sibling's captures, the result should be the
**union** of the two, keyed on the originating binding, so a single binding never produces two
trailing params (or two env fields). Both the formal param list and the call-site argument list
must point at the deduped set, and the dedup must survive across nesting depth.

## Suggested fix

1. Thread the source binding id onto `DirectOperand` so forwarded captures keep their identity.
2. In `computeCodegenCaptureLayout`, dedup value slots and expanded sibling-capture slots against
   each other by binding id, keeping the first slot and re-pointing later references at it.
3. Verify the same layout still produces correct env fields on the materialized-closure path,
   and that `innerScope` / `innerDirectable` rebinding stays consistent after dedup.

## Severity

The "no behavioral change" claim is verified only for borrow captures. `evaluateDirectCaptures`
registers `__free_*` cleanups for owned heap captures of **move** Direct lambdas; before treating
this as bloat-only, confirm that deduping does not drop or double a required free on the
move/ownership path.

## Repro

```
mmlc mml/samples/astar3.mml   # inspect the solve_4 signature in the emitted IR
```

## Suggested regression check

Assert that no emitted Direct function signature has two trailing params bound to the same source
binding. A focused test: a 3-level nested inner-function chain where the innermost references a
binding from the outermost, confirming exactly one forwarded param for that binding at each tier.
