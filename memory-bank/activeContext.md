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

### (#168) Improve Parser Error Hanlding

See `./specs/168-improve-parsing-error.md` 


### (#133) Simple Type Checker (COMPLETED)

This task was to implement a simple, forward-propagating type checker to unblock the codegen update (#156). The full specification is in `memory-bank/specs/133-simple-typechecker.md`.

**Status: ✓ IN PROGRESS**

**Execution Plan Results:**
*   **Block 1: Update AST Literals:** ✓ COMPLETED
*   **Block 2: Implement Core Type Checker Logic:** ✓ COMPLETED
*   **Block 3: Integrate and Test:** ✓ COMPLETED
    -   All core functionality working
    -   Complex expression type inference implemented
    -   Test "complex grouping with multiple binops: (1 + 2) * (3 - 4) / 5" now passes

**Awaiting validation and general tire kicking**

### Codegen Update (Ticket #156) - IN PROGRESS
The implementation plan is detailed in `memory-bank/specs/codegen-update.md`. Progress on the four blocks:

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
    - ❌ **REMAINING: Replace all hardcoded types in `compileTerm`/`compileApp` with `getLlvmType` helper**
    - ❌ **REMAINING: Operators rewritten to curried app need to use the `op=` attribute**
    - ❌ **REMAINING: Remove special cases for `BinOpDef` and `UnaryOpDef` in `compileExpr`**

## Next Session Plan:

For



### Future work        
* implement protocols 
* recursion 
* design a very simple type checker (in progress)
* modules
