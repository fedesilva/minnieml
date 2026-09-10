# Lambda restart: test preservation map

The noncompiler transfer is complete on `dev-lambdas-migration`. All source-tip test declarations,
helpers, memory programs, samples, documentation, and independent benchmark/tooling changes are
present. The compiler matched parent `c7e9078` at that checkpoint; current compiler work is
recorded in the workstream sections below.

The transfer baseline passed **430 tests**, with **62 ignored** and no failures or errors. Ignored tests
are unfinished compiler work, not passing coverage. Every ignore has its reason in a comment
immediately above the test. One preexisting nested TBAA declaration remains undiscovered.

## Standing handoff requirement: every migration phase

The Author requires this document to stay current throughout **all phases of the migration**,
including documentation, tests, compiler changes, verification, and follow-up work. This applies
to every workstream, not only the currently approved borrowed-return work.

Update the handoff at each meaningful checkpoint, before pausing or ending a workstream, and
before handing work to a fresh session. Record:

- Work completed and the files or commits containing it; distinguish committed, pushed, and
  uncommitted changes.
- Work in progress, unresolved findings, blockers, and the exact next action.
- Checks actually run and their results, including failures, ignores, and checks still pending.
  Keep prior baseline results distinct from verification of the current changes.
- Decisions and approvals already given, their scope, and decisions still needed from the Author.
- Any live command or process and how to resume checking it; do not infer completion from silence.

A session reset must not require reconstructing progress from the conversation. Start a resumed
session by reading this handoff and verifying the recorded state against the repository. Preserve
existing approvals, avoid repeating completed work, and update the note as the phase advances.
This requirement also applies to future phases that have not yet been planned.

## Resume from this checkpoint

### Current focus — 2026-09-10

The Author directed the session back to the lambda migration and requested a missing
entry in `tracking.md`. Its Current Workstream section now links this handoff and records
progress on the migration branch. The Author explicitly requested deleting the old checklist
from tracking: this is a fresh start, and source-branch completion claims do not carry forward.
This direction supersedes the workflow-port-first scheduling below; the workflow port remains
planned.

The next compiler discussion follows step 6 of the salvage sequence: common ownership and PAP
elaboration. Select a bounded slice using the preserved regressions, establish its semantic
contract, and obtain approval before implementation. No particular compiler slice is approved.
The signed-off compiler checkpoint is `2efeb8c`; this tracking/handoff update is uncommitted.
No compiler checks were rerun for this documentation-only update.

### Transfer checkpoint and recovery

The pushed documentation/helper checkpoint is `35c87bd9cb89da4f48b8fe2fa2cbae70129e3beb`
(`Start lambda migration with docs and test helpers`). The complete transfer is committed as
`6e33ee4` (`Preserve lambda regressions and remaining source files`). At the start of the compiler
workstream, it is one commit ahead of `origin/dev-lambdas-migration`. Verify this when resuming:

```sh
git status --short --branch
git log -3 --oneline
git rev-parse HEAD
git ls-remote --heads origin dev-lambdas-migration dev-lambdas-unify
git cat-file -t c16231a8753d214617a88a089341033fefe803d7
```

The Author waived confirmation requirements while moving material from the source branch. The
Author also authorized ignored tests and required a reason comment beside each one. **Normal
confirmation rules apply again before compiler changes.** The Author subsequently approved the
borrowed-return workstream recorded below. That approval persists across session resets; do not
ask for it again within this scope. Tracked-item statuses remain the preserved source history.

Read `AGENTS.md`, this document, and [unify-lambdas-salvage.md](unify-lambdas-salvage.md). Before
proposing compiler work, read `docs/memory-model.md`, `context/specs/pap-ownership-model.md`, and
the relevant compiler design/rules. Keep the source branch and immutable provenance recoverable.
The transfer checkpoint did not publish a compiler. The borrowed-return workstream has since
published its compiler locally; see its verification record below.

To resume, the Author can use:

> Read AGENTS.md, context/lambda-test-migration.md, and context/unify-lambdas-salvage.md. Verify the
> migration branch and current changes. The Author has signed off the borrowed-return workstream;
> read its results and remaining native failures below. Preserve existing approvals
> and the ownership contract. Discuss and approve the next bounded compiler slice before editing.

## Signed-off workstream: typed closure destruction

### Final review and local commit approval — 2026-09-10

The Author finished review, approved the compiler-design updates, and authorized a local commit.
The Author explicitly prohibited pushing. This checkpoint supersedes the pending-review and
pending-signoff statements in the historical verification notes below. No top-level Tracked Item
status change or GitHub synchronization is authorized; the broader lambda work remains open.

