# GitHub Actions compiler verification and benchmarks

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-10-23
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference:** none

## Execution Checklist

1. [x] **complete** — Approve runner coverage, workflow triggers, and benchmark execution policy.
2. [x] **complete** — Implement workflow, supporting scripts, MML benchmarks, and retained
   memory diagnostics; Author review accepted.
3. [x] **complete** — Validate workflow syntax, execution, reporting, and failure handling locally.
4. [x] **complete** — Update post-chores and verification documentation; complete independent review,
   tracking checks and Author signoff.

## Tags

- ci
- verification
- benchmarks

## Problem

Compiler verification depends on local tooling and Linux containers. Emulated x86-64 execution
provides useful correctness coverage but does not establish behavior on native x86-64 hardware.
Cloud verification needs retained diagnostics and benchmark measurements tied to each runner.

## Outcome

GitHub-hosted runners compile the compiler, run applicable test suites and smoke tests, and
compile and execute MML benchmarks. Summaries identify architecture, toolchain, check status,
and timings; downloadable artifacts retain measurements and failure logs.

## Scope

- Workflow configuration and minimal supporting scripts; no compiler behavior changes.
- Formatting/lint checks, compilation, full library/CLI tests, smoke tests, and ASan/LSan harness.
- macOS O0/O3 tail-cleanup probes and their stderr negative control.
- MML-only benchmark compilation and execution, including existing optimization/TCO variants.
- Optional cloud-run offer in post-chores when local Docker verification is required.
- No cross-language comparisons, automatic releases, or merges. Cloud runs require an explicit
  manual request; recurring monitoring is disabled.
- Deliver the testing-branch corrections as one squash into `dev-2026-03-21-lambdas`.

## Approved Plan

Implementation approval: granted for this scope, with a review checkpoint after the first slice.

- Use standard native `ubuntu-24.04`, `ubuntu-24.04-arm`, and `macos-26` runners.
- Trigger only through manual dispatch; no push, PR, or scheduled trigger.
- Install JDK 25, repository-pinned `sbt --server --batch` with server autostart disabled,
  LLVM/Clang 20 on Linux and LLVM/Clang 23 on macOS, Python, Hyperfine and GNU timeout.
  Keep workspace commands sequential; platform jobs may run independently.
- Disable matrix fail-fast; limit each platform job to 120 minutes. Cache downloaded dependencies
  by OS, architecture, JDK, and build configuration, excluding project outputs and binaries.
- Run non-mutating formatting/lint checks, compilation, smoke, and `testFull`. Require smoke
  and tests to pass before local publication; publish before memory checks and benchmarks.
- Continue independent checks after failures, mark dependent checks blocked, and retain exit codes.
- Reuse the Makefile's complete MML binary list. Clean and compile before measurements; use
  two warmups, ten measured runs, and a 300-second limit per invocation.
- Run sieve at 10,000,000 except the four `--no-tco` builds, which use 100,000. Local and CI
  targets share this input policy. Compare timing ratios only within the same workload.
- Report check durations, benchmark median/min/max/standard deviation and count, commit SHA,
  runner CPU/OS/architecture, and toolchain versions. Keep compilation timing separate.
  Timings include process startup, GNU timeout overhead, and output capture.
- Export JSON/CSV and Markdown run summaries. Retain logs and test reports for 14 days even
  after failure. No baseline comparisons or timing thresholds; no correctness claims beyond
  existing benchmark checks and captured output.
- Preserve per-fixture memory diagnostics outside the harness's cleaned binary directory.
- Add an optional cloud-run offer when local Docker checks are mandatory. Keep those local
  gates mandatory; declining the extra run does not block completion. Identify the branch/SHA,
  obtain any required commit/push authorization, and verify the dispatched run's SHA.
- Document manual dispatch availability after the workflow reaches the default branch.

## Verification

Initial implementation checks:

- Python AST parsing passed for all three CI Python modules.
- `bash -n scripts/ci/setup.sh tests/mem/run.sh` passed.
- `make -C benchmark -n bench-mml RESULTSDIR=../build/ci/benchmarks` enumerated all 37 MML
  binaries, including the existing O0–O3/TCO variants, through the shared Makefile list.
