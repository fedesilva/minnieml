# Portable macOS ARM64 builds

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG, PORTABILITY, CRASH
- **Priority:** HIGH
- **Created:** 2026-10-09
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference:** None

## Execution Checklist

1. [x] **complete** — Identify the copied executable and its unsupported CPU instruction on M3.
2. [x] **complete** — Establish explicit CPU and macOS targets on M5; inspect the build
   and verify local CLI behavior. The original copied binary’s invocation is unavailable.
3. [x] **complete** — Implement and verify baseline CPU/macOS 15 defaults, explicit native
   optimization, cross-architecture native diagnostics, and native benchmark builds.
4. [x] **complete** — Verify the default-built executable on M3 and obtain signoff.
   Acceptance is based on the Author-confirmed destination run.

## Target policy

Builds use Clang's default CPU for the selected target unless `--cpu` is supplied.
`--cpu native` requests host CPU optimization and requires matching host/target architectures.
An explicit CPU, such as `--cpu apple-m3`, overrides the baseline.

| MML compiler options | macOS ARM64 target | CPU selection |
| --- | --- | --- |
| Neither option | Auto-detected platform, macOS 15.0 | Clang target baseline |
| `--cpu native` | Auto-detected platform, macOS 15.0 | Host CPU |
| `--cpu apple-m3` | Auto-detected platform, macOS 15.0 | Explicit CPU |
| `--target arm64-apple-macosx` | macOS 15.0 | Clang target baseline |
| `--target arm64-apple-macosx14.0` | Explicit macOS 14.0 | Clang target baseline |
| Versioned `--target` and `--cpu` | Explicit version | Explicit CPU selection |

