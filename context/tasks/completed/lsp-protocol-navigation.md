# Extend LSP navigation for protocols

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** feature
- **Priority:** MID
- **Created:** 2026-10-27
- **Target Branch:** dev/lsp-protocol-navigation

## Execution Checklist

1. [x] **complete** — Trace existing navigation and approve a bounded implementation plan.
2. [x] **complete** — Route protocol-member definitions to signatures and add implementation navigation.
3. [x] **complete** — Verify instance-specific references and preserve ordinary navigation.
4. [x] **complete** — Add readable navigation regressions and verify the LSP behavior.
5. [x] **complete** — Support all three plugins and verify Zed's chained navigation.
6. [x] **complete** — Complete compiler verification, independent correction review, and handoff.

## Problem

Protocol functions and operators need navigation between their use, their required signature,
and their concrete implementations. Selecting a concrete instance during type checking must
not make the protocol declaration disappear from the editor's navigation path.

## Outcome

| Starting point | Editor action | Destination |
| --- | --- | --- |
| A reference to a protocol function or operator | Go to Definition | The matching signature inside its protocol. |
| A protocol declaration | Go to Implementation | Its concrete instance declarations. |
| A signature inside a protocol | Go to Implementation | The corresponding member in each instance. |
| A concrete instance declaration | Find References | Uses selecting members of that instance. |
| A concrete instance member | Find References | Uses selecting that implementation. |

For example, a call to `describe` implemented by `Describe String` first leads to the `describe`
signature in `Describe`. From that signature, implementation navigation offers the String and
Int implementations. Finding references from the String implementation returns its uses without
including calls selecting the Int implementation. Operators follow the same rules.

## Scope

- Deliver after [Protocols](protocols.md).
- Support named functions and operators through the same navigation rules.
- Reuse protocol, signature, and instance binding identities; do not infer associations from
  generated names or add another AST traversal mechanism for each navigation direction.
- Preserve source ranges through instance lowering so jumps target written declarations.
- Preserve ordinary function/type navigation and signature-wide reference search.
- Keep compiler resolution and instance selection semantics unchanged.
- Deliver usable navigation through all three MML editor plugins: VS Code, Zed, and Neovim.

## Implementation Plan

1. Extend the existing registry association queries with member-to-signature, protocol-to-instance,
   and instance-to-member lookups. Keep source selection in `AstLookup`.
2. Route selected protocol call definitions to signature names, add implementation lookup using
   the existing cursor-target traversal, and expand instance-header references to its members.
   Preserve concrete-type navigation in instance headers and source-name destination spans.
3. Advertise and dispatch `textDocument/implementation` alongside definitions and references.
4. Add literal multi-instance function/operator regressions, exact source ranges, recovery and
   ordinary-navigation controls, plus capability and JSON-RPC dispatch checks.
5. Expose all three editor actions in each MML plugin. Flush VS Code's pending document changes
   before navigation, add Neovim commands and shortcuts, and verify Zed's native LSP integration.
6. Run formatting, lint, smoke, full tests, compiler publication, benchmarks, plugin checks,
   QA enforcement, and independent review.

Approval: full implementation, all editor plugins, verification, and task bookkeeping authorized.

## Verification

Verified on 2026-11-05 on macOS arm64.

`AstLookup` uses registry associations for signature definitions, implementation destinations,
and instance/member reference scopes. `LspHandler` advertises and dispatches implementation
requests. Navigation results are deduplicated and ordered by written source position.

`ProtocolNavigationTests` checks literal multi-instance functions and operators, exact spans,
aliases, distinct protocols sharing member names, callable lowering, error recovery, rejected-instance
exclusion, and ordinary navigation. `ProtocolNavigationLspTests` checks advertised capabilities and
framed JSON-RPC requests.

- Compiler smoke: `./tests/smoke/run.sh all`, 8/8 passed.
  A first run hit sbt client shutdown timeouts after two programs completed successfully;
  rerunning in a terminal passed with exit code zero.
