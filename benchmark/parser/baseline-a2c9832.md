# Parser baseline at a2c9832

Measured on 2026-10-25 before parser recovery or grammar changes. Compiler source is
commit `a2c9832513dc3eb36026ff439f2a6d9e54c754fe`; the only added executable source is the
[ParserBaseline utility](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/ParserBaseline.scala).
An unrelated task-document edit does not affect the measured compiler. No production
source differs from that commit for these runs.

Utility SHA-256:

```text
a9abff529e832e4d59861cc6cd4f6acd7bc8a0a55bbf68c4f21cb72e2bb14400
```

## Method

Run this command three times sequentially from the repository root:

```sh
sbtn "set mmlclib / Test / fork := true; \
mmlclib / Test / runMain mml.mmlclib.parser.ParserBaseline \
$PWD 5 7 100 benchmark/nqueens.mml benchmark/mat-mul.mml \
mml/samples/astar2.mml mml/samples/raytracer.mml"
```

Each invocation starts a fresh JVM. For each input, it preloads the source, compares one
uninstrumented result with one instrumented result, runs five warmup batches of 100 parses,
and records seven batches of 100 parses. Timing covers `Parser.parseModuleWithInfo`, including
source indexing, plus the small result checksum. File reads, instrumentation, deep tree
equality, recovered-error traversal, and output are outside measured intervals. Sources are
processed in the order shown. No other builds or measurements run concurrently.

Every input in every fork produced a module with zero recovered parser errors. Instrumented
and uninstrumented trees compared equal. Every batch had the expected checksum and its final
raw tree equaled the reference. These are parsing checks; no semantic or code-generation
claims follow from them. All three commands exited successfully with no compiler warnings or
errors. The JVM emitted the Scala library’s `sun.misc.Unsafe` deprecation notice outside the
measured intervals.

Restore the shared sbt setting afterward:

```sh
sbtn 'set mmlclib / Test / fork := false'
```

## Environment

All three forks reported the following environment:

| Field | Value |
|---|---|
| Java version | 25.0.4.1 |
| VM | OpenJDK 64-Bit Server VM |
| OS | Mac OS X 26.6.2 |
| Architecture | aarch64 |
| Available processors | 10 |
| Maximum heap | 8,589,934,592 bytes |
| sbt | 2.0.10 |

JVM arguments:

```text
-XX:ThreadPriorityPolicy=1 -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCIProduct
-XX:+EnableJVMCI -XX:-UnlockExperimentalVMOptions
```

Fork process IDs were 43552, 43771, 43967.

## Inputs and identity

All files contain ASCII source, so character counts equal byte counts. Source and raw-tree
digests were identical across forks. Raw-tree digests hash the parser result representation;
they are controls for these sources and AST definitions, not a stable serialization format.

### `benchmark/nqueens.mml`

Bytes: 1710. Recovered errors: 0.

```text
source_sha256  1f489dd491e8e2c09ba0602378b9ab27d2b0ee97afb311674193b933ff741f06
raw_ast_sha256 421c2899fd23a688b7e20a0762e9f93bf0ab291fe9bdde7e0403e78c7257e26a
```

### `benchmark/mat-mul.mml`

Bytes: 2255. Recovered errors: 0.

```text
source_sha256  51383fc14e8307643c33fa910bb44cc630fe147aa0e9a917a39b78769f1cc7ed
raw_ast_sha256 df7e53dc8a9faa00f924c6494dbc02a3a622ef45042f3aa289521f9679fd18f8
```

### `mml/samples/astar2.mml`

Bytes: 24700. Recovered errors: 0.

```text
source_sha256  5c7203f0e5dfb17210cbbf04c8a09926f0b75d0c597526d4371ae04bffb666e3
raw_ast_sha256 5e40acce30a2bce69337969cabc1e3dd17afc949b5512c7102fb0105660fb713
```

### `mml/samples/raytracer.mml`

Bytes: 6815. Recovered errors: 0.

```text
source_sha256  30cdd1249f08c7abad9364c723ec11691dd59a04670035b61e63b787ba4abd04
raw_ast_sha256 894beff396c0a7f514828ad41fdbb209bb6c73b6231c85f8633e70151cf3e309
```

## Batch measurements

Every `elapsed_ns` value covers 100 parses. Divide by 100 for nanoseconds per parse.
All measured samples are retained below; no sample is discarded.

### `benchmark/nqueens.mml`

```csv
fork,sample,elapsed_ns,checksum
1,1,30124250,171400
1,2,26692791,171400
1,3,25472542,171400
1,4,25741292,171400
1,5,21478542,171400
1,6,21641375,171400
1,7,22493375,171400
2,1,33562459,171400
2,2,31271542,171400
2,3,28299250,171400
2,4,28111209,171400
2,5,24161875,171400
2,6,23217250,171400
2,7,22231334,171400
3,1,31219750,171400
3,2,28841084,171400
3,3,26493083,171400
3,4,25635667,171400
3,5,23040333,171400
3,6,22976292,171400
3,7,21693625,171400
```

### `benchmark/mat-mul.mml`

