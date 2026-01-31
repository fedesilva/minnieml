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

### Simple Memory Management Prototype

**Doc:** `docs/brainstorming/mem-man/1-simple-mem-prototype.md`
**Plan:** `~/.claude/plans/nifty-coalescing-phoenix.md`

Linear ownership with borrow-by-default. Enables safe automatic memory management
and unlocks `noalias` parameter attributes for LLVM optimization.

**Key points:**
- Borrow by default, explicit move with `~` syntax
- Extend `@native` with `[mem=alloc]` / `[mem=static]` attributes
- New OwnershipAnalyzer phase inserts `__free_T` calls into AST
- No codegen changes - just AST rewriting

**Progress:**

- [x] **Phase 0: AST & Infrastructure**
  - [x] Added `MemEffect` enum (Alloc, Static) to `ast/terms.scala`
  - [x] Extended `NativeImpl` with `memEffect: Option[MemEffect]` field
  - [x] Added `consuming: Boolean` flag to `FnParam` in `ast/common.scala`
  - [x] Added `DataDestructor` term (for future struct destructor generation)
  - [x] Modified `mkFn` helper in `semantic/package.scala` to accept `MemEffect`
  - [x] Tagged allocating functions with `MemEffect.Alloc`: readline, concat, to_string, mkBuffer*, ar_*_new, read_line_fd
  - [x] Added `__free_*` functions to stdlib: __free_String, __free_Buffer, __free_IntArray, __free_StringArray
  - [x] Added runtime free functions to `mml_runtime.c`
  - [x] Updated pretty printers for new AST nodes

- [x] **Phase 1: Parser Support**
  - [x] Extended `nativeImplP` for `[mem=alloc]` / `[mem=static]` syntax
  - [x] Added `~` prefix parsing for consuming parameters in `fnParamP`

- [ ] **Phase 2: OwnershipAnalyzer** (in progress)
  - [x] Created `OwnershipAnalyzer.scala` with basic structure
  - [x] Added `OwnershipState` enum (Owned, Moved, Borrowed, Literal)
  - [x] Added `OwnershipScope` for tracking binding states
  - [x] Added ownership errors to `SemanticError`: UseAfterMove, ConsumingParamNotLastUse, PartialApplicationWithConsuming, ConditionalOwnershipMismatch
  - [x] Integrated into `SemanticStage.scala` pipeline
  - [x] Basic use-after-move detection working
  - [ ] **TODO:** Detect `App` calls with `MemEffect.Alloc` and mark bindings as Owned
  - [ ] **TODO:** Insert `App(Ref("__free_T"), Ref(binding))` at scope end
  - [ ] **TODO:** Track type information for bindings to select correct `__free_T`
  - [ ] **TODO:** Implement move semantics for `~` consuming parameters

- [ ] **Phase 3: Struct Destructors** 
  - [ ] Generate `__free_StructName` for user structs alongside `__mk_StructName`
  - [ ] Handle `DataDestructor` in codegen to free heap-allocated fields

- [ ] **Phase 4: Testing**
  - [ ] Write test programs with allocations
  - [ ] Verify with `leaks --atExit` that leak count is 0
  - [ ] Find edge cases, iterate



### Runtime: time functions

TBD


---

## Recent Changes

### 2026-01-31 (branch: memory-prototype)

- **Memory management prototype (partial)**: Implemented Phase 0-1 and started Phase 2.
  - Added `MemEffect` enum and extended `NativeImpl` with `memEffect` field
  - Added `consuming` flag to `FnParam` for `~` move syntax
  - Added `DataDestructor` term for future struct destructor generation
  - Tagged allocating stdlib functions with `MemEffect.Alloc`
  - Added `__free_*` functions to stdlib and runtime
  - Extended parser for `@native[mem=alloc]` and `~param` syntax
  - Created `OwnershipAnalyzer.scala` phase with basic use-after-move detection
  - Added ownership-related errors to `SemanticError`
  - **Pending:** Insert `__free_T` calls, track allocations from App nodes

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
