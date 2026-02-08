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

* Follow the rules in those documents faithfully.
* No exceptions

## Active Tasks

### Memory Management 

Affine ownership with borrow-by-default. Enables safe automatic memory management.

- Borrow by default, explicit move with `~` syntax
- OwnershipAnalyzer phase inserts `__free_T` calls into AST
- No codegen changes - just AST rewriting

**Remaining:**

#### Bug Fixes

- [x] **Fix `arrays-mem.mml` double-free** — missing `consuming` on `ar_str_set` [COMPLETE]
  - **Root cause:** `ar_str_set` stores the String in the array (takes ownership) but
    the `value` param lacks `consuming = true`, so ownership analyzer inserts a free
    after the call. Later `__free_StringArray` frees the same strings -> double-free.
  - **Fix:** Add `consuming = true` to `value` param in `ar_str_set` (package.scala:749)

#### Move Semantics (`~` consuming parameters)

- [x] `~param` syntax parsing [COMPLETE]
- [x] `consuming` flag on `FnParam` [COMPLETE]
- [x] Use-after-move detection and error reporting [COMPLETE]
- [x] `__free_*` params marked consuming [COMPLETE]
- [x] **Partial application ban** — consuming params must be in saturating calls [COMPLETE]
  - A closure capturing an owned value via `~` would need `FnOnce` semantics.
    Ban this for now: consuming args only in fully-applied calls.
- [x] **Last-use validation** — arg to `~` param must be final use of that binding [COMPLETE]
  - If not last use, emit diagnostic suggesting `clone` or restructuring.

#### Borrow Safety

- [ ] **Borrow escape enforcement** — borrows cannot escape a function
  - A borrowed value must not be: returned, stored into a container, or captured
    by an escaping closure.
  - Enforcement rules:
    1. Return type owned -> returned value must be owned (not borrowed)
    2. Struct constructor args consume (see below) -> can't pass a borrow to a sink
    3. Closures: deferred until closures exist, but design must account for it
  - This is the critical invariant that prevents use-after-free from borrow misuse.

#### Struct Ownership

- [ ] **Struct constructors as sinks (move-in)**
  - Currently constructors clone their args (borrow + internal copy).
  - Change to: constructors consume all heap-typed fields (move semantics).
  - No `~` annotation needed on struct fields — consuming is the default for
    constructors. This is not opt-out.
  - Callers who want to retain a value must explicitly `clone` before passing.
  - Impacts: `MemoryFunctionGenerator`, `OwnershipAnalyzer` constructor handling,
    `FunctionEmitter` (remove clone-on-store for constructor params).
- [ ] **Move-only structs** — assigning a struct with owned fields is a move
  - `let a = mkFoo x; let b = a` moves `a` into `b`, `a` is invalid after.
  - Use-after-move detection already exists for leaf values; extend to structs.
  - Explicit `clone` required to duplicate a struct with owned fields.
  - Must enforce: no implicit shallow copy of aggregates with owned fields (invariant I3/A2).
- [ ] **Recursive/nested struct destructors** — consider later
  - A struct containing another struct with owned fields needs recursive free.
  - `__free_Outer` must call `__free_Inner` on nested owned struct fields.
  - `MemoryFunctionGenerator` may already handle this; needs verification and testing.

#### Native Type Improvements

- [ ] **`@native` type annotations: explicit deallocation function**
  - Currently assumes `__free_<TypeName>` by naming convention.
  - Make explicit: `@native[mem=heap, free=__free_String]`
  - Only for `@native` types; MML structs generate free functions automatically.
- [ ] **Native struct constructors**
  - Removes the need for constructor functions in C.
  - Same logic as MML struct constructors.

#### Optimization

- [x] **`noalias` on allocating function returns** [COMPLETE]
  - Return values from `[mem=alloc]` functions are fresh allocations.
  - Safe to mark with `noalias` — can't alias anything pre-existing.
- [x] **`noalias` on consuming parameters** [COMPLETE]
  - `~` params can be `noalias` because caller can't use the value afterward.
  - Requires solid move semantics enforcement (last-use validation).

#### OOM Policy

- [ ] **Decide OOM invariant** — Policy A (null => empty) vs Policy B (trap on OOM)
  - Policy A: if `ptr == null` then `len == 0`, consumers handle gracefully.
  - Policy B: allocation failure traps/aborts before returning.
  - Decision needed. Once chosen, enforce globally across all producers/consumers.
  - See `qa-mem.md` I0 for full invariant descriptions.

#### Testing & Infrastructure

- [ ] **Edge case testing** — see `mem-next.md` for test matrix
  - Aliasing / copy hazards (T1, T2)
  - Move invalidation across rebinding (T3, T4)
  - Aggregate ownership (T5-T8) — addressed by move-only structs + clone
  - Nested conditional cleanup (T9-T11)
  - OOM invariant verification (T12) — after OOM policy decision
- [ ] **Memory test harness** — automated ASan + leaks pass
  - Compile and run each sample with `--asan` (double-free, use-after-free, overflows)
  - Compile and run without ASan, check with `leaks --atExit --` (leaks)
  - Samples: `mml/samples/mem/`
  - Fail on any ASan error or non-zero leak count

#### Code Quality (from `qa-mem.md`)

- [ ] **Split `analyzeTerm`** (~380 lines) into helpers — High priority
  - Extract: `analyzeLetBinding`, `analyzeRegularApp`, `collectCurriedArgs`,
    `wrapAllocatingArgs`
- [ ] **Extract hardcoded stdlib IDs** to shared constants object — Medium
  - `"stdlib::typedef::Unit"` repeated 6+ places, `"stdlib::typedef::Bool"` 3+ places
