# Instrument MML-generated IR for AddressSanitizer

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** CORRECTNESS
- **Priority:** HIGH
- **Created:** 2026-10-20
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference (optional):** none

## Execution Checklist

1. [x] **complete** — Add generated-destructor negative controls with specific ASan diagnostics.
2. [x] **complete** — Implement shared function attributes and one ASan pass after optimization.
3. [x] **complete** — Verify instrumentation, memory fixtures, and target-specific toolchains.
4. [x] **complete** — Correct coverage documentation, complete independent review, and obtain signoff.

## Problem

Linking the sanitizer runtime detects allocation/free errors and leaks, but does not add
checks to generated LLVM memory accesses. Generated functions need the `sanitize_address`
attribute and an ASan pass. Linking runtime bitcode that already carries the
`nosanitize_address` module flag causes that pass to skip the combined module.

The negative control adds `load volatile i8` immediately after the generated destructor's
`mml_free_raw` call. The volatile read remains observable through optimization. The
uninstrumented pipeline misses it; correctly instrumented code reports `heap-use-after-free`.
This injected fault tests sanitizer coverage, not a source-language ownership defect.

## Outcome

Sanitized compilation instruments MML-generated code as well as the C runtime, and a
generated-code memory-access negative control fails under `-s`. Tests and reports state
precisely what sanitizer coverage they establish.

## Scope

- In scope: sanitizer attributes, LLVM pass placement, runtime bitcode interaction,
  target/toolchain support, generated-code negative controls, and coverage wording in
  [tests/mem/run.sh](../../../tests/mem/run.sh) and affected tests/docs.
- Out of scope: unrelated closure ownership changes, the separate
  [Linux ASan assembly failure](linux-asan-internalization.md), and
  [runtime cache separation](../sanitizer-runtime-cache.md).

## Plan (Approval Gate)

Approval: implementation, regression tests, affected documentation, and task bookkeeping approved.

1. Emit `sanitize_address` in both generated function attribute groups when `-s` is enabled.
2. Defer LLVM passes for sanitized runtime bitcode with `-Xclang -disable-llvm-passes`.
   Include those flags in its cache identity. Run ASan once after combined optimization
   and executable internalization, with `--asan-use-after-scope` enabled to retain Clang defaults.
   Library objects receive the same generated-code pass;
   their separate runtime object retains Clang instrumentation.
3. Add checked-in generated-code negative controls requiring the ASan diagnostic, valid
   controls, executable/library coverage, and checks of affected native-test helpers.
4. Update sanitizer coverage documentation and run host compiler gates and Linux checks.
   The separate Linux assembly task remains open; test the implicated pass ordering here
   without claiming completion of its wider verification scope.

## Verification

The checked-in [AsanInstrumentationTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/AsanInstrumentationTests.scala)
compiles a move closure through the frontend and emitter, injects the read after its generated
environment release, and invokes `LlvmToolchain.compile`. Executable and library cases run
at O0/O3. Library cases link the emitted module/runtime objects with a C driver. Valid controls
must exit zero; negative controls must exit one with the ASan use-after-free diagnostic.
Further checks cover generated function attributes, sanitized/ordinary/sanitized cache reuse,
and a seeded pre-instrumented runtime artifact using the legacy cache key. C fixtures with
explicit local lifetimes require stack-use-after-scope diagnostics at O0/O3 through the
production pipeline, preserving Clang's default coverage when instrumentation is deferred.

Host verification on macOS arm64 with LLVM/Clang 23.1.1:

| Command | Result |
| --- | --- |
| `sbtn 'scalafmtAll;scalafixAll;testFull'` | 1,362 library tests and nine CLI tests pass; 36 existing ignores; no compiler warnings |
| `sbtn 'scalafmtAll;scalafixAll;testOnly mml.mmlclib.codegen.LocalCallableCodegenTests mml.mmlclib.codegen.OwnedClosureTailRecursionTests'` | 112 tests pass after final helper style cleanup |
| `./tests/smoke/run.sh all` | 8/8 pass |
| `sbtn mmlcPublishLocal` | Compiler installed successfully after smoke verification |
| `make -C benchmark clean` then `make -C benchmark mml` | All 13 MML programs build; no performance measurements |
| `./tests/mem/run.sh all` | 52/52 ASan+LSan fixtures pass in 74 seconds |

