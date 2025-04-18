# MML Active Context

## Current Focus

Currently focusing on enhancing the compiler's semantic analysis, specifically with alphabetic operators and expression rewriting. Working on improving error reporting with better context. Building support for language features like visibility modifiers and type aliases. Native integration via `@native` annotations has been implemented, allowing seamless bridging between MinnieML and C code.

## Recent Changes

We've refactored LLVM code generation into a new emitter package. Added support for alphabetic operators (`and`, `or`, `not`) alongside symbolic ones. Implemented member visibility modifiers and type aliases. Enhanced error reporting with more AST context. Integrated the MML C runtime compilation directly into the build process.

## Next Steps

Immediate tasks include parser and semantic tests for Alpha ops, and implementing cross-compilation support for different platforms. Next, we'll appify operator expressions, update codegen to use a unified strategy, and detect recursion in app chains. Our short-term goal is to get the language capable of loops, conditionals, and basic useful programs.

## Design Decisions

We're simplifying the codebase by rewriting operators as application chains to unify function calls and operator usage. Native integration enhancements will make the system more flexible for targeting different operations. We're evaluating managed effects in delimited contexts and considering interaction nets for memory management and parallelism.
