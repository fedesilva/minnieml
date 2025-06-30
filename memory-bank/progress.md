# MML Progress

## What Works

- Basic parsing of MML syntax (functions, operators, types, conditionals)
- Semantic analysis (reference resolution, expression rewriting, contextual disambiguation)
- ML-style curried function application via juxtaposition (`f a b c` → `((f a) b) c`)
- Native integration with C runtime (`@native` annotations, String operations, I/O)
- LLVM IR generation for simple programs
- Command-line interface for compilation commands
- Distribution packaging system with OS/architecture awareness
- VSCode extension for syntax highlighting

## What's Left to Build

- Detect recursion in app chains
- Support for function bodies with complex expressions
- Type Checker (basic type checking)
- Manual Region Allocators
- Improved error handling and continuation after errors
- Cross-compilation support for multiple platforms
- Proper span building for AST nodes
- Support for loops and more complex control flow

## Current Status

The compiler successfully parses a substantial subset of the intended language syntax with unified expression rewriting for both operators and function applications. The semantic phase handles complex mixed expressions with proper precedence and associativity. Ready for upcoming type checking phase and transition to Typed Lambda Intermediate Representation (TLIR).

- Modified ExpressionRewriter.scala to transform operator expressions into function applications (branch 142-operator-as-app)
- Updated all tests in OpPrecedenceTests.scala to use TXApp extractors for validating operator-as-app transformation 
- Added prettyPrintList helper in prettyprint/ast/package.scala for improved error diagnostics
- Ensured all error messages properly use pretty printing for AST nodes and collections

## Known Issues

- Span building of IDs considers the space after them
- Span building requires going through source for each AST node (inefficient)
- Currently aborting on errors instead of continuing
- Poor parsing error reporting for non-member errors
