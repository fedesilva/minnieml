# MML Task Tracking

## CRITICAL Rules

------------------------------------------------------------------------
/!\ /!\ Do not edit without explicit approval or direct command. /!\ /!\
/!\ /!\ Follow rules below strictly                              /!\ /!\
/!\ /!\ COMPLETING A TASK? -> ADD [COMPLETE] TAG. NEVER DELETE.  /!\ /!\
------------------------------------------------------------------------

* *Always read* `context/task-tracking-rules.md` 
  *before* working with this file - even if you read it before, 
  it might have changed in the meantime.

* *Always read* `context/coding-rules.md` 
  before working with this file - even if you read it before, 
  it might have changed in the meantime.

* Follow the rules in those documents.
* These rules are mandatory unless the Author explicitly overrides them.

## Active Tasks

### Memory Management 

Affine ownership with borrow-by-default. Enables safe automatic memory management.

GitHub: `https://github.com/fedesilva/minnieml/issues/134`

- Borrow by default, explicit move with `~` syntax
- OwnershipAnalyzer phase inserts `__free_T` calls into AST
- No codegen changes - just AST rewriting
  
- [ ] **Define and enforce global borrow-only ownership semantics**: document and implement
  no-move behavior for top-level bindings passed to consuming params.
  See `context/spaces/mem-globals-no-move.md`.
- [x] **BUG: nested mixed-ownership conditionals** [COMPLETE]: witness booleans don't propagate
  through nested `if/else` where inner branches mix heap/literal. Blocks T10 mixed variant.
  - **Plan:**
    - Implement recursive mixed-ownership classification in `OwnershipAnalyzer` so nested
      `Cond` trees produce a composed witness boolean expression.
    - Replace shallow top-level mixed detection (`detectMixedConditional`) with recursive
      witness extraction used by `analyzeLetBinding`.
    - Keep existing semantics for non-mixed branches and all-heap branches.
    - Add regression tests in `OwnershipAnalyzerTests` for nested mixed conditionals.
    - Extend/adjust memory sample coverage for T10 mixed variant.
    - Run full post-task verification (`sbtn` chain, benchmarks, mem tests).
  - **Progress subtasks:**
    - [x] Implement recursive witness propagation for nested mixed conditionals.
    - [x] Wire new witness detection into `analyzeLetBinding`.
    - [x] Add unit regression test(s) for nested mixed conditionals.
    - [x] Update memory sample coverage for T10 mixed variant.
    - [x] Run full verification and record results.
  - **Current status (2026-02-13):**
    - Recursive witness propagation for nested mixed conditionals is implemented.
    - `OwnershipAnalyzer` fixed conditional branch ownership reconciliation for mixed
      consume/borrow flows by merging branch states and inserting branch-local frees.
    - New regression tests in `OwnershipAnalyzerTests` pass, including:
      - `nested mixed conditional in heap-returning function is accepted`
      - `nested mixed conditional let-binding creates ownership witness`
      - `conditional consume in one branch frees in the other branch`
    - Memory samples split for clarity:
      - `tests/mem/nested-cond.mml`
      - `tests/mem/cond-consume.mml`
    - Full verification passed:
      - `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"`
      - `make -C benchmark clean`
      - `make -C benchmark mml`
      - `./tests/mem/run.sh all`: ASan 15/15, Leaks 15/15

#### Bugs

- [x] **BUG: user-struct `__clone_*` resolution** [COMPLETE]: `wrapWithClone` resolves to
  `stdlib::bnd::__clone_<T>` even when `__clone_<T>` is generated in the current module,
  causing unresolved symbols for auto-cloned user-defined heap structs.
- [x] **BUG: constructor auto-clones borrowed args** [COMPLETE]: `analyzeRegularApp` wraps
  non-owned (borrowed) args to consuming constructor params with `wrapWithClone`. This is
  wrong: constructors take ownership, so passing a borrowed value should be an error, not
  a silent clone. Literals (e.g. `"hello"`) still need heap allocation but that's a
  separate concern from cloning borrowed refs.

### LSP QA and Bug Fixing

Quality assurance and bug fixes for the LSP server.

GitHub: `https://github.com/fedesilva/minnieml/issues/220`

- [ ] **PRECONDITION: Evolve `FromSource` to distinguish real source spans vs synthesized origins**:
  Hard dependency for reliable go-to-definition and semantic token behavior around
  generated symbols (constructors, destructors, clones). See `context/specs/qa-lsp.md`
  intro note 2.
  - **Approach:** Make `FromSource` a sum type: `Loc(span: SrcSpan)` | `Synth` (no payload).
    AST nodes that currently extend the `FromSource` trait get a `source: FromSource` field
    instead. Add convenience `def span: Option[SrcSpan]` for extraction. Replace all 9
    `startsWith("__")` checks in LSP code with `Loc`/`Synth` pattern matching. Orthogonal
    to `BindingOrigin` — provenance and binding category are separate concerns.
  - **Subtasks:**
    - [ ] Replace `FromSource` trait with enum: `Loc(span: SrcSpan)` | `Synth`.
      Add `def span: Option[SrcSpan]` convenience method.
    - [ ] Update AST nodes (`Bnd`, `FnParam`, `Field`, `TypeStruct`, `TypeDef`,
      `TypeAlias`, `DocComment`, `DuplicateMember`, `InvalidMember`) to carry
      `source: FromSource` field instead of extending the trait.
    - [ ] Update all parser-produced nodes to emit `FromSource.Loc(span)`.
    - [ ] Update `ConstructorGenerator` and `MemoryFunctionGenerator` to emit
      `FromSource.Synth` on generated bindings and params.
    - [ ] Replace all 9 `startsWith("__")` in `AstLookup` (8) and `SemanticTokens` (1)
      with `FromSource.Loc`/`Synth` pattern matching.
    - [ ] Add/update LSP tests for synthesized symbol filtering.

- [ ] **PRECONDITION (related): Implement names as explicit AST nodes**:
  Architectural follow-up covered in a separate design document. Out of scope for this
  QA pass but needed for full LSP precision. See `context/specs/qa-lsp.md` intro note 3.

- [ ] **BUG: Go-to-definition on struct constructor resolves to function, not struct**:
  Constructor is synthetic (`__mk_<Name>`), LSP should resolve through it to the
  `TypeStruct` declaration. Blocked by `FromSource` precondition.
  See `context/specs/qa-lsp.md` repro case D.

- [ ] **Investigate semantic token bugs**: Declaration positions are guessed (not
  span-derived), conditional keyword tokenization is brittle on multiline, unresolved
  refs get no token. See `context/specs/qa-lsp.md` findings 1–3.

### QA Debt

Quality debt tasks tracked from QA misses.

GitHub: `https://github.com/fedesilva/minnieml/issues/235`

- [ ] **Define semantic detection for ownership free/clone assertions**: follow
  `context/qa-misses.md` section `2026-02-15 - Top-priority brittle string-name assertions in OwnershipAnalyzerTests`
  and decide resolved-id/type-aware checks for `__free_*` / `__clone_*` assertions instead of
  raw name string matching.

- [ ] **Refactor noisy wildcard AST matcher patterns with extractors**: follow
  `context/qa-misses.md` section `2026-02-15 - Brittle deep wildcard pattern matching in OwnershipAnalyzerTests helpers`
  and introduce extractor-first handling for repeated noisy match patterns in tests, or perform a
  focused review with documented rationale where extractors are not appropriate.


---

## Recent Changes

### 2026-02-16 Constructor borrowed-arg consumption semantics [COMPLETE]

- **Problem:** Constructors were auto-cloning borrowed arguments passed to consuming params,
  masking ownership errors and allowing invalid borrowed consumption.
- **Fix:** Updated ownership analysis so borrowed refs passed to consuming constructor params are
  rejected via existing consuming-ownership diagnostics, while retaining literal string
  auto-clone behavior and preserving temp-wrapper internals.
