# MML benchmark report (aarch64)

Date: 2026-09-13

Host: Apple M5, aarch64-apple-darwin

Previous report: [2026-05-17](report-2026-05-17-aarch.md)

## Headline

MML reaches C parity on optimized matrix multiplication and Euclidean, runs slightly
faster on quicksort and naive matrix multiplication, and leads on N-Queens and Ackermann.
Sieve remains close to C. The large O3 slowdowns in the May M5 results are absent from
this round.

The strongest changes are in workloads that had remained behind C for several reports.
N-Queens takes 41.979 ms against C's 50.203 ms, a 16.4% reduction in elapsed time.
Ackermann takes 94.384 ms against 98.664 ms, a 4.3% reduction. Optimized matmul reaches
17.769 ms against C's 17.770 ms with an explicit LLVM interleave setting.

There is still work in the optimizer defaults: naive matmul is faster at O1 than O3,
and optimized matmul takes about twice as long without the interleave setting. The
standalone memory measurements put MML within 64 KiB of C's peak RSS across the main
comparisons. The tables use those independent measurements because Hyperfine's
exported memory fields retain peaks from earlier commands.

## Run and interpretation

The full run used:

```sh
LOG_BENCH_RESULTS=1 MML_OPT=3 make -C benchmark/ bench
```

[Hyperfine results](2026-09-13/) contain nine groups, 58 configurations, and 8,360 timed
executions. Every recorded exit code is zero.

A separate resource run used:

```sh
make -C benchmark/ bench-time > tmp/time.log
```

Its [stdout](2026-09-13/time.stdout.log) contains the commands and program results;
the [stderr capture](2026-09-13/time.stderr.log) contains 58 `/usr/bin/time -l` blocks.
[Parsed measurements](2026-09-13/time.json) pair those blocks with the commands in
execution order. All configurations agree on their workload's output: sieve 78,498;
quicksort -85; matmul 381,460; N-Queens 14,200; Euclidean 5,010,954,496,756; Ackermann 8,189.

Each benchmark measures a standalone program completing a job. Startup and shutdown are
part of the cost for every implementation. Builds are outside the timed commands.

Tables combine repeated Hyperfine timing with the separate resource run. Mean elapsed
time ± sample standard deviation is in milliseconds. Relative values divide each
mean by the fastest mean in that table; lower is better. Instructions and cycles are
in millions; maximum resident set size and peak memory footprint are in MiB. Resource
columns describe one execution per configuration, not averages of the Hyperfine runs.
The coarse elapsed times printed by `time` are not used for the timing rankings. Standard
deviation describes run-to-run variation, not a confidence interval for the mean.
The longer workloads generally vary by less than 1%. Sieve and Euclidean finish in
about 2 ms, so small differences there deserve more caution.

The [Makefile](../Makefile) uses:

- MML at O3 for the cross-language run. Self-benchmarks explicitly select O0 through O3,
  with and without frontend TCO.
- `--llvm-opt-arg=-force-vector-interleave=4` for `matmul-opt-mml`. Self-benchmark
  targets omit this additional flag.
- Clang with `-O3 -flto -march=native -fomit-frame-pointer -fno-stack-protector -DNDEBUG`.
- Rust with `rustc -O`, Go with `go build`, and GraalVM native-image with
  `-O3 -march=native --no-fallback`.

The array workloads use 64-bit integers. MML's benchmark array accesses use the
`unsafe_ar_int_*` operations, which emit unchecked accesses. The tables compare these
particular implementations of the same workloads; they do not isolate one compiler
pass or prove that all source implementations perform identical machine operations.

## Codegen and flags behind this round

The relevant history on `dev-lambda-unify`, through `612bfd2`, includes both changes
to generated code and corrections to how benchmark configurations are built. The
diffs explain what changed; today's measurements establish the effects of the tested
configurations. They do not provide a separate before/after timing for every commit.

