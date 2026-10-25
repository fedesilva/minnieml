# Scala Tools

How to build, test, and run the MML compiler during development.

## Build tool

The project uses sbt with a thin client (`sbtn`). Always use `sbtn`.

The root project `mml` aggregates `mmlclib` (compiler library) and `mmlc` (CLI).
Always run tasks from the root — aggregation handles subprojects automatically.

Do not run multiple `sbtn` commands in parallel. The thin client/server socket is
single-session in practice, and concurrent invocations collide or fail with connection
errors. If multiple sbt tasks are needed, batch them into one `sbtn "clean; compile; testFull"`
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

Metrics flags are independent and can be combined:

- `-m` / `--metrics`: compilation timings and stage totals.
- `-P` / `--parser-metrics`: parser counters and per-rule timings.

Both flags are available for default compilation, `run`, `ast`, and `ir`.

## SBT subprojects

The top level project aggregates the sub projects
*Prefer* running tests, and other tasks from the top level, not specific modules.

## Tests

Run the compiler smoke collection before publishing:

```sh
./tests/smoke/run.sh all
```

It checks the `.mml` copies in `tests/smoke/` using sequential `sbtn` invocations.
See [the smoke-test guide](../tests/smoke/Readme.md) for run modes and logs.
Do not run another `sbtn` session while the harness is active.

```
sbtn testFull                      # full test suite (all modules, uncached)
sbtn test                          # incremental tests
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
flags as the runtime. Builds use Clang's baseline CPU for the selected target unless
`--cpu` is supplied. Use `--cpu native` for host CPU optimization or an explicit CPU
such as `--cpu apple-m3`. Native selection requires the host and target architectures to
match; cross-architecture builds must use a baseline or an explicit CPU.

Local macOS builds leave the deployment version to Clang's host/toolchain defaults.
Use an explicit target, such as `--target arm64-apple-macosx15.0`, to select an older
macOS version. Explicit target triples are preserved. CPU and OS selection are independent:
`--cpu native` changes CPU optimization without choosing a deployment version. External
libraries must also support the destination CPU and OS. The probe emits LLVM IR without
running target code, so it also works for cross-compilation.

Probe responses are cached under `<build-dir>/toolchain/target-<hash>.ll`. The key
includes the resolved Clang executable path, its size and modification time, the
compilation flags, and the probe format version. Warm builds read the cached response
without launching Clang for the probe. Missing or malformed entries are regenerated,
and writes are atomic. Runtime cache entries include the same key. These caches are
local build artifacts; clean them when moving a build directory to another host.
`llvm-info` remains a separate tool-discovery report.

## Manual cloud verification

The **Compiler verification** workflow runs only through manual dispatch. It has no push,
pull-request, or scheduled trigger. Its native jobs use `ubuntu-24.04`, `ubuntu-24.04-arm`,
and `macos-26`, with JDK 25, the pinned sbt version, LLVM 20 on Linux and LLVM 23 on macOS.
CI uses independent `sbt --server --batch` processes with server autostart disabled.

GitHub requires the workflow to exist on the default branch before manual dispatch is
available. After registration, select **Actions → Compiler verification → Run workflow**
and choose the desired branch. See
[GitHub's manual-run requirements](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).
The registration stub on `main` performs no compiler verification; select a branch containing
the full workflow until that implementation reaches `main`.

Identify the intended branch and commit before dispatch. Commit and push require their own
authorization when not already granted; a run tests the remote commit, not uncommitted changes.
After explicit acceptance, dispatch the selected branch:

```sh
gh workflow run verify.yml --repo fedesilva/minnieml --ref BRANCH
gh run list --repo fedesilva/minnieml --workflow verify.yml --branch BRANCH --event workflow_dispatch
gh run view RUN_ID --repo fedesilva/minnieml --json headSha,status,conclusion,url,jobs
```

Verify `headSha` against the intended commit. Report the run link and actual result; an
in-progress job is not evidence that its current command is advancing. Recurring monitoring
requires an explicit request.

The run summary contains check status and durations, runner hardware and tool versions, and
MML benchmark measurements. Each platform uploads logs, raw Hyperfine JSON/CSV, retained
memory-fixture diagnostics, and test reports for 14 days, including failed runs. Compilation
time is separate from execution timing; timing differences are informational. Sieve workload
sizes and measurement limits are documented in [the benchmark guide](../benchmark/Readme.md).

Cloud runs are optional after required local Docker verification. Declining them does not
waive local checks or block task completion. Record unavailable or deferred hosted validation
explicitly; revisit failures from a later run as follow-up work.

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

`-s` instruments generated LLVM code and the C runtime. The compiler runs the ASan pass
after optimization and executable internalization. Leak detection is enabled by the harness's
`ASAN_OPTIONS`. The `AsanInstrumentationTests` suite checks this pipeline with deliberate
generated-code access errors and valid controls at O0/O3, including library output. A passing
memory fixture means no sanitizer error was detected on its executed paths.

## Benchmarks

Run after publishing:
```
make -C benchmark clean
make -C benchmark mml
```
