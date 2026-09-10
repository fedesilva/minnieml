# QA Misses Tracking document

## Agent Instructions

* When asked to present this list, present it as a numbered list and use whiteline spacing between them
  * This makes reading easier
  * Allows for a faster, menu style interaction


## Brittle string-name assertions in OwnershipAnalyzerTests

- Location: `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala:9-50`
- Current status: fresh, but the old `FIXME:QA` markers are gone and helper shape improved.
- Problem: Assertions still depend on hardcoded generated names such as `__free_String`,
  `__clone_String`, `__free_User`, `__free_Outer`, `__free_closure`, and `__owns_s`.
  This keeps ownership tests coupled to generated symbol spelling rather than stable semantic
  identity.
- Current helper state: local helpers now use shared test AST extractors and traversal helpers
  (`TXCall1`, `TXRefResolved`, `TXRefNamed`, `existsTerm`, `countTerms`), so the earlier deep
  wildcard-match smell is closed.
- Smell level: high
- Impact: high regression risk in ownership semantics tests, brittle refactors, potential false
  confidence from name-coupled assertions.
- Suggested direction: replace name-string assertions with resolved-id/type-aware helper predicates
  where stable identity exists. Keep generated-name checks only where the generated name itself is
  the behavior under test.
- Scope decision: file-only now; optional follow-up scan across semantic tests for `__free_`,
  `__clone_`, and `__owns_` hardcoded name assertions.

## try/catch blocks in LlvmToolchain (No Exceptions rule)

- Location: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala`
- Current sites: `:100`, `:139`, `:152`, `:186`, `:203`, `:532`, `:571`, `:813`, `:825`,
  `:882`, `:891`, `:923`, `:927`, `:946`, `:956`, `:977`, `:1005`, `:1011`, `:1014`,
  `:1016`.
- Current status: fresh.
- Problem: These wrap genuinely throwing JDK/process APIs, but the exception boundary is scattered.
  Some paths intentionally degrade to missing-tool or typed error results, while others ignore
  failures, including the existing `// TODO: do not swallow exceptions` near marker invalidation.
- Smell level: medium
- Impact: medium. Failures from `llc`/`clang`/`opt` invocations and file operations can be
  misreported or silently dropped, masking toolchain breakage during codegen.
- Suggested direction: wrap JDK/process calls once at the FFI boundary, using
  `IO.blocking(...).attempt` or an equivalent typed adapter, and return
  `Either[LlvmCompilationError, A]` to the rest of the pipeline. Where a best-effort operation
  genuinely ignores a failure, make that boundary explicit with a short present-tense comment.
- Scope decision: file-local refactor; coordinate with whoever owns the toolchain runner.

## while loop with mutable state in TypeChecker.topologicalOrder

- Location: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala:241-272`
  (`topologicalOrder`).
- Current status: fresh.
- Problem: Uses `mutable.Map`, `mutable.ListBuffer`, `mutable.Queue`, and
  `while queue.nonEmpty do` inside a core semantic pass. This is not one of the allowed mutable
  boundaries in `qa-rules-and-coding-style.md` rule 2.
- Smell level: medium
- Impact: medium. It works today, but normalizes mutable algorithms inside the semantic stage and
  complicates reasoning about pass purity and re-entrancy.
- Suggested direction: rewrite as tail-recursive Kahn's algorithm over immutable
  `Map[String, Int]` and an immutable queue/list, returning the sorted member IDs and cycle tail.
  If a mutable variant is genuinely justified for performance, leave a one-line boundary comment.
- Scope decision: function-local; no test contract changes expected.

## Unsafe `.head` usage in main compiler sources

- Current status: fresh; the older entry undercounted current usage.
- Current scan: 38 `.head` sites in main sources.
- Locations:
  - API/parser/AST: `api/FrontEndApi.scala:17,20`, `api/CompilerApi.scala:219`,
    `api/ParserApi.scala:32`, `ast/TypeUtils.scala:37`, `parser/expressions.scala:130`.
  - Semantic: `LoweredCaptureLayout.scala:29`, `DuplicateNameChecker.scala:37,44,51,212`,
    `TailRecursionDetector.scala:48`, `TypeChecker.scala:190,803,1122,1124,1126,1283`,
    `ExpressionRewriter.scala:506,585`, `MaterializationAnalyzer.scala:78,202,250`,
    `OwnershipAnalyzer.scala:851,1337,1559`.
  - Codegen: `PreCodegenValidator.scala:106`, `LlvmToolchain.scala:895`,
    `emitter/expression/Applications.scala:46,47`, `emitter/package.scala:191,215`,
    `emitter/NominalTypeNameResolver.scala:13`.
  - LSP/printing: `lsp/AstLookup.scala:625,753`, `lsp/SemanticTokens.scala:340`,
    `util/prettyprint/ast/Term.scala:105`.
- Problem: `.head` on `List`/`Seq` throws `NoSuchElementException` on empty input. Many sites have
  an upstream invariant, but the invariant is implicit at the call site.
- Smell level: medium-high in semantic/codegen paths, lower in pretty-printers and explicitly
  guarded parser paths.
- Impact: medium. A future refactor that relaxes an invariant can produce an opaque crash instead
  of a typed compiler error.
- Suggested direction:
  - For sites where emptiness is possible, switch to `headOption` and propagate through existing
    `Either`/`Option` plumbing.
  - For sites where emptiness is structurally impossible, encode the invariant in the type, such as
    `NonEmptyList`/`NonEmptyChain`, or destructure with pattern matching near the source of truth.
- Scope decision: triage per file; bundle by module rather than fixing all sites at once.

## Long lines (>100 chars) concentrated in error-printing utilities

- Current status: fresh, bottom priority.
- Locations (top offenders):
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/SemanticErrorPrinter.scala` -
    30 lines over 100 chars.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/ErrorPrinter.scala` -
    19 lines over 100 chars.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/yolo/inspect.scala` - 7 lines.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Member.scala` - 7 lines.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Type.scala` - 5 lines.
  - Smaller clusters remain in parser, codegen emitter, pretty-printer, and toolchain files.
- Problem: `coding-rules.md` mandates max line length 100. `scalafmt` should normally enforce this;
  persistent violations suggest either a config gap or long string interpolations that scalafmt
  cannot break.
- Smell level: low
- Impact: low. This is readability and rule conformance, not a behavior risk.
- Suggested direction: extract long interpolation pieces into named values or multiline
  `stripMargin` strings; verify `.scalafmt.conf` `maxColumn` is 100 and printers are not excluded.
- Scope decision: lowest priority; bundle as a single formatting pass when touching printers.
