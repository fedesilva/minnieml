# MML Task Tracking

## CRITICAL Rules

------------------------------------------------------------------------
/!\ /!\ Do not edit without explicit approval or direct command. /!\ /!\
/!\ /!\ Follow rules below strictly                              /!\ /!\
/!\ /!\ COMPLETING A TASK? -> ADD [COMPLETE] TAG. NEVER DELETE.  /!\ /!\
------------------------------------------------------------------------

* *Always read* `context/task-tracking-rules.md` 
  *before* working with this file - even if you read it before, 
  it might have changed in the meantime.

* Follow the rules stated above.
* These rules are mandatory unless the Author explicitly overrides them.

## Active Tasks

### #243 Bug: isMoveOnRebind does not move native heap types [COMPLETE]

- GitHub: https://github.com/fedesilva/minnieml/issues/243
- [x] Fix `isMoveOnRebind` to handle native heap types (String, Buffer, arrays)
- [x] Add tests for String/Buffer/array move-on-rebind
- [x] Verify samples still pass

### #188 Literal lambdas and captures

- GitHub: https://github.com/fedesilva/minnieml/issues/188
- Reference: `docs/brainstorming/language/lambda-syntax-design.md`
- Phase 1 — Parser: parse `{ params -> body }` syntax into Lambda AST nodes (no runnable programs yet) [COMPLETE]
  - [x] Add `arrowKw`, `lambdaLitP` parser combinator
  - [x] Wire into `termP`/`termMemberP`
  - [x] Tests in `LambdaLitTests.scala`
- Phase 2 — Codegen: semantic analysis + code generation for lambda values (closures, function pointers)
  - [ ] Name resolution and type checking for lambdas in expression position
  - [ ] Closure representation and codegen
- Phase 3 — Ownership: capture semantics and memory management (rules TBD/confirm)
  - [ ] Capture analysis (populate `captures` list in semantic phase)
  - [ ] Ownership rules for captured bindings (borrow vs move — needs design confirmation)


## Recent Changes

- 2026-03-21: #188 Phase 1 complete — parser support for literal lambdas.
  - Added `arrowKw` keyword, `lambdaLitP` parser combinator in `expressions.scala`.
  - Wired into `termP`/`termMemberP`. Updated `types.scala` to use `arrowKw`.
  - Guarded `->` from being parsed as operator in `identifiers.scala`.
  - 11 new tests in `LambdaLitTests.scala`. All 318 tests pass, benchmarks compile.
- 2026-03-21: Fix #243: `isMoveOnRebind` now moves native heap types [COMPLETE].
  - Changed `isMoveOnRebind` to use `TypeUtils.isHeapType` instead of `isStructWithHeapFields`,
    so rebinding native heap types (String, Buffer, arrays) transfers ownership.
  - Updated `"string rebinding still borrows"` test to `"string rebinding moves ownership"` —
    now asserts `UseAfterMove` when original is used after rebind.
  - Added `"string rebinding without use-after-move is valid"` and
    `"string rebinding target gets freed"` tests.
  - All 307 tests pass, 17/17 memory tests pass (ASan+LSan), benchmarks compile.

