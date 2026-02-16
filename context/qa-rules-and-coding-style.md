# QA Rules and Coding Style

This document defines coding and QA rules for compiler and test changes.
These rules are mandatory unless the Author explicitly overrides them.
If a rule is unclear or conflicts with the task, raise it and wait for direction.

## 1) Functional Default

- Prefer a strict functional style.
- `var` is discouraged and should be treated as a code smell.
- Model state as immutable data and return updated copies.
- Pass context/state explicitly through function parameters and return values; do not keep hidden mutable context.
- Prefer `copy(...)`-based updates on case classes over in-place mutation.

Examples in current codebase:
- Immutable state threading: `modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CompilerState.scala`
- Pipeline-style composition: `modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/SemanticStage.scala`

## 2) Mutation and Loops: Allowed Only at Explicit Boundaries

- Avoid `while` and mutable counters in normal compiler logic.
- Prefer `foldLeft`, recursion, or `@tailrec` helpers.
- Use mutation only when the API/protocol forces it (I/O streams, parser instrumentation, protocol handlers, tight decode loops).
- Any mutable boundary should include a short comment explaining why mutation is required.

Current boundary examples (allowed, but not style targets):
- LSP/JSON-RPC stream parsing and protocol state:
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/JsonRpc.scala`
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala`
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala`
- FastParse instrumentation callbacks:
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/ParserMetricsCollector.scala`

## 3) Prefer Tail Recursion and Folds

- Prefer tail-recursive helpers over manual index/counter loops.
- Prefer `foldLeft`/`foldRight` for accumulation and structural transforms.
- Use `@tailrec` on recursive functions where applicable.

Examples:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/MmlWhitespace.scala`
- `modules/mmlc-lib/src/test/scala/mml/mmlclib/test/TestExtractors.scala`

## 4) Cats Extensions and Option/Either Style

- Prefer Cats syntax extensions and combinators:
  - `.asRight`, `.asLeft`
  - `.some`, `.none`
- Prefer expression-oriented `Either`/`Option` flows over ad-hoc branching and sentinels.
- Keep error accumulation and propagation explicit and typed.

Examples:
- Parser/semantic/codegen modules consistently use Cats syntax imports and extension methods.

## 5) Pattern Matching and Extractors

- Avoid brittle deep wildcard matches such as long `case (..., _, _, _, _, ...)` structures.
- If the same AST-shape logic appears more than once, introduce small extractors/helpers.
- Prefer named intermediate values over unreadable tuple destructuring.
- In tests, use test-local extractors to encode intent and reduce shape-coupling.

Examples:
- Good direction: `modules/mmlc-lib/src/test/scala/mml/mmlclib/test/TestExtractors.scala`
- Good non-test extractor usage: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ExpressionRewriter.scala`
- Areas to improve: noisy deep matches in semantic tests, especially ownership helpers.

## 6) QA Assertion Rules

- Assertions should target semantic intent, not fragile representation details.
- Avoid hardcoded symbol-name checks as primary test oracle when stable semantic identity exists.
- Prefer resolved-id/type-aware assertions over raw string-name matching.
- Centralize repeated test traversal logic into shared helper functions or extractors.

Current QA debt reference:
- `context/qa-misses.md`

## 7) Practical Review Checklist

Before considering a change ready, verify:

- No avoidable `var`/`while` introduced in compiler core paths.
- Context/state is threaded explicitly and immutably.
- Cats syntax style is used consistently (`.asRight`, `.some`, etc.).
- Deep wildcard patterns were replaced with extractors/helpers when repeated or brittle.
- Tests assert semantics (resolved/type-aware) rather than brittle naming where possible.
