# MML benchmark report: aggregate ABI and Int32 (aarch64)

Date: 2026-10-04
Host: Apple M5, aarch64-apple-darwin
Branch: `dev-link-directive`
Previous report: [2026-09-13](report-2026-09-13-aarch.md)

## Main findings

Optimized MML matrix multiplication takes 8.7 ms with default compiler settings,
down from 35.5 ms in September. That is about 4.1 times faster, in two steps:
the aggregate-ABI compiler brings it to 17.8 ms while integers are still 64 bits;
the Int32 migration brings it to 8.7 ms. The second step also changes the generated
inputs to keep the arithmetic within Int32. The multiplication loops themselves
are unchanged.

With Int32, optimized MML, C, and Rust finish in approximately 8.7 ms. Checked MML
takes 9.0 ms, about 3% behind unchecked C and 2% behind Rust. Unchecked quicksort remains at C
parity; checked quicksort takes about 9% longer than explicitly checked C.
N-Queens retains MML's roughly 16% elapsed-time advantage over C.

The gains are uneven. Ordinary matrix multiplication improves modestly with Int32,
and still runs faster at O1 than O3. O0 results regress substantially relative to
September, with much of that regression already present in the ABI-only build.

## Runs and evidence

The two October datasets each contain nine groups, 64 configurations, and 8,960
timed executions. Every recorded exit code is zero.

| Dataset                                        | Compiler and workload                                                                             | Interleave setting                                                   |
| :--------------------------------------------- | :------------------------------------------------------------------------------------------------ | :------------------------------------------------------------------- |
| [ABI-only](2026-10-04/)                        | Branch checkpoint `beae801`, default Int64, original benchmark inputs                             | LLVM default                                                         |
| [ABI + Int32](2026-10-04-int32-and-abi/)       | ABI checkpoint plus the uncommitted Int32 migration and matching cross-language benchmark changes | LLVM default                                                         |
| [September 13](2026-09-13/)                    | Int64 historical baseline                                                                         | Main optimized MML benchmark forced 4; self-benchmarks used defaults |
| [September 16 checked suite](2026-09-16-safe/) | Int64 baseline including nested and checked matrix variants                                       | Optimized MML variants forced 4                                      |

The labels describe complete builds. "ABI-only" includes the checkpoint's layout,
adapter, and build-tool changes. "Int32" includes the migration and benchmark-input
changes. Neither run isolates one compiler transformation.

For the verified ABI-only build, generated matrix IR contained
`%struct.IntArray = type { i64, i64* }` and `mul i64` in the original RNG.
The compiled MML and C optimized matrix programs both returned checksum **381460**.
The Int32 implementations were separately checked for agreement, including matrix
checksum **-286176**. These output checks are separate from Hyperfine's exit codes.

The timings include process startup, allocation, initialization, the computation,
output, and cleanup. Builds are outside the measured commands. Each configuration
has 200 runs for sieve, quicksort, Euclidean, and the three self-benchmark groups;
matmul has 50, and N-Queens and Ackermann have 20. Tables show milliseconds. Where
included, ± is the sample standard deviation, not a confidence interval.
No new independent memory or hardware-counter measurements accompany these runs.

## Two stages of matrix improvement

The self-benchmarks provide the clearest comparison. All three columns below use
LLVM's default interleave choice. Interleaving controls how many groups of vector
operations LLVM schedules per loop iteration; September's main benchmark forced
that setting to four, but its self-benchmarks did not. TCO is MML's frontend
tail-call optimization. The baseline here is September 13.

"Optimized" matmul is the existing i-k-j loop order, which visits B and C
contiguously in the inner loop. Ordinary matmul uses i-j-k and walks down columns
of B. Each variant keeps that loop order across the compared builds.

| Configuration        | September | ABI-only | ABI + Int32 | September / Int32 |
| :------------------- | --------: | -------: | ----------: | ----------------: |
| O2, frontend TCO on  |     35.49 |    17.83 |        8.66 |             4.10× |
| O3, frontend TCO on  |     35.51 |    17.86 |        8.72 |             4.07× |
| O3, frontend TCO off |     35.51 |    17.83 |        8.63 |             4.12× |

At O3 with frontend TCO, the two steps are **1.99×** and **2.05×**, for **4.07×**
overall. The O2 and TCO-off rows show the same pattern. Frontend TCO being disabled
does not disable LLVM's own optimizations.