The final review identified alias comparisons in `DestructionValidator` as a correctness issue.
Fresh primary review completed, but the agent thread limit blocked an independent verifier.
The Author explicitly approved parent verification and a fix if confirmed. Both reported cases
reproduced: an `Int64 -> Int64` closure with an `Int` parameter and a native destructor returning
an alias of `Unit`. The validator follows resolved aliases and single-type groups while preserving
native declaration identity. Five regressions cover compatible aliases and incompatible native
types; the three positive regressions failed before the fix. Verification and the narrow re-review
used the approved parent fallback, without per-claim independence.

Final formatting, lint and the full suite pass: **470 passed, 58 ignored**, without warnings
(`/tmp/mml-alias-tests.log`). The alias closure runs under ASan and prints `3`; the aliased
destructor emits IR accepted by `llvm-as`. Required native checks and the typed closure ASan
sample pass (`/tmp/mml-alias-smokes-final.log`), except the known `partial-fac1` exit 139
(`/tmp/mml-alias-partial-fac1.log`). The compiler is installed in `~/bin`
(`/tmp/mml-alias-publish.log`), and all seven benchmark builds pass
(`/tmp/mml-alias-benchmarks.log`). The memory harness remains **29/34** with the same five PAP
failures (`/tmp/mml-alias-memory.log`). Linux sanitizer verification remains deferred.

The design reference documents alias-aware validation, `OwnedClosure`, and the full semantic
pipeline. Scoped QA and whitespace checks pass. All verification processes are finished.
The next work is the planned local workflow port, before another compiler slice; that migration
has not started.

### Next work after Author review

The Author scheduled [the local workflow port](specs/local-workflow-port.md) after reviewing
the current code and before the next compiler slice. Its local tracking entry and spec are
created; the migration has not started. Destructor review is signed off above. GitHub tracking
is not part of this task.

The Author approved bringing over the code-review skill first. The local skill and discovery
metadata are ported with Scala/compiler criteria and linked from `AGENTS.md`; the rest of the
workflow migration remains deferred. The independent review and its follow-up fixes are recorded
below. The skill port changes documentation only and does not alter prior test evidence.

### Shared LLVM test assertions

The Author authorized sharing the destructor tests' LLVM inspection helpers on 2026-09-10.
`BaseEffFunSuite` exposes `functionBody`, `functionBodyMatching`, `phiCount`, and
`assertPhiPredecessors` through `test/llvm/LlvmAssertions.scala`. All five codegen suites share
function lookup, preserving literal-name lookup and regex signature/ABI constraints separately.
Missing or ambiguous matches fail explicitly.

Phi checking handles conditional and unconditional branches, loop backedges, and repeated edges.
The destructor regressions retain their explicit two-phi expectation; the existing tail-recursive
sum regression also checks predecessors. The helper supports explicit unquoted block labels and
`br`, `ret`, and `unreachable` terminators, rejecting unsupported control flow. It checks predecessor
edges, not all LLVM invariants. Destructor-specific AST construction remains local.

Formatting, lint, and **465 tests pass, 58 ignored**, without compiler warnings
(`/tmp/mml-llvm-helpers-tests.log`). Nine helper tests cover lookup boundaries and positive/negative
phi cases. LLVM 23.1.1 accepts the conditional, loop, and repeated-edge fixtures independently.
This follow-up changes test code only; native publishing, smokes, benchmarks and the memory harness
retain the preceding compiler verification. A fresh scoped review through the local skill found no
actionable issues and independently reran LLVM verification on all three positive fixtures. QA and
whitespace checks pass. Changes remain uncommitted; Author review/signoff is next.

### Independent review fixes: missing cleanup and CPU cache identity

The Author authorized fixing both independently confirmed findings on 2026-09-10.
Final destruction validation requires a registered destructor and matching cleanup entry for
every native/struct heap field. Missing explicit and default destructor registrations accumulate
errors per capture. Regression tests also reject omitted cleanup with a registered target and
check valid cleanup by field and destructor identity. Scalar and borrowed function captures
retain their existing behavior.

Runtime bitcode and object cache filenames include a digest of the compilation flags and
optimization level, alongside the target triple. Distinct resolved/explicit/default CPU choices
use distinct entries; legacy triple-only cache artifacts are bypassed. Tests cover both artifact
kinds, repeat selection, x86 CPU flags, optimization, sanitizer and stack-check options.

Formatting, lint and **456 tests pass, 58 ignored**, without compiler warnings. The missing-target
and omitted-cleanup assertions fail before the fix. Logs: `/tmp/mml-review-fixes-red.log` and
`/tmp/mml-review-fixes-tests.log`. The six previously successful required smokes and the typed
closure ASan sample pass (`/tmp/mml-review-fixes-smokes.log`). `partial-fac1` retains runner
exit 139 (`/tmp/mml-review-fixes-partial-fac1.log`).

