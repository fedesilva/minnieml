# Ignored lambda regression audit

Supporting evidence for [Unify lambdas](unify-lambdas.md#restore-ignored-regressions).

- **Audit date:** 2026-09-23.
- **Branch:** `dev-lambda-unify`, HEAD `90851ac`, with the verified expression-preservation
  changes included in the completed expression-preservation slice.
- **Baseline:** 51 runner-discovered ignored tests across ten suites.
- **Audit result (2026-09-23):** four pass unchanged; 47 fail. One failing case rejects the
  intended invalid program but expects an obsolete diagnostic name. The other 46 need assertion/API
  reconciliation or compiler investigation.
- **Repository test status:** fourteen cases are enabled: four unchanged assertions, one typed
  diagnostic assertion, and nine emitted-IR replacements; 37 remain ignored.
- **Restoration verification (2026-09-23):** formatting and lint pass; full suite passes
  763 library tests and nine CLI tests, with 47 ignored. All four restored cases execute and pass.
  Test assertions and compiler implementation are unchanged
  by this restoration. Independent review reports no actionable findings; focused QA and
  tracking checks pass. The four-case restoration is signed off; commit and push are authorized.

## Borrowed-PAP restoration verification

- **Date:** 2026-09-24.
- The source program is unchanged; `semState` checks for a typed
  `BorrowClosureEscapeViaReturn` diagnostic, and the case is enabled.
- `sbtn 'scalafmtAll;scalafixAll;test'` passes: 764 library tests and nine CLI tests,
  with 46 ignored and no warnings.
- QA and focused tracking checks pass; independent review reports no actionable findings.
  The subtask is complete and signed off; local commit is authorized and push is not authorized.
- Test-only change: compiler implementation and the source fixture are unchanged. No smoke,
  publishing, benchmark, or memory harness run applies to this slice.

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
2. The borrowed-PAP escape case is enabled with a typed `BorrowClosureEscapeViaReturn`
   assertion through `semState`; verification and independent review pass. The subtask is signed off.
3. Six materialization cases are enabled in `MaterializationCodegenTest`, preserving their
   original names and source fixtures. Verification and review are recorded below.
4. Resolve each of the remaining 37 entries against the agreed model, preserving its semantic
   intent. Record a linked fix or an approved replacement/retirement for obsolete expectations.
   Re-enable each case with its corresponding repair, rather than accumulating working ignores.

## Inventory

| Disposition | Cases |
| --- | ---: |
| Restore placeholder assertion/helper | 5 |
| Enabled; emitted-IR replacement | 9 |
| Enabled; assertions unchanged | 4 |
| Reconcile IR/lowering assertions | 29 |
| Nullary application rejection | 1 |
| Enabled; typed diagnostic assertion | 1 |
| Reconcile heap-alias behavior | 2 |

Fourteen rows are enabled; the other 37 remain pending. Lines below identify the audit snapshot,
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
| let-bound lambda used only directly is direct | 89 | Enabled in MaterializationCodegenTest; [optimization evidence](#direct-local-entry-optimization) |
| let-bound lambda used as value is not direct | 104 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| let-bound lambda used through partial application remains direct | 120 | Enabled in MaterializationCodegenTest; [PAP target repair](#pap-target-repair) |
| lambda literal in immediate application is direct | 138 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| nullary lambda literal in immediate application is direct | 163 | Nullary application rejection |
| let-bound nullary lambda used as value is not direct | 187 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| nullary top-level fn invoked is direct | 203 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| nullary top-level fn used as value is not direct | 215 | Enabled in MaterializationCodegenTest; [function-value repair](#function-value-repair) |

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
| escaped Direct PAP with borrowed heap capture is rejected by ownership | 387 | Enabled; typed diagnostic assertion |
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

**Status:** function-value repair and direct local-entry optimization are complete and signed off.
PAP target optimization is complete and signed off under its approved bounded plan. Preserve
each source fixture when replacing placeholder assertions. Each bounded change requires signoff.

### Function-value repair

- **Status:** complete; implementation, verification, and independent review pass.
- **Implementation plan:** distinguish emitted callables from function-valued storage at value
  lowering; emit and reuse null-environment adapters by resolved binding identity. Share closure
  entry emission and native/template call lowering. Preserve direct calls, ownership contracts,
  and unary eta-expansion. Restore the original fixture as codegen coverage, add ABI and runtime
  regressions, run compiler gates and independent review, and reconcile this inventory.
- **Boundaries:** no local direct-entry optimization, PAP target repair, nullary immediate-lambda
  repair, or whole-migration completion.
- **Case:** `nullary top-level fn used as value is not direct`.
- **Failure evidence before repair:** the fixture loaded `{ ptr, ptr }` from the emitted `nada`
  function symbol to initialize the global, reading code as data. The original emitted IR was:

  ```llvm
  %0 = load { ptr, ptr }, ptr @test_nada, !tbaa !19
  store { ptr, ptr } %0, ptr @test_a
  ```

- **Relevant code:** `ExpressionCompiler.compileTerm` uses `expression.isDirectCallableRef`
  to distinguish callable symbols from storage by resolved binding.
  `ExpressionRewriter.wrapIfUndersaturated` only eta-expands when applied arguments are fewer
  than parameters, so it does not wrap a zero-parameter function.
- **Implementation:** `ClosureEntries` emits null-environment adapters, reuses entries by
  resolved binding identity, and shares wrapper emission with non-capturing recursive lambdas.
  Shared typed-operand call emission preserves native ABI lowering and template substitution.
  Global initialization stores the literal closure operand. Explicit `nada ()` uses its plain entry.
- **Acceptance:** replace the ignored case with a function-value construction assertion;
  check global initialization, local aliases, higher-order arguments, returned values, and
  subsequent invocation. Verify symbol identity under shadowing, preserve consuming parameter
  contracts, and cover nullary and unary signatures. LLVM verification and native execution
  must show that invoking the stored nullary value returns `42` without calling it during
  value construction. Run the applicable compiler gates.

#### Function-value verification

- **Date:** 2026-09-28.
- `sbtn 'scalafmtAll;scalafixAll;test'`: 776 library tests and nine CLI tests pass;
  39 remain ignored; no compiler warnings.
- [FunctionValueCodegenTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/FunctionValueCodegenTests.scala)
  verifies adapter reuse across globals and deferred bodies, stored/global and local aliases,
  returned values, higher-order calls, shadowing, native symbol overrides, Unit and value
  templates, consuming pointer contracts, and x86-64/AArch64 native structure-return IR.
- All five new regression cases pass LLVM assembly. The host executable exits 0 and prints
  `0, 2, 1, 1, 91, 3, 42, 4, 4, 42, 1` on separate lines: the first target invocation follows
  construction markers `0` and `2`. Stored ordinary, native, and template functions return `42`.
  The fixture links a small C support file after dead-function elimination; this is host native
  execution, not cross-target execution or sanitizer evidence.
- The original nullary source fixture is enabled in
  [MaterializationCodegenTest](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/MaterializationCodegenTest.scala)
  with assertions for literal construction, a callable adapter, no code-as-data load, and no
  invocation or allocation during initialization.
- `./tests/smoke/run.sh all`: 8/8 pass. `sbtn mmlcPublishLocal` succeeds.
- `make -C benchmark clean` and `make -C benchmark mml` succeed; these are build checks,
  not performance measurements.
- `./tests/mem/run.sh all`: 44/44 ASan+LSan checks pass.
- Host: macOS arm64, Homebrew LLVM/clang 23.1.1. Native execution covers the host only;
  x86-64 evidence is emitted IR and LLVM assembly.
- Focused QA, tracking consistency, local links, and whitespace checks pass. A fresh independent
  code review reports no actionable findings after tracing callable identity, adapter reuse,
  deferred-state merging, global initialization, native ABI/template calls, consuming parameters,
  and recursive wrappers. No candidate findings required claim verification.
- **Signoff:** function-value repair accepted. Local commit is authorized for the repair,
  its regressions, documentation, and completion records. Push is not authorized.

### Direct local-entry optimization

- **Status:** complete; implementation, verification, and review pass. The bounded optimization
  is signed off with local commit authorization. Overall lambda migration remains open.
- **Restored case:** `let-bound lambda used only directly is direct` retains its original
  source fixture in `MaterializationCodegenTest`. The assertion discovers the local symbol from
  its call, checks a plain entry and call without an environment argument, and rejects closure
  construction. The ignored semantic placeholder is removed.
- **Implementation:** `LocalCallableAnalysis` runs once on the final typed module, identifying
  non-capturing local functions and simple aliases by binding ID. It distinguishes saturated
  direct calls from value uses and limits target knowledge to lexical scope. `ScopeEntry`
  distinguishes runtime operands from known callable targets. `LocalCallableEmitter` registers
  plain entries before compiling bodies and reuses tail-recursive emission. Value uses share
  one adapter per canonical binding through the existing closure-entry emitter, including
  uses in deferred bodies. Captures store the materialized closure value.
- **Boundaries:** this is an optimization, with no language or ownership changes. PAP
  direct-target optimization, capture elimination, and the owned-closure tail-recursion bug
  are excluded. Value-only locals, conditional selection, and field selection retain ordinary
  value lowering.
- **Coverage:** `LocalCallableCodegenTests` checks aliases, local and parameter shadowing,
  ordinary and tail recursion, TCO disabled, mixed and repeated value uses, returned values,
  captures, Unit effects, argument order, consuming parameters, and conditional callable
  values. Generated symbols are discovered from calls or closure operands. The suite assembles
  LLVM and executes fixtures through the existing toolchain test helpers.
- **Host verification (2026-10-05):** macOS arm64, Homebrew Clang/LLVM 23.1.1.
  `sbtn 'scalafmtAll; scalafixAll; test'` passes 844 library tests and nine CLI tests,
  with 38 existing ignores and no compiler warnings. The local-callable fixtures pass LLVM
  assembly and native exit-code assertions. `./tests/smoke/run.sh all` passes 8/8;
  `sbtn mmlcPublishLocal` installs the compiler; `make -C benchmark clean` followed by
  `make -C benchmark mml` builds all 12 MML benchmarks. `./tests/mem/run.sh all` passes
  45/45 ASan+LSan cases. No benchmark timing or speedup is claimed.
- **Linux arm64 verification:** `mml-linux-arm64`, native AArch64 execution in Docker,
  Ubuntu Clang/LLVM 20.1.8; 97 tests pass with 26 existing ignores.
  It includes native local-callable execution, the native C aggregate matrix, MML/C function-value
  and destructor interoperability, and Clang cross-compilation controls for all four supported
  targets. The `str_eq` native-symbol assertion accepts the target-dependent Boolean extension
  attribute; Linux AArch64 emits `i1`, while Apple AArch64 emits `zeroext i1`.
- **Linux amd64 verification:** `mml-linux-amd64`, Ubuntu Clang/LLVM 20.1.8, x86-64
  execution emulated on an arm64 host; the same selection passes 97 tests with 26 existing
  ignores. Both containers execute fixtures; cross-compilation alone is not the evidence.
  All required execution checks for this bounded optimization pass.
- **Review:** QA compliance, focused tracking review, added links, and whitespace checks pass.
  A fresh independent code review finds no actionable issues after inspecting the complete scoped
  diff, all three new Scala files, connected emission paths, technical documentation, and test
  helpers. No candidate finding requires independent claim verification. The review relies on
  the recorded execution results; it makes no performance claim.
- **Final documentation checks:** comments explain entry selection, capture storage, argument
  effects, and the state shared between emitted bodies. Executable Scala text matches the
  implementation verified above. Formatting, lint, whitespace checks, and a fresh independent
  review of the comment and design changes pass. The ignored test fixtures and assertions
  are unchanged by the documentation edits.

The container commands run sequentially with this suite selection:

```sh
suites=(
  mml.mmlclib.codegen.LocalCallableCodegenTests
  mml.mmlclib.codegen.MaterializationCodegenTest
  mml.mmlclib.codegen.FunctionValueCodegenTests
  mml.mmlclib.codegen.FunctionSignatureTest
  mml.mmlclib.codegen.TailRecursionLoopificationTest
  mml.mmlclib.codegen.ClosureCodegenTest
  mml.mmlclib.codegen.AggregateAbiTests
)
docker exec -w /workspace mml-linux-arm64 sbtn "testOnly ${suites[*]}"
docker exec -w /workspace mml-linux-amd64 sbtn "testOnly ${suites[*]}"
```

### PAP target repair

- **Status:** complete; implementation, required verification, and independent review pass.
- **Signoff:** PAP target preservation and descriptive analysis/layout names are accepted;
  local commit is authorized. Overall lambda migration remains open; push is not authorized.
- **Case:** `let-bound lambda used through partial application remains direct`.
- **Approved plan:** preserve proven entry identity independently of each closure's environment.
  Include capturing and non-capturing targets. Use direct calls when complete value-flow evidence
  identifies one entry; otherwise preserve indirect calls. Follow aliases, parentheses, PAP chains,
  captures, returns, constructor fields, and complete same-entry conditional merges. Unknown sources
  and branches remain unknown; observed callers do not specialize function parameters.
- **Storage:** omit a known target's null-environment closure field in PAPs; retain only the
  environment pointer for known capturing targets. Share one physical plan for declarations,
  allocation, stores, loads, destruction, alignment, and alias metadata. Preserve semantic captures,
  borrowing, consuming transfers, environment disarming, and ordinary function-value pairs.
- **Acceptance:** restore the unchanged scalar fixture with direct-entry and payload assertions.
  Cover differing environments sharing an entry, staged and returned PAPs, aliases, shadowing,
  mixed value uses, proven merges, unknown fallbacks, exactly-once effects, Unit parameters,
  supplied/deferred consuming arguments, drop cleanup, borrow errors, and use-after-move errors.
  Run LLVM/native fixtures, full compiler checks, smoke checks, local publishing, benchmark builds,
  ASan/LSan memory tests, both Linux architectures, QA, tracking checks, and independent review.
- **Boundaries:** no environment flattening, speculative specialization, implicit heap clones,
  source-language changes, or new ownership semantics. Overall migration signoff, local commit,
  and push are separate authorizations.
- **Task working memory:** `CallableTargetAnalysis` establishes complete target-flow evidence;
  `KnownCallableEmitter` registers entries and emits calls and values; `ClosureEnvironmentLayout`
  projects PAP capture storage consistently across emission and destruction. The original fixture
  is enabled byte-for-byte unchanged in codegen coverage. Empty borrowing environments have valid
  alias metadata and LLVM/native regression coverage. Host gates, both Linux architectures, QA,
  tracking checks, and independent review pass. The bounded slice is signed off.
- **Host verification (2026-10-05):** macOS arm64, Homebrew Clang/LLVM 23.1.1.
  `sbtn 'scalafmtAll; scalafixAll; test'` passes 860 library tests and nine CLI tests, with
  37 existing ignores and no compiler warnings. Native PAP fixtures pass LLVM assembly, native
  exit-code checks, and ASan. `./tests/smoke/run.sh all` passes 8/8; `sbtn mmlcPublishLocal`
  installs the compiler; `make -C benchmark clean` followed by `make -C benchmark mml` builds
  all 12 MML benchmarks; `./tests/mem/run.sh all` passes 45/45 ASan+LSan cases.
  Benchmark checks establish successful builds, not a speedup measurement.
- **Linux arm64 verification:** `mml-linux-arm64`, native AArch64 execution in Docker,
  Ubuntu Clang/LLVM 20.1.8. The suite selection below passes 113 tests with 26 existing ignores.
  It includes native PAP execution with ASan, native aggregate ABI and C interoperability,
  function-value adapters, destruction, and cross-compilation controls for all four supported targets.
- **Linux amd64 verification:** `mml-linux-amd64`, x86-64 execution emulated on an arm64 host,
  Ubuntu Clang/LLVM 20.1.8. The same selection passes 113 tests with 26 existing ignores.
  Fixtures execute inside the container; cross-compilation alone is not the execution evidence.
- **Review:** focused QA, tracking consistency, local links, and whitespace checks pass. A fresh
  independent reviewer inspects all scoped changes, including new sources and technical documents,
  and reports no actionable findings. Independent execution of `CallableTargetAnalysisTests`,
  `LocalCallableCodegenTests`, and `MaterializationCodegenTest` passes 38/38; `git diff --check`
  also passes. The broader execution results rely on the recorded gate evidence above.
- **Naming verification (2026-10-06):** the analysis and layout types, source filenames, state
  fields, test suite, and documentation use the descriptive names above. Formatting, lint,
  860 library tests, nine CLI tests, 8/8 smoke checks, local publishing, all 12 benchmark builds,
  and 45/45 ASan+LSan checks pass; 37 existing ignores remain. A fresh independent review of the
  rename reports no actionable findings. The executable changes consist only of identifier
  substitutions and formatting; Linux execution evidence remains the 2026-10-05 run.

The verification references use current suite names; the 2026-10-05 runs preceded the
`CallableTargetAnalysisTests` rename. Both Linux builders run the following suite selection
sequentially, without another host or container build running:

```sh
suites=(
  mml.mmlclib.codegen.CallableTargetAnalysisTests
  mml.mmlclib.codegen.LocalCallableCodegenTests
  mml.mmlclib.codegen.MaterializationCodegenTest
  mml.mmlclib.codegen.FunctionValueCodegenTests
  mml.mmlclib.codegen.FunctionSignatureTest
  mml.mmlclib.codegen.TailRecursionLoopificationTest
  mml.mmlclib.codegen.ClosureCodegenTest
  mml.mmlclib.codegen.AggregateAbiTests
)
docker exec -w /workspace mml-linux-arm64 sbtn "testOnly ${suites[*]}"
docker exec -w /workspace mml-linux-amd64 sbtn "testOnly ${suites[*]}"
```