- **Changes:**
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala`:
    reject borrowed refs in `handleConsumingParam`; narrow constructor clone trigger to literal
    strings only.
  - `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala`:
    replaced borrowed constructor auto-clone assertions with rejection tests for borrowed args
    (`n`, `r`, and user-struct `u`).
  - `modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/StructCodegenTest.scala`:
    updated constructor fixture to pass owned input (`int_to_str`) to satisfy consuming semantics.
  - `context/coding-rules.md`:
    added post-task rule for stalled verification sessions (kill and retry once; report clearly
    if still blocked).
- **Verification:**
  - `sbtn "testOnly mml.mmlclib.semantic.OwnershipAnalyzerTests"` passed (34/34).
  - `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"` passed (264/264).
  - `make -C benchmark clean` passed.
  - `make -C benchmark mml` passed.
  - `./tests/mem/run.sh all` passed (ASan 15/15, Leaks 15/15).

### 2026-02-15 Memory edge-case testing marked complete [COMPLETE]

- **Status update:** Author confirmed all cases listed in `mem-next.md` are covered.
- **Tracking update:** Marked `Edge case testing` complete under `## Active Tasks -> ### Memory Management`.

### 2026-02-14 User-struct clone resolution in ownership analyzer [COMPLETE]

- **Problem:** `OwnershipAnalyzer.wrapWithClone` hardcoded clone refs to
  `stdlib::bnd::__clone_<T>`, which breaks user-defined struct cloning when
  `__clone_<T>` is generated in the current module namespace.
- **Fix:** Added clone-ID resolution that prefers module-local `__clone_<T>` for
  user-defined structs with heap fields, while preserving stdlib preference for
  native/stdlib heap types.
- **Changes:**
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala`:
    added `lookupCloneFnId`, threaded `resolvables` into `wrapWithClone`,
    and updated clone-wrapping call sites.
  - `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala`:
    added regression test
    `constructor clone of user struct resolves to module-local clone fn`.
- **Verification:** `sbt "test; scalafmtAll; scalafixAll; mmlcPublishLocal"` passed;
  `make -C benchmark clean` and `make -C benchmark mml` passed. Memory harness
  handoff: `./tests/mem/run.sh all` run delegated to Author.

### 2026-02-13 Conditional ownership merge + memory harness progress output [COMPLETE]

- **Problem:** Leak failure was reported under nested mixed conditionals, but isolation showed
  `nested-cond` clean and leaks coming from branch-divergent conditional consumption
  (`consume` on one branch, borrow on the other).
- **Fix:** Updated `OwnershipAnalyzer.analyzeCond` to reconcile ownership across both branches and
  emit branch-local frees when ownership diverges across branches.
- **Changes:**
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala`:
    conditional branch state merge + branch-local free insertion
  - `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala`:
    added regression test
    `conditional consume in one branch frees in the other branch`
  - Replaced `tests/mem/t10-nested-cond.mml` with:
    - `tests/mem/nested-cond.mml`
    - `tests/mem/cond-consume.mml`
  - `tests/mem/run.sh`: added streamed per-test progress and per-step timings for
    compile/run/check phases in ASan and leaks modes
- **Verification:** `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"` passed; benchmarks
  passed; `./tests/mem/run.sh all` passed (ASan 15/15, Leaks 15/15).

### 2026-02-10 Preserve `.5` float literals with `.` operator [COMPLETE]

- **Problem:** `termP`/`termMemberP` parsed `opRefP` before `numericLitP`, so leading-dot
  float literals were split into operator + int (`.5` -> `Ref(".")`, `LiteralInt(5)`).
- **Fix:** Reordered term parsing so `numericLitP` runs before `opRefP`, allowing `.5` and
  similar leading-dot floats to parse as `LiteralFloat` while preserving operator parsing.
- **Changes:**
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala`:
    `termP` and `termMemberP` now try `numericLitP` before `opRefP`
  - `modules/mmlc-lib/src/test/scala/mml/mmlclib/grammar/LiteralTests.scala`:
    added regression test for `let x = .5;`
  - `modules/mmlc-lib/src/test/scala/mml/mmlclib/grammar/LiteralTests.scala`:
    added combined regression test for `let x = .5 +. .25;`
- **Verification:** `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"` passed
  (260 tests); `make -C benchmark clean` and `make -C benchmark mml` passed (all 7 benchmarks).

### 2026-02-10 Parser whitespace regression for `=` and `;` [COMPLETE]

- **Problem:** Parser required a non-word boundary after `=` and `;`, rejecting valid compact
  syntax like `let x=1;let y=2;` and function definitions without inserted spaces.
- **Fix:** Removed `wordBoundary` from `defAsKw` and `semiKw` in parser keywords so these
  punctuation tokens parse independently of trailing whitespace.
- **Changes:**
  - `modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/keywords.scala`: `defAsKw` changed
    from `P("=" ~ wordBoundary)` to `P("=")`, `semiKw` from `P(";" ~ wordBoundary)` to `P(";")`
  - `modules/mmlc-lib/src/test/scala/mml/mmlclib/grammar/LetBndTests.scala`: added
    "let bindings allow no spaces around = and ;" regression test
- **Verification:** New parser regression test added and committed with the fix (`afb168c`).

### 2026-02-10 Negative test harness complete [COMPLETE]

- **Problem:** Negative tests (expected compile errors) were confirmed manually but lacked
  unit tests asserting the correct error types.
- **Fix:** Added T8 test ("same string in two array slots rejected") to
  `OwnershipAnalyzerTests.scala`. Passes same owned string to `ar_str_set` twice; asserts
  `ConsumingParamNotLastUse` or `UseAfterMove` is emitted.
- **All 5 negative cases now have unit tests:**
  - T8 (array alias): "same string in two array slots rejected"
  - use-after-move: "use after move to consuming param", "use after move in expression"
  - borrow-escape: "borrowed param returned from heap-returning function is rejected"
  - consume-not-last: "consuming param not last use detected"
  - partial-consume: "partial application of function with consuming param is rejected"
- **Verification:** 257 tests pass, `scalafmtAll`/`scalafixAll` clean.

### 2026-02-09 Move constructor generation from parser to semantic phase [COMPLETE]

- **Problem:** Constructor synthesis (`addStructConstructors`, `mkStructConstructor`,
  `mkNativeStructConstructor`) lived in the parser (`modules.scala`), mixing parsing with
  code generation. `MemoryFunctionGenerator` ran after `TypeChecker` despite only needing
  resolved types and constructor nodes.
- **Fix:** New `ConstructorGenerator` semantic phase generates constructor `Bnd` nodes for
  both MML and native structs. Runs after `TypeResolver`. `MemoryFunctionGenerator` moved
  from after `TypeChecker` to after `ConstructorGenerator`, grouping code-generation phases
  before analysis phases.
- **Changes:**
  - New `semantic/ConstructorGenerator.scala`: moved constructor logic from parser
  - `parser/modules.scala`: removed `addStructConstructors`, `mkStructConstructor`,
    `mkNativeStructConstructor` and related helpers
  - `compiler/SemanticStage.scala`: pipeline reorder (ctor-gen → mem-fn-gen → ref-resolver →
    ... → type-checker)
  - `semantic/MemoryFunctionGenerator.scala`: doc comment updated
  - `docs/design/compiler-design.md`: phase numbering, Mermaid diagram, summary updated
  - Test moved from `grammar/DataConstructorTests` to `semantic/DataConstructorTests`
- **Verification:** 256 tests pass, `scalafmtAll`/`scalafixAll` clean, `mmlcPublishLocal` OK,
  all 7 benchmarks compile, 13/13 memory tests pass (ASan + leaks).

### 2026-02-09 Memory test harness [COMPLETE]

- **Problem:** Memory management validation (ASan + leaks) was manual per POST-CHORE instructions.
- **Fix:** Shell script harness at `tests/mem/run.sh` automating both checks across 13 positive
  test files copied to `tests/mem/`.
