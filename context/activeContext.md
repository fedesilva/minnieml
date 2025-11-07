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

 - #174 revamp module parsing

    see context/specs/174-revamp-module-parsing.md

    - [x] Parser entrypoint now uses `topLevelModuleP`, legacy named/anon paths and `Module.isImplicit` removed; spec updated with explicit-name rules.
    - [x] ParserApi/CompilerApi/CodeGenApi/NativeEmitter/CLI/yolo helpers and all call sites now pass explicit module names (tests/tools/CLI).
    - [x] BaseEffFunSuite helpers default to string names; grammar tests/docs updated and full suite rerun after scalafmt/scalafix.
    - [x] Tests and `mml/samples` no longer wrap content in `module ... =`; top-level members stand alone.
    - [x] Error recovery follow-up: broadened `failedMemberP`, kept module cleanup logic aligned, and re-enabled the “rubbish at the end” test once behavior is correct.

## Recent Changes

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
