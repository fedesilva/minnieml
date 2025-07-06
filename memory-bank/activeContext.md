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

* **(2025-07-06)** Major progress on Type Checker (#133) - Fixed critical bugs in RefResolver and TypeChecker
  - **RESOLVED:** RefResolver now properly sets `resolvedAs` for single candidates (was leaving unambiguous references unresolved)
  - **RESOLVED:** TypeChecker now handles FnParam references correctly (was only handling Decl types)
  - **RESOLVED:** Fixed operator arity checking - TypeChecker now correctly uses the resolved operator variant (unary vs binary)
  - **RESOLVED:** Fixed injected operators to only set `typeAsc`, not `typeSpec` (typeSpec should be computed by TypeChecker)
  - **RESOLVED:** Swapped RefResolver and TypeResolver order in semantic pipeline (TypeResolver now runs first)
  - **RESOLVED:** Fixed operator type validation bug - injected operators had incorrect `typeAsc` set to TypeFn instead of just return type
    - Arithmetic operators now have `typeAsc = Some(intType)` 
    - Comparison operators now have `typeAsc = Some(boolType)`
    - Logical operators now have `typeAsc = Some(boolType)`
    - Test "Test operators with the same symbol but different arity" now passes
  - **MAJOR REFACTOR COMPLETED:** TypeChecker now works correctly without higher-order functions
    - TypeFn is never created or assigned to any node
    - First pass lowers mandatory ascriptions to specs for functions/operators
    - Type checker only works with typeSpec fields, never reads typeAsc (except for validation)
    - Parameter context properly threaded through body checking
  - **REMAINING ISSUES:** 
    - Some complex expressions like `(1 + 2) * 3` fail with UnresolvableType
    - Mixed associativity test also failing

* **(2025-07-05)** Made progress on Type Checker (#133), but issues remain.
  - **IN PROGRESS:** The `TypeChecker` still fails on many operator-related tests. The logic for handling multi-argument function application was improved, but this was not sufficient to resolve the type errors that occur after operators are rewritten into `App` chains.
  - **NEXT:** The immediate next step is to fix the remaining test failures in `OpPrecedenceTests` by correctly handling the type checking of rewritten operator expressions.
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


### (#133) Simple Type Checker (high priority, IN PROGRESS)

This task is to implement a simple, forward-propagating type checker to unblock the codegen update (#156). The full specification is in `memory-bank/specs/133-simple-typechecker.md`.

**What Works:**
- ✓ RefResolver correctly resolves references and sets `resolvedAs` for unambiguous cases
- ✓ TypeChecker handles FnParam references (parameters)
- ✓ TypeChecker correctly identifies operator arity based on resolved operator
- ✓ Injected operators properly use only `typeAsc` (not `typeSpec`)
- ✓ Semantic pipeline order corrected (TypeResolver before RefResolver)

**What Doesn't Work:**
- ❌ TypeResolver only resolves one level of type aliases (not recursive)
  - CRITICAL: Type alias resolution MUST be recursive, stopping at a TypeDef
  - CRITICAL: Must walk back the chain assigning typeSpec from the TypeDef
  - This causes TypeMismatch errors when comparing types with different levels of resolution

the test "complex grouping with multiple binops: (1 + 2) * (3 - 4) / 5".only 
has been marked to be the only to run. we can work on fixing this one.

**Execution Plan:**

*   **Block 1: Update AST Literals:** ✓ COMPLETED
*   **Block 2: Implement Core Type Checker Logic:** ✓ COMPLETED
*   **Block 3: Integrate and Test:** IN PROGRESS
    -   Most core functionality working
    -   **CRITICAL REMAINING:** Fix recursive type alias resolution in TypeResolver

#### Focus on bugs/133-typechecker-current-issues.md

This file has a description of the current problem I am trying to solve.
There is an associated dump, if the need arises for more information or to 
confirm the bug report.

Focus on fixing the specific test mentioned in the bug report.
    

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

### Future work        
* implement protocols 
* recursion 
* design a very simple type checker
* modules