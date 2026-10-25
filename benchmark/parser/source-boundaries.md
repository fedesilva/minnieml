# Parser measurements after source-boundary correction

Measured on 2026-10-25 with the source-boundary correction. The parser-source manifest below
identifies the measured implementation. Its SHA-256 digests match the fingerprint captured
before the replacement runs and the check after all three completed.

This report compares with the [preceding recovery measurements](comparison-final.md),
not the initial parser baseline. Historical reports retain their original results and hashes.
The four median timings are lower in this run set. They are local observations, with the
between-run variation and measurement limits described below.

## Method and results

Three fresh JVMs ran sequentially, each reading the same four inputs in the order shown below.
Each input received five warmup batches and seven measured batches, with 100 parses per batch.
The shared build session was reserved for these measurements. All three commands exited 0;
the third command restored `mmlclib / Test / fork` to `false`. An earlier run interrupted by
checkout activity is excluded; these are three complete replacement runs after the checkout
was restored and the compiler rebuilt.

```sh
sbtn "set mmlclib / Test / fork := true; \
mmlclib / Test / runMain mml.mmlclib.parser.ParserBaseline \
$PWD 5 7 100 benchmark/nqueens.mml benchmark/mat-mul.mml \
mml/samples/astar2.mml mml/samples/raytracer.mml"
# Repeat sequentially for three fresh JVMs, then restore:
sbtn 'set mmlclib / Test / fork := false'
```

Timing covers raw parsing, source indexing, and a small result checksum. File reads,
instrumentation, diagnostic traversal, full tree comparisons, and output occur outside the
measured interval. Every input in every fork produces a module with zero recovered parser
errors. Instrumented and uninstrumented trees compare equal. Every warmup and measured batch
passes its checksum and final-tree check.

Values below are nanoseconds per parse. Each fork value is the median of its seven batch
means (`elapsed_ns / 100`); the current median is the median of those three fork values.
Change is relative to the preceding recovery report's median. Negative values mean less
elapsed time in these runs.

| Input                     |       Fork 1 |       Fork 2 |       Fork 3 | Current median | Prior median | Change |
| ------------------------- | -----------: | -----------: | -----------: | -------------: | -----------: | -----: |
| benchmark/nqueens.mml     |   219,186.25 |   220,627.50 |   324,294.16 |     220,627.50 |   245,288.75 | -10.1% |
| benchmark/mat-mul.mml     |   294,561.25 |   302,732.08 |   337,934.58 |     302,732.08 |   330,767.08 |  -8.5% |
| mml/samples/astar2.mml    | 1,158,069.17 | 1,146,613.75 | 1,158,655.83 |   1,158,069.17 | 1,387,856.66 | -16.6% |
| mml/samples/raytracer.mml |   733,030.41 |   724,212.92 |   732,165.42 |     732,165.42 |   894,286.25 | -18.1% |

## Environment

The replacement forks report the same Java, operating-system, architecture, processor-count,
heap, and JVM-argument values as the preceding report. They use Java 25.0.4.1 and sbt 2.0.10
on aarch64 macOS 26.6.2, with 10 available processors and a maximum heap of 8,589,934,592 bytes.
These reported settings do not establish identical operating-system scheduling or JIT state.

Fork process IDs: 55413, 55727, 56013.

```text
java.version=25.0.4.1
java.vm.name=OpenJDK 64-Bit Server VM
os.name=Mac OS X
os.version=26.6.2
os.arch=aarch64
processors=10
max_heap_bytes=8589934592
jvm_args=-XX:ThreadPriorityPolicy=1 -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCIProduct -XX:+EnableJVMCI -XX:-UnlockExperimentalVMOptions
warmup_batches=5
sample_batches=7
iterations=100
```

## Parser and measurement utility identities

The [measurement utility](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/ParserBaseline.scala)
is unchanged from the preceding report:

```text
a9abff529e832e4d59861cc6cd4f6acd7bc8a0a55bbf68c4f21cb72e2bb14400
```

