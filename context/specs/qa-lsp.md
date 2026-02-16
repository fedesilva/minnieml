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

2. **Hard dependency for this work:**
- evolve the AST source model so `FromSource` distinguishes real source spans vs synthesized
  spans/origins

3. **Related architectural follow-up (out of scope for this QA pass):**
- implement names as explicit AST nodes (covered in a separate design document)

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

### 1. High: Semantic token declaration positions are guessed, not derived from AST spans

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:341`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:99`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:105`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:111`

`declarationToken` explicitly uses an approximation (`span.start.col + keywordLen + 1`),
and type/struct keyword/name placement is computed from hardcoded offsets. This is
incompatible with real parser spans for `TypeDef`, `TypeAlias`, `TypeStruct` when
visibility/doc comments/formatting vary.

`TypeDef` and `TypeAlias` spans start before `visibility` and `type`
(`modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/types.scala:35`,
`modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/types.scala:87`), and `TypeStruct`
span starts before doc comment + visibility
(`modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/types.scala:49`).

**Impact:** wrong token columns, missing/shifted semantic tokens, and perceived “same symbol has different colors”.

### 2. High: Conditional keyword tokenization is brittle and fails on multiline / non-trivial spacing

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:279`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:291`

`keywordBetween` only emits tokens when both boundaries are on the same line.
`then` / `else` in multiline conditionals are silently dropped.
`keywordAtEnd` infers `end` using a strict arithmetic offset from span end.

**Impact:** inconsistent keyword semantic coloring in realistic formatted code.

### 3. Medium: Unresolved references get no semantic token (inconsistent coloring during edits/errors)

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:172`

If a `Ref` is unresolved or has multiple candidates, tokenization returns `Nil`.
During normal editing (transient unresolved states), this causes some references to lose
semantic coloring while others remain typed.

**Impact:** visible color “flicker” and apparent mismatch while typing.

### 4. Medium: Test suite does not cover declaration-position and multiline conditional tokenization

- `modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/SemanticTokensTests.scala:10`

Current test coverage checks only consistent coloring for repeated `println` refs. There are no tests for:

- `type`/`struct` declarations with visibility/doc comments
- multiline `if/then/else/end` keyword tokens
- unresolved references behavior

**Impact:** current regressions pass tests undetected.

### 5. Medium: LSP handler and token encoding use mutable/imperative style inconsistent with FP ethos

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala:21`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/JsonRpc.scala:75`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:372`

Notable mutable state:

- `var initialized`, `var shutdown` in handler
- `while` + `return` loops in JSON-RPC reader
- `var prevLine`, `var prevCol` in delta encoding

These are not acceptable under the project FP standard and should be rewritten using
tail-recursive and expression-oriented forms to remove `var`/`while` control flow.

## Repro Cases for Semantic Color Mismatch

### A. Struct/type declaration offsets

```mml
pub type MyNum = Int;

/* Doc */
pub struct User { name: String };
```

Expected: semantic tokens for `type`, `struct`, `MyNum`, `User`, `String` at exact columns.  
Likely current behavior: shifted/missing declaration tokens.

note: this I can confirm is broken.

### B. Multiline conditional keywords

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

note: this works ok. write a test to validate.

### C. In-edit unresolved ref state

```mml
fn main() =
  prinln "x";  // typo while editing
  println "y"
;
```

Expected: stable semantic class behavior for both call-like refs.  
Likely current behavior: first ref drops semantic token entirely.

### D. Go-to-definition on struct constructor usage resolves to function, not struct

The constructor is synthetic (`__mk_<Name>`), so the LSP should resolve through it
to the `TypeStruct` declaration.

```mml
struct MinHeap {
  indices: IntArray,
  scores:  IntArray,
  capacity: Int
};

fn heap_new (cap: Int): MinHeap =
  MinHeap          // <-- go-to-def here should jump to struct MinHeap
    (ar_int_new cap)
    (ar_int_new cap)
    cap
;
```

Action: place cursor on `MinHeap` in the constructor call within `heap_new` and run
go-to-definition.
Expected: jump to `struct MinHeap { ... }` declaration.
Actual: jumps to the enclosing function definition (current `fn`), not the struct
declaration.

## Recommendations

1. Make `FromSource` provenance explicit (real source vs synthesized) in the AST and thread
   that through LSP definition/reference/token paths. This is a hard dependency for reliable
   source-backed navigation behavior.
2. Add an LSP client test helper that can drive request/response flows against the server
   using known small MML samples. This should cover open/change/semantic-tokens/definition/
   references so behavior is validated end-to-end instead of only by AST-level unit tests.
3. Replace positional guessing in `SemanticTokens` with span-derived coordinates from AST nodes only.
4. Rework conditional keyword extraction to be source-driven (or parser-provided keyword
   spans), not inferred from neighboring spans.
5. Emit a fallback token class for unresolved refs (for example `variable`/`function`
   heuristic) to reduce edit-time color flicker.
6. Add targeted tests in `SemanticTokensTests` for:
   - `type`/`struct` declarations with visibility + doc comments
   - multiline `if/then/else/end`
   - unresolved refs in partially invalid code
7. Required FP cleanup pass:
   - remove `var`/`while` in `LspHandler`, `JsonRpc`, and token delta encoding
   - use tail-recursive/state-threaded helpers and expression-oriented flow only

### 6. High: Synthesized constructor/destructor body traversal leaks overlapping tokens

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala:65`

`collectFromBnd` filters the keyword and name tokens for synthesized Bnds (span is synthetic,
`tokenAtPos` returns `None`). But it unconditionally calls `collectFromExpr(bnd.value, ...)`,
which traverses the constructor's body Lambda, DataConstructor, and type ascription. These
inner nodes reuse `struct.span` from the source struct declaration, producing stray tokens
at the struct's source position.

When the struct declaration happens to share a line position with user code (e.g., same column
offset), these stray tokens overlap with real tokens. Observed: `println ("Name: " ++ p.name)`
gets three tokens at col 9 — a length-11 function token (from the constructor body), a
length-1 parameter token, and the correct length-7 function token for `println`. The length-11
token extends over the `"Name: "` string literal, causing the editor to color it as a function.

**Fix:** `collectFromBnd` should skip body traversal when `bnd.source == SourceOrigin.Synth`.

## Functional Programming Ethos Review Summary

- **Good:** `DocumentManager` uses `Ref[IO, Map[...]]` and pure state updates
  (`modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/DocumentManager.scala:16`).
- **Needs alignment:** `LspHandler`, `JsonRpc`, and token encoding include imperative
  constructs that are locally practical but stylistically inconsistent with the rest of
  the compiler pipeline.
