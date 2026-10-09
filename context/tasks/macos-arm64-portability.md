# Portable macOS ARM64 builds

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG, PORTABILITY, CRASH
- **Priority:** HIGH
- **Created:** 2026-10-09
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference:** None

## How target parameters are selected

Our compiler defaults to optimizing for the build machine's CPU. The decision is in
`LlvmToolchain.clangFlags` in
[LlvmToolchain.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala):

| MML compiler options | Target triple | CPU passed to Clang on ARM64 |
| --- | --- | --- |
| Neither option | Auto-detected | `-mcpu=native` |
| `--target ...` | Supplied triple | No override; Clang's target default |
| `--cpu ...` | Auto-detected | `-mcpu=<supplied CPU>` |
| Both | Supplied triple | `-mcpu=<supplied CPU>` |

For auto-detection, we ask `clang -print-target-triple` and cache the result. On macOS,
we strip the OS version, producing something like `arm64-apple-macosx`. An explicit
`--target` triple is preserved, including any deployment version it contains.

Next, [ClangTarget.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/ClangTarget.scala)
asks Clang to compile a tiny C probe using those flags and reads its resolved `target-cpu`
and `target-features`. We copy those attributes into our generated LLVM IR, compile the C
runtime with the same target flags, and pass the resolved CPU to LLVM's optimizer (`opt`)
and assembly generator (`llc`). Probe results are cached for subsequent builds.

MML chooses the CPU policy; Clang supplies the feature list; LLVM selects instructions
accordingly. We do not manually choose CSSC or emit the ARM64 `smax` instruction directly,
but requesting `native` on an M5 enables that instruction. Supplying an explicit target
without a CPU override changes this default even when the architecture remains ARM64.

Separately, an unversioned macOS triple leaves the deployment-version choice to Clang.
The CPU selection and the executable's minimum macOS version are independent settings.

## Execution Checklist

1. [x] **complete** — Identify the copied executable and its unsupported CPU instruction on M3.
2. [ ] **planned** — Inspect the M5 build invocation and establish explicit CPU and macOS targets.
3. [ ] **planned** — Agree on portable defaults and implement any approved compiler changes.
4. [ ] **planned** — Build on M5, verify the transferred executable on M3, and obtain signoff.

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

## Plan (Approval Gate)

1. On the M5, capture the exact compiler invocation, compiler revision, LLVM/Clang versions,
   resolved target attributes, deployment-target environment, and selected SDK. The original
   copied binary's build flags are not available in the M3 evidence.
2. Verify a build with explicit `--target arm64-apple-macosx15.0` and no `--cpu` override.
   Inspect the runtime and MML CPU attributes and the executable's `LC_BUILD_VERSION`.
   This is a candidate configuration, not a verified M5-to-M3 solution.
3. Settle the portable CPU baseline and minimum macOS default. Prepare a bounded change
   only if the existing explicit-target workflow or agreed default behavior needs repair.
   Preserve explicit CPU selection for host-specific optimization.
4. Transfer the M5 artifact to the M3. Verify `help`, argument handling, and complete window
   playback for a found path and a full barrier. Record the exact build and dependency
   configuration before claiming portability. Run applicable compiler gates if code changes.

Approval: investigation and task documentation are authorized. Compiler implementation,
default-policy changes, completion, commit, and push remain pending.

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

[mml_runtime.c](../../modules/mmlc-lib/src/main/resources/mml_runtime.c) implements
`mml_args_to_array` with ordinary C:

```c
int32_t user_argc = (argc > 1) ? (int32_t)(argc - 1) : 0;
```

Clang/LLVM chooses the machine instructions; the MML emitter does not emit this ARM64
instruction directly. [LlvmToolchain.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
selects `native` when both `targetCpu` and `targetTriple` are absent. On ARM64 this becomes
`-mcpu=native`. Supplying `--target` without `--cpu` omits that CPU override. The shared
flags feed the target probe, runtime compilation, and final linking;
[ClangTarget.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/ClangTarget.scala)
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

For implicit local targets, `resolveLocalTriple` in `LlvmToolchain.scala` normalizes the
queried Darwin/macOS triple to an unversioned `*-apple-macosx` triple. User-provided triples
are returned unchanged. Clang's effective deployment version on the M5 therefore needs to
be checked along with its environment and SDK configuration. Do not treat the `minos 26.0`
stamp as proof that this program requires macOS 26 APIs, or simply relabel an executable
without rebuilding and verifying the intended deployment target.

## Risks / Notes

- CPU instruction availability and minimum OS version are independent constraints. Lowering
  `minos` does not remove CSSC instructions; changing CPU settings does not establish OS API
  compatibility.
- The M3 target-probe cache selects `apple-m3` and does not enable CSSC. Build-directory
  caches are host-local artifacts; inspect or regenerate them on the M5 and when testing
  explicit target settings.
- No compatible replacement built on the M5 has been executed on the M3. End-to-end
  portability, the exact original M5 flags, and any additional incompatibility remain open.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.
- Push authorization: not granted.

## Task Working Memory

The immediate failure is confirmed: the copied M5 executable executes scalar `smax` in
`mml_args_to_array`, and the M3 raises `SIGILL`. The macOS 26.0 minimum is a separate
deployment concern. LLVM selects the instruction from CPU features; MML's implicit native
CPU choice is a candidate source of those features, pending the original build invocation.

Continue on the M5 with the exact invocation, toolchain, target probe, and deployment-target
settings. Verify the explicit macOS 15 ARM64 target, then decide whether compiler defaults
need a bounded repair. Implementation approval and M5-to-M3 execution evidence are pending.
