# MML Progress

## What Works

Implemented and functional:

- Basic parsing of MML syntax including:
  - Functions and operators
  - Basic types and expressions
  - Conditionals
- Initial AST representation
- Semantic analysis including:
  - Reference resolution with multiple candidate collection
  - Unified expression rewriting for both operators and function applications
  - ML-style curried function application via juxtaposition:
    - Left-associative nesting (`f a b c` → `((f a) b) c`)
    - Highest precedence (100) for function application
    - Treats function calls as an implicit juxtaposition operator
  - Contextual disambiguation of references based on position
  - Precedence climbing with appropriate operator associativity
  - Support for complex mixed expressions (operators + function calls)
  - Duplicate name checking
  - Member error checking
    - see if parser left error marking nodes
      - it does not stop, it leaves an error node and continues.
- Native integration capabilities:
  - Support for `@native` type and function annotations
  - C runtime library with implementations for native functions (String operations, I/O, etc.)
  - Automatic extraction and compilation of runtime alongside MinnieML code
  - Linking between MinnieML code and native implementations
  - Support for both binary and library output modes
- LLVM IR generation for simple programs
- Command-line interface for compilation commands
- Sample programs demonstrating basic language features and native integration
- Native image build support for the compiler itself.
- Functional command-line interface (`mmlc`) for current capabilities (single-file compilation to binary, library, AST, IR).
- Distribution packaging system:
  - Automated assembly of compiler distribution with sample programs and VSCode tooling (`tooling/vscode`)
  - Cross-platform packaging with OS/architecture-aware naming
  - Support for local installation and zip-based distribution
  - Docker-based tooling for Linux builds
- Development tools:
  - VSCode extension for syntax highlighting of MML files

See `docs/articles/2025-02/2025-04-12-expression-rewriting.md` for details on the updated expression rewriting system

## What's Left to Build

The following tasks are planned, prioritized as immediate and subsequent:

### Immediate Tasks:

- Parser and semantic tests for:
  - App rewriting
  - Alpha ops

### Subsequent Tasks:

- Appify operator expressions
- Update codegen to use unified strategy (apps for fn and op)
- Detect recursion: detect in app chains, update the codegen
- Fn and app bodies: allow declarations (multiline, complex expressions)
- TypeRef and TypeRef Resolver
- Manual Region Allocators - type based.

### Goal:

Get the language in a position where we can loop, cond, and write useful(ish) programs - if a bit basic.

_(Other features like Pattern Matching, Protocols, Managed Effects, Row Polymorphism, Standard Library, Self-hosting compiler are longer-term goals not included in this immediate roadmap)._

## Current Status

- The compiler successfully parses a substantial subset of the intended language syntax
- Expression rewriting is now fully functional with a unified approach for both operators and function applications
- The ML-style curried juxtaposition-based function application system is completely implemented
- The semantic phase handles complex mixed expressions with proper precedence and associativity
- Contextual disambiguation correctly resolves references based on their position in expressions
- Standard operators are automatically injected into every module
- The compiler can process expressions with custom operators and function applications
- Moving toward the Typed Lambda Intermediate Representation (TLIR) which will be the core language of the compiler
- LLVM IR generation works for the implemented language subset

The compiler now has a robust pipeline from parsing through semantic analysis with unified expression rewriting, laying the groundwork for the upcoming type checking phase and the transition to TLIR. _(Code review on 2025-04-12 confirmed the implementation matches the documented state.)_

## Known Issues

The things that are not yet implemented are not considered issues, just the way things are. It is understood that this is a work in progress.

- span building of ids considers the space after them. small bug.
- span building involves going through the source for each ast node.
  - this is incredibly inefficient.
- Not a bug, but something we need to fix soon is to not abort on errors.
- Parsing error reporting is still bad for anything but members,
  - if the module fails to parse we just fail and bail.
  - we should probably add a broken module marker and proceed.

## Evolution of Project Decisions

Notable decisions and their evolution:

- Choice of Scala 3 with new syntax for implementation
- Selection of FastParse over ANTLR4 (mentioned in README)
- Decision to use LLVM IR as compilation target
