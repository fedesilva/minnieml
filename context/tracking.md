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
  - [x] QA complete
    - [x] TypeChecker: pre-seed recursive let type from param typeAsc (not just lambda typeAsc)
    - [x] TypeChecker: `extractTypeFn` resolves type aliases via `resolveAliasChain`
    - [x] TypeChecker: `areTypesCompatible` treats `TypeFn(Nil, R)` ≡ `TypeFn([Unit], R)` (nullary ≡ thunk)
    - [x] Codegen: `resolveToTypeFn` helper in emitter package (resolves aliases for fn type detection)
    - [x] Codegen: `isIndirect` check uses `resolveToTypeFn` instead of `isInstanceOf[TypeFn]`
    - [x] Let-bound lambdas: stable names, TCO, direct self-calls
      - TypeResolver: resolve param typeAsc in expression-level lambdas (4 cases)
      - Stable names: `mangleName(param.name)` instead of `allocAnonFnName`
      - TailRecursionDetector: detect let-bound lambda self-recursion via binding param
      - Codegen: TCO path for let-bound lambdas (deferred emission via `compileTailRecursiveLambda`)
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

- 2026-03-21: runtime — add FORCE_INLINE to string/IO functions.
  - `readline`, `print`, `println`, `concat`, `substring`, `free_string`,
    `string_builder_append`, `string_builder_finalize`, `to_cstr`.
- 2026-03-21: #188 Phase 2 QA — let-bound lambdas: stable names, TCO, direct self-calls.
  - TypeResolver: resolve param typeAsc in expression-level lambdas (4 cases missed params).
  - Stable names: `mangleName(param.name)` replaces `allocAnonFnName`.
  - TailRecursionDetector: traverse let-binding chains, detect self-recursion via binding param.
  - Codegen: TCO deferred emission, generalized `isSelfRef`/`findTailRecBody`.
- 2026-03-21: #188 Phase 2 QA — type ascription, recursive lets, codegen fixes.
  - General term-level type ascription in parser (`Term.withTypeAsc`).
  - Lambda `}: Type` return ascription flows as expected type for body.
  - RefResolver: let-binding name in scope during arg resolution.
  - TypeChecker: pre-seed binding type from lambda typeAsc for recursive lets.
  - Codegen: pre-allocate anon fn name for recursive let-bound lambda self-calls.
- 2026-03-21: #188 Phase 2 — lambda codegen for non-capturing lambdas.
  - TypeChecker: infer lambda param types from call-site expectedType.
  - `getLlvmType(TypeFn)` → `"ptr"` (opaque function pointer).
  - `compileLambdaLiteral`: expression-position lambdas to internal LLVM functions.
  - `compileIndirectCall`: call through function pointers (TypeFn in scope).
  - `CodeGenState`: `deferredDefinitions` + `nextAnonFnId`.
  - Runtime: `str_to_int` panics on invalid input, `mml_panic` helper.
- 2026-03-21: #188 Phase 1 — parser support for literal lambdas.
  - `arrowKw` keyword, `lambdaLitP` parser combinator in `expressions.scala`.
  - Wired into `termP`/`termMemberP`. Reuse `arrowKw` in type arrow parsing.
  - Guard `->` from operator parsing in `identifiers.scala`.
- 2026-03-21: Fix #243: `isMoveOnRebind` now moves native heap types [COMPLETE].
  - `isMoveOnRebind` uses `TypeUtils.isHeapType` instead of `isStructWithHeapFields`.

