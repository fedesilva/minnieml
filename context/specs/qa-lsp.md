# QA Report: LSP Codebase

**Date:** 2026-02-13  
**Scope:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/*` + LSP tests

Ticket: https://github.com/fedesilva/minnieml/issues/220

## Intro Notes

1. **Definition targets must be source-backed.**  
   Synthesized compiler artifacts (for example generated constructor bindings) do not exist
   in user source and therefore cannot be valid go-to-definition targets. If resolution
   points to a synthesized symbol, LSP must map to the corresponding source declaration
   (`struct`, `type`, `fn`, etc.) or report no source location.

These changes are expected to improve LSP precision (definition/references/tokens), error
mapping, and behavior around generated symbols.

## Verification Run

- Ran:
  ```bash
  sbt "testOnly mml.mmlclib.lsp.SemanticTokensTests \
  mml.mmlclib.lsp.FindDefinitionTests mml.mmlclib.lsp.FindReferencesTests"
  ```
- Result: **pass** (5 tests, 0 failures)

## Findings (By Severity)

### 1. High: Conditional keyword tokenization is brittle and fails on multiline / non-trivial spacing

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:279`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:291`

`keywordBetween` only emits tokens when both boundaries are on the same line.
`then` / `else` in multiline conditionals are silently dropped.
`keywordAtEnd` infers `end` using a strict arithmetic offset from span end.

**Impact:** inconsistent keyword semantic coloring in realistic formatted code.

### 2. Medium: Unresolved references get no semantic token (inconsistent coloring during edits/errors)

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:172`

If a `Ref` is unresolved or has multiple candidates, tokenization returns `Nil`.
During normal editing (transient unresolved states), this causes some references to lose
semantic coloring while others remain typed.

**Impact:** visible color “flicker” and apparent mismatch while typing.

### 3. Medium: Test suite does not cover multiline conditional and unresolved-reference tokenization

- `modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/SemanticTokensTests.scala:10`

Current test coverage checks only consistent coloring for repeated `println` refs. There are no tests for:

- multiline `if/then/else/end` keyword tokens
- unresolved references behavior

**Impact:** current regressions pass tests undetected.

## Repro Cases for Semantic Color Mismatch

### A. Multiline conditional keywords

```mml
fn main(): Int =
  if true then
    1
  else
    2
  end
;
```

Expected: `if`, `then`, `else`, `end` all tokenized as `keyword`.  
Likely current behavior: partial keyword coverage.

### B. In-edit unresolved ref state

```mml
fn main() =
  prinln "x";  // typo while editing
  println "y"
;
```

Expected: stable semantic class behavior for both call-like refs.  
Likely current behavior: first ref drops semantic token entirely.

## Other

- [ ] **conditional keyword tokenization is brittle**  on multiline, unresolved refs
  get no token. See `context/specs/qa-lsp.md` findings 2–3, 5.

## Recommendations

1. Add an LSP client test helper that can drive request/response flows against the server
   using known small MML samples. This should cover open/change/semantic-tokens/definition/
   references so behavior is validated end-to-end instead of only by AST-level unit tests.
2. Rework conditional keyword extraction to be source-driven (or parser-provided keyword
   spans), not inferred from neighboring spans.
3. Emit a fallback token class for unresolved refs (for example `variable`/`function`
   heuristic) to reduce edit-time color flicker.
4. Add targeted tests in `SemanticTokensTests` for:
   - multiline `if/then/else/end`
   - unresolved refs in partially invalid code
