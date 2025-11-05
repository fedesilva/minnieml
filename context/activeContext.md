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

 - #174 module parsing is fragile

    This code fails:

    ```
        let a = 1;
        rubbish-at-the-end
    ```

    and it should, but it fails with a parsing error, and that is unnacceptable.

    ```
        sbt:mml> console
        [info] compiling 1 Scala source to /Users/f/Workshop/mine/mml/mml/modules/mmlc/target/scala-3.6.4/classes ...
        Welcome to Scala 3.6.4 (21.0.6, Java Java HotSpot(TM) 64-Bit Server VM).
        Type in expressions for evaluation. Or try :help.

        scala> :load scripts/include.scala

        scala> rewrite("""
             | let a = 1;
             | rubbish-at-the-end
             | """)
        Parse error:
          Failure(Expected moduleP:1:1 / (namedModuleP | anonModuleP):2:1, found "let a = 1;")
        Failed to parse module

        scala>
    ```

    this one fails, too.

    ```
    let a = 1;;;
    ```

    So for some reason when we fail to parse the module itself, we can't recover.

    We need to investigate, and fix this issue.

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
* recursion 
* protocols 
