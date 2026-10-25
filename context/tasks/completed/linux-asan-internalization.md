# Fix Linux ASan assembly failure after internalization

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-07
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference:** None

## Execution Checklist

1. [x] **complete** — Reproduce the failure independently of MML on both Linux architectures;
   see [verification](#verification).
2. [x] **complete** — Reconcile the failing reproducer with passing Linux sanitizer
   evidence; current ordering and 92 focused tests pass on each Linux architecture.
3. [x] **complete** — Confirm existing implementation and regression coverage are sufficient;
   no additional compiler repair is required.
4. [x] **complete** — Verify both Linux architectures and focused macOS sanitizer execution;
   Linux full suites, smokes, benchmarks, and memory harnesses pass. Signoff is granted.

## Problem

Recorded sanitizer-enabled builds on Linux with LLVM/Clang 20.1.8 fail during
assembly/linking with `Linkage must be 'comdat'` for an `asan_globals` section. The same failure occurs in a
standalone C program processed by `default<O0>,internalize,globaldce`; omitting the last two
passes allows the program to link and run under ASan/LSan.

The failing executable pipeline recorded on 2026-10-07 in
[LlvmToolchain.scala](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
links sanitized runtime bitcode before optimization, appends `internalize,globaldce` while
preserving `main` as public, emits assembly with `llc`, and assembles/links with Clang.
The [current-baseline investigation](#current-baseline-reconciliation-2026-10-23) isolates
the trigger to internalization of pre-instrumented globals on LLVM 20.1.8. The completed
[generated-IR ASan task](asan-generated-ir.md#verification) supplies the deferred-instrumentation
ordering that passes the standalone control. Compatibility with other LLVM versions is not
established by the standalone investigation. Broader Linux acceptance passes on both
architectures; see [verification](#broader-verification-2026-10-23).

The recorded failure blocked Linux native sanitizer verification of
[A* finding 7: conditional field-alias lifetime](astar-animation-correctness-bugs.md#7-conditional-field-aliases-can-outlive-their-temporary-owner).
Finding 7 completion is approved using host verification and independent review. Its Linux
sanitizer follow-up is satisfied by this task's full-suite and memory-harness execution.

## Outcome

Sanitizer-enabled Linux executables assemble, link, and run with memory checks intact.
The supported standalone pipeline and affected native regressions pass on ARM64 Linux and
emulated amd64 Linux. macOS host verification remains passing, and the supported toolchain
configuration is documented. Linux smoke, benchmark, and full memory-harness results are
recorded separately, distinguishing build failures from runtime ownership failures.

## Scope

- In scope: LLVM sanitizer metadata and the executable optimization/assembly pipeline;
  relevant toolchain compatibility; focused regressions and both Linux builder configurations;
  Linux smoke, benchmark, and full memory-harness verification.
- Out of scope: conditional borrow semantics, M2 deployment, disabling sanitizer coverage,
  hardcoded CPUs, and unrelated compiler or runtime changes.

## Plan (Approval Gate)

- [x] Reconcile the historical assembly reproducer with the passing Linux suites and pipeline
  experiments in [generated-IR ASan verification](asan-generated-ir.md#verification). The current
  ordering and both full memory harnesses pass.
- [x] Test `internalize` and `globaldce` separately and inspect the sanitizer globals and
  section-group metadata before and after each pass.
- [x] Identify the implicated boundary: pass ordering. The existing deferred-instrumentation
  implementation resolves the reproduced assembly failure; no additional repair is proposed.
- [x] Confirm existing regression coverage and implementation satisfy the task without
  weakening sanitizer checks or hardcoding CPUs; no additional repair is needed.
- [x] Rerun the affected native suites and full tests in both Linux containers sequentially,
  plus applicable host verification.
- [x] Run Linux smokes, benchmarks, and the full memory harness on both architectures;
  classify remaining compiler failures separately from runtime ownership failures.

Approval: reconciliation, verification, final signoff, and local closeout commit authorized.
No additional compiler implementation is required; push is not authorized.

Approved verification stage:

1. On each Linux builder sequentially, run `sbtn testFull` and `./tests/smoke/run.sh all`.
2. After successful smoke execution, run `sbtn mmlcPublishLocal` inside that builder, then
   `make -C benchmark clean`, `make -C benchmark mml`, and `./tests/mem/run.sh all`.
   Clean shared benchmark outputs before switching architectures. Benchmark builds establish
   build success, not performance.
3. Rerun the focused sanitizer/native suites on the macOS host. If verification requires a
   compiler repair, stop for a bounded implementation plan and run applicable host gates
   after the approved repair.
4. Record assembly, compiler, and runtime failures separately; review results before signoff.

## Verification

### Standalone reproduction and native suites, 2026-10-07

Evidence: 2026-10-07. Both existing Linux builders use Ubuntu LLVM/Clang 20.1.8 and
GraalVM Java 21.0.3. Tests execute inside the containers against the conditional field-alias
repair sources.

| Container and command | Execution | Result |
| --- | --- | --- |
| `docker exec -w /workspace mml-linux-arm64 sbtn test` | Native ARM64 Linux, `aarch64-unknown-linux-gnu` | 991 library tests pass, 29 fail, 37 existing ignores; all 9 CLI tests pass. |
| `docker exec -w /workspace mml-linux-amd64 sbtn test` | Emulated x86-64 Linux through Rosetta, `x86_64-pc-linux-gnu` | 991 library tests pass, 29 fail, 37 existing ignores; all 9 CLI tests pass. |

On each architecture, all 64 `FieldQualifierOwnershipTests` pass. `CallExitBlockTests`
passes 25 checks and fails its eight native checks during assembly/linking, before execution.
The other failures are in `RuntimeTests` and `StringReturnTests`. The shared diagnostic is
`Linkage must be 'comdat'` for an `asan_globals` section emitted by `llc`.

This C program reproduces the failure on both architectures without MML compilation:

```c
int value = 7;
int main(void) { return value - 7; }
```

Save the embedded source as `probe.c` in an isolated build directory inside either container:

```sh
clang -O0 -fPIC -fsanitize=address -emit-llvm -c probe.c -o probe.bc
opt '--passes=default<O0>,internalize,globaldce' --internalize-public-api-list=main probe.bc -o probe_opt.bc
llc -relocation-model=pic probe_opt.bc -o probe.s
clang -fsanitize=address probe.s -o probe
```

The final command fails on both architectures at this assembly directive:

```asm
.section asan_globals,"awoG",@progbits,value,value,unique,1
```

Control: replace the `opt` command with
`opt '--passes=default<O0>' probe.bc -o probe_opt.bc`, then repeat `llc` and `clang`.
Both controls link and exit zero with
`ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1 ./probe`.
The failure reproduces independently of MML ownership analysis. The control removes both
passes together; it does not identify the responsible pass or establish a verified repair.

macOS ARM64 with Homebrew LLVM/Clang 23.1.1 passes the conditional field-alias repair's
host gates, including native ASan/LSan execution at `-O0` and `-O3`. This differs in both
platform and LLVM version from the Linux builders; it does not prove that an LLVM upgrade
alone resolves the Linux failure.

### Linux smoke, benchmark, and memory-harness evidence

The [Linux verification follow-up](unify-lambdas.md#linux-verification-follow-up)
records ARM64 Ubuntu 25.10 with LLVM/Clang 20.1.8: ordinary smoke programs and benchmark
builds pass, except for `partial-fac1` exiting 139. The memory harness reports 0/34, all
compilation failures before sanitizer execution. An `arrays-mem.mml` diagnostic shows
`asan_globals` assembly rejected with `Linkage must be 'comdat'`. Other harness failures
were not individually classified. A standalone C probe reports the intended use-after-free,
confirming that ASan itself executes in the container. These are historical results, not
verification of the current compiler.

### Passing sanitizer evidence, 2026-10-22

The completed [generated-IR ASan task](asan-generated-ir.md#verification) records passing
full suites on native ARM64 and emulated x86-64 Linux with LLVM/Clang 20.1.8: 1,362 library
tests and nine CLI tests, with 36 existing ignores. Its pipeline experiments reproduce the
assembly failure with pre-instrumented globals; deferred runtime instrumentation followed
by post-internalization ASan builds and detects the intended fault at O0/O3 on both targets.
Those results support the changed pass ordering, but do not establish acceptance of this
task's standalone reproducer or the full Linux memory harness. The current-baseline comparison
and full Linux memory-harness acceptance are recorded below.

### Current-baseline reconciliation, 2026-10-23

Source baseline: `3ed00ec5a81cd3883fc467f8b47bc1f66f7f02e6`. Both builders use Ubuntu
Clang 20.1.8. `mml-linux-arm64` executes natively as `aarch64-unknown-linux-gnu`;
`mml-linux-amd64` executes through Docker emulation as `x86_64-pc-linux-gnu`.

Using the embedded `probe.c` above, compile pre-instrumented bitcode with the recorded
Clang command. For each pass list below, run `opt` with
`--internalize-public-api-list=main`, then the recorded `llc` and final Clang commands.
Run successfully linked binaries with `ASAN_OPTIONS=detect_leaks=1:halt_on_error=1`.

| Pass list on pre-instrumented bitcode | ARM64 Linux | Emulated amd64 Linux |
| --- | --- | --- |
| `default<O0>` | Links, exits 0 | Links, exits 0 |
| `default<O0>,internalize` | Assembly failure | Assembly failure |
| `default<O0>,globaldce` | Links, exits 0 | Links, exits 0 |
| `default<O0>,internalize,globaldce` | Assembly failure | Assembly failure |

Both failures report `Linkage must be 'comdat'` on the `asan_globals` section directive.
Disassembly with `llvm-dis` shows that `internalize` changes `$value = comdat any` to
`$value = comdat nodeduplicate`, and changes `@value` to internal linkage. The
`@__asan_global_value` record retains `section "asan_globals", comdat($value)`.
`globaldce` alone preserves the original group and links successfully. This isolates the
trigger to internalization of pre-instrumented globals on the tested LLVM version;
it does not establish behavior on other LLVM versions.

The current compiler defers runtime instrumentation in `compileRuntimeBitcode` and runs
`asan` after `internalize,globaldce` in `runOptimization` in
[LlvmToolchain.scala](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala).
The equivalent standalone pipeline is reproducible with:

```sh
for level in 0 3; do
  clang -O"$level" -fPIC -fsanitize=address -Xclang -disable-llvm-passes \
    -emit-llvm -c probe.c -o deferred.bc
  opt "--passes=default<O$level>,internalize,globaldce,asan" \
    --internalize-public-api-list=main --asan-use-after-scope \
    deferred.bc -o deferred-opt.bc
  llc -relocation-model=pic deferred-opt.bc -o deferred.s
  clang -fsanitize=address deferred.s -o deferred-bin
  ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 ./deferred-bin
done
```

Both optimization levels link and exit zero on both architectures. The historical command
remains a failing control; acceptance concerns the supported deferred-instrumentation pipeline,
not making internalization of already-instrumented bitcode succeed. No additional compiler
repair is indicated by this comparison.

Current compiler verification ran sequentially with this command, substituting each builder:

```sh
docker exec -w /workspace <builder> sbtn 'testOnly mml.mmlclib.codegen.AsanInstrumentationTests mml.mmlclib.codegen.CallExitBlockTests mml.mmlclib.codegen.RuntimeTests mml.mmlclib.codegen.StringReturnTests'
```

| Builder | Result |
| --- | --- |
| `mml-linux-arm64` | 92 passed, 0 failed, 0 errors; 48 seconds |
| `mml-linux-amd64` | 92 passed, 0 failed, 0 errors; 171 seconds |

No compiler warnings or ignored tests were reported. Both use sbt 2.0.10 with Oracle Java
21.0.3. The suite includes executable/library generated-access negative controls at O0/O3,
stack-use-after-scope controls, runtime-cache controls, and the three historically failing
native suites. The negative controls require specific ASan diagnostics, so passing means
instrumentation detects the deliberate fixture faults. Full suites, Linux smokes, benchmark
builds, the full memory harness, and fresh host verification were not run in this stage.

### Broader verification, 2026-10-23

Source baseline remains `3ed00ec5a81cd3883fc467f8b47bc1f66f7f02e6`; no compiler changes.
The initial ARM64 `sbtn testFull` passes 1,414 library tests and nine CLI tests, and
`./tests/smoke/run.sh all` passes 8/8. Publishing succeeds. The benchmark launcher then fails
before compilation because the container's Java 21 rejects
`--sun-misc-unsafe-memory-access=allow` from `packaging/mmlc`.

The Dockerfile already specifies `25.4.4+1-graal`. Container repair uses its existing SDKMAN:

```sh
docker exec <builder> bash -c 'source /root/.sdkman/bin/sdkman-init.sh && sdk install java 25.4.4+1-graal'
docker exec <builder> bash -c 'source /root/.sdkman/bin/sdkman-init.sh && sdk default java 25.4.4+1-graal'
docker exec -w /workspace <builder> sbtn shutdown
docker exec <builder> java -version
```

Both containers report Java `25.0.4.1.1`; their sbt servers were stopped before restarting
verification. ARM64's published `mmlc --help` succeeds. No Dockerfile or launcher changes
are required. The initial benchmark failure was a stale-container runtime mismatch.

ARM64 verification on Java 25:

| Command inside `mml-linux-arm64`, working directory `/workspace` | Result |
| --- | --- |
| `sbtn testFull` | 1,414 library tests and nine CLI tests pass; no ignores or warnings reported |
| `./tests/smoke/run.sh all` | 8/8 pass |
| `sbtn mmlcPublishLocal` | Pass; installed in the container |
| `make -C benchmark clean` then `make -C benchmark mml` | All 13 MML benchmark programs build |
| `./tests/mem/run.sh all` | 52/52 ASan+LSan cases pass, 43 seconds |

On amd64, the first Java 25 full-suite run stalls after its compiler subprocesses exit,
leaving defunct children and an unresponsive sbt JVM. Graceful termination does not stop the
server; forced termination is required. The waiting client is also stopped before the actual
retry starts. One fresh-server retry of `sbtn testFull` passes all 1,414 library tests and
nine CLI tests in 200 seconds, without reported warnings or ignores. The stall's root cause
is not established. The remaining amd64 checks pass:

| Command inside `mml-linux-amd64`, working directory `/workspace` | Result |
| --- | --- |
| `./tests/smoke/run.sh all` | 8/8 pass |
| `sbtn mmlcPublishLocal` | Pass; installed in the container |
| `make -C benchmark clean` then `make -C benchmark mml` | All 13 MML benchmark programs build |
| `./tests/mem/run.sh all` | 52/52 ASan+LSan cases pass, 293 seconds |

No performance measurements are claimed.

Host verification uses macOS arm64, Homebrew LLVM/Clang 23.1.1, and GraalVM CE Java
25.0.4.1. The four-suite `sbtn testOnly` command recorded above passes 92/92 tests in
35 seconds, with no reported warnings or ignores. Initial sandbox execution cannot open the
sbt cache lock; the authorized rerun with cache access passes. No host test failure occurs.
The host full suite and memory harness are not repeated because no compiler code changes;
the approved host scope is the focused sanitizer/native suite.

Focused tracking review, local links, and `git diff --check` pass. Verification and container
runtime repair introduce no product-source changes, so no changelog entry is required.

## Risks / Notes

- The historical failure occurs on native ARM64 Linux too; amd64 emulation alone cannot
  explain it.
- The ASan/LSan controls pass on both Linux architectures. The failure is specific to the
  tested pipeline, not evidence that sanitizers are generally unavailable on Linux.
- The failed native tests stop before executable execution; passing semantic or LLVM
  verification checks does not establish runtime memory safety.
- Use the [Linux builder workflow](../../../packaging/docker/Readme.md). Run builds sequentially
  and distinguish emulated amd64 execution from native ARM64 execution.

## Signoff

- Workstream signoff: complete, 2026-10-23; passing verification satisfies the authorized condition.
- Tracked item completion: complete.
- Local closeout commit: authorized; push not authorized.

## Task Working Memory

- **Outcome:** current sanitizer compilation and execution pass on native ARM64 Linux and
  emulated amd64 Linux. No compiler repair or new regression tests are required.
- **Verification:** each Linux architecture passes 1,414 library tests, nine CLI tests,
  eight smokes, 13 benchmark builds, and 52 ASan+LSan fixtures. The focused host suite passes
  92 tests. Results and the recovered amd64 test-server stall are recorded above.
- **Environment:** both existing containers use the Dockerfile's GraalVM Java 25 version.
  Their compiler launchers and container-local published artifacts work. LLVM remains 20.1.8.
- **Completion:** all scoped work is complete and signed off; local commit is authorized.
  No push is authorized. Benchmark verification establishes builds, not performance.
