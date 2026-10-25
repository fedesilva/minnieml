# Parser measurements

The [initial baseline](baseline-a2c9832.md) records complete measurements before parser recovery
changes, including source identities and the known grouped-lambda syntax failure. The
[final comparison](comparison-final.md) retains all 84 samples after recovery and compares
timings separately from instrumentation counters.

`ParserBaseline` measures the raw parser directly. It preloads source files, checks that
instrumented and uninstrumented parsing produce equal trees, then runs warmup and measured
batches through `Parser.parseModuleWithInfo`. The measured interval includes source indexing,
parsing, and a small checksum of the returned module's member count and source endpoint.
It excludes file reads, instrumentation, semantic analysis, diagnostic traversal, deep tree
comparison, and output.

The last result of each batch must equal the reference tree, and the accumulated checksum
must match. `ParsingErrorChecker` counts recovered errors recursively outside the measured
interval. A recovered module can contain errors; `outcome=module` alone does not establish
valid syntax. A fatal parser failure is reported separately as `outcome=failure`.

Run from the repository root with `sbtn`. The temporary setting below makes each invocation
use a fresh JVM. Arguments are the repository root, positive warmup batch count, measured
batch count, parses per batch, and one or more source paths relative to the root.

```sh
sbtn "set mmlclib / Test / fork := true; \
mmlclib / Test / runMain mml.mmlclib.parser.ParserBaseline \
$PWD 5 7 100 benchmark/nqueens.mml benchmark/mat-mul.mml \
mml/samples/astar2.mml mml/samples/raytracer.mml"
```

Repeat the command sequentially for at least three fresh JVMs when comparing revisions.
Keep inputs, JVM options, warmups, batch sizes, machine load, and source order unchanged.
Record the Git commit and any uncommitted source differences alongside the output. Compare
per-input batch medians across forks; a single run does not establish a performance change.
Do not run competing builds or benchmark processes during measurement.

The `set` command affects the shared sbt session. After measurement, restore the ordinary
test setting with `sbtn 'set mmlclib / Test / fork := false'`.

The output includes environment details, source sizes and SHA-256 digests, raw-tree digests,
per-batch nanoseconds, and counters from a separate instrumented parse. Counter timings
include instrumentation overhead; counter reductions alone do not prove a speed improvement.
Raw-tree digests are useful controls within this implementation, not a stable serialization
format across AST changes.

The tracked valid-source corpus above covers search, matrix arithmetic, a larger pathfinding
program, and a raytracer. Confirm `recovered_errors=0` for every valid input on each revision.
For a short tooling check, use one input with `1 1 1` instead of `5 7 100`.

The following is a separate known-failing syntax control, not part of successful-parse timing:

```sh
sbtn "set mmlclib / Test / fork := true; \
mmlclib / Test / runMain mml.mmlclib.parser.ParserBaseline \
$PWD 1 3 1 mml/samples/errors-are-not-granular.mml"
```

This source contains valid grouped-lambda syntax rejected by the parser baseline before the
recovery changes. Record its recovered-error count before changing recovery, and assess later
changes against the intended valid syntax. The harness reports raw parsing only; semantic
correctness requires separate compiler tests.

## Conditional compatibility control

[conditional-controls.mml](conditional-controls.mml) contains sixteen valid declarations covering
full/no-`else` conditionals, repeated `elif`, nesting, expression/member contexts, sequenced
conditions and branches, groups, tuple lambdas, and keyword-looking strings/comments.
It is a raw-parser compatibility control, not a runtime benchmark or executable sample.

At compiler commit `da7e9d19`, before conditional refactoring, this command passed on
2026-10-25 with zero recovered errors and equal instrumented/uninstrumented trees:

```sh
sbtn "set mmlclib / Test / fork := true; \
mmlclib / Test / runMain mml.mmlclib.parser.ParserBaseline \
$PWD 1 1 1 benchmark/parser/conditional-controls.mml; \
set mmlclib / Test / fork := false"
```

Source bytes: 1,312. Host: aarch64 macOS 26.6.2, Java 25.0.4.1, maximum heap
8,589,934,592 bytes. Compiler source matched the commit; the control was the only added
parser input. Its final repository path is part of the raw AST identity. The utility matches
the SHA-256 recorded in the initial baseline. These one-parse batches establish tree
compatibility only; they do not support a performance comparison.

```text
source_sha256  3b7539d165aeb32219964234e224b7e703fe7dadebdb4d6b6dbee06777982938
raw_ast_sha256 bce84d60ef25124afadcaf54c404c0e3e94ed3b53edd9836de2db219043a0077
```