The following SHA-256 manifest covers all Scala files directly under
`modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/`. All 15 file digests were unchanged
between the pre-measurement fingerprint and the check after the three replacement runs.

```text
da261925b0c625f38ec5195453f988186acb0eedd7b091b7ca61dbb903da4bf2  MmlWhitespace.scala
927828f07068e3388d82f0c431fad0f09037ea92a6e27b0cbd0f2fb3aa1b09d4  Parser.scala
cd484d8c11a25f4e2d26d75fddb79d2a90a1804d05681bc7092fb94425785ce8  ParserMetricsCollector.scala
9653d15f18b6def0e4902130c65bd8a9eb2b9318edd511f6cf4b22fdd48b3478  Recovery.scala
220ebb0c9f77cf9e8e0d8f11cfb02fc01692208a74c4674770d71bbc517a1466  bindingRecovery.scala
3f3003352b42205c984231aedafcf90028ccae2848ca8adf7c2bb16bf69f9530  expressions.scala
b71369c182e323ad55ba84a3d7e47756222a5f84354ca3976f004778ccf3b456  identifiers.scala
f10b8f8b90a09dca43c87097396b605ba28405f9be9e07953bd123b413205240  keywords.scala
0e94e87fc7374b717c0f0b052ab9d04997d52f195935917284cb73cb90128285  links.scala
3811f195829961d9648aeb3065d49147be62e32681d4ffcb21218a79a9180b3c  literals.scala
5180e63dcbddb231af92c8e832507e7bc4060f0b33e872cc584685fe1ffbd12d  members.scala
8faf620073c327ff4113aedf672bce2f3ceb91a25860e70525b9a3c7bfcb74aa  modules.scala
e9442d9690bdad3b4822f7259448365f4c08b6e58e87c4a7dac01d276f64b990  srcinfo.scala
8b87fab8519794c4c79125f4a743a3e38b62dd90b1697d1e63fb92ea74c2ced3  types.scala
02daf9b1d84764dfa43520722c2809815ecedba2c503234262710e576915c42e  utils.scala
```

## Input and raw-tree identities

Input sizes and source hashes match the preceding recovery report. The corrected raw-tree
hashes below agree across all three replacement forks. Each differs from the preceding
report because source locations are part of the raw AST representation and this correction
changes those locations. Cross-revision raw-tree equality is therefore not an acceptance
condition for this work. These hashes identify results; by themselves they do not prove that
all other tree properties are unchanged. Grammar and recovery tests supply separate evidence.

### `benchmark/nqueens.mml`

Bytes/characters: 1710/1710. Outcome: module. Recovered errors: 0.

```text
source_sha256  1f489dd491e8e2c09ba0602378b9ab27d2b0ee97afb311674193b933ff741f06
raw_ast_sha256 846f484dbb3fe9cd046da95de54a4f17c8d4feb0526ffcd22a3396755ab8df64
```

### `benchmark/mat-mul.mml`

Bytes/characters: 2255/2255. Outcome: module. Recovered errors: 0.

```text
source_sha256  51383fc14e8307643c33fa910bb44cc630fe147aa0e9a917a39b78769f1cc7ed
raw_ast_sha256 7707cc31ce50f3d6872cb0a939f0ae64d15c8e1203bc8dad8f8572bf9fc78671
```

### `mml/samples/astar2.mml`

Bytes/characters: 24700/24700. Outcome: module. Recovered errors: 0.

```text
source_sha256  5c7203f0e5dfb17210cbbf04c8a09926f0b75d0c597526d4371ae04bffb666e3
raw_ast_sha256 d3f799dee70eb4813c876986b0157162e74f2d21235ed2aa7c330ed81c40900e
```

### `mml/samples/raytracer.mml`

Bytes/characters: 6815/6815. Outcome: module. Recovered errors: 0.

```text
source_sha256  30cdd1249f08c7abad9364c723ec11691dd59a04670035b61e63b787ba4abd04
raw_ast_sha256 c01b796556684aa3cbddb04dbb86d5ad4845768c2fbd415d4e43d07fc2e34f87
```

