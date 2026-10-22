# Record and enforce LLVM and Clang versions

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Created:** 2026-10-22
- **Target Branch:** unassigned
- **External Reference:** none

## Execution Checklist

1. [ ] **planned** — Record the LLVM and Clang versions used by the project in a versioned file.
2. [ ] **planned** — Check local versions when generating `llvm-info` and report mismatches.
3. [ ] **planned** — Verify comparisons, diagnostics, exit behavior, and cached-report handling.

## Problem

The compiler collects local LLVM tool versions in `llvm-info` without comparing them against
the versions used by the project. Unsupported older tools can reach later compilation stages.

## Outcome

A versioned file records the project's LLVM and Clang versions. When the compiler creates
`llvm-info`, it compares the selected local tools against those recorded versions:

- Equal: continue normally.
- Higher: emit a warning and continue.
- Lower: emit an error, stop compilation, and exit unsuccessfully.

Apply the policy to LLVM and Clang independently. Diagnostics identify the tool, detected
version, and recorded version.

## Scope

- In scope: version data, numeric version comparison, integration with `llvm-info` generation,
  warnings, errors, regression tests, and availability of the version file in installed builds.
- Out of scope: automatic tool installation, upgrades, and unrelated toolchain changes.

## Plan (Approval Gate)

- [ ] Confirm the baseline versions and choose the file format and packaged location.
- [ ] Reuse tool-version collection in
  [LlvmToolchain.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala).
- [ ] Integrate warnings and failures through
  [CodegenStage.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CodegenStage.scala).
- [ ] Define cached `llvm-info` invalidation when the baseline or selected tools change.
- [ ] Add focused tests and run the applicable compiler verification gates.

Approval: implementation plan pending.

## Verification

Implementation verification is pending. Cover equal, higher, and lower LLVM and Clang versions,
mixed outcomes, numeric ordering, version-output parsing, installed builds, and cached reports.
Confirm that an older tool prevents later compilation and causes an unsuccessful CLI exit.

## Risks / Notes

Confirm comparison granularity, vendor version formats, handling of unparseable versions,
and whether supported platforms require distinct baselines before implementation.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The required policy is equal/continue, higher/warn, and lower/error-and-stop.
Next action: confirm baseline versions and prepare a bounded implementation plan for the
version file and `llvm-info` integration.