The main comparison suite has a different historical baseline: September's MML
optimized entries already used the manual tuning flag. Against that tuned baseline,
the combined improvement is about 2×, rather than 4×. These rows use September 16.

| Configuration            | September | ABI-only | ABI + Int32 | September / Int32 |
| :----------------------- | --------: | -------: | ----------: | ----------------: |
| C, optimized             |     17.97 |    19.10 |        8.70 |             2.07× |
| Rust, optimized          |     18.14 |    18.21 |        8.79 |             2.06× |
| MML, optimized           |     17.76 |    18.29 |        8.69 |             2.04× |
| MML, optimized nested    |     17.81 |    18.95 |        8.66 |             2.06× |
| MML, optimized checked   |     22.01 |    27.69 |        8.98 |             2.45× |
| Go, optimized row slices |     36.42 |    36.81 |       37.51 |             0.97× |

The ABI-only compiler gets close to the old tuned unchecked MML results without
the flag. The checked variant fares worse: 27.69 ms against September's 22.01 ms.
Int32 brings it down to 8.98 ms, close to the unchecked variants. C and Rust also
gain about 2× with the 32-bit workload. Go's row-slice variant barely changes.

## What changes on this branch

### Native aggregate ABI and shared layouts

The branch adds generic C ABI lowering for x86-64 System V and Linux/Apple AArch64.
A shared target-layout calculation supplies struct sizes, field offsets, padding,
and alignment. Native argument and result lowering uses complete signature plans,
including small aggregate packing, indirect large values, floating-point aggregates,
and x86-64 register exhaustion.

The benchmarks encounter this code whenever they pass array or string structs
to the C runtime. An array descriptor contains a length and a data pointer.
The generator derives its handling from the field layout and target rules; it does
not special-case names such as `IntArray`, `String`, or raylib's `Color`. Ordinary
MML-to-MML calls retain the MML calling convention.

For calls needing temporary conversion storage, the compiler generates small
internal LLVM IR functions called adapters. Each accepts MML values, packs the
arguments for the C ABI, calls the native function, and converts its result back.
One adapter is reused per native symbol and ABI plan. Its temporary storage belongs
to its own stack frame, with no additional
heap allocation and no copy of the array's element buffer.

Adapters carry `inlinehint`. LLVM can inline them and remove temporary storage;
for runtime array operations it can also inline the underlying load or store.
The hint is not a guarantee. At low optimization levels, calls and temporary
storage can remain. The implementation is in
[NativeAbi.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/abis/NativeAbi.scala)
and [TargetLayout.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/TargetLayout.scala).

LLVM sees different IR even though the source loop is identical. We can measure
the improvement before Int32, but have not traced it to a particular LLVM pass.
Inlining and aggregate simplification are plausible parts of the explanation.
The same limit applies to the O0 regressions: adapters can cost time there, but
the measurements do not isolate their contribution.

### Int32 data and bounded workloads

The migration makes `Int` an alias for `Int32`. IntArray elements, public lengths
and indexes, and runtime Int parameters/results become 32-bit values. Pointer widths,
allocation byte counts, explicit Int64 values, and the runtime's internal RNG state
retain their appropriate widths. Parsing, formatting, conversions, entry-point
returns, and runtime cache identity are updated for the new representation.

Array element storage falls from eight bytes to four. Each 500x500 matrix therefore
uses 1 MB of element storage instead of 2 MB. Smaller elements reduce the bytes
accessed by each load or store and change the arithmetic LLVM can generate. We
have not compared the resulting instruction sequences or measured peak memory
for these runs.

The benchmark implementations in C, Go, Rust, and Java also use 32-bit data and
arithmetic. MML uses ordinary Int operators. Checked variants keep their checked accesses;
unchecked variants keep their unchecked accesses.

The input changes are explicit:

| Workload           | Int64 dataset                                     | Int32 dataset                                              |
| :----------------- | :------------------------------------------------ | :--------------------------------------------------------- |
| Quicksort RNG      | Original 64-bit LCG, values reduced modulo 100000 | `(seed * 25173 + 13849) % 65536`; store the state directly |
| Matrix inputs      | Original LCG, signed remainder modulo 100         | Bounded LCG, `(seed % 100) - 50`                           |
| Euclidean modulus  | 1000000007                                        | 10007                                                      |
| Quicksort median   | -85                                               | 32767                                                      |
| Matrix trace       | 381460                                            | -286176                                                    |
| Euclidean checksum | 5010954496756                                     | 50024579                                                   |

