# Scala Tools

How to build, test, and run the MML compiler during development.

## Build tool

The project uses sbt with a thin client (`sbtn`). Always use `sbtn`.

The root project `mml` aggregates `mmlclib` (compiler library) and `mmlc` (CLI).
Always run tasks from the root — aggregation handles subprojects automatically.

Do not run multiple `sbtn` commands in parallel. The thin client/server socket is
single-session in practice, and concurrent invocations collide or fail with connection
errors. If multiple sbt tasks are needed, batch them into one `sbtn "task1" "task2"`
command or run separate `sbtn` commands sequentially.

## Running the compiler during development

The following applies *only if you are working with the compiler*.
If you are working with mml sources and you know the compiler is fresh and
there are no changes, sbtn only adds overhead and is not parallelizable.

Use `sbtn` to run the in-development compiler directly, without publishing.

```
sbtn "run run <file>.mml"          # compile and run
sbtn "run <file>.mml"              # compile only
sbtn "run ir <file>.mml"           # emit LLVM IR
sbtn "run run -s <file>.mml"       # compile and run with ASan
```

## SBT subprojects

The top level project aggregates the sub projects
*Prefer* running tests, and other tasks from the top level, not specific modules.

## Tests

```
sbtn test                          # full test suite (all modules)
sbtn "testOnly <fully.qualified.TestClass>"   # single test class
```

### Filtering tests within a suite

Use munit's built-in filtering via `testOnly` arguments:

```
sbtn "testOnly <TestClass> -- *substring*"           # glob match on test name
sbtn "testOnly <TestClass> -- --tests=exact-name"    # exact test name
sbtn "testOnly <TestClass> -- \"--tests=name with spaces\""
```

In-code filtering (for development only, do not commit):
- `test("name".only) { ... }` — run only this test
- `test("name".ignore) { ... }` — skip this test

Tag-based filtering:
```
sbtn "testOnly <TestClass> -- --include-tags=TagName"
sbtn "testOnly <TestClass> -- --exclude-tags=TagName"
```

## Formatting and linting

Run before finishing any code change:
```
sbtn scalafmtAll
sbtn scalafixAll
```

Fix all compiler warnings and exhaustivity errors.

## Publishing

Install the fat jar to `~/bin` for standalone use:
```
sbtn mmlcPublishLocal
```

Never publish without first verifying the compiler works via `sbtn "run run ..."`.

After publishing, `mmlc` is available system-wide:
```
mmlc run <file>.mml                # compile and run
mmlc <file>.mml                    # compile only
mmlc ir <file>.mml                 # emit LLVM IR
mmlc -I <file>.mml                 # optimized IR
mmlc -s <file>.mml                 # ASan instrumented
mmlc -h                            # Compiler arguments help
```

Select optimization with `-O0`, `-O1`, `-O2`, or `-O3` (the default). These flags
are mutually exclusive.

Use repeatable `--llvm-opt-arg` options to forward individual arguments to LLVM `opt`
when compiling an executable or library, or using `mmlc run`:

```sh
mmlc -O3 --llvm-opt-arg=-force-vector-interleave=4 benchmark/mat-mul-opt.mml
mmlc run -O3 --llvm-opt-arg=-pass-remarks=loop-vectorize \
  --llvm-opt-arg=-pass-remarks-missed=loop-vectorize benchmark/mat-mul-opt.mml
```

Each occurrence forwards one argument, in order. Quote the whole option if its value
contains spaces. Arguments go only to `opt`; LLVM validates them, and availability
depends on the installed LLVM version. Without this option, the optimizer defaults
are unchanged. Forced interleaving is a tuning experiment, not a guarantee of SIMD.

Target CPU and feature attributes come from the selected Clang using the same target
flags as the runtime. Local builds use Clang's native CPU selection; `--target`
without `--cpu` uses that target's default CPU. The probe emits LLVM IR without
running target code, so it also works for cross-compilation.

Probe responses are cached under `<build-dir>/toolchain/target-<hash>.ll`. The key
includes the resolved Clang executable path, its size and modification time, the
compilation flags, and the probe format version. Warm builds read the cached response
without launching Clang for the probe. Missing or malformed entries are regenerated,
and writes are atomic. Runtime cache entries include the same key. These caches are
local build artifacts; clean them when moving a build directory to another host.
`llvm-info` remains a separate tool-discovery report.

## Distribution

```
sbtn mmlcDistroAssembly            # create distribution directory
sbtn mmlcDistro                    # create distribution zip
```

## Memory tests

Run when changes touch memory management or ownership:
```
./tests/mem/run.sh all
```

All tests must pass both ASan and leaks checks.

## Benchmarks

Run after publishing:
```
make -C benchmark clean
make -C benchmark mml
```
