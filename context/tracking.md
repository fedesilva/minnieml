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
- Phase 2 — Codegen (non-capturing): lambda values as function pointers, indirect calls
  - Spec: `context/specs/lambda-step2.md`
  - [ ] TypeChecker: infer lambda param types from call-site context
  - [ ] `getLlvmType(TypeFn)` → `"ptr"`, deferred definitions in CodeGenState
  - [ ] Compile lambda literals as internal functions returning function pointers
  - [ ] Indirect call codegen for function-pointer variables
  - [ ] Test with sample program + full test suite
- Phase 3 — Closures: capturing lambdas + ownership
  - [ ] Capture analysis (populate `captures` list in semantic phase)
  - [ ] Closure representation and codegen
  - [ ] Ownership rules for captured bindings (borrow vs move — needs design confirmation)


### #244 Bidirectional type inference for lambda parameters

- GitHub: https://github.com/fedesilva/minnieml/issues/244
- Infer lambda param types from body usage (e.g. `{ x -> x + 1 }` infers `x: Int` from `+`)
- Requires constraint-based or unification-based inference — design TBD
- [ ] Design inference approach (type variables + unification vs local constraint propagation)
- [ ] Implementation
- [ ] Tests

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

