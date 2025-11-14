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

### Fix Partial Application (HIGH PRIORITY - INCOMPLETE)

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
     - Baseline (TODO)
      - Walk ExpressionCompiler.compileApp + FnDef emission to confirm how args + TypeFn metadata flow today.
      - Identify exact failure point for stored partial apps (which node produces TypeFn at codegen, what IR we emit).
      - Map where runtime declarations live so we know where to inject allocator/closure structs.
  - Runtime Support (TODO)
      - Add void *mml_alloc(uint64_t size) in modules/mmlc-lib/src/main/resources/mml_runtime.c, forwarding to malloc.
      - Ensure headers/prototypes match (probably #include <stdint.h> already there).
      - Update LLVM emitter to declare declare i8* @mml_alloc(i64) once (state.functionDeclarations).
      - Verify build scripts copy the updated runtime into binaries.
  - Closure Representation (TODO)
      - Extend CodeGenState with slots to remember closure struct/env declarations (e.g., %Closure = { i8*, i8* }).
      - When first needed, emit %Closure type def and store in nativeTypes.
      - Decide on env naming scheme (e.g., %Env_fn_arityPrefix_hash), track layouts per unique capture set.
      - Provide helper builders to emit loads/stores for closure struct fields.
  - Partial Application Lowering (TODO)
      - In compileApp, after collecting args, inspect the callee’s TypeFn signature to know total arity + param llvm types.
      - If args < arity → build closure creation sequence:
          - Emit env struct, allocate via @mml_alloc, store captured values.
          - Emit closure allocation, store thunk pointer + env pointer.
          - Return %Closure* typed value; add resulting TypeSpec mapping (TypeFn minus consumed params).
      - If args == arity → existing direct call path, but ensure we bypass closure packaging.
      - If args > arity (over-application) → repeatedly call closure apply path.
      - Ensure type lowering returns %Closure* for TypeFn results so later consumers know how to call them.
  - Thunk Generation & Reuse (TODO)
      - Create data structure (maybe inside CodeGenState) to queue thunk definitions: key by function symbol + num captured args.
      - For each partial application site, register needed thunk(s); include metadata: remaining param types, capture types, final callee.
      - During module finalization (before output assembly), emit all queued thunks:
          - Function signature define %Closure* @fn_thunk_k(i8* %env, <nextType> %arg).
          - Inside: cast env to %Env_k, load captures, allocate %Env_{k+1}, store captures + new arg, allocate closure, store next thunk pointer.
          - Final thunk (arity-1) instead returns raw result by calling base fn directly.
      - Reuse identical thunks/env layouts across sites to reduce duplication.
  - Validation (TODO)
      - Update/expand tests: ensure mml/samples/partial-app.mml compiles/runs; add unit test covering stored partial application.
      - Run sbt test (per instructions, full suite), capture status.
      - Run scalafmt/scalafix if any Scala files touched; ensure runtime C compiled.


### Verify our compiled binaries return status code 0 

Successful execution should return exit code 0.

### Review pretty printer

* TypeFns are missing
* function arguments are not indented
* alias printing is too verbose

###  Run Command

Introduce a `run` subcommand that is like `bin` but after building the binary, executes it.

### Pre Codegen Validation

* New Phase
* Run checks on the ast and fail if they don't pass
* Fist checks:
    - No holes are allowed (???)



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
