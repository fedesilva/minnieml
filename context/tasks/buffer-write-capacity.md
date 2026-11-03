# B28 — Bound writes to buffer capacity

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG
- **Priority:** HIGH
- **Created:** 2026-11-03
- **Target Branch:** dev/buffer-write-capacity
- **External Reference:** None.

## Execution Checklist

1. [x] **complete** — Add failing runtime regressions for oversized and boundary writes.
2. [x] **complete** — Route buffered output through one capacity-bounded byte writer.
3. [x] **complete** — Runtime checks, compiler gates, and review pass; signoff approved.

## Problem

A single flush empties the pending output but cannot make a payload larger than the buffer
fit. Copying the entire payload after that flush exceeds the allocation. String and numeric
writers need bounded copies; newline variants also need room for their final byte.

[Bug notes B28](bug-notes.md#notes) records sanitizer failures for a five-byte string written
to a four-byte buffer and numeric/newline writes with capacity one in the
[runtime](../../modules/mmlc-lib/src/main/resources/mml_runtime.c).

## Outcome

Every buffered writer accepts valid payloads larger than the buffer while keeping stored
length within capacity and preserving byte order. Buffer allocation size remains fixed.

## Scope

- String, integer, float, newline, and byte writers in `mml_runtime.c`.
- Runtime sanitizer regressions and a compiled-MML integration regression in
  [RuntimeTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/RuntimeTests.scala).
- Preserve public signatures, buffer layout, numeric formatting, and byte truncation semantics.
- B29 StringArray initialization and B30 destruction-time flushing remain separate repairs.
  Tests explicitly flush local buffers. OS write-error and short-write handling are outside
  this capacity repair; the implementation continues to use the existing `flush` operation.

## Fix Plan

1. **Pin observable behavior.** Add literal C fixtures using the existing sanitizer runner
   pattern. Check exact output for payloads below, equal to, and above capacity; partially
   filled buffers; repeated writes; capacity one; empty strings; and newline boundaries.
   Exercise every writer, including integer limits, float formatting, and raw byte output.
   Preserve null-buffer and null-string behavior. Run the focused tests before the fix and
   record the failing controls.
2. **Centralize capacity handling.** Add one private byte-writing helper. Copy only the
   minimum of remaining payload length and available capacity. Flush a full buffer before
   continuing with more bytes. Keep `length <= capacity` throughout, using subtraction for
   available space instead of potentially overflowing length additions. Leave the final
   buffered bytes for an explicit flush or subsequent write. Document the mutation boundary
   as buffered I/O state.
3. **Use the shared path.** Send string bytes and formatted numeric bytes through the helper.
   Send newlines and single-byte writes through that same path. Keep existing formatters and
   public entry points; remove duplicated flush-and-copy capacity logic.
4. **Verify integration and regressions.** Run the C fixtures with AddressSanitizer and
   UndefinedBehaviorSanitizer, and a compiled-MML program with AddressSanitizer and explicit
   flushing. Verify output ordering across automatic flushes. Run formatting, lint, smoke,
   the full suite, publishing, benchmark compilation, and the memory harness in the order
   required by [coding rules](../coding-rules.md). Complete QA enforcement and independent
   code review. No ABI change is planned.

Implementation approval: granted for this fix plan and its verification.

## Verification

- Existing reproduction and controls are embedded in
  [Bug notes](bug-notes.md#reproduction-evidence-for-b28b33); they are historical evidence.
- On 2026-11-03, the linked B28 C reproduction failed on macOS arm64 with Clang 23.1.1,
  compiled using `-std=c17 -O2 -g -fsanitize=address,undefined -fno-sanitize-recover=all`.
  AddressSanitizer reported `heap-buffer-overflow`, `WRITE of size 5`, immediately past a
  four-byte allocation. The process aborted; symbolizer startup warnings left native frames
  unsymbolized.
- Before the runtime change, `sbtn 'testOnly mml.mmlclib.codegen.RuntimeTests -- *buffer*'`
  ran both added regressions. Both failed with AddressSanitizer heap-buffer-overflow reports:
  the C fixture wrote four bytes into capacity one, and compiled MML wrote five bytes into
  capacity one. Both processes exited 134. These failures establish that the tests detect B28.
- `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.codegen.RuntimeTests'` passes:
  all nine runtime tests, including the two added regressions. The C fixture checks exact
  binary output under AddressSanitizer/UndefinedBehaviorSanitizer for capacities 1, 4, 5,
  11, 12, and 32. The compiled-MML fixture checks output under AddressSanitizer with explicit
  flushing at capacities 1 and 4.
- `./tests/smoke/run.sh all` passes all eight fixtures. The first invocation could not access
  sbt's local lock file under sandbox restrictions, so no smoke program executed; the retry
  with the required local access passed.
- `sbtn testFull` passes all 2,515 library tests and 9 CLI tests.
- `sbtn mmlcPublishLocal` succeeds.
- `make -C benchmark clean` and `make -C benchmark mml` succeed, producing all 37 MML
  benchmark binaries. This establishes compilation, not execution-time performance.
- `./tests/mem/run.sh all` passes all 52 fixtures with AddressSanitizer and LeakSanitizer.
- QA enforcement found no rule violations in the source/test diff.
- Independent primary review and all four dedicated checks (style/QA, test quality,
  readability, and code documentation) completed with no actionable findings. Five
  documentation candidates were independently examined by separate fresh verifiers;
  all were rejected. The reviewer ran `git diff --check`; execution results above were
  supplied evidence, not independent reruns.
- Native execution covers macOS arm64. Sanitizers establish checks on executed paths;
  benchmark compilation supplies no performance measurement. No ABI changes require
  additional Linux container verification for this repair.

## Risks / Notes

Chunking uses the existing allocation and flush operation, with work proportional to the
payload and the number of buffer fills. Small capacities cause more flushes. Tests must check
emitted bytes rather than depend on a particular number of flush calls.

## Signoff

- Workstream signoff: approved for B28 implementation and verification.
- Tracked item completion: approved.
- Commit authorization: granted for the repair and its bookkeeping.
- Delivery authorization: local squash ship to the Git Town parent, `main`.

## Task Working Memory

The repair uses one fixed-capacity byte writer shared by all buffered output functions.
Both added regressions fail against the unchanged runtime with sanitizer diagnostics.
The shared writer and caller changes pass all required local compiler checks, QA enforcement,
and independent review. Signoff is approved. No open findings or implementation steps remain.
