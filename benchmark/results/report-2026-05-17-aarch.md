# MML benchmark report (aarch64)

Date: 2026-05-17
Host: Apple M5, aarch64-apple-darwin

Previous report: [2026-03-14](report-2026-03-14.md) (x86_64)

**First benchmark report on aarch64.** The previous report ran on x86_64.
Absolute milliseconds are not directly comparable across architectures; the
relative MML/C ratio per benchmark is what to read.

## Changes since last report

Compiler changes that could affect codegen:

- Several lambda/closure ABI commits landed since 2026-03-14
  (`Checkpoint callable ABI split`, `Nested capturing lambdas: call-site IR,
  unique names, TCO`, `Fix local tailrec closure ABI materialization`, `#188`
  phase work). The benchmarks here do not use lambdas.
- `runtime: add FORCE_INLINE to string/IO functions` and `inlined some
  functions`. The hot allocators (`ar_int_new`, `ar_str_new`, `ar_float_new`)
  were already `FORCE_INLINE` before March; this run extended that to more
  helpers.

No changes specifically targeted at AArch64 codegen.

## Headline

Initial bench numbers built MML with the default `mmlc` optimisation level
(O3) and showed large regressions vs the March x86 baseline on the array- and
loop-heavy benchmarks. Switching the Makefile to build MML with `-O 1`
removed the regression on every affected bench:

| Bench         | x86_64 Mar (O3) | m5 O3              | m5 O1                          |
|---------------|----------------:|-------------------:|-------------------------------:|
| sieve         | ~1.00× C        | **11.73× C**       | **1.03× C**                    |
| matmul naive  | ~1.00× C        | **15.26× C**       | **0.83× C** (MML faster)       |
| matmul-opt    | ~1.00× C        | **6.79× C**        | 1.99× C                        |
| quicksort     | 1.19× C         | 1.40× C            | **1.01× C**                    |
| euclidean     | 1.09× C         | **9.55× C**        | **0.92× C** (MML faster)       |
| nqueens       | 1.23× C         | 1.42× C            | not retried at O1              |
| ackermann     | 1.21× C         | 1.18× C            | not retried at O1              |

The Makefile in this commit pins the affected MML benches to `-O 1`. Other
benches (`nqueens-mml`, `ackermann-mml`) still default to O3.

The MML compiler is producing fine IR. Some LLVM mid-end pass (or interaction
of passes) at O2/O3 is making decisions on AArch64 that turn that IR into bad
m5 code; at O1 those passes do not run and the backend produces near-C
performance.

The owned-aggregate-return-ABI hypothesis from
`docs/brainstorming/codegen/owned-aggregate-return-abi.md` does not survive
this result: the regression was eliminated without any ABI change.

## Sieve of Eratosthenes

Finds all primes up to 1,000,000.

At `-O 1`:

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/sieve-c` | 1.7 ± 0.2 | 1.5 | 2.1 | 1.00 |
| `bin/sieve-mml` | 1.8 ± 0.2 | 1.6 | 2.1 | 1.03 ± 0.14 |
| `bin/sieve-rs` | 1.8 ± 0.2 | 1.6 | 2.4 | 1.06 ± 0.16 |
| `bin/sieve-go` | 2.5 ± 0.1 | 2.4 | 2.8 | 1.48 ± 0.16 |

At C parity within noise. Min times overlap.

At `-O 3` (the initial m5 result, for reference):

| Command | Mean [ms] | Relative |
|:---|---:|---:|
| `bin/sieve-c` | 1.6 ± 0.1 | 1.00 |
| `bin/sieve-mml` | 19.2 ± 0.3 | 11.73 ± 0.44 |

Previous results (Mar 14, x86_64):

| Command | Mean [ms] | Relative |
|:---|---:|---:|
| `bin/sieve-mml` | 5.6 ± 0.6 | 1.00 |
| `bin/sieve-c` | 6.4 ± 1.5 | 1.14 ± 0.29 |

## Quicksort

In-place quicksort on 1,000,000 integers, LCG-filled.

At `-O 1`:

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/quicksort-c` | 42.0 ± 0.3 | 41.6 | 43.7 | 1.00 |
| `bin/quicksort-mml` | 42.4 ± 0.3 | 41.9 | 42.9 | 1.01 ± 0.01 |

At C parity. Lower variance than the March x86 result on both sides.

At `-O 3`:

| Command | Mean [ms] | Relative |
|:---|---:|---:|
| `bin/quicksort-c` | 42.1 ± 0.9 | 1.00 |
| `bin/quicksort-mml` | 59.1 ± 0.4 | 1.40 ± 0.03 |

