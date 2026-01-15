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


### LSP semantic tokens for statement chains [COMPLETE]


### Soft References [COMPLETE - pending validation]

see `context/specs/soft-references.md`

---

## Recent Changes

### 2026-01-14: Add workspace/symbol LSP support
- Added `workspaceSymbolProvider` capability to server
- Implemented `workspace/symbol` handler in LspHandler
- Added `collectSymbols` to AstLookup using ResolvablesIndex (not member iteration)
- Filters out stdlib items (ID prefix `stdlib::`) and synthetic `__*` names
- Added SymbolKind and SymbolInformation types to LspTypes
- Files: lsp/LspHandler.scala, lsp/LspTypes.scala, lsp/AstLookup.scala

### 2026-01-14: Add LSP support to Neovim plugin
- Added `after/plugin/mml_lsp.lua` to auto-configure MML LSP with nvim-lspconfig
- Updated install script to copy LSP config file
- Updated README with LSP features and prerequisites
- Files: tooling/nvim-syntax/after/plugin/mml_lsp.lua (new), tooling/install-nvim-syntax.sh,
  tooling/nvim-syntax/README.md

### 2026-01-14: Fix LSP restart command
- Added 100ms delay in LspHandler before shutdown to ensure response is fully transmitted
- Added custom ErrorHandler in VSCode extension to suppress expected errors during restart
- Added `isRestarting` flag to track restart state and prevent error logging
- Files: lsp/LspHandler.scala, tooling/vscode/src/extension.ts

### 2026-01-14: LSP semantic tokens consistency
- Skip synthetic `__*` lambda params in semantic token emission to prevent overlapping highlights
- Added SemanticTokensTests using `hola` to confirm `println` calls get Function tokens (prints raw `main` binding)
- Files: lsp/SemanticTokens.scala, lsp/SemanticTokensTests.scala

### 2026-01-14: Fix LSP definition for locals
- Rebuilt resolvables index after type checking to include function params and local lets
- Added ResolvablesIndexer phase after type-checker
- Added FindDefinitionTests for local param/let definition lookups
- Files: semantic/ResolvablesIndexer.scala, compiler/SemanticStage.scala,
  lsp/FindDefinitionTests.scala

### 2026-01-13: Improve span collection and LSP find references fix
- Fixed parser spans for fnDefP, binOpDefP, unaryOpP: Bnd span is now just the name, Lambda span starts at `(`
- Changed `ReferenceTarget` in AstLookup from storing `Resolvable` objects to storing `String` IDs
- Updated `memberContains` to check both bnd.span and bnd.value.span for Bnd members
- All 3 find references tests pass: function, parameter, let binding
- Files: parser/members.scala, lsp/AstLookup.scala, test FindReferencesTests.scala (new)