- **Modes:** `asan` (compile+run with `-s`), `leaks` (compile, check with `leaks --atExit`),
  `all` (both in sequence). `mmlc clean` between modes prevents cross-contamination.
- **Build isolation:** `-b build/test-mem` with `-o build/test-mem/<name>` keeps test builds
  inside `build/` (gitignored). Cleaned up on exit.
- **Verification:** 13/13 ASan pass, 13/13 leaks pass, nonzero exit on failure (CI-ready).

### 2026-02-09 Native struct constructor generation and field access [COMPLETE]

- **Problem:** User-defined `@native` struct types (e.g., `type Vec2 = @native { x: Float, y: Float }`)
  required hand-written C constructors and getter functions. MML user-defined structs already had
  auto-generated constructors; native structs did not.
- **Fix:** Extended constructor generation to `@native` struct types, and enabled dot-notation field
  access on them.
- **Changes:**
  - `parser/modules.scala`: `addStructConstructors` matches `TypeDef` with `NativeStruct`, new
    `mkNativeStructConstructor` generates constructor `Bnd` from `NativeStruct.fields`
  - `codegen/emitter/package.scala`: `resolveTypeStruct` handles `TypeDef`/`NativeStruct` by
    synthesizing a `TypeStruct` from native fields (enables `compileStructConstructor` and field access)
  - `semantic/TypeChecker.scala`: `resolveStructType` extended with same `TypeDef`/`NativeStruct`
    handling (enables field access type checking)
  - `semantic/MemoryFunctionGenerator.scala`: `rewriteModule` also finds native structs with heap
    fields and rewrites their constructor params as `consuming = true`. No `__free_T`/`__clone_T`
    generated for native structs (user-provided via `free=` or C code).
- **Tests:** 4 new (parser: native struct ctor synthesized, codegen: alloca/store + extractvalue,
  semantic: heap params marked consuming)
- **Verification:** 256 tests pass, `scalafmtAll`/`scalafixAll` clean, `mmlcPublishLocal` OK,
  all 7 benchmarks compile.

### 2026-02-09 Nested struct destructor resolution and ownership tracking [COMPLETE]

- **Problem:** Three bugs prevented nested structs (e.g., `Outer { inner: Inner, data: String }`)
  from compiling correctly when `Inner` itself has heap fields.
- **Bug 1: ID resolution:** `MemoryFunctionGenerator` hardcoded `stdlib::bnd::__free_T` for all
  free/clone references. User-defined struct destructors live at `moduleName::bnd::__free_T`, not
  stdlib. Added `resolveMemFnId` helper: checks user struct first, stdlib second, user-declared
  `free=` functions third. Fixed both `mkFreeFunction` and `wrapFieldWithClone`.
- **Bug 2: Consuming move inside temp wrappers:** `analyzeAllocatingApp` marks inherited owned
  bindings as Borrowed inside temp wrappers (to prevent double-free). But non-allocating args
  passed to consuming constructor params (e.g., `i` in `Outer i ("data" ++ ...)`) were never
  marked Moved because `handleConsumingParam` saw them as Borrowed. Fix: collect non-allocating
  consumed args after building the wrapper and propagate Moved state to the outer scope.
