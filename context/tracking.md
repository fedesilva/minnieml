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

### Compile runtime to central location

* Compile runtime to ~/.config/mml/cache/runtime/
* add an `init` subcommand to clean and recompile the runtime.
    - or run the init code if it's not been ran when we first compile something 
* update tooling to find the runtime where it's compiled.

### Simple Memory Management Prototype

**Doc:** `docs/brainstorming/mem/1-simple-mem-prototype.md`
**QA:** `context/specs/qa-mem.md`

Affine ownership with borrow-by-default. Enables safe automatic memory management.

**Key points:**
- Borrow by default, explicit move with `~` syntax
- Extend `@native` with `[mem=alloc]` / `[mem=static]` attributes
- New OwnershipAnalyzer phase inserts `__free_T` calls into AST
- No codegen changes - just AST rewriting


**Remaining:**

- [ ] **Fix `arrays-mem.mml` double-free** — missing `consuming` on `ar_str_set`
  - **Root cause:** `ar_str_set` stores the String in the array (takes ownership) but
    the `value` param lacks `consuming = true`, so ownership analyzer inserts a free
    after the call. Later `__free_StringArray` frees the same strings → double-free.
  - **Fix:** Add `consuming = true` to `value` param in `ar_str_set` (package.scala:749)

- [ ] **@native type annotations: explicit deallocation function**
  - Currently assumes `__free_<TypeName>` by naming convention
  - Make explicit: `@native[mem=heap, free=__free_String]`
  - Only for @native types; MML structs generate free functions automatically

- [ ] **`noalias` on allocating function returns** — can do now
  - Return values from `[mem=alloc]` functions are fresh allocations
  - Safe to mark with `noalias` - can't alias anything pre-existing

- [ ] **Move semantics for `~` consuming parameters** (partial)
  - **Done:**
    - `~param` syntax parsing
    - `consuming` flag on `FnParam`
    - Use-after-move detection and error reporting
    - `__free_*` params marked consuming (freed bindings become Moved)
  - **Missing:**
    - Partial application ban (consuming params must be in saturating calls)
    - Last-use validation (arg to `~` param must be final use of that binding)

- [ ] **`noalias` on consuming parameters** — needs move semantics first
  - `~` params can be `noalias` because caller can't use the value afterward
  - Requires solid move semantics enforcement (last-use validation)
  - Cannot add `noalias` to borrowed params - they can alias (`foo x x`)

- [ ] **Testing: find edge cases**
  - Existing tests pass: `leak_test.mml`, `mixed_ownership_test.mml`, `records-mem.mml`
  - Need to explore more complex ownership patterns

- [ ] **Memory test harness** — similar to benchmark infrastructure
  - **Approach:**
    1. Compile and run each sample with `--asan` (AddressSanitizer)
       - Detects: double-free, use-after-free, buffer overflows
    2. Compile and run without ASAN, check with `leaks --atExit --`
       - Detects: memory leaks
  - **Samples:** `mml/samples/mem/`
    - `test_unused_locals.mml`
    - `test_temporaries.mml`
    - `test_leaks.mml`
    - `records-mem.mml`
    - `to_string_complex.mml`
  - **Infrastructure:**
    - Makefile or script to run both ASAN and leaks passes
    - Fail on any ASAN error or non-zero leak count
    - Report summary of results


### Runtime: time functions

TBD


---

## Recent Changes

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

- **Phase E: Sidecar booleans for local mixed ownership**: Replaced runtime `__cap` checks with
  compile-time ownership tracking for local variables with mixed allocation origins.
  - Extended `BindingInfo` with `sidecar: Option[String]` field
  - Extended `OwnershipScope` with `withMixedOwnership()` and `getSidecar()` helpers
  - Added `detectMixedConditional()` - detects XOR allocation (one branch allocates, other doesn't)
  - Added `mkSidecarConditional()` - generates `if cond then true else false` tracking bool
  - Added `mkConditionalFree()` - generates `if __owns_x then __free_T x else ()`
  - Modified `wrapWithFrees()` to emit conditional free for bindings with sidecars
  - LLVM IR: sidecar compiles to `phi i1` at merge, conditional free to predicted branch
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
