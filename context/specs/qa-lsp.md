# QA Report: LSP Codebase

**Date:** 2026-02-21  
**Scope:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/*` + LSP tests  
**Ticket:** https://github.com/fedesilva/minnieml/issues/220

## Freshness Verification

This document was refreshed against current source and tests on 2026-02-21.

- Previous version date: 2026-02-13.
- Current focused LSP test run:
  ```bash
  sbt "testOnly mml.mmlclib.lsp.FindDefinitionTests \
  mml.mmlclib.lsp.FindReferencesTests \
  mml.mmlclib.lsp.SemanticTokensTests \
  mml.mmlclib.lsp.DiagnosticsTests \
  mml.mmlclib.lsp.LspLoggingTests"
  ```
- Result: **pass** (`18` tests, `0` failures).

## Status Of Prior Findings

### 1. Conditional keyword tokenization brittleness

Status: **still valid**.

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:307`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:323`

`keywordBetween` still requires same-line boundaries and can drop multiline `then`/`else`.
`keywordAtEnd` still uses inferred offsets from expression spans.

### 2. Unresolved references get no semantic token

Status: **still valid**.

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:189`

When a `Ref` cannot be uniquely resolved, tokenization still returns `Nil`, causing edit-time
color instability.

### 3. Semantic token test coverage gaps from prior report

Status: **partially stale**.

`SemanticTokensTests` now cover more scenarios than before (not just repeated `println`), including:

- ownership-wrapper token behavior
- string literals in call arguments
- synthesized symbols exclusion from workspace symbols

But there is still no targeted test for multiline `if/then/else/end` tokenization, and no explicit
test for unresolved references in partially invalid code.

## Current Findings (By Severity)

### 1. High: `shutdown` stops server loop before `exit`

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala:92`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala:46`

Handling `shutdown` sets `shutdown = true`, which ends the main loop immediately. This can terminate
the server before the client sends `exit`.

Impact: protocol-lifecycle mismatch, potential client-side "server crashed/stopped unexpectedly"
behavior.

### 2. High: URI to path conversion is unsafe for encoded/special characters

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala:492`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala:517`

`uriToPath` strips `"file://"` instead of parsing URI semantics. This is brittle for encoded paths
(spaces, unicode, `%xx`) and platform edge cases.

Impact: `workspace/executeCommand` operations (`compile*`, `ast`, `ir`) can fail on valid URIs.

### 3. Medium: JSON-RPC read/parse errors hard-stop the server

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala:53`

Any `Left(error)` from `JsonRpc.readMessage` currently triggers `shutdown = true`.

Impact: one malformed or partial frame can kill an otherwise recoverable session.

### 4. Medium: Missing request-loop integration tests for handler/protocol lifecycle

No tests currently exercise end-to-end `JsonRpc` + `LspHandler` message flow for:

- initialize -> shutdown -> exit lifecycle
- parse error handling behavior
- URI decoding edge cases in execute-command paths

Impact: core transport/lifecycle regressions can pass current tests.

### 5. Low: Stale TODO in `FindDefinitionTests`

- `modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/FindDefinitionTests.scala:163`

The TODO says constructor go-to-definition is a known failing bug, but the corresponding test now
passes in the current suite.

Impact: misleading maintenance signal.

## Recommendations

1. Split shutdown state into "shutdown requested" vs "terminate loop", and exit only on `exit`
   notification.
2. Replace manual URI string stripping/building with `java.net.URI` and `Path#toUri`.
3. Make read-error handling more granular: treat EOF as termination, malformed frames as recoverable
   parse errors where feasible.
4. Add integration tests for handler lifecycle and JSON-RPC framing.
5. Add targeted semantic-token tests for:
   - multiline `if/then/else/end`
   - unresolved refs in partially invalid code
6. Remove or reword stale TODO comments once the associated behavior is verified green.
