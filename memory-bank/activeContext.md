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

* **(2025-07-06)** Completed Type Checker implementation for complex expressions (#133)
  - **RESOLVED:** Fixed type inference for App nodes - TypeChecker now properly assigns types to all application nodes
    - Modified `checkApplicationWithContext` to recursively type-check nested App nodes
    - Implemented `determineApplicationType` to extract return types from resolved operators/functions
    - All App nodes now get proper typeSpec assigned, even in partial application chains
  - **RESOLVED:** Fixed type propagation for Expr nodes - types now properly flow up expression trees
  - **RESOLVED:** Complex expressions like `(1 + 2) * 3` now type-check correctly
  - **RESOLVED:** Test "complex grouping with multiple binops: (1 + 2) * (3 - 4) / 5" now passes
  - **IMPLEMENTATION COMPLETE:** TypeChecker fully handles expression type inference
    - RefResolver correctly resolves references and sets `resolvedAs` for unambiguous cases
    - TypeChecker handles FnParam references (parameters)
    - TypeChecker correctly identifies operator arity based on resolved operator
    - Injected operators properly use only `typeAsc` (not `typeSpec`)
    - Semantic pipeline order corrected (TypeResolver before RefResolver)
    - TypeFn is never created or assigned to any node
    - First pass lowers mandatory ascriptions to specs for functions/operators
    - Type checker only works with typeSpec fields, never reads typeAsc (except for validation)
    - Parameter context properly threaded through body checking

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


### (#133) Simple Type Checker (COMPLETED)

This task was to implement a simple, forward-propagating type checker to unblock the codegen update (#156). The full specification is in `memory-bank/specs/133-simple-typechecker.md`.

**Status: ✓ COMPLETED**

**Execution Plan Results:**
*   **Block 1: Update AST Literals:** ✓ COMPLETED
*   **Block 2: Implement Core Type Checker Logic:** ✓ COMPLETED
*   **Block 3: Integrate and Test:** ✓ COMPLETED
    -   All core functionality working
    -   Complex expression type inference implemented
    -   Test "complex grouping with multiple binops: (1 + 2) * (3 - 4) / 5" now passes

### Remaining Test Failures (Not Part of #133)

The test suite revealed other pre-existing issues not related to the Type Checker implementation:

1. **Mandatory Type Annotations**: Many tests fail because functions now require explicit type annotations (as per spec)
   - Tests were written before this requirement
   - Need to update test cases to include type annotations

2. **TypeResolver Unit Type Issue**: TypeResolver fails on `Unit` type references
   - Error: `UndefinedTypeRef(TypeRef(..., Unit, None))`
   - Unit is a special case that may need different handling

3. **Affected Test Suites**:
   - AppRewritingTests (20 failures) - missing type annotations
   - TypeResolverTests - Unit type resolution
   - MissingTypeAnnotationTest - expects old behavior
   - MemberErrorCheckerTests - missing type annotations
   - AlphaOpTests - missing type annotations
    

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