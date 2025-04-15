# MML Active Context

## Current Work Focus

Current focus appears to be on:

- Implementing the core compilation pipeline
- Developing the LLVM IR code generation with native function integration
- Building out the parser for MML syntax
- Expanding the runtime integration capabilities through `@native` annotations
- Linking the compiled code with the C runtime library

The native integration via `@native` annotations allows seamless bridging between MinnieML and C code, as evidenced in samples like `print_string.mml` that use native String type and print functions.

## Recent Changes

Recent developments in the project:

- Implemented unified expression rewriting that handles both operators and function applications
- Redesigned semantic phase with cleaner separation of concerns:
  - RefResolver now focuses solely on collecting candidate definitions
  - ExpressionRewriter handles all expression structuring in a single pass
- Implemented ML-style function application via curried juxtaposition:
  - Function application is treated as an implicit operator with highest precedence (level 100)
  - Uses left-associative nesting for multiple arguments, e.g., `f a b c` becomes `((f a) b) c` in the AST
  - Function application chains are built naturally through this curried approach
- Enhanced contextual disambiguation to better handle mixed expressions with both operators and function calls
- Added support for alphabetic operators like 'and', 'or', and 'not' alongside symbolic operators
- Enhanced the distribution packaging system:
  - Implemented automatic inclusion of sample programs in the distribution package
  - Created a structured directory layout for the distribution with binary, script, and samples
  - Ensured cross-platform compatibility with OS/architecture-specific naming
  - Added Docker-based tooling for building on Linux platforms
  - Integrated VSCode extension for syntax highlighting of MML files
  - Updated distribution packaging (`mmlcDistroAssembly`) to include the `tooling/vscode` directory.

See: `docs/articles/2025-02/2025-04-12-expression-rewriting.md` for detailed implementation notes

_(Code review on 2025-04-12 confirmed the implementation matches the documented state, including the semantic pipeline order and the use of pattern matching extractors in `ExpressionRewriter` for contextual disambiguation.)_

## Next Steps

### Immediate Tasks:

- Parser and semantic tests for:
  - App rewriting
  - Alpha ops

### Subsequent Tasks:

- Appify operator expressions (rewrite as function application chains, e.g., `2 + 2` -> `((+ 2) 2)`)
- Update codegen to use unified strategy (apps for fn and op)
- Enhance native integration: Allow specifying LLVM types (`@native:t:<type>`) and mapping to LLVM intrinsics (`@native:i:<op>`).
- Detect recursion: detect in app chains, update the codegen
- Fn and app bodies: allow declarations (multiline, complex expressions)
- TypeRef and TypeRef Resolver
- Manual Region Allocators

### Goal:

Get the language in a position where we can loop, cond, and write useful(ish) programs - if a bit basic.

## Active Decisions and Considerations

Current design decisions being evaluated:

- How to handle managed effects in delimited contexts
- Implementation strategy for row polymorphism
- Approach to native code integration

TODO: Add specific decisions currently being evaluated

## Design Rationale for Next Steps

- **Operator Rewriting (as App Chains):** Aims to simplify the code generation strategy and enable unified approaches for optimization and typechecking across both standard function calls and operator usage.
- **Native Integration Enhancements (`@native:t`, `@native:i`):** Intended to remove hardcoded logic within the code generator, making the system more flexible and configurable for targeting different low-level operations and types.

## Important Patterns and Preferences

Observed patterns and preferences in the codebase:

- Functional programming with immutable data structures
- Pipeline-based processing with explicit error handling
- Clear separation between parsing, semantic analysis, and code generation

TODO: Add other patterns and preferences that should be maintained

## Learnings and Project Insights

Insights gained during development:

- Challenges in implementing custom operators with proper precedence
- Approaches to handling error accumulation in the compiler pipeline

TODO: Add specific learnings and insights from recent work
