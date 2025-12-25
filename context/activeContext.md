# MML Active Context

## Active

### CPS rewrite

    __stmt: TypeRef Unit(unresolved)


* is is not typed! 
* but crucially, why pass unit and not use a nullary function?
    - is it because lambdas look like () -> ???; ?


### CLI and precodegen validation phase bug (includes CompilerApi naming)
- find `FIXME: BUG`
- pre codegen validation should run as a semantic phase or maybe as a separate pipeline for codegen!
- note: it currently runs in the CLI because SemanticApi is mode-agnostic and the validator
  needs `CompilationMode`
- CompilerApi naming confusion: `compileState` vs `compileString` same inputs, different error
  behavior
- need to decide rename strategy (options):
    - option A: `compileState` -> `compileStateAllowErrors`, `compileString` -> `compileStateStrict`
    - option B: `compileState` -> `compileStateRaw`, `compileString` -> `compileStateChecked`
    - option C: keep `compileState`, rename `compileString` -> `compileStateOrFail`

---

## Next Steps


- **#188**: Literal lambdas (`(x) -> x + 1`)
- **Placeholder partial application**: `_ + 2` syntax (brainstormed in `docs/brainstorming/placeholder-partial-application.md`)

## Recent Changes

- **Typechecker error context cleanup**: Replaced fake TypeRef sentinels for unresolvable types
  with value-context metadata, updated error printing to say "Unable to infer type" (optionally
  "for 'name'"), and added regression test ensuring invalid applications no longer report
  "function of type 'f'".
- **Parser keyword word boundary fix**: Keywords now require word boundaries (e.g., `op` no longer
  matches prefix of `open`). Added `wordBoundary` negative lookahead in `keywords.scala`.
- **File I/O exposed to MML**: `open_file_read`, `open_file_write`, `open_file_append`, `close_file`
  functions and `mkBufferWithFd` for writing to arbitrary file descriptors. Runtime handles C string
  conversion transparently via `to_cstr()` helper.
- **Docs fix**: Corrected operator syntax in design docs (removed erroneous `precedence` keyword -
  correct syntax is `op +(a: Int, b: Int): Int 60 left`). Fixed incorrect "significant indentation"
  claim in compiler-design.md.
- **Rudimentary benchmark infrastructure**: Added `benchmark/` folder with Makefile for C/Go
  comparison builds. Ackermann function stress-tests recursion; MML beats clean C by ~20%.
- **LTO enabled**: Linker now uses `-flto` for link-time optimization. Dead code from runtime is
  stripped (e.g., unused `open_file`, `run_process` removed from fizzbuzz binary).
- **Unit/void phi node fix**: Conditionals returning Unit no longer emit invalid `phi void` nodes.
  `ExpressionCompiler` skips phi emission for void type. FizzBuzz sample now compiles and runs.
- **Recursive function error improved**: Functions with self-reference but no return type now emit
  `RecursiveFunctionMissingReturnType` ("Missing return type for self-recursive function") instead
  of confusing `UnresolvableType`. Fix in `TypeChecker.checkMember`.
- **Nested let in member bindings restored**: Added `letExprP` to `termMemberP` so
  `let x = let y = 1; y + 1;` parses at member level. Test added in TypeCheckerTests.
- **str_to_int added**: Runtime now exposes strict `str_to_int(String): Int` and it is injected in
  the semantic prelude. Sample `str_to_int.mml` updated to print `to_string` result. Docs updated
  to list `readline`, `to_string`, and `str_to_int`.
- **ExpressionRewriter now rewrites inside App**: ensures expression-level `let` bodies with
  applications are rewritten correctly. Regression test added in `AppRewritingTests.scala`.
- **Expression-level let bindings (#149 complete)**: `let x = E; body` inside expressions now works.
  Parser desugars to `App(Lambda([x], body), E)`. TypeChecker infers param type from arg (checks arg
  first for immediately-applied lambdas). Codegen handles single-param immediate lambda application.
  RefResolver/TypeResolver traverse App/Lambda in expressions. Tests in TypeCheckerTests.scala.
- **Alphanumeric operator mangling fix**: `and`, `or`, `not` now mangle to `op.and.2`, `op.or.2`,
  `op.not.1` instead of `op.a_n_d.2`, `op.o_r.2`, `op.n_o_t.1`. Alphanumeric operators pass through unchanged.
- **Unit parameter elision**: Codegen now skips `void` params/args - can't pass void in LLVM.
- **readline() added**: Nullary function returning String, declared in `injectCommonFunctions`.
- **readline-loop.mml sample**: REPL loop using CPS-style function chaining (proves LLVM TCO works).
- **Recursion enabled**: RefResolver now includes current binding in scope (removed self-exclusion filter).
- **App.fn type expanded**: Changed from `Ref | App` to `Ref | App | Lambda` to support immediate lambda application.
- **Left-associative application fixed**: `buildAppChain` now handles `Ref` and atom arguments directly, ensuring `f x y` → `((f x) y)`.
- **Curried application tests**: Added tests for multi-arg calls and nested function calls with parameters.
- **tco-factorial.mml sample**: Tail-recursive factorial example added.
- **Partial application eta-expansion (#136 complete)**: ExpressionRewriter's `wrapIfUndersaturated`
  transforms undersaturated applications into Lambda expressions. Both partial application
  (`let greet = concat "Hello, "`) and function aliasing (`let f = add`) now work. TypeChecker
  handles synthesized Lambdas via `checkLambdaWithContext`. Codegen emits wrapper functions.
  Chained partial application (`let add10 = add3 10; let add10and20 = add10 20`) works via
  `transformedBindings` map. Tests in `FunctionSignatureTest.scala`. All 150 tests pass.
- **Operator mangling fix**: Custom operators now use mangled names in codegen calls (was using
  unmangled names like `@**` instead of `@op.star_star.2`).
- **Bnd(Lambda) unification complete**: All callables (functions, operators) now represented as
  `Bnd` with `Lambda` body and `BindingMeta`. Parser emits directly, semantic phases use meta,
  codegen targets `Bnd(Lambda)`.
- Pretty-print function types in arrow form: `TypeFn` now renders as `A -> B`.
- Type ascription parsing supports function types (`->`) everywhere.
- **Nullary auto-apply removed (#186)**: Explicit `()` required for nullary calls.
- Codegen errors pretty-print with source snippets.
- Binary entry-point: `main` can return `Int64` or `Unit` (Unit rewrites to `ret i64 0`).
- TypeChecker + codegen store/consume full `TypeFn` signatures (#178 complete).
- **AST restructured (#170 complete)**: Split AstNode.scala into 6 focused files.
- **Module parsing hardened (Spec 174 complete)**: Parser recovers from errors.
- Unit type vs value: `Unit` is type, `()` is value.