### Matching the target across MML and its runtime

`2efeb8c` (September 10) forwards the resolved CPU to Clang when compiling the runtime
and linking the executable. It also changes runtime cache keys from target triple
alone to include optimization level and compilation flags. Previously, builds sharing
the benchmark directory could reuse runtime bitcode compiled at another optimization
level. This matters particularly for the O0–O3 self-benchmarks: the runtime is linked
into the program before LLVM optimization. Today's results use separate cache entries;
the historical logs do not establish which configuration populated each old cache.

`6d1fc97` (September 13) completes the target alignment. The old emitter obtained a
CPU name from the LLVM host marker and emitted `target-cpu` alone. The new path probes
the selected Clang with the runtime's target flags, then copies both `target-cpu` and
`target-features` onto MML functions, including global initializers. It passes the
resolved CPU to `opt` and `llc`, and includes Clang identity in the cache key. The
current benchmark IR confirms `apple-m5` and Clang's feature list on MML functions.

This is a concrete change to the target information available for optimization and
runtime inlining, and a plausible contributor to the recovery from May's O3 results.
Its individual timing contribution has not been isolated. Probe caching itself saves
build work; builds are excluded from the drag race.

### Optimization levels and matrix tuning

`6e33ee4` replaces the per-target optimization choices left after May with `MML_OPT`,
defaulting to O1 on Apple Silicon. Today's explicit `MML_OPT=3` overrides that default
for every cross-language MML target. `2f53e35` introduces compact `-O0`–`-O3` syntax
and repeatable `--llvm-opt-arg` options. The syntax change does not itself optimize
code: LLVM still runs `default<Olevel>,internalize,globaldce` for executables. Extra
arguments go to `opt`; `-I` merely saves optimized IR.

`d77cfa7` adds `--llvm-opt-arg=-force-vector-interleave=4` to **only** the
`matmul-opt-mml` drag-race target. Its self-benchmark targets retain LLVM's default
choice. Both use the same source, including the output-zeroing loop added by that
commit. The strongest positive and negative flag effects visible today are:

| Configuration comparison | Elapsed time | Instructions retired | Result |
|:---|:---|:---|:---|
| Interchanged matmul, O3 default → forced interleave 4 | 35.508 → 17.769 ms | 895.71 → 396.80 M | About half the time; reaches C parity |
| Naive matmul with frontend TCO, O1 → O3 | 22.497 → 25.804 ms | 460.39 → 583.78 M | O3 takes 14.7% longer and retires 26.8% more instructions |
| Naive matmul at O0, frontend TCO off → on | 589.499 → 63.986 ms | 2799.63 → 1526.69 M | Frontend loopification gives a 9.21× speedup |

Forced interleaving is a benchmark-specific tuning choice, not a new compiler default
or proof that SIMD caused the gain. Conversely, the remaining naive-matmul penalty
shows that higher optimization is still not uniformly better. No pass-by-pass
comparison identifies the responsible transformation yet. Frontend TCO predates this
round; its on/off rows measure its continuing benefit, rather than a new September
optimization.

The output initialization in `d77cfa7` is also significant: i-k-j accumulation requires
zeroed C, and the old source merely assumed the allocation was zeroed. Today's tuned
and untuned variants both pay for that work. The O0+TCO increase from May's 57.735 to
128.096 ms therefore crosses a source change as well as compiler changes; the logs
do not isolate the cost of initialization. The other six MML benchmark sources are
unchanged from the May report commit (`cf62882`).

### Closure metadata and the comparison set

`612bfd2` adds matching field TBAA to capture loads and `align`/`dereferenceable(N)`
to capturing-entry environment pointers with known, nonempty layouts. It makes no
new general `noalias` promise about array arguments or captured objects. Scoped alias
metadata remains disabled in these benchmark builds. The earlier September closure
destruction (`2efeb8c`), partial-application (`ea803e2`), and owned-field (`2b55e82`)
changes are part of the measured compiler, but this suite does not isolate their
performance. None should be credited with the matrix or recursion wins from timing
alone.

