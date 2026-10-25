# Bring the Zed plugin to VS Code feature parity

## Metadata

- **Owner:** Author
- **Status:** design
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-10-22
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Investigate diagnostics after compiler republishing and audit feature parity.
2. [ ] **planned** — Establish supported Zed command integration and approve a repair plan.
3. [ ] **planned** — Implement diagnostics and command parity in bounded stages.
4. [ ] **planned** — Verify the installed plugin against the parity checklist and update docs.

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
- [ ] Compare the VS Code command registrations and client behavior with Zed's adapter and
  native actions. Record supported equivalents and unresolved gaps.
- [ ] Define bounded fixes and the editor verification procedure; obtain implementation approval.
- [ ] Implement and verify each approved stage, then reconcile the parity checklist and docs.

Approval: pending for implementation.

## Verification

Runtime verification is pending. Required evidence includes the tested editor, plugin, and
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

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: not granted

## Task Working Memory

Next action: test diagnostic delivery across compiler republishing and explicit server restart,
then build the VS Code-to-Zed parity checklist. Diagnostics are reported working intermittently;
the republishing trigger and restart recovery remain unverified. Source inspection establishes
the command registration difference but does not identify the runtime failure's cause.
