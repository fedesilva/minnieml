# MML Active Context

## Current Work Focus

Current focus appears to be on:

- Enhancing the compiler's semantic analysis capabilities
- Improving error reporting with better context and source code highlighting
- Building support for language features like visibility modifiers and type aliases
- Expanding the runtime functionality with new native operations (concat, free_string)
- Implementing cross-compilation support for multiple target platforms

The native integration via `@native` annotations has been implemented and integrated into the build process through the `LlvmOrchestrator`, allowing seamless bridging between MinnieML and C code, as evidenced in samples like `print_string.mml` that use native String type and print functions.

## Recent Changes

Recent developments in the project:

- **Compiler Core & Semantics:**

  - Refactored LLVM code generation into a new `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/` package
  - Added basic support for member visibility modifiers (`pub`, `prot`, `priv`) in the AST and parser
  - Added support for alphabetic operators (`and`, `or`, `not`) alongside symbolic ones
  - Introduced `TypeAlias` declarations in the AST and parser
  - Added basic AST nodes (`NativeTypeImpl`, `NativeImpl`) for handling `@native` implementations
  - Refined the semantic analysis pipeline order and improved logic in `ExpressionRewriter`, `Simplifier`, and `DuplicateNameChecker`
  - Improved duplicate name checking, especially for functions vs. operators
  - Enhanced error reporting with more AST context and improved source code snippet highlighting/formatting

- **Runtime & Build:**

  - Integrated the compilation and linking of the MML C runtime (`mml_runtime.c`) directly into the build process
  - Added `concat` and `free_string` functions to the C runtime
  - Updated distribution packaging to include sample programs and VSCode extension

- **Tooling & Documentation:**
  - Added a VSCode syntax highlighting extension for MML files
  - Updated documentation with new articles on expression rewriting
  - Added brainstorming documents for error accumulation and the compiler pipeline

See: `docs/articles/2025-02/2025-04-12-expression-rewriting.md` for detailed implementation notes

_(Code review on 2025-04-12 confirmed the implementation matches the documented state, including the semantic pipeline order and the use of pattern matching extractors in `ExpressionRewriter` for contextual disambiguation.)_

## Next Steps

### Immediate Tasks:

- **Parser and semantic tests** :

  - for App rewriting
  - for Alpha ops

- **Cross-Compilation Support**: Implement target specification to allow building MinnieML programs for different platforms (Darwin x86_64, Darwin aarch64, Linux x86_64) from a single host. This is very important and needs to be done soon. See `docs/brainstorming/cross-compiling-project.md` for implementation details.

### Subsequent Tasks:

- Appify operator expressions (rewrite as function application chains, e.g., `2 + 2` -> `((+ 2) 2)`)
  - this will require fixing the big test suite around operators precedence.
- Update codegen to use unified strategy (apps for fn and op)
- Enhance native integration: Allow specifying LLVM types (`@native:t:<type>`) and mapping to LLVM intrinsics (`@native:i:<op>`).
- Detect recursion: detect in app chains, update the codegen
- Fn and app bodies: allow declarations (multiline, complex expressions)
- TypeRef and TypeRef Resolver
- Manual Region Allocators

### Short Term Goal:

Get the language in a position where we can loop, cond, and write useful(ish) programs - if a bit basic.

## Active Decisions and Considerations

Mid term we need a memory management strategy that allows us
producing useful programs before getting to the more sophisticated
techniques (look below note about interaction networks).

Current design decisions being evaluated:

- How to handle managed effects in delimited contexts
  - designing, not even a clue of how it would like yet.
- Interaction nets could be a win
  - highly parallel graph rewrites
  - ability to detect parallel code, to do simd, etc.
  - high quality lifecycle analysis possible.
    - enable mem management inference and codegen
  - but more exotic than lambda calculus

## Design Rationale for Next Steps

- **Operator Rewriting (as App Chains):** Aims to simplify the code generation strategy and enable unified approaches for optimization and typechecking across both standard function calls and operator usage.
- **Native Integration Enhancements (`@native:t`, `@native:i`):** Intended to remove hardcoded logic within the code generator, making the system more flexible and configurable for targeting different low-level operations and types.

## Important Patterns and Preferences

Observed patterns and preferences in the codebase:

- Functional programming with immutable data structures
- Pipeline-based processing with explicit error handling using `Either[CompilationError, T]`
- Clear separation between parsing, semantic analysis, and code generation
- Modular code organization with distinct packages for each compiler phase
- Consistent use of Cats and Cats Effect for functional programming patterns
- Scala 3 with new syntax style throughout the codebase
- Immutable AST with transformations producing new instances rather than mutations

## Learnings and Project Insights

Insights gained during development:

- Challenges in implementing custom operators with proper precedence
- Unified approach to function application and operator precedence simplifies the compiler architecture
- Approaches to handling error accumulation in the compiler pipeline
- Integration of native code with the compiler requires careful orchestration of build processes
- VSCode extensions significantly improve the development experience for language projects