`48ead18` changes restricted C from i-k-j to i-j-k, matching ordinary C's access order.
It now takes 27.244 ms against ordinary C's 27.260 ms: `restrict` alone produces no
meaningful timing advantage here. Its loss of the earlier optimized-C timing follows
that deliberate source change. The same commit adds the Go row-slice variant without
replacing existing variants. Along with the Rust and Java additions (`2461213`,
`6e33ee4`, `11cf360`), these broaden the comparison set; they are not MML codegen gains.

## Comparison with previous rounds

The table below divides MML's mean by the corresponding C mean. Below 1.00 means MML
finishes sooner. Naive matmul is compared with naive C; optimized matmul is compared
with optimized C. This denominator is consistent across every date.

| Benchmark | Jan 14, x86 | Feb 7, x86 | Mar 14, x86 | May 17, M5 O3 | Sep 13, M5 O3 |
|:---|---:|---:|---:|---:|---:|
| Sieve | 1.01 | 1.02 | 0.87 | 11.73 | 1.04 |
| Quicksort | 0.99 | 1.14 | 1.19 | 1.40 | 0.98 |
| Matmul, i-j-k | 0.41 | 1.00 | 1.00 | 10.04 | 0.95 |
| Matmul, i-k-j | 1.58 | 1.00 | 1.00 | 6.79 | 1.00 |
| N-Queens | 1.65 | 1.57 | 1.23 | 1.42 | 0.84 |
| Euclidean | 1.20 | 1.08 | 1.09 | 9.55 | 1.00 |
| Ackermann | 1.00 | 1.22 | 1.20 | 1.18 | 0.96 |

January through March ran on x86. May and September ran on Apple M5. The historical
ratios describe MML's position against C in each round; absolute time changes across
architectures cannot be assigned to compiler improvements.

The May column uses the saved JSON's initial O3 measurements. The May report also
contains separate O1 reruns that recovered much of the performance. Those results
must not be confused with its saved O3 batch. Today's source and build settings also
differ, especially for optimized matmul. Earlier causal explanations involving
`noalias`, attribute groups, or a particular LLVM pass remain hypotheses unless
supported by a separate controlled comparison.

## Sieve of Eratosthenes

Primes up to 1,000,000. [Raw measurements](2026-09-13/sieve.json).

| Command | Mean ± SD [ms] | Relative | Instructions [M] | Cycles [M] | Peak RSS [MiB] | Peak footprint [MiB] |
|:---|---:|---:|---:|---:|---:|---:|
| `bin/sieve-c` | 1.661 ± 0.089 | 1.00 | 15.48 | 7.81 | 5.14 | 4.77 |
| `bin/sieve-mml` | 1.728 ± 0.085 | 1.04 | 18.75 | 7.93 | 5.19 | 4.81 |
| `bin/sieve-rs` | 1.780 ± 0.069 | 1.07 | 20.34 | 7.67 | 5.31 | 4.81 |
| `bin/sieve-go` | 2.538 ± 0.073 | 1.53 | 32.15 | 11.45 | 8.36 | 7.00 |
| `bin/sieve-java-native` | 3.562 ± 0.077 | 2.14 | 57.52 | 17.78 | 12.39 | 6.08 |
| `java -cp bin Sieve` | 33.806 ± 0.613 | 20.36 | 576.33 | 217.24 | 54.09 | 31.83 |

MML is 4.1% behind C by mean, a difference of 0.068 ms. The practical result is close
performance at this short runtime. This continues the January–March pattern and the
May O1 rerun's roughly 1.03× C result.

May's saved O3 MML result was 19.181 ms; today's is 1.728 ms. C moves only from 1.636
to 1.661 ms. The large MML slowdown seen in that O3 batch is absent, and the explicit
O3 self-benchmark below confirms a roughly 1.76 ms result.

