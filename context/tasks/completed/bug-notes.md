# Bug notes

## Metadata

- **Owner:** The Author
- **Status:** in_progress
- **Kind:** BUG
- **Priority:** unassigned
- **Created:** 2026-10-26
- **Target Branch:** dev/b34-custom-output-execution
- **External Reference:** None.

## Execution Checklist

1. [ ] **in_progress** — Collect confirmed bugs and update their fix status.
2. [ ] **planned** — Triage open notes and split them into focused tasks.

## Problem / Outcome

Keep bugs found during development visible without interrupting the active implementation.
Repair approvals are recorded separately under Scope / Approval.

## Notes

No open bug notes.

### Resolved notes

- **B34 — Complete: execute the custom `run --output` path.**
  [CompilerConfig.executablePath](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CompilerConfig.scala)
  selects one absolute path for linking and execution, including custom relative outputs.
  [Run regressions](#b34-custom-output-execution) cover custom paths, stale default executables,
  and default naming. Compiler checks, independent review, and Author signoff are complete.

- **B33 — Complete: decode file URIs in editor compile commands.**
  [LspHandler.uriToPath](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala)
  uses shared URI decoding for binary, library, AST, and IR commands. Hierarchical `file:/`
  forms decode once; raw filesystem paths, including relative `file:`-prefixed filenames,
  pass through unchanged. All [53 command regressions and compiler checks](#b33-editor-file-uri-decoding)
  pass. Independent review and Author signoff are complete.

- **B31 — Complete: preserve build-directory paths containing spaces.**
  [LlvmToolchain](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
  preserves path arguments during assembly, runtime bitcode linking, and optimized IR emission.
  The shared command runner accepts argument sequences. Executable and library regressions,
  required compiler checks, independent review, and Author signoff are complete.
  See [verification](#b31-spaced-build-paths) for commands and failure controls.

- **B30 — Fixed: document explicit Buffer flushing.**
  The [buffer I/O contract](../../docs/language-reference.md#buffered-io) requires an explicit
  `flush(b)` to write pending output before dropping a buffer. Destruction releases storage
  and discards pending output. This matches
  [__free_Buffer](../../modules/mmlc-lib/src/main/resources/mml_runtime.c) and its
  [storage-only classification](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/StorageOnlyDestruction.scala).
  The flush-on-free promise was a documentation error; runtime and compiler behavior are unchanged.

- **B23 — Fixed: nested parentheses cause rapidly growing parse work.**
  Shared parenthesized parsing bounds work in ordinary and member-body arithmetic and nested
  tuples. [Bounded measurements](parser-parenthesized-expression-performance-evidence.md)
  show 3,452 callbacks for the 16-term case, down from 8,339,468, with preserved parser output.
  Compiler checks, independent review, and Author signoff are complete.
  Implementation and verification belong to
  [Bound parsing work for nested parenthesized expressions](parser-parenthesized-expression-performance.md).

- **B1 — Fixed: moved-capture editor diagnostics.**
  [LSP diagnostics](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/Diagnostics.scala)
  maps `CapturedMovedHeapBinding` to its reference range and existing message. A source-driven
  regression confirms the capture-specific diagnostic alongside the existing use-after-move error.
  This is a diagnostic-delivery bug; no ownership-checking or memory-safety defect is established.
  Follow-up: [Show moved-capture and visibility errors in the editor](lsp-missing-semantic-diagnostics.md).

- **B2 — Fixed: visibility diagnostic presentation.**
  `VisibilityViolation` has LSP range and message mappings, covered with a diagnostic built from
  parsed source nodes. It has a CLI renderer but no production emitter; this repair does not
  establish source-level visibility enforcement.
  Follow-up: [Show moved-capture and visibility errors in the editor](lsp-missing-semantic-diagnostics.md).

### Historical reproduction evidence for B30, B31, and B33

Independent checks recorded these observations at commit
`4cd89e56cbb47e8a52b3103235492b6b7eca66f0` on macOS arm64 with Clang/LLVM 23.1.1.
The unchanged compiler passes `sbtn testFull` (2,277 library and 9 CLI tests) and all eight
smoke fixtures. These passes do not cover the controls described above.
B30's empty output is expected under the explicit-flush contract; B31 and B33 remain defects.

B31 interpolates paths into `llvm-as $sourceFile -o $outputFile` and invokes `Process(cmd, dir)`,
splitting spaced paths into separate arguments.
`sbtn 'run -v --build-dir "build/review space" tests/smoke/hola.mml'` reproduces
`llvm-as: Too many positional arguments`. The same source with a path without spaces passes;
quoted direct assembly of the generated IR passes. A spaced final `--output` with a build
directory without spaces also passes. This finding does not establish a final-output-path bug.

For B30, create a C fixture containing `#include "mml_runtime.c"` and the following
`main`, then compile from the repository root with
`clang -std=c17 -g -O0 -fsanitize=address -fno-omit-frame-pointer -I modules/mmlc-lib/src/main/resources fixture.c -o fixture`.
The B30 output observation comes from direct runtime execution at O0/O3 with AddressSanitizer and leak
detection disabled. Compiler allocation and cleanup paths were traced; this case was not
run as a newly compiled MML program.

```c
// B30: no output; inserting flush(b) before destruction prints the text.
int main(void) {
    Buffer b = mkBuffer();
    buffer_write(b, (String){11, "local-tail\n"});
    __free_Buffer(b);
    mml_sys_flush();
    return 0;
}
```

B33's filesystem check uses a real existing file `original`, obtains
`u = original.toUri().toASCIIString()`, and compares
`Files.readString(Path.of(u.substring("file://".length())))` with
`Files.readString(Path.of(new URI(u).getPath()))` for each filename listed above.

## Scope / Approval

Collect and update notes as development proceeds. Follow-up task creation and immediately relevant
protocol repairs are approved; unrelated repairs require their own plans. Review-checkpoint commits
are authorized. Broader protocol choices have
[discussion subtasks](protocols-design-discussions.md).

B31 repair approved: use argument lists for LLVM assembly, runtime bitcode linking, and optimized
IR emission; remove the unused string-command overload; add spaced-path executable and library
regressions; run required compiler checks and review; update the evidence in this record.

B33 repair approved: reuse shared URI decoding for editor binary/library, AST, and IR commands;
add command-level regressions for escaped filenames and ordinary paths; run required compiler
checks and review; update this record. Work branch: `dev/b33-file-uri-decoding`.

B34 repair approved: share executable-path selection between compilation and execution;
add regressions for custom paths, stale default executables, and default naming; run required
compiler checks and independent review; update this record. Work branch:
`dev/b34-custom-output-execution`, based on `main`.

## Verification

### B34 custom-output execution

Baseline source at `f0f49adb` selects `config.outputName` during linking but ignores it when
launching the executable. With an initially absent build directory,
`mmlc run --build-dir build/b34-run-output --output build/b34-run-output/custom tests/smoke/hola.mml`
compiles the custom executable, then exits 1 because `build/b34-run-output/target/hola` is absent.
Executing the custom output directly prints `Hola, fede` and `Hola, pepe` and exits 0.
An existing default executable can instead run stale code; the regression below establishes
that failure with distinct program exit codes.

Host checks on 2026-11-05 use macOS arm64 and Clang/LLVM 23.1.1.
[CompilerRunTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/CompilerRunTests.scala)
compiles and executes source fixtures through `CompilerApi.processRun`, checking exact exit codes.
Six cases cover an absolute custom path with spaces and an explicit host target, a nested relative
path, a basename in the process working directory, a stale default executable, and default naming
with implicit and explicit host targets. Temporary outputs are removed through resource cleanup.

- Before connecting the shared path rule, `sbtn 'testOnly mml.mmlclib.codegen.CompilerRunTests'`
  reports four failures and two passes. Three custom-output cases fail to launch; the stale-default
  case returns 37 from the old program instead of 43 from the freshly compiled custom executable.
- `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.codegen.CompilerRunTests mml.mmlclib.codegen.ToolchainPathTests'`
  passes all eight cases after compilation and execution use the shared rule.
- `./tests/smoke/run.sh all` passes all eight fixtures. An initial sandboxed attempt cannot
  access the sbt cache lock; the rerun with cache access passes.
- `sbtn 'run run --build-dir "build/b34-run space" --output "build/b34-run space/custom program" tests/smoke/hola.mml; testFull; mmlcPublishLocal'`
  runs the custom executable, printing `Hola, fede` and `Hola, pepe`, passes 2,599 library tests
  and nine CLI tests, then installs the compiler locally. Test reports contain no failures,
  errors, or skipped tests. An initial invocation with separate task arguments fails CLI parsing;
  the semicolon-separated command above completes successfully.
- `make -C benchmark clean` and `make -C benchmark mml` pass, rebuilding all 37 MML binaries
  with the installed compiler. This verifies compilation, not execution timings.
- QA enforcement, task/tracker consistency, all local file links, and the B34-scoped
  `git diff --check` pass.
- Memory-harness and ABI container gates do not apply to this executable-path selection change.
  Execution coverage is macOS arm64; no Linux or Windows execution is claimed.
- Independent review finds no actionable issues. All four separate checks complete: code style
  and QA, test quality, human readability, and code documentation. No candidate findings require
  claim verification, and no unresolved claims remain. The review independently checks test
  reports and the shared path inputs; it does not rerun tests.

### B33 editor file-URI decoding

Host checks on 2026-11-05 use macOS arm64 and Clang/LLVM 23.1.1.
[LspCommandTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/LspCommandTests.scala)
exercises binary, library, AST, and IR commands through the JSON-RPC handler with real temporary
source files. Each command receives file URIs and raw paths for ordinary filenames and names
containing spaces, `#`, `%`, `+`, and literal `%20`; a further AST case covers `file:/` syntax.
An exact type-mismatch diagnostic establishes source loading before artifact generation.

- Before the repair, `sbtn 'testOnly mml.mmlclib.lsp.LspCommandTests'` reports 17 failures
  and 32 passes. The failures are the four commands' escaped filenames and the `file:/` case;
  they report file-read errors instead of the source diagnostic.
- `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.lsp.LspCommandTests'` passes all
  49 cases after shared decoding is applied.
- `./tests/smoke/run.sh all` passes all eight fixtures, including compilation and execution
  through the development compiler.
- `sbtn 'testFull; mmlcPublishLocal'` passes 2,593 library tests and nine CLI tests, then installs
  the compiler locally. No tests fail; the test reports contain no skipped tests.
- `make -C benchmark clean` and `make -C benchmark mml` pass, rebuilding all 37 MML binaries
  with the installed compiler. This verifies compilation, not execution timings.
- QA enforcement, task/tracker consistency, all 31 local file links, and `git diff --check` pass.
  Initial independent review completed all four dedicated checks and confirmed one compatibility
  finding with the broad `file:` guard: a raw relative `file:demo.mml` argument terminated every
  compile command without a response. The same file passed to AST as `./file:demo.mml` succeeded.
  Java URI decoding returns no path for the opaque form, so decoding is restricted to hierarchical
  `file:/` forms to preserve those raw paths.
- Four real relative-file regressions reproduce the review finding before correction: 49 cases
  pass and four fail with `NullPointerException`. After narrowing the guard,
  `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.lsp.LspCommandTests'` passes all 53.
  The four cases create unique `file:`-prefixed files in the working directory and remove them
  through resource cleanup. Smoke, full-suite, installation, and all 37 benchmark builds pass again
  after this correction.
- Fresh narrow independent review confirms the correction and finds no actionable issues.
  All four dedicated checks complete: code style/QA, test quality, human readability, and code
  documentation. The sole new candidate is independently rejected; no unresolved claims remain.
- Independent replay through the installed `mmlc lsp` uses a relative `file:demo.mml` containing
  `let value: Int = true;` in the server's working directory. All four compile commands return
  the expected source diagnostic, preserve initialization/command/shutdown responses, and exit 0.
- Memory and ABI gates do not apply to this URI-to-path conversion change. Interactive editor
  execution is untested; command regressions exercise the actual JSON-RPC handler in memory.

### B31 spaced build paths

Host checks on 2026-11-04 use macOS arm64 and Clang/LLVM 23.1.1.
[ToolchainPathTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/ToolchainPathTests.scala)
compiles executable and library fixtures with spaced build and final-output paths, reassembles
their optimized IR, and checks exit status 37. The library case links its emitted module and
runtime objects through a C driver before execution.

- The recorded CLI reproduction fails at `llvm-as` before the repair.
- `sbtn 'testOnly mml.mmlclib.codegen.ToolchainPathTests'` initially fails both cases at
  `llvm-as`. With only assembly repaired, the executable case fails at `llvm-link` and the
  library case fails at `llvm-dis`. These controls establish coverage of all three command sites.
- `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.codegen.ToolchainPathTests'` passes
  after the full repair: two tests passed, none failed.
- `./tests/smoke/run.sh all` passes all eight fixtures.
- `sbtn 'testFull; mmlcPublishLocal'` passes 2,540 library tests and nine CLI tests, then installs
  the compiler locally. No tests fail; the test reports contain no skipped tests.
- `sbtn 'run run -I --build-dir "build/review space" tests/smoke/hola.mml'` exits 0 and prints
  `Hola, fede` and `Hola, pepe`, covering CLI compilation and execution with optimized IR enabled.
- `make -C benchmark clean` and `make -C benchmark mml` pass, building all 37 MML binaries
  with the locally installed compiler. This verifies compilation, not execution timings.
- QA compliance and independent review pass with no actionable findings. The primary review and
  all four separate checks are complete: code style/QA, test quality, human readability, and
  code documentation. No candidate findings required claim verification.
- Task/tracker consistency, all 30 local file links, and `git diff --check` pass.
- Memory-harness and ABI container gates do not apply: the change preserves tool argument
  boundaries without changing generated code, runtime behavior, ownership, or ABI rules.

### Earlier repairs

B30 documentation matches `buffer_write_bytes`, `flush`, and `__free_Buffer` in the runtime.
Independent documentation review found no actionable issues across QA/style, test applicability,
readability, and code documentation. Task/tracker consistency, all 27 local links in those records,
and `git diff --check` pass.
Compiler builds and runtime tests are not required for this documentation-only correction.

B1/B2 compiler checks and independent review pass. Verification evidence is recorded in their
[repair task](lsp-missing-semantic-diagnostics.md#evidence-and-verification).

## Signoff

- Workstream signoff: Ongoing note collection approved.
- B30 documentation correction: signed off; local commit authorized.
- B31 spaced build-path repair: signed off; local commit and delivery to `main` authorized.
- B33 file-URI decoding: signed off; local commit and delivery to `main` authorized.
- B34 custom-output execution: signed off; local commit and delivery to `main` authorized.
- Tracked item completion: Pending triage.
- Commit authorization: granted for the existing notes and follow-up definitions.

## Task Working Memory

B1/B2 diagnostic-delivery mappings and exhaustive semantic conversion are complete, verified,
independently reviewed, and signed off.
B23's parser repair, compiler verification, independent review, and Author signoff are complete.
B30's documentation correction is complete, verified, independently reviewed, and signed off.
B31's spaced build-path repair is complete, verified, independently reviewed, and signed off.
B33's file-URI decoding repair is complete, verified, independently reviewed, and signed off.
B34 repair is implemented on `dev/b34-custom-output-execution`. `CompilerConfig.executablePath`
selects the absolute output path for both linking and execution. Custom outputs take precedence;
default names retain explicit-target suffixes. Four failing run regressions pass with the repair,
alongside two default-naming controls and two existing toolchain-path regressions.
Required compiler checks, focused QA/tracking checks, and independent review pass.
B34 is signed off; local commit and delivery to `main` are authorized. All recorded bugs are
resolved. Bug collection remains open without an active repair; new notes require triage.