## Complete measured samples

All 84 measured samples are retained. Each elapsed value covers 100 parses; no sample from
these three replacement forks is discarded. Warmup batches are excluded from this table.
Checksums agree across forks and match the preceding report: the checksum uses module member
count and whole-file endpoint, which do not change when child spans are corrected.

```csv
fork,input,sample,elapsed_ns,iterations,checksum
1,benchmark/nqueens.mml,1,28984667,100,171400
1,benchmark/nqueens.mml,2,28136167,100,171400
1,benchmark/nqueens.mml,3,26080208,100,171400
1,benchmark/nqueens.mml,4,21918625,100,171400
1,benchmark/nqueens.mml,5,18974584,100,171400
1,benchmark/nqueens.mml,6,17876709,100,171400
1,benchmark/nqueens.mml,7,18571083,100,171400
2,benchmark/nqueens.mml,1,33440416,100,171400
2,benchmark/nqueens.mml,2,30389708,100,171400
2,benchmark/nqueens.mml,3,27467500,100,171400
2,benchmark/nqueens.mml,4,22062750,100,171400
2,benchmark/nqueens.mml,5,20530875,100,171400
2,benchmark/nqueens.mml,6,20348333,100,171400
2,benchmark/nqueens.mml,7,21072667,100,171400
3,benchmark/nqueens.mml,1,32846167,100,171400
3,benchmark/nqueens.mml,2,34248917,100,171400
3,benchmark/nqueens.mml,3,27646375,100,171400
3,benchmark/nqueens.mml,4,32429416,100,171400
3,benchmark/nqueens.mml,5,25206417,100,171400
3,benchmark/nqueens.mml,6,29736875,100,171400
3,benchmark/nqueens.mml,7,34066459,100,171400
1,benchmark/mat-mul.mml,1,30993667,100,226400
1,benchmark/mat-mul.mml,2,29456125,100,226400
1,benchmark/mat-mul.mml,3,32288333,100,226400
1,benchmark/mat-mul.mml,4,31678750,100,226400
1,benchmark/mat-mul.mml,5,25489750,100,226400
1,benchmark/mat-mul.mml,6,28789250,100,226400
1,benchmark/mat-mul.mml,7,25958000,100,226400
2,benchmark/mat-mul.mml,1,34323625,100,226400
2,benchmark/mat-mul.mml,2,29016166,100,226400
2,benchmark/mat-mul.mml,3,30273208,100,226400
2,benchmark/mat-mul.mml,4,31163417,100,226400
2,benchmark/mat-mul.mml,5,26789250,100,226400
2,benchmark/mat-mul.mml,6,30896708,100,226400
2,benchmark/mat-mul.mml,7,25713000,100,226400
3,benchmark/mat-mul.mml,1,52959333,100,226400
3,benchmark/mat-mul.mml,2,45130125,100,226400
3,benchmark/mat-mul.mml,3,27823625,100,226400
3,benchmark/mat-mul.mml,4,28881500,100,226400
3,benchmark/mat-mul.mml,5,33793458,100,226400
3,benchmark/mat-mul.mml,6,34984708,100,226400
3,benchmark/mat-mul.mml,7,29167709,100,226400
1,mml/samples/astar2.mml,1,116771709,100,2472600
1,mml/samples/astar2.mml,2,116791625,100,2472600
1,mml/samples/astar2.mml,3,115692041,100,2472600
1,mml/samples/astar2.mml,4,115806917,100,2472600
1,mml/samples/astar2.mml,5,115349958,100,2472600
1,mml/samples/astar2.mml,6,115560708,100,2472600
1,mml/samples/astar2.mml,7,116182375,100,2472600
2,mml/samples/astar2.mml,1,114490041,100,2472600
2,mml/samples/astar2.mml,2,114668250,100,2472600
2,mml/samples/astar2.mml,3,115183959,100,2472600
2,mml/samples/astar2.mml,4,114740917,100,2472600
2,mml/samples/astar2.mml,5,114652166,100,2472600
2,mml/samples/astar2.mml,6,114661375,100,2472600
2,mml/samples/astar2.mml,7,113986250,100,2472600
3,mml/samples/astar2.mml,1,115907125,100,2472600
3,mml/samples/astar2.mml,2,115865583,100,2472600
3,mml/samples/astar2.mml,3,116428042,100,2472600
3,mml/samples/astar2.mml,4,114985250,100,2472600
3,mml/samples/astar2.mml,5,116120958,100,2472600
3,mml/samples/astar2.mml,6,114929084,100,2472600
3,mml/samples/astar2.mml,7,114900166,100,2472600
1,mml/samples/raytracer.mml,1,73303041,100,683500
1,mml/samples/raytracer.mml,2,73282334,100,683500
1,mml/samples/raytracer.mml,3,73165916,100,683500
1,mml/samples/raytracer.mml,4,73936167,100,683500
1,mml/samples/raytracer.mml,5,73035000,100,683500
1,mml/samples/raytracer.mml,6,75176583,100,683500
1,mml/samples/raytracer.mml,7,73534541,100,683500
2,mml/samples/raytracer.mml,1,72421292,100,683500
2,mml/samples/raytracer.mml,2,72183208,100,683500
2,mml/samples/raytracer.mml,3,72703084,100,683500
2,mml/samples/raytracer.mml,4,73064500,100,683500
2,mml/samples/raytracer.mml,5,72087709,100,683500
2,mml/samples/raytracer.mml,6,72905416,100,683500
2,mml/samples/raytracer.mml,7,72083250,100,683500
3,mml/samples/raytracer.mml,1,73216542,100,683500
3,mml/samples/raytracer.mml,2,75231166,100,683500
3,mml/samples/raytracer.mml,3,75818875,100,683500
3,mml/samples/raytracer.mml,4,76756166,100,683500
3,mml/samples/raytracer.mml,5,72955916,100,683500
3,mml/samples/raytracer.mml,6,72765584,100,683500
3,mml/samples/raytracer.mml,7,72965917,100,683500
```

