# QA misses

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-12
- **Target Branch:** Unassigned
- **External Reference (optional):** None

## Execution Checklist

1. [ ] **planned** — Select a pending item and approve its bounded repair plan.
2. [ ] **planned** — Implement, verify, and review the selected repair.

## Purpose

Collect concrete QA findings without interrupting ongoing work to fix each one. Select items
individually, group related items into a cleanup session, or create a dedicated task when an
item needs its own plan. A cleanup campaign can work through several approved groups.

Completed findings and their resolution evidence are in
[Completed QA misses](../history/qa-misses-completed.md).

## Recording and selection

- Give each finding a stable ID, a descriptive title, source links, the problem, and an
  expected outcome. Distinguish observed behavior from suspected consequences.
- Recording an item does not start its implementation. Agree on a bounded plan when selecting
  an item or group; follow the normal verification and signoff rules for the affected code.
- When an item becomes a separate task, link it here and keep implementation detail there.
- Mark an item resolved only with verification evidence. Keep its ID and a concise resolution
  so later references remain meaningful.

## Items

### QA-002: Grow string builders to fit the complete append

- **Status:** planned
- **Source:** [mml_runtime.c](../../modules/mmlc-lib/src/main/resources/mml_runtime.c),
  `string_builder_append`.
- **Problem:** The append path doubles capacity once, then copies the entire input and writes
  its terminator. A builder with capacity 16 and length 0 appending 100 bytes allocates only
  32 bytes before copying 100 bytes and writing at offset 100.
- **Evidence:** Direct code inspection of the growth, `memcpy`, and terminator-write sequence.
  No runtime reproduction has been run for this finding.
- **Expected outcome:** Capacity growth covers the existing contents, complete append, and
  terminator with checked size arithmetic; a large single-append regression verifies the bound.
- **Scope:** Deferred. Scalar ToString conversions do not use this builder; array conversion is
  also deferred.

### QA-010: Explain specialization records and test recursion through cached instances

