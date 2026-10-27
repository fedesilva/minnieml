# Bring the Zed plugin to VS Code feature parity

## Metadata

- **Owner:** Author
- **Status:** in_progress
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-10-22
- **Target Branch:** dev/zed-commands

## Execution Checklist

1. [ ] **in_progress** — Command audit recorded below; diagnostic-loss investigation remains pending.
2. [x] **complete** — Approve extension tasks for compiler commands and native restart/log viewing.
3. [ ] **in_progress** — Compiler tasks and log following implemented; editor verification pending.
   - Confirm task discovery, save behavior, keybindings, restart, and log viewers in Zed.
4. [ ] **in_progress** — Usage docs updated and bundle installed; editor checks remain pending.

## Tags

- lsp
- zed
- plugins

## Problem

Compilation diagnostics appear to work in Zed, but have also been observed missing for
invalid code. Republishing `mmlc` while Zed is open is a suspected trigger, not a confirmed
cause. Investigate server lifecycle and recovery around compiler replacement.

The plugin is also reported not to expose commands to compile or restart the language
server. Users need the compiler feedback and operations available through VS Code.

The Zed README claims diagnostic support and documents the built-in
`editor: restart language server` command. Reproduce the installed behavior and reconcile
these claims with the actual user experience; the diagnostic failure's cause is unverified.

Reported reproduction: `mmlc -aI mml/samples/pap-ownership.mml` failed after an inline
completion inserted `text.reverse` and malformed closure syntax, but Zed displayed no
errors for the file. The compiler reported `String` has no field `reverse` at line 14,
and parser failures for `fn int_to_str(num: Int): ~{ fn(Int): String } =` and
`~{ extra -> read_length (int_to_str num) extra; };` at lines 17–18. Preserve these
invalid-input cases in reproducible tests; do not rely on the sample retaining these edits.

## Outcome

Zed provides working compilation diagnostics and discoverable, keybindable compiler and
server operations with functional parity to the VS Code integration. Successes and failures
are visible, and the documentation describes verified installed behavior.

## Scope

- Audit VS Code's implemented features and record an explicit parity checklist.
- Investigate intermittent diagnostic loss, particularly after compiler republishing, and
  repair confirmed delivery or lifecycle failures. Cover display, updates, and clearing.
- Cover the VS Code commands: restart server, compile binary, compile library, clean,
  generate AST, and generate IR.
- Check shared LSP features, server startup/configuration, and lifecycle recovery for gaps.
- Use native Zed actions where they provide equivalent behavior; investigate extension API
  limits and supported task/command mechanisms before choosing implementation details.
- Update installation and usage documentation for the verified behavior.
- Keep new completion functionality in [Explicit type-directed completion](lsp-type-directed-completion.md).
- Exclude MML language changes and unrelated VS Code feature additions.

## Plan (Approval Gate)

- [ ] Reproduce missing errors with a minimal invalid document, checking installed plugin and
  compiler versions, server attachment, document synchronization, and diagnostic publication.
- [ ] Establish working diagnostics, republish the compiler while Zed remains open, and
  introduce an error. Compare behavior before and after an explicit language-server restart.
  Capture server logs and process state to distinguish crashes, disconnection, stale server
  state, and diagnostic publication failures; do not assume a restart fixes the issue.
- [x] Compare the VS Code command registrations and client behavior with Zed's adapter and
  native actions. Record supported equivalents and unresolved gaps.
- [ ] Define bounded diagnostic lifecycle fixes and obtain their implementation approval.
- [ ] Implement and verify each approved stage, then reconcile the parity checklist and docs.

Approval: command-focused implementation and logging approved. Diagnostic lifecycle repairs
require a separate bounded plan; no compiler changes are included in the command stage.

## Command stage

The language extension ships `languages/mml/tasks.json`. The existing installer copies
all files in that directory into the extension bundle. No Rust adapter changes are needed.