- Controlled Python commands confirmed successful execution returns 0, a failing command retains
  exit code 7, and an expired deadline returns 124. A report without check/benchmark data returns
  failure and marks checks unavailable.
- `git diff --check` passed.

The initial dry-run enumeration checked measurement arguments, not the compile prerequisites;
the hosted ARM64 run exposed that gap. Final local validation covers process failures,
dependency blocking, retained memory diagnostics, real benchmark execution and report generation.
A fresh end-to-end local workflow has not run. Independent review completed with no actionable findings.

Hosted run [40151127457](https://github.com/fedesilva/minnieml/actions/runs/40151127457)
tested `cdff778deff70755131254170d9babdf01fe82b4` and failed on all three platforms:

- Linux x86-64 passed style, compilation and smoke; the native aggregate ABI test timed out
  after 31 seconds. Publication, memory checks and benchmarks were blocked.
- Linux ARM64 passed the full suite and all 52 memory fixtures. Benchmark compilation built
  only 13 binaries because Make expanded prerequisites before the variant lists were defined;
  measurement attempted 37 binaries and the missing 24 failed with exit code 127.
- macOS passed style and compiler compilation. LLVM 20's linker rejected `arm64e.x1` in the
  default SDK's TAPI stubs. Smoke and tail probes failed; the full suite reported 175 failures.

Hosted run [40164361041](https://github.com/fedesilva/minnieml/actions/runs/40164361041)
tested `14ba054a126afad1e28be71b62ad278eb90753bf`:

- Both Linux jobs passed 1,531 library tests, 9 CLI tests, all 52 memory fixtures, and all
  37 benchmark builds. The native ABI cases passed; x86-64 compilation took 6.1 seconds
  at O0 and 25.6 seconds at O2. Both jobs failed only `sieve-mml-O0-no-tco`, terminated
  by a signal; the remaining 36 benchmarks passed.
- macOS failed setup. Every probe still linked against the CommandLineTools SDK because
  Homebrew's target config supplied `-isysroot`, overriding SDKROOT. Alternative SDK
  compatibility was not established by that run.

Run [40165759598](https://github.com/fedesilva/minnieml/actions/runs/40165759598) at
`32ab3abc205a32fc0ef853cf162919f9cc2ef0bd` established that Xcode 26.0.1 passes the
explicit LLVM 20 compile/link/run probe. Setup then failed because the configuration
path used the newer LLVM installation layout. LLVM 20 uses Homebrew's shared
`etc/clang` directory; commit 1580120 supplies that path. Both Linux jobs passed all 1,540 tests,
52 memory fixtures and 37 benchmarks.

Run [40165872839](https://github.com/fedesilva/minnieml/actions/runs/40165872839) at
`1580120888a95a0a59ff9cc64ac94e0aa96bc5ff` passed both Linux jobs. Downloaded artifacts
confirm all checks and 37 benchmark results with ten successful repetitions each.
macOS passed SDK setup but reported repeated 31-second timeouts in sanitizer-backed
`MixedOwnershipCodegenTests` and `RuntimeTests`. Its final logs have not been reviewed.

The failure pattern matches the upstream macOS sanitizer startup deadlock described in
[LLVM issue 200447](https://github.com/llvm/llvm-project/issues/200447). The
[upstream fix](https://github.com/llvm/llvm-project/pull/182943) has a
[22.x backport](https://github.com/llvm/llvm-project/pull/188913). This is a probable cause,
not a confirmed runner stack trace. The approved correction uses Homebrew LLVM/Clang and
lld 23 on macOS, matching the local major version; Linux retains LLVM 20. All sanitizer checks
remain enabled and test deadlines remain unchanged.

A sanitizer preflight runs a plain control, an ASan/UBSan control with leak detection,
and a deliberate leak that must exit 99 with a LeakSanitizer diagnostic. Native executions
have 15-second deadlines. Logs and fixture source are retained in the existing artifact
directory. A failed probe blocks the full suite and publication while independent checks
continue. Local macOS LLVM 23, native ARM64 Linux LLVM 20 and emulated x86-64 Linux LLVM 20
passed. Controlled build failure, timeout, missing leak diagnostic and missing detection
cases correctly failed. Controlled workflow execution confirmed dependent checks are
blocked and the summary exits nonzero. Python parsing, shell syntax and scoped whitespace
checks passed. Run [40167098457](https://github.com/fedesilva/minnieml/actions/runs/40167098457)
at `5886f4f3666adac0f59e9d7ffa14625130e9d7a7` completed: both Linux jobs passed; macOS
LLVM 22 passed every verification check, including sanitizers, all tests, memory fixtures,
tail probes and all 37 benchmarks. The macOS job failed only in summary generation because
Hyperfine 2.0 stores samples under `measurements`, while the reporter expected 1.x `times`.

The reporter supports both formats and derives median, minimum, maximum and sample standard
deviation from ten successful wall-clock measurements in seconds. Invalid or missing data
produces a failed table row and nonzero report status while retaining other rows. Local
regeneration from both hosted artifact sets produced 37 rows each; every statistic matched
Hyperfine's original summary. Unknown schema, incomplete samples, nonzero execution,
incorrect units, non-finite values, malformed JSON and missing results failed as expected.

Run [40167268975](https://github.com/fedesilva/minnieml/actions/runs/40167268975) at
`a41e9ada1257097515db1cd501a00ee5f8ffd2d5` uses LLVM 23; its reporter has
the Hyperfine 2.0 incompatibility. Its final logs have not been reviewed.
Homebrew supplies major 23 through its unversioned
`llvm` and `lld` formulae; setup rejects any other major version before verification.
The selection guard passes against local LLVM/lld 23.1.1; shell syntax and scoped diff checks pass.

Local follow-up reproduced the sieve failure on native ARM64 Linux with LLVM 20: the
unchanged ten-million input exited 139 under the default 8 MiB stack, then passed under
512 MiB and printed 664,579 primes. The Author requested a command-line size argument.
Both MML sieve programs retain ten million by default and accept limits from 2 through
ten million. Measurements use 100,000 only for the four `--no-tco` variants; all other
sieve builds use ten million. Arguments appear in JSON/CSV labels, status JSON and the
Markdown summary. Local Hyperfine groups the two workloads separately. At the smaller
input the O0/no-TCO variant passes under the normal stack limit and prints 9,592 primes.

Validation of the follow-up:

- Native ARM64 Linux and macOS passed O0/no-TCO and checked-sieve argument checks;
  invalid limits and extra arguments fail. Optimized default-size controls print 664,579.
- The real Linux Hyperfine harness completed two warmups and ten measurements of the
  O0/no-TCO sieve at 100,000. Report argument labels and missing-measurement failure passed.
- A real local Clang/lld probe started with an intentionally wrong target configuration,
  selected the explicit SDK, updated only temporary Clang configs, verified the linker's
  actual SDK, and compiled/executed without an explicit override. Controlled fallback,
  all-candidates-failed and wrong-linker-SDK cases passed. This used local LLVM 23;
  hosted LLVM 20/Xcode compile/link/run compatibility is established by run 40165872839;
  sanitizer-backed execution remains failing.

Acceptance remains local validation and review; hosted execution is not a task-completion gate.
No failed or blocked hosted check is passing evidence.

Local validation of the manual-only trigger and sieve input policy:

- YAML parsing confirmed `workflow_dispatch` is the only trigger.
- `make -C benchmark bench-self-sieve BENCH_RUNS=10 LOG_BENCH_RESULTS=1` passed all eight
  variants, with 20 warmups and ten measurements each, exporting separate TCO/no-TCO results.
- All ten existing local sieve binaries returned the expected counts: 664,579 at ten million
  and 9,592 at 100,000. No compiler rebuild or behavior change was involved.
- Make dry runs retained all 37 MML binaries. Controlled CI command capture checked all ten
  sieve selections plus a non-sieve control, including arguments in labels and status JSON.
  The local Hyperfine and `/usr/bin/time` targets use the same size assignments.
- The default O0/no-TCO invocation reproduced SIGSEGV under the local approximately 8 MiB
  stack; supplying 100,000 returned successfully. No timeout wraps the local self targets.

Closure validation:

- Python AST parsing, shell syntax and whitespace checks passed. Real command controls returned
  0 for success, 7 for failure, 124 for timeout and 127 for a missing executable, retaining logs.
- Controlled style failure allowed independent checks to continue. Compilation and sanitizer
  failures blocked their dependents, preserved exit codes and produced failing Markdown reports.
- Isolated memory-harness controls covered success, compilation failure and execution failure.
  Cleanup removed binaries while preserving compile/run logs; the harness retained failure status.
- Reports regenerated from actual Linux Hyperfine 1.x and macOS 2.x artifacts retained all 37
  rows and matching statistics. Missing or malformed results failed while retaining 36 valid rows.
- `make -B -C benchmark bin/nqueens-mml` compiled the nested safety helper; execution returned
  `Solutions for 12-queens: 14200`.

Run [40167953337](https://github.com/fedesilva/minnieml/actions/runs/40167953337) at
`20532469b08ab67fc1fd29423276cc9c98fc66bf` passed the macOS LLVM 23 job, including report
generation. Its log and artifact establish 1,540 passing tests, 52 memory fixtures, all tail
probes and 37 benchmarks. Both Linux jobs were active at the closure inspection; their final
outcomes are deferred evidence, not passing results for this commit.

The macOS artifact retained 52 compile logs and 52 execution logs but omitted XML test reports:
sbt 2 stores them under nested target directories. The corrected `**/test-reports/` upload
pattern selects all 97 local sbt 2 report files. Hosted validation of that pattern, the final
sieve workload selection and the nested nqueens helper is deferred; no new run is dispatched.

The independent primary review and three fresh dedicated checks (code style/QA, test quality,
and human readability) completed with no actionable findings. No candidate required claim
verification. The test-quality reviewer independently exercised process outcomes, benchmark
schema/argument validation, sanitizer diagnostic identity and dependency blocking. Review was
read-only; it did not rerun full compiler suites or hosted workflows. Final tracking consistency,
local documentation links and scoped whitespace checks passed.

## Risks / Notes

- Hosted runner variability limits performance conclusions.
- macOS `vmmap` coverage cannot be supplied by Linux checks.
- A registration stub is published on `main` in `c942c05`; manual runs can select the
  development or testing branch. The compiler work remains outside main.
- This task can be closed after its agreed local acceptance checks; optional cloud runs do not block closure.

## Signoff

- Implementation plan: approved.
- First implementation slice review: accepted.
- Workstream signoff: granted by explicit closure instruction; required local checks and review passed.
- Tracked item completion: complete.
- Commit and push authorization: granted for workflow corrections on the testing branch.
- Delivery: squashed locally into `dev-2026-03-21-lambdas`; parent push is pending.

## Task Working Memory

The full workflow and benchmark changes are complete and squashed locally into
`dev-2026-03-21-lambdas`. The parent has not been pushed; `main` retains its registration stub.
The workflow is manual-only, the recurring monitor is deleted, and further cloud runs require
an explicit request.

Both hosted Linux architectures passed the complete workflow in earlier runs. macOS LLVM 23
passed all verification, benchmarks and reporting at 2053246. Latest Linux completion remains
unreviewed; the reporter is locally validated against real 1.x and 2.x artifacts.
macOS setup selects LLVM/lld 23 and its installation's Clang configuration directory;
Linux retains LLVM 20. Test deadlines and compiler behavior remain unchanged.

Sieve input selection is locally validated: 100,000 for `--no-tco` and ten million for other
builds, including local self-benchmark targets. The nqueens helper is nested and its result
validated. The artifact glob includes nested sbt 2 reports. Documentation describes manual
dispatch, SHA verification and the optional cloud-run offer after mandatory local Docker checks.

Local acceptance, documentation, independent review, tracking checks and signoff are complete.
There is no remaining implementation work. Deferred hosted results may produce separate follow-up work.

### Hosted correction evidence

Run `40150856461` passed setup on all platforms but failed to connect to the sbt server:
sbt 2 selects its thin client by default even with batch mode. The launcher requires
`--server` to select the foreground JVM; `--batch` and disabled server autostart keep each
invocation independent. Command output streams to the Actions console and retained logs.
The original Linux x86-64 run passed style, compilation, and smoke, then timed out in one
aggregate ABI fixture after 31 seconds (1,529 library tests passed, one timed out).
