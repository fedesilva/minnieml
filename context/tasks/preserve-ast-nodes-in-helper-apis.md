# Eliminate unnecessary AST field decomposition

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Created:** 2026-09-12
- **Target Branch:** dev-lambda-unify
- **External Reference (optional):** None

## Execution Checklist

1. [x] **complete** — Inventory compiler helper candidates and approve the migration plan.
2. [x] **complete** — OwnershipAnalyzer helpers and application reconstruction migrated,
   verified, independently reviewed, and signed off.
3. [x] **complete** — Migrate remaining compiler helpers; repeat the compiler-wide search
   and explain retained decomposition.
4. [x] **complete** — Full-scope verification and independent review passed;
   workstream signed off.

## Problem

Compiler helpers accept long lists of fields taken from the same AST node. This spreads the
node's structure across signatures and callers, even when the helper uses only part of it.
Passing the node directly makes that relationship explicit. Where helpers also reconstruct
the node, manual field forwarding adds the risk of losing metadata.

The migration starts with
[OwnershipAnalyzer.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala)
and covers full-node analysis, partial-field queries, and application reconstruction.
Passing every field is an extreme example, not a requirement for identifying the pattern.

## Outcome

Prefer the existing node when a helper receives several of its fields, whether or not it
uses every field or returns a rewritten node. More than two or three fields from one node
is a strong signal to pass the node. Even two or three warrant doing so when their meaning
is tied to that node and they have no useful independent source.

Keep a small set of separate arguments when the helper defines a useful independent operation
and those inputs can meaningfully come from other sources. Keep analysis context separate.
For rewrites, use `copy` to preserve unchanged fields. Apply this criterion throughout the compiler.

## Scope

- In scope: compiler helper definitions, callers, node reconstruction, and affected references.
- Out of scope: semantic changes, AST redesign, and unrelated ownership or construction fixes.

## Approved Plan

1. Pass `Lambda`, `App`, `Cond`, and `Tuple` nodes to OwnershipAnalyzer helpers;
   make `lambdaReturnType` accept a `Lambda`. Carry application nodes through argument
   analysis, keeping ownership facts separate. Use `copy` for rewrites and remove addressed
   `FIXME:QA` markers.
2. Run the required compiler gates and independent review. Pause for slice signoff.
3. Pass `Cond` to conditional codegen and existing bindings to tail-recursion helpers
   that receive their name and ID separately. Repeat the compiler-wide inventory and
   migrate additional candidates, including struct field lookup, or justify retained
   decomposition by each helper's contract.
4. Verify the remaining changes and reconcile the task's completion evidence.

Implementation plan approved. OwnershipAnalyzer slice signed off; remaining migration authorized.

### Inventory

- OwnershipAnalyzer: `analyzeLambdaApplication`, `analyzeLambda`, `analyzeCond`,
  `analyzeRegularApp`, `analyzeTuple`, `lambdaReturnType`, and application reconstruction
  through `ArgumentOwnership` and `AnalyzedArguments`.
- Conditional codegen: `Conditionals.compileCond` and its ExpressionCompiler caller.
- Tail recursion: binding-name/ID forwarding in TailRecursionDetector, FunctionEmitter,
  and ExpressionCompiler.
- LSP: `AstLookup.findReferenceTargetInStructFields` accepts the existing `TypeStruct`
  instead of forwarding its fields and name separately.

### Retained helper contracts

The final sweep covers all 114 production Scala sources under both compiler modules: helper
signatures, calls forwarding multiple fields of the same receiver, and pattern-match
forwarding. Each remaining AST-related candidate was checked against its callers.

| Helpers | Reason to retain separate inputs |
| --- | --- |
| TypeChecker parameter inference | Receives the filtered, updated untyped parameters and a body to inspect, not the original lambda's parameter list. |
| TypeChecker function-type builders and expression checking | Combine inferred or remaining parameter types, independently obtained return types, and expected-type context. |
| ExpressionRewriter precedence and application helpers | Consume successive term-list suffixes and combine new operands; the source can belong to an enclosing group. |
| CallableValues application collection | Threads a current callee and accumulated arguments from multiple application nodes. |
| OwnershipScope state updates | Combine binding identities with inferred allocation types, alias ownership, or move locations; they update analysis state rather than rewrite an AST node. |
| OwnershipAnalyzer cleanup construction | Consumes ownership records and generated witness information, not decomposed AST nodes. |
| Struct-layout calculation | Accepts field descriptions from native structs and MML structs, including resolved representations. |
| Native-type and operator emission | Defines a named native representation or evaluates an operator with its operands; these are independent emission operations. |
| Parser, LocalBindings, and standard-library constructors | Assemble new nodes from parsed or generated parts; there is no existing node to preserve. |
| Source-position queries, token emission, and printers | Combine queried positions, computed lengths, formatting options, or one node field with independent context. |

