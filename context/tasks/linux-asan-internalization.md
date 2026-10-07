# Fix Linux ASan assembly failure after internalization

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-07
- **Target Branch:** unassigned
- **External Reference:** None

## Execution Checklist

1. [x] **complete** — Reproduce the failure independently of MML on both Linux architectures;
   see [verification](#verification).
2. [ ] **planned** — Isolate the failing pass interaction and determine a bounded repair.
3. [ ] **planned** — Approve and implement the repair with regression coverage.
4. [ ] **planned** — Verify sanitizer execution on both Linux architectures and the macOS host,
   then obtain signoff.

## Problem

Sanitizer-enabled executable builds on Linux with LLVM/Clang 20.1.8 fail during assembly/linking
with `Linkage must be 'comdat'` for an `asan_globals` section. The same failure occurs in a
standalone C program processed by `default<O0>,internalize,globaldce`; omitting the last two
passes allows the program to link and run under ASan/LSan.

The executable pipeline in
[LlvmToolchain.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
links sanitized runtime bitcode before optimization, appends `internalize,globaldce` while
preserving `main` as public, emits assembly with `llc`, and assembles/links with Clang.
The exact defective pass, interaction, and affected LLVM versions remain undetermined.

This blocks Linux native sanitizer verification of
[A* finding 7: conditional field-alias lifetime](astar-animation-correctness-bugs.md#7-conditional-field-aliases-can-outlive-their-temporary-owner).
Finding 7 completion is approved using host verification and independent review; its Linux
sanitizer verification is explicitly deferred to this task.

## Outcome

Sanitizer-enabled Linux executables assemble, link, and run with memory checks intact.
The standalone reproducer and affected native regressions pass on ARM64 Linux and emulated
amd64 Linux. macOS host verification remains passing, and the supported toolchain or pipeline
configuration is documented.

## Scope

- In scope: LLVM sanitizer metadata and the executable optimization/assembly pipeline;
  relevant toolchain compatibility; focused regressions and both Linux builder configurations.
- Out of scope: conditional borrow semantics, M2 deployment, disabling sanitizer coverage,
  and unrelated compiler or runtime changes.

## Plan (Approval Gate)

- [ ] Test `internalize` and `globaldce` separately and inspect the sanitizer globals and
  section-group metadata before and after each pass.
- [ ] Establish whether the repair belongs in pass ordering, symbol preservation, toolchain
  compatibility, or another directly implicated boundary; approve the resulting bounded plan.
- [ ] Add a regression and implement the approved repair without weakening sanitizer checks.
- [ ] Rerun the affected native suites and full tests in both Linux containers sequentially,
  plus applicable host and memory verification.

Approval: pending for implementation.

## Verification

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

## Risks / Notes

- Native ARM64 Linux also fails, so amd64 emulation alone cannot explain the error.
- The ASan/LSan controls pass on both Linux architectures. The failure is specific to the
  tested pipeline, not evidence that sanitizers are generally unavailable on Linux.
- The failed native tests stop before executable execution; passing semantic or LLVM
  verification checks does not establish runtime memory safety.
- Use the [Linux builder workflow](../../packaging/docker/Readme.md). Run builds sequentially
  and distinguish emulated amd64 execution from native ARM64 execution.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

A standalone C reproduction and passing control isolate the failure to the tested
sanitizer/internalization pipeline on LLVM 20.1.8. Both Linux full-suite runs have the same
29 native-test failures; their semantic qualifier tests pass. Next: isolate the pass
interaction and prepare a bounded repair plan. Implementation approval is pending.
