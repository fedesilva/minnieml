# Explicit type-directed completion

## Metadata

- **Owner:** Author
- **Status:** design
- **Kind:** feature
- **Priority:** MID
- **Created:** 2026-10-22
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Define expression selection, candidate matching, and completion edits.
2. [ ] **planned** — Prototype the interaction in VS Code, Zed, and Neovim.
3. [ ] **planned** — Implement server completion and editor invocation under an approved plan.
4. [ ] **planned** — Verify matching, edits, and explicit invocation across all three editors.

## Tags

- lsp
- completion
- editor-integration

## Problem

Users need to discover functions applicable to an expression without knowing the function
name. Ordinary name completion does not provide the equivalent of exploring operations
on a receiver in a member-completion menu.

## Outcome

An explicit command invoked immediately before or after an expression offers visible
functions whose first parameter accepts the expression's type. Include both unary functions
and functions with further parameters. Accepting a candidate produces ordinary MML function
application, preserving the expression as its first argument.

For a String binding named `s`, invocation at either `s <cursor>` or `<cursor> s` offers
compatible functions. Selecting a unary candidate named `reverse` produces `reverse s`.
Selecting a candidate with additional parameters places the cursor for the next argument.
These names illustrate completion behavior; they do not assert a standard-library API.

## Scope

- Support VS Code, Zed, and Neovim through the shared MML language server.
- Provide a keybindable explicit invocation, with Ctrl+. as the intended default.
- Configure the MML experience to avoid unsolicited completion popups.
- Match first-parameter compatibility using compiler type semantics, including compatible
  generic functions where supported by the language.
- Display function signatures and remaining parameters in the candidate list.
- Preserve expression grouping and surrounding source when applying a completion.
- Keep MML syntax and application semantics unchanged. No dot, pipeline, or hole syntax
  is required for this feature.
- Exclude AI-generated implementations and searches for arbitrary argument positions.

## Plan (Approval Gate)

- [ ] Define which adjacent expression is selected, including whitespace, nested applications,
  parenthesized expressions, and ambiguous positions.
- [ ] Define visibility, type compatibility, generic matching, and ownership presentation.
- [ ] Prototype standard LSP completion edits and filtering in all three clients. Check
  multiline expressions, cursor placement, and completion while the document is incomplete.
- [ ] Verify editor command and keybinding support, including Ctrl+. conflicts and terminal
  delivery in Neovim. Resolve limitations before committing to a uniform interaction.
- [ ] Prepare bounded implementation stages for server behavior, editor integration, and tests.

Approval: pending for implementation; the required interaction and syntax constraint are fixed.

## Verification

Implementation verification is pending. Acceptance checks must cover:

- Equivalent candidate discovery before and after the same expression.
- Unary and multi-parameter candidates, incompatible first parameters, visibility, shadowing,
  and compatible generic signatures.
- Correct insertion or replacement, required parentheses, and remaining-argument cursor placement.
- Incomplete documents and positions without a usable expression or type.
- Manual invocation, filtering, acceptance, cancellation, and absence of unsolicited popups
  in VS Code, Zed, and Neovim, with tested editor versions recorded.

## Risks / Notes

- LSP completion's primary text-edit range must contain the request position and occupy one
  line. Multiline expression handling needs a verified edit strategy.
- Clients filter candidates using completion text and replacement ranges. Replacing `s`
  must not accidentally hide a candidate named `reverse`.
- Standard completion requests do not identify an arbitrary custom invocation mode; define
  how expression-directed discovery coexists with ordinary name completion.
- Editor extension APIs and terminal key handling may require different invocation wiring.
  Identical behavior across clients is a verification requirement, not an established fact.
- Protocol reference: [LSP completion specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/language/#textDocument_completion).

## Signoff

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: not granted

## Task Working Memory

The next action is to settle expression selection and candidate matching, then prototype
completion edits in the three clients before requesting implementation approval.
Relevant entry points are `LspHandler.scala` and `LspTypes.scala` under
`modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/`, plus `tooling/vscode/`, `tooling/zed/`,
and `tooling/nvim-syntax/after/plugin/mml_lsp.lua`.