Linux builders use LLVM/Clang 20.1.8 and Oracle GraalVM Java 21.0.3. ARM64 execution is
native; x86-64 execution uses Docker emulation on the ARM64 host.

| Command | Result |
| --- | --- |
| `docker exec -w /workspace mml-linux-arm64 sbtn testFull` | 1,362 library tests and nine CLI tests pass; 36 existing ignores; no compiler warnings |
| `docker exec -w /workspace mml-linux-amd64 sbtn testFull` | 1,362 library tests and nine CLI tests pass; 36 existing ignores |
| `docker exec -w /workspace mml-linux-amd64 sbtn 'testOnly mml.mmlclib.codegen.AsanInstrumentationTests'` | 13/13 pass after final link-flag cleanup; no compiler warnings |

Verification corrections:

- LLVM 20 rejects `asan<use-after-scope>` (67 library failures in the initial ARM64 full
  run). Both LLVM 20 and 23 expose `--asan-use-after-scope`; all 13 dedicated controls
  pass on both Linux architectures with that spelling.
- The ARM64 full rerun passes 1,360 library tests and nine CLI tests, with two failures
  in the tail-recursion stress fixture. Its local-variable address measures ASan fake-stack
  rotation: an isolated million-call C probe reports a 1,048,512-byte local-address span
  with use-after-return checking enabled, zero with it disabled, and zero frame-address
  span in both cases. The fixture measures `__builtin_frame_address(0)` while retaining
  all sanitizer checks; the x86-64 full suite passes with that correction.
- Linux Clang warns that `-fno-omit-frame-pointer` is unused at the assembly-only final link.
  That command omits the option; C runtime compilation retains it. The x86-64 final
  controls pass without warnings.
- The x86-64 builder's stale sbt 1.12.1 server lacks `testFull` and cannot load the sbt 2
  build. A fresh sbt 2.0.10 server loads the repository and executes the checks above.

Final host gates, QA enforcement, and focused tracking review pass. A fresh independent
primary review and three separate fresh checks (style/QA, test quality, readability) report
no actionable findings. No candidates require claim verification; no review checks are waived.
The review is source-based and relies on the execution evidence above.

## Risks / Notes

- The separate Linux assembly task remains open. This implementation changes the implicated
  pass ordering; its Linux checks must distinguish that result from unrelated task completion.
- Test helpers that invoke Clang directly need sanitizer-eligible IR as well as the Clang
  flag. Local callable and tail-cleanup tests use the emitter's shared attributes and retain
  their own generated-access negative controls.
- Sanitizer passes, execution, and leak detection only cover the paths exercised by a test.

## Signoff

- Workstream signoff: complete, 2026-10-22
- Tracked item completion: complete
- Local implementation commit: authorized; push not authorized

## Task Working Memory

- **Delivered:** shared attributes, single-pass instrumentation, runtime-cache identity, and
  durable negative controls are implemented. Host gates and both Linux suites pass, including
  portable stack-scope options and the real-frame stress measurement.
- **Research evidence:** at O0 and O3, an injected volatile byte read after freeing an emitted
  closure environment exits zero through the uninstrumented baseline pipeline. Attributes alone
  and an ASan pass after linking pre-instrumented runtime bitcode also miss the fault. Deferring
  runtime instrumentation and applying ASan after optimization detects `heap-use-after-free`;
  the valid closure control exits zero. Host: macOS arm64, LLVM/Clang 23.1.1.
- **Linux evidence:** isolated global-plus-generated-read controls reproduce the
  `asan_globals` assembly failure on LLVM/Clang 20.1.8 in native ARM64 Linux and emulated
  x86-64 Linux. Deferred runtime instrumentation followed by post-internalization ASan
  builds and detects the intended fault at O0 and O3 on both targets. These are pipeline
  experiments, not full compiler verification.
- **Remaining scoped work:** none. Related Linux assembly and runtime-cache tasks retain their
  independent scope and status.