- **Bug 3: ReturnOwnershipAnalysis type priority:** `termReturnsOwned` for let-bindings returned
  `argOwned.orElse(bodyReturns)`, preferring the arg's alloc type. For `let i = Inner(...);
  Outer i (...)`, this returned `InnerType` instead of `OuterType`, causing type mismatch with
  the let-binding param and marking the result as Borrowed. Fix: swapped to
  `bodyReturns.orElse(argOwned)`.
- **Changes:**
  - `semantic/MemoryFunctionGenerator.scala`: `resolveMemFnId` helper, fixed `mkFreeFunction`
    and `wrapFieldWithClone`
  - `semantic/OwnershipAnalyzer.scala`: `consumedMoves` propagation in `analyzeAllocatingApp`,
    `bodyReturns.orElse(argOwned)` in `ReturnOwnershipAnalysis.termReturnsOwned`
  - `OwnershipAnalyzerTests.scala`: 1 new test (nested struct with heap fields has correct free calls)
  - `mml/samples/mem/nested-struct.mml`: new sample (100 iterations, inline allocation)
  - `mml/samples/mem/nested-stress.mml`: stress sample (1000 iterations via helper function)
- **Verification:** 252 tests pass, `scalafmtAll`/`scalafixAll` clean, `mmlcPublishLocal` OK,
  all 7 benchmarks compile, both nested struct samples ASan clean, all 13 memory samples 0 leaks.

### 2026-02-09 OOM Policy B: trap on allocation failure [COMPLETE]

- **Decision:** Policy B: all allocation failures abort immediately.
- **Changes:** `mml_runtime.c` only.
  - Added `mml_sys_oom_abort()` helper: writes "out of memory\n" to stderr, calls `abort()`.
  - Replaced all `return {0, NULL}` / `return NULL` on malloc/realloc failure paths with
    `mml_sys_oom_abort()` across all producers and clone functions.
  - Preserved legitimate empty-value returns: empty arrays (`size <= 0`), `readline` EOF,
    out-of-bounds `substring`, `concat` of two null strings, clone of empty input,
    `string_builder_finalize(NULL)`.
  - `__free_*` null guards kept: protect against freeing legitimately empty values.
- **Verified:** MML struct constructors use `alloca` (stack), not `malloc`: no OOM path.
- **Verification:** 243 tests pass, `scalafmtAll`/`scalafixAll` clean, `mmlcPublishLocal` OK,
  all 7 benchmarks compile, all 11 memory samples 0 leaks.

### 2026-02-09 OwnershipAnalyzer code quality cleanup [COMPLETE]

- **All 5 code quality tasks from `qa-mem.md` now complete:**
  - `analyzeTerm` split from ~380 lines to 50, with helpers: `analyzeLetBinding`, `analyzeRegularApp`,
    `collectArgsAndBase`, `argNeedsClone`/`argAllocates`/`wrapWithClone`
  - Hardcoded stdlib IDs extracted to `UnitTypeId`/`BoolTypeId` constants (line 109-110)
  - Mutable state in `analyzeExpr` refactored from `var` + `.map()` to `foldLeft`
  - `__free_String` silent fallback (`getOrElse`) removed (no longer present)
  - Constructor detection uses `DataConstructor` term lookup via `isConstructorCall`, not
    `startsWith("__mk_")`
- **Today's change:** `analyzeExpr` `foldLeft` refactor (the rest were completed in prior sessions)
- **Verification:** 243 tests pass, `scalafmtAll`/`scalafixAll` clean, `mmlcPublishLocal` OK,
  all 7 benchmarks compile.

### 2026-02-08 Move-only structs + LSP ownership diagnostics [COMPLETE]

- **Problem:** Rebinding a struct with heap fields (`let b = a`) was a silent borrow (shallow
  copy), risking unclear ownership. Also, ownership errors (UseAfterMove, BorrowEscapeViaReturn,
  etc.) were not reported in the LSP: `Diagnostics.extractSpan` had `case _ => None` catching
  all ownership error variants, producing 0 diagnostics even when the compiler detected errors.
- **Fix:**
  - Rebinding an Owned user-defined `TypeStruct` with heap fields is now an implicit move. The
    source becomes Moved (invalid), the target becomes Owned. Native types (String, IntArray, etc.)
    keep borrow-by-default. Structs without heap fields also keep borrow behavior.
  - Use-after-move detection extended to qualified refs (field access like `a.name` after move).
  - LSP `Diagnostics.scala`: added span extraction and message formatting for all 5 ownership
    error variants (UseAfterMove, ConsumingParamNotLastUse, PartialApplicationWithConsuming,
    ConditionalOwnershipMismatch, BorrowEscapeViaReturn).
- **Changes:**
  - `ast/TypeUtils.scala`: added `isStructWithHeapFields`: matches only `TypeStruct`, not native
  - `semantic/OwnershipAnalyzer.scala`: added `isMoveOnRebind` helper, move branch in let-binding
    case, qualified ref check in use-after-move detection
  - `lsp/Diagnostics.scala`: 5 new cases in `extractSpan` and `formatErrorMessage`
  - `OwnershipAnalyzerTests.scala`: 6 new tests (struct move, use-after-move, field access after
    move, non-heap struct borrows, string borrows, borrowed struct stays borrowed)
  - `mml/samples/mem/move-struct.mml`: new sample (100 iterations, 0 leaks)
- **Verification:** 243 tests pass, `scalafmtAll`/`scalafixAll` clean, `mmlcPublishLocal` OK,
  all 7 benchmarks compile, all 10 memory samples 0 leaks.

### 2026-02-08 Fix consuming param memory leaks + BindingOrigin metadata [COMPLETE]

- **Problem:** Two leak regressions: `test_unused_locals.mml` (100 leaks), `move-valid.mml`
  (2 leaks). Consuming (`~`) params were invisible to ownership tracking inside function bodies:
  skipped entirely in Lambda param scope, never freed. Additionally, `ReturnOwnershipAnalysis`
  used `Map.empty` as initial env, so pass-through functions like `identity(~s) = s` were not
  recognized as returning owned values.
- **Fix:**
  - Lambda case: consuming params added as Owned in body scope, frees inserted at body end for
    those not returned or moved.
  - `ReturnOwnershipAnalysis.discover`: consuming params seeded into env so the fixed-point
    correctly propagates through pass-through functions.
  - `skipConsumingOwnership` flag on `OwnershipScope` prevents freeing in destructors,
    constructors, and native bodies.
  - Added `BindingOrigin.Destructor` and `BindingOrigin.Constructor` variants to replace
    string prefix matching (`__free_*`/`__mk_*`). Tagged in `MemoryFunctionGenerator` and
    `parser/modules.scala`. Exhaustivity updates in `TypeChecker`, `SemanticTokens`, `Member`.
- **Changes:**
  - `ast/common.scala`: `BindingOrigin.Destructor`, `BindingOrigin.Constructor`
  - `parser/modules.scala`: constructor uses `BindingOrigin.Constructor`
  - `semantic/MemoryFunctionGenerator.scala`: destructor uses `BindingOrigin.Destructor`
  - `semantic/OwnershipAnalyzer.scala`: core fix (consuming param scope, ReturnOwnershipAnalysis
    env, skipConsumingOwnership via metadata)
  - `semantic/TypeChecker.scala`, `lsp/SemanticTokens.scala`, `util/prettyprint/ast/Member.scala`:
    exhaustivity for new BindingOrigin variants
- **Verification:** 237 tests pass, `scalafmtAll`/`scalafixAll` clean, `mmlcPublishLocal` OK,
  ASan clean on both samples, all 9 memory samples 0 leaks, all 7 benchmarks compile.

### 2026-02-08 Struct constructors as sinks (move-in semantics) [COMPLETE, pending review]

- **Problem:** Constructors borrowed all args and cloned heap-typed fields internally at codegen
  level. Every construction with heap fields involved redundant allocations: caller keeps original,
  constructor clones into struct, caller frees original.
- **Fix:** Constructors now consume (move-in) heap-typed fields. Owned values move directly into
  the struct. Non-owned values (literals, borrowed refs) are auto-cloned at the call site.
- **Changes:**
  - `semantic/MemoryFunctionGenerator.scala`: `rewriteConstructor` marks heap params as consuming.
    `mkCloneFunction` wraps heap field accesses with explicit `__clone_T` calls (constructor no
    longer clones internally). Added `wrapFieldWithClone` helper.
  - `codegen/emitter/FunctionEmitter.scala`: Removed clone-on-store in `compileStructConstructor`.
    Constructor now trusts its params are already owned.
  - `semantic/OwnershipAnalyzer.scala`: Added `isConstructorCall`, `argNeedsClone`, `argAllocates`
    helpers. Auto-clone pre-processing between `baseFnParams` and `argsWithAlloc` wraps non-owned
    args to consuming constructor params with `wrapWithClone`. Critical fix: move propagation in
    let-binding case: consuming params inside let-binding bodies now propagate Moved state to
    enclosing scopes (was causing double-free).
- **Tests:** 4 new in `OwnershipAnalyzerTests.scala` (owned consumed without clone, literal auto-
  clone, borrowed auto-clone, non-heap fields not consumed). 1 new in `StructCodegenTest.scala`
  (constructor IR has no clone calls).
- **Verification:** 237 tests pass, `scalafmtAll`/`scalafixAll` clean, `mmlcPublishLocal` OK,
  ASan clean on all memory samples, all 7 benchmarks compile. Leaks: 0 on 7/9 samples.
  Known regressions: `test_unused_locals.mml` (100 leaks), `move-valid.mml` (2 leaks): from
  earlier changes on this branch, tracked as urgent bug fix.

### 2026-02-07 Borrow escape enforcement (Rule 1: return) [COMPLETE]

- **Problem:** Nothing prevented a function from returning a borrowed parameter as if owned,
  causing use-after-free: caller would free a value it doesn't own.
- **Fix:** After `promoteStaticBranchesInReturn` in the Lambda case of `analyzeTerm`, inspect the
  terminal expression for any `Borrowed` refs. If the return type is a heap type, emit
  `BorrowEscapeViaReturn` errors. Runs on the promoted body to avoid false positives on mixed
  conditionals (where clone insertion already handles the static branch).
- **Changes:**
  - `semantic/package.scala`: Added `BorrowEscapeViaReturn(ref, phase)` error variant
  - `semantic/OwnershipAnalyzer.scala`: Added `returnedBorrowedRefs` helper (walks through
    `Cond`/`TermGroup`). Lambda case checks after promotion, emits per-ref errors.
  - 3 error printer files: `SemanticErrorPrinter`, `ErrorPrinter`, `SourceCodeExtractor`
  - `mml/samples/mem/test_unused_locals.mml`: `identity`/`wrapper_layer` now use `~` move
    semantics instead of returning borrowed params
  - `mml/samples/mem/borrow-escape.mml`: negative example (rejected)
- **Tests:** 6 new in `OwnershipAnalyzerTests.scala`, 1 existing test fixed in `TypeResolverTests`
- **Verification:** 232 tests pass, `scalafmtAll`/`scalafixAll` clean, all 7 benchmarks compile,
  all memory samples run, ASan clean on `test_unused_locals.mml`

### 2026-02-07 Add `noalias` attributes to LLVM IR emission [COMPLETE]

- **Two cases:** (1) Native functions with `MemEffect.Alloc` returning `NativePointer` types get
  `noalias` on return. (2) Consuming (`~`) parameters of `NativePointer` type get `noalias` in
  user function definitions.
- **Key distinction:** Only `NativePointer` types (Buffer, CharPtr, etc.) are actual LLVM pointers.
  `NativeStruct` types (String, arrays) are value types at LLVM level: `noalias` doesn't apply.
- **Changes:**
  - `ast/TypeUtils.scala`: Added `isPointerType`: checks if type resolves to `NativePointer`
  - `codegen/emitter/FunctionEmitter.scala`: Added `isPointerParam` helper. Both
    `compileRegularLambda` and `compileTailRecursiveLambda` emit `noalias` on consuming pointer params
  - `codegen/emitter/Module.scala`: Native function declarations check both `NativeImpl.memEffect`
    (stdlib) and the return type's own `memEffect` (user-defined `@native[t=*i8, mem=heap]`)
- **Tests:** 6 new tests in `FunctionSignatureTest.scala` covering all combinations
- **Benchmark impact (matmul):**
  - `matmul-opt-mml`: 70.2 ms -> 48.6 ms (31% faster, now matches `matmul-opt-c` at 47.0 ms)
  - `noalias` on `ar_int_new` return lets LLVM prove array pointers don't alias, enabling
    vectorization in the inner loop
- **Verification:** 226 tests pass, `scalafmtAll`/`scalafixAll` clean, ASan clean on
  `move-valid.mml`, all 7 benchmarks compile

### 2026-02-07 Last-use validation for consuming parameters [COMPLETE]

- **Problem:** No proactive error at the consuming call site when a binding is still used later.
  `UseAfterMove` only fires reactively at the later use. Added `ConsumingParamNotLastUse` which
  fires at the consuming call site, complementing the existing error.
- **Approach:** Forward-scan in the let-binding case (`App(Lambda, arg)`). After analyzing `arg`,
  diff `movedAt` keys to find newly-moved bindings, then check if the lambda `body` references them
  via `containsRef`/`containsRefInExpr` tree walkers (skip lambdas that shadow the name).
- **Changes:**
  - `semantic/OwnershipAnalyzer.scala`: Added `consumedVia: Map[String, (Ref, FnParam)]` to
    `OwnershipScope`, recorded in `handleConsumingParam` on valid move. Added `containsRef`/
    `containsRefInExpr` helpers. Forward-scan after `analyzeExpr(arg, scope)` emits
    `ConsumingParamNotLastUse` when moved binding appears in body.
  - `util/error/print/ErrorPrinter.scala`: Fixed `getErrorSourcePosition` to use `ref.span`
    (call site) instead of `param.span` (function definition).
  - `mml/samples/mem/consume-not-last.mml`: negative example
- **Tests:** 4 new tests in `OwnershipAnalyzerTests.scala`:
  - "consuming param not last use detected"
  - "consuming param as last use accepted"
  - "consuming param only use accepted"
  - "independent bindings each consumed once no error"
- **Verification:** 220 tests pass, `scalafmtAll`/`scalafixAll` clean, `mmlcPublishLocal` OK,
  existing memory samples compile without false positives, `move-valid.mml` ASan clean

### 2026-02-07 Partial application ban for consuming parameters [COMPLETE]

- **Problem:** Partial application of functions with `~` (consuming) params silently drops move
  semantics: ExpressionRewriter eta-expands into a Lambda with synthetic params that lack the
  `consuming` flag, leading to potential double-frees.
- **Fix:** `wrapIfUndersaturated` in `ExpressionRewriter.scala` now checks remaining (unapplied)
  params for `consuming = true`. If found, emits `PartialApplicationWithConsuming` error instead
  of eta-expanding. Borrowing params still allow partial application.
- **Changes:**
  - `semantic/package.scala`: Changed error field from `app: App` to `fn: Term` (bare refs aren't `App`)
  - `semantic/ExpressionRewriter.scala`: `wrapIfUndersaturated` returns `Either[NEL[SemanticError], Expr]`,
    checks remaining params for consuming flag; updated two callers in `buildAppChain`
  - 4 error printer files: renamed `app` to `fn` in pattern matches
  - `mml/samples/mem/partial-consume.mml`: negative example
- **Tests:** 2 new tests in `OwnershipAnalyzerTests.scala`:
  - "partial application of function with consuming param is rejected"
  - "saturated call to function with consuming param is accepted"
- **Verification:** 216 tests pass, `scalafmtAll`/`scalafixAll` clean, all 7 benchmarks compile,
  existing memory samples compile without false positives

### 2026-02-07 Add use-after-move regression tests and samples

- **Tests added** to `OwnershipAnalyzerTests.scala`:
  - "use after move to consuming param": double move of same binding to `~` param detected
  - "use after move in expression": read of binding after move detected
  - "no error when each binding moved once": valid single-move usage produces no `UseAfterMove`
- **Samples added:**
  - `mml/samples/mem/use-after-move.mml`: negative example, rejected with `UseAfterMove` error
  - `mml/samples/mem/move-valid.mml`: positive example, compiles and runs ASan clean
- **Verification:** 214 tests pass, `scalafmtAll`/`scalafixAll` clean, samples verified with
  `mmlc` and `mmlc run -s`

### 2026-02-07 Fix `arrays-mem.mml` double-free [COMPLETE]

- **Root cause:** Two issues. (1) `ar_str_set`'s `value` param lacked `consuming = true`,
  so the ownership analyzer inserted a free after the call even though the callee takes
  ownership of the string. (2) The temp wrapper in `OwnershipAnalyzer` generated explicit
  free calls for ALL allocating temps, including those passed to consuming parameters:
  causing a double-free when the callee already owned the value.
- **Fix:**
  - `semantic/package.scala`: Added `consuming = true` to `ar_str_set`'s `value` param
  - `semantic/OwnershipAnalyzer.scala`: Resolve the base function's param list when building
    temp wrappers, track `isConsumed` per arg position, filter consumed temps out of the
    explicit free calls list
- **Verification:** 211 tests pass, ASan clean on `arrays-mem.mml` and all memory samples,
  all 7 benchmarks compile, scalafmt/scalafix clean

### 2026-02-06 Add `inline` keyword

Added `inline fn` / `inline op` syntax that emits LLVM `inlinehint` attribute on functions.
Motivated by LLVM's cost model not inlining `hit_sphere` in the raytracer.

**Changes:**
- `ast/common.scala`: Added `inlineHint: Boolean = false` to `BindingMeta`
- `parser/keywords.scala`: Added `inlineKw` definition, included in `keywords` list
- `parser/members.scala`: `fnDefP`, `binOpDefP`, `unaryOpP` parse optional `inline` before
  `fn`/`op`, set `inlineHint` in `BindingMeta`
- `codegen/emitter/FunctionEmitter.scala`: Both `compileRegularLambda` and
  `compileTailRecursiveLambda` emit `#1` (inlinehint) or `#0` based on `bnd.meta.inlineHint`.
  Note: `compileTailRecursiveLambda` was previously missing any attribute group.
