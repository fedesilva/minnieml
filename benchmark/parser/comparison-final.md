# Parser comparison after recovery

Measured on 2026-10-25 at compiler commit `81c2d3024634cc89edc210d59c67ecdbaf7924c4`. Compiler and measurement
utility sources matched that commit during measurement. The subsequent grouped-lambda
normalization correction changes semantic rewriting after parsing; parser and measurement
utility sources remain unchanged. These results measure the raw parser at the recorded commit.
Compare with the [initial baseline](baseline-a2c9832.md) at `a2c9832`.

## Method and results

Three fresh JVMs ran sequentially with the same four-source order, JVM arguments, five warmup
batches, seven measured batches, and 100 parses per batch as the baseline. No competing builds
or benchmark processes ran. Each command exited 0; the shared fork setting was restored to false.
The utility hash is unchanged:

```text
a9abff529e832e4d59861cc6cd4f6acd7bc8a0a55bbf68c4f21cb72e2bb14400
```

```sh
sbtn "set mmlclib / Test / fork := true; \
mmlclib / Test / runMain mml.mmlclib.parser.ParserBaseline \
$PWD 5 7 100 benchmark/nqueens.mml benchmark/mat-mul.mml \
mml/samples/astar2.mml mml/samples/raytracer.mml"
# Repeat sequentially for three fresh JVMs, then restore:
sbtn 'set mmlclib / Test / fork := false'
```

Timing covers raw parsing, source indexing, and a small result checksum. File reads,
instrumentation, diagnostic traversal, full tree comparisons, and output are outside the
measured interval. Every input in every fork has zero recovered parser errors; instrumented
and uninstrumented trees compare equal. Every batch passes its checksum and final-tree check.
All source and raw-AST hashes match the baseline. These establish raw-parser compatibility for
this corpus; semantic, native execution, and sanitizer results are separate task evidence.

Values below are nanoseconds per parse. The comparison uses the median of each fork's seven
batch means, then the median of those three values. Negative change means less elapsed time.

| Input | Fork 1 | Fork 2 | Fork 3 | Final median | Baseline median | Change |
|---|---:|---:|---:|---:|---:|---:|
| `benchmark/nqueens.mml` | 256,039.17 | 245,288.75 | 241,708.75 | 245,288.75 | 256,356.67 | -4.3% |
| `benchmark/mat-mul.mml` | 338,902.50 | 323,369.16 | 330,767.08 | 330,767.08 | 438,389.16 | -24.5% |
| `mml/samples/astar2.mml` | 1,387,856.66 | 1,382,070.41 | 1,416,382.91 | 1,387,856.66 | 1,861,848.33 | -25.5% |
| `mml/samples/raytracer.mml` | 949,925.83 | 847,453.33 | 894,286.25 | 894,286.25 | 1,200,440.84 | -25.5% |

## Environment

All forks use GraalVM Community Java 25.0.4.1 with sbt 2.0.10 on the same aarch64 macOS
26.6.2 host as the baseline. Available processors: 10; maximum heap: 8,589,934,592 bytes.
No Scala compiler warnings were reported. The JVM emits the Scala library's `sun.misc.Unsafe`
deprecation notice outside measured intervals.

Fork process IDs: 59244, 59426, 59570.

```text
java.version=25.0.4.1 java.vm.name=OpenJDK 64-Bit Server VM os.name=Mac OS X os.version=26.6.2 os.arch=aarch64 processors=10 max_heap_bytes=8589934592 jvm_args=-XX:ThreadPriorityPolicy=1 -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCIProduct -XX:+EnableJVMCI -XX:-UnlockExperimentalVMOptions
```

## Input identities

### `benchmark/nqueens.mml`

Bytes/characters: 1710/1710. Recovered errors: 0.

```text
source_sha256  1f489dd491e8e2c09ba0602378b9ab27d2b0ee97afb311674193b933ff741f06
raw_ast_sha256 421c2899fd23a688b7e20a0762e9f93bf0ab291fe9bdde7e0403e78c7257e26a
```

### `benchmark/mat-mul.mml`

Bytes/characters: 2255/2255. Recovered errors: 0.