On ARM64 macOS with LLVM/Clang 23.1.1, the original reused-directory CPU reproduction passes:
host M5, explicit M1, repeat M1, and no-CPU selections compile and run successfully. Library-mode
checks also pass, with one runtime compilation per distinct selection and cache reuse on repeat.
Independent inspection confirms target attributes in cached bitcode/objects and verifies the
delivered library runtime matches the selected cache entry. Logs:
`/tmp/mml-review-fixes-cpu-transitions.log` and
`/tmp/mml-review-fixes-cpu-library-publish-final.log`. Local publishing succeeds in the latter log.
The first library command attempt used an invalid option; final checks use `--target-type lib`.

All seven benchmark builds pass after cleaning (`/tmp/mml-review-fixes-benchmarks.log`). The full
memory harness remains **29/34**, with the same five PAP failures and the typed closure regression
passing (`/tmp/mml-review-fixes-memory.log`). Linux sanitizer verification remains deferred.
Native x86 CPU-transition execution is not part of this follow-up.

Each fix received a fresh, narrowly scoped primary re-review through the local skill; neither
review produced actionable findings. QA enforcement and staged/unstaged whitespace checks pass.
All verification sessions are finished. Changes remain uncommitted, existing staging is preserved,
and Author review/signoff is next. Tracked-item status, commit and push approvals remain separate;
no GitHub changes.

### Review follow-up: destruction operand exit blocks

The Author authorized regression tests and the fix for the review's lost `exitBlock` finding.
The new codegen tests reproduce wrong outer-phi predecessors for conditional operands in
`DestroyClosure` and `DestroyClosureEnvironment`; the dispatcher control case already passes.
Both straight-line emitters now preserve the operand's exit block. All seven destruction
codegen tests pass, covering AArch64 and x86-64. LLVM assembly verification also passes for all
six emitted regression modules. Logs: `/tmp/mml-destruction-exit-red.log` and
`/tmp/mml-destruction-exit-green-ir.log`; passing IR:
`/tmp/mml-destruction-exit-{closure,environment,dispatch}_{AArch64,X86_64}.ll`.
Reconstructing the failing function bodies from the red test log in those modules also makes
`llvm-as` reject both with `PHI node entries do not match predecessors!`
(`/tmp/mml-destruction-exit-{closure,environment}-reproduced-invalid.ll`).

Final verification: formatting, lint and **451 tests pass, 58 ignored**, with no compiler
warnings. The six previously successful required smokes and local compiler publishing pass
(`/tmp/mml-destruction-exit-full.log`). `partial-fac1` retains its known runner exit 139
(`/tmp/mml-destruction-exit-partial-fac1.log`). All seven benchmark builds pass after cleaning
(`/tmp/mml-destruction-exit-benchmarks.log`). The memory harness remains **29/34**, with the
same five PAP failures listed below and the typed closure destruction sample passing
(`/tmp/mml-destruction-exit-memory.log`). Linux verification remains deferred.

QA review of this follow-up found no further issues; staged and unstaged whitespace checks pass.
All verification commands have finished. Changes remain uncommitted, existing staging is preserved,
and the next step is Author review. Workstream signoff and tracked-item status are still pending.
No GitHub changes.

### Implementation and previous verification

The Author approved implementation of [typed closure destruction](specs/typed-closure-destruction.md)
and its full verification in the fresh-context request. This approval supersedes the older
next-slice planning note below. No tracked-item completion, commit, or push is authorized.

Implementation checkpoint (uncommitted): intrinsic AST nodes, resolved field/target IDs,
helper registration and ownership-aware body completion, expression lowering, final-index
validation, traversal/editor/printing support, and removal of metadata/body/call overrides are
implemented. Ordinary struct destructors retain their bodies. Owning function captures carry
an explicit ownership witness in `Capture.OwnedClosure`; helper bodies are completed after
ownership analysis so borrowed functions cannot acquire destruction by type alone.

The first full run passed 434 tests with three old-helper assertion failures (58 ignored).
After helper updates and five new contract/ownership tests, 441 passed; one new alias test
incorrectly expected ownership transfer on function aliasing. Existing semantics retain the
original owner; its identity assertion is corrected without changing transfer decisions.
Logs: `/tmp/mml-destruction-tests.log`, `/tmp/mml-destruction-tests2.log`.

Verification checkpoint: 445 tests passed with 58 ignored before the final ABI regression test.
`hola`, `quicksort`, and the new nested-closure program compiled and ran successfully via sbtn
(`/tmp/mml-destruction-tests-smoke2.log`). QA caught native ABI lowering being applied to
MML struct destructors; the emitter now lowers native targets only and keeps generated struct
calls in the MML ABI. A new test exercises both AArch64 and x86-64. Validator checks include
actual helper parameter types; native implementation bodies are governed by their registered
signatures. Intermediate test-only expectation failures are recorded in
`/tmp/mml-destruction-final-tests-smokes{,2}.log` and corrected.