- **Status:** complete
- **Implementation and verification:** [Generic functions S4](generic-functions.md#s4-fnmonomorphizer).
- **Source:** [FnMonomorphizer](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/FnMonomorphizer.scala)
  and [FnMonomorphizerTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/FnMonomorphizerTests.scala).
- **Problem:** The `Scope`, `Template`, and `Instance` comments omitted their local contracts.
  The integration suite did not prove that a cached callee still contributes recursion evidence;
  dependency-graph unit tests cannot detect omission of that call from the monomorphizer.
- **Expected outcome:** Explain lexical substitutions, generated identities, template emission,
  and pending instance bodies at their declarations. Prime a concrete instance before a
  changing-type caller and assert the specific recursion diagnostic.
- **Evidence:** Independent S4 review completed all four dedicated checks; four separate fresh
  verifiers confirmed these documentation and test-coverage findings. No behavioral defect
  was established by that review.
- **Resolution:** Comments and the cache-hit integration regression are implemented. All 31 focused S4/S5
  tests and the full 2,840-library/9-CLI suite pass. Fresh narrow review completed all four
  dedicated checks with no actionable findings. Author signoff is granted with S4.

### QA-011: Verify local specialization identities and capture execution

- **Status:** complete
- **Implementation and verification:** [Generic functions S5](generic-functions.md#s5-callables-and-ownership).
- **Source:** [FnMonomorphizer](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/FnMonomorphizer.scala),
  [FnMonomorphizerTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/FnMonomorphizerTests.scala),
  and [GenericCallableOwnershipTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericCallableOwnershipTests.scala).
- **Problem:** Semantic-success tests did not establish distinct recursive-local identities or
  execute a generic local that itself captures an enclosing value. Lambda-rewrite documentation
  omitted provisional capture reconstruction; unary-application documentation omitted its general
  purpose. Substantial term-rewrite alternatives lacked paragraph separation.
- **Expected outcome:** Assert local definition and use identities across specializations, execute
  capturing generic locals under O0/O3 sanitizers, and explain the rewrite contracts beside the code.
- **Evidence:** Independent S5 review completed all four dedicated checks. Five separate fresh
  verifiers confirmed the two coverage gaps and three documentation/readability findings. No runtime
  defect was established by that review.
- **Resolution:** Corrections pass all 31 focused S4/S5 tests and the full suite, including eight native
  O0/O3 ASan+LSan cases. Fresh narrow review completed all four dedicated checks with no actionable
  findings. Recursive generic-local capture execution covers scalar captures; the separate escaping
  closure fixture covers owned captures. Author signoff is granted with S5.

### QA-012: Remove duplicated runtime source-name declarations

- **Status:** planned
- **Source:** [LlvmToolchain.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala),
  `mmlRuntimeResourcePath` and `mmlRuntimeFilename`.
- **Note:** `TODO:QA the following two vars are the same. dedupe.`
- **Problem:** Two adjacent constants independently define `"mml_runtime.c"`, with separate
  comments presenting the same basename as two declarations. This is a basic code-quality miss:
  unnecessary duplication makes readers investigate a distinction the implementation does not
  need and creates two places to update one name.
- **Evidence:** Resource loading and source extraction use the same basename. Direct source
  inspection establishes the duplication; no runtime failure is claimed. Target-specific object
  and cache names in the same file have distinct inputs and purposes.
- **Expected outcome:** Use one clearly named runtime source filename for loading and extraction.
  Remove the redundant declaration and comment; keep names with actual target/cache distinctions.
- **Scope:** The runtime source-name declarations and their uses in `LlvmToolchain.scala`.

### QA-013: Report failures to invalidate the LLVM tools marker

- **Status:** planned
- **Source:** [LlvmToolchain.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala),
  `invalidateToolsMarker`, `executeCommand`, and `gatherLlvmInfo`.
- **Note:** `TODO:QA do not swallow exceptions`.
- **Problem:** When archiving the verification marker fails, the fallback deletion can also fail.
  Its empty catch discards that failure and returns normally. A surviving readable marker can
  cause a later `gatherLlvmInfo` call to skip tool verification. The original missing-tool error
  still reaches the caller; the additional cleanup failure is lost.
- **Evidence:** Direct inspection of the archive/delete fallback and marker acceptance path.
  [ToolchainFailureTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/ToolchainFailureTests.scala)
  covers successful invalidation, but does not exercise failure of both cleanup operations.
  No filesystem-failure reproduction has been run.
- **Expected outcome:** Make failed invalidation observable while preserving the original tool
  error. Define the marker-failure result explicitly and cover the archive/delete failure path.
- **Related cases:** `markerHasTools` treats unreadable markers as unverified and triggers a fresh
  check. That fallback has a different consequence from silently retaining a trusted marker.
- **Scope:** Marker invalidation and its callers/tests. A repository-wide exception-handling
  review is separate.

### QA-014: Explain generic prefix arity and function-value checking

- **Status:** complete
- **Implementation and verification:** [Generic functions S6](generic-functions.md#s6-documentation-and-verification).
- **Source:** [RefResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/RefResolver.scala),
  `Context`; [TypeChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala),
  `checkGenericInstantiation`.
- **Problem:** The resolver environment did not explain that its local-function map stores binding
  IDs and type-parameter counts. The generic-checking comment omitted the selected type substitution
  and the distinction between a function value and a complete application chain.
- **Expected outcome:** Explain the map's role in selecting the type prefix and the checker's
  inputs, function-value path, substitution, and validation contract at their declarations.
- **Evidence:** The focused precedence review completed four fresh dedicated checks. Two separate
  fresh verifiers confirmed the documentation gaps against the producers and consumers of this
  state. No runtime defect was found.
- **Resolution:** Both declaration comments are corrected. The narrow review completed all four checks
  and separately confirmed one wording error: protocol requirements are satisfied by constraints
  or instances, while value arguments are checked against the instantiated signature. That sentence
  is corrected. Final fresh narrow review completed all four dedicated checks with no actionable
  findings. Formatting and whitespace pass; executable behavior is unchanged. Author signoff is
  granted with S6.

### QA-015: Explain retained templates at editor and ownership boundaries

- **Status:** complete
- **Implementation and verification:** [Generic functions](generic-functions.md#verification).
- **Source:** [SemanticTokens](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/SemanticTokens.scala),
  `collectTokens`; [OwnershipAnalyzer](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala),
  `ReturnOwnershipAnalysis.discover`.
- **Problem:** The token collector did not explain why indexed local templates bypass ordinary
  declaration-keyword inference. Return discovery did not state that its member list contains
  executable definitions while the shared index can retain checked generic templates.
- **Expected outcome:** Explain each operation's purpose and the template distinction locally,
  without requiring readers to reconstruct the contract from specialization and lowering callers.
- **Evidence:** Independent S4–S6 correction review completed all four dedicated checks. Two
  separate fresh verifiers confirmed these documentation gaps. No runtime defect was established.
- **Resolution:** Both comments are corrected; formatting and whitespace checks pass. Fresh narrow
  review completed all four dedicated checks with no actionable findings. Executable behavior is
  unchanged by these corrections. Author signoff is granted with S4–S6.

## Working memory

QA-012 and QA-013 are planned toolchain cleanup items; implementation plans remain to be selected.