```csv
fork,sample,elapsed_ns,checksum
1,1,47331291,226400
1,2,43488667,226400
1,3,42846417,226400
1,4,40774333,226400
1,5,41599792,226400
1,6,40332791,226400
1,7,46550125,226400
2,1,51014042,226400
2,2,45367666,226400
2,3,43470458,226400
2,4,42361625,226400
2,5,50696084,226400
2,6,44300291,226400
2,7,47889125,226400
3,1,49072334,226400
3,2,43838916,226400
3,3,43068750,226400
3,4,46461041,226400
3,5,43624166,226400
3,6,44285583,226400
3,7,42923000,226400
```

### `mml/samples/astar2.mml`

```csv
fork,sample,elapsed_ns,checksum
1,1,185000250,2472600
1,2,190041417,2472600
1,3,185929125,2472600
1,4,184370333,2472600
1,5,184009208,2472600
1,6,182935334,2472600
1,7,185750083,2472600
2,1,193056625,2472600
2,2,194108666,2472600
2,3,193977000,2472600
2,4,195905125,2472600
2,5,196205708,2472600
2,6,193421959,2472600
2,7,196007125,2472600
3,1,185953333,2472600
3,2,187517917,2472600
3,3,187837333,2472600
3,4,185692334,2472600
3,5,193333625,2472600
3,6,186011125,2472600
3,7,186184833,2472600
```

### `mml/samples/raytracer.mml`

```csv
fork,sample,elapsed_ns,checksum
1,1,118467541,683500
1,2,119363333,683500
1,3,117804209,683500
1,4,117525834,683500
1,5,117781625,683500
1,6,119678792,683500
1,7,118286375,683500
2,1,125663250,683500
2,2,130435375,683500
2,3,129467541,683500
2,4,129754167,683500
2,5,130196875,683500
2,6,126287792,683500
2,7,125898417,683500
3,1,120044084,683500
3,2,119914916,683500
3,3,122946459,683500
3,4,120353834,683500
3,5,119714667,683500
3,6,119641417,683500
3,7,120060666,683500
```

## Medians

Each fork median uses its seven samples. The final column is the median of the three fork
medians. Values are nanoseconds per parse; these summarize one baseline, not a speedup.

| Source | Fork 1 | Fork 2 | Fork 3 | Median of fork medians |
|---|---:|---:|---:|---:|
| `benchmark/nqueens.mml` | 254,725.42 | 281,112.09 | 256,356.67 | 256,356.67 |
| `benchmark/mat-mul.mml` | 428,464.17 | 453,676.66 | 438,389.16 | 438,389.16 |
| `mml/samples/astar2.mml` | 1,850,002.50 | 1,941,086.66 | 1,861,848.33 | 1,861,848.33 |
| `mml/samples/raytracer.mml` | 1,182,863.75 | 1,294,675.41 | 1,200,440.84 | 1,200,440.84 |

## Separate instrumented counters

One instrumented parse per input per fork produced identical summary counts. These counts
are separate from the timed uninstrumented batches.

| Source | Rule invocations | Backtrack counter | Progress (%) | Unique rules |
|---|---:|---:|---:|---:|
| `benchmark/nqueens.mml` | 27,579 | 12,247 | 100 | 80 |
| `benchmark/mat-mul.mml` | 69,030 | 43,341 | 100 | 79 |
| `mml/samples/astar2.mml` | 258,979 | 151,203 | 100 | 81 |
| `mml/samples/raytracer.mml` | 183,527 | 114,147 | 100 | 81 |

The four inputs total 539,115 rule invocations and 320,938 backtrack-counter events per fork.
Unique-rule counts overlap across inputs and are not summed.

The collector increments its backtrack counter whenever a rule starts before the furthest
previous rule-start index. It does not count grammar alternatives or discarded trees
directly. Progress is that furthest start index as a percentage of source length. Counter
reductions alone do not establish a time improvement. Instrumented rule timings include
collector overhead and are not used as parser-duration evidence here.

## Known grouped-lambda syntax control

A separate tooling run used one warmup batch, one measured batch, and one parse per batch
with `benchmark/nqueens.mml` followed by `mml/samples/errors-are-not-granular.mml`:

```sh
sbtn "set mmlclib / Test / fork := true; mmlclib / Test / runMain mml.mmlclib.parser.ParserBaseline $PWD 1 1 1 benchmark/nqueens.mml mml/samples/errors-are-not-granular.mml"
```

The second source is 659 bytes and produced a recovered module containing six parser errors.
Its valid grouped-lambda syntax is a known baseline parser failure. This control is excluded
from successful-parse timing above. Instrumented and uninstrumented trees compared equal,
and the single measured batch passed the same checksum and final-tree checks.

```text
source_sha256  00a37ddd4e8358691c9b146dde23b3b48db8aef0a4caf128f136148e75a8120a
raw_ast_sha256 9a7d0c62acbef22043f6ad29c0abe808f5b23aed1121c6ea6a6a40ca22fa5cca
```

## Limits

This is a small local corpus on one machine and JVM configuration. Warmup does not prove
complete JIT stabilization; garbage collection and system scheduling remain part of observed
batch variance. Nqueens samples decline across each fork, from about 0.301/0.336/0.312 ms per
parse in the first sample to 0.225/0.222/0.217 ms in the last. This is consistent with continued
warmup; do not treat these medians as proof of fully steady performance.

The lightweight checksum consumes every result but does not traverse every AST node. Deep
equality checks the reference, instrumented result, and final result of each batch outside
timing. Repeat the same commands and identities for later comparisons, retain all samples,
and use separate regression tests to establish language and recovery behavior.