Final Scala verification passes: **446 passed, 58 ignored**, no failures or compiler warnings.
Formatting and lint pass, including the final operand-ID test helper review.
Logs: `/tmp/mml-destruction-final-tests-smokes3.log` and
`/tmp/mml-destruction-signoff-tests.log`. Required smokes passed except `astar2` (CSSC assembler
compatibility) and `partial-fac1` (exit 139). Local publishing succeeded in
`/tmp/mml-destruction-publish.log`. Benchmark builds passed except `nqueens` (same CSSC issue).

The full memory harness passed **29/34**, compared with the prior **27/33**. The added
`typed-closure-destruction` case and existing `direct-move-owned-struct-capture` pass.
The five remaining failures concern PAP ownership/codegen: `direct-pap-escaping-heap-alias-arg`,
`direct-pap-escaping-heap-arg`, `direct-pap-escaping-heap-capture`,
`direct-pap-inscope-heap-capture`, and `escaping-paps`. Logs and baseline IR comparisons:
`/tmp/mml-destruction-memory.log`, `/tmp/mml-destruction-diagnostics/results.json`, and
`/tmp/mml-destruction-diagnostics/baseline-comparisons.json`. Common function definitions in the
four emitted PAP programs match the borrowed-return baseline exactly. PartialFac1 and Astar2
also retain their common function definitions; added universal helpers/entry wrappers account
for the differences. PAP fixes remain outside this slice.

The Author additionally authorized fixing the CSSC toolchain compatibility drift and explicitly
rejected hardcoding the machine CPU. LLVM emits assembly for the resolved host CPU, but final
Clang assembly lacked that CPU selection. The portable change in `LlvmToolchain.scala` forwards
the resolved CPU consistently to Clang using `-march` for x86 and `-mcpu` for ARM. Explicit
cross targets without a CPU continue to omit host CPU flags. Runtime compilation uses the same
flags. No CPU model or operating system is hardcoded. The portable change passed formatting, lint, **446 tests (58 ignored)** and the six successful
required smokes, including `astar2`, in `/tmp/mml-cpu-portable-verification.log`. A fresh native
Astar2 build/run also passed, and local publishing succeeded in `/tmp/mml-cpu-publish-final.log`.
A full x86 macOS executable built with `--cpu x86-64` and ran successfully; verbose output confirms
`-march=x86-64` on runtime compilation and final assembly/linking
(`/tmp/mml-cpu-portable-publish.log`). Clang C-to-assembly-to-object checks also passed for x86-64
and AArch64 Linux in `/tmp/mml-cpu-linux-objects`; Linux runtime execution is untested.

The optional cross-target check without `--cpu` exposed an existing, independent LLVM rejection:
`attributes #0 = {}` in `codegen/emitter/Module.scala`. The same emission exists at HEAD; CPU flag
forwarding does not cause it. This check stopped the initial publish batch; publishing was rerun
successfully after a fresh native smoke. `partial-fac1` still exits 139
(`/tmp/mml-cpu-partial-fac1.log`). All benchmark builds now pass after `make -C benchmark clean` and `make -C benchmark mml`,
including `nqueens` (`/tmp/mml-cpu-benchmarks.log`). The repeated memory harness remains
**29/34 passing**, with exactly the same five PAP failures (`/tmp/mml-cpu-memory.log`).
All verification commands are terminal; no background work remains. `git diff --check` passes.
The next action is Author review and workstream signoff; tracked-item status and commit/push
approval remain separate.
QA reviewed the closure diff and portable CPU change against the coding rules. Native/MML ABI
and semantic identity findings are fixed. No new QA findings remain; the recorded PAP failures,
ignored coverage and Linux sanitizer assembly failures remain explicit limits; the Linux follow-up
below resolves the no-CPU cross-target rejection and verifies ordinary Linux execution.

The new memory case covers nested owned closures, strings, structs, consuming parameters,
alias use, recursion and resource-free function values. All changes remain uncommitted;
workstream signoff, tracked-item updates, commit and push remain separate approval steps.

## Linux verification follow-up

The Author pointed out the existing Docker setup and directed use of its shell scripts.
Docker Desktop was stopped; it is now running. The Ubuntu image built successfully, and
`packaging/docker/linux-builder-shell.sh` started and entered the `mml` Compose service.
The container is ARM64 Linux with LLVM/Clang 20.1.8 and GraalVM 21.0.3.

The first runtime attempt exposed LLVM's `Host CPU: (unknown)` report being forwarded literally
as a CPU name. Every program failed compilation before runtime; preserve these results as
`/tmp/mml-linux-verification-initial.log`. This establishes a remaining portability gap in the
CPU fix, not a passing Linux check. The follow-up treats unknown CPU reports as absent and omits
the invalid empty attribute definition in default-CPU IR. No replacement CPU is hardcoded.
Two regression tests cover CPU discovery and default-CPU attributes. An initial test fixture
missed the function-body terminator; it was corrected. Final formatting, lint and full suite pass:
**448 passed, 58 ignored**, no warnings. The six successful native smokes, default-CPU x86 macOS
cross-compilation and local publishing pass in `/tmp/mml-linux-cpu-fallback-tests-final.log`.
The x86 executable also ran successfully. `partial-fac1` retains exit 139
(`/tmp/mml-linux-followup-partial-fac1.log`). Repeated macOS benchmark builds pass and the memory
harness remains **29/34** with the same five PAP failures
(`/tmp/mml-linux-followup-mac-{benchmarks,memory}.log`). QA review and `git diff --check` pass.

