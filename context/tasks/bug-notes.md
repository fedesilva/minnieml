# Bug notes

## Metadata

- **Owner:** The Author
- **Status:** in_progress
- **Kind:** BUG
- **Priority:** unassigned
- **Created:** 2026-10-26
- **Target Branch:** main
- **External Reference:** None.

## Execution Checklist

1. [ ] **in_progress** — Collect confirmed bugs and update their fix status.
2. [ ] **planned** — Triage open notes and split them into focused tasks.

## Problem / Outcome

Keep bugs found during development visible without interrupting the active implementation.
These are short notes for later triage, not approved repair plans.

## Notes

Open notes are listed in priority order.

- **B31 — Open, P2: build-directory paths containing spaces fail LLVM assembly.**
  [LlvmToolchain](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
  interpolates paths into the string command `llvm-as $sourceFile -o $outputFile` and invokes
  `Process(cmd, dir)`, splitting spaced paths into separate arguments.
  `sbtn 'run -v --build-dir "build/review space" tests/smoke/hola.mml'` reproduces
  `llvm-as: Too many positional arguments`. The same source with a path without spaces passes;
  quoted direct assembly of the generated IR passes. A spaced final `--output` with a build
  directory without spaces also passes. Use the existing argument-vector execution API at
  the remaining string-command sites; this finding does not establish a final-output-path bug.

- **B33 — Open, P2: editor compile commands do not decode file URIs.**
  [LspHandler.uriToPath](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala)
  strips `file://` and passes the percent-encoded remainder to path-based compilation.
  The VS Code extension sends `uri.toString()` to binary, AST, and IR commands. Files named
  `demo#file.mml`, `demo file.mml`, and `demo%file.mml` are therefore opened using literal
  `%23`, `%20`, and `%25` names. JDK filesystem reproductions fail with `NoSuchFileException`,
  while URI-decoded paths and an ordinary filename succeed. Use shared URI decoding rather
  than prefix stripping. This is source tracing plus a filesystem reproduction; editor execution
  was not run.

### Resolved notes

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

## Verification

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
- Tracked item completion: Pending triage.
- Commit authorization: granted for the existing notes and follow-up definitions.

## Task Working Memory

B1/B2 diagnostic-delivery mappings and exhaustive semantic conversion are complete, verified,
independently reviewed, and signed off.
B23's parser repair, compiler verification, independent review, and Author signoff are complete.
B30's documentation correction is complete, verified, independently reviewed, and signed off.
Open notes are ordered B31, B33 and need tooling triage.
Next: prepare the B31 spaced build-path plan.
