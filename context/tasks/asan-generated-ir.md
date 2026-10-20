# Instrument MML-generated IR for AddressSanitizer

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** CORRECTNESS
- **Priority:** HIGH
- **Created:** 2026-10-20
- **Target Branch:** unassigned
- **External Reference (optional):** none

## Execution Checklist

1. [ ] **planned** — Establish a failing generated-code memory-access negative control.
2. [ ] **planned** — Design and implement ASan instrumentation across the LLVM pipeline.
3. [ ] **planned** — Verify instrumentation, memory fixtures, and target-specific toolchains.
4. [ ] **planned** — Correct sanitizer coverage claims in tests and documentation; review and sign off.

## Problem

`-s` passes `-fsanitize=address` to Clang for the C runtime and final link, but the
MML-generated IR passes through `llvm-link`, `opt`, and `llc` without ASan attributes or
an ASan instrumentation pass. Linking the runtime bitcode also adds a
`nosanitize_address` module flag. As a result, the normal memory harness detects leaks,
invalid frees, and runtime-side faults, but does not establish use-after-free or
out-of-bounds coverage within generated closure entries, PAP entries, or destructors.

A generated-destructor use-after-free negative control escaped the ordinary `-s`
pipeline. Applying ASan to generated IR before native compilation detected the same
negative control.

## Outcome

Sanitized compilation instruments MML-generated code as well as the C runtime, and a
generated-code memory-access negative control fails under `-s`. Tests and reports state
precisely what sanitizer coverage they establish.

## Scope

- In scope: sanitizer attributes, LLVM pass placement, runtime bitcode interaction,
  target/toolchain support, generated-code negative controls, and coverage wording in
  [tests/mem/run.sh](../../tests/mem/run.sh) and affected tests/docs.
- Out of scope: unrelated closure ownership changes, the separate
  [Linux ASan assembly failure](linux-asan-internalization.md), and
  [runtime cache separation](sanitizer-runtime-cache.md).

## Plan (Approval Gate)

- [ ] Reproduce the negative control with the current toolchain and inspect the linked
  module's sanitizer attributes and flags.
- [ ] Compare bounded instrumentation points in the `llvm-link` → `opt` → `llc`
  pipeline, including the runtime bitcode's `nosanitize_address` flag.
- [ ] Implement the chosen path and retain a generated-code UAF or OOB negative control.
- [ ] Run compiler, smoke, native sanitizer, and relevant Linux checks; update coverage claims.

Approval: task recording authorized; implementation plan pending.

## Verification

The branch review reported 52/52 memory fixtures and roughly 30 adversarial probes clean
at `-O0` and `-O3` after applying ASan to generated IR. Six fixtures whose `main` takes
arguments ran with a hand-written main stub, so those runs did not exercise the usual
entry point. The review's injected generated-destructor use-after-free was detected only
by the fully instrumented path. These are reported review results, not verification run
for this task. The review did not run smoke, benchmarks, Linux/x86-64,
`scalafmtCheck`, or `scalafix`.

Acceptance requires a checked-in negative control, proof that MML-generated functions
receive ASan instrumentation, and explicit results for the normal memory harness and
the affected `verify(sanitized = true)` codegen tests.

## Risks / Notes

[LlvmToolchain.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
currently adds Clang flags only. The separate Linux assembly task already concerns
sanitizer/internalization behavior; coordinate target verification without treating that
existing build blocker as proof of generated-code instrumentation.

## Signoff

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: task-definition commit only; implementation commits not granted

## Task Working Memory

- **Evidence:** the generated-destructor negative control escapes the shipped `-s`
  pipeline and is detected after explicit instrumentation of generated IR. The normal
  harness's success is limited to the coverage described above.
- **Next action:** prepare a bounded instrumentation plan and a durable negative control
  for approval before changing compiler code.