- `codegen/emitter/Module.scala`: Emits two attribute groups: `#0` (base) and `#1`
  (base + `inlinehint`). Both include `target-cpu` when set, or empty/`inlinehint` when not.
- `mml/samples/raytracer.mml`: Marked `vec_add`, `vec_sub`, `vec_scale`, `dot`,
  `length_squared`, `length`, `unit_vector`, `ray_at`, `no_hit`, `hit_sphere` as `inline`
- `docs/design/language-reference.md`: Documented `inline` modifier, added to keywords list

**Results:**
- `inlinehint` successfully caused LLVM to inline `hit_sphere` into `world_hit`/`compute_row`
  (confirmed in optimized IR at `build/out/<triple>/Raytracer_opt.ll`)
- Float SIMD (`<N x float>`) did NOT happen: branchy intersection code prevents
  auto-vectorization. Only `<8 x i8>` byte shuffles (from string/buffer helpers) present.
- 211 tests pass, ASan clean, all benchmarks compile

### 2026-02-06 Fix float literal materialization, raytracer working

- **Root cause:** Two codegen sites materialized literals into registers using hardcoded
  `add i64 0, <value>`, which broke for `Float` (wrong type, wrong instruction, wrong operand).
  The deeper issue: `functionScope: Map[String, (Int, String)]` only stored register+type,
  so literal info was lost on scope entry and had to be "materialized" immediately.
- **Fix:** Introduced `ScopeEntry` case class carrying `register`, `typeName`, `isLiteral`,
  and `literalValue`. Replaced all `(Int, String)` scope entries across 5 files. Scope lookup
  now preserves literal info through references: no materialization instructions emitted at all.
- **Files changed:**
  - `emitter/package.scala`: added `ScopeEntry` case class
  - `emitter/expression/Conditionals.scala`: updated `ExprCompiler` type alias
  - `emitter/ExpressionCompiler.scala`: scope lookup preserves literal info
  - `emitter/expression/Applications.scala`: removed `add i64` materialization in `compileLambdaApp`
  - `emitter/FunctionEmitter.scala`: removed `add <type>` materialization in `compileBoundStatements`,
    updated param scope construction