## Quicksort

In-place sort of 1,000,000 LCG-filled integers.
[Raw measurements](2026-09-13/quicksort.json).

| Command | Mean ± SD [ms] | Relative | Instructions [M] | Cycles [M] | Peak RSS [MiB] | Peak footprint [MiB] |
|:---|---:|---:|---:|---:|---:|---:|
| `bin/quicksort-mml` | 43.024 ± 0.354 | 1.00 | 254.50 | 192.27 | 9.00 | 8.63 |
| `bin/quicksort-c` | 43.949 ± 0.412 | 1.02 | 253.29 | 191.69 | 8.94 | 8.56 |
| `bin/quicksort-rs` | 43.953 ± 0.372 | 1.02 | 287.66 | 191.35 | 9.14 | 8.64 |
| `bin/quicksort-go` | 45.900 ± 0.370 | 1.07 | 379.56 | 201.61 | 12.19 | 10.86 |
| `bin/quicksort-java-native` | 53.250 ± 0.183 | 1.24 | 531.75 | 230.25 | 16.19 | 9.88 |
| `java -cp bin Quicksort` | 97.466 ± 0.542 | 2.27 | 2375.87 | 672.05 | 99.25 | 63.11 |

MML takes 2.1% less time than C and Rust. It is a small advantage in this round, with
low run-to-run variation. C and Rust are effectively tied. MML retires 254.50 million
instructions against C's
253.29 million, a difference of 0.48%. Peak RSS is 9.00 versus 8.94 MiB: 64 KiB more
for MML in this resource run.

The MML/C ratio was 0.99 in January, 1.14 in February, 1.19 in March, and 1.40 in May's
initial O3 run. May's O1 retry already brought it back to 1.01. Today's O3 result is
0.98. MML has recovered the close performance of the earlier good configurations.

## Matrix multiplication

500×500 integer matrices, with i-j-k and interchanged i-k-j implementations.
[Raw measurements](2026-09-13/matmul.json).

| Command | Mean ± SD [ms] | Relative | Instructions [M] | Cycles [M] | Peak RSS [MiB] | Peak footprint [MiB] |
|:---|---:|---:|---:|---:|---:|---:|
| `bin/matmul-opt-mml` | 17.769 ± 0.039 | 1.00 | 396.80 | 76.50 | 7.14 | 6.77 |
| `bin/matmul-opt-c` | 17.770 ± 0.055 | 1.00 | 395.57 | 76.86 | 7.09 | 6.72 |
| `bin/matmul-opt-rs` | 18.013 ± 0.048 | 1.01 | 398.70 | 77.21 | 7.27 | 6.77 |
| `bin/matmul-mml` | 25.862 ± 0.082 | 1.46 | 583.53 | 111.23 | 7.14 | 6.77 |
| `bin/matmul-rs` | 26.024 ± 0.171 | 1.46 | 586.57 | 112.16 | 7.27 | 6.77 |
| `bin/matmul-restricted-c` | 27.244 ± 0.200 | 1.53 | 646.44 | 117.17 | 7.09 | 6.72 |
| `bin/matmul-c` | 27.260 ± 0.154 | 1.53 | 646.50 | 117.93 | 7.09 | 6.72 |
| `bin/matmul-opt-bce-go` | 36.774 ± 0.192 | 2.07 | 914.99 | 161.21 | 10.30 | 8.95 |
| `bin/matmul-opt-go` | 47.611 ± 0.194 | 2.68 | 1162.74 | 206.61 | 10.33 | 8.98 |
| `bin/matmul-go` | 47.946 ± 0.201 | 2.70 | 1162.96 | 207.24 | 10.30 | 8.95 |
| `bin/matmul-bce-go` | 53.413 ± 0.203 | 3.01 | 1538.84 | 233.79 | 10.20 | 8.86 |
| `bin/matmul-java-native` | 69.902 ± 0.255 | 3.93 | 2055.18 | 303.04 | 14.30 | 8.02 |

