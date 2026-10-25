# Actionable codegen issues

Consolidated from all four IRs.

## `nsw`/`nuw` on arithmetic

Concrete cost: freeze before `sdiv`/`srem` by 30 in the hot loop, blocking
range propagation. Also prevents LLVM from choosing `udiv`/`urem` for values
known to be non-negative (grid indices are always 0..299).

## TBAA root mismatch

MML emits under `"MML TBAA Root"`, runtime uses `"Simple C/C++ TBAA"`. During
LTO the trees can't merge, so all MML-specific type discrimination collapses
to `long long`. No distinction between `g_score[i]`, `closed[i]`,
`came_from[i]`, `grid.walls[i]` survives. Fix: root MML's TBAA under
`"Simple C/C++ TBAA"` → `"omnipotent char"`.

## Direct calls for non-escaping closures

In progress. LLVM does devirtualize at `-O1`+, but emitting direct calls from
the start would make the unoptimized IR debuggable and removes a dependency
on LLVM's devirtualization succeeding in more complex cases.

## `noalias` on array data pointers

Ownership tracking knows `g_score`, `closed`, `came_from`, `grid.walls`,
`heap.indices`, `heap.scores` are all distinct allocations. Annotating their
`i64*` backing pointers as `noalias` would let LLVM reason about
non-interference without tracing pointer origins through the inlined code.
Most visible in `sift_down` where LLVM re-loads `scores[smallest]` because it
can't prove `heap_swap` on indices didn't alias scores.