- **Verification:** 211 tests pass, raytracer produces valid 400x225 PPM (~1MB), ASan clean
  on raytracer and all memory samples, all 7 benchmarks compile, `scalafmtAll`/`scalafixAll` clean.

### 2026-02-06 Update language reference [COMPLETE]

- Renamed `docs/design/semantics.md` to `docs/design/language-reference.md`
- Updated references in `docs/design/compiler-design.md`
- Added **Declarations** section: `let`, `fn`, `op`, `struct`, `type` with syntax and examples
- Added **Program structure** section: modules, semicolons as terminators, expression sequencing
- Added **Control flow** section: conditionals (`if`/`elif`/`else`/`end`), recursion and tail
  calls, limitations (no loops, no nested functions: temporary, pending memory model)
- Added **Structs** as standalone type system section with heap classification
- Added **Scoping rules** to semantic rules
- Updated keywords list (added `struct`, `elif`, `end`, `~`)
- Rewrote error categories in language terms (removed compiler internal type names)
- Changed all code fences from `scala`/`rust` to `mml`
- Clarified juxtaposition as uniform application mechanism (functions, operators, constructors)

### 2026-02-06 Fix struct semantic token miscoloring [COMPLETE]

- **Root cause:** `collectFromTypeStruct` used `declarationToken` which calls
  `keywordLengthFor(TokenType.Type)` = 4 ("type"). But the keyword is "struct" (length 6).
  The name token started 2 chars too early, causing `stru` to show as keyword and `ct MinHeap`
  as type name.
- **Fix:** Replaced `declarationToken` call with direct `tokenAtPos` using correct offset 7
  (6 for "struct" + 1 for space) in `SemanticTokens.scala:collectFromTypeStruct`.
- Verified: 211 tests pass, all 7 benchmarks compile.

### 2026-02-06 LSP crash fix + logging [COMPLETE]

- **Root cause:** `JsonRpc.readContent` used `BufferedReader.read(N)` (characters) but
  `Content-Length` is bytes. astar.mml's 2 em dashes (U+2014, 3 bytes/1 char each)
  caused 4-byte overshoot → next message's headers consumed as JSON body → parse error → exit.
- **JsonRpc.scala:** Rewrote to use raw `InputStream`. Headers parsed byte-by-byte,
  content read as exact byte count then decoded UTF-8. Added diagnostic info to parse errors.
- **LspHandler.scala:** Replaced `IO.println` (stdout corruption) with logger. Added logging
  on every request, response, notification, error, compilation. Threaded `Logger[IO]` as
  explicit parameter.
- **LspServer.scala:** Creates logger via `LspLogging.create`, passes to handler and
  document manager.
- **DocumentManager.scala:** Added compilation logging with error counts.
- **New: LspLogging.scala:** `Logger[IO]` implementation backed by `PrintWriter` to
  `$outputDir/lsp/server.log`. No SLF4J backend needed.
- **Dependencies.scala:** Removed `reload4j` (unused), removed `log4cats-slf4j`
  (SLF4J classpath issues in fat jar). Kept `log4cats-core` only.
- Verified: 211 tests pass, all 7 benchmarks compile, astar.mml opens in VSCode without crash.

### 2026-02-06 Lift inner functions in astar.mml