Optimized MML and C both take 17.77 ms. Rust is close at 18.01 ms. MML's tuned i-k-j
version takes 31.3% less time than its naive version, which itself runs 5.1% ahead of
naive C. Even the fastest Go variant takes 2.07× MML's optimized time; Java native
takes 3.93×. The additional Go implementation makes the comparison more representative.

Restricted C and ordinary C are effectively tied at 27.244 and 27.260 ms. Historical
restricted-C results used i-k-j order and matched optimized C. Its higher time in this
round follows the deliberate source change to i-j-k; it is not a regression in C or
an argument against alias information in general.

May's saved O3 measurements were 269.295 ms for naive MML and 119.891 ms for optimized
MML. Its O1 reruns reached about 22.2 and 35.1 ms. Today's naive O3 result is 25.862 ms;
the tuned optimized result is 17.769 ms. C's corresponding times remain close to May.

The resource counters reinforce the optimized result: MML retires 396.80 million
instructions against C's 395.57 million, a difference of 0.31%, and uses 48 KiB more
peak RSS. Naive MML retires 583.53 million instructions against C's 646.50 million.

The self-benchmarks still put naive O1 ahead of naive O3. They also show that the
17.77 ms optimized result depends on a different build configuration: without the
explicit interleave setting, optimized MML takes about 35.5 ms at O1–O3.

## N-Queens

N=12, counting solutions with backtracking.
[Raw measurements](2026-09-13/nqueens.json).

| Command | Mean ± SD [ms] | Relative | Instructions [M] | Cycles [M] | Peak RSS [MiB] | Peak footprint [MiB] |
|:---|---:|---:|---:|---:|---:|---:|
| `bin/nqueens-mml` | 41.979 ± 0.062 | 1.00 | 463.21 | 181.11 | 1.31 | 0.94 |
| `bin/nqueens-c` | 50.203 ± 0.039 | 1.20 | 440.98 | 221.70 | 1.31 | 0.94 |
| `bin/nqueens-go` | 69.878 ± 0.293 | 1.66 | 907.66 | 303.03 | 3.92 | 2.69 |

This is the largest clear lead over C in the suite: MML takes 16.4% less time, or
runs 1.20× as fast. The difference is much larger than the measured variation.

N-Queens was MML's weakest result in January at 1.65× C. The ratio fell to 1.57 in
February and 1.23 in March, then reached 1.42 in May's M5 round. It is 0.84 today.
On the same chip family, MML falls from May's 75.480 to 41.979 ms, while C falls from
53.134 to 50.203 ms. The MML improvement exceeds the movement in the C baseline.

MML and C both peak at 1.31 MiB RSS and 0.94 MiB memory footprint. MML retires slightly
more instructions, 463.21 versus 440.98 million, but records fewer cycles, 181.11 versus
221.70 million. Instruction count alone therefore does not explain the timing win.

The benchmark implementations express the search differently, including their
recursion structure. The result establishes a win for the MML implementation; a
specific codegen explanation would require inspecting and comparing the generated code.

## Euclidean extended GCD

Extended Euclidean algorithm and modular exponentiation over 10,000 iterations.
[Raw measurements](2026-09-13/euclidean.json).

| Command | Mean ± SD [ms] | Relative | Instructions [M] | Cycles [M] | Peak RSS [MiB] | Peak footprint [MiB] |
|:---|---:|---:|---:|---:|---:|---:|
| `bin/euclidean-ext-mml` | 2.048 ± 0.128 | 1.00 | 15.83 | 8.47 | 1.34 | 0.97 |
| `bin/euclidean-ext-c` | 2.052 ± 0.147 | 1.00 | 15.52 | 8.66 | 1.30 | 0.92 |

