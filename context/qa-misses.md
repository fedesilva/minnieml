## 2026-02-15 - Top-priority brittle string-name assertions in OwnershipAnalyzerTests

- Location: `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala:33`, `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala:47`
- Note: `FIXME:QA: brittle string match.`
- Problem: Assertions depend on hardcoded mem-fn names (`__free_String`, `__clone_String`) instead of stable semantic identity, making tests fragile to resolver/name changes.
- Similar in-file cases: `containsRefName` (`modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala:75`)
- Smell level: high
- Impact: high regression risk in ownership semantics tests, brittle refactors, potential false confidence from name-coupled assertions.
- Suggested direction: replace name-string assertions with resolved-id/type-aware helper predicates and avoid raw symbol-name equality as the primary oracle.
- Discussion needed: align on a semantic detection strategy for `__free_*` / `__clone_*` assertions (e.g. resolved-id and type-directed checks) before refactoring assertions broadly.
- Scope decision: file-only now; optional follow-up scan across semantic tests for `__free_`/`__clone_` hardcoded name assertions.

## 2026-02-15 - Brittle deep wildcard pattern matching in OwnershipAnalyzerTests helpers

- Location: `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala:26`
- Note: `FIXME:QA: General, matching with so many _,_ is an antipattern.`
- Problem: `countFreesOf` relies on a deep AST shape with many wildcard placeholders, so benign AST representation changes can break helper behavior or hide mismatches.
- Similar in-file cases: recursive matcher helpers in the same file (`containsFreeOf`, `containsCloneString`, `containsRefName`) that repeat low-level tree-walking patterns.
- Smell level: medium
- Impact: medium maintenance cost and test fragility during AST refactors; lower immediate regression risk than the hardcoded string-name assertions.
- Suggested direction: introduce small local extractors/predicates for the target call forms and centralize traversal logic to reduce shape-coupling.
- Decision note: for repeated noisy AST match patterns across nodes, default to extractors; if extraction is unclear or costly, schedule a focused review and document why an extractor is not used.
- Pattern discussion: tests repeatedly encode the same three intents with noisy AST matching:
  1) recursive tree traversal over `App|Expr|Lambda|TermGroup|Cond|Tuple`,
  2) call-shape detection (`App` with `Ref` fn + wrapped/single arg refs),
  3) wrapper unboxing (`Expr(_, List(term), _, _)`).
  Candidate test-local extractors/helpers: `Call1(fnRef,argRef)`, `RefNamed(name)`,
  `RefResolved(name,resolvedId)`, `Single(term)`, `LambdaBody(body)`, plus one shared term fold.
- Scope decision: file-only now; optional whole-codebase scan for repeated deep wildcard AST match helpers.

## 2026-05-18 - Exceptions thrown in compiler main sources (No Exceptions rule)

- Locations:
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala:23` — `throw IllegalStateException("Parser expected source-located node")` inside `locSpan` for `SourceOrigin.Synth`.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala:562` — `throw new Exception(...)` when runtime resource is not found (already self-flagged `FIXME:QA` at line 561).
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Conditionals.scala:144` — `throw new RuntimeException(s"Codegen error: ${err.message}")` (already self-flagged `FIXME:QA` at line 143).
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Conditionals.scala:148` — second `throw new RuntimeException(...)` (same FIXME context).
- Problem: Violates `qa-rules-and-coding-style.md §8 — No Exceptions, without exceptions`. The compiler is supposed to accumulate errors as typed values, not raise.
- Smell level: high
- Impact: high — any of these can crash the compiler instead of producing a diagnostic; they also normalize the pattern for new code.
- Suggested direction:
  - `expressions.scala`: surface `SourceOrigin.Synth` as an `Either[CompilerError, SrcSpan]` (or accumulate into the existing semantic-error channel) rather than throwing; callers already plumb error accumulation.
  - `LlvmToolchain.scala:562`: thread the failure through the existing `EitherT`/error-accumulating return type used in `LlvmToolchain` instead of raising.
  - `Conditionals.scala:144,148`: emitter has access to the codegen error channel — convert to error accumulation and return the existing failure type. Remove the two `FIXME:QA` comments once converted.