- `astar.mml`: Uncommented A* implementation, lifting 4 inner functions to top-level
  with captured variables passed as explicit parameters (MML doesn't support closures).
  - `init_g(g_score, inf, total, i)`: was inner to `astar`, captured `g_score`, `size`, `inf`
  - `visit_neighbors(open_set, g_score, walls, goal_idx, width, height, current, cx, cy, dir, heap_sz)`: was inner to `solve`, captured solve's locals + astar's state
  - `solve(open_set, g_score, walls, goal_idx, width, height, h_size)`: was inner to `astar`, mutually recursive with `visit_neighbors`
  - `build_wall(walls, w, i)`: was inner to `main`, captured `walls` and `w`
- Verified: compiles and outputs 198 (correct shortest path cost)

### 2026-02-06 JsonRpc Header Parsing Fix [COMPLETE]

- `JsonRpc.scala`: Modified `readContentLength` to robustly skip headers until an empty line,
  extracting `Content-Length` regardless of order.
- Fixed "exit code 0" crash when clients (VSCode/Neovim) sent `Content-Type` or other headers.
- Verified with updated `repro_lsp.py` injecting `Content-Type`.

### 2026-02-06 LSP Crash Fix [COMPLETE]

- `LspHandler.scala`: Added error recovery to `handleNextMessage`. Unhandled exceptions during
  message processing (e.g., compiler bugs) now log to stderr and keep the server alive
  instead of terminating the process.
- Verified with `repro_lsp.py`.

### 2026-02-06 Right-assoc operator use-after-free fix [COMPLETE]

- `OwnershipAnalyzer.scala`: `insideTempWrapper: Boolean` flag on `OwnershipScope` skips
  all scope-level frees inside temp wrappers (explicit free chain handles cleanup).
  Fixes right-assoc `++` chain use-after-free. 211 tests pass, all mem samples clean
  under ASan, identical IR output, benchmarks compile.

### 2026-02-04 Double-clone fix [COMPLETE]

- `MemoryFunctionGenerator.scala`: `mkCloneFunction()` simplified to just pass field accesses
  to constructor (removed clone wrapping, constructor handles it in codegen)
- `OwnershipAnalyzer.scala`: removed `__mk_` struct constructor special-casing that wrapped
  allocating args with `wrapWithClone()` (lines 767-785)
- Verified: `__clone_User` emits 0 clone calls, `__mk_User` emits 2 (one per heap field)
- 210/210 tests pass, `records-mem.mml` runs with 0 leaks

### 2026-02-04 Phase 3: Struct memory function generation [COMPLETE]

**Summary:** `MemoryFunctionGenerator` phase generates `__free_T` and `__clone_T` functions for
user-defined structs that contain heap-allocated fields.

**Implementation:**
- New file `semantic/MemoryFunctionGenerator.scala`
- `mkFreeFunction()` - generates `__free_StructName` that calls `__free_T` on each heap field
- `mkCloneFunction()` - generates `__clone_StructName` that deep-copies heap fields via `__clone_T`
- Integrated into `SemanticStage.scala` pipeline (runs after TypeChecker, before ResolvablesIndexer)
- Free function params marked `consuming = true` for move semantics

**Note:** Original plan called for `DataDestructor` term in codegen. Approach changed to generate
free functions as regular AST (App nodes). No codegen changes needed; `DataDestructor` is vestigial.

**Verification:**
- `records-mem.mml` runs with 0 leaks (structs with String fields properly freed)
- All tests pass

### 2026-02-04 Struct constructor ownership tracking [COMPLETE]

**Original problem:** `records-mem.mml` had 600 memory leaks because struct constructor calls like
`User name_str role_str` were not recognized as allocating operations.

**Root cause:** `bndAllocates()` only checked for `NativeImpl` with `MemEffect.Alloc`. Struct
constructors have `DataConstructor` in their body, so they were never detected as allocating.
Result: `let u = User name_str role_str` marked `u` as `Borrowed` instead of `Owned`.

**Fix in `OwnershipAnalyzer.scala`:**
1. Modified `bndAllocates(bnd, resolvables)` to also recognize `DataConstructor` terms that return
   heap types (structs with heap fields)
2. Updated two call sites to pass `resolvables` parameter
3. Added `lookupFreeFnId` helper to find the actual free function ID - looks in stdlib first,
   then searches user module resolvables (fixes `__free_User` → `recordsmem___free_User` resolution)
4. Updated `mkFreeCall` to use the helper instead of assuming all free functions are in stdlib

**Verification:**
- All 210 tests pass
- `records-mem.mml` runs without crash
- `leaks --atExit` shows **0 leaks** (was 600)
- All 7 benchmarks compile

### 2026-02-04 ASAN flag [COMPLETE]

Added `--asan`/`-s` CLI flag to enable AddressSanitizer for memory error detection.

**Changes:**
- Added `asan: Boolean` field to `CompilerConfig` (default: false)
- Added `asan: Boolean` to `Command.Build` and `Command.Run` in `CommandLineConfig`
- Added `-s`/`--asan` CLI option
- Added `clangAsanFlags()` helper in `LlvmToolchain` - emits `-fsanitize=address -fno-omit-frame-pointer`
- Updated `clangFlags` construction to include ASAN flags when enabled

**Verification:**
- All 210 tests pass
- `records-mem.mml` runs clean with ASAN (no memory errors)
- Memory leak tests all pass (0 leaks):
  - `to_string_complex.mml`
  - `test_temporaries.mml`
  - `test_unused_locals.mml`

### 2026-02-03

- **Phase F: Remove `__cap` infrastructure**: Final cleanup eliminating runtime `__cap` discriminator.
  - Removed `__cap` field from String, IntArray, StringArray structs in `semantic/package.scala`
  - Removed `__cap` field from C runtime structs (String, IntArray, StringArray, BufferImpl)
  - Removed `__cap > 0` checks from `__free_*` functions - now unconditionally free if data non-null
  - Removed `__cap = -1` from string literal codegen (`Literals.scala`, `Module.scala`)
  - String struct now 16 bytes (was 24): fits in registers, passed as `(i64, i8*)` on x86_64
  - Updated `TbaaEmissionTest` and `FunctionSignatureTest` for new struct layout/ABI
  - All 210 tests pass, 7 benchmarks compile, 0 memory leaks in mixed_ownership_test

- **Phase E: Witness booleans for local mixed ownership**: Replaced runtime `__cap` checks with
  compile-time ownership tracking for local variables with mixed allocation origins.
  - Extended `BindingInfo` with `witness: Option[String]` field
  - Extended `OwnershipScope` with `withMixedOwnership()` and `getWitness()` helpers
  - Added `detectMixedConditional()` - detects XOR allocation (one branch allocates, other doesn't)
  - Added `mkWitnessConditional()` - generates `if cond then true else false` tracking bool
  - Added `mkConditionalFree()` - generates `if __owns_x then __free_T x else ()`
  - Modified `wrapWithFrees()` to emit conditional free for bindings with witnesses
  - LLVM IR: witness compiles to `phi i1` at merge, conditional free to predicted branch
  - Verified: `mixed_ownership_test.mml` runs with 0 leaks
  - Ran `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"` (210/210 tests pass)
  - Ran `make -C benchmark clean && make -C benchmark mml` (all 7 benchmarks compile)

- **Require LLD linker**: Added `ld.lld` to required LLVM tools and configured clang to use it.
  - Added `ld.lld` to `llvmTools` list in `LlvmToolchain.scala`
  - Added `-fuse-ld=lld` flag to `compileBinary` clang invocation
  - Updated install instructions: `brew install llvm lld` (macOS), `apt-get install llvm clang lld` (Linux)
  - LLD is a separate package on both platforms, not bundled with llvm
  - Ran `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"` (210/210 tests pass)
  - Ran `mmlc clean && make -C benchmark clean && make -C benchmark mml` (all 7 benchmarks compile)

### 2026-02-02

- **Expression temporary cleanup**: Fixed memory leaks for orphaned expression temporaries.
  Nested allocating calls like `concat (concat p1 p2) p1` now properly free intermediate results.
  - Insert explicit `__free_*` calls inside temp wrapper structure (not relying on scope-end)
  - Mark `__free_*` params as `consuming=true` so freed bindings become Moved
  - Mark inherited owned bindings as Borrowed inside temp wrappers (prevents double-free)
  - Remove `preExistingOwned` filter; all owned bindings freed at terminal body
  - `test_temporaries.mml`: 0 leaks (was 300+), all memory tests pass

- **Memory prototype current state**: Affine ownership with borrow-by-default functional for
  String, Buffer, IntArray, StringArray. `OwnershipAnalyzer` tracks ownership states, detects
  allocating calls (native `MemEffect.Alloc` + intramodule fixed-point for user functions),
  inserts `__free_T` via CPS rewriting. Runtime `__cap` field discriminates static vs heap.
  Use-after-move detection, inline conditional handling, return escape analysis all working.
  Tests pass with 0 leaks. Remaining: `~` move semantics (partial), struct destructors (Phase 3).

- **AArch64 large-struct ABI fix**: Fixed ABI mismatch causing segfaults and malloc errors in
  cross-compiled aarch64 binaries. Root cause: MML emitted `ptr byval(%struct)` for large struct
  parameters on both x86_64 and aarch64, but AAPCS64 represents indirect struct passing as plain
  `ptr` (no `byval` attribute). Clang-compiled runtime expected plain pointer, MML passed byval.
  - Updated `LargeStructIndirect.scala`: `lowerParamTypes` and `lowerArgs` now emit `ptr` instead
    of `ptr byval(%struct.T) align 8` for aarch64
  - Updated `FunctionSignatureTest.scala`: aarch64 tests now expect plain `ptr` for large structs
  - Verified: cross-compiled sieve/matmul run correctly on aarch64 hardware
  - Ran `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"` (210/210 tests pass)

- **Refactor codegen ABI strategies**: Introduced per-target `AbiStrategy` objects threaded via `CodeGenState`; replaced TargetAbi branching. Added AArch64 HFA regression test (Vec3d/Vec4f) to ensure ≤4 float/double structs stay in registers (no byval/sret). Ran `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"` and `make -C benchmark clean`, `make -C benchmark mml`.

### 2026-02-01 (branch: memory-prototype)

- **Ownership analyzer: conditional allocations**: `termAllocates` now detects allocations in
  conditional branches, propagating ownership so frees are inserted for inline conditionals
  (fixes leak in `mixed_ownership_test.mml`). Ran `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"`.
  Benchmarks reported passing after author fixed `mat-mul-opt.mml` typo.
- **Neovim syntax: comment highlighting fix**: Updated `tooling/nvim-syntax/syntax/mml.vim`
  operator regex to ignore `//` and `/*` sequences so they remain in comment groups.
  Verified headless with `synIDattr`; division `/` still highlights as operator.
  Ran `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"` after the change.

### 2026-01-31 (branch: memory-prototype)

- **x86_64 sret ABI fix**: Fixed crash in `leak_test.mml` caused by ABI mismatch for large
  struct return values. On x86_64, structs >16 bytes must use `sret` (structure return)
  convention where caller passes hidden first pointer for return value.
  - Added `needsSretReturn()` and `lowerNativeReturnType()` to `AbiLowering.scala`
  - Updated `Module.scala`: native declarations with large struct returns now emit
    `declare void @fn(ptr sret(%struct.T) align 8, ...)` instead of `declare %struct.T @fn(...)`
  - Updated `Applications.scala`: call sites allocate space, pass sret pointer, load result
  - Updated `FunctionSignatureTest.scala` to expect sret signature for `join_strings`
  - All 208 tests pass, all benchmarks compile, `leak_test.mml` runs with 0 leaks

- **Memory management prototype**: Implemented Phase 0-2 core functionality.
  - Added `MemEffect` enum and extended `NativeImpl` with `memEffect` field
  - Added `consuming` flag to `FnParam` for `~` move syntax
  - Added `DataDestructor` term for future struct destructor generation
  - Tagged allocating stdlib functions with `MemEffect.Alloc`
  - Added `__free_*` functions to stdlib and runtime
  - Extended parser for `@native[mem=alloc]` and `~param` syntax
  - Created `OwnershipAnalyzer.scala` phase:
    - Detects allocating calls via `MemEffect.Alloc`
    - Tracks ownership state (Owned, Moved, Borrowed, Literal) with type info
    - Inserts `__free_T` calls at scope end using CPS-style wrapping
    - Use-after-move detection working
  - Added `mml/samples/leak_test.mml` - passes with 0 leaks
  - **Problem found:** `__cap` field not implemented in runtime structs.
    Current code works only because literals never reach `__free_*`.
    Mixed conditionals (static vs heap) would crash or leak.
  - **Pending:** Add `__cap` to all heap types (String, IntArray, StringArray, Buffer)

### 2026-01-31 (branch: 2026-01-14-dev)

- **Doc comments parsing**: Fixed backtracking that broke preceding members when a later doc
  comment followed a semicolon. `exprP` now guards statement sequencing against `/* ... */`
  tokens; added regression test `doc comment on later function does not break earlier function`.
  Ran `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"`.

### 2026-01-28 (branch: 2026-01-14-dev)

- **Build as default CLI action**: Made `build` the default command so `mmlc file.mml` compiles
  directly without needing `mmlc build file.mml`. Deleted the `build` subcommand.
  - Changed `Config` default from `Command.Info()` to `Command.Build()`
  - Added top-level build options in parser (`topLevelFileArg`, `topLevelTargetTypeOpt`, etc.)
  - Removed `buildCommand` block; top-level options apply to default Build command
  - Added early help handling in `Main.scala` (checks `-h`/`--help` before parsing)
  - Updated no-file error to show `Usage: mmlc [options] <source-file>`
  - Updated `benchmark/Makefile`: replaced all 31 `mmlc build` occurrences with `mmlc`
  - Updated `context/coding-rules.md` documentation
  - CLI usage: `mmlc file.mml`, `mmlc -v -O 2 file.mml`, `mmlc -x lib file.mml`
  - Subcommands (`run`, `ast`, `ir`, `dev`, `lsp`, `clean`, `info`) still work

### 2026-01-27 (branch: 2026-01-14-dev)

- **Unified build command**: Replaced separate `bin` and `lib` CLI commands with a single
  `build` command. New `-x`/`--target-type` flag selects output type: `exe` (default) or `lib`.
  - Renamed `CompilationMode.Binary` → `CompilationMode.Exe` throughout codebase
  - Renamed `CompilerConfig.binary(...)` → `CompilerConfig.exe(...)`
  - Removed `Command.Bin` and `Command.Lib`, added `Command.Build` with `targetType` field
  - Updated `Main.scala` handler to dispatch based on `targetType`
  - Updated `LspHandler.scala`, `PreCodegenValidator.scala`, `LlvmToolchain.scala`
  - Updated test files: `PreCodegenValidatorSuite.scala`, `FunctionSignatureTest.scala`
  - Updated `benchmark/Makefile`, `context/coding-rules.md`, `CompilerApi.scala` references
  - CLI usage: `mmlc build file.mml` (exe), `mmlc build -x lib file.mml` (library)

### 2026-01-15 (branch: 2026-01-14-dev)

- **LLVM info step reordering**: Moved LLVM tools check earlier in the pipeline
  to fix `resolveTargetCpu` dependency on marker file. Renamed `llvm-check-ok`
  marker to `llvm-info`, renamed `checkLlvmTools` to `gatherLlvmInfo` (now public).
  Added `llvmInfo` step in `CodegenStage` before `emitIr` in both `processNative`
  and `emitIrOnly` pipelines. Removed redundant call from `LlvmToolchain.compileInternal`.
  Fixed clang `-mcpu=` bug: `compileRuntimeBitcode` now uses `config.targetCpu`
  (explicit `--cpu` CLI flag) instead of resolved `targetCpu` (which included
  host CPU from marker). Clang only gets `-mcpu=` when `--cpu` is explicitly passed.
- **Codegen config cleanup**: Refactored `LlvmToolchain` entry points from 12-13
  individual parameters to 4 (`llvmIrPath`, `config`, `resolvedTriple`, `targetCpu`).
  Pass `CompilerConfig` through internal chain instead of unpacking fields.
  Changed `workingDirectory: String` to use `config.outputDir: Path` directly,
  eliminating unnecessary Path→String→Path conversions. Simplified `CodegenStage.compileNative`,
  removed redundant `selectCompileOperation` function. Emitter reviewed - uses
  `CodeGenState` correctly, no changes needed.
- **TARGET CPU fix for cross-compilation**: Removed `--arch`/`-A` flag entirely.
  Fixed `target-cpu` IR attribute logic: explicit `--cpu` uses that value,
  `--target` without `--cpu` omits attribute (cross-compiling), neither uses
  host CPU. Simplified clang flags: `-march=native` for local builds only,
  no CPU flags for cross-compilation. Fixes "skylake is not a recognized
  processor" warnings when cross-compiling to aarch64.
- **LSP in-process compilation**: LSP no longer forks mmlc process for compile
  commands. Added `CompilerApi.compileBinaryQuiet` and `compileLibraryQuiet`
  methods that return error messages instead of printing. `LspHandler` now calls
  these directly.
- **CompilationError.message**: Added `def message: String` to `CompilationError`
  trait. Implemented for all error types: `ParserError`, `TypeError`,
  `SemanticError`, `LlvmCompilationError`, `CodeGenError`, `CompilerError`.
  Plain text messages without ANSI codes.
- **VSCode extension**: Updated to display error messages from LSP and log them
  to output channel.
- **IO.blocking for LSP**: Changed blocking operations to use `IO.blocking`:
  - `FrontEndApi.compile` - parsing/semantic analysis
  - `LlvmToolchain` - process execution, file I/O (collectLlvmToolVersions,
    queryAndCacheTriple, queryLocalTriple, extractRuntimeResource, executeCommand,
    checkLlvmTools, invalidateToolsMarker)
  - `CompilerApi.runPipelineQuiet` - codegen validation
- **LSP commands (clean, ast, ir)**: Added three new LSP commands:
  - `mml.server.clean` - cleans build directory
  - `mml.server.ast` - generates AST file
  - `mml.server.ir` - generates LLVM IR file
  Added `CompilerApi.cleanQuiet`, `processAstQuiet`, `processIrQuiet` methods.
  All use `CompilerConfig.default` (output to `build/` relative to CWD).
  VSCode extension updated with corresponding commands.
- **LSP heartbeat**: Added background heartbeat fiber in `LspServer` that runs
  `IO.sleep(50ms) *> IO.cede` in a loop. Prevents cats-effect CPU starvation
  warnings during active use. Warning still occurs after laptop sleep/wake
  (possibly clock skew) - low priority, revisit later.

### 2026-01-14 (branch: 2026-01-14-dev)

- **Benchmark infrastructure**: Added matmul and ackermann benchmarks,
  Makefile improvements, benchmark results and reports
- **Scoped TBAA**: Added scoped TBAA metadata for better alias analysis
- **Alias scope emitter**: New `AliasScopeEmitter` for alias scope metadata
  on function calls and memory operations
  - **Host CPU attribute fix**: Fixed bug in `LlvmToolchain.readHostCpu` where
    `collectFirst { case line => ... }` matched all lines (total pattern),
    stopping at first line instead of finding `Host CPU:`. Changed to
    `.find(...).map(...)` pattern.

### 2026-01-27

- **Struct constructor rename**: Changed generated struct constructor names from `mk<Name>`
  to `__mk_<Name>` to avoid collisions with user-defined functions. Resolution still works
  via `meta.originalName` matching (same technique as operators). Updated `modules.scala:42`
  and `DataConstructorTests.scala`.

- **CompilerApi consolidation**: Removed redundant `processLibrary` and `compileLibraryQuiet`
  (identical to binary counterparts - both called `processNativeBinary`/`processNativeBinaryQuiet`).
  Renamed `processBinary` → `processNative`, `compileBinaryQuiet` → `compileNativeQuiet`.
  Binary/library distinction carried by `config.mode`. Updated call sites in `Main.scala`
  and `LspHandler.scala`.

### 2026-01-24

- **Deterministic IDs**: Top-level IDs now use `module::<decl-lc>::<name>` with decl class names lowercased; struct fields use `module::typestruct::<structName>::<fieldName>`; stdlib IDs aligned; nested params/lambdas keep owner+UUID.
- **Tests**: `sbt "test; scapegoat; scalafmtAll; scalafixAll; mmlcPublishLocal"`
