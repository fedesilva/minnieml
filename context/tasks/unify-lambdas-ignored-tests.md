# Ignored lambda regression audit

Supporting evidence for [Unify lambdas](unify-lambdas.md#restore-ignored-regressions).

- **Audit date:** 2026-09-23.
- **Branch:** `dev-lambda-unify`, HEAD `90851ac`, with the verified expression-preservation
  changes included in the completed expression-preservation slice.
- **Baseline:** 51 runner-discovered ignored tests across ten suites.
- **Audit result (2026-09-23):** four pass unchanged; 47 fail. One failing case rejects the
  intended invalid program but expects an obsolete diagnostic name. The other 46 need assertion/API
  reconciliation or compiler investigation.
- **Repository test status:** ten cases are enabled: four unchanged assertions and six
  emitted-IR replacements; 41 remain ignored.
- **Restoration verification (2026-09-23):** formatting and lint pass; full suite passes
  763 library tests and nine CLI tests, with 47 ignored. All four restored cases execute and pass.
  Test assertions and compiler implementation are unchanged
  by this restoration. Independent review reports no actionable findings; focused QA and
  tracking checks pass. The four-case restoration is signed off; commit and push are authorized.

## Method and limits

The audit executed copies of the preserved tests under distinct suite names, replacing
`.ignore` with `.only` while retaining their bodies, helpers, and assertions. It accounted
for all 51 original test names: four passed and 47 failed, with no errors or skipped cases.
The [inventory](#inventory) records each case and its disposition. The compiler and repository
test sources were unchanged by the audit. No historical compiler was rebuilt, so these
results establish readiness at the audit checkpoint rather than which commit fixed each case.

This is semantic and emitted-IR test evidence on macOS arm64. It does not establish native
execution or sanitizer coverage for these individual cases. Failing IR assertions can reflect
old representation assumptions; they are not automatically 29 compiler defects. Placeholder
failures do not execute their preserved commented-out assertions.

## Restoration order

1. The four unchanged passing cases are enabled and their stale ignore explanations removed.
   Strengthening naming-sensitive checks remains a separate change.
2. Restore the borrowed-PAP escape case with a typed `BorrowClosureEscapeViaReturn`
   assertion through `semState`.
3. Six materialization cases are enabled in `MaterializationCodegenTest`, preserving their
   original names and source fixtures. Verification and review are recorded below.
4. Resolve each of the remaining 41 entries against the agreed model, preserving its semantic
   intent. Record a linked fix or an approved replacement/retirement for obsolete expectations.
   Re-enable each case with its corresponding repair, rather than accumulating working ignores.

## Inventory

| Disposition | Cases |
| --- | ---: |
| Restore placeholder assertion/helper | 5 |
| Materialization lowering repair | 3 |
| Enabled; emitted-IR replacement | 6 |
| Enabled; assertions unchanged | 4 |
| Reconcile IR/lowering assertions | 29 |
| Nullary application rejection | 1 |
| Update diagnostic assertion | 1 |
| Reconcile heap-alias behavior | 2 |

Ten rows are enabled; the other 41 remain pending. Lines below identify the audit snapshot,
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

[Pending source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/MaterializationAnalyzerTests.scala);
[restored codegen source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/MaterializationCodegenTest.scala).

| Test | Line | Result / next action |
| --- | ---: | --- |
| top-level fn called directly is direct | 53 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| recursive self-call keeps fn direct | 72 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| let-bound lambda used only directly is direct | 89 | [Direct local-entry repair](#direct-local-entry-repair) |
| let-bound lambda used as value is not direct | 104 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| let-bound lambda used through partial application remains direct | 120 | [PAP target repair](#pap-target-repair) |
| lambda literal in immediate application is direct | 138 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| nullary lambda literal in immediate application is direct | 163 | Nullary application rejection |
| let-bound nullary lambda used as value is not direct | 187 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| nullary top-level fn invoked is direct | 203 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| nullary top-level fn used as value is not direct | 215 | [Function-value repair](#function-value-repair) |

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

## Materialization restoration evidence

- **Date:** 2026-09-24; branch `dev-lambda-unify`, HEAD `f432e18`.
- **Scope:** six emitted-IR replacements and plans for three compiler repairs. Source fixtures
  and test names match the preserved declarations byte for byte. The separate nullary
  immediate-application rejection remains pending and outside this slice.
- **Tests:** top-level calls, recursive self-calls, and explicit nullary calls require plain
  signatures without an environment parameter and no closure value at the call site. Immediate
  lambda application returns its argument without a call or closure value. Higher-order unary
  and nullary cases require a null-environment argument, a matching entry signature, and an
  indirect call using the entry and environment extracted from the same closure.
- **Focused verification:** `sbtn 'scalafmtAll;scalafixAll;testOnly *Materialization*'`
  passes six tests with four pending ignores.
- **Verification scope:** the full-suite run includes the separate uncommitted borrowed-PAP
  diagnostic restoration, which enables one additional test beyond this commit.
- **Full verification:** `sbtn 'scalafmtAll;scalafixAll;test'` passes 770 library tests and
  nine CLI tests, with 40 ignores and no warnings. QA and focused tracking checks pass; independent
  code review reports no actionable findings. The slice is complete and signed off.
- **Probe results:** all nine unchanged fixtures emit IR through `compileAndGenerate`.
  The six supported cases are executable in
  [MaterializationCodegenTest](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/MaterializationCodegenTest.scala).
  The other three source fixtures remain in
  [MaterializationAnalyzerTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/MaterializationAnalyzerTests.scala);
  their observed IR excerpts are included in the repair plans below. IR generation alone
  does not establish LLVM validity or runtime correctness.
- **Limits:** compiler implementation is unchanged. Smoke, publishing, benchmarks, and memory
  harness runs do not apply to this test-only slice. Local commit is authorized; push is not authorized.

## Materialization repair plans

**Status:** planned; compiler implementation approval is pending. Preserve all three ignored
source regressions until each has executable replacement coverage. The steps below follow
correctness-first delivery order and require separate bounded approval and signoff.

### Function-value repair

- **Case:** `nullary top-level fn used as value is not direct`.
- **Evidence:** the fixture initializes the function-valued global by loading `{ ptr, ptr }`
  from the emitted `nada` function symbol. It reads code as data instead of constructing a
  callable value. This is emitted-IR evidence; no native execution was performed.
  The observed initialization instructions are:

  ```llvm
  %0 = load { ptr, ptr }, ptr @test_nada, !tbaa !19
  store { ptr, ptr } %0, ptr @test_a
  ```

- **Relevant code:** `ExpressionCompiler.compileTerm` sends a non-local `Ref` through the
  global-load path; `expression.isDirectCallableRef` already identifies callable symbols by
  resolved binding. `ExpressionRewriter.wrapIfUndersaturated` only eta-expands when applied
  arguments are fewer than parameters, so it does not wrap a zero-parameter function.
- **Proposed repair:** distinguish callable symbols from function-valued storage by resolved
  identity at the value-lowering boundary. Give callable values an ABI-compatible entry and
  null environment; preserve ordinary loads for globals that actually store function values.
  Keep explicit `nada ()` on its plain call path. Settle wrapper reuse and native/template
  callable handling in the bounded implementation plan before editing compiler code.
- **Acceptance:** replace the ignored case with a function-value construction assertion;
  check global initialization, local aliases, higher-order arguments, returned values, and
  subsequent invocation. Verify symbol identity under shadowing, preserve consuming parameter
  contracts, and cover nullary and unary signatures. LLVM verification and native execution
  must show that invoking the stored nullary value returns `42` without calling it during
  value construction. Run the applicable compiler gates.

### Direct local-entry repair

- **Case:** `let-bound lambda used only directly is direct`.
- **Evidence:** the fixture calls the local entry with `i64` plus `ptr null`; the entry accepts
  an environment parameter. The call names a symbol, but still uses the closure ABI.
  `compileLocalBindingValue` routes lambda bindings to `compileLambdaLiteral`, and
  `compileRegularLambdaLiteral` appends the environment parameter regardless of later use.
  The observed caller and entry are:

  ```llvm
  define internal i64 @test_main(i64 %0) #0 {
  entry:
    %1 = call i64 @test_id_0(i64 %0, ptr null)
    ret i64 %1
  }
  define internal i64 @test_id_0(i64 %0, ptr %1) #0 {
  entry:
    ret i64 %0
  }
  ```

- **Proposed repair:** derive local callable usage through resolved binding IDs, including
  aliases and shadowing. Separate plain entry availability from materialized function-value
  use; both may coexist. Lower the non-capturing, saturated direct-call case to a plain entry,
  preserving closure entry behavior wherever a value is required. Specify the analysis phase,
  result representation, and mixed-use behavior before implementation; do not recreate a
  semantic distinction between scope lambdas and callable lambdas.
- **Acceptance:** restore the original fixture with a plain-entry assertion and no closure
  construction. Add alias, shadowing, recursion, and mixed direct/value controls. Capturing
  and escaping paths must retain ownership and lifetime checks. Run LLVM/native checks and
  the applicable compiler gates. General capture-operand optimization requires its own scope.

### PAP target repair

- **Case:** `let-bound lambda used through partial application remains direct`.
- **Dependency:** the direct local-entry repair must expose a safe, identity-based target
  representation that PAP lowering can use.
- **Evidence:** the fixture stores a `{ ptr, ptr }` value for `add` in the PAP environment;
  the residual entry extracts that closure and invokes it indirectly.
  `PartialApplicationElaborator.elaborate` constructs a residual lambda referring to the
  original local callee; capture discovery and codegen retain it as a function-value payload.
  The observed caller stores the original callable in field 1:

  ```llvm
  %3 = getelementptr %struct.__closure_env_0, ptr %1, i32 0, i32 1
  store { ptr, ptr } { ptr @test_add_0, ptr null }, ptr %3, !tbaa !23
  ```

  The residual entry loads that field and calls through the extracted pointers:

  ```llvm
  define internal i64 @test_addDummy_1(i64 %0, ptr align 8 dereferenceable(32) %1) #0 {
  entry:
    %2 = getelementptr %struct.__closure_env_0, ptr %1, i32 0, i32 1
    %3 = load { ptr, ptr }, ptr %2, !tbaa !23
    %4 = getelementptr %struct.__closure_env_0, ptr %1, i32 0, i32 2
    %5 = load i64, ptr %4, !tbaa !24
    %6 = extractvalue { ptr, ptr } %3, 0
    %7 = extractvalue { ptr, ptr } %3, 1
    %8 = call i64 %6(i64 %5, i64 %0, ptr %7)
    ret i64 %8
  }
  ```

- **Proposed repair:** let a PAP invoke a proven direct target without materializing the original
  callable as a closure. Preserve the derived PAP value, supplied-argument evaluation order,
  capture identities, and consuming/borrowing contracts. Define how target operands survive
  beyond their source scope before extending the optimization to capturing callables.
- **Acceptance:** restore the unchanged scalar fixture with assertions that the residual
  entry calls the plain original target and its environment has no redundant original-callable
  closure field. Preserve returned-PAP behavior, staged application, supplied and deferred
  consuming arguments, use-after-move diagnostics, and exactly-once cleanup. Run emitted-IR,
  native, sanitizer, and applicable compiler gates. Do not introduce implicit heap clones.
