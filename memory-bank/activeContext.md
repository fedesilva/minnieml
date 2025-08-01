# MML Active Context

## Current Focus

Ability to compile simple programs:
* basic types
* error accumulation
* explicit types
* recursion (tco)
* codegen app chains
* type resolver (error if all types are not resolved)


## Recent Changes

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


## Next Steps

### Codegen Update (Ticket #156) - NEARING COMPLETION
The implementation plan is detailed in `memory-bank/specs/codegen-update.md`. Progress:

**Goal:** 
Compile 

* √ `mml/samples/print_string.mml` 
* √ `mml/samples/print_string_concat.mml`
* `mml/samples/test_to_string.mml`
* `mml/samples/test_print_add.mml`

*   **Block 1: AST & Parser Changes:** ✓ COMPLETED - AST and parser support new `@native:` syntax
*   **Block 2: Semantic Analysis Changes:** ✓ COMPLETED - TypeResolver now handles native struct definitions  
*   **Block 3: Codegen - LLVM Type Emission:** ✓ COMPLETED - LLVM type emission works for native types
*   **Block 4: Codegen - Expression Compiler Refactoring:** MOSTLY COMPLETED
    - ✓ Fixed function signature derivation from AST type annotations
    - ✓ Unit type `()` correctly converted to `void` in LLVM IR
    - ✓ Fixed native operator code generation for boolean operators (`and`, `or`, `not`)
    - ❌ **REMAINING:** Replace hardcoded `i32` types in `compileApp` and `compileTerm` with proper type resolution using `getLlvmType` helper
      - Complete analysis in: `memory-bank/bugs/hardcoded-i32.md`
      - Should enable `print_string_concat.mml` to compile successfully

## Issues 


### High priority

* **CRITICAL BLOCKING ISSUE - Hardcoded Types in ExpressionCompiler.scala** - **CLINE'S FAULT**
  - **Problem:** Multiple functions in ExpressionCompiler.scala still contain hardcoded LLVM types ("i32", "i64", "i1") instead of extracting types from AST, despite the Int64 type having proper native i64 representation in semantic package
  - **Culprit:** Cline repeatedly implemented hardcoded fallbacks and ignored available type information from AST `typeSpec` fields
  - **Affected Functions:** 
    - `applyBinaryOp` (lines ~310-380): ALL emit calls hardcode "i32" - `emitAdd(resultReg, "i32", leftOp, rightOp)`
    - `applyUnaryOp` (lines ~380-420): ALL emit calls hardcode "i32" - `emitSub(resultReg, "i32", "0", argOp)`
    - Conditional compilation (line ~150): hardcodes "i32" in `icmp ne i32 $condOp, 0`
    - Native operator fallbacks use hardcoded "i64"/"i1" instead of proper error handling
  - **Consequence:** Causes "Unresolved type reference: Int64" errors when compiling `test_print_add.mml`                      
  - **Required Fix Draft Plan:** 
    1. Modify `applyBinaryOp`/`applyUnaryOp` signatures to accept original AST terms (left/right/arg)
    2. Extract LLVM types using `getLlvmType(term.typeSpec)` for each operand
    3. Remove ALL hardcoded type strings ("i32", "i64", "i1") from emit calls
    4. Remove hardcoded fallbacks - return proper errors if type resolution fails
    5. Fix conditional compilation to extract actual condition type from AST
  - **Status:** IMPERATIVE TO FIX - This is blocking core compilation functionality
  - **Multiple failed attempts by Cline:** 2025-07-31

* **TypeChecker Bug - Missing Type Validation**: TypeChecker incorrectly allows `println (5 + 3)` where `println` expects `String` but receives `Int`. This should fail during semantic analysis with a proper type mismatch error, but currently passes with "No errors". The TypeChecker is not properly validating function argument types against parameter types.
  - Issue first observed: 2025-07-27
  - Test case: `fn main(): () = println (5 + 3);` should fail but doesn't

* **Potential typechecker Bug - Type Alias Resolution**: 
  it seems that type aliases do not get a typespec. confirm

  TypeAlias(
  visibility = Protected,
  span = SrcSpan(SrcPoint(0,2,1), SrcPoint(2,22,23)),
  name = "TestI64",
  typeRef = TypeRef(
    span = SrcSpan(SrcPoint(2,16,16), SrcPoint(2,21,21)),
    name = "Int64",
    resolvedAs = Some(TypeDef(
      visibility = Protected,
      span = SrcSpan(SrcPoint(0,0,0), SrcPoint(0,0,0)),
      name = "Int64",
      typeSpec = Some(NativePrimitive(
        span = SrcSpan(SrcPoint(0,0,0), SrcPoint(0,0,0)),
        llvmType = "i64"
      )),
      docComment = None,
      typeAsc = None
    ))
  ),
  typeSpec = Some(TypeRef(
    span = SrcSpan(SrcPoint(2,16,16), SrcPoint(2,21,21)),
    name = "Int64",
    resolvedAs = Some(TypeDef(
      visibility = Protected,
      span = SrcSpan(SrcPoint(0,0,0), SrcPoint(0,0,0)),
      name = "Int64",
      typeSpec = Some(NativePrimitive(
        span = SrcSpan(SrcPoint(0,0,0), SrcPoint(0,0,0)),
        llvmType = "i64"
      )),
      docComment = None,
      typeAsc = None
    ))
  )),
  typeAsc = None,
  docComment = None
)

question: should the typechecker resolve the typeSpec of the type alias to the typeSpec of the ref?
such that the codegen does not need to try to resolve the typespec of the ref?


### Medium Priority

* **Complex nested concatenation with to_string fails**: The file `mml/samples/failing_to_string_complex.mml` compiles successfully but outputs "Error" instead of the expected concatenated string. Simple `to_string` works (e.g., `to_string 123` outputs "123"), but complex nested concatenations fail at runtime. This may be related to:
  - String memory management issues in the C runtime
  - LLVM IR generation for deeply nested function calls
  - Potential stack overflow or memory allocation problems
  - Issue first observed: 2025-07-27

## Next Session Plan:

Review the rest of the 



### Future work        
* implement protocols 
* recursion 
* design a very simple type checker (in progress)
* modules
