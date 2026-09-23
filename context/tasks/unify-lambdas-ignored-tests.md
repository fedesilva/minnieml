# Ignored lambda regression audit

Supporting evidence for [Unify lambdas](unify-lambdas.md#restore-ignored-regressions).

- **Audit date:** 2026-09-23.
- **Branch:** `dev-lambda-unify`, HEAD `90851ac`, with the verified expression-preservation
  changes included in the completed expression-preservation slice.
- **Baseline:** 51 runner-discovered ignored tests across ten suites.
- **Result:** four pass unchanged; 47 fail. One failing case rejects the intended invalid
  program but expects an obsolete diagnostic name. The other 46 need assertion/API
  reconciliation or compiler investigation.
- **Repository test status:** four cases are enabled with unchanged assertions; 47 remain ignored.
- **Restoration verification (2026-09-23):** formatting and lint pass; full suite passes
  763 library tests and nine CLI tests, with 47 ignored. All four restored cases execute and pass.
  Log: `/tmp/mml-unignore-four-gates.log`. Test assertions and compiler implementation are unchanged
  by this restoration. Independent review reports no actionable findings; focused QA and
  tracking checks pass. The four-case restoration is signed off; commit and push are authorized.

## Method and limits

Temporary copies in `/tmp/mml-ignored-audit-20260923` use distinct suite names and replace
`.ignore` with `.only`. Test bodies, helpers, and assertions are unchanged in the main audit.
The original test-report names were matched against every audit result: 51/51 cases accounted
for, four passed, 47 failed, no errors or skipped cases. Original test-file hashes matched at the audit checkpoint.

Commands:

```sh
sbtn 'set mmlclib / Test / unmanagedSourceDirectories += file("/tmp/mml-ignored-audit-20260923");testOnly *IgnoredAudit*'
sbtn reload
```

The audit command exits nonzero because 47 tests fail. Its log is
`/tmp/mml-ignored-audit-20260923/run.log`; the result map and original-source hashes are
`results.json` and `manifest.json` in the same directory. SBT settings are restored afterward.
The audit left compiler and repository test sources unchanged. No historical compiler was rebuilt, so
these results establish present readiness rather than which commit first fixed each case.

This is semantic and emitted-IR test evidence on macOS arm64. It does not establish native
execution or sanitizer coverage for these individual cases. Failing IR assertions can reflect
old representation assumptions; they are not automatically 29 compiler defects. Placeholder
failures do not execute their preserved commented-out assertions.

## Restoration order

1. The four unchanged passing cases are enabled and their stale ignore explanations removed.
   Strengthening naming-sensitive checks remains a separate change.
2. Restore the borrowed-PAP escape case with a typed `BorrowClosureEscapeViaReturn` assertion
   through the existing semantic helpers. Its current string check expects
   `BorrowedPapEscapeViaReturn`, while the compiler reports `BorrowClosureEscapeViaReturn`.
3. Resolve each of the remaining 46 entries against the agreed model, preserving its semantic
   intent. Record a linked fix or an approved replacement/retirement for obsolete expectations.
   Re-enable each case with its corresponding repair, rather than accumulating working ignores.

## Inventory

| Disposition | Cases |
| --- | ---: |
| Restore placeholder assertion/helper | 14 |
| Enabled; assertions unchanged | 4 |
| Reconcile IR/lowering assertions | 29 |
| Nullary application rejection | 1 |
| Update diagnostic assertion | 1 |
| Reconcile heap-alias behavior | 2 |

Four rows are enabled; the other 47 remain pending. Lines below identify the audit snapshot,
not subsequent source line shifts.

### ClosureCodegenTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/ClosureCodegenTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| deferred lambda body preserves emitted metadata | 31 | Reconcile IR/lowering assertions |
| recursive Direct lambda calls its plain direct entry | 65 | Reconcile IR/lowering assertions |
| non-recursive named Direct lambda does not rebuild unused self closure | 103 | Reconcile IR/lowering assertions |
| Direct move lambda capturing a heap literal frees its clone once | 237 | Reconcile IR/lowering assertions |
| Direct move lambda capturing an owned String frees it once without cloning | 256 | Reconcile IR/lowering assertions |
| Direct move lambda capturing an owned struct frees it through its destructor | 277 | Reconcile IR/lowering assertions |
| loopified Direct move lambda frees its heap capture once before the back-edge | 299 | Reconcile IR/lowering assertions |
| loopified Direct lambdas do not allocate borrow envs | 324 | Reconcile IR/lowering assertions |
| loopified borrow closures stop being tracked after nested shadowing | 387 | Reconcile IR/lowering assertions |
| loopified borrow closure validation respects lambda parameter shadowing | 415 | Enabled; assertions unchanged |

### FunctionSignatureTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/FunctionSignatureTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| higher-order named function arguments lower as function values, not symbol loads | 296 | Reconcile IR/lowering assertions |
| repeated higher-order named function arguments reuse one closure entry | 325 | Reconcile IR/lowering assertions |
| mixed direct and higher-order top-level function uses keep both call shapes | 350 | Reconcile IR/lowering assertions |
| local Direct lambda calls use plain direct entry call | 407 | Reconcile IR/lowering assertions |
| shadowed local callable args do not eta-expand from top-level names | 435 | Reconcile IR/lowering assertions |
| nested Direct lambda threads outer Direct callable's captures | 800 | Reconcile IR/lowering assertions |
| loopified Direct closure captures Direct sibling operands as trailing params | 842 | Reconcile IR/lowering assertions |
| Direct move lambda capturing a heap literal clones at the binder site | 894 | Reconcile IR/lowering assertions |

### LambdaEquivalenceCodegenTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/LambdaEquivalenceCodegenTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| direct-only equivalent forms avoid closure materialization | 11 | Reconcile IR/lowering assertions |
| first-class non-capturing values use null-env closure values | 55 | Reconcile IR/lowering assertions |

### LambdaEquivalenceSemanticTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/LambdaEquivalenceSemanticTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| direct scalar calls have equivalent semantic shape | 82 | Restore placeholder assertion/helper |
| borrow-capture direct calls are equivalent to explicit top-level parameter passing | 121 | Restore placeholder assertion/helper |
| first-class non-capturing values use equivalent null-env materialization | 163 | Restore placeholder assertion/helper |
| consuming-use invalid programs report equivalent use-after-move class | 255 | Enabled; assertions unchanged |

### MaterializationAnalyzerTests

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/MaterializationAnalyzerTests.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| top-level fn called directly is direct | 53 | Restore placeholder assertion/helper |
| recursive self-call keeps fn direct | 72 | Restore placeholder assertion/helper |
| let-bound lambda used only directly is direct | 89 | Restore placeholder assertion/helper |
| let-bound lambda used as value is not direct | 104 | Restore placeholder assertion/helper |
| let-bound lambda used through partial application remains direct | 120 | Restore placeholder assertion/helper |
| lambda literal in immediate application is direct | 138 | Restore placeholder assertion/helper |
| nullary lambda literal in immediate application is direct | 163 | Nullary application rejection |
| let-bound nullary lambda used as value is not direct | 187 | Restore placeholder assertion/helper |
| nullary top-level fn invoked is direct | 203 | Restore placeholder assertion/helper |
| nullary top-level fn used as value is not direct | 215 | Restore placeholder assertion/helper |

### OwnershipAnalyzerTests

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| alias-typed allocating let binding is freed at scope end | 123 | Enabled; assertions unchanged |
| scalar-returning function that owns a local struct is not treated as returning the struct | 1176 | Enabled; assertions unchanged |

### ParsingErrorCheckerTests

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ParsingErrorCheckerTests.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| MemberErrorChecker should catch term errors nested inside expressions | 45 | Restore placeholder assertion/helper |

### TailRecursionLoopificationTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/TailRecursionLoopificationTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| named loopified function used as a value keeps a closure-entry wrapper | 62 | Reconcile IR/lowering assertions |
| local non-capturing loopified Direct function emits no closure-entry wrapper | 106 | Reconcile IR/lowering assertions |
| partial application of local loopified Direct function emits a PAP entry | 149 | Reconcile IR/lowering assertions |
| escaped partial application of local loopified Direct function uses heap env | 201 | Reconcile IR/lowering assertions |
| capturing partial application of local loopified Direct function tags PAP env fields | 317 | Reconcile IR/lowering assertions |
| escaped Direct PAP with borrowed heap capture is rejected by ownership | 387 | Update diagnostic assertion |
| Direct PAP with borrowed heap applied arg stores without cloning | 419 | Reconcile IR/lowering assertions |
| Direct PAP with aliased borrowed heap applied arg stores without cloning | 457 | Reconcile IR/lowering assertions |
| Direct PAP with consuming heap applied arg owns env field | 497 | Reconcile IR/lowering assertions |
| local capturing loopified Direct function uses trailing captures | 556 | Reconcile IR/lowering assertions |

### TbaaEmissionTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/TbaaEmissionTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| zero-field closure env TBAA is not emitted | 265 | Reconcile IR/lowering assertions |

### TypeUtilsTests

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeUtilsTests.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| heap native aliases resolve to the underlying memory functions | 120 | Restore placeholder assertion/helper |
| heap native aliases without explicit free use the underlying type name | 149 | Reconcile heap-alias behavior |
| heap struct aliases resolve to the underlying struct memory functions | 162 | Reconcile heap-alias behavior |
