# B29 — Initialize StringArray slots safely

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG
- **Priority:** HIGH
- **Created:** 2026-11-03
- **Target Branch:** dev/string-array-initialization
- **External Reference:** None.

## Execution Checklist

1. [x] **complete** — Add failing runtime and compiled-MML regressions for unfilled arrays.
2. [x] **complete** — Initialize every allocated StringArray slot to a valid empty String.
3. [x] **complete** — Compiler and memory checks, QA, and review pass; signoff approved.

## Problem

Allocating StringArray slots without initializing them leaves invalid values for destruction.
`__free_StringArray` destroys every slot, including slots that have never been assigned.
The reproduction and controls are recorded below and in
[RuntimeTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/RuntimeTests.scala).

## Outcome

Every newly allocated slot holds a valid empty String. Arrays can be read, cloned, and
destroyed before all slots have been assigned, while assigned slots retain their owned strings.

## Scope

- Initialize slots in `ar_str_new` without changing public signatures, layout, or allocation
  failure handling.
- Add runtime and compiled-MML sanitizer regressions in
  [RuntimeTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/RuntimeTests.scala).
- Preserve nonpositive-size behavior and fully initialized array behavior.
- Document the StringArray initialization contract in the language reference.
- Replacing an already owned array element remains outside this initialization repair.

## Fix Plan

1. Add unfilled and partially filled array destruction regressions, with zero-length,
   negative-size, and fully filled controls. Check empty-slot reads and cloning as well as
   destruction. Use the existing runtime and compiled-program sanitizer helpers.
2. Capture the regressions failing against the unchanged runtime, then initialize allocated
   slots to empty String values before returning the array.
3. Run focused regressions, formatting, lint, smoke, the full suite, publishing, benchmark
   compilation, and the memory harness. Complete QA enforcement and independent code review.

Implementation approval: granted for this plan and its verification on a feature branch.

## Verification

The minimal reproduction at commit `4cd89e56cbb47e8a52b3103235492b6b7eca66f0` includes
`mml_runtime.c` and destroys an array before assigning either slot:

```c
int main(void) {
    StringArray a = ar_str_new(2);
    __free_StringArray(a);
    return 0;
}
```

It fails under AddressSanitizer at O0 on macOS arm64 with Clang/LLVM 23.1.1. Compile from
the repository root with
`clang -std=c17 -g -O0 -fsanitize=address -fno-omit-frame-pointer -I modules/mmlc-lib/src/main/resources fixture.c -o fixture`.

- At base commit `e05e20cc`, source tracing confirms uninitialized allocation followed by
  destruction of every slot.
- Existing `tests/mem/arrays-mem.mml` fills every slot before destruction.
- Before the runtime change, `sbtn 'testOnly mml.mmlclib.codegen.RuntimeTests -- *StringArray*'`
  ran all five new regressions; all failed. Both C destruction fixtures reached an invalid
  free detected by AddressSanitizer, and the empty-slot read assertion failed. Compiled MML
  at O0 and O3 aborted in StringArray destruction. Every fixture exited 134 instead of 0.
- `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.codegen.RuntimeTests'` passes all
  14 runtime tests. The new C fixtures run with AddressSanitizer and UndefinedBehaviorSanitizer
  at O2; the compiled-MML fixture runs at O0/O3 with AddressSanitizer and leak detection.
  Native execution uses macOS arm64 and Clang/LLVM 23.1.1.
- `./tests/smoke/run.sh all` passes all eight fixtures. An initial sandboxed invocation
  could not access sbt's local lock and executed no smoke programs; the retry with the
  required local access passed.
- `sbtn testFull` passes all 2,520 library and 9 CLI tests. An initial invocation failed
  before tests because the sandbox denied sbt's local lock; the retry with local access passed.
- `sbtn mmlcPublishLocal` succeeds.
- `make -C benchmark clean` and `make -C benchmark mml` succeed, producing all 37 MML
  benchmark binaries. These are compilation checks, not performance measurements.
- `./tests/mem/run.sh all` passes all 52 fixtures with AddressSanitizer and LeakSanitizer.
- QA enforcement finds no rule violations in the source, tests, or directly affected
  language-reference change. Focused tracking review and local link checks pass.
- Fresh independent primary review and all four dedicated checks (code style/QA, test quality,
  readability, and code documentation) complete with no actionable findings. No candidates
  require claim verification. The reviewer independently checks `git diff --check` and executes
  a C fixture that reads a fresh empty slot, converts it with `to_cstr`, checks its terminator,
  and releases both allocations. Compilation and execution pass at O2 with ASan/UBSan.
  The broader test and harness results above are supplied evidence, not reviewer reruns.
- There is no ABI change requiring Linux container checks. Native execution evidence covers
  macOS arm64 only; no performance measurements were taken.

## Risks / Notes

Initialization adds work proportional to the number of slots. There is no ABI change.
Sanitizer passes establish only the paths and targets executed.

## Signoff

- Workstream signoff: approved for B29 implementation and verification.
- Tracked item completion: approved.
- Commit authorization: granted for the repair and its bookkeeping.
- Delivery authorization: local squash ship to the Git Town parent, `main`.

## Task Working Memory

`ar_str_new` initializes each allocated slot to an empty String before returning. All five
regressions fail against the unchanged runtime and pass with initialization; all 14 runtime
tests pass. Formatting, lint, smoke, full-suite, publishing, benchmark compilation, memory
checks, QA enforcement, focused tracking review, and independent review pass. Signoff is approved.
No open findings or implementation steps remain.
