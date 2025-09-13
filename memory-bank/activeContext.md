# MML Active Context

## Current Focus

Ability to compile simple programs:
 - finalize simple type checker
 - normalize tree a bit (fndef -> let fn)
 - tail recursion


## Issues

### High priority


* **TypeChecker Bug - Missing Type Validation**: TypeChecker incorrectly allows `println (5 + 3)` where `println` expects `String` but receives `Int`. This should fail during semantic analysis with a proper type mismatch error, but currently passes with "No errors". The TypeChecker is not properly validating function argument types against parameter types.
  - Test case: `fn main(): () = println (5 + 3);` should fail but doesn't
  - file `mml/samples/should-fail.mml`
  - should make a unit test

* **Nullary Function Call Is Not Lowered to IR Call**
  see `memory-bank/bugs/nullary-function-bug.md` 


### Medium Priority

## Next Steps



## Infer return types
see `specs/infer-return-type.md`

**Progress:**
- **TypeChecker:** Updated to infer return types for functions and operators when not explicitly provided. It now correctly skips validation for `NativeImpl` bodies.
- **CodeGen:**
  - `Module.scala`: `emitFnDef` now uses the inferred `fn.typeSpec` for the return type.
  - `ExpressionCompiler.scala`: `compileApp` now uses the inferred `app.typeSpec` for the return type of function applications.
- **Testing:** Added a new test case to `TypeCheckerTests.scala` to validate the inference logic. All tests are passing.
- **Validation:** The user has confirmed that `mml/samples/simple_string.mml` now compiles successfully.

The task is now awaiting final approval from the user.

## `()` in type position should generate a ref to Unit. 

 * () should be treated like a literal when in value pos (I think this works, verify)
 * 
 * update all the samples and tests that might use it.
 * We will just write `Unit` for the unit type.



### Codegen Update (Ticket #156) - NEARING COMPLETION
The implementation plan is detailed in `memory-bank/specs/codegen-update.md`. Progress:

**Goal:** 

**Remove hardcoded stuff completely***

#### Strings hardcoded logic, should be common for native structs

  * Strings are treated specially.
  * Strings are just native structs, they should ALL be compiled in a general way.
    * The `ExpressionCompiler.scala` file has notes on the literal String compiler function.
    * it hardcodes a bunch of types, too. those should be picked up from the ast.
  * *We need to write a small spec for this before commiting to specific changes.*


### Infer fn/op as FnType and rewrite them as bnd to FnType

* see `docs/brainstorming/lambdify.md`


## Recent Changes

* **(2025-09-13)** **COMPLETED:** Fixed failing test in TypeResolverTests (function return type resolution)
  - **Issue:** Test selected an injected common function instead of the function defined in the test code.
  - **Root Cause:** SemanticApi injects common functions (print, println, concat, to_string) at the beginning of the module; the test used `collectFirst { case f: FnDef => f }` and matched an injected `FnDef`.
  - **Change:** Updated tests to select functions by name:
    - Parameters test: select `greet`
    - Return type test: select `isTrue`
  - **Files:** `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeResolverTests.scala`
  - **Verification:** `sbt test` passed — Total 125, Failed 0, Errors 0, Ignored 6

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