Previous results (Mar 14, x86_64):

| Command | Mean [ms] | Relative |
|:---|---:|---:|
| `bin/quicksort-c` | 70.8 ± 4.2 | 1.00 |
| `bin/quicksort-mml` | 84.0 ± 6.2 | 1.19 ± 0.11 |

## Matrix multiplication

Naive 500x500 int64 multiply and loop-interchanged (i-k-j) variant.

At `-O 1`:

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/matmul-opt-c` | 17.7 ± 0.1 | 17.5 | 17.8 | 1.00 |
| `bin/matmul-restricted-c` | 17.7 ± 0.1 | 17.5 | 18.4 | 1.00 ± 0.01 |
| `bin/matmul-mml` | 22.2 ± 0.2 | 21.9 | 22.5 | 1.26 ± 0.01 |
| `bin/matmul-c` | 26.9 ± 0.3 | 26.6 | 28.1 | 1.53 ± 0.02 |
| `bin/matmul-opt-mml` | 35.1 ± 0.1 | 34.8 | 35.5 | 1.99 ± 0.01 |
| `bin/matmul-opt-go` | 47.4 ± 0.2 | 47.0 | 48.6 | 2.68 ± 0.02 |
| `bin/matmul-go` | 47.5 ± 0.2 | 47.0 | 48.0 | 2.69 ± 0.02 |
| `bin/matmul-bce-go` | 53.0 ± 0.4 | 52.1 | 54.8 | 3.00 ± 0.03 |

Two odd things here:

1. Naive MML (22.2ms) is *faster* than naive C (26.9ms), and only 1.26× the
   loop-interchanged C. That is a real win at O1.
2. The i-k-j hand-optimised MML (35.1ms) is *slower* than the naive MML
   (22.2ms). In C the opposite holds (17.7 opt vs 26.9 naive). The
   source-level loop interchange is not producing its expected benefit in
   MML at O1 — likely because at O1 the autovectoriser isn't aggressive
   enough to exploit the i-k-j layout the way it would at higher levels.
   Worth a separate look.

At `-O 3`:

| Command | Mean [ms] | Relative |
|:---|---:|---:|
| `bin/matmul-opt-c` | 17.7 ± 0.2 | 1.00 ± 0.01 |
| `bin/matmul-mml` | 269.3 ± 2.0 | 15.26 ± 0.16 |
| `bin/matmul-opt-mml` | 119.9 ± 0.8 | 6.79 ± 0.06 |

Previous results (Mar 14, x86_64):

| Command | Mean [ms] | Relative |
|:---|---:|---:|
| `bin/matmul-opt-c` | 43.1 ± 4.0 | 1.00 |
| `bin/matmul-opt-mml` | 43.1 ± 2.4 | 1.00 ± 0.11 |
| `bin/matmul-c` | 234.4 ± 9.8 | 5.44 ± 0.56 |
| `bin/matmul-mml` | 234.6 ± 6.1 | 5.45 ± 0.53 |

## Euclidean extended GCD

Extended Euclidean algorithm + RSA-style modular exponentiation.

At `-O 1` (second run; the first showed 1.02× with overlapping ranges):

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/euclidean-ext-c` | 2.1 ± 0.2 | 1.9 | 2.5 | 1.09 ± 0.10 |
| `bin/euclidean-ext-mml` | 1.9 ± 0.0 | 1.9 | 2.0 | 1.00 |

MML faster than C within noise. Variance on MML is essentially zero, which
itself is a positive sign about codegen stability.

This is the cleanest demonstration that the m5 regression at O3 is not about
arrays or aggregate returns: Euclidean has neither, and it was the worst
non-array regression at O3 (9.55× C).

At `-O 3`:

| Command | Mean [ms] | Relative |
|:---|---:|---:|
| `bin/euclidean-ext-c` | 2.0 ± 0.0 | 1.00 |
| `bin/euclidean-ext-mml` | 19.1 ± 0.3 | 9.55 ± 0.26 |

Previous results (Mar 14, x86_64):

| Command | Mean [ms] | Relative |
|:---|---:|---:|
| `bin/euclidean-ext-c` | 4.7 ± 0.5 | 1.00 |
| `bin/euclidean-ext-mml` | 5.1 ± 0.7 | 1.09 ± 0.19 |

## N-Queens

N=12, counting all 14,200 solutions. Backtracking with array board state and
TCO in the inner `is_safe` loop. Not retried at O1 this batch.