The task-picker integration is accepted for the command delivery. Direct
`Cmd-Shift-P → MML` palette entries are unavailable through extension API 0.7.0;
see [Zed issue 18043](https://github.com/zed-industries/zed/issues/18043).
Native editor UI checks and the broader diagnostic investigation remain open.

| Operation | Zed integration |
| --- | --- |
| Compile binary | `MML: Compile to Binary` → `mmlc <active file>` |
| Compile library | `MML: Compile as Library` → `mmlc --target-type lib <active file>` |
| Clean | `MML: Clean Build Directory` → `mmlc clean` |
| AST | `MML: Generate AST` → `mmlc ast <active file>` |
| IR | `MML: Generate IR` → `mmlc ir <active file>` |
| Restart | Native `editor: restart language server` |
| Protocol logs | Native `dev: open language server logs` |
| Detailed server log | `MML: Follow Server Log` → `tail -n 200 -F build/lsp/server.log` |

Compiler tasks save the active file, run from the project root, and retain terminal output
and exit summaries. They use PATH, independently of `lsp.mml.binary` settings. The README
covers custom task commands, keybindings, generated-file locations, and the distinction
between protocol logs, compiler server logs, and task output.

### Command verification — 2026-10-26

Environment: macOS arm64, installed Zed 1.25.3, LLVM 23.1.1, installed mmlc
0.0.4-SNAPSHOT build `2026-10-26T01:33:10.292165Z-5badd0ed`.
The compiler was not rebuilt or published for this extension-only change.

CLI checks executed the task command/argument arrays with absolute file paths in an
isolated working directory. Valid input was copied from `tests/smoke/hola.mml`; invalid
input was copied from `mml/samples/type-error.mml`. These checks establish CLI behavior,
not Zed's task substitution, save behavior, or UI behavior.

- Library, AST, and IR succeeded on valid input and produced their expected files.
- All four compilation commands rejected invalid input with exit 1 and visible diagnostics.
- With Apple Clang 21 and Homebrew LLVM 23 mixed on PATH, executable generation failed:
  `LLVM ERROR: Unsupported stack probing method` (subprocess exit 134; CLI exit 1).
  Prepending the Homebrew LLVM 23 `bin` directory resolved this. All four compilation
  commands then passed valid input and rejected invalid input; the generated executable
  printed `Hola, fede` and `Hola, pepe` and exited 0.
- Clean succeeded, removed the isolated build directory, and preserved source files.
- Log following displayed existing content and a newly appended message.
- Two fresh `mmlc lsp` processes each completed initialize, didOpen with the invalid
  fixture, didChange with the valid fixture, shutdown, and exit. Diagnostic notifications
  were nonempty for invalid source and empty after correction. The server log contained
  startup and diagnostic-publication records. This is protocol-level evidence, not
  evidence of Zed display or its native restart action.
- `bash tooling/zed/test.sh` passed all 11 corpus cases, reference parses, and Zed queries.
  Tree-sitter reported missing parser-directory configuration and one slow-parse warning.
- `bash tooling/install-zed-extension.sh` built the grammar and Rust/Wasm adapter, then
  installed the extension. The installed task file matched the source byte-for-byte.
- `git diff --check` passed. Zed has not been restarted or exercised through its UI.
- A separate valid-input check with spaces in the working directory failed in `llvm-as`:
  `Too many positional arguments specified!` The CLI received one correct absolute source
  argument; failure occurred in its downstream tool invocation.

Reproduce compiler checks from an isolated directory using absolute paths to the two
versioned fixtures, running `mmlc <file>`, `mmlc --target-type lib <file>`,
`mmlc ast <file>`, and `mmlc ir <file>`, then `mmlc clean`. Repeat executable generation
in a directory containing spaces to reproduce the downstream argument-splitting failure.

The mixed-toolchain failure is resolved by the documented PATH setup; no compiler fix
was required. Native builds under directories containing spaces remain outside this stage.
Also, `mmlc clean` deletes
`build/lsp/server.log` while a running server retains its writer; restarting the server
recreates it. This follows directly from `LspLogging.create` and
`FileOperations.cleanOutputDir` and is documented in the README. Runtime recovery and
republishing remain part of the pending diagnostic investigation.

## Verification

Installed-editor verification is pending. Required evidence includes the tested editor, plugin, and
compiler versions and results for:

- Parse and type errors appearing at correct source ranges after opening and editing a file.
- Diagnostics clearing after correction and remaining functional after a server restart.
- Diagnostics before and after compiler republishing, with explicit restart as a separate
  recovery check. Record whether the suspected trigger reproduces and whether restart helps.
- Each parity command succeeding on valid input and reporting failures on invalid input.
- Discoverability and keybinding of compile and restart operations in the installed plugin.
- Existing navigation and semantic features continuing to work.

## Risks / Notes

- The Zed adapter in `tooling/zed/src/lib.rs` configures server launch; it does not register
  the MML command set exposed in `tooling/vscode/src/extension.ts` and its package manifest.
- `tooling/zed/README.md` documents diagnostics and native server restart, so missing runtime
  behavior must be distinguished from missing custom command registration.
- The VS Code README describes an older launch command than its implementation. Use the
  implementation as the parity reference and reconcile directly affected documentation.
- Zed extension capabilities may differ from VS Code. Surface any unmet parity requirement
  for a decision rather than silently narrowing scope.
- Relevant repository references: [Zed README](../../tooling/zed/README.md),
  [VS Code client](../../tooling/vscode/src/extension.ts), and
  [VS Code commands](../../tooling/vscode/package.json).

## Signoff

- Command-task and logging delivery: accepted.
- Broader parity workstream signoff: pending
- Tracked item completion: pending
- Commit and delivery authorization: command tasks, logging documentation, and related records.

## Task Working Memory

Command tasks and detailed-log following are implemented and installed; native restart and
protocol-log viewing are documented. All compiler commands pass the CLI smoke checks with
a consistent LLVM 23 toolchain. Direct LSP checks establish diagnostic publication and
clearing across fresh processes. The remaining path-with-spaces limitation is recorded above.
No compiler or VS Code code changed. Command-task and logging delivery is accepted,
including its task-picker limitation. The broader parity task remains open.

Next action: verify the packaged extension in Zed, including discovery, buffer saving,
keybindings, restart, protocol logs, and task output. Native UI automation is unavailable
in this environment, so those checks require an editor session. The intermittent
diagnostic-loss investigation remains open; process-level checks do not test compiler
republishing while Zed remains open.
