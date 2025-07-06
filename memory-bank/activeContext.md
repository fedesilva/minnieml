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

**Execution Plan:**

*   **Block 1: Update AST Literals:** ✓ COMPLETED
    -   Modified `AstNode.scala` to replace the `LiteralType` hierarchy with direct usage of `TypeRef` for all literal nodes.
    -   Removed the `LiteralType` sealed trait and all its subclasses.
    -   Cleaned up any remaining usages of `LiteralType` across the codebase.
*   **Block 2: Implement Core Type Checker Logic:** ✓ COMPLETED
    -   Defined new `TypeError` and `SemanticError` cases.
    -   Created `TypeChecker.scala` and implemented the core logic, including state-threading, alias resolution, and initial `Hole` typing.
*   **Block 3: Integrate and Test:** IN PROGRESS
    -   Integrated the new `TypeChecker` into the `SemanticApi.scala` pipeline.
    -   Created `TypeCheckerTests.scala` and updated other test suites (`LiteralTests`, `AppRewritingTests`, `OpPrecedenceTests`) to align with the new, stricter typing rules.
    -   **Remaining:** Numerous test failures still exist, primarily in `OpPrecedenceTests` and `AppRewritingTests`, due to the new type checker - specifically, the tested code does not have the required type annotations. The next step is to fix these tests.
    -   **PARTIALLY RESOLVED (2025-07-05)**: TypeChecker incorrectly handles multi-argument function applications.
        - The core logic in `checkApplication` was updated to collect all arguments in a chain before validation.
        - This fixed direct multi-argument function calls, but issues remain with operator applications, which are rewritten into `App` chains.
        - **Remaining:** The type checker still fails on many operator-related tests, indicating the fix was not sufficient for all cases.

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
