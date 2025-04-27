# MML Active Context

## Current Focus

Ability to compile simple programs:
* basic types
* explicit types
* recursion (tco)
* codegen app chains
* type resolver (error if all types are not resolved)


## Recent Changes

Convert operator expressions to curried applciations chains

## Next Steps


Pending: finish op precedence tests
Pending: think about the fact that we do not check that the ref of an app is a function:
    * maybe we should allow it since a bnd could be bound to a lambda.
