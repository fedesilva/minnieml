# Chrome extension for MML syntax highlighting

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** feature
- **Priority:** LOW
- **Created:** 2026-10-27
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Approve the extension design and bounded implementation plan.
2. [ ] **planned** — Package the shared Tree-sitter grammar and highlight queries for Chrome.
3. [ ] **planned** — Add automatic highlighting for GitHub source views and Markdown.
4. [ ] **planned** — Add user-activated MML fence detection on other sites.
5. [ ] **planned** — Verify browser behavior and document installation and use.

## Tags

- tooling
- chrome
- tree-sitter
- syntax-highlighting

## Problem

MML source views and fenced Markdown examples need browser syntax highlighting using
the same grammar as the Zed extension.

## Outcome

A Chrome extension automatically highlights `.mml` source views and MML fenced blocks
in rendered Markdown on GitHub. On other sites, the user activates the extension to
detect and highlight MML fences on a best-effort basis.

## Scope

- Reuse the [Tree-sitter grammar](../../tooling/tree-sitter-mml/grammar.js) compiled to WASM
  and the [Zed highlight queries](../../tooling/zed/languages/mml/highlights.scm).
- Bundle the browser Tree-sitter runtime, grammar, queries, and styles in the extension.
- Provide dedicated GitHub handling for `.mml` source renderings and MML Markdown fences.
- On other sites, detect explicit MML fence markers or their rendered equivalents, such
  as language classes and labels, only after user activation.
- Handle dynamically inserted or updated blocks without repeated self-triggered highlighting.
- Preserve source text, whitespace, selection, and copying.
- Exclude language inference from source syntax, compiler semantic highlighting, and LSP support.
- Chrome Web Store publication is outside this task.

## Plan (Approval Gate)

- [ ] Define GitHub adapters, generic fence detection, permissions, and activation behavior.
- [ ] Package a compatible WASM grammar and web-tree-sitter runtime with shared queries.
- [ ] Implement capture-to-style rendering and automatic GitHub integration.
- [ ] Implement user activation and best-effort fence detection for other sites.
- [ ] Verify representative pages and dynamic updates; document installation and limitations.

Approval: pending implementation plan.

## Verification

Pending implementation:

- Confirm the bundled runtime loads the grammar and executes the shared highlight queries.
- Check GitHub `.mml` source views and MML fences in rendered Markdown, including navigation.
- Check explicit MML fences and rendered language markers on other sites after activation.
- Confirm other sites remain untouched before activation and unmarked blocks remain untouched.
- Check dynamic updates, repeat activation, copy fidelity, whitespace, selection, and themes.
- Verify the extension runs with its packaged assets and intended permissions.

## Risks / Notes

The [Zed installer](../../tooling/install-zed-extension.sh) already builds the grammar with
`tree-sitter build --wasm`. Browser runtime compatibility still needs verification.
GitHub DOM changes may require adapter maintenance. Sites that discard fence language
information may not provide enough evidence for detection; leave those blocks unchanged.

## Signoff

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: granted for the task definition on `main`; synchronization authorized.

## Task Working Memory

The required behavior is automatic GitHub source and Markdown highlighting, plus
user-activated detection of explicit MML fences on other sites. Syntax-based language
guessing is excluded. Next action: prepare the extension design and implementation plan
for approval, including permissions and the supported fence representations.