## Separate instrumented counters

These summary counters come from an instrumented parse outside the timed batches and are
identical across the three replacement forks.

| Input                     | Invocations | Backtrack counter | Progress (%) | Unique rules |
| ------------------------- | ----------: | ----------------: | -----------: | -----------: |
| benchmark/nqueens.mml     |      20,463 |             9,148 |          100 |           87 |
| benchmark/mat-mul.mml     |      40,528 |            20,234 |          100 |           86 |
| mml/samples/astar2.mml    |     153,991 |            73,030 |          100 |           89 |
| mml/samples/raytracer.mml |     108,252 |            55,661 |          100 |           89 |

The corpus totals 323,234 invocations versus 324,939 in the preceding recovery
report (-0.52%), and 158,073 backtrack-counter events versus 159,080
(-0.63%). Unique-rule counts are not additive. The backtrack counter counts rule starts
before the furthest previous rule-start position, not literal alternative rewinds or
discarded ASTs. Parser factoring changes the rules exposed to instrumentation; these counter
differences do not demonstrate a corresponding change in parsing work or elapsed time.

## Limits

This is a four-input, successful-parse corpus on one host and JVM configuration, with three
forks per revision. The preceding measurements and these runs were collected at different
times, rather than as interleaved old/new runs. OS scheduling, garbage collection, and JIT
compilation can affect the results. N-Queens continues falling after warmup in the first two
forks, while its third fork is noticeably slower; matrix multiplication also varies more in
the third fork. All those samples contribute to the published summary.

The observed medians do not establish fully steady-state performance, statistical significance,
a causal speedup from the source-boundary correction, or a general performance guarantee.
They do not measure malformed-input latency, type checking, whole-compiler time, peak memory,
native program execution, or cross-platform behavior. Build, semantic, and sanitizer checks
are separate evidence in the [source-boundary task](../../context/tasks/completed/parser-source-boundaries.md).
