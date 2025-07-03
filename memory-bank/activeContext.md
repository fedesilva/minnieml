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

## Next Steps

### Codegen Update (Ticket #156) - IN PROGRESS
* Created doc/brainstorming/codegen-update.md with implementation plan
* Need to implement:
    * √ @native attribute parsing for 'op' and 't' parameters
      √   * Example: @native[op=add], @native[t=i64]
    * injectBasicTypes function 
        * type Int → @native[t=i64]
        * type String → @native (points to a struct %)
        * Bool → @native[t=i1]
    * Update injectStandardOperators with type annotations and op attributes
    * Refactor codegen to use types from AST (no assumptions)
        * Currently hardcodes i32, i64, %String
        * Must fail if types are missing
                

            
            

    * Treat operators as function applications
        * Use LLVM intrinsics via op= attribute

### Future work        
* design a very simple type checker
