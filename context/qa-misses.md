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
