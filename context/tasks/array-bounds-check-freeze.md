# Let LLVM fold array bounds checks against known lengths

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Created:** 2026-11-25
- **Target Branch:** unassigned
- **External Reference (optional):** none

## Execution Checklist

1. [ ] **planned** — Reproduce the surviving checks on the Author's toolchain and record the LLVM version.
2. [ ] **planned** — Confirm that no path creates an array with `NULL` data and a positive length.
3. [ ] **planned** — Remove the `!arr.data` test from the six checked array accessors.
4. [ ] **planned** — Verify the optimized IR, the safe benchmarks, and the bounds-failure behavior.

## Tags

- codegen
- runtime
- performance

## Problem

The checked array accessors in
[mml_runtime.c](../../modules/mmlc-lib/src/main/resources/mml_runtime.c) test
`!arr.data || idx < 0 || idx >= arr.length`. The accessors are `ar_int_set`, `ar_int_get`,
`ar_str_set`, `ar_str_get`, `ar_float_set`, and `ar_float_get`.

The `!arr.data` test prevents LLVM from removing bounds checks that a known array length
already proves. In optimized `main` for
[mat-mul-safe.mml](../../benchmark/mat-mul-safe.mml), the length of every array is the constant
250000. Instead of folding, the length reaches the loops through a frozen aggregate:

```llvm
%.fr = freeze %struct.IntArray { i32 250000, ptr poison }
```

Every check then compares against an opaque extracted length, so none of them fold. The
inner loops are split into a check-free portion, a checked scalar tail, and per-row
trip-count arithmetic (`smax`, `udiv`, `umin`).

Benchmark evidence on Apple M5, `-O3 --cpu native`, 2026-11-15:

| Binary                | Time    | Instructions retired |
|-----------------------|---------|----------------------|
| `matmul-safe-mml`     | 45.4 ms | 1.40 B               |
| `matmul-nested-mml`   | 23.7 ms | 583 M                |
| `matmul-safe-c`       | 24.4 ms | 594 M                |
| `quicksort-safe-mml`  | 49.2 ms | —                    |
| `quicksort-safe-c`    | 44.2 ms | —                    |

`matmul-opt-safe-mml` runs within 3% of `matmul-opt-c`, so the loss shows up in the naive
loop shape.

`matmul-safe.c` avoids the problem because its `main` checks `!A.data` right after
`malloc` and returns early, which makes `!arr.data` known false inside the loops.
`ar_int_new` calls `mml_sys_oom_abort` on allocation failure, so MML array data also cannot
be `NULL` with a positive length. The optimizer does not learn that fact: the pointer
field appears as `ptr poison` in the frozen aggregate.

The test is redundant. `ar_int_new`, `ar_str_new`, and `ar_float_new` produce `NULL` data
only together with length 0, and `idx >= arr.length` already rejects every index of an
empty array.

The index arithmetic already carries `nsw`, so missing no-wrap flags are not the cause.

## Outcome

The checked accessors test only `idx < 0 || idx >= arr.length`. When array lengths are
compile-time constants, LLVM removes the checks, and optimized `mat-mul-safe.mml` has the
same loop structure as [mat-mul-nested.mml](../../benchmark/mat-mul-nested.mml).

## Scope

- In scope: the six checked accessors listed above; evidence that the change is safe;
  IR and benchmark verification.
- Out of scope: general bounds-check elimination for arrays whose lengths are not
  constants; changes to the mmlc optimization pipeline; the `[2 x i64]` ABI conversion that
  loses the non-null fact for the data pointer.

## Plan (Approval Gate)

- [ ] Run the safe benchmarks and inspect optimized IR on the Author's toolchain. Count
  `exit` paths left in optimized `main` for `mat-mul-safe.mml`.
- [ ] Audit array construction for any path that yields `NULL` data with a positive length,
  including FFI-declared constructors and generated struct constructors.
- [ ] Remove `!arr.data ||` from the six accessors. Leave the free and clone helpers' tests
  unchanged; they guard zero-length arrays, not indexing.
- [ ] Re-run the IR inspection and the matmul and quicksort benchmarks; run the full test
  suite and the sanitizer tests; confirm that out-of-bounds access on an empty array still
  reports the error and exits.

Approval: pending

## Implementation Checklist

- [ ] Edit `ar_int_set`, `ar_int_get`, `ar_str_set`, `ar_str_get`, `ar_float_set`, and
  `ar_float_get` in `mml_runtime.c`.

## Verification

Evidence from LLVM 18.1.3 (`opt -O3`, `target-cpu`=`apple-m1`, AArch64 Linux triple). The
emitted IR for `mat-mul-safe.mml` and `mat-mul-nested.mml` was linked with the runtime array
functions compiled for AArch64:

- `mat-mul-safe.mml`: 6 `exit` paths remain in optimized `main`, with the frozen
  `IntArray` aggregate shown above.
- `mat-mul-safe.mml` with `!arr.data ||` removed from `ar_int_get` and `ar_int_set`: no
  `freeze`, zero `exit` paths, and optimized `main` matches `mat-mul-nested.mml` apart from
  block and value names.
- Before optimization, the emitted IR for the two programs differs only in calling the
  checked or unchecked accessors.

Pending: reproduction and timing on the Author's toolchain.

## Risks / Notes

- With LLVM 18, the checked `mat-mul-safe.mml` loop still runs most iterations in its
  check-free portion. That build does not reproduce the 2× slowdown, which depends on
  the LLVM version and target used by mmlc. Verification must use the Author's toolchain.
- The pass that inserts the `freeze` has not been identified. The `!arr.data` test is
  confirmed as the trigger; the experiment does not explain the mechanism.
- Benchmark sizes are compile-time constants. With runtime sizes, the checks stay in both C
  and MML; this task does not change that.

## Signoff

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: not granted

## Task Working Memory

The problem and the fix are identified from LLVM 18 evidence; nothing in the runtime has
changed. Next action: on the Author's toolchain, build `mat-mul-safe.mml` and count `exit`
paths in optimized `main`, then compare with the same build after removing `!arr.data ||`.
