# MML Active Context

## Current Focus

- module parsing is fragile
- TypeFn for all
- Improve module system
    - parse nested module
    - better scope management

## Issues

### High priority
### Medium Priority

## Next Steps

# 177 Improve APIs to enable us to build better testing tools
  see context/specs/improve-semantic-testing-tools.md
  - [x] Refactor `SemanticApi.rewriteModule` to return the final `SemanticPhaseState` while preserving the existing phase threading/injection logic.
  - [x] Add `compileState` in `CompilerApi`, update `compileString` to reuse it, and keep fatal-error behavior for production callers.
  - [x] Replace `semWithState` in `BaseEffFunSuite` with compileState-backed helpers (e.g. `semState`) and update semantic tests to use them.
  - [x] Document the new semantic-state API surface in `docs/design-and-semantics.md` and other relevant context files.
  - [ ] Run `sbt test` (or faster Metals equivalents) and ensure the new helpers/tests pass before completion. (blocked: sbt needs explicit approval)

## Recent Changes

- revamped module parsing
    * the distinction between anon and named module is gone
    * a file is a top level module, does not require or allow `module` or `;` as terminator
    * this made it easier to improve the failedMemberP catch all parser.
    * we now fail to parse trash constructs but do not abort the process
- Docs: Source tree overview consolidated into `docs/design-and-semantics.md` Appendix A; `context/systemPatterns.md` now references updated compilation flow.
- Unit type vs value
     * `Unit` is the type, `()` is the only inhabitant of that type.
     * Parser now rejects `()` in type positions; use `Unit` in annotations.
     * Samples and tests updated so Unit annotations remain only where required (e.g. native stubs).
- Semantic phases solidified: TypeChecker now lowers ascriptions, infers return types, validates calls, and surfaces errors consistently; TypeResolver covers alias chains and nested refs.
- Parsing and expression handling hardened: parser modules reorganized for identifiers, literals, modules, with better invalid-id reporting; expression rewriter now normalizes operator precedence and auto-calls nullary functions.
- LLVM codegen reworked: native op descriptors drive emission, literal globals become static definitions, string/multiline handling cleaned up, boolean ops emit direct LLVM instructions.
- Tooling and docs refreshed: design/semantics guide rewritten to match pipeline, AGENTS guidance updated, new Neovim syntax package and scripts added.
- Samples and tests updated: sample programs align with new semantics, grammar/semantic/codegen suites broadened to cover native types, operator precedence, and type inference paths.


### Future work        

* modules
* TypeFn: infer correctly for functions
* drop opdef and fn def in favor of bindings to lambdas with metadata (for assoc, etc) 
* recursion 
* protocols 
