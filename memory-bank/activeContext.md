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

* **(2025-07-03)** Fixed critical TypeResolver bug and completed Block 3 of codegen update (#156)
  - **IMPORTANT: Code emission validity is under question - needs investigation**
  - Fixed TypeResolver bug where TypeRefs inside NativeStruct fields were resolving to outdated TypeAlias instances
  - The fix: TypeResolver now creates temporary module with accumulated members for type lookups
  - Completed Block 3: LLVM type emission now works for native types, including type aliases
  - Test "handles type alias to native type correctly" now passes
  - **WARNING: The emitted LLVM type definitions (e.g., `%Int64 = type i64`) may not be valid LLVM IR - requires investigation**
* **(2025-07-03)** Completed Block 2 of codegen update (#156): TypeResolver now properly handles NativeStruct definitions
  - Changed NativeStruct fields from `List[(String, TypeSpec)]` to `Map[String, TypeSpec]` for uniqueness and O(1) lookup
  - Fixed TypeResolver to process TypeDef members and resolve TypeRefs inside NativeStruct fields
  - Added comprehensive tests for TypeResolver with NativeStruct
  - Updated parser to use Map for NativeStruct fields (duplicate fields handled by Map semantics - last wins)
* **(2025-07-03)** Completed Block 1 of codegen update (#156): AST and parser now support new `@native:` syntax for primitives, pointers, and structs
* **(2025-07-03)** Rewrote and unified the design for native type interoperability in `doc/brainstorming/codegen-update.md`. The new design is now the single plan of record.
* Implemented TypeResolver following RefResolver pattern
* TypeRef now has single `resolvedAs: Option[ResolvableType]` field (no candidates)
* TypeResolver integrated into semantic pipeline after RefResolver
* Resolves type references in bindings, function parameters/returns, and type aliases
* Reports UndefinedTypeRef errors for missing types
* Improved pretty printing: TypeDef shows @native, TypeAlias uses arrow notation, TypeRef shows resolution status
* Fixed Error trait to extend InvalidNode - all error AST nodes now properly categorized as invalid constructs
* **(2025-07-02)** Pivoted design for native type handling. Decided to implement declarative native structs (`@native { ... }`) to make the system scalable. Updated `doc/brainstorming/codegen-update.md` with the new design.

## Next Steps

### Codegen Update (Ticket #156) - IN PROGRESS
The implementation plan has been updated and is detailed in `doc/brainstorming/codegen-update.md`. The work is divided into four blocks:

*   **Block 1: AST & Parser Changes:** ✓ COMPLETED - AST and parser support new `@native:` syntax
*   **Block 2: Semantic Analysis Changes:** ✓ COMPLETED - TypeResolver now handles native struct definitions
*   **Block 3: Codegen - LLVM Type Emission:** IN PROGRESS - Basic emission implemented but has bugs
    - **⚠️ CRITICAL: Validity of emitted LLVM IR is questionable - needs investigation**
    - Currently emitting definitions like `%Int64 = type i64` which may not be valid LLVM
    - Tests pass but the generated code needs review
*   **Block 4: Codegen - Expression Compiler Refactoring:** Remove all hardcoded types from the expression compiler and use the new type information from the AST

### Future work        
* implement protocols 
* recursion 
* design a very simple type checker
* modules
