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
* Created doc/brainstorming/codegen-update.md with implementation plan
* **COMPLETED**:
  * ✓ @native attribute parsing for 'op' and 't' parameters
  * ✓ injectBasicTypes function with TypeDef + TypeAlias pattern:
    - Int64 = @native, Int = Int64
    - Float64 = @native, Float = Float64  
    - Bool = @native, String = @native
  * ✓ Updated injectStandardOperators with type annotations and op attributes
  * ✓ Operators already rewritten as function applications in semantic phase
  * ✓ Error handling already in place
  * ✓ Fixed test conflicts, all tests passing (98 passing, 0 failing)
* **REMAINING WORK**:
  * **Note**: The implementation plan has been significantly revised. See the "Revised Plan: Declarative Native Structs" section in `doc/brainstorming/codegen-update.md` for the new block-based implementation strategy.
  * **Phase 3**: Refactor codegen to use types from AST (no hardcoded assumptions)
    - Currently hardcodes i32, i64, %String in ExpressionCompiler.scala
    - Need to read 't' attribute from type definitions
    - At codegen level: use 'op' attribute for LLVM intrinsics

### Future work        
* implement protocols 
* design a very simple type checker
* recursion 
* modules
