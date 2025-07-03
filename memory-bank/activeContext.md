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

*   **Block 1: AST & Parser Changes:** Update `AstNode.scala` and `Parser.scala` to support the new `@native:` syntax.
*   **Block 2: Semantic Analysis Changes:** Update `TypeResolver` to handle native struct definitions.
*   **Block 3: Codegen - LLVM Type Emission:** Implement the logic to generate LLVM `type` definitions from the AST.
*   **Block 4: Codegen - Expression Compiler Refactoring:** Remove all hardcoded types from the expression compiler and use the new type information from the AST.

### Future work        
* implement protocols 
* design a very simple type checker
* recursion 
* modules