Configuration, compiler-state, analysis-result, and LLVM-value decomposition are outside
the AST-node scope. Unrelated constructor and error-boundary fixes remain excluded.

## Implementation Checklist

- [x] Replace unnecessary field lists with the existing node; update every caller.
- [x] Use `copy` for rewrites, preserving source, types, identities, captures, and other metadata.
- [x] Remove addressed `FIXME:QA` markers and update affected documentation references.
- [x] Repeat the compiler-wide search; inspect remaining candidates and explain any retained
  decomposition by the helper's actual contract.
- [x] Run applicable compiler checks and review the final changes for behavior preservation.

## Verification

### OwnershipAnalyzer slice

Verification on macOS arm64:

- `sbtn scalafmtAll` and `sbtn scalafixAll`: passed, with no compiler warnings.
- `sbtn test`: 636 passed, 0 failed, 51 ignored. Ignored tests remain verification gaps.
- Required `sbtn` sample checks passed: run `hola.mml`, `quicksort.mml`, `astar2.mml`,
  and `partial-fac1.mml`; compile `style-guide.mml`, `lambda-factorial.mml`, and
  `raytracer3_p6.mml`, all under `mml/samples/`.
- `sbtn mmlcPublishLocal`: passed after the sample checks.
- `make -C benchmark clean` and `make -C benchmark mml`: passed; seven benchmark
  programs built. This establishes native build success, not measured performance.
- `./tests/mem/run.sh all`: 41/41 passed with ASan+LSan (48 seconds).
- QA enforcement and focused tracking review: no actionable findings.
- Independent code review: no actionable findings. The reviewer checked node forwarding,
  metadata preservation, application order, ownership transitions, cleanup, diagnostics,
  and return-type precedence against the diff and verification logs.

Metadata preservation is checked against the AST definitions and changed rewrites:
lambda copies change only body and analyzed captures; application copies change argument
and callee; conditional copies change their three children; tuple copies change elements.
Sources, annotations, computed types, parameter identities, lambda metadata, and move flags
remain attached to the original node. Application collection and reconstruction retain
source evaluation order.

### Full-scope verification

- Final sequential `sbtn` batch passed: `scalafmtAll`, `scalafixAll`, `test`, the seven
  sample checks listed above, and `mmlcPublishLocal`. No compiler warnings or errors;
  636 tests passed, 0 failed, 51 ignored.
- `make -C benchmark clean` and `make -C benchmark mml` passed; seven programs built.
- `./tests/mem/run.sh all`: 41/41 passed with ASan+LSan (47 seconds).
- QA enforcement and focused tracking review: no actionable findings.
- Independent review of the remaining changes and inventory: no actionable findings.
  The reviewer checked affected callers, AST definitions, recursion and cleanup tests,
  verification logs, and retained helper contracts across the 114-source inventory.

Runtime evidence covers macOS arm64. Benchmark builds do not establish performance changes.
The remaining helper changes preserve the same conditional children, recursive binding
name/ID comparisons, and struct field targets; they do not reconstruct AST nodes.
Struct-field lookup preservation was checked statically; no dedicated test covers that helper.

## Risks / Notes

Judge the relationship between the arguments, their sources, and the helper's purpose; field
count is a guide, not a mechanical cutoff. Neither partial use nor absence of reconstruction
justifies a long list of fields from one node. Preserve useful independent operations and
pattern matching. Do not add wrapper types to replace the AST.

## Signoff

- OwnershipAnalyzer slice signoff: Approved.
- Workstream signoff: Approved.
- Tracked item completion: Complete.
- Commit authorization: Approved for the full migration and completion records.

## Task Working Memory

The full migration and compiler-wide inventory are complete and signed off. All applicable
execution gates passed; independent reviews found no actionable issues. Verification limits
are recorded above. No implementation or review work remains.
