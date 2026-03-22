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


### #188 Literal lambdas and captures

- GitHub: https://github.com/fedesilva/minnieml/issues/188
- Reference: `docs/brainstorming/language/lambda-syntax-design.md`
- Phase 1 — Parser [COMPLETE]
- Phase 2 — Codegen (non-capturing) [COMPLETE]
- Phase 3 — Closures: capturing lambdas + ownership
  - Spec: `context/specs/lambda-step3-closures.md`
  - [x] 3.0 — Update tracking with Phase 3 subtasks
  - [x] 3.1 — CaptureAnalyzer semantic phase
  - [x] 3.2 — Fat pointer calling convention (`{ ptr fn, ptr env }`)
  - [x] 3.3 — Env struct codegen (value-type captures)
  - [ ] 3.4 — Ownership integration (env as owned value) — spec: `context/specs/lambda-step3-ownership.md`
  - [ ] 3.5 — Heap-type captures (String, structs) + clone/free — spec: `context/specs/lambda-step3-ownership.md`


### #244 Bottom-up type inference for lambda parameters [COMPLETE]

- GitHub: https://github.com/fedesilva/minnieml/issues/244
- Spec: `context/specs/244-lambda-param-inference.md`
- Infer lambda param types from body usage (e.g. `{ x -> x + 1 }` infers `x: Int` from `+`)
- Monomorphic signature lookup — no unification, no generalization
- [x] 1 — Implement `inferParamTypesFromBody` AST walk in TypeChecker
- [x] 2 — Wire into `checkLambdaWithContext` after existing expectedType inference
- [x] 3 — Error messages for conflicts and unresolvable params
- [x] 4 — Tests: direct op usage, captures, let-aliases, conflict/no-anchor errors
- [x] 5 — Verify existing top-down inference still takes priority (no regression)

## Recent Changes

- 2026-03-22: #244 bottom-up lambda param inference [COMPLETE].
  - TypeChecker: infer still-untyped lambda params from monomorphic body usage sites.
  - Supports simple let-alias propagation and capture-assisted anchors.
  - Adds dedicated conflict / no-anchor lambda inference errors and LSP/error-printer plumbing.
  - Tests cover operator/function anchors, alias chains, captures, conflicts, and top-down priority.
  - Sample: `mml/samples/lambda-infer-args.mml` now demonstrates unannotated lambda param inference.
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
