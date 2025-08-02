# MML Active Context

## Current Focus

Ability to compile simple programs:
* basic types
* error accumulation
* explicit types
* recursion (tco)
* codegen app chains
* type resolver (error if all types are not resolved)


## Issues

### High priority

* **TypeChecker Bug - Missing Type Validation**: TypeChecker incorrectly allows `println (5 + 3)` where `println` expects `String` but receives `Int`. This should fail during semantic analysis with a proper type mismatch error, but currently passes with "No errors". The TypeChecker is not properly validating function argument types against parameter types.
  - Test case: `fn main(): () = println (5 + 3);` should fail but doesn't
  - file `mml/samples/should-fail.mml`
  - should make a unit test

* 🐞 **Nullary Function Call Is Not Lowered to IR Call**
  see `memory-bank/bugs/nullary-function-bug.md` 


### Medium Priority



## Next Steps

## Infer return types
see `specs/infer-return-type.md`

## NativeOpDescriptor Validation

 * We have the new `NativeOpDescriptor` machinery in place.
 * We now need a semantic phase that walks the ast and validates:
  * that op selectors are known
  * that only operators use the op=selector field


### Codegen Update (Ticket #156) - NEARING COMPLETION
The implementation plan is detailed in `memory-bank/specs/codegen-update.md`. Progress:

**Goal:** 
Compile 

* √ `mml/samples/print_string.mml` 
* √ `mml/samples/print_string_concat.mml`
* `mml/samples/test_to_string.mml`
* √ `mml/samples/test_print_add.mml`

*   **Block 1: AST & Parser Changes:** ✓ COMPLETED - AST and parser support new `@native:` syntax
*   **Block 2: Semantic Analysis Changes:** ✓ COMPLETED - TypeResolver now handles native struct definitions  
*   **Block 3: Codegen - LLVM Type Emission:** ✓ COMPLETED - LLVM type emission works for native types
*   **Block 4: Codegen - Expression Compiler Refactoring:** MOSTLY COMPLETED
    - ✓ Fixed function signature derivation from AST type annotations
    - ✓ Unit type `()` correctly converted to `void` in LLVM IR
    - ✓ Fixed native operator code generation for boolean operators (`and`, `or`, `not`)
    - ❌ **REMAINING:** Replace hardcoded `i32` types in `compileApp` and `compileTerm` with proper type resolution using `getLlvmType` helper
      - Complete analysis in: `memory-bank/bugs/hardcoded-i32.md`
        - NEEDS Attention:
          * This might be already fixed, OR there might still be hiddens instances
          * **inspect and list them before commiting to a change.**
      - ✓ Should enable `print_string_concat.mml` to compile successfully

#### Pending

  * Strings are treated specially.
  * Strings are just native structs, they should ALL be compiled in a general way.
    * The `ExpressionCompiler.scala` file has notes on the literal String compiler function.
    * it hardcodes a bunch of types, too. those should be picked up from the ast.
  * *We need to write a small spec for this before commiting to specific changes.*


### Infer fn/op as FnType and rewrite them as bnd to FnType

* needs a spec


## Recent Changes

* **(2025-08-01)** **COMPLETED:** Fixed TypeResolver bug - TypeAlias resolution with nested TypeRefs
  - **Issue:** TypeRef nodes inside TypeAlias objects were not being resolved, causing "Unresolved type reference: Int64" errors
  - **Root Cause:** When TypeRefs pointed to TypeAlias objects, they pointed to the original TypeAlias that still contained unresolved internal TypeRefs
  - **Solution:** Implemented three-phase resolution strategy:
    1. Build initial type map from all TypeDef/TypeAlias members
    2. Resolve type definitions themselves to ensure TypeAlias objects have resolved internal TypeRefs
    3. Resolve all members using the fully resolved type map
  - **Implementation:** Modified `rewriteModule` to use `resolveTypeMap`, added missing TypeSpec cases (TypeSeq, TypeScheme, InvalidType)
  - **Result:** All TypeRef nodes throughout the AST are now properly resolved
  - **Verification:** `mml/samples/test_print_add.mml` compiles successfully and outputs "8"
  - **Testing:** All 121 tests pass with no regressions

* **(2025-07-31)** **COMPLETED:** Fixed native boolean operator code generation (Codegen Update #156)
  - **Issue:** Boolean operators (`and`, `or`, `not`) generated function calls instead of native LLVM instructions
  - **Root Cause:** `compileApp` treated operator App chains as regular function calls, generating `call i1 @and(...)` instead of native `and i1` instructions
  - **Solution:** Enhanced `compileApp` with native operator detection and generation helpers
  - **Implementation:** Added `getNativeOperator()`, `generateNativeBinaryInstruction()`, `generateNativeUnaryInstruction()`
  - **Result:** Boolean expressions now generate efficient native LLVM instructions (`and i1`, `or i1`, `xor i1`)
  - **Verification:** `mml/samples/and-not-or.mml` compiles successfully and outputs correct result "NADA"
  - **Testing:** All 121 tests pass, applied scalafix and scalafmt

* **(2025-07-26)** **COMPLETED:** Completed implementation of parsing error handling for invalid identifiers (#168)
  - **Parser Enhancement:** Updated `fnDefP`, `binOpDefP`, and `unaryOpP` to use `operatorIdOrError` and `bindingIdOrError` wrappers
  - **Result:** Parser now gracefully handles invalid identifiers in `let`, `fn`, and `op` definitions

* **(2025-07-07)** **COMPLETED:** Implemented comma-separated parameter syntax for functions and operators
  - **Syntax Change:** `fn concat(a: String b: String)` → `fn concat(a: String, b: String)`
  - **Result:** All tests pass, syntax is more familiar and consistent with other languages








### Future work        

* implement protocols 
* recursion 
* modules