- Scope decision: per-site fix; do not bundle with unrelated refactors. Each call site has a local conversion path.

## 2026-05-18 - try/catch blocks in LlvmToolchain (No Exceptions rule)

- Location: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala:923`, `:946` (nested), `:1005` — `try { ... } catch { ... }` around external subprocess interaction. Related `// TODO: do not swallow exceptions` at `:1015`.
- Problem: Even though these wrap genuinely throwing JDK APIs (`ProcessBuilder` / IO), the current shape *both* uses exceptions for control flow *and* may swallow them (per the existing TODO). This conflicts with §8 and with the project's preference for accumulating typed errors.
- Smell level: medium
- Impact: medium — failures from `llc`/`clang`/`opt` invocations can be misreported or silently dropped, masking toolchain breakage during codegen.
- Suggested direction: wrap the JDK call once in a small adapter (e.g. `IO.delay(...).attempt` or `Either.catchNonFatal(...)`) at the FFI boundary and return a typed `Either[ToolchainError, A]` to the rest of the pipeline. Justify any remaining catch with a one-line comment per §2 (mutable/exception boundaries).
- Scope decision: file-local refactor; coordinate with whoever owns the toolchain runner.

## 2026-05-18 - while loop with mutable state in TypeChecker.topologicalOrder

- Location: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala:252-272` (`topologicalOrder`).
- Problem: Uses `mutable.Map`, `mutable.ListBuffer`, `mutable.Queue` and a `while queue.nonEmpty do` loop inside a core semantic pass. This is not one of the allowed mutable boundaries enumerated in `qa-rules-and-coding-style.md §2` (LSP stream parsing, FastParse instrumentation). Violates §1 (functional default), §2 (mutation only at explicit boundaries), §3 (prefer tail recursion / folds).
- Smell level: medium
- Impact: medium — works today, but normalizes mutable algorithms inside the semantic stage and complicates reasoning about pass purity / re-entrancy.
- Suggested direction: rewrite as a tail-recursive Kahn's-algorithm over immutable `Map[String, Int]` (in-degrees) and an immutable queue/list (e.g. `Vector` or `Queue`), returning `(sorted: List[String], remaining: Map[String, Int])`. Pattern matches recursion examples in `MmlWhitespace.scala`. If a mutable variant is genuinely justified for perf, leave a one-line comment per §2 explaining why and confine it behind a private helper.
- Scope decision: function-local; no test changes needed, contract is preserved.

## 2026-05-18 - Unsafe `.head` usage in main compiler sources

- Locations (representative; full list ~19 sites):
  - Semantic: `OwnershipAnalyzer.scala:772, 1258, 1478`; `TypeChecker.scala:190, 803, 1122-1126, 1283`; `ExpressionRewriter.scala:506, 585`; `DuplicateNameChecker.scala:37, 44, 51, 212`; `TailRecursionDetector.scala:48`.
  - Codegen: `PreCodegenValidator.scala:106`; `emitter/NominalTypeNameResolver.scala:13`; `emitter/package.scala:191, 215`; `emitter/expression/Applications.scala:43, 44`.
  - LSP: `lsp/SemanticTokens.scala:340`; `lsp/AstLookup.scala:625, 753`.
  - Other: `parser/expressions.scala:130` (guarded by `size == 1`, low risk); `util/prettyprint/ast/Term.scala:105`; `codegen/LlvmToolchain.scala:895`.
- Problem: `.head` on a `List`/`Seq` throws `NoSuchElementException` on empty input — re-introduces exceptions into hot paths and creates fragile invariants that are not visible at the call site. Conflicts with §1 (immutable/typed flows) and §8 (no exceptions).
- Smell level: medium-high in semantic/codegen paths, lower in pretty-printers.
- Impact: medium — most sites today have an upstream invariant making them safe, but the invariant is implicit. A future refactor that relaxes the invariant will produce an opaque crash instead of a typed error.
- Suggested direction:
  - For sites where emptiness is a real possibility: switch to `headOption` and propagate via existing `Either`/`Option` plumbing.
  - For sites where emptiness is structurally impossible: encode the invariant in the type (e.g. `NonEmptyList`/`NonEmptyChain` from Cats) so the `.head` becomes total. The semantic/codegen modules already import Cats.
  - Prefer destructuring (`case h :: _ =>`) over `.head` when ergonomics allow — makes the partiality explicit at the call site.
- Scope decision: triage per file; bundle by module rather than fix all 19 at once.

## 2026-05-18 - Long lines (>100 chars) concentrated in error-printing utilities

- Locations (top offenders):
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/SemanticErrorPrinter.scala` — 30 lines over 100 chars.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/ErrorPrinter.scala` — 19 lines.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/yolo/inspect.scala` — 7 lines.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Member.scala` — 7 lines.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Type.scala` — 5 lines.
  - Plus 2–4 lines each in `parser/members.scala`, `codegen/emitter/FunctionEmitter.scala`, `codegen/LlvmToolchain.scala`, `codegen/emitter/expression/Applications.scala`, `util/prettyprint/ast/Term.scala`.
