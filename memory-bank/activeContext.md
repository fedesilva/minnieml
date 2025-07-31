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

* **(2025-07-26)** **COMPLETED:** Completed implementation of parsing error handling for invalid identifiers (#168)
  - **Parser Enhancement:** Updated `fnDefP`, `binOpDefP`, and `unaryOpP` to use `operatorIdOrError` and `bindingIdOrError` wrappers.
  - **Error Flow:** Invalid identifiers in function and operator definitions now create `ParsingIdError` nodes.
  - **Testing:** Added tests to `FnTests.scala` and `OpTests.scala` to verify the new error handling.
  - **Result:** Parser now gracefully handles invalid identifiers in `let`, `fn`, and `op` definitions.

* **(2025-07-12)** **COMPLETED:** Implemented parsing error handling for invalid identifiers (#168)
  - **Parser Enhancement:** Added `bindingIdOrError` wrapper parser that validates identifiers and continues parsing on errors
  - **AST Changes:** `ParsingIdError` extends `Member, Error` for proper semantic phase integration
  - **Error Flow:** Invalid identifiers create error nodes instead of failing parse completely
  - **Semantic Integration:** Added `SemanticError.ParsingIdErrorFound` and updated `ParsingErrorChecker`
  - **Error Reporting:** Updated all error printers to handle identifier errors with proper source highlighting
  - **Testing:** Enhanced `BaseEffFunSuite` with `parseFailedWithErrors()` helper and comprehensive test coverage
  - **Result:** Parser now gracefully handles `let 123invalid = 5;` by creating error nodes while continuing to parse

* **(2025-07-07)** **COMPLETED:** Implemented comma-separated parameter syntax for functions and operators
  - **Parser Changes:** Added `fnParamListP` helper and updated `fnDefP`/`binOpDefP` to require commas
  - **Syntax Change:** `fn concat(a: String b: String)` → `fn concat(a: String, b: String)`
  - **Breaking Change:** All multi-parameter functions and binary operators now require comma separation
  - **Files Updated:** Parser implementation, 6 sample files, 8 test files (23 total test changes)
  - **Result:** All 116 tests pass, syntax is more familiar and consistent with other languages
  - **Unary operators unchanged:** Single-parameter functions still work without commas

* **(2025-07-06)** **RESOLVED:** Fixed critical AST pretty-printing bug that was masking correct TypeChecker behavior
  - **Issue:** App node pattern match in `Term.scala` had wrong parameter order: `App(sp, fn, arg, typeSpec, typeAsc)` 
  - **Fix:** Corrected to match case class definition: `App(sp, fn, arg, typeAsc, typeSpec)`
  - **Impact:** TypeChecker was actually working correctly all along - the bug was only in debug output display
  - **Result:** All OpPrecedenceTests now pass (20/20), confirming TypeChecker implementation is solid

* **(2025-07-06)** **COMPLETED:** Type Checker implementation for complex expressions (#133)
  - TypeChecker now properly assigns types to all App and Expr nodes in complex expressions
  - Complex expressions like `(1 + 2) * (3 - 4) / 5` now type-check correctly  
  - Implementation includes:
    - Recursive type-checking for nested App nodes via `checkApplicationWithContext`
    - Return type extraction from resolved operators/functions via `determineApplicationType`
    - Proper type propagation through expression trees
    - First pass lowering of type ascriptions to specs for functions/operators
    - Parameter context threading through body checking

* **(2025-07-05)** Completed Block 1 of Simple Type Checker (#133): Unified literal types in the AST.
  - **RESOLVED: All literal AST nodes now use `TypeRef` for their type representation, eliminating the special `LiteralType` hierarchy.**
* **(2025-07-04)** Fixed critical expression rewriter bug that caused incorrect function application associativity.
  - **RESOLVED: `println concat "a" "b"` now correctly parses as `(println (concat "a" "b"))` instead of `(((println concat) "a") "b")`.**
* **(2025-07-03)** UPDATED Block 4 of codegen update (#156): Fixed function signature derivation from AST type annotations
  - **RESOLVED: Code emission validity issues - function signatures now correctly derived from AST**
* **(2025-07-03)** Fixed critical TypeResolver bug and completed Block 3 of codegen update (#156)
  - **RESOLVED: LLVM type emission now works for native types, including type aliases**
* **(2025-07-03)** Completed Block 2 of codegen update (#156): TypeResolver now properly handles NativeStruct definitions
* **(2025-07-03)** Completed Block 1 of codegen update (#156): AST and parser now support new `@native:` syntax for primitives, pointers, and structs
* **(2025-07-03)** Rewrote and unified the design for native type interoperability in `memory-bank/specs/codegen-update.md`.
* Implemented TypeResolver following RefResolver pattern
* Fixed Error trait to extend InvalidNode
* **(2025-07-02)** Pivoted design for native type handling to use declarative native structs.

## Next Steps

### Codegen Update (Ticket #156) - IN PROGRESS
The implementation plan is detailed in `memory-bank/specs/codegen-update.md`. Progress on the four blocks:

Initial Meassure of Success:
* the following files compile
  * mml/samples/print_string.mml 
  * mml/samples/print_string_concat.mml 

*   **Block 1: AST & Parser Changes:** ✓ COMPLETED - AST and parser support new `@native:` syntax
*   **Block 2: Semantic Analysis Changes:** ✓ COMPLETED - TypeResolver now handles native struct definitions
*   **Block 3: Codegen - LLVM Type Emission:** ✓ COMPLETED - LLVM type emission works for native types
*   **Block 4: Codegen - Expression Compiler Refactoring:** IN PROGRESS
    - ✓ Fixed function signature derivation from AST type annotations (no more hardcoded i32 returns)
    - ✓ Unit type `()` correctly converted to `void` in LLVM IR    
    - ❌ **Function calls use hardcoded i32 instead of actual types**
      - Issue found in `concat_print_string.mml`: LLVM compilation fails with type mismatches
      - ExpressionCompiler hardcodes `i32` types instead of using actual parameter types from AST
      - Complete analysis: `memory-bank/bugs/hardcoded-i32.md`
      
      **Plan for Fixing Hardcoded i32 Issues:**
      
      Key Understanding:
      1. **MinnieML Type System**: Uses `Unit` type, not `void`
      2. **LLVM Mapping**: `Unit` maps to `void` in LLVM IR  
      3. **Type Safety**: All nodes should have `typeSpec` after TypeChecker - missing types are errors
      4. **Function Types**: Functions returning `Unit` generate `void` calls in LLVM
      
      Issues to Fix:
      1. **Line 466**: `val argType = if argRes.typeName == "String" then "%String" else "i32"` - hardcoded fallback
      2. **Line 512**: `val args = compiledArgs.map { case (value, _) => ("i32", value) }` - hardcoded i32 for all args  
      3. **Line 513**: `emitCall(Some(resultReg), Some("i32"), fnRef.name, args)` - hardcoded i32 return
      4. **Line 111**: `emitLoad(reg, "i32", s"@${ref.name}")` - hardcoded i32 for global refs
      
      Plan:
      1. **Fix `compileApp`**: Use `getLlvmType(arg.typeSpec)` for argument types, error if missing
      2. **Fix function return types**: Use `getLlvmType(fnDef.typeSpec)` for return types, error if missing  
      3. **Handle Unit → void mapping**: `getLlvmType` already handles `TypeUnit(_) => "void"`
      4. **Fix `compileTerm` global refs**: Use actual type from `ref.typeSpec` instead of hardcoded i32
      5. **Remove native function special cases**: Let type system drive everything uniformly
      
      Test Plan:
      - `print_string.mml` should still compile (simple case)
      - `print_string_concat.mml` should now compile (was failing due to hardcoded types)
      
    - ❌ **REMAINING: Replace all hardcoded types in `compileTerm`/`compileApp` with `getLlvmType` helper**
    - ❌ **CURRENT ISSUE: Operators rewritten to curried app need to use native LLVM instructions**
      
      **Problem:** Boolean operators generate function calls instead of native LLVM instructions. `compileApp` treats operator App chains as regular function calls, generating `call i1 @and(...)` instead of native `and i1` instructions.
      
      **Root Cause:** Operators get rewritten to App chains (e.g., `not flag1 or flag2` → `App(App(Ref or, App(Ref not, Ref flag1)), Ref flag2)`) where each `Ref` resolves to `BinOpDef`/`UnaryOpDef` with `NativeImpl` body, but `compileApp` doesn't check for native operations.
      
      **Evidence from AST:**
      ```
      BinOpDef pub and
        Expr
          NativeImpl
      
      App
        fn: Ref or (resolvedAs: BinOpDef or)
        arg: ...
      ```
      
      **Commands to reproduce:**
      - Compile: `sbt "run bin mml/samples/and-not-or.mml"`
      - See AST: `sbt "run ast mml/samples/and-not-or.mml"` then `cat build/AndNotOr.ast`
      - Error: `llvm-as: error: use of undefined value '@and'`
      
      **Solution:** In `compileApp`, check if `fnRef.resolvedAs` is `BinOpDef`/`UnaryOpDef` with `NativeImpl` body and generate native LLVM instructions (`and i1`, `or i1`, `xor i1`) instead of function calls.
      
    - ❌ **REMAINING: Remove special cases for `BinOpDef` and `UnaryOpDef` in `compileExpr`**

## Issues 


### High priority
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

Restart #156. We now have a small working typechecker.
Project might need to be reviewed.



### Future work        
* implement protocols 
* recursion 
* design a very simple type checker (in progress)
* modules
