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

* **(2025-07-05)** Completed Block 1 of Simple Type Checker (#133): Unified literal types in the AST.
  - **RESOLVED: All literal AST nodes now use `TypeRef` for their type representation, eliminating the special `LiteralType` hierarchy.**
  - Modified `AstNode.scala` to change `LiteralInt`, `LiteralString`, etc., to use `TypeRef("Int")`, `TypeRef("String")`, etc.
  - Removed the `LiteralType` sealed trait and its subclasses entirely from the AST.
  - Updated the pretty-printer in `util/prettyprint/ast/Type.scala` to remove handling for the obsolete `LiteralType`.
  - The project compiles successfully after these changes.
* **(2025-07-04)** Fixed critical expression rewriter bug that caused incorrect function application associativity.
  - **RESOLVED: `println concat "a" "b"` now correctly parses as `(println (concat "a" "b"))` instead of `(((println concat) "a") "b")`.**
  - Implemented a recursive `buildAppChain` method in `ExpressionRewriter` to correctly handle precedence of nested function calls.
  - The new logic correctly distinguishes between left-associative application (`f x y`) and grouped application for function chains (`f g x`).
  - Updated the `AppRewritingTests` suite to reflect the correct, nested AST structure for function chains, ensuring tests align with the fix.
  - All tests in `AppRewritingTests` now pass.
* **(2025-07-03)** UPDATED Block 4 of codegen update (#156): Fixed function signature derivation from AST type annotations
  - **RESOLVED: Code emission validity issues - function signatures now correctly derived from AST**
  - Fixed hardcoded i32 return types: Native and regular functions now derive LLVM signatures from AST type annotations
  - Unit type `()` correctly converted to `void` in LLVM IR (e.g., `define void @main()` instead of `define i32 @main()`)
  - Added strict type annotation requirements: Functions must have explicit return and parameter type annotations
  - Clear error messages for missing type annotations: "Missing return type annotation for function 'X'" and "Missing type for param 'Y' in fn 'Z'"
  - Updated sample files (e.g., `print_string.mml`) with proper type annotations
  - Added comprehensive test coverage for error handling with missing type annotations
  - **RESULT: Generated LLVM IR now has correct function signatures (e.g., `declare void @println(%String)` from `fn println (a: String): ()`)**
* **(2025-07-03)** Fixed critical TypeResolver bug and completed Block 3 of codegen update (#156)
  - Fixed TypeResolver bug where TypeRefs inside NativeStruct fields were resolving to outdated TypeAlias instances
  - The fix: TypeResolver now creates temporary module with accumulated members for type lookups
  - Completed Block 3: LLVM type emission now works for native types, including type aliases
  - Test "handles type alias to native type correctly" now passes
* **(2025-07-03)** Completed Block 2 of codegen update (#156): TypeResolver now properly handles NativeStruct definitions
  - Changed NativeStruct fields from `List[(String, TypeSpec)]` to `Map[String, TypeSpec]` for uniqueness and O(1) lookup
  - Fixed TypeResolver to process TypeDef members and resolve TypeRefs inside NativeStruct fields
  - Added comprehensive tests for TypeResolver with NativeStruct
  - Updated parser to use Map for NativeStruct fields (duplicate fields handled by Map semantics - last wins)
* **(2025-07-03)** Completed Block 1 of codegen update (#156): AST and parser now support new `@native:` syntax for primitives, pointers, and structs
* **(2025-07-03)** Rewrote and unified the design for native type interoperability in `memory-bank/specs/codegen-update.md`. The new design is now the single plan of record.
* Implemented TypeResolver following RefResolver pattern
* TypeRef now has single `resolvedAs: Option[ResolvableType]` field (no candidates)
* TypeResolver integrated into semantic pipeline after RefResolver
* Resolves type references in bindings, function parameters/returns, and type aliases
* Reports UndefinedTypeRef errors for missing types
* Improved pretty printing: TypeDef shows @native, TypeAlias uses arrow notation, TypeRef shows resolution status
* Fixed Error trait to extend InvalidNode - all error AST nodes now properly categorized as invalid constructs
* **(2025-07-02)** Pivoted design for native type handling. Decided to implement declarative native structs (`@native { ... }`) to make the system scalable. Updated `memory-bank/specs/codegen-update.md` with the new design.

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
    -   **NEW ISSUE DISCOVERED (2025-07-05)**: TypeChecker incorrectly handles multi-argument function applications
        - Tries to apply arguments one at a time (expecting currying) instead of collecting all arguments
        - Causes `InvalidApplication` errors for valid code like `mult 2 2`  
        - Fix documented in spec section 4.1 - needs implementation in next session
        - This is the root cause of many test failures 
    -   **Possible bug** The printed ast is weird, see `memory-bank/bugs/133-typechecker-ast-needs-checking.txt`
           - I see operators defined where the Bool type is resolved and then later it's not.
           - check the injection definition.
           - check how we resolve aliases again, it might be that for some types we don't?
           - this is not limited to Bool. 
           - examples
           ```
            UnaryOpDef prot +
              typeSpec: TypeFn
                params: TypeRef Int => TypeAlias(Int)
                return: TypeFn
                  params: TypeRef Int(unresolved)
                  return: TypeRef Int(unresolved)
            ```


            ```
             UnaryOpDef prot not
              typeSpec: TypeFn
                params: TypeRef Bool => TypeDef(Bool)
                return: TypeFn
                  params: TypeRef Bool(unresolved)
                  return: TypeRef Bool(unresolved)
            ```


            

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