Quicksort still sorts one million elements. Matrices remain 500x500 with seeds 42
and 1337. Euclidean still processes inputs 2 through 9999 with exponent 65537.
Sieve, N-Queens, and Ackermann retain their workload parameters and results.

The bounded RNG's maximum intermediate is 1,649,726,404. Matrix entries in [-50, 49]
bound the absolute trace and its partial sums by 625,000,000. Euclidean's products
are bounded by 100,120,036 and its checksum by 100,039,988. These fit signed Int32.
The changed quicksort distribution and smaller Euclidean modulus can affect execution
time independently of integer width, so the migration comparison measures both.

### Linking and native interoperability

The branch also adds module `@link` directives, owned C-string conversion and cleanup,
and native-library examples using zlib and raylib. Generic aggregate handling enables
raylib's four-byte Color struct; the Int32 migration aligns ordinary coordinates with
C int parameters. These features make the native-library examples work, but the
numerical benchmarks do not exercise them.

## Cross-language results with Int32

The ordinary MML and C entries below use unchecked array access. Rust and Go use
checked source operations, with check elimination left to their compilers. The Go
optimized-matmul entry is its fastest row-slice BCE variant.

| Benchmark     |          MML |            C |           Rust |            Go |
| :------------ | -----------: | -----------: | -------------: | ------------: |
| Sieve         |  1.60 ± 0.07 |  1.52 ± 0.08 |    1.65 ± 0.05 |   2.35 ± 0.18 |
| Quicksort     | 41.03 ± 0.45 | 41.81 ± 0.63 |   42.27 ± 0.78 |  48.19 ± 0.63 |
| Matmul, i-j-k | 23.84 ± 0.28 | 25.93 ± 0.83 |   23.83 ± 0.17 |  52.79 ± 0.81 |
| Matmul, i-k-j |  8.69 ± 0.12 |  8.70 ± 0.12 |    8.79 ± 0.07 |  37.51 ± 0.47 |
| N-Queens      | 42.75 ± 0.80 | 50.73 ± 0.22 |              — |  74.94 ± 2.69 |
| Euclidean     |  1.72 ± 0.14 |  1.74 ± 0.16 |              — |             — |
| Ackermann     | 99.01 ± 4.67 | 99.93 ± 0.69 | 112.89 ± 21.97 | 107.48 ± 1.49 |

Optimized MML, C, and Rust are effectively tied. Ordinary MML matmul also matches
Rust and takes about 8% less time than C. Quicksort's lead over C is small, about 2%.
N-Queens has a larger gap: MML takes about 16% less time than C and 43% less
than Go. Sieve remains close to C, while Euclidean and Ackermann are near parity.
Rust's Ackermann timings vary too much to treat its 14% higher mean as a stable gap.

Go does not share the optimized-matmul gain; its ordinary matrix version takes
about 10% longer in the Int32 dataset than in the ABI-only dataset. Java native
matmul remains near 71 ms. Java native quicksort improves from 50.34 to 48.12 ms;
its JVM process improves from 85.48 to 80.73 ms. JVM timings include startup and
are not steady-state JIT measurements.

## Cost of checked array access

The checked MML and C quicksort variants check array accesses explicitly. The two
checked MML matrix variants use `ar_int_get/set` throughout. There is no matching
explicitly checked C matrix entry in this suite. Rust and Go retain their normal
checked source operations; the generated checks may be eliminated when proven redundant.

| Benchmark     | Checked MML | Unchecked C | Checked C |  Rust |    Go |
| :------------ | ----------: | ----------: | --------: | ----: | ----: |
| Quicksort     |       49.99 |       41.81 |     45.76 | 42.27 | 48.19 |
| Matmul, i-j-k |       45.07 |       25.93 |         — | 23.83 | 52.79 |
| Matmul, i-k-j |        8.98 |        8.70 |         — |  8.79 | 37.51 |

Checked optimized MML is about 3% behind unchecked C and 2% behind Rust, and 4.18× faster than
Go's row-slice variant. Its overhead against unchecked nested MML is **3.8%**, down
from **46.1%** in the ABI-only run and **23.6%** in September's tuned checked suite.