Final Docker results are saved in `/tmp/mml-linux-verification-final.log`. `hola`, `quicksort`,
`astar2`, and `nqueens` compile and run; `style-guide`, `lambda-factorial`, and `raytracer3_p6`
compile; all benchmark builds pass. Linux `partial-fac1` also exits 139. The memory harness
reports **0/34**, all compile failures, so it provides no Linux memory-safety result. A standalone
`arrays-mem` diagnostic shows LLVM 20's generated `asan_globals` assembly rejected by Clang 20
with `Linkage must be 'comdat'`. Its assembly remains at
`/tmp/mml-linux-asan-diagnostic/out/aarch64-unknown-linux-gnu/ArraysMem.s` inside the container.
Other individual compile failures have not been classified beyond the harness results.

ASan itself works in this container: a tiny C probe compiled with Clang's `-fsanitize=address`
and reported the deliberately introduced heap-use-after-free, with a source line and stack trace
(`/tmp/mml-linux-asan-probe.log`). The remaining blocker is MML's sanitizer assembly path;
it must not be described as lack of Linux ASan support or as detected MML runtime memory errors.
No sanitizer workaround or Docker/source-script changes were made.

All commands are terminal and the interactive shell is closed. Docker Desktop and the Compose
service remain running. Linux build outputs are isolated inside the container. The Author deferred Linux sanitizer work to the separate local item
[Revisit Linux sanitizer verification](tracking.md#revisit-linux-sanitizer-verification).
The Author explicitly requires `tracking.md` only, with no GitHub synchronization for this item.
An issue-creation command already in flight created GitHub issue #271 before the interruption;
it has not been added to the project or modified further. The Author was informed. No further
GitHub changes are authorized. Workstream signoff, other tracked-item statuses, commit and push
remain separate approvals.

The Author additionally requested compiling/running `mml/samples/astar3.mml` and the MML
benchmarks in Linux. The existing Docker shell script reopened the service. `astar3` compiled and ran successfully,
printed its path map and `Path found!`, and exited 0. All seven MML benchmark executables ran
once and exited 0: sieve (`78498` primes), quicksort (median `-85`), matmul and matmul-opt
(trace `381460`), nqueens (`14200` solutions), euclidean-ext (`5010954496756`), and ackermann
(`8189`). Their Linux builds were already current from the successful full benchmark build;
`make mml` reconfirmed that state. These are successful execution checks, not a statistical
performance comparison. Log copied to the host: `/tmp/mml-linux-astar3-benchmarks.log`.
The shell is closed and no checks remain running. The local deferred Linux item is recorded;
the closure destruction and portable CPU work is ready for review/signoff with the documented
sanitizer and PAP limits.

## Review follow-up: separate phase files

The Author requires each semantic phase to live in its own file. Body completion is extracted
from `ClosureMemoryFnGenerator.completeBodies` into
`semantic/ClosureDestructorBodyGenerator.scala`, exposing `rewriteModule`. The first generator
registers layouts/helpers after type checking; the new phase completes field cleanup after
ownership analysis. The pipeline and design reference name the two phases separately. This is
a structural extraction with the same phase order and cleanup behavior. The Author also requires
shared implementation to live outside the phase files. Shared intrinsic-body construction is
in `semantic/ClosureDestructorAst.scala`; both phase objects retain a single
`rewriteModule` entry point and no phase calls the other.

The Author accepted this organization and asked to finish. A common semantic-phase trait was
discussed and left for later; none is introduced. Formatting, lint and the full suite pass after
the final shared-helper extraction: **448 passed, 58 ignored**, no warnings. The six successful
required native smokes and local publishing pass in `/tmp/mml-destructor-phase-split-final.log`.
QA reviewed both separate phases, their shared helper and pipeline/docs references; no new issues
were found. Staged and unstaged whitespace checks pass; existing staging is left untouched.

Final benchmark builds pass (`/tmp/mml-phase-split-benchmarks.log`). The memory harness remains
**29/34** with the same five PAP failures (`/tmp/mml-phase-split-memory.log`); the new nested
closure regression passes. `partial-fac1` retains exit 139
(`/tmp/mml-phase-split-partial-fac1.log`). All verification processes are terminal, and the final
compiler is published locally. Linux sanitizer work remains deferred in the local tracking item.

The Author will read the final phase walkthrough and finish review tomorrow. Workstream signoff
is still pending; no tracked-item completion, commit or push has been performed. No further GitHub
changes were made. Resume from the two phase files and `SemanticStage.scala`, preserving the
current staging. The next action is Author review, not another implementation or test cycle.

## Signed-off workstream: borrowed returns through aliases

Approved by the Author after the migration commit and discussion of the existing symbol indexes.
The scope is return-escape analysis through local aliases and conditional results, including
aliases of borrow-capturing closures. Preserve valid shadowing, static returns, and owned returns;
reject invalid escapes without adding implicit clones.

The checkpoint commit is titled `Reject borrowed returns through local aliases` and contains
`semantic/OwnershipAnalyzer.scala`, `semantic/OwnershipAnalyzerTests.scala`, and both handoff
documents. Four preserved regressions
are enabled. Three new checks cover parameter identity after shadowing, valid consuming returns
through aliases, and rejection of a borrowed branch alongside an allocating branch.

`sbtn 'scalafmtAll;scalafixAll;test'` passed **437 tests, 58 ignored**, with no failures or
errors. This includes all four enabled regressions and all three new checks. The log is
`/tmp/mml-borrow-return-final-tests.log`; its process is terminal. Earlier syntax and
unused-member build errors were fixed before this pass.

Verification checkpoint:

- Required smoke passes: `hola` and `quicksort` compile/run; `style-guide`, `lambda-factorial`,
  and `raytracer3_p6` compile. Logs: `/tmp/mml-borrow-return-smoke-hola.log`,
  `/tmp/mml-borrow-return-smokes.log`, and `/tmp/mml-borrow-return-smokes-compile.log`.
- `astar2` fails assembly because `umin`/`umax` require `cssc`; `partial-fac1` exits 139 at
  runtime (log `/tmp/mml-borrow-return-smokes-rest.log`). Their newly generated LLVM IR is
  byte-for-byte identical to the saved parent-compiler migration IR in
  `/tmp/mml-migration-ir/3/` and `/tmp/mml-migration-ir/42/`, respectively. The new copies are
  in `/tmp/mml-borrow-return-ir/`. These failures are not caused by changed IR in this slice.
- `sbtn mmlcPublishLocal` succeeded after smoke verification, installing this workstream's
  compiler into `~/bin`. Log: `/tmp/mml-borrow-return-publish.log`.
- Benchmark clean succeeded. `make -C benchmark mml` built sieve, quicksort, matmul, and
  matmul-opt, then failed assembling nqueens: `abs` requires `cssc`. The remaining targets
  were not built. Log: `/tmp/mml-borrow-return-benchmarks.log`.
- `./tests/mem/run.sh all` completed: **27/33 pass ASan/LSan, six fail**. Log:
  `/tmp/mml-borrow-return-memory.log`. All five failing programs that generate IR produce
  byte-for-byte identical IR to their saved parent-compiler migration baseline. The sixth,
  `escaping-paps`, retains its baseline `TypeGroup` codegen rejection. Diagnostic reruns
  and comparisons are in `/tmp/mml-borrow-return-memory-diagnostics/results.json` and the
  adjacent per-program logs. Failures are detailed below; the memory gate is not green.
- QA reviewed all changed hunks against coding and QA rules: immutable origin propagation,
  indexed identities, existing test helpers, unchanged migrated assertions, no new ignores,
  and no inserted clones. Formatting, lint, and `git diff --check` pass. No additional defect
  was found in this bounded change; native verification failures remain open.
- All verification commands are terminal. No command or session handle remains to resume.

| Memory program | Failure |
| --- | --- |
| `direct-move-owned-struct-capture` | LLVM rejects duplicate `__free_Pair` definition. |
| `direct-pap-escaping-heap-alias-arg` | LLVM sees a closure pair where `%struct.String` is expected. |
| `direct-pap-escaping-heap-arg` | LLVM sees a closure pair where `%struct.String` is expected. |
| `direct-pap-escaping-heap-capture` | ASan reports double-free in `__free_String`. |
| `direct-pap-inscope-heap-capture` | ASan reports a segmentation fault in the local `say` function. |
| `escaping-paps` | No LLVM type mapping for grouped function-return `TypeGroup`. |

Implementation and index findings:

- `Module.resolvables` supplies the existing stable-ID value/type index. `ResolvablesIndexer`
  includes nested lambda parameters and runs before and after ownership analysis.
- `returnedOrigins` follows the result of immediate lambda applications, groups, and conditional
  branches. Supplied argument origins are associated with parameter IDs. The existing index is
  used for reference lookup and updated with the current lambda parameters during traversal.
- Both borrowed-reference and borrowed-closure return checks use those origins. Reference checks
  use indexed parameter consumption and captured-value IDs, without name-based fallback.
- Return escape is checked on the typed body before cleanup and static-return promotion, so
  an inserted clone cannot conceal a borrowed return path.
- The prior separate closure walker, shallow borrowed-reference walker, name fallback, and
  special `__stmt` constant used by that walker are removed.
- General ownership-state maps and owned-value cleanup are unchanged by this slice. The work
  does not claim to resolve every name-based ownership operation, PAP, or struct-field gap.

Implementation and verification are complete for this bounded slice, with the failed native
gates explicitly recorded above. The Author signed off this workstream after reviewing those
results and approved the four-file checkpoint commit. Next action: discuss the next bounded
compiler slice. Do not mark a Tracked Item complete; this signoff covers only the borrowed-return
slice. The checkpoint follows `6e33ee4`; use `git log -2 --oneline` and `git status --short --branch`
to verify its commit and working-tree state when resuming. No push is included in this checkpoint;
the branch will be two commits ahead of the local origin tracking ref after the commit.
The transfer inventory JSON remains the immutable `6e33ee4` preservation snapshot; its test
results and destination hashes describe that checkpoint, not these new compiler changes.

These four preserved `OwnershipAnalyzerTests` cases are enabled without weakened assertions:

- `borrowed param returned through let-binding wrapper is rejected`
- `borrowed param returned through let-bound conditional is rejected`
- `borrowed param returned through two nested let-bindings is rejected`
- `borrow-capturing lambda returned through two nested let-bindings is rejected`

The shadowing and static-return companions remain enabled. The focused new cases cover resolved
identity, valid consuming returns without clone/free insertion, and a mixed borrowed/allocating
return. The known grouped-return codegen failure is not resolved by this approval.

Keep this checkpoint current so a new session can identify the changes, results, and remaining work.
Workstream review is complete; do not request the same signoff again.
Further compiler workstreams and tracked-item status changes require separate approval.

## References and transferred scope

- Parent: `c7e90780897d202d857292b029baedc5b5f9b7e3`.
- Test/program source: `c16231a8753d214617a88a089341033fefe803d7`.
- Documentation source: `8e83506`, retained on local `dev-lambdas-unify`.
- Complete inventory: [lambda-test-migration.json](lambda-test-migration.json).

The complete `context/` and `docs/` trees and root instruction changes were transferred at the
first checkpoint, including additions, edits, renames, and deletions. Migration handoff updates
and these inventory files are intentional additions on the destination branch.

The subsequent transfer includes 67 changed sample entries, 12 added memory programs, all
remaining Scala test changes, `benchmark/Makefile`, both Rust matrix benchmarks, and
`tooling/vscode-llvm-ir/package-lock.json`. All 83 sample/memory/benchmark/tooling files match the
source bytes exactly. None of the source branch's 28 changed compiler implementation files were
transferred.

Three shared test helpers moved from `test/extractors/` to `test/ast/`:

| Parent file | Destination file |
| --- | --- |
| `TXAstExtractors.scala` | `AstExtractors.scala` |
| `TXLambdaHelpers.scala` | `LambdaTestQueries.scala` |
| `TXTermTraversal.scala` | `AstTraversal.scala` |

`AstTraversal` accesses the lambda body through typed `Lambda` matches instead of matching all
eight constructor fields. Its traversal behavior is unchanged. The other helpers match the source
blobs exactly. Existing caller imports and the four traversal helper names were updated.

## Test preservation and ignores

| Measure | Count |
| --- | ---: |
| Parent declarations retained | 411 |
| Source-tip declarations transferred | 477 |
| Current declarations, including retained parent variants | 493 |
| Passing tests | 430 |
| Ignored tests | 62 |
| Undiscovered nested declarations | 1 |
| Original file/name pairs across source history | 486 |
| Distinct declaration versions across source history | 507 |
| Corpus paths / distinct path-and-blob versions | 266 / 367 |

The 16 parent cases replaced or changed in source are retained alongside their successors. Eleven
same-name cases have a `[parent c7e9078]` suffix; five keep their original names. All 410 parent
cases that the runner discovered before migration still run and pass. Parent assertions remain
unchanged after helper-name normalization and test-header formatting.

The first transferred suite failed to compile with 14 errors in five suites. Eighteen tests
require absent materialization metadata, PAP/parser diagnostics, or the type-name comparison API.
Five test bodies are preserved inside comments next to explicit failing placeholders. Three
helper bodies likewise preserve their source assertions in comments and fail explicitly. Restore
those bodies against the agreed compiler API before enabling their dependent tests. Removing an
ignore alone cannot make these placeholders pass.

With those API-dependent tests ignored, the suite ran 430 passing and 44 failing tests. Those 44
source regressions are now ignored, with the observed gap explained directly above each test.
Their programs and assertions remain executable and unchanged. The inventory records each reason
and a short failure excerpt. The final suite has 430 passing and 62 ignored tests.

The pending cases include borrowed returns through aliases, PAP argument moves and escapes,
struct ownership and destruction, heap type aliases, Direct call shapes, capture cleanup, and
named closure entries. Some code-generation assertions pin the source implementation's exact IR
shape. Their preservation does not authorize reproducing that architecture. Parent and source
representation expectations must be reconciled deliberately while preserving their semantic and
ownership coverage.

For example, these aliases must not manufacture ownership:

```mml
fn echo(s: String): String =
  let x = s;
  let y = x;
  y;
;
```

The parent accepts this borrowed return. Its source rejection regression is retained and ignored
with that reason. The shadowing and static-return companion regressions run and pass.

`TbaaEmissionTest.scala` still declares `loads and stores include alias scope metadata` inside
`TBAA field offsets honor alignment (String has ptr at offset 8, not 4)`. This preexisting nested
registration is recorded as `not-discovered`, not passing or ignored.

## Reading the inventory

Schema version 2 keeps every immutable source/history reference from the first checkpoint.
`scala_cases` records original file/name identities, source and parent declarations, their current
`destination`, and a separate `parent_destination` where applicable. `pending` records ignore
reasons and whether an unsupported body is commented. Execution is recorded independently from
preservation status.

| Case status | Meaning |
| --- | --- |
| `source-case-identical` | Current declaration text matches the source tip. |
| `source-case-format-adapted` | Only formatting or an adjacent migration comment differs. |
| `source-case-ignored` | Source program/assertions preserved; the test is explicitly pending. |
| `historical-name-present` | Superseded source name remains in the parent suite. |
| `historical-name-absent` | Intermediate name is retained through source history and successor mapping. |

`history` stores commit, blob, inclusive line range, declaration SHA-256, ignored state, and any
enclosing test. Declaration hashes cover text with trailing whitespace removed. File blob hashes
cover complete files. Recover a complete original with `git cat-file blob <blob>` or
`git show <commit>:<path>`.

`corpus_files` records all file versions and current hashes. `independent_files` records the four
benchmark/tooling paths outside the test/sample corpus. `verification` contains current JUnit
report hashes and outcomes; `previous_checkpoint_verification` retains the 410-test baseline.
Reports are local build outputs and may be absent in a new checkout. Rerun the suite there.
`destination_head` identifies HEAD when the working-tree snapshot was recorded.

Nine historical names have explicit successor mappings. Five remain active as parent tests; four
intermediate-only names remain recoverable in immutable source blobs. Three of the latter assert
rejected clone-per-PAP behavior. Their successors are preserved, but the rejected cloning contract
is not restored. The other intermediate replacement changes sibling-capture representation.

## Verification and limits

```sh
sbtn 'scalafmtAll;scalafixAll;test'
```

Formatting and linting pass without warnings. Final result: 430 passed, 62 ignored, zero failures
or errors. Checks verify every source declaration's preserved body, every parent declaration's
retained destination, all corpus file hashes, and unchanged compiler implementation.

Both Rust matrix benchmarks build and run with checksum `381460`. The transferred lockfile's
root dependencies match `package.json`; its source updates esbuild to `0.25.12`, Node types to
`20.19.41`, and VS Code types to `1.120.0`. Dependencies were not installed or refreshed.

All 79 transferred MML files were checked for IR generation using this freshly built compiler.
The CLI classpath came from `sbtn 'export mmlc / Compile / fullClasspath'`; each file was then
checked with `java -cp <classpath> mml.mmlc.Main ir -b <temporary-directory> <file>`. Results and
diagnostics are recorded in each corpus entry's `ir_check` and summarized in `program_verification`.
There were 67 generated-IR results and 12 rejections, including intentional negative examples.

Of the 12 memory programs, 11 generate IR. `tests/mem/escaping-paps.mml` and sample
`partial-fac2-escape.mml` fail codegen because the parent has no LLVM mapping for `TypeGroup` in
the grouped function-return type. `lambda-forms/direct-inline-lambda.mml` fails parsing.
`borrow-escape-test.mml` is rejected for its untyped hole, so it does not validate borrow escape.
The negative `lambda-forms/struct-field-borrow-fail.mml` sample generates IR: the parent wrongly
accepts that borrowed closure in an owning field, matching the ignored semantic regression.

IR generation does not establish runtime correctness or memory safety. No MML program was run
under sanitizers during this transfer. The memory harness cannot be called passing while
`escaping-paps.mml` fails to compile. Full ASan/LSan checks and MML benchmarks remain required
when implementing the relevant compiler changes.

## Later compiler workstreams

After the approved borrowed-return workstream, discuss and approve the next bounded slice. The salvage review's
candidate is complete executable destructor ASTs; the preserved ownership regressions provide
additional concrete entry points. Establish the invariant and MML examples first, then identify
which ignored tests should become enabled for that slice. Do not silently clone values to repair
ownership, weaken assertions to obtain a green suite, or import the failed compiler wholesale.

Reconcile stale design-document claims and historical tracked-item statuses through their own
reviewed work. Neither this transfer nor the green suite with ignores completes lambda unification.
