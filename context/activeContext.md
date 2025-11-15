# MML Active Context

## Current Focus

- TypeFn for all 
- Improve module system
    - parse nested modules
    - better scope management

## Issues

### High priority
### Medium Priority

## Next Steps

### Fix Partial Application (INCOMPLETE)

**Current Status**:

✅ **Working**:
- `partial-inline-simple.mml`: `((concat "Hola, ") "fede")` - compiles and runs
- `partial-app-inline.mml` also works
- `double-and-sum.mml` / `apply.mml`: grouped applications now rewrite correctly

❌ **Not Working**:
- `partial-app.mml`: `let greet = concat "Hola, "; ... greet "fede"` - fails at codegen with "No LLVM type mapping for TypeSpec: TypeFn"
    - need to teach codegen to ... codegen

* Spec
    - The codegen strategy for closures is now specified in:
    - `context/specs/codegen-closure.md`

* Plan (WIP)
    - see `context/tracking/codegen-closure.md`

### Verify our compiled binaries return status code 0

- done: REQUIRED AUTHOR VALIDATION

### Review pretty printer

* TypeFns are missing
* function arguments are not indented
* alias printing is too verbose

###  Run Command

Introduce a `run` subcommand that is like `bin` but after building the binary, executes it.

### Pre Codegen Validation (active)

- done: required author validaton

### Pre Codegen Validation

**New Phase (Phase 8, after TypeChecker):**
- Run checks on the AST and fail before codegen if they don't pass

**Checks:**
- **Binary Entry Point Validation** (when compilation mode = Binary):
  - Validate `main` function exists in the module
  - Must have zero parameters (for now)
  - Return type must be `Unit` or `i32`-compatible type (like `Int32`)
  - Emit clear error: "Binary compilation requires valid entry point: fn main(): Unit"

**Implementation Notes:**
- Need to thread compilation mode through semantic pipeline or create mode-aware validator
- Should run after TypeChecker so we have fully type-checked AST

**Status:** In progress - new phase needed

*Plan (Focused and Contained):*
The core issue is that the `PreCodegenValidator` needs the `CompilationMode` to perform its checks, but threading this `CompilationMode` through the entire `CompilerApi` and `SemanticApi` creates unnecessary coupling and has led to numerous compilation errors.

The most focused approach is to:

1.  **Modify `CompilerApi.compileString` to return `CompilerEffect[SemanticPhaseState]` instead of `CompilerEffect[Module]`.** This allows the `CompilationPipeline` to access the `SemanticPhaseState` (which includes errors) directly after semantic analysis.
2.  **In `CompilationPipeline.scala`, after `CompilerApi.compileString` returns the `CompilerEffect[SemanticPhaseState]`:**
    *   Call `PreCodegenValidator.validate` with the appropriate `CompilationMode` (Binary, Library, Ast, Ir) and the `SemanticPhaseState`.
    *   Handle any new errors added by the `PreCodegenValidator`.
    *   Extract the `Module` from the (potentially updated) `SemanticPhaseState` for subsequent code generation steps.
3.  **Revert `SemanticApi.scala` to its original state** (i.e., `rewriteModule` does not take `CompilationMode` and does not call `PreCodegenValidator`).
4.  **Revert `CompilerApi.scala` to its original state** (i.e., `compileState` and `compileString` do not take `CompilationMode`).
5.  **Revert `CodeGenApi.scala` to its original state** (i.e., `compileString` does not take `CompilationMode` and does not import `CompilationMode`).

This approach ensures that the `PreCodegenValidator` logic is isolated and only introduces the `CompilationMode` at the point where it's needed for validation within the `CompilationPipeline`.

**Current Progress:**
- `PreCodegenValidator.scala` created and basic structure implemented.
- `SemanticError.InvalidEntryPoint` definition updated to use `SrcSpan`.
- `PreCodegenValidator.scala` imports and logic fixed.
- `PreCodegenValidatorSuite.scala` created and tests added, `mml` code corrected.

**Current Issues:**
- The compiler pipeline (`CompilerApi`, `SemanticApi`, `CompilationPipeline`, `CodeGenApi`) is currently in an inconsistent state due to previous attempts to thread `CompilationMode`. These changes need to be reverted and the new focused plan applied.

### CodeGen Holes

## Technical Debt

- **`InvalidEntryPoint` Error Printing:** The `InvalidEntryPoint` semantic error is not fully handled in the error printers (`ErrorPrinter.scala`, `SemanticErrorPrinter.scala`, `SourceCodeExtractor.scala`). The pattern matches need to be updated to provide proper error messages for this case.


**Goal:** Handle `???` (hole) expressions in code generation properly

**Current behavior:** Holes type-check but likely cause codegen to fail or produce invalid IR

**Implementation:**
- Detect `Hole` nodes during expression compilation in `ExpressionCompiler`
- Emit LLVM IR that calls runtime function `mml_not_implemented()`
- Add `void mml_not_implemented()` to `mml_runtime.c`:
  - Print "(not_implemented)" to stderr
  - Exit with code 2 (distinct from normal error exit code 1)
- Update LLVM emitter to declare `declare void @mml_not_implemented()` in function declarations

**Status:** TODO - codegen may currently fail on holes



## Recent Changes

- **Partial application support (#179 complete)**: ExpressionRewriter now treats all refs uniformly—any ref (FnDef, Bnd, FnParam) followed by arguments builds an application chain. Inline partial applications like `((concat "Hola, ") "fede")` compile and run successfully. Stored partial applications type-check but hit codegen limitation (no first-class functions yet). Changed `IsAtom` extractor to exclude refs, fixed TermGroup double-wrapping. All 130 tests pass.
- **Grouped application fix**: Term groups are rewritten into atomic terms before application folding, so expressions like `sum (double 1) 2` now form the correct AST and pass both rewriting and type-checking tests.
- Type mismatch diagnostics now name call sites when possible (new `expectedBy` label threads through TypeChecker + printers).
- TypeChecker + codegen now store/consume full `TypeFn` signatures for functions and operators (#178 complete): lowering wraps params/return into `TypeFn`, application typing consumes them, and codegen extracts LLVM signatures; diagnostics now surface partially applied function types.
- **AST restructured (#170 complete)**: Split 501-line AstNode.scala into 6 focused files (common.scala, native.scala, module.scala, members.scala, types.scala, terms.scala). Sealed traits preserved where critical (Term, TypeSpec, OpDef, LiteralValue, NativeType) for exhaustiveness checking. Strategic unsealing of cross-file traits (AstNode, Member, Decl, Native). All 130 tests pass, code formatted and linted.

- **Module parsing hardened (Spec 174 complete)**: Parser now recovers from missing semicolons and malformed constructs; top-level modules no longer distinguish anon vs named; `Module.isImplicit` removed; `ModuleTests` regression suite added.
- **Semantic testing improved**: `SemanticApi.rewriteModule` returns `SemanticPhaseState` (module + errors); `CompilerApi.compileState` exposes state; `semState` helper enables full-pipeline testing with error inspection.
- **Parser cuts removed**: Top-level parsing recovers instead of aborting on errors.
- **SourceInfo tracking**: Line positions tracked efficiently without rescanning source.
- Unit type vs value: `Unit` is the type, `()` is the value; parser rejects `()` in type positions.
- Semantic phases solidified: TypeChecker lowers ascriptions, infers return types, validates calls; TypeResolver covers alias chains.
- LLVM codegen reworked: Native op descriptors drive emission, literal globals are static, boolean ops emit direct LLVM.
- Docs refreshed: design-and-semantics.md matches current pipeline.
