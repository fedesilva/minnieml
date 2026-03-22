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
  - [x] TypeChecker: infer lambda param types from call-site context
  - [x] `getLlvmType(TypeFn)` → `"ptr"`, deferred definitions in CodeGenState
  - [x] Compile lambda literals as internal functions returning function pointers
  - [x] Indirect call codegen for function-pointer variables
  - [x] Test with sample program + full test suite (318 pass, benchmarks compile)
  - [x] Runtime: `str_to_int` panics on invalid input, `mml_panic` helper
  - [x] General term-level type ascription (`expr: Type`) in parser + `Term.withTypeAsc`
  - [x] Lambda return type ascription (`}: Type`) used as expected type for body
  - [x] RefResolver: let-binding name in scope during arg resolution (recursive lets)
  - [x] TypeChecker: pre-seed binding type from lambda typeAsc (recursive lets)
  - [x] Codegen: pre-allocate anon fn name for recursive let-bound lambdas
  - [ ] QA in progress
    - [x] TypeChecker: pre-seed recursive let type from param typeAsc (not just lambda typeAsc)
    - [x] TypeChecker: `extractTypeFn` resolves type aliases via `resolveAliasChain`
    - [x] TypeChecker: `areTypesCompatible` treats `TypeFn(Nil, R)` ≡ `TypeFn([Unit], R)` (nullary ≡ thunk)
    - [x] Codegen: `resolveToTypeFn` helper in emitter package (resolves aliases for fn type detection)
    - [x] Codegen: `isIndirect` check uses `resolveToTypeFn` instead of `isInstanceOf[TypeFn]`
    - [ ] **BLOCKED**: codegen emits `call void @loop()` instead of using the pre-allocated anon fn name
      - Sample: `mml/samples/let-lambda-type-ascription.mml` (recursive let-bound lambda with type alias ascription)
      - Root cause: `compileApp` → `isIndirect` check may still fail because the `TypeRef("ForeverFn")` on the
        ref's `typeSpec` might lack a `resolvedId` (the typeSpec was set by TypeChecker pre-seeding from
        `param.typeAsc`, which should have been resolved by TypeResolver — needs verification)
      - The lambda IS emitted as `@letlambdatypeascription__anon_0` but both call sites (line 195, 243 in IR)
        emit `call void @loop()` — meaning `isIndirect` is false, falling through to `compileRegularCall`
      - Approach discussed: use stable names derived from binding ID (e.g. `main_loop`) instead of `allocAnonFnName`,
        since `FnParam.id` already encodes `module::bnd::owner::name::uuid`
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

- 2026-03-21: #188 Phase 2 QA fixes — type ascription, recursive lets, codegen.
  - General term-level type ascription in parser (`Term.withTypeAsc` on AST).
  - Lambda `}: Type` return ascription flows as expected type for body.
  - RefResolver puts let-binding name in scope during arg resolution.
  - TypeChecker pre-seeds binding type from lambda typeAsc for recursive lets.
  - Codegen pre-allocates anon fn name so recursive let-bound lambdas self-call.
  - `readline-loop-lambda.mml` compiles and runs. All 318 tests pass, benchmarks compile.
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