```text
source_sha256  51383fc14e8307643c33fa910bb44cc630fe147aa0e9a917a39b78769f1cc7ed
raw_ast_sha256 df7e53dc8a9faa00f924c6494dbc02a3a622ef45042f3aa289521f9679fd18f8
```

### `mml/samples/astar2.mml`

Bytes/characters: 24700/24700. Recovered errors: 0.

```text
source_sha256  5c7203f0e5dfb17210cbbf04c8a09926f0b75d0c597526d4371ae04bffb666e3
raw_ast_sha256 5e40acce30a2bce69337969cabc1e3dd17afc949b5512c7102fb0105660fb713
```

### `mml/samples/raytracer.mml`

Bytes/characters: 6815/6815. Recovered errors: 0.

```text
source_sha256  30cdd1249f08c7abad9364c723ec11691dd59a04670035b61e63b787ba4abd04
raw_ast_sha256 894beff396c0a7f514828ad41fdbb209bb6c73b6231c85f8633e70151cf3e309
```

## Complete measured samples

All 84 samples are retained. Each elapsed value covers 100 parses; no sample is discarded.
Warmup batches are not timed. Checksums match the baseline for every source.

```csv
fork,input,sample,elapsed_ns,iterations,checksum
1,benchmark/nqueens.mml,1,35469042,100,171400
1,benchmark/nqueens.mml,2,32225375,100,171400
1,benchmark/nqueens.mml,3,26187875,100,171400
1,benchmark/nqueens.mml,4,25603917,100,171400
1,benchmark/nqueens.mml,5,20459667,100,171400
1,benchmark/nqueens.mml,6,20079084,100,171400
1,benchmark/nqueens.mml,7,20086209,100,171400
2,benchmark/nqueens.mml,1,31233459,100,171400
2,benchmark/nqueens.mml,2,29168459,100,171400
2,benchmark/nqueens.mml,3,27332333,100,171400
2,benchmark/nqueens.mml,4,24528875,100,171400
2,benchmark/nqueens.mml,5,20408125,100,171400
2,benchmark/nqueens.mml,6,20231042,100,171400
2,benchmark/nqueens.mml,7,19233875,100,171400
3,benchmark/nqueens.mml,1,31793583,100,171400
3,benchmark/nqueens.mml,2,29212167,100,171400
3,benchmark/nqueens.mml,3,26542083,100,171400
3,benchmark/nqueens.mml,4,24170875,100,171400
3,benchmark/nqueens.mml,5,19939166,100,171400
3,benchmark/nqueens.mml,6,19183667,100,171400
3,benchmark/nqueens.mml,7,20852708,100,171400
1,benchmark/mat-mul.mml,1,35854792,100,226400
1,benchmark/mat-mul.mml,2,34499584,100,226400
1,benchmark/mat-mul.mml,3,31581958,100,226400
1,benchmark/mat-mul.mml,4,31918084,100,226400
1,benchmark/mat-mul.mml,5,30767750,100,226400
1,benchmark/mat-mul.mml,6,35249208,100,226400
1,benchmark/mat-mul.mml,7,33890250,100,226400
2,benchmark/mat-mul.mml,1,34257875,100,226400
2,benchmark/mat-mul.mml,2,32824584,100,226400
2,benchmark/mat-mul.mml,3,29818208,100,226400
2,benchmark/mat-mul.mml,4,30542583,100,226400
2,benchmark/mat-mul.mml,5,28945333,100,226400
2,benchmark/mat-mul.mml,6,32992666,100,226400
2,benchmark/mat-mul.mml,7,32336916,100,226400
3,benchmark/mat-mul.mml,1,36477292,100,226400
3,benchmark/mat-mul.mml,2,35952167,100,226400
3,benchmark/mat-mul.mml,3,33586958,100,226400
3,benchmark/mat-mul.mml,4,33076708,100,226400
3,benchmark/mat-mul.mml,5,31269500,100,226400
3,benchmark/mat-mul.mml,6,31907958,100,226400
3,benchmark/mat-mul.mml,7,31082292,100,226400
1,mml/samples/astar2.mml,1,139696166,100,2472600
1,mml/samples/astar2.mml,2,138290542,100,2472600
1,mml/samples/astar2.mml,3,139450083,100,2472600
1,mml/samples/astar2.mml,4,138785666,100,2472600
1,mml/samples/astar2.mml,5,138662417,100,2472600
1,mml/samples/astar2.mml,6,139094542,100,2472600
1,mml/samples/astar2.mml,7,138068292,100,2472600
2,mml/samples/astar2.mml,1,139577500,100,2472600
2,mml/samples/astar2.mml,2,138887000,100,2472600
2,mml/samples/astar2.mml,3,137902000,100,2472600
2,mml/samples/astar2.mml,4,137539041,100,2472600
2,mml/samples/astar2.mml,5,138207041,100,2472600
2,mml/samples/astar2.mml,6,138527458,100,2472600
2,mml/samples/astar2.mml,7,138169375,100,2472600
3,mml/samples/astar2.mml,1,141638291,100,2472600
3,mml/samples/astar2.mml,2,142625125,100,2472600
3,mml/samples/astar2.mml,3,141034500,100,2472600
3,mml/samples/astar2.mml,4,142434792,100,2472600
3,mml/samples/astar2.mml,5,141409542,100,2472600
3,mml/samples/astar2.mml,6,142497042,100,2472600
3,mml/samples/astar2.mml,7,141118042,100,2472600
1,mml/samples/raytracer.mml,1,94992583,100,683500
1,mml/samples/raytracer.mml,2,98913292,100,683500
1,mml/samples/raytracer.mml,3,189029125,100,683500
1,mml/samples/raytracer.mml,4,108067375,100,683500
1,mml/samples/raytracer.mml,5,88196000,100,683500
1,mml/samples/raytracer.mml,6,88247208,100,683500
1,mml/samples/raytracer.mml,7,89417750,100,683500
2,mml/samples/raytracer.mml,1,85571292,100,683500
2,mml/samples/raytracer.mml,2,85622625,100,683500
2,mml/samples/raytracer.mml,3,85345333,100,683500
2,mml/samples/raytracer.mml,4,84745333,100,683500
2,mml/samples/raytracer.mml,5,84233250,100,683500
2,mml/samples/raytracer.mml,6,84480209,100,683500
2,mml/samples/raytracer.mml,7,84342167,100,683500
3,mml/samples/raytracer.mml,1,90473417,100,683500
3,mml/samples/raytracer.mml,2,89692041,100,683500
3,mml/samples/raytracer.mml,3,89943042,100,683500
3,mml/samples/raytracer.mml,4,89428625,100,683500
3,mml/samples/raytracer.mml,5,89375625,100,683500
3,mml/samples/raytracer.mml,6,89146833,100,683500
3,mml/samples/raytracer.mml,7,89155792,100,683500
```

