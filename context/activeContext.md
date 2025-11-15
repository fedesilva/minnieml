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

- when a function main is detected in a module that with unit return type, 
the codegen needs to add a return 0;

ONLY for the case of a main function.
    

### Review pretty printer

* TypeFns are missing
* function arguments are not indented
* alias printing is too verbose

###  Run Command

Introduce a `run` subcommand that is like `bin` but after building the binary, executes it.

**Objective:**
Add a `run` subcommand that compiles a binary and immediately executes it, returning the program's exit code.

**Analysis:**
- Binary executables are placed at: `{outputDir}/target/{moduleName}-{targetTriple}`
- The `bin` command currently returns `ExitCode.Success` on successful compilation
- The target triple is detected automatically by `LlvmOrchestrator.detectOsTargetTriple`
- We need to capture the executable path after successful compilation and execute it

**Implementation Plan:**

1. **Add `Run` command variant** (CommandLineConfig.scala)
   - Location: Line 10, after `Bin` case
   - Add: `case Run(file, outputDir, outputAst, verbose, targetTriple)` with same fields as `Bin`
   - Add command parser similar to `binCommand` (after line 102)
   - Description: "Compile source file to a binary executable and run it"

2. **Add `Run` handler** (Main.scala)
   - Location: Line 45, after `Bin` case handler
   - Add pattern match for `run: Command.Run`
   - Call new pipeline method: `CompilationPipeline.processRun(path, moduleName, run)`

3. **Add `processRun` method** (CompilationPipeline.scala)
   - Location: After `processBinary` method (line 68)
   - Implementation:
     - Call `compileModule` to parse and validate
     - Call `PreCodegenValidator.validate(CompilationMode.Binary)`
     - Call new `processBinaryModuleAndRun` helper

4. **Add `processBinaryModuleAndRun` helper** (CompilationPipeline.scala)
   - Location: After `processBinaryModule` (line 68)
   - Implementation:
     - Write AST if requested (same as `processBinaryModule`)
     - Call new `CodeGeneration.generateAndRunBinary` instead of `generateNativeOutput`

5. **Add `generateAndRunBinary` method** (CodeGeneration.scala)
   - Location: After `generateNativeOutput` method (line 41)
   - Implementation:
     - Generate LLVM IR from module
     - Call new `LlvmOrchestrator.compileAndRun` instead of `compile`
     - Return the exit code from the executed program

6. **Add `compileAndRun` method** (LlvmOrchestrator.scala)
   - Location: After `compile` method (line 107)
   - Implementation:
     - Call existing `compile` method
     - On success, construct executable path: `{workingDirectory}/target/{moduleName}-{targetTriple}`
     - Detect target triple first (reuse `detectOsTargetTriple`)
     - Execute binary using `ProcessBuilder`
     - Return actual program exit code

**Files to Modify:**
1. `modules/mmlc/src/main/scala/mml/mmlc/CommandLineConfig.scala` - Add `Run` command variant and parser
2. `modules/mmlc/src/main/scala/mml/mmlc/Main.scala` - Add `Run` case handler
3. `modules/mmlc/src/main/scala/mml/mmlc/CompilationPipeline.scala` - Add `processRun` and helper
4. `modules/mmlc/src/main/scala/mml/mmlc/CodeGeneration.scala` - Add `generateAndRunBinary`
5. `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmOrchestrator.scala` - Add `compileAndRun`

**Testing Strategy:**
- Test with existing sample: `mml/samples/concat_print_string.mml`
- Verify: `sbt "run run mml/samples/concat_print_string.mml"`
- Check that program output appears and correct exit code is returned

**Edge Cases:**
- Binary compilation fails → return compilation error (same as `bin`)
- Binary executes but fails → return actual program exit code
- Verbose mode → show both compilation and execution output

**Status:** Ready for implementation - plan approved


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
