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

Depends on: Pre Codegen Validation

**Entry Point Requirement Relaxation:**
- In `FunctionEmitter.scala`, convert `main(): Unit` to LLVM `define i32 @main()` with `ret i32 0`
- Only applies to functions named `main` with `void` return type
- Allows users to write idiomatic `fn main() = ...` while generating proper native entry point

**Status:** In progress - codegen fix needed

**Note:** Validation that `main` exists for binary mode is handled by "Pre Codegen Validation" task.

### Review pretty printer

* TypeFns are missing
* function arguments are not indented
* alias printing is too verbose

###  Run Command

Introduce a `run` subcommand that is like `bin` but after building the binary, executes it.

### Pre Codegen Validation (active)

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

**Status:** TODO - new phase needed

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

**Status:** TODO - new phase needed

*Plan:*
1.  **Create `PreCodegenValidator.scala`**:
    *   A new file will be created at `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/PreCodegenValidator.scala`.
    *   This file will contain a `PreCodegenValidator` object with a `validate` method. This method will take the `CompilationMode` and the current `SemanticPhaseState` and return the updated state.
    *   The `validate` method will iterate over a list of check functions, allowing for easy extension in the future.

2.  **Implement Entry Point Check**:
    *   The first check will be `validateEntryPoint`.
    *   If the `CompilationMode` is `Binary`, this function will ensure that a `main` function is defined.
    *   It will validate that `main` has zero parameters and a return type of `Unit` or a type compatible with `i32`.
    *   If the validation fails, it will add a specific `SemanticError` to the state.

3.  **Update Compiler Pipeline**:
    *   The `CompilationMode` will be passed through the compilation pipeline, starting from `CompilationPipeline.scala`, through `CompilerApi`, to `SemanticApi.rewriteModule`.
    *   The new `PreCodegenValidator.validate` phase will be added to the end of the semantic analysis pipeline in `SemanticApi.scala`, right after the `TypeChecker`.

4.  **Add Tests**:
    *   A new test suite, `PreCodegenValidatorSuite.scala`, will be created.
    *   Tests will be added to verify that the `main` function check works correctly for both valid and invalid cases.

This approach will create a new, extensible validation phase and integrate it into the existing compiler architecture.

### CodeGen Holes

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