A tie: 2.048 ms for MML and 2.052 ms for C. January's roughly 20% gap had already
narrowed to about 8–9% in February and March. May's O1 reruns were also near parity.

May's initial O3 result was 19.139 ms for MML against 2.005 ms for C. That large
slowdown is absent from this O3 run. This matters because the workload does not
rely on matrix or sieve array loops.

## Ackermann

A(3, 10), a recursion-heavy workload. [Raw measurements](2026-09-13/ackermann.json).

| Command | Mean ± SD [ms] | Relative | Instructions [M] | Cycles [M] | Peak RSS [MiB] | Peak footprint [MiB] |
|:---|---:|---:|---:|---:|---:|---:|
| `bin/ackermann-mml` | 94.384 ± 0.563 | 1.00 | 346.85 | 432.99 | 1.53 | 1.14 |
| `bin/ackermann-c` | 98.664 ± 0.300 | 1.05 | 347.09 | 453.16 | 1.53 | 1.14 |
| `bin/ackermann-c-chacho` | 98.836 ± 0.659 | 1.05 | 346.78 | 456.38 | 1.52 | 1.13 |
| `bin/ackermann-rs` | 100.001 ± 0.786 | 1.06 | 349.92 | 457.78 | 1.67 | 1.16 |
| `bin/ackermann-go` | 104.224 ± 0.905 | 1.10 | 675.24 | 471.89 | 4.77 | 3.55 |

MML leads both C implementations and Rust. It takes 4.3% less time than ordinary C,
and the 4.28 ms difference is comfortably larger than the run-to-run variation.

January was a tie with C. February and March showed roughly a 20–22% MML deficit;
May showed about 17.5%. Today MML takes 94.384 ms, down from May's 117.316 ms, while
ordinary C moves only from 99.844 to 98.664 ms.

MML and ordinary C both peak at 1.53 MiB RSS. Their instruction counts are nearly
equal at 346.85 and 347.09 million; MML records 432.99 million cycles against C's
453.16 million in the resource run.

The old Ackermann deficit is absent in these measurements. The earlier reports'
suggestion that LLVM attribute groups caused it was not established by those timings,
and should not be carried forward as a proven diagnosis.

## MML self-benchmarks

These targets use explicit optimization levels. `no-tco` disables MML's frontend
loopification; LLVM may still eliminate tail calls at its own optimization levels.
The following tables retain optimization-level order for comparison.

### Sieve

[Raw measurements](2026-09-13/self.json).

| Command | Mean ± SD [ms] | Relative | Instructions [M] | Cycles [M] | Peak RSS [MiB] | Peak footprint [MiB] |
|:---|---:|---:|---:|---:|---:|---:|
| `bin/sieve-mml-O0-tco` | 1.987 ± 0.052 | 1.13 | 24.55 | 8.53 | 5.19 | 4.81 |
| `bin/sieve-mml-O0-no-tco` | 11.856 ± 0.138 | 6.74 | 47.31 | 53.47 | 28.05 | 27.67 |
| `bin/sieve-mml-O1-tco` | 1.784 ± 0.068 | 1.01 | 20.18 | 7.88 | 5.17 | 4.80 |
| `bin/sieve-mml-O1-no-tco` | 1.864 ± 0.063 | 1.06 | 20.97 | 8.47 | 5.19 | 4.81 |
| `bin/sieve-mml-O2-tco` | 1.786 ± 0.080 | 1.02 | 18.77 | 7.20 | 5.19 | 4.81 |
| `bin/sieve-mml-O2-no-tco` | 1.766 ± 0.066 | 1.00 | 18.75 | 7.32 | 5.19 | 4.81 |
| `bin/sieve-mml-O3-tco` | 1.761 ± 0.065 | 1.00 | 18.80 | 7.31 | 5.17 | 4.80 |
| `bin/sieve-mml-O3-no-tco` | 1.759 ± 0.065 | 1.00 | 18.76 | 7.06 | 5.19 | 4.81 |