- Problem: `coding-rules.md` mandates max line length 100. `scalafmt` should normally enforce this; persistent violations suggest either a config gap or long string interpolations that scalafmt can't break.
- Smell level: low
- Impact: low — readability and rule conformance.
- Suggested direction: extract long interpolation pieces into named `val`s or `s"""...""".stripMargin` blocks; verify `.scalafmt.conf` `maxColumn` is 100 and that printers aren't excluded.
- Scope decision: low priority; bundle as a single formatting pass.

## 2026-05-18 - `println` calls in `util/yolo` debug utilities

- Locations:
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/yolo/codegen.scala:13, 18, 19, 20`
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/util/yolo/inspect.scala:65-98` (~14 calls)
- Problem: Direct stdout writes in library code. The `yolo` package is explicitly an exploratory/inspection helper, so this is borderline rather than a clear violation — but it sidesteps the rest of the compiler's effectful/IO discipline and there is no comment justifying the boundary as §2 asks for.
- Smell level: low
- Impact: low — package name communicates intent, but anything outside the REPL/inspector that calls in inherits the I/O leak.
- Suggested direction: either (a) keep as-is and add a one-line header comment per file declaring the yolo package as an allowed I/O boundary, or (b) thread an output sink (`PrintStream` / `Show`-based pretty printer) so callers can capture output. (a) is the minimal-change option.
- Scope decision: defer; flag during any cleanup of `util/yolo`.

## 2026-05-18 - Orphan TODO/FIXME comments in compiler main paths

- Locations:
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala:1015` — `// TODO: do not swallow exceptions` (tied to the try/catch finding above).
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Literals.scala:99` — `// FIXME: Generalize, String is just a native record`.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Literals.scala:100` — `// FIXME: Uses so many hardcoded types!`.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala:73` — `// TODO: Why not define this within the members?`.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/Simplifier.scala:87` — `// TODO: How to apply typeAsc to non-Expr terms?`.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/types.scala:38` — `// TODO: make this use Field`.
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/types.scala:74` — `// TODO: not all decls are typeable...`.
- Problem: These are free-floating TODOs/FIXMEs without tracking-item links. `coding-rules.md §Comments` discourages leftover "fix that / removed this" comments, and tracked work belongs in `context/tracking.md`.
- Smell level: low
- Impact: low — orientation/code-archaeology noise. The String/codegen ones (Literals.scala) are real design debt that should be tracked.
- Suggested direction: for each: either resolve, convert to a `FIXME:QA` (so `qa-scan` picks it up), or open a tracked item in `context/tracking.md` and reference it in the comment.
- Scope decision: triage as a single pass; ~5 minutes per item.

## 2026-05-18 - Test FIXME notes (TypeChecker, FindDefinition)

- Locations:
  - `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeCheckerTests.scala:187` — `// FIXME see BaseFunSuite ...`.
  - `modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/FindDefinitionTests.scala:163` — `// TODO: fix-ctor-gotodef: ...`.
- Problem: Open test-side notes without tracking-item links. Same shape as the prior entry; called out separately because tests are governed by §6 (Assertion Rules).
- Smell level: low
- Impact: low.
- Suggested direction: convert to `FIXME:QA` (so qa-scan picks them up) or open tracked items.
- Scope decision: bundle with the orphan-TODO pass above.