## Separate instrumented counters

Summary counters are identical across the three forks and were collected outside timed batches.

| Input | Invocations | Backtrack counter | Progress (%) | Unique rules |
|---|---:|---:|---:|---:|
| `benchmark/nqueens.mml` | 20,600 | 9,221 | 100 | 88 |
| `benchmark/mat-mul.mml` | 40,802 | 20,357 | 100 | 87 |
| `mml/samples/astar2.mml` | 154,835 | 73,559 | 100 | 90 |
| `mml/samples/raytracer.mml` | 108,702 | 55,943 | 100 | 90 |

The corpus totals 324,939 invocations versus 539,115 in the baseline (-39.7%),
and 159,080 backtrack-counter events versus 320,938 (-50.4%). Unique rules
are not additive. Backtracks count rule starts before the furthest earlier rule-start index,
not literal alternative rewinds or discarded ASTs. Rule factoring changes instrumentation;
these counts alone do not demonstrate a speedup.

## Limits

This is a small corpus on one machine and JVM configuration, with three forks per revision.
The baseline and final runs occurred at different times. OS scheduling, garbage collection,
and continued JIT warmup can affect the samples. In particular, the baseline nqueens batches
continued falling after warmup; neither set establishes fully steady-state performance.
The table describes observed local timings, not a general speed guarantee or statistical
significance claim. It does not measure malformed-input latency, peak memory, native program
performance, or cross-platform behavior.

The sixteen-declaration [conditional control](Readme.md#conditional-compatibility-control)
separately preserves exact raw trees and zero parse errors; its one-parse check is not included
in the timing comparison. The grouped-lambda control rejected at the baseline compiles and runs
with output `6`, `6`, `4`, `3`; it is also excluded from successful-parse timing.