At O0, frontend TCO reduces runtime from 11.856 to 1.987 ms, a 5.97× speedup. O1–O3
cluster around 1.76–1.86 ms. The small ordering differences within that cluster are
less useful than the clear cost of O0 without frontend TCO.

At O0, frontend TCO also reduces peak RSS from 28.05 to 5.19 MiB. The optimized
variants remain near 5.2 MiB; the large repeated values in Hyperfine's memory export
do not describe their actual independent peaks.

The saved May O3+TCO self-result was 20.192 ms. Today's is 1.761 ms. The high-optimization
slowdown in that saved batch does not persist.

### Naive matrix multiplication

[Raw measurements](2026-09-13/self-matmul.json).

| Command | Mean ± SD [ms] | Relative | Instructions [M] | Cycles [M] | Peak RSS [MiB] | Peak footprint [MiB] |
|:---|---:|---:|---:|---:|---:|---:|
| `bin/matmul-mml-O0-tco` | 63.986 ± 0.156 | 2.84 | 1526.69 | 275.03 | 7.12 | 6.75 |
| `bin/matmul-mml-O0-no-tco` | 589.499 ± 2.054 | 26.20 | 2799.63 | 2727.41 | 18.53 | 18.16 |
| `bin/matmul-mml-O1-tco` | 22.497 ± 0.134 | 1.00 | 460.39 | 97.92 | 7.12 | 6.75 |
| `bin/matmul-mml-O1-no-tco` | 40.744 ± 0.167 | 1.81 | 1025.01 | 177.08 | 7.14 | 6.77 |
| `bin/matmul-mml-O2-tco` | 25.821 ± 0.250 | 1.15 | 583.74 | 111.52 | 7.12 | 6.75 |
| `bin/matmul-mml-O2-no-tco` | 25.801 ± 0.135 | 1.15 | 583.63 | 110.98 | 7.12 | 6.75 |
| `bin/matmul-mml-O3-tco` | 25.804 ± 0.105 | 1.15 | 583.78 | 111.65 | 7.14 | 6.77 |
| `bin/matmul-mml-O3-no-tco` | 25.804 ± 0.099 | 1.15 | 583.76 | 111.82 | 7.14 | 6.77 |

O1+TCO is the fastest configuration at 22.497 ms. O2 and O3 cluster near 25.8 ms,
with little difference from the frontend TCO switch. O3+TCO takes 14.7% longer than
O1+TCO. This is a much smaller gap than May's 66.073 ms at O1 versus 279.169 ms at O3,
but it remains a concrete optimization opportunity.

The instruction counts give this investigation a concrete target: O1+TCO retires
460.39 million instructions, while O3+TCO retires 583.78 million, 26.8% more. Their
peak RSS differs by only 16 KiB in the resource run.

Frontend TCO matters at lower levels: O0 without it takes 589.499 ms against 63.986 ms
with it, a 9.21× difference. Peak RSS falls from 18.53 to 7.12 MiB with frontend TCO.
At O1 the difference is 40.744 versus 22.497 ms, or 1.81×.

### Interchanged matrix multiplication

[Raw measurements](2026-09-13/self-matmul-opt.json).