Checked ordinary MML matmul has **90.3%** overhead against unchecked nested MML.
It takes 74% longer than unchecked C and 89% longer than Rust, but 15% less time
than Go. Check overhead is therefore strongly dependent on loop structure and the
optimizations it permits.

Checked quicksort takes **21.8%** longer than unchecked MML, about **9.2%** longer
than checked C, 18% longer than Rust, and 4% longer than Go. Checked C's overhead
against unchecked C is 9.4%. These costs include changes in what LLVM can optimize around the checks.

## Quicksort across architectures

The January–March reports ran on x86-64. May is the first Apple M5 report.
The MML/C ratio compares the two implementations within each run; below 1.00
means MML finishes sooner. May's O1 retry is recorded in the report, while its
saved JSON contains the initial O3 batch.

| Run                | Architecture / MML optimization |    MML |      C | MML / C |
| :----------------- | ------------------------------: | -----: | -----: | ------: |
| Jan 14             |                      x86-64, O3 | 108.18 | 109.80 |    0.99 |
| Feb 7              |                      x86-64, O3 |  81.42 |  71.47 |    1.14 |
| Mar 14             |                      x86-64, O3 |  83.99 |  70.80 |    1.19 |
| May 17             |                          M5, O3 |  59.07 |  42.15 |    1.40 |
| May retry          |                          M5, O1 |  42.40 |  42.00 |    1.01 |
| Sep 13             |                          M5, O3 |  43.02 |  43.95 |    0.98 |
| Oct 4, ABI-only    |                          M5, O3 |  44.66 |  44.52 |    1.00 |
| Oct 4, ABI + Int32 |                          M5, O3 |  41.03 |  41.81 |    0.98 |

Sources: [January](report-2026-01-14.md), [February](report-2026-02-07.md),
[March](report-2026-03-14.md), [May including the O1 retry](report-2026-05-17-aarch.md),
and [September](report-2026-09-13-aarch.md).

On x86-64, MML begins at C parity, then trails by 14–19% in the noisier February
and March runs. On M5, the first O3 result trails by 40%, while the O1 retry already
restores parity. By September, O3 also reaches parity. That recovery predates this
branch's aggregate ABI change.

October's ABI-only quicksort remains at parity. The Int32 dataset takes about 8%
less time in MML and 6% less in C, with a different input distribution. Quicksort's
story is recovery to C-level performance at O3 on M5, followed by a modest gain.
There is no current x86-64 measurement. The drop from 108 ms on the earlier x86
host to 41 ms on M5 includes the hardware change.

## Remaining optimization issues

Ordinary matmul still prefers O1 with frontend TCO: **18.97 ms**, compared with
**23.70 ms** at O3 in the Int32 self-benchmark. O3 takes about 25% longer. In the
ABI-only run the same comparison is 22.60 versus 25.93 ms.

O0 also has substantial regressions against September:

| Configuration                   | September | ABI-only | ABI + Int32 |
| :------------------------------ | --------: | -------: | ----------: |
| Ordinary matmul, O0 + TCO       |     63.99 |   120.16 |      122.86 |
| Optimized-loop matmul, O0 + TCO |    128.10 |   177.63 |      194.40 |
| Sieve, O0 + TCO                 |      1.99 |     2.25 |        2.66 |

Most of the ordinary-matmul regression is already present before Int32. Optimized-loop
matmul worsens in both stages. At O1, optimized-loop matmul stays near 35.7 ms across
the ABI-only and Int32 runs; its major gains appear at O2/O3. The next useful
code-generation comparison is at O0 and O1, where the costs remain.

## Reproduction and comparison limits

For each source state, publish that compiler before rebuilding the standalone
benchmarks. Stashing compiler sources does not change the installed `mmlc` executable,
and republishing it does not replace already built benchmark binaries.

After the compiler's required verification, use:

```sh
sbtn mmlcPublishLocal
make -C benchmark clean
LOG_BENCH_RESULTS=1 MML_OPT=3 make -C benchmark bench RESULTSDIR=results/new-run-name
```

Use a distinct results directory for each run. Confirm matching program outputs and
inspect freshly generated IR to verify the intended integer width before comparing
timings. The Makefile's optimized MML recipes use LLVM's default interleave choice;
September's forced-interleave measurements must remain labeled as such.

The raw JSON retains every timing sample. Follow-up measurements can use the same
workloads to investigate O0 cost, ordinary matmul's O1 advantage, and check overhead.
A fresh x86-64 run would test whether the current results carry over to that target.