### 2026-01-13: Soft References implementation [pending validation]
- Replaced `resolvedAs: Option[Resolvable]` with `resolvedId: Option[String]` in Ref/TypeRef
- Added stable IDs to all Resolvable/ResolvableType nodes (stdlib: `stdlib::name`, user: `user::name::uuid`)
- Created `ResolvablesIndex` case class with `lookup`/`updated`/`updatedType` methods
- Added `resolvables: ResolvablesIndex` field to Module
- Created new `IdAssigner.scala` phase: assigns IDs to all user-defined members before resolution
- Updated `semantic/package.scala`: `stdlibId` and `stdlibTypeRef` helpers for stdlib injection
- Updated `TypeResolver`: Phase 4 updates resolvables index with resolved members
- Updated `codegen/emitter/Module.scala`: passes `module.resolvables` to CodeGenState
- Updated `compiler/SemanticStage.scala`: added IdAssigner to pipeline before TypeResolver
- Fixed 3 `Option.get` violations in `ExpressionRewriter.scala` (restructured pattern matching)
- All 200 tests pass, scapegoat clean, benchmarks verified
- Files: ast/*.scala, semantic/IdAssigner.scala, semantic/package.scala, semantic/TypeResolver.scala,
  semantic/RefResolver.scala, semantic/ExpressionRewriter.scala, codegen/emitter/Module.scala,
  compiler/SemanticStage.scala

### 2026-01-13: LSP find references (incomplete)
- Added `textDocument/references` handler and server capability
- Added reference lookup traversal in AstLookup
- Current issue: references for functions/params/let bindings still broken due to resolvedAs identity
  mismatch after rewrites
- Files: lsp/AstLookup.scala, lsp/LspHandler.scala, lsp/LspTypes.scala

### 2026-01-13: Fix semantic token coloring
- Parser spans now stop before trailing whitespace/comments for expressions, types, literals, members
- Semantic tokens now use safe lengths, avoid overlap, and tokenize selection fields separately
- Files: parser/*.scala, lsp/SemanticTokens.scala

### 2026-01-12: letExprP span fix (incomplete)
- Changed FnParam in expression-level `let` to use identifier span instead of full expression span
- File: parser/expressions.scala:202-227
- Added spP(info) markers around identifier: `spP(info) ~ letKw ~ spP(info) ~ bindingIdOrError ~ spP(info)`
- FnParam now uses `span(idStart, idEnd)` instead of `span(start, end)`
- Did NOT fix the hover-on-comments bug in fill_random

### 2026-01-12: Fix hover-on-comments in single-branch if
- Single-branch `if` now synthesizes the implicit `Unit` else with a zero-length span at `end`
- Prevents comment lines inside `then` blocks from matching the Unit literal on hover
- File: parser/expressions.scala (ifSingleBranchExprP, ifSingleBranchExprMemberP)

### 2026-01-12: LSP Semantic Tokens
- Added `textDocument/semanticTokens/full` request handling for rich syntax highlighting
- Token types: function, operator, parameter, variable, type, string, number, keyword
- Token modifiers: declaration, readonly (all bindings are readonly in MML)
- Collects tokens from: member keywords, member names, lambda params, refs (resolved), literals, conditionals
- Fixed span position calculation: Bnd spans start at NAME, not keyword
- Keyword positions calculated backwards from name: `keywordCol = name_col - 1 - keywordLen`
- Delta encoding for LSP protocol (0-based line/col from 1-based source spans)
- New file: lsp/SemanticTokens.scala
- Updated: LspTypes.scala (SemanticTokensLegend, SemanticTokensResult), LspHandler.scala

### 2026-01-12: Simple LSP implementation
- Added `mmlc lsp` command: stdio-based LSP server for IDE integration
- LSP features: diagnostics (parse + semantic errors), hover (types at cursor)
- AstLookup: span-based position lookup with exclusive end bounds
- Fixed hover edge cases: body-before-params in Lambda, arg-before-fn in App, qualifier handling for field access
- Server commands via executeCommandProvider: restart, compileBin, compileLib
- VS Code extension: wires LSP client, registers commands in palette
- New files: lsp/LspServer.scala, LspHandler.scala, DocumentManager.scala, Diagnostics.scala, AstLookup.scala, JsonRpc.scala, LspTypes.scala

### 2026-01-12: Dev mode command (part 1)
- Added `dev` command: validates source (parse + semantic) without LLVM
- Watch loop using Java NIO WatchService with `IO.interruptible` for Ctrl+C support
- Output: green "compiled, ok" on success, "Changes detected: <ISO timestamp>" on change
- Keeps last `CompilerState` in `Ref` for future LSP use
- Files: LlvmToolchain.scala, CompilerConfig.scala, CommandLineConfig.scala, Main.scala
- New files: dev/FileWatcher.scala, dev/DevLoop.scala

### 2026-01-12: Struct codegen completion
- Removed codegen/TBAA/StructLayout fallbacks for unresolved types
- TypeResolver runs a second pass so struct fields resolve through aliases
- Completed full quality chain and benchmarks for struct codegen

### 2026-01-11: Selection parsing order fix
- Implement selection `p.name`
- Moved selection parsing before operator refs so `p.name` parses as a selection term
- Selection-related tests pass (StructTests, TypeCheckerTests)

### 2026-01-11: Struct grammar test rename
- Renamed DataTypesTests to StructTests (file and suite)

### 2026-01-11: Struct constructor synthesis
- Parser treats uppercase terms as Refs and synthesizes mk<TypeName> constructor bindings
- Constructor body uses DataConstructor with return TypeRef
- Added DataConstructorTests for constructor synthesis and term parsing

### 2026-01-11: Struct type checking pass
- TypeChecker compares struct types nominally by name (including TypeRef comparisons)
- TypeResolver updates struct fields via Field.copy with resolved type specs
- Type pretty-printer now uses TypeOpenRecord for the structural record case
- Codegen type naming handles TypeStruct with the correct arity

### 2026-01-06: Single branch if syntax
- Added `ifSingleBranchExprP` and `ifSingleBranchExprMemberP` parsers for `if cond then body end` syntax
- Parser synthesizes `LiteralUnit` for missing else branch, sets `typeSpec`/`typeAsc` to Unit
- Reviewed TypeChecker, CodeGen, ExpressionRewriter - no changes needed
- Updated 10 files to use new syntax: fizzbuzz.mml, fizzbuzz2.mml, fizzbuzz-notco.mml, sieve.mml, quicksort.mml, mat-mul.mml (both samples/ and benchmark/)
- All 189 tests pass, benchmarks produce correct results

### 2026-01-06: Remove all Option.get usage (scapegoat cleanup)
- Fixed 16 `Option.get` violations across 10 files
- Added `ensureTbaaRootWithId` method returning `(CodeGenState, Int)` tuple
- Refactored operator extractors (IsOpRef, IsBinOpRef, IsPrefixOpRef, IsPostfixOpRef) to use `.collectFirst` + `.flatMap`
- Used tuple pattern matching for CodegenStage warning and ErrorPrinter
- Scapegoat now reports 0 errors, 0 warnings
- All 189 tests pass

### 2026-01-06: Native type syntax update
- Changed primitive type syntax from `@native:i64` to `@native[t=i64]`
- Changed pointer type syntax from `@native:*i8` to `@native[t=*i8]`
- Changed struct syntax from `@native:{ ... }` to `@native { ... }` (removed colon)
- Parser: added `nativeBracketTypeP` for bracket syntax, struct now parsed directly
- Updated tests: NativeTypeTests, NativeTypeEmissionTest, TypeResolverNativeStructTests
- Updated pretty printers: Type.scala, Member.scala
- Updated docs: semantics.md
- All 189 tests pass

### 2026-01-06: ExpressionCompiler refactor + test fixes
- Refactored ExpressionCompiler.scala (1045 → 245 lines) by moving helpers to expression/ subpackage
- Created expression/package.scala with extractors (HasNativeOpTemplate, HasFunctionTemplate) and helpers
- Created expression/Literals.scala (compileLiteralString, compileHole)
- Created expression/Conditionals.scala (compileCond)
- Created expression/Applications.scala (collectArgsAndFunction, compileLambdaApp, compileNativeOp, etc.)
- Filled in 2 pending TypeChecker tests: MissingParameterType and TypeMismatch in let binding
- Fixed doc comment # margin stripping: added .stripMargin('#') in literals.scala docCommentP
- All 189 tests pass, 0 ignored

### 2026-01-05: Extend Native Templates to Functions
- Functions can now use `@native[tpl="..."]` for inline LLVM IR (e.g., LLVM intrinsics)
- Added `getFunctionTemplate()` helper in ExpressionCompiler
- Updated `compileApp()` to check for function templates before emitCall
- Template placeholders: `%operand` (single arg), `%operand1`, `%operand2`, ... (multiple)
- Example: `fn ctpop(x: Int): Int = @native[tpl="call i64 @llvm.ctpop.i64(i64 %operand)"]`
- Added tests: single-arg template (`ctpop`), multi-arg template (`mymax` with `smax`)
- Files: ExpressionCompiler.scala, semantics.md, FunctionSignatureTest.scala

### 2026-01-05: Remove Native Operator Registry
- Replaced `@native[op=X]` syntax with `@native[tpl="..."]` for inline LLVM IR templates
- Template placeholders: `%type`, `%operand1`, `%operand2` (binary), `%operand` (unary)
- Codegen now prepends `%result =` to templates automatically
- Deleted NativeOpDescriptor.scala (the registry)
- Updated AST: `NativeImpl.nativeOp` → `NativeImpl.nativeTpl`
- Updated parser, semantic/package.scala, ExpressionCompiler
- Updated docs: semantics.md, compiler-design.md, ackermann article, mechanical-sympathy brainstorm
- Files: terms.scala, expressions.scala, package.scala, ExpressionCompiler.scala, TypeCheckerTests.scala

### 2026-01-05: Optimization level flag (-O/--opt)
- Added `-O/--opt` flag to `bin`, `run`, and `lib` commands to control LLVM optimizations (0-3).
- Propagated `optLevel` through `CompilerConfig` to `LlvmToolchain`.
- `LlvmToolchain` now uses the dynamic optimization level for `opt` and `clang` commands.
- Files: CommandLineConfig.scala, CompilerConfig.scala, Main.scala, CodegenStage.scala, LlvmToolchain.scala

### 2026-01-05: Fix `mmlc run` binary path after -o/--output changes- `executeBinary` in CompilerApi.scala now matches LlvmToolchain naming logic
- Uses lowercase module name, only appends triple when `-T/--target` is explicit
- File: CompilerApi.scala

### 2026-01-05: Conditional benchmark result logging
- Hyperfine JSON/CSV/Markdown exports now gated by `LOG_BENCH_RESULTS=1` env var
- Default: `make bench-*` runs benchmarks without file output
- With env var: `LOG_BENCH_RESULTS=1 make bench-*` writes to results/YYYY-MM-DD/
- Added `EXPORT_FLAGS` and `RESULTS_DEP` conditional variables
- Updated targets: bench-sieve, bench-quicksort, bench-matmul, bench-nqueens, bench-euclidean
- File: benchmark/Makefile

### 2026-01-05: Fully silent output by default
- "Compiling module X" now conditional on `-p/--print-phases` (was unconditional)
- "Done" message now conditional on `-p/--print-phases` (was unconditional)
- Default successful compilation produces no output
- Files: LlvmToolchain.scala, CompilerApi.scala

### 2026-01-05: Hide phase printing behind -p/--print-phases flag
- Default output now minimal: "Compiling module X" + "Done"
- Added `-p/--print-phases` flag to bin/run/lib commands for verbose phase output
- Added `printPhases` field to CompilerConfig and CommandLineConfig
- Modified LlvmToolchain logging: `logPhase()`/`logInfo()` now conditional on printPhases
- Added `logModule()` for unconditional module name output
- Threaded printPhases through CodegenStage to LlvmToolchain
- Simplified CompilerApi success message to just "Done"
- Files: CommandLineConfig, CompilerConfig, Main, LlvmToolchain, CodegenStage, CompilerApi

### 2026-01-05: Output naming improvements (-o/--output)
- Binary names now lowercase by default: `HelloWorld` → `helloworld`
- Library names now lowercase by default: `HelloWorld` → `helloworld.o`
- Triple suffix only appended when `-T/--target` is explicitly passed
- Added `-o/--output` flag for custom artifact names
- Renamed `-o/--output-dir` to `-b/--build-dir`
- Files: CompilerConfig, CommandLineConfig, Main, CodegenStage, LlvmToolchain

### 2026-01-05: Fix nested conditional block label collisions
- Fixed `ExpressionCompiler` Cond handling: block labels (`thenBB`, `elseBB`, `mergeBB`) now computed from `condRes.state.nextRegister` instead of stale `state.nextRegister`
- Added `stateWithReservedLabels` to advance register counter before compiling branches, preventing nested conditionals from reusing the same block labels
- `--no-tco` flag now works correctly; nqueens.mml compiles and outputs correct result (14200)

### 2026-01-04: Fix TCO exit block tracking and literal materialization
- Fixed `compileBoundStatements` exit block tracking: changed `_` to `prevExitBlock` in fold pattern
- Added literal materialization in `compileBoundStatements` with type-aware LLVM type mapping (`mmlTypeNameToLlvm`)
- Fixed lambda application exit block in `ExpressionCompiler`: preserve `argRes.exitBlock` through body compilation
- Fixed `extractNestedLatchCond`: pass `accStatements` through recursive calls for proper latchPrefix propagation
- Fixed nqueens.mml algorithm bug: `count + (solve ...)` instead of just `solve ...`
- euclidean-ext.mml now compiles and runs (Checksum: 5010954496756)
- nqueens.mml now compiles and outputs correct result (14200 for n=12)
- All 180 tests pass

### 2026-01-04: Benchmark Makefile overhaul
- Copied sieve.mml, quicksort.mml, mat-mul.mml to benchmark/
- Added mmlc build targets with platform-aware triple detection (darwin→macosx normalization)
- Added C/Go benchmark targets: quicksort-c, matmul-c, matmul-restricted-c, matmul-go, matmul-hoisted-go
- Added hyperfine benchmark targets: bench-sieve, bench-quicksort, bench-matmul, bench (runs all)
- Fixed matmul-hoisted.go unused variable

### 2026-01-04: Nested conditional support for tail recursion loopification
- Extended ExitBranch to support compound conditions: `conditions: List[(Expr, Boolean)]`
- Added `latchPrefix` field to ExitBranch for statements that must execute before exit check
- Added `extractNestedLatchCond` helper for nested conditional pattern extraction
- Defensive fallback: unrecognized patterns emit `TailRecPatternUnsupported` warning and fall back to regular codegen
- Added `warnings: List[CompilerWarning]` to CodeGenState with propagation to CompilerState
- sieve_loop (nested if-else wrapping recursive call) now loopifies correctly
- All sieve.mml functions now loopify: init_sieve, clear_multiples, find_next_prime, sieve_loop, count_loop, isqrt

### 2026-01-04: TBAA hard error on missing type info
- Changed TBAA functions from `Option`-based silent failures to `Either[CodeGenError, ...]`
- `getMmlTypeName`, `computeStructFieldLayout`, `resolveNativeStruct` now return Either
- `ensureTbaaStructForTypeDef`: `CodeGenState` → `Either[CodeGenError, CodeGenState]`
- `getTbaaStructFieldTag`: `(State, Option[String])` → `Either[CodeGenError, (State, String)]`
- Error messages include context: struct name, field name, specific failure reason
- Updated call sites in `Module.scala` and `ExpressionCompiler.scala`

### 2026-01-04: Fix Bool (i1) layout in size/alignment helpers
- Added `i1` handling to `sizeOfLlvmType` and `alignOfLlvmType` (returns 1 byte)
- Previously, Bool defaulted to 8-byte size/alignment, causing incorrect struct layouts

### 2026-01-04: TBAA scalar naming consistency fix
- Fixed inconsistency where different code paths produced different TBAA scalar names
- Removed `llvmTypeToTbaaScalar` which collapsed MML types into generic "int"/"any pointer"
- Now uses MML type names consistently (Int64, CharPtr, etc.) to preserve type distinctions
- `getTbaaTag` uses `td.name` for all TypeDef cases
- `computeStructFieldLayout` extracts MML type name via `getMmlTypeName`
- Test updated: "TBAA has proper hierarchy: root, MML types, struct, access tags"

### 2026-01-04: TBAA P1 nested struct fix + refactor
- Created `codegen/emitter/tbaa/` package for TBAA-related code
- Moved `TbaaEmitter.scala` to new package with updated imports
- Added `StructLayout.scala` with `sizeOf`/`alignOf` for recursive struct size computation
- `computeStructFieldLayout` now uses `StructLayout` to correctly handle nested structs
- Example: `{ i64, Inner, i64 }` where Inner is 16 bytes now correctly computes 32 bytes total
- Added 2 unit tests for nested struct layout

### 2026-01-04: TBAA P0 alignment fix
- Added `alignOfLlvmType(llvmType): Int` - returns ABI alignment (1/2/4/8 bytes)
- Added `alignTo(offset, alignment): Int` - aligns offset to boundary
- Updated `computeStructFieldLayout` to use alignment when computing field offsets
- Example: `{ i32, ptr }` now correctly places ptr at offset 8, not 4
- Added test: "TBAA field offsets honor alignment (String has ptr at offset 8, not 4)"

### 2026-01-04: TBAA root format and struct registration scaffolding
- Changed root node from `!{!0}` to `!{!"MML TBAA Root"}` for cleaner AA debugging
- Added `TbaaEmitter.ensureTbaaStructForTypeDef()` for generic TBAA struct node registration
- Updated `Module.scala` to register TBAA struct nodes for module-level native structs
- Note: prelude types (IntArray/StringArray) still need verification

### 2026-01-04: TBAA field-level access tags
- Fixed unsafe TBAA: struct fields now get distinct tags with correct offsets
- Added `llvmTypeToTbaaScalar`, `sizeOfLlvmType` helpers to package.scala
- Added `getTbaaStruct`, `getTbaaFieldAccessTag` methods to CodeGenState
- Added `getTbaaStructFieldTag` to TbaaEmitter (resolves NativeStruct, computes field layout)
- Updated ExpressionCompiler LiteralString to use field-specific tags
- Added TbaaEmissionTest.scala with 3 tests

### 2026-01-04: Strict type propagation in codegen
- Removed all type fallbacks in ExpressionCompiler (lines 269, 271, 743, 790)
- Missing types now error instead of defaulting to "Int" or "i32"
- Fixed `getMmlTypeForOp` to read from `opRef.typeSpec` instead of `bnd.typeSpec`
- Fixed `extendedScope` tuple to include `(bindingReg, argRes.typeName)`
- Deleted dead `OperatorEmitter.scala` (not imported anywhere)

### 2026-01-04: Strict Naming and TBAA Fix for Type Collapsing
- Problem: `IntArray` and `String` were collapsing into `%String` in optimized IR due to structural identity.
- Fix: Switched `mml_runtime.c` to use tagged structs (`struct String { ... }`).
- Fix: Updated MML codegen to use `%struct.` prefix for native structs to match Clang's convention.
- Implemented `TbaaEmitter` to generate Type-Based Alias Analysis metadata.
- Integrated TBAA tags into `ExpressionCompiler` for `LiteralString` and global `Ref` loads.
- Switched `NativeStruct` to use `List` instead of `Map` for deterministic field ordering.
- Removed hardcoded type string checks in codegen, using proper `TypeSpec` resolution.
- Result: `String` and `IntArray` now maintain distinct identities in the LLVM IR, even with opaque pointers and optimization.

### 2026-01-04: TCO PHI node predecessor fix
- Fixed LLVM IR verification error when latch statements contain conditionals (e.g., fizzbuzz)
- Bug: PHI nodes claimed values came from `loop.latch` but actually came from merge blocks
- Fix: `compileBoundStatements` now tracks and returns exitBlock from compiled expressions
- `compileTailRecArgs` accepts initial exitBlock to propagate correct predecessor through arg compilation

### 2026-01-04: Pipeline compile and run separation
- Moved binary execution from CodegenStage to CompilerApi
- CompilerApi.processNativeRun now: compile → print timings → execute binary
- Added executeBinary method using ProcessBuilder with inheritIO
- Removed dead code from LlvmToolchain (compileAndRun*, executeProgram, signal helpers)
- Simplified CodegenStage by removing runBinary parameter from compileNative/selectCompileOperation

### 2026-01-04: Tail Recursion Loopification
- Added TailRecursionDetector semantic phase: marks lambdas with self-calls in terminal position
- Added loopification codegen in FunctionEmitter: emits phi nodes, loop.header/latch/exit blocks
- Data structures: ExitBranch (condition polarity), TailRecPattern (pre/latch statements, exits, args), BoundStatement (let-binding tracking)
- Supported patterns: simple recursion, pre-statements (sequences), elif chains, latch statements, let-bindings
- Fallback to regular codegen when pattern extraction fails
- sieve.mml: init_sieve, clear_multiples, find_next_prime, count_loop, isqrt now loopify

### 2026-01-03: Arch-specific ABI emitters + triple-suffixed IR
- Added ABI-aware native lowering with per-arch emitters (x86_64 split, aarch64 pack)
- Added target hint conflict warning and ABI selection based on explicit target
- LLVM IR output now includes target triple suffix (fallback `-unknown`) and toolchain strips it

### 2026-01-03: LLVM toolchain file IO refactor
- Moved LLVM IR file writing into CodegenStage and pass `.ll` paths to the toolchain
- Renamed LlvmOrchestrator to LlvmToolchain across code and docs

### 2026-01-03: LLVM toolchain opt pipeline
- Run opt on linked bitcode and keep opt IR optional via --emit-opt-ir
- Use llvm-dis for opt IR dumps and drop -flto on the final clang link
- Added emit-opt-ir plumbing through CLI/config and tool checks

### 2026-01-03: FunctionSignatureTest ABI expectations
- Updated native signature expectations to match ABI-split String params
- Re-enabled the signature test and removed debug printing

### 2026-01-02: Aarch64 stack probing flag control
- Found llc crash caused by clang emitting `probe-stack=__chkstk_darwin`
- Added `--no-stack-check` to pass `-fno-stack-check` on demand
- Added `-A/--arch` and `-C/--cpu` to forward `-march`/`-mcpu` when `-T` is provided

### 2026-01-02: Monomorphic Arrays and ABI struct splitting
- Added IntArray/StringArray to runtime (mml_runtime.c) with FORCE_INLINE
- Injected array types and 8 functions in semantic prelude (package.scala)
- Sieve of Eratosthenes benchmark (mml/samples/sieve.mml)
- Fixed x86_64 ABI: codegen splits small structs into register args for native calls
- Added Go/C sieve benchmarks (benchmark/sieve.go, benchmark/sieve.c)
- Performance: MML 4.5ms user (7% slower than C, 20% faster than Go)

### 2026-01-02: Module source path propagation
- Module AST carries optional source path, piped from CompilerApi to Parser
- Compiler errors now show file header when source path is available

### 2026-01-02: Holes, typer bug fix and runtime flush fixes
- Typechecker now propagates expected types through apps/conds/nested exprs and reports real binding names for holes
- Added runtime + codegen support for holes via `__mml_sys_hole` and a codegen test
- Added boolean literal codegen
- Fixed readline buffering by flushing stdout before reads and reordered runtime setup

### 2026-01-02: Readline Ctrl+D handling
- `readline()` now clears EOF so Ctrl+D no longer loops endlessly on stdin

### 2026-01-02: Target triple auto-detection and caching
- Fixed triple mismatch warning during LLVM linking
- Added `clang -print-target-triple` auto-detection when no user triple provided
- Cached detected triple in `build/local-target-triple` (cleared by `mmlc clean`)
- Normalized darwin/macosx triples to strip version (e.g., `x86_64-apple-macosx`)
- Refactored CodegenStage: `process()` pure validation, `processIrOnly()`/`processNative()` effectful
- User-provided triples bypass cache and are used as-is (free-form)
- `mmlc info --triples` shows current auto-detected triple plus sample triples
- Added `queryLocalTriple` for non-caching queries (info command doesn't create `build/`)

### 2026-01-02: Pipeline refactor completed
- Renamed `CompilerApi` → `FrontEndApi` (ingest + semantic)
- Renamed `CompilationPipeline` → `CompilerApi` (full pipeline)
- Deduplicated pipeline methods via `runFrontend`/`runPipeline` helpers
- All stages (IngestStage, SemanticStage, CodegenStage) now use `|>` with `timePhase`

### 2026-01-01: Name mangling and synthesized main
- All user-defined members now lowered as `modulename_membername` (e.g., `hello_main`)
- Native functions keep original names (no prefix)
- Added `entryPoint` field to `CompilerState`, set by `PreCodegenValidator` for Binary mode
- Added `moduleName` and `mangleName()` to `CodeGenState`
- Synthesized C-style `main` function: calls user entry point, then `mml_sys_flush()`, returns 0 (Unit) or propagates exit code (Int)
- Updated `getResolvedName` to mangle non-native function/value references
- Updated `FunctionSignatureTest` assertions for new mangled names

### 2026-01-01: Pipeline config refactor
- `CompilationPipeline` public methods now receive `CompilerConfig` directly instead of individual params
- Added `outputAst` field to `CompilerConfig`
- Added smart constructors: `CompilerConfig.binary()`, `.library()`, `.ast()`, `.ir()`
- Removed `toCompilerConfig` helper from pipeline

### 2026-01-01: Timings instrumentation expanded
- Timings now track stage + phase, print per-phase rows, stage totals, and a total line
- LLVM toolchain timings are captured per step for `llvm-*` phases

### 2026-01-01: CLI timing flag and build info tweaks
- Added `-t/--time` flag to compile-related CLI commands and fixed `mmlc info -t` for triples
- Renamed IR emission timing label to `emit-llvm-ir` for clarity

### 2026-01-01: Yolo pipeline inspection updates
- `rewrite`/`rewritePath` now run ingest + all semantic phases and dump AST after each phase
- Optional LLVM IR dump added after pre-codegen validation

### 2026-01-01: Standard operator injection
- Added `%` as a native operator (srem) and documented it in design docs

### 2025-12-29: Parser fix for if-else chains
- Added `end` keyword to terminate if-else expressions (fixes bug where `;` after else branch consumed subsequent statements)
- Added `elif` keyword for else-if chains without needing multiple `end`s
- Syntax: `if cond then expr elif cond then expr else expr end`
- Updated VSCode and Vim syntax highlighting for new keywords
- Updated all sample files to use new syntax

### 2025-12-29: Runtime buffer auto-flush
- Changed `buffer_write` and `buffer_writeln` to auto-flush when buffer is about to overflow
- Removed dynamic realloc growth - buffer now has fixed capacity and flushes when full

### 2025-12-31: Buffer int writes and bitcode linking
- Added `buffer_write_int`/`buffer_writeln_int` with manual int formatting and buffer int ops
- Added FORCE_INLINE macro usage in `mml_runtime.c` for hot buffer helpers
- Binary pipeline links runtime bitcode via `llvm-link` for inlining
- Operator mangling keeps underscores in alphanumeric operator names