- `sbtn scalafmtAll` and `sbtn scalafixAll` passed.
- `sbtn "testOnly mml.mmlclib.lsp.*"`: 118/118 passed.
- `sbtn testFull`: compiler library 2,630/2,630 and CLI 9/9 passed.
- `ProtocolNavigationTests`: 12/12 passed within the LSP and full suites.
- `sbtn mmlcPublishLocal` passed after smoke and full-suite verification.
- `make -C benchmark clean` and `make -C benchmark mml` passed; all 37 MML targets built.
  This checks benchmark compilation, not runtime performance.
- VS Code: `npm test`, 5/5 passed; `npm run build` passed.
- Neovim: headless command, attachment, and buffer-mapping checks passed.
- Zed: grammar checks passed all 16 corpus cases, reference programs, and queries;
  Rust formatting and wasm Clippy passed.
- `python3 tooling/test-protocol-navigation.py`: 22 standalone responses and five selection chains
  (20 further requests) passed through the published `mmlc lsp`, including capability checks and
  clean process exit. Each chain uses returned selection endpoints for the next request.
- Neovim 0.12.5: `tooling/nvim-syntax/test/navigation-server.lua` passed through the real LSP
  client, plugin commands/mappings, native cursor/quickfix destinations, and unsaved edits.
  Server exit code and signal were both zero. The reproducible headless commands, including
  writable test-state configuration, are in [the plugin guide](../../../tooling/nvim-syntax/README.md).
- Zed 1.27.0 GUI: Definition followed immediately by Implementation succeeds from selected names.
  `protocol-functions.mml` shows both `render` implementations; references from the selected Int
  member show its declaration and Int uses, excluding the Bool call. `protocol-operators.mml`
  selects all three `<+>` implementations. The corrected compiler is installed and Zed's MML
  language server was restarted for these checks.
- VS Code extension-host sessions were not exercised; adapter and shared-server checks passed.
- VS Code 0.2.0, Neovim, and Zed 0.1.0 plugins are installed. Installed VS Code runtime/language
  assets, all three Neovim files, and all nine Zed bundle files match the verified builds.
  The installed Neovim plugin loads with the installed `nvim-lspconfig` dependency and registers
  all three navigation commands. A headless check using the full user configuration stalled;
  full-configuration startup remains unverified.
- Independent review completed its four dedicated checks and identified missing rejected-instance
  navigation coverage. The regression passes, and a fresh narrow re-review completed all four
  dedicated checks with no actionable findings. The endpoint correction also completed all four
  checks; two independently confirmed source-span comment inaccuracies were corrected. A fresh
  comment-only re-review completed all four checks with no actionable findings.
- Final QA enforcement, whole-record tracking review, local-link checks, and `git diff --check`
  passed. Evidence is embedded here and reproducible from tests/guides.
- ABI and ownership semantics are unchanged;
  dedicated Linux ABI and memory-harness runs are not applicable.

## Signoff

- Workstream signoff: accepted for protocol navigation across all three editor plugins.
- Tracked item completion: complete; all six execution steps are verified.
- Implementation commit authorization: granted for the implementation and completion records.
- Delivery authorization: squash merge and push to `main`.

## Task Working Memory

Implementation uses registry identities and existing source spans, with source-ordered results.
VS Code flushes debounced changes before navigation; Neovim exposes native actions through
commands and buffer mappings; Zed uses its native actions and advertised LSP capabilities.
All three editor plugins and the corrected compiler are installed. Navigation accepts Zed's
selected-name endpoints with exact-token precedence. Zed 1.27.0 GUI checks pass for named members,
operators, and follow-up references; the full suite and benchmark builds pass.
Independent correction review and final QA are complete. Workstream signoff is accepted;
no implementation or verification work remains in scope. Verification limits are listed above.