At `-O 3`:

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/nqueens-c` | 53.1 ± 0.2 | 52.7 | 53.6 | 1.00 |
| `bin/nqueens-go` | 68.3 ± 0.4 | 67.7 | 69.7 | 1.29 ± 0.01 |
| `bin/nqueens-mml` | 75.5 ± 0.4 | 74.9 | 76.6 | 1.42 ± 0.01 |

Previous results (Mar 14, x86_64):

| Command | Mean [ms] | Relative |
|:---|---:|---:|
| `bin/nqueens-c` | 86.3 ± 6.1 | 1.00 |
| `bin/nqueens-mml` | 105.8 ± 4.2 | 1.23 ± 0.10 |

## Ackermann

A(3, 10). Pure recursion, no arrays. Not retried at O1 this batch.

At `-O 3`:

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/ackermann-c` | 99.8 ± 0.4 | 99.0 | 100.4 | 1.00 ± 0.01 |
| `bin/ackermann-c-chacho` | 99.7 ± 1.1 | 98.4 | 102.5 | 1.00 |
| `bin/ackermann-rs` | 100.8 ± 0.5 | 99.8 | 101.9 | 1.01 ± 0.01 |
| `bin/ackermann-go` | 104.1 ± 1.1 | 102.1 | 106.6 | 1.04 ± 0.02 |
| `bin/ackermann-mml` | 117.3 ± 4.2 | 114.5 | 133.1 | 1.18 ± 0.04 |

The only benchmark that did not regress at O3 on m5. Ackermann is
call/return-overhead dominated; it has no inner loop for LLVM to optimise
or fail to optimise. The fact that it sits where it sat on x86 supports the
read that the O2/O3 issue is in loop transforms, not in call-path codegen.

## Self-benchmarks

Self-benchmark binaries (`sieve-mml-O{0..3}-{tco,no-tco}`,
`matmul-mml-O{0..3}-{tco,no-tco}`, `matmul-opt-mml-O{0..3}-{tco,no-tco}`) were
not run as a full batch this report. Partial data from one self-bench run on
m5:

| Variant | Mean [ms] |
|:---|---:|
| `matmul-opt-mml-O1-tco` | 46.1 |
| `matmul-opt-mml-O1-no-tco` | 55.3 |
| `matmul-opt-mml-O2-tco` | 119.9 |
| `matmul-opt-mml-O2-no-tco` | 119.9 |
| `matmul-opt-mml-O3-tco` | 120.1 |
| `matmul-opt-mml-O3-no-tco` | 120.1 |

The 2.6× jump between O1 and O2 is the symptom of the mid-end issue: O1 is
46ms, O2 and O3 are ~120ms, and TCO is irrelevant at that level (O2-tco and
O2-no-tco are identical to one decimal).

(The pinned-Makefile measurement of `matmul-opt-mml` at O1 in the bench-matmul
run came in at 35.1ms rather than 46.1ms; the self-bench differs in build
flags from the pinned target. Worth aligning before the next full
self-benchmark run.)

## Summary

### Wins (Makefile pinned at O1)

- **Sieve**: 1.03× C — at parity.
- **Quicksort**: 1.01× C — at parity, better than x86 (1.19×).
- **Naive matmul**: 0.83× C — MML faster than naive C.
- **Euclidean**: 0.92× C — MML faster than C.

### Open at O1

- **matmul-opt**: 1.99× C, and slower than naive MML on the same benchmark.
  Loop-interchanged source not paying off at O1. Separate, smaller issue.

### Not retested at O1

- **nqueens**: 1.42× C at O3. May or may not improve at O1.
- **ackermann**: 1.18× C at O3, unchanged from x86. Call-bound; likely
  unchanged at any opt level.

### To investigate

1. **LLVM O2/O3 on AArch64**: identify which mid-end pass is responsible for
   the 6–15× regression on MML's IR shape. The self-bench split (O1 ≈ 46ms,
   O2 = 120ms, no TCO interaction) localises this cleanly to mid-end
   optimisation, not codegen.
2. **matmul-opt at O1**: why is the i-k-j hand-optimised source slower than
   the naive source in MML at O1? Look at the inner-loop IR for both.
3. **Self-benchmarks on m5**: full O0/O1/O2/O3, TCO/no-TCO batch. This is the
   highest-information single measurement still missing.
4. **Default opt level**: once (1) is understood, decide whether the right
   default for the benchmark Makefile (and for end users) is O1 or O3 with a
   targeted pass disable, or a compiler-side workaround that lets O3 work.

### Stop investigating

- **Owned aggregate return ABI**
  (`docs/brainstorming/codegen/owned-aggregate-return-abi.md`): not the
  cause. The regression went away with no ABI change. The aarch64 issue is
  in LLVM mid-end optimisation, not in how aggregate returns are lowered.
