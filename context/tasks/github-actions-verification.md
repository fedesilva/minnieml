# GitHub Actions compiler verification and benchmarks

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-10-23
- **Target Branch:** unassigned
- **External Reference:** none

## Execution Checklist

1. [ ] **planned** — Approve runner coverage, workflow triggers, and benchmark execution policy.
2. [ ] **planned** — Implement cloud compilation, test suites, smoke tests, and benchmark jobs.
3. [ ] **planned** — Verify actual GitHub-hosted runs and review their reports and artifacts.
4. [ ] **planned** — Author review and signoff before the merge checkpoint.

## Tags

- ci
- verification
- benchmarks

## Problem

Compiler verification depends on local tooling and Linux containers. Emulated x86-64 execution
provides useful correctness coverage but does not establish behavior on native x86-64 hardware.
The repository has no GitHub Actions workflow for compilation, its test suites, or benchmarks.

## Outcome

GitHub-hosted runners compile the compiler, run all applicable test suites and smoke tests,
and compile and execute the benchmarks. Results identify the architecture, toolchain, and
executed checks, with logs and benchmark results available for inspection.

## Scope

- Compiler compilation and formatting/lint checks using the existing build tooling.
- Full library and CLI tests, including calling-convention and interoperability fixtures.
- Smoke tests and the ASan/LSan memory harness.
- Platform-specific tests, including the macOS tail-cleanup probe where supported.
- Benchmark compilation and execution, with retained measurements and runner metadata.
- Workflow configuration, minimal supporting scripts, and usage documentation.
- Out of scope: compiler behavior changes, automatic releases, and automatic merges.

## Plan (Approval Gate)

Approval: pending. Task scheduling does not authorize workflow implementation or remote execution.

- [ ] Inventory test and benchmark entry points and their toolchain dependencies.
- [ ] Select native runner architectures and operating systems; evaluate Linux x86-64 and
  ARM64 coverage and a macOS job for the `vmmap`-dependent probe.
- [ ] Set triggers, timeouts, caching, artifact retention, benchmark variants and repetitions.
- [ ] Reuse the existing commands in the required order, keeping `sbtn` invocations sequential
  within each workspace and publishing locally before consumers invoke `mmlc`.
- [ ] Validate the workflow and obtain evidence from real GitHub-hosted executions.

## Verification

Implementation and cloud execution evidence are pending. Existing entry points are documented in
[development tools](../dev-tools.md), [smoke tests](../../tests/smoke/Readme.md), and
[tail-cleanup tests](../../tests/tail-cleanup/Readme.md). The
[memory harness](../../tests/mem/run.sh) and [benchmark Makefile](../../benchmark/Makefile)
provide the other execution commands.

Acceptance requires compiler compilation, the full test suite, smoke tests, applicable memory
and platform-specific checks, and actual benchmark execution. Report unavailable or failing
checks explicitly. Retain failure logs as well as successful benchmark output.

## Risks / Notes

- Runner selection, triggers, and benchmark repetitions require approval.
- Benchmark comparisons include additional toolchains; determine whether every comparison
  and optimization variant runs on each trigger or in a separate benchmark job.
- Cloud timing variability limits performance conclusions. Record CPU and toolchain details;
  timing regression thresholds need a separate justified policy.
- The infinite tail-cleanup probe requires macOS `vmmap`; Linux execution cannot cover it.
- Place this task before **Checkpoint: Merge into main** in the tracker.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The required coverage is cloud compilation, all applicable test suites including smoke and
memory checks, and benchmark compilation and execution. Existing harnesses should supply the
commands rather than duplicating their behavior in workflow YAML. Next: settle runner coverage,
triggers, benchmark policy, and a bounded implementation plan for approval.
