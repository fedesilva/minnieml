# MML Benchmark Report
Date: 2026-01-14

## Preliminary Notes

**Unsafe Array Access:**

Most MML benchmarks (Sieve, Quicksort, Matrix Multiplication, N-Queens) utilize `unsafe_ar_int_*` intrinsics for array access. These intrinsics map directly to LLVM load/store instructions without bounds checking, similar to raw pointer access in C. 

This provides a performance advantage over languages like Rust and Go which enforce bounds checking by default (though Go's BCE should mitigate this, but see below). The `unsafe` usage aligns MML's performance characteristics closer to C for these specific micro-benchmarks.

As the MinnieML type system becomes a reality, it will provide safety guarantees that result in code equivalent to the one used now. When possible, all bounds checks will be verified at compile time using a combination of effects tracking, affine types, and GDP (Ghosts of Departed Proofs).

These early benchmarks serve as a baseline to keep us honest about performance as we layer on these safety features.

**Data Types and Memory Pressure:**

- **Monomorphic Arrays:** Due to the generalizing typechecker still being under development, benchmarks use a specialized `IntArray`.

- **Fixed Int64:** All benchmarks use `int64` as it is currently the only integer type fully supported by MML `IntArray`. This ensures a level playing field across all compared languages.

- **Memory Subsystem:** The use of `int64` consistently increases pressure on the memory subsystem compared to benchmarks that might otherwise use smaller types (e.g., 8-bit or 32-bit integers), providing a more strenuous test of memory throughput.

## Sieve of Eratosthenes

The Sieve of Eratosthenes benchmark finds all prime numbers up to a limit (1,000,000). The implementation uses a mutable array of 64-bit integers to track prime status and counts them at the end. 
- `sieve-mml`: Uses tail recursion for loops and `unsafe_ar_int_*` intrinsics for array access.
- `sieve-c`: Standard C using `malloc` and `while` loops. Features a branchless summation optimization in the counting phase.
- `sieve-rs`: Standard idiomatic Rust using `Vec<i64>` and `while` loops with bounds checking enabled.
- `sieve-go`: Idiomatic Go using slices and `for` loops.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/sieve-c` | 9.3 ± 0.4 | 8.3 | 11.3 | 1.00 |
| `bin/sieve-mml` | 9.4 ± 0.5 | 8.3 | 11.7 | 1.01 ± 0.07 |
| `bin/sieve-rs` | 9.8 ± 0.4 | 9.0 | 11.9 | 1.06 ± 0.07 |
| `bin/sieve-go` | 12.3 ± 0.4 | 11.3 | 14.2 | 1.33 ± 0.08 |

MML takes **2nd place**, effectively tied with C (Winner) as the 0.1ms difference is well within the statistical noise margin (±0.07). It outperforms Rust (3rd) by ~4% and Go by ~33%.

## Quicksort

Standard in-place Quicksort algorithm sorting an array of 1,000,000 integers. The array is filled with pseudo-random numbers using a linear congruential generator (LCG). 
- `quicksort-mml`: Uses tail recursion for the partitioning loop and recursive calls for the sorting steps.
- `quicksort-c`: Standard in-place C implementation using `while` loops.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/quicksort-c` | 109.8 ± 0.8 | 108.8 | 111.8 | 1.01 ± 0.01 |
| `bin/quicksort-mml` | 108.2 ± 0.7 | 107.2 | 110.1 | 1.00 |

MML takes **1st place**, slightly outperforming C (Runner-up) by ~1.5%.

## Matrix Multiplication

Naive matrix multiplication of two 500x500 matrices of 64-bit integers.
- `matmul-mml`: Standard O(N^3) triple loop implementation.
- `matmul-opt-mml`: Loop-interchanged (i-k-j) version for better cache locality.
- `matmul-c`: Naive O(N^3) triple loop with strided access on matrix B.
- `matmul-opt-c`: Loop-interchanged (i-k-j) version for better cache locality (standard C).
- `matmul-restricted-c`: Same as `matmul-opt-c` but with `restrict` keyword (no significant gain observed).
- `matmul-go`: Naive O(N^3) triple loop using slices.
- `matmul-opt-go`: Loop-interchanged (i-k-j) version for better cache locality (idiomatic Go, no manual BCE).
- `matmul-bce-go`: Attempt to enable Bounds Check Elimination (BCE) via explicit bounds hinting (`_ = A[size-1]`).

**Note:** The `matmul-bce-go` variant performed **worse** than the naive Go implementation, indicating that manual BCE hints interfered with other compiler optimizations in this specific case.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/matmul-restricted-c` | 44.4 ± 2.4 | 39.7 | 53.4 | 1.00 |
| `bin/matmul-opt-c` | 44.5 ± 3.0 | 37.8 | 49.3 | 1.00 ± 0.09 |
| `bin/matmul-opt-mml` | 70.2 ± 4.4 | 63.6 | 79.4 | 1.58 ± 0.13 |
| `bin/matmul-opt-go` | 94.3 ± 6.0 | 85.4 | 117.2 | 2.12 ± 0.18 |
| `bin/matmul-mml` | 99.6 ± 7.1 | 92.6 | 133.4 | 2.24 ± 0.20 |
| `bin/matmul-go` | 169.3 ± 8.8 | 158.6 | 194.9 | 3.81 ± 0.29 |
| `bin/matmul-c` | 244.5 ± 10.6 | 229.3 | 268.6 | 5.50 ± 0.38 |
| `bin/matmul-bce-go` | 254.5 ± 13.4 | 227.3 | 286.2 | 5.73 ± 0.43 |

Optimized MML takes **3rd place**, trailing the highly optimized C versions (`restricted-c` / `opt-c`) by ~58%. However, it effectively outperforms the optimized Go implementation (`matmul-opt-go`) by ~25%.

## N-Queens

Solves the N-Queens problem for N=12, finding the number of valid solutions (14,200). The backtracking algorithm uses recursion and an array to store board state.
- `nqueens-mml`: Relies on tail-call optimization for the inner loops of the `is_safe` check.
- `nqueens-c`: Standard recursive backtracking implementation in C.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/nqueens-c` | 131.8 ± 0.4 | 131.3 | 132.6 | 1.00 |
| `bin/nqueens-mml` | 217.8 ± 0.5 | 217.1 | 219.5 | 1.65 ± 0.01 |

MML takes **2nd place**, trailing C (Winner) by ~65%.

## Euclidean Extended GCD

Runs the Extended Euclidean Algorithm in a loop as part of an RSA-style modular exponentiation benchmark. Heavy on integer arithmetic and recursion.
- `euclidean-ext-mml`: Implemented using recursion.
- `euclidean-ext-c`: Iterative implementation of the Extended Euclidean Algorithm in C.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/euclidean-ext-c` | 7.2 ± 0.2 | 6.7 | 8.0 | 1.00 |
| `bin/euclidean-ext-mml` | 8.6 ± 0.2 | 8.2 | 9.0 | 1.20 ± 0.05 |

**Performance Note:** MML takes **2nd place**, trailing C (Winner) by ~20%.

## Ackermann

Computes the Ackermann function A(3, 10). This benchmark stresses deep recursion and function call overhead.
- `ackermann-mml`: Standard recursive implementation.
- `ackermann-c`: Standard recursive C implementation.
- `ackermann-rs`: Recursive Rust implementation.
- `ackermann-go`: Recursive Go implementation.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/ackermann-c` | 112.5 ± 7.1 | 105.8 | 137.9 | 1.00 |
| `bin/ackermann-mml` | 112.6 ± 5.3 | 105.7 | 125.4 | 1.00 ± 0.08 |
| `bin/ackermann-rs` | 132.6 ± 6.1 | 125.0 | 144.9 | 1.18 ± 0.09 |
| `bin/ackermann-go` | 199.2 ± 8.9 | 188.9 | 223.6 | 1.77 ± 0.14 |

**Performance Note:** MML effectively **ties for 1st place** with C, as the 0.1ms difference is negligible within the statistical noise (±5-7ms). It outperforms Rust by ~18% and Go by ~77%.

## MML Self-Benchmark: Sieve (Optimization Levels)

Comparing the performance of the Sieve benchmark compiled with different MML optimization levels (O0-O3) and Tail Call Optimization (TCO) enabled/disabled. At `-O0`, MML's TCO provides a **3.5x speedup** (9.5ms vs 33.6ms). At higher optimization levels (`-O2+`), LLVM's own tail call elimination closes the gap.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/sieve-mml-O0-tco` | 9.5 ± 0.4 | 8.4 | 11.5 | 1.02 ± 0.06 |
| `bin/sieve-mml-O0-no-tco` | 33.6 ± 2.1 | 31.1 | 40.1 | 3.61 ± 0.27 |
| `bin/sieve-mml-O1-tco` | 9.4 ± 0.4 | 8.3 | 11.3 | 1.01 ± 0.06 |
| `bin/sieve-mml-O1-no-tco` | 9.4 ± 0.4 | 8.5 | 10.9 | 1.01 ± 0.06 |
| `bin/sieve-mml-O2-tco` | 9.3 ± 0.4 | 8.5 | 11.5 | 1.00 ± 0.06 |
| `bin/sieve-mml-O2-no-tco` | 9.3 ± 0.4 | 8.4 | 11.2 | 1.00 |
| `bin/sieve-mml-O3-tco` | 9.3 ± 0.4 | 8.4 | 11.1 | 1.00 ± 0.06 |
| `bin/sieve-mml-O3-no-tco` | 9.4 ± 0.4 | 8.5 | 11.0 | 1.01 ± 0.06 |

## MML Self-Benchmark: Matrix Mul (Optimization Levels)

Naive Matrix Multiplication (O^3) across optimization levels. MML's TCO provides a massive **7.9x speedup** at `-O0` (159.2ms vs 1254.5ms), demonstrating the critical importance of frontend loopification for heavy recursion when backend optimizations are disabled.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/matmul-mml-O0-tco` | 159.2 ± 7.2 | 155.7 | 256.3 | 1.68 ± 0.13 |
| `bin/matmul-mml-O0-no-tco` | 1254.5 ± 254.1 | 849.2 | 1636.2 | 13.23 ± 2.80 |
| `bin/matmul-mml-O1-tco` | 102.9 ± 8.1 | 89.3 | 168.9 | 1.08 ± 0.11 |
| `bin/matmul-mml-O1-no-tco` | 108.4 ± 18.9 | 90.5 | 307.1 | 1.14 ± 0.21 |
| `bin/matmul-mml-O2-tco` | 96.4 ± 10.2 | 86.7 | 191.2 | 1.02 ± 0.12 |
| `bin/matmul-mml-O2-no-tco` | 97.6 ± 10.7 | 86.3 | 174.7 | 1.03 ± 0.13 |
| `bin/matmul-mml-O3-tco` | 95.3 ± 19.4 | 86.0 | 462.8 | 1.01 ± 0.21 |
| `bin/matmul-mml-O3-no-tco` | 94.8 ± 5.8 | 86.4 | 125.7 | 1.00 |

## MML Self-Benchmark: Matrix Mul Opt (Optimization Levels)

Loop-interchanged Matrix Multiplication (i-k-j) across optimization levels. This benchmark shows the **largest impact** from MML's TCO loopification: at `-O0`, enabling TCO yields a **10x speedup** (84.8ms vs 851.6ms), effectively turning a crash-prone recursive workload into a performant loop even without LLVM optimizations.

| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |
|:---|---:|---:|---:|---:|
| `bin/matmul-opt-mml-O0-tco` | 84.8 ± 4.0 | 78.8 | 106.3 | 1.26 ± 0.10 |
| `bin/matmul-opt-mml-O0-no-tco` | 851.6 ± 52.8 | 785.6 | 1164.1 | 12.64 ± 1.14 |
| `bin/matmul-opt-mml-O1-tco` | 67.6 ± 3.8 | 61.7 | 103.3 | 1.00 ± 0.09 |
| `bin/matmul-opt-mml-O1-no-tco` | 74.2 ± 5.5 | 66.6 | 123.1 | 1.10 ± 0.11 |
| `bin/matmul-opt-mml-O2-tco` | 68.3 ± 4.1 | 61.9 | 89.6 | 1.01 ± 0.09 |
| `bin/matmul-opt-mml-O2-no-tco` | 68.5 ± 4.0 | 61.8 | 86.9 | 1.02 ± 0.09 |
| `bin/matmul-opt-mml-O3-tco` | 67.7 ± 4.6 | 60.2 | 84.6 | 1.00 ± 0.10 |
| `bin/matmul-opt-mml-O3-no-tco` | 67.4 ± 4.4 | 60.1 | 90.1 | 1.00 |