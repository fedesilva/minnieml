# Ignored lambda regression audit

Supporting evidence for [Unify lambdas](unify-lambdas.md#restore-ignored-regressions).

- **Audit date:** 2026-09-23.
- **Branch:** `dev-lambda-unify`, HEAD `90851ac`, with the verified expression-preservation
  changes included in the completed expression-preservation slice.
- **Baseline:** 51 runner-discovered ignored tests across ten suites.
- **Audit result (2026-09-23):** four pass unchanged; 47 fail. One failing case rejects the
  intended invalid program but expects an obsolete diagnostic name. The other 46 need assertion/API
  reconciliation or compiler investigation.
- **Repository test status:** 43 original cases are enabled, five duplicate cases are consolidated, and three
  optimization contracts are retired in favor of the implemented capture model. No ignored tests
  remain. Full-inventory cleanup is complete and signed off, with passing verification and review.
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
4. Complete the [full-inventory cleanup](#full-inventory-cleanup), including the approved alias
   and nested-parser diagnostic repairs. Every original case must have passing active coverage
   or an explicit duplicate/obsolete retirement. No ignored tests remain at acceptance.

## Inventory

| Disposition | Cases |
| --- | ---: |
| Enabled; emitted-IR materialization replacement | 10 |
| Enabled; assertions unchanged | 4 |
| Enabled; ownership/shadowing replacement | 9 |
| Enabled; typed diagnostic assertion | 1 |
| Enabled; current callable/environment IR assertions | 12 |
| Enabled; resolved semantic type/capture assertions | 3 |
| Enabled; heap-alias repair | 3 |
| Consolidated duplicate case; active coverage retained | 5 |
| Retired optimization contract; source retained under current capture model | 3 |
| Enabled; nested-parser diagnostic repair | 1 |

All 51 original cases are accounted for. Lines below identify the audit snapshot, not
subsequent source line shifts. A retained test keeps its original name unless the row names
its current test. Enabled status describes registration; final verification and signoff are
recorded separately.

### ClosureCodegenTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/ClosureCodegenTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| deferred lambda body preserves emitted metadata | 31 | [Enabled; current IR assertions](#full-inventory-cleanup); current test: `deferred non-capturing lambda body preserves emitted metadata` |
| recursive Direct lambda calls its plain direct entry | 65 | [Retired optimization contract; source retained](#consolidated-coverage) |
| non-recursive named Direct lambda does not rebuild unused self closure | 103 | [Consolidated duplicate case; active coverage](#consolidated-coverage) |
| Direct move lambda capturing a heap literal frees its clone once | 237 | [Enabled; ownership/shadowing replacement](#ownership-and-shadowing-restoration); current test: `move lambda capturing a heap literal frees its clone once` |
| Direct move lambda capturing an owned String frees it once without cloning | 256 | [Enabled; ownership/shadowing replacement](#ownership-and-shadowing-restoration); current test: `move lambda capturing an owned String frees it once without cloning` |
| Direct move lambda capturing an owned struct frees it through its destructor | 277 | [Enabled; ownership/shadowing replacement](#ownership-and-shadowing-restoration); current test: `move lambda capturing an owned struct frees it through its destructor` |
| loopified Direct move lambda frees its heap capture once before the back-edge | 299 | [Enabled; ownership/shadowing replacement](#ownership-and-shadowing-restoration); current test: `loopified move lambda frees its heap capture once before the back-edge` |
| loopified Direct lambdas do not allocate borrow envs | 324 | [Retired optimization contract; source retained](#consolidated-coverage) |
| loopified borrow closures stop being tracked after nested shadowing | 387 | [Enabled; ownership/shadowing replacement](#ownership-and-shadowing-restoration); current test: `nested local scalar shadowing preserves ordinary recursive calls` |
| loopified borrow closure validation respects lambda parameter shadowing | 415 | Enabled; assertions unchanged |

### FunctionSignatureTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/FunctionSignatureTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| higher-order named function arguments lower as function values, not symbol loads | 296 | [Consolidated duplicate case; active coverage](#consolidated-coverage) |
| repeated higher-order named function arguments reuse one closure entry | 325 | [Enabled; current IR assertions](#full-inventory-cleanup); current test: `repeated higher-order named function arguments preserve both calls` |
| mixed direct and higher-order top-level function uses keep both call shapes | 350 | [Enabled; current IR assertions](#full-inventory-cleanup) |
| local Direct lambda calls use plain direct entry call | 407 | [Consolidated duplicate case; active coverage](#consolidated-coverage) |
| shadowed local callable args do not eta-expand from top-level names | 435 | [Consolidated duplicate case; active coverage](#consolidated-coverage) |
| nested Direct lambda threads outer Direct callable's captures | 800 | [Enabled; current IR assertions](#full-inventory-cleanup); current test: `nested capturing lambda forwards the captured callable environment` |
| loopified Direct closure captures Direct sibling operands as trailing params | 842 | [Enabled; current IR assertions](#full-inventory-cleanup); current test: `loopified closure calls its captured sibling with the saved environment` |
| Direct move lambda capturing a heap literal clones at the binder site | 894 | [Enabled; current IR assertions](#full-inventory-cleanup); current test: `move lambda clones a heap literal into the invoked environment` |

### LambdaEquivalenceCodegenTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/LambdaEquivalenceCodegenTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| direct-only equivalent forms avoid closure materialization | 11 | [Enabled; current IR assertions](#full-inventory-cleanup) |
| first-class non-capturing values use null-env closure values | 55 | [Enabled; current IR assertions](#full-inventory-cleanup) |

### LambdaEquivalenceSemanticTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/LambdaEquivalenceSemanticTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| direct scalar calls have equivalent semantic shape | 82 | [Enabled; semantic type/capture assertions](#full-inventory-cleanup) |
| borrow-capture direct calls are equivalent to explicit top-level parameter passing | 121 | [Enabled; semantic type/capture assertions](#full-inventory-cleanup) |
| first-class non-capturing values use equivalent null-env materialization | 163 | [Enabled; semantic type/capture assertions](#full-inventory-cleanup); current test: `first-class non-capturing values have equivalent types and captures` |
| consuming-use invalid programs report equivalent use-after-move class | 255 | Enabled; assertions unchanged |

### MaterializationAnalyzerTests

[Semantic coverage](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/MaterializationAnalyzerTests.scala);
[restored codegen source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/MaterializationCodegenTest.scala).

| Test | Line | Result / next action |
| --- | ---: | --- |
| top-level fn called directly is direct | 53 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| recursive self-call keeps fn direct | 72 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| let-bound lambda used only directly is direct | 89 | Enabled in MaterializationCodegenTest; [optimization evidence](#direct-local-entry-optimization) |
| let-bound lambda used as value is not direct | 104 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| let-bound lambda used through partial application remains direct | 120 | Enabled in MaterializationCodegenTest; [PAP target repair](#pap-target-repair) |
| lambda literal in immediate application is direct | 138 | Enabled in MaterializationCodegenTest; emitted-IR assertion |
| nullary lambda literal in immediate application is direct | 163 | Enabled in MaterializationCodegenTest; [nullary application repair](#nullary-immediate-application) |
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
| MemberErrorChecker should catch term errors nested inside expressions | 45 | [Enabled; nested-parser diagnostic repair](#nested-parser-diagnostics) |

### TailRecursionLoopificationTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/TailRecursionLoopificationTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| named loopified function used as a value keeps a closure-entry wrapper | 62 | [Consolidated duplicate case; active coverage](#consolidated-coverage) |
| local non-capturing loopified Direct function emits no closure-entry wrapper | 106 | [Retired optimization contract; source retained](#consolidated-coverage) |
| partial application of local loopified Direct function emits a PAP entry | 149 | [Enabled; current IR assertions](#full-inventory-cleanup); current test: `partial application forwards a literal argument to a plain loopified entry` |
| escaped partial application of local loopified Direct function uses heap env | 201 | [Enabled; ownership/shadowing replacement](#ownership-and-shadowing-restoration); current test: `escaped PAP of a local loopified function owns a heap environment` |
| capturing partial application of local loopified Direct function tags PAP env fields | 317 | [Enabled; current IR assertions](#full-inventory-cleanup); current test: `capturing partial application tags its projected environment pointer` |
| escaped Direct PAP with borrowed heap capture is rejected by ownership | 387 | Enabled; typed diagnostic assertion |
| Direct PAP with borrowed heap applied arg stores without cloning | 419 | [Enabled; ownership/shadowing replacement](#ownership-and-shadowing-restoration); current test: `PAP borrows a heap applied argument in a stack environment without cloning` |
| Direct PAP with aliased borrowed heap applied arg stores without cloning | 457 | [Enabled; ownership/shadowing replacement](#ownership-and-shadowing-restoration); current test: `PAP borrows an aliased heap applied argument in a stack environment without cloning` |
| Direct PAP with consuming heap applied arg owns env field | 497 | [Enabled; ownership/shadowing replacement](#ownership-and-shadowing-restoration); current test: `PAP transfers its owned heap argument and then releases only its environment` |
| local capturing loopified Direct function uses trailing captures | 556 | [Enabled; current IR assertions](#full-inventory-cleanup); current test: `local capturing loopified function loads its step from a stack environment` |

### TbaaEmissionTest

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/TbaaEmissionTest.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| zero-field closure env TBAA is not emitted | 265 | [Enabled; current IR assertions](#full-inventory-cleanup) |

### TypeUtilsTests

[Source](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeUtilsTests.scala)

| Test | Line | Result / next action |
| --- | ---: | --- |
| heap native aliases resolve to the underlying memory functions | 120 | [Enabled; heap-alias repair](#heap-alias-repair) |
| heap native aliases without explicit free use the underlying type name | 149 | [Enabled; heap-alias repair](#heap-alias-repair) |
| heap struct aliases resolve to the underlying struct memory functions | 162 | [Enabled; heap-alias repair](#heap-alias-repair) |

## Ownership and shadowing restoration

- **Status:** complete; verified and signed off within the full-inventory cleanup.
- **Scope:** nine preserved capture-cleanup, escaped-PAP, applied-argument ownership, and
  nested-shadowing fixtures. Compiler implementation is unchanged.
- **Baseline:** at `ecd07a2`, all nine original fixtures generate IR. Their unchanged
  assertions produce one pass and eight failures. Native ABI spelling, caller-only frees,
  and obsolete environment names/layouts account for the eight failing oracles.
- **Capture cleanup:** replacements follow the stored value and environment through closure
  invocation and destruction. Literal capture checks one clone; owned captures check no
  additional clone. Payload cleanup precedes raw environment release. The struct case checks
  both String fields. The loop case requires cleanup in the actual back-edge block.
- **PAP cleanup:** borrowed and alias-typed arguments retain one outer cleanup after invocation;
  consuming arguments transfer to the callee after disarming the same environment. An uncalled
  consuming PAP retains its payload destructor. The escaped scalar PAP retains heap-backed
  storage, argument forwarding, caller cleanup, and alias-metadata checks.
- **Shadowing replacement:** the original nested-shadowing fixture uses ordinary mutual calls
  between `loop` and `step`; it cannot test loopified borrow validation. Its identical enabled
  parent fixture is consolidated into one test of scalar argument flow through those calls.
  A separate [native control](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/LocalCallableCodegenTests.scala)
  places the recursive call directly in `loop`, asserts PHIs and a back edge, and checks the
  accumulator result with AddressSanitizer. A fresh independent verifier confirmed the
  original coverage defect; no compiler defect was established by this audit.
- **Verification (2026-10-22):** `sbtn 'scalafmtAll; scalafixAll; testFull'` passes without
  warnings: 1,378 library tests and nine CLI tests, with 27 existing ignored library tests.
  This includes the new native shadowing control with ASan and the existing native ownership
  controls. Host: arm64 macOS, Clang/LLVM 23.1.1. All nine original source fixtures remain
  byte-identical and occur exactly once. A final focused rerun passes all 15 active TailRecursion
  tests, with five ignores at that checkpoint, including two additional assertions rejecting
  raw frees in borrowed PAP paths. Independent review found and verified a missing environment
  base-pointer check and two readability issues; those corrections are included in the full
  cleanup. This checkpoint predates the alias/parser compiler repairs; their required gates
  belong to the full-inventory verification below. No cross-target or performance claim is made.
- **Signoff:** complete within the full-inventory cleanup. Local commit is authorized;
  push is not authorized. The parent task remains open.

## Full-inventory cleanup

- **Status:** complete; verification, independent review, and scoped signoff are complete.
- **Scope:** all 36 cases ignored at `ecd07a2`, covering lambda forms, captures, partial
  applications, alias ownership, metadata, semantic equivalence, and nested parser errors.
  Obsolete or duplicate tests may be retired with named active coverage; zero ignores is the
  acceptance criterion. Alias and nested-parser repairs have separate bounded approval.
- **Baseline:** after the nine ownership/shadowing restorations, the other 27 preserved
  bodies ran with their original assertions: three passed, 24 failed. Most failures depend
  on abandoned materialization APIs or older generated entries and layouts. The alias and
  nested-parser failures expose the compiler defects described below.
- **Replacement contracts:** callable tests follow the entries actually passed or invoked,
  environments actually stored and loaded, loop PHIs, argument/result flow, and cleanup order.
  Repeated higher-order arguments preserve both calls; per-use eta expansion need not share
  one entry. Adapter reuse remains covered by `FunctionValueCodegenTests`. Semantic equivalence
  compares resolved type IDs and exact closure capture IDs. Immediately applied lambdas have
  no stored captures; their lexical references must resolve to the enclosing bindings. Physical
  storage belongs to codegen tests.
- **Focused verification (2026-10-22):** formatting and lint pass; all 191 tests in the
  affected codegen, semantic, CLI-printer, editor-diagnostic, and native local-callable suites
  pass with zero ignores. The source scan finds no `.ignore`, `.only`, or abandoned API
  placeholders. Initial replacement runs exposed test-helper issues (regex match lifetime,
  top-level binding types, colored output, and accumulated recovery diagnostics); the final
  focused run passes after correcting those assertions without weakening their intended checks.
- **Host:** macOS 26.6.2 arm64, Homebrew LLVM/Clang 23.1.1. The new loop-shadowing control
  passes native execution with AddressSanitizer. Other restored tests establish semantic or
  emitted-IR behavior; they do not individually establish sanitizer coverage.
- **Compiler gates before review corrections:** `./tests/smoke/run.sh all` passes 8/8.
  `sbtn 'testFull; mmlcPublishLocal'`
  passes 1,401 library tests and nine CLI tests, with zero ignores and no warnings, then installs
  the verified local compiler. `make -C benchmark clean` and `make -C benchmark mml` pass all
  13 MML builds; these are build checks, not performance measurements.
  `./tests/mem/run.sh all` passes 52/52 ASan+LSan fixtures at `-O0`. QA enforcement,
  focused tracking consistency, local links, and whitespace checks pass. No ABI contract
  changes; Linux container verification is not required for these repairs.
- **Initial independent review:** the complete code/design scope and all three dedicated checks
  are complete. QA/style and readability have no actionable findings. A separately verified
  test-quality finding showed the owned-String cleanup test accepted freeing an empty String
  instead of the loaded capture. The shared cleanup helper now connects the captured field,
  destructor environment argument, payload cleanup operand/target, and final environment free.
  `sbtn 'scalafmtAll; scalafixAll; testFull'` passes after that correction: 1,401 library tests
  and nine CLI tests, with zero ignores and no warnings. Fresh narrow re-review passes all
  three dedicated checks with no actionable findings. Fresh Scala 3.10.0 compilation of the
  actual owned-String test confirms: unchanged cleanup passes; substituting `zeroinitializer`
  for the loaded payload or removing its cleanup call fails the intended assertion. All three
  IR controls assemble. These controls establish assertion effectiveness, not additional
  runtime coverage. Production code is unchanged by the assertion correction.
- **Review corrections (2026-10-22):** the original noncapturing metadata source is restored
  byte-for-byte beside the nested-capture fixture. Semantic tests distinguish lowered `let`
  wrappers from immediate lambda applications. Inline lambdas assert their invocation status,
  empty stored captures, matching type, and exact enclosing `seed` reference; local/let closures
  assert exact capture IDs. Non-heap struct and two-level alias controls reject cloning and heap
  classification. The parser regression retains independent message/source/count/tree assertions
  without duplicating the checker's traversal.
- **Shared checks:** closure construction and invocation, payload/environment cleanup, invocation
  order/counts, environment metadata, and basic-block inspection use shared helpers. Owned cleanup
  returns the already-checked capture with its destructor; named fields keep operand roles explicit.
  The native heap-declaration predicate is shared without changing alias resolution or ownership.
  [Alias-resolution consistency](type-property-alias-resolution.md) is a separate review-first
  follow-up after Unify lambdas.
- **Correction verification:** `sbtn 'scalafmtAll; scalafixAll; testFull'` passes 1,403 library
  tests and nine CLI tests, zero ignores, with no warnings. An initial run's single failure was
  a test-side named-regex capture lifetime error in the restored metadata case; copying the
  matched values before advancing the iterator corrects it. Smoke checks pass 8/8;
  `sbtn mmlcPublishLocal` installs the verified compiler and the installed jar matches the build.
  Clean benchmark builds pass 13/13; ASan+LSan memory fixtures pass 52/52 at `-O0`. These are
  host checks on macOS arm64 with LLVM/Clang 23.1.1; benchmark builds do not measure performance.
  QA, local links, whitespace, and inventory checks pass.
- **Correction review:** fresh primary and separate QA/style, test-quality, and readability
  checks are complete. One independently verified test-coverage finding showed that the loop
  fixture's block-local invocation count could miss an extra call in an unreachable block.
  The fixture retains its block-local lifetime checks and also requires exactly one invocation
  across the full loop function. No production defect was demonstrated. Formatting, lint,
  1,403 library tests, and nine CLI tests pass after this assertion correction, with zero
  ignores and no warnings. Fresh narrow re-review, including separate QA/style, test-quality,
  and readability checks, reports no actionable findings. The assertion proves static direct-call
  cardinality; it does not measure runtime invocation frequency.
- **Final assertion cleanup:** share the remaining closure operand, destructor-slot, metadata,
  and immediate-return checks; keep distinct ownership fixtures and capture contracts explicit.
  Connect the shadowing regression's captured `n` and `acc` to their arithmetic operands, and
  separate assertion stages with deliberate spacing. This scope changes tests only and does
  not introduce a general testing framework.
- **Final assertion verification:** formatting, lint, 1,403 library tests, and nine CLI tests pass
  with zero ignores and no compiler warnings. The first run exposed a test signature pattern
  that omitted LLVM parameter attributes; the corrected pattern retains the actual environment
  operand. Isolated Scala controls using the actual shadowing assertions accept the emitted IR
  and reject swapped arithmetic inputs; both LLVM modules assemble. Shared lifetime checks
  reject a different closure environment and an extra invocation; destructor-slot and immediate
  return checks reject different operands sharing the expected name's prefix. These controls
  establish assertion sensitivity, not native execution or sanitizer coverage. The standalone
  runner emits the existing JDK `sun.misc.Unsafe` runtime deprecation warning. Production code
  and source fixtures are unchanged; the recorded smoke, publication, benchmark-build, and
  memory gates remain applicable.
- **Final assertion review:** the primary review and three separate QA/style, test-quality,
  and readability checks are complete. One independently confirmed test-quality finding showed
  that a capture load through `%v2Other` could match the slot `%v2`, accepting incorrect
  arithmetic input despite valid LLVM. Capture store and load checks require a complete operand
  followed by metadata, a newline, or the end of the inspected body. Formatting, lint,
  1,403 library tests, and nine CLI tests pass after this correction, with zero ignores and no
  compiler warnings. The actual shadowing assertions accept original and consistently renamed
  baselines, and reject swapped arithmetic inputs, wrong loads with and without a shared name
  prefix, and a wrong store with a shared prefix. All corresponding LLVM modules assemble.
  Fresh narrow review of the two operand boundaries, including separate QA/style, test-quality,
  and readability checks, reports no actionable findings. Metadata-delimited operands are covered
  by the controls; newline and end-of-body boundaries were checked by source inspection.
- **Signoff:** complete for the full-inventory cleanup, including the alias and nested-parser
  repairs and assertion corrections. The parent task remains open.
- **Commit authorization:** granted for the cleanup and its associated records, including
  the memory changes. Push is not authorized.

### Consolidated coverage

Five duplicate declarations share their source scenarios and behavioral contracts with active
tests. Three other declarations share source scenarios but require capture-elimination
optimizations outside the implemented model. Their original optimization expectations are
retired; the surviving tests check the implemented capture representation. These three are not
counted as equivalent coverage of the discarded optimization contracts. General capture
elimination remains outside this cleanup, as described in the
[compiler design](../../docs/design/compiler-design.md#direct-local-entry-optimization).
The distinct noncapturing metadata fixture is enabled alongside the nested-capture fixture.

**Scope decision:** the three obsolete capture-elimination contracts are approved for retirement;
their source scenarios retain coverage of the implemented model. Capture elimination is outside
lambda-completion acceptance, and no separate optimization task is selected for this cleanup.

| Suite | Original ignored case | Disposition and active coverage |
| --- | --- | --- |
| ClosureCodegenTest | recursive Direct lambda calls its plain direct entry | Retired optimization contract: removing the captured callable's closure requires capture elimination. `recursive capturing lambda rebuilds self closure from env` retains the source and checks the actual recursive environment. |
| ClosureCodegenTest | non-recursive named Direct lambda does not rebuild unused self closure | Duplicate: `non-recursive named capturing lambda does not rebuild unused self closure` checks the identical source, loaded scalar use, and absence of self construction. |
| ClosureCodegenTest | loopified Direct lambdas do not allocate borrow envs | Retired optimization contract: removing the captured scalar's environment requires passing it directly or equivalent capture elimination. `loopified borrow closures hoist env alloca to function entry` retains the source and checks one hoisted allocation and the captured loop value. |
| FunctionSignatureTest | higher-order named function arguments lower as function values, not symbol loads | Duplicate: same-name active test; identical source, passed entry forwarding, and indirect result flow. |
| FunctionSignatureTest | local Direct lambda calls use plain direct entry call | Duplicate: `local direct-only call uses a plain entry`; identical source, signature/body/result, and no closure pair. |
| FunctionSignatureTest | shadowed local callable args do not eta-expand from top-level names | Duplicate: same-name active test; identical source and local callable identity through forwarding. |
| TailRecursionLoopificationTest | named loopified function used as a value keeps a closure-entry wrapper | Duplicate: same-name active test; identical source, passed wrapper, indirect call, and plain loop. |
| TailRecursionLoopificationTest | local non-capturing loopified Direct function emits no closure-entry wrapper | Retired optimization contract: eliminating the captured callable value or its adapter requires capture elimination. `local non-capturing loopified function materializes a closure-entry wrapper` retains the source and checks the stored adapter. |

### Heap-alias repair

- **Approved scope:** resolve aliases through existing canonical type resolution in
  `cloneFnFor` and `isStructWithHeapFields`, preserving nominal type identity and the rule
  that function-containing structs cannot be cloned. Restore all three alias regressions.
- **Baseline:** the native alias requested `__clone_AliasBuf` rather than `__clone_BaseBuf`;
  aliases of a heap-owning struct were not recognized as such by `isStructWithHeapFields`.
- **Implementation:** both queries inspect the underlying nominal declaration. Clone names
  use that declaration's name. Controls retain distinct native declarations, reject scalar
  cloning, and reject direct/nested function-containing struct clones through aliases.
- **Focused evidence:** all restored alias cases and the new preservation control pass.
  The 191-test focused run, full compiler gates, and independent review pass.

### Nested parser diagnostics

- **Approved scope:** collect nested `TermError` nodes into a typed semantic diagnostic;
  preserve the original source, message, and failed code through CLI and editor reporting.
  Restore the original regression and add focused recovery/diagnostic controls.
- **Baseline:** the preserved source contains `let 123invalid = 5;`. Parsing retains
  `TermError` with message `Invalid identifier '123invalid'` and failed code `123invalid`;
  both `ParsingErrorChecker` and the full semantic pipeline report only the trailing
  semicolon's `MemberErrorFound`. The intended identifier diagnostic is lost.
- **Implementation:** `ParsingErrorChecker` traverses binding expressions through the shared
  term traversal and recurses through module members. `SemanticError.TermErrorFound` retains
  the original error payload; CLI location/message/snippet consumers and editor diagnostics
  handle it. The original regression, branch-error accumulation, local-function recovery,
  CLI rendering, and editor range/message controls pass in the 191-test focused run.

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

### Nullary immediate application

- **Status:** complete; implementation, verification, and independent review pass.
- **Branch:** `dev-2026-03-21-lambdas`.
- **Baseline reproduction (`87fd9c6`):** `fn main(): Int = ({ 42; } ());;` fails in `TypeChecker` with
  `cannot apply function of type '(Unit) -> Int' to argument of type 'Unit'`.
  Seven existing nullary tests pass; none covers this immediate call.
- **Completed plan:**
  1. Validate the literal application's function type through the ordinary argument checker,
     accepting Unit and retaining typed rejection of other argument types.
  2. Emit the Unit argument before the lambda body in the current lexical scope, preserving
     effects and exit blocks without constructing a closure.
  3. Restore the original fixture as emitted-IR coverage and replace its obsolete metadata
     helper. Cover invalid arguments, surrounding values, effect order, and conditional control flow.
  4. Run compiler gates, QA, focused tracking review, and independent code review. Update the
     inventory with actual results; migration completion and subtask signoff remain separate.
- **Scope:** immediate nullary literal application; no ABI or ownership-policy change.
- **Implementation:** function-type validation accepts the nullary literal; emission evaluates
  its argument before the body in the enclosing scope and preserves the final basic block.
  The original fixture is enabled with an emitted-IR assertion; the absent `LambdaMeta.isDirect`
  helper is replaced by semantic result-type and argument-mismatch checks.
- **Focused verification:** formatting, lint, and 43 materialization/local-callable tests pass,
  including LLVM assembly and native execution of the three new effect/control-flow cases.
- **Compiler verification (2026-10-18):** `./tests/smoke/run.sh all` passes 8/8;
  `sbtn 'scalafmtCheckAll;scalafixAll --check;testFull;mmlcPublishLocal'` passes formatting,
  lint, 1,107 library tests, and nine CLI tests, with 36 ignored. Local publication succeeds.
- **Host:** macOS 26.6.2 arm64; Homebrew LLVM/Clang 23.1.1. No ABI change; Linux container
  verification is not required for this slice. Existing Linux sanitizer work remains separate.
- **Benchmark builds:** `make -C benchmark clean` and `make -C benchmark mml` pass all
  12 MML builds. This is build verification, not a performance measurement.
- **Memory verification:** `./tests/mem/run.sh all` passes 51/51 ASan+LSan fixtures at `-O0`.
- **Review:** QA, focused tracking review, and self-review pass. A fresh independent reviewer
  reports no actionable findings after tracing argument validation, lexical scope, captures,
  ownership, callable adapters, and conditional exit blocks. Its independent rerun passes all
  43 focused tests, including LLVM assembly and native execution; `git diff --check` passes.
- **Review limits:** the independent reviewer assessed the full-suite, benchmark, and sanitizer
  evidence without rerunning those gates. No Linux execution or performance measurement applies
  to the recorded host results.
- **Signoff:** granted for the nullary immediate-application repair.
- **Completed:** 2026-10-18. The parent migration remains in progress; 36 original cases remain ignored.

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