| Command | Mean ± SD [ms] | Relative | Instructions [M] | Cycles [M] | Peak RSS [MiB] | Peak footprint [MiB] |
|:---|---:|---:|---:|---:|---:|---:|
| `bin/matmul-opt-mml-O0-tco` | 128.096 ± 0.410 | 3.61 | 1902.89 | 555.22 | 7.14 | 6.77 |
| `bin/matmul-opt-mml-O0-no-tco` | 641.323 ± 2.211 | 18.07 | 2928.06 | 2955.60 | 18.53 | 18.16 |
| `bin/matmul-opt-mml-O1-tco` | 35.526 ± 0.144 | 1.00 | 895.80 | 155.01 | 7.12 | 6.75 |
| `bin/matmul-opt-mml-O1-no-tco` | 37.965 ± 0.126 | 1.07 | 1150.21 | 164.79 | 7.12 | 6.75 |
| `bin/matmul-opt-mml-O2-tco` | 35.486 ± 0.118 | 1.00 | 895.72 | 154.07 | 7.12 | 6.75 |
| `bin/matmul-opt-mml-O2-no-tco` | 35.505 ± 0.146 | 1.00 | 895.60 | 153.58 | 7.12 | 6.75 |
| `bin/matmul-opt-mml-O3-tco` | 35.508 ± 0.118 | 1.00 | 895.71 | 153.68 | 7.14 | 6.77 |
| `bin/matmul-opt-mml-O3-no-tco` | 35.511 ± 0.108 | 1.00 | 895.70 | 153.69 | 7.14 | 6.77 |

O1+TCO, O2, and O3 all take about 35.5 ms. The May O3+TCO result was 120.137 ms;
the large gap between May's O1 and O2/O3 results is absent here.

These targets do not pass `-force-vector-interleave=4`. The tuned cross-language
binary takes 17.769 ms, approximately half their time. This is the most useful
remaining question for the optimized matrix code: what prevents the default cost
model from choosing the faster form on this target? The resource run records 895.71
million instructions and 153.68 million cycles for untuned O3+TCO, against 396.80
million instructions and 76.50 million cycles for the tuned binary. Both peak at
7.14 MiB RSS.

At O0, frontend TCO gives a 5.01× speedup, from 641.323 to 128.096 ms. The O0+TCO result
is nevertheless slower than May's 57.735 ms. Explicit output initialization was added
to the source in September, so the old and new workloads are not identical. The size
of the difference warrants investigation before calling it a compiler regression.

## Memory measurement source

The memory columns in every table come from separate `/usr/bin/time -l` executions.
RSS and memory footprint are distinct process-level peak measurements; neither is a
total of heap allocations. MML's cross-language peak RSS is within 64 KiB of its C
counterpart throughout this round. Frontend TCO at O0 also substantially reduces
measured memory in all three self-benchmark workloads.

Hyperfine 1.20.0's exported memory arrays are excluded. A separate reproduction ran
`true`, a Python process allocating 128 MiB, then `true` again. The first `true`
reported 1,196,032 bytes. The allocating process reached 149,438,464 bytes, and the
final `true` inherited that exact value. The full suite exhibits the same retention
of earlier peaks.

The independent measurements resolve the misleading entries: Java native quicksort
uses 16.19 MiB RSS, rather than inheriting the JVM's roughly 99 MiB; N-Queens MML uses
1.31 MiB, rather than inheriting Go's roughly 4 MiB. Optimized MML self-benchmarks
return to ordinary memory levels after the O0-without-TCO runs.

## Follow-up work

1. Compare naive matmul's O1 and O3 generated loops. The remaining 14.7% difference is
   stable enough to investigate, and is far smaller than the old multi-fold slowdown.
2. Compare optimized matmul with and without the interleave setting under identical
   build conditions. Extend the self-benchmark comparison to include that setting so
   the 17.77 ms result and the 35.5 ms defaults appear together.
3. Investigate optimized matmul's O0+TCO increase, accounting for output initialization
   and changes in lowering before assigning a cause.
4. Keep independent memory collection in the benchmark workflow, or correct the
   Hyperfine export path before using it for per-program memory comparisons. Preserve
   build settings and both output streams with future timing and resource logs.

## Previous reports

- [2026-01-14](report-2026-01-14.md): initial full x86 comparison.
- [2026-02-07](report-2026-02-07.md): optimized matmul parity, with regressions elsewhere.
- [2026-03-14](report-2026-03-14.md): N-Queens improvement and persistent optimization gaps.
- [2026-05-17](report-2026-05-17-aarch.md): first M5 report, O3 slowdowns, and O1 reruns.
