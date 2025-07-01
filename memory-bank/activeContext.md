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

## Next Steps

* Error ast node needs to be subclass of InvalidNode
* udpdate codegen
    * injectStandardOperators 
        * add types to the definitions
            * add op= to the @native parser
                * so we can say @native[op=mult] for example, to guide the codegen.
    * create a function like injectStandarOperators
        * injectBasicTypes (Int -> i64, String -> @native, Bool -> i1, ...)    
        *  (all the types that can be represented as literals)
    * use types from the source (do not assume, missing types are a fatal error)
    * operators now are app (lambda application), should be treated like functions
        * but we will end up using the llvm intrinsic operations (via op=)

        
* design a very simple type checker