[LlvmToolchain.scala](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
applies the deployment default to fresh and cached local triples and to unversioned ARM64
macOS targets. Explicit deployment versions are preserved. Other platforms retain their
existing triple policy, with baseline CPU selection by default.

[ClangTarget.scala](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/ClangTarget.scala)
probes Clang with the same flags used for runtime compilation and linking. Its resolved
CPU/features feed MML IR, LLVM optimization, and assembly generation. Probe and runtime
cache keys include the selected flags, separating baseline and explicit native builds.
External dependencies must independently support the destination CPU and OS.

## Problem

An ARM64 executable built on an Apple M5 crashes immediately with `SIGILL` on an Apple M3
running macOS 15.7.7. A build made locally on the M3 runs.

The copied executable contains a scalar `smax` instruction requiring the CSSC CPU extension.
The M3 faults on that instruction in the C runtime's argument conversion, before entering
the MML program. Separately, the executable declares macOS 26.0 as its minimum deployment
version. That declaration is not the cause of the observed illegal instruction, but it does
not describe the intended macOS 15 deployment either.

An ordinary macOS ARM64 build should support the agreed Apple Silicon baseline. Enabling
instructions unavailable on that baseline should require an explicit CPU choice. The build
must also declare a macOS deployment target compatible with the destination.

## Outcome

A documented, repeatable M5 build produces an executable that runs on the M3. CPU defaults,
explicit CPU overrides, and the minimum macOS version have clear, independently verified
behavior. The chosen configuration applies consistently to MML code, the C runtime, and
linking. Compatible builds do not require source workarounds.

## Scope

- In scope: macOS ARM64 CPU selection, target-triple and deployment-version propagation,
  runtime compilation, target caches, relevant documentation and regression coverage, and
  M5-to-M3 execution verification.
- Out of scope: macOS-to-Linux compilation, raylib changes, MML language semantics, and
  unrelated ownership or animation changes.

## Approved implementation plan

1. Capture M5 toolchain settings and verify the explicit macOS 15 target, including MML and
   runtime CPU attributes, final load commands, and M3 execution evidence.
2. Default CPU selection to Clang's target baseline. Preserve explicit CPU overrides and
   reject `--cpu native` when the requested architecture differs from the host.
3. Default local and unversioned macOS ARM64 targets to macOS 15.0, including cached local
   triples. Preserve explicit deployment versions. Update CLI help, documentation, and
   regression coverage; run applicable compiler checks and independent review.
4. Verify the default-built M5 artifact on M3 and obtain signoff. Accepted completion
   evidence is the Author-confirmed destination run. Separate found-path/full-barrier
   completion and destination dependency inventory are not part of the accepted closure
   evidence and are not claimed as verified.

Approval: baseline CPU defaults, macOS ARM64 deployment default 15.0, explicit CPU overrides,
cross-architecture native rejection, relevant tests/docs, and verification are authorized.
Workstream signoff, task completion, and local commit are authorized on the confirmed
default-build M3 execution. Push is not authorized.

## Verification

Evidence collected on 2026-10-09, repository revision `2b6fb10`, macOS 15.7.7 (24G720),
Apple M3. The CPU-selection probe uses Homebrew Clang 23.1.1. No compiler sources changed.

### Executable identity and launch

- Copied executable: `~/Dropbox/exchange/mml/astar3_animated/astar3animated`.
- Format: Mach-O 64-bit ARM64.
- SHA-256: `3ca4eb0d2a85174355e9c3b8e6b515492bdb994629e126580bf0bd9654067a23`.
- Mach-O UUID: `4C4C44E3-5555-3144-A19D-45B51C7F1CC9`.
- Launches with no arguments and with `--help` both return `-4` (`SIGILL`) through Python's
  subprocess API, with empty stdout and stderr. The sample's valid help argument is `help`;
  the copied binary faults before argument parsing.
- The local `build/target/astar3animated help` exits zero and prints usage. Full graphical
  execution of the local rebuild is Author-confirmed; automated checks here cover help.
- Both executables link `/opt/homebrew/opt/raylib/lib/libraylib.600.dylib` and
  `/usr/lib/libSystem.B.dylib`.

### Faulting instruction

The M3 report `~/Library/Logs/DiagnosticReports/astar3animated-2026-10-09-071152.ips`
matches the copied executable's UUID and records:

```text
EXC_BAD_INSTRUCTION / SIGILL
frame 0: mml_args_to_array + 24, image offset 17960 (0x4628)
frame 1: main + 16
instruction word: 0x11c00408
```

`llvm-objdump --disassemble --disassemble-symbols=_mml_args_to_array` identifies the
instruction at unslid address `0x100004628` as:

```asm
smax w8, w0, #1
```

The working local executable uses `cmp w0, #1` and `csinc w8, w0, wzr, gt` for the same
operation. Reports from 2026-10-06 show the same unsupported instruction. An earlier
missing-raylib failure is a separate loader error. The illegal-instruction reports reach
`main`, so the loader has accepted the executable despite its `minos` declaration.

LLDB could not launch the process in the tool environment (`process exited with status -1
(no such process)`). The fault location comes from the UUID-matched macOS crash reports and
the executable's disassembly, not a successful live debugger session.

### LLVM instruction selection and MML configuration

[mml_runtime.c](../../../modules/mmlc-lib/src/main/resources/mml_runtime.c) implements
`mml_args_to_array` with ordinary C:

```c
int32_t user_argc = (argc > 1) ? (int32_t)(argc - 1) : 0;
```

Clang/LLVM chooses the machine instructions; the MML emitter does not emit this ARM64
instruction directly. At evidence revision `2b6fb10`, [LlvmToolchain.scala](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
selects `native` when both `targetCpu` and `targetTriple` are absent. On ARM64 this becomes
`-mcpu=native`. Supplying `--target` without `--cpu` omits that CPU override. The shared
flags feed the target probe, runtime compilation, and final linking;
[ClangTarget.scala](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/ClangTarget.scala)
also supplies CPU/features for emitted MML functions.

The automatic native selection can enable the failing instruction on M5, but the original
build command must still be checked. The target triple alone does not select M5 features.
LLVM's [M5 CPU definition](https://github.com/llvm/llvm-project/commit/f85494f6afeb52c18b0fd55fbe340b4b6df630bd)
enables CSSC. Arm's [instruction reference](https://documentation-service.arm.com/static/67e40f3398aa3c3b6eea6a85)
defines scalar `SMAX (immediate)` as requiring `FEAT_CSSC`.

A reduced code-generation probe holds the macOS target constant:

```sh
clang -target arm64-apple-macosx15.0 -mcpu=apple-m5 -O3 -S -x c -o - - <<'EOF'
int clamp_argc(int argc) { return argc > 1 ? argc : 1; }
EOF
```

| CPU option | macOS target | Generated operation |
| --- | --- | --- |
| Omitted | 15.0 | `cmp` and `csinc` |
| `-mcpu=apple-m3` | 15.0 | `cmp` and `csinc` |
| `-mcpu=apple-m5` | 15.0 | `smax` |

All three probes compile successfully. This is assembly-generation evidence, not execution
of an M5-built replacement. It isolates CPU selection from the minimum macOS version.

### Minimum macOS version (`minos`)

`otool -l` reports:

| Executable | `LC_BUILD_VERSION minos` | SDK |
| --- | --- | --- |
| M5 copy | 26.0 | 26.5 |
| M3 local rebuild | 15.0 | 26.2 |

The destination runs macOS 15.7.7. The copied binary's minimum version therefore exceeds
the destination, but execution reaches the C runtime and fails on `smax`. This establishes
the immediate CPU failure; it does not verify every OS API or later execution path.
The SDK version alone does not determine compatibility: Apple
[documents support for older deployment targets with newer SDKs](https://developer.apple.com/documentation/security/resolving-common-notarization-issues).

At evidence revision `2b6fb10`, `resolveLocalTriple` in `LlvmToolchain.scala` normalizes the
queried Darwin/macOS triple to an unversioned `*-apple-macosx` triple. User-provided triples
are returned unchanged. Clang's effective deployment version on the M5 therefore needs to
be checked along with its environment and SDK configuration. Do not treat the `minos 26.0`
stamp as proof that this program requires macOS 26 APIs, or simply relabel an executable
without rebuilding and verifying the intended deployment target.

### M5 explicit-target experiment — 2026-10-10

Compiler and sample revision: `8849b9b40ff949a476587433cc846d6957f01ab4`, branch
`dev-2026-03-21-lambdas`, clean working tree before the experiment. Host macOS 26.6.2
(25G83); LLVM reports host CPU `apple-m5`. Clang, opt, and llc are Homebrew 23.1.1.
The resolved Clang executable is `/opt/homebrew/Cellar/llvm/23.1.1/bin/clang-23`.
`MACOSX_DEPLOYMENT_TARGET` and `DEVELOPER_DIR` are unset;
`SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk` resolves to SDK 26.5.
Clang's configuration adds `-isysroot /Library/Developer/CommandLineTools/SDKs/MacOSX26.sdk`.
`LIBRARY_PATH` includes `/opt/homebrew/lib` and `/usr/local/lib`.

Reproduction from that revision, with an unused build directory:

```sh
sbtn 'run -v -I --target arm64-apple-macosx15.0 --build-dir build/portability-m5-macos15-20261010 mml/samples/astar3_animated.mml'
```

The build exits zero. Default optimization is O3; no CPU override or sanitizer is supplied.
The following results are embedded evidence; generated build files are reproducible outputs,
not required repository context:

- Probe, MML IR, and runtime bitcode all select `target-cpu=apple-m1`, without `+cssc`.
  Probe and runtime also contain `tune-cpu=apple-m5`; their instruction feature baseline
  remains the recorded M1 feature set.
- Runtime Clang flags: `-emit-llvm -c -target arm64-apple-macosx15.0 -std=c17 -O3 -fPIC`.
  LLVM opt and llc receive `--mcpu=apple-m1`; llc also receives
  `-mtriple=arm64-apple-macosx15.0 -relocation-model=pic`.
- Final Clang link flags: `-fuse-ld=lld -target arm64-apple-macosx15.0 -std=c17 -O3 -fPIC`,
  generated assembly, and `-lraylib`.
- Output name: `astar3animated-arm64-apple-macosx15.0`, in the build directory's `target/`.
  SHA-256: `5c51fd70e8066f9dac9cc307a15899b0a79bbf4ac5fd72268ddbddb621c30027`.
  Mach-O UUID: `4C4C4404-5555-3144-A1F0-81E702296198`.
- `LC_BUILD_VERSION`: `minos 15.0`, SDK `26.5`.
- Disassembly of `mml_args_to_array`: `cmp w0, #1` at `0x100003f2c`, followed by
  `csinc w8, w0, wzr, gt` at `0x100003f30`. The identified failing operation uses no `smax`.
- M5 execution: `help`, `25 3 4 0`, and `25 3 4 -1` each exit zero with empty stderr.
  Help prints usage; the latter cases print the positive-speed validation message.
  The Author confirms this exact transferred artifact runs on M3, including the animation
  window. Separate found-path/full-barrier completion and destination raylib identity are
  not recorded.
- Linked libraries: `/opt/homebrew/opt/raylib/lib/libraylib.600.dylib` and
  `/usr/lib/libSystem.B.dylib`. The M5 raylib is version 6.0.0 and declares `minos 26.0`,
  SDK 26.4. The linker warns that this dependency exceeds the requested minimum.
  Destination verification must identify and use a compatible M3 raylib; copying this
  M5 dylib does not establish macOS 15 compatibility.

The existing M5 native probe selects `apple-m5`, includes `+cssc`, and declares macOS 26.0.
Local build logs contain sample invocations without explicit target/CPU settings, but no
invocation is tied to the original crashing binary's hash. Its exact build command remains
unconfirmed. The explicit-target experiment verifies configuration propagation on M5;
M3 execution is Author-confirmed above. The original copied binary’s invocation remains unknown.

### Portable-default implementation verification — 2026-10-10

Host/toolchain match the M5 experiment above. Changes are based on revision
`8849b9b40ff949a476587433cc846d6957f01ab4`.

| Build options | Sample | Probe, MML IR, and runtime CPU | Executable minimum OS |
| --- | --- | --- | --- |
| No target or CPU | `astar3_animated.mml` | `apple-m1`, no CSSC | 15.0 |
| `--cpu native` | `hola.mml` | `apple-m5`, CSSC enabled | 15.0 |
| `--target arm64-apple-macosx --cpu apple-m3` | `hola.mml` | `apple-a16` (Clang's resolved CPU), no CSSC | 15.0 |

Reproduction commands, each with a fresh build directory:

```sh
sbtn 'run -v -I -b build/portability-default-20261010 mml/samples/astar3_animated.mml'
sbtn 'run -v -I --cpu native -b build/portability-native-20261010 mml/samples/hola.mml'
sbtn 'run -v --target arm64-apple-macosx --cpu apple-m3 -b build/portability-m3-20261010 mml/samples/hola.mml'
```

All builds succeed. Both `hola` executables run and exit zero on M5. The default A* executable
prints help and exits zero; its argument conversion contains `cmp w0, #1` and
`csinc w8, w0, wzr, gt`. Its SHA-256 is
`0fd6d681b010184d71d54c0130caa53cb7e09ec87de0e68f1059871f5d449646`.
The Author confirms the fresh default-build artifact with this hash runs on M3. Linking
still warns about the M5 raylib minimum
macOS 26.0; the external dependency caveat from the explicit-target experiment applies.

The installed compiler rejects the following with exit 1 and a diagnostic naming the host
and requested target architectures, before emitting target code:

```sh
mmlc --target x86_64-unknown-linux-gnu --cpu native mml/samples/hola.mml
```

Verification:

- `sbtn 'scalafmtAll;scalafixAll;testOnly mml.mmlclib.codegen.ClangTargetTests'`: 9 pass.
  Coverage includes default/explicit CPU flags, explicit versions, unversioned targets,
  cached local triples, architecture aliases, and native mismatch rejection.
- `./tests/smoke/run.sh all`: 8/8 pass.
- `sbtn test`: library 1,024 pass, CLI 9 pass; 37 existing library tests ignored.
- Target-mismatch warnings in test fixtures are corrected by selecting the same target for
  the C dependencies, probe, and driver. After that test-only correction,
  `testOnly mml.mmlclib.codegen.LinkingTests mml.mmlclib.codegen.AggregateAbiTests`:
  13 pass without warnings. Formatting and linting pass again.
- `sbtn mmlcPublishLocal`: succeeds; publishes the compiler to the local installation.
- `make -C benchmark clean` followed by `make -C benchmark mml`: all 12 benchmark
  executables compile successfully. This is build verification, not performance measurement.
- Final `sbtn 'scalafmtAll;scalafixAll;test'`: library 1,024 pass, CLI 9 pass,
  37 existing ignores; no warnings. Final smoke rerun: 8/8 pass.
- Independent review identifies an unversioned four-component target bypass. The fix preserves
  environment components and explicit OS versions; regression coverage includes
  `arm64-apple-macosx-unknown` and `arm64-apple-macosx14.0-unknown`.
  Fresh narrow review finds no remaining issues in the fix or benchmark Makefile changes.
- Final compiler publication succeeds. A build with
  `SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk mmlc --target arm64-apple-macosx-unknown -b build/portability-fourpart-final-20261010 mml/samples/hola.mml`
  produces `minos 15.0`, SDK 26.5, and runs with exit zero. An sbt-server attempt without
  that SDK environment fails to find `fcntl.h`; four-component targets require an explicit
  SDK environment on this Homebrew configuration, unlike its configured three-component targets.
- All 36 benchmark MML recipes pass `--cpu native`, including self-comparison variants.
  A forced Make dry run verifies all 36 expanded commands. After final publication,
  `make -C benchmark -B mml` rebuilds all 12 main MML benchmark executables successfully.
- Author-supplied benchmark results place optimized nested MML at 1.01 ± 0.06 times the
  speed of optimized C, optimized safe MML within 1.01 ± 0.01 of optimized nested MML,
  and optimized Rust within 1.02 ± 0.02. These are reported measurements, not independently
  rerun timing results.
- QA, tracking consistency, local links, and `git diff --check` pass.
- No ABI-lowering or ownership changes; Linux ABI gates and the memory harness are not
  applicable to this change. Host ABI fixture tests include native sanitizer execution;
  cross-compilation results are not destination execution evidence.

### Default-build M3 acceptance — 2026-10-10

Implementation commit: `a4c86eb`.

```sh
mmlc -v -I -b build/portability-default-transfer-20261010 mml/samples/astar3_animated.mml
```

No target or CPU override is supplied. The fresh executable selects `apple-m1`, omits CSSC,
declares `minos 15.0`, and passes the M5 help check. Its SHA-256 is
`0fd6d681b010184d71d54c0130caa53cb7e09ec87de0e68f1059871f5d449646`.
The transferred executable matches that hash. The Author confirms it runs on the M3 and
accepts task completion. This verifies the compiler's default portable build path.
Separate playback-case results and destination raylib inventory are not asserted.

## Risks / Notes

- CPU instruction availability and minimum OS version are independent constraints. Lowering
  `minos` does not remove CSSC instructions; changing CPU settings does not establish OS API
  compatibility.
- The M3 target-probe cache selects `apple-m3` and does not enable CSSC. Build-directory
  caches are host-local artifacts; inspect or regenerate them on the M5 and when testing
  explicit target settings.
- Both the explicit-target and default-built M5 artifacts run on M3, as confirmed by the
  Author. Separate complete playback-case results and destination dependency identity are
  not recorded. The original failing M5 binary's flags remain unknown.

## Signoff

- Workstream signoff: granted.
- Tracked item completion: accepted on default-build M3 execution.
- Commit authorization: granted.
- Push authorization: not granted.

## Task Working Memory

Portable CPU defaults, macOS ARM64 default 15.0, explicit native optimization, and native
benchmark recipes are implemented in `a4c86eb`. Compiler checks and independent review pass;
the final compiler is published locally. Both explicit-target and default-built artifacts
run on M3. The default artifact's hash, reproduction command, and accepted verification
scope are recorded above. No implementation work remains; push is not authorized.