- [ ] **Refactor mutable state in `.map()`** to `foldLeft` — Medium
  - Lines 741-756: `var currentScope` + `var argErrors` mutated inside `.map()`
- [ ] **Remove `__free_String` silent fallback** — Low
  - Line 296: `getOrElse("__free_String")` masks bugs; should error instead.
- [ ] **Use binding metadata for constructor detection** — Low
  - Replace `name.startsWith("__mk_")` with `BindingOrigin.DataConstructor` lookup.

---

## Recent Changes

### 2026-02-07 Add `noalias` attributes to LLVM IR emission [COMPLETE]

- **Two cases:** (1) Native functions with `MemEffect.Alloc` returning `NativePointer` types get
  `noalias` on return. (2) Consuming (`~`) parameters of `NativePointer` type get `noalias` in
  user function definitions.
- **Key distinction:** Only `NativePointer` types (Buffer, CharPtr, etc.) are actual LLVM pointers.
  `NativeStruct` types (String, arrays) are value types at LLVM level — `noalias` doesn't apply.
- **Changes:**
  - `ast/TypeUtils.scala`: Added `isPointerType` — checks if type resolves to `NativePointer`
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
  semantics — ExpressionRewriter eta-expands into a Lambda with synthetic params that lack the
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
  - "use after move to consuming param" — double move of same binding to `~` param detected
  - "use after move in expression" — read of binding after move detected
  - "no error when each binding moved once" — valid single-move usage produces no `UseAfterMove`
- **Samples added:**
  - `mml/samples/mem/use-after-move.mml` — negative example, rejected with `UseAfterMove` error
  - `mml/samples/mem/move-valid.mml` — positive example, compiles and runs ASan clean
- **Verification:** 214 tests pass, `scalafmtAll`/`scalafixAll` clean, samples verified with
  `mmlc` and `mmlc run -s`

### 2026-02-07 Fix `arrays-mem.mml` double-free [COMPLETE]

- **Root cause:** Two issues. (1) `ar_str_set`'s `value` param lacked `consuming = true`,
  so the ownership analyzer inserted a free after the call even though the callee takes
  ownership of the string. (2) The temp wrapper in `OwnershipAnalyzer` generated explicit
  free calls for ALL allocating temps, including those passed to consuming parameters —
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
- `codegen/emitter/Module.scala`: Emits two attribute groups — `#0` (base) and `#1`
  (base + `inlinehint`). Both include `target-cpu` when set, or empty/`inlinehint` when not.
- `mml/samples/raytracer.mml`: Marked `vec_add`, `vec_sub`, `vec_scale`, `dot`,
  `length_squared`, `length`, `unit_vector`, `ray_at`, `no_hit`, `hit_sphere` as `inline`
- `docs/design/language-reference.md`: Documented `inline` modifier, added to keywords list

**Results:**
- `inlinehint` successfully caused LLVM to inline `hit_sphere` into `world_hit`/`compute_row`
  (confirmed in optimized IR at `build/out/<triple>/Raytracer_opt.ll`)
- Float SIMD (`<N x float>`) did NOT happen — branchy intersection code prevents
  auto-vectorization. Only `<8 x i8>` byte shuffles (from string/buffer helpers) present.
- 211 tests pass, ASan clean, all benchmarks compile

### 2026-02-06 Fix float literal materialization, raytracer working

- **Root cause:** Two codegen sites materialized literals into registers using hardcoded
  `add i64 0, <value>`, which broke for `Float` (wrong type, wrong instruction, wrong operand).
  The deeper issue: `functionScope: Map[String, (Int, String)]` only stored register+type,
  so literal info was lost on scope entry and had to be "materialized" immediately.
- **Fix:** Introduced `ScopeEntry` case class carrying `register`, `typeName`, `isLiteral`,
  and `literalValue`. Replaced all `(Int, String)` scope entries across 5 files. Scope lookup
  now preserves literal info through references — no materialization instructions emitted at all.
- **Files changed:**
  - `emitter/package.scala` — added `ScopeEntry` case class
  - `emitter/expression/Conditionals.scala` — updated `ExprCompiler` type alias
  - `emitter/ExpressionCompiler.scala` — scope lookup preserves literal info
  - `emitter/expression/Applications.scala` — removed `add i64` materialization in `compileLambdaApp`
  - `emitter/FunctionEmitter.scala` — removed `add <type>` materialization in `compileBoundStatements`,
    updated param scope construction
- **Verification:** 211 tests pass, raytracer produces valid 400x225 PPM (~1MB), ASan clean
  on raytracer and all memory samples, all 7 benchmarks compile, `scalafmtAll`/`scalafixAll` clean.

### 2026-02-06 Update language reference [COMPLETE]

- Renamed `docs/design/semantics.md` to `docs/design/language-reference.md`
- Updated references in `docs/design/compiler-design.md`
- Added **Declarations** section: `let`, `fn`, `op`, `struct`, `type` with syntax and examples
- Added **Program structure** section: modules, semicolons as terminators, expression sequencing
- Added **Control flow** section: conditionals (`if`/`elif`/`else`/`end`), recursion and tail
  calls, limitations (no loops, no nested functions — temporary, pending memory model)
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
  - `init_g(g_score, inf, total, i)` — was inner to `astar`, captured `g_score`, `size`, `inf`
  - `visit_neighbors(open_set, g_score, walls, goal_idx, width, height, current, cx, cy, dir, heap_sz)` — was inner to `solve`, captured solve's locals + astar's state
  - `solve(open_set, g_score, walls, goal_idx, width, height, h_size)` — was inner to `astar`, mutually recursive with `visit_neighbors`
  - `build_wall(walls, w, i)` — was inner to `main`, captured `walls` and `w`
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
