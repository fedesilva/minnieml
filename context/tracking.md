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

* there are issues to address:
  `context/specs/lambdas-work-review.md`

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
    - [x] 3.4.0 — Stdlib: RawPtr type + mml_free_raw C function
    - [x] 3.4.1 — ClosureMemoryGenerator phase (env TypeStruct + free fn)
    - [x] 3.4.2 — OwnershipAnalyzer: termAllocates, isOwnedType, mkFreeCall for TypeFn
    - [x] 3.4.3 — Codegen: extractEnvPtr from fat pointer for free calls
    - [x] 3.4.4 — TypeChecker: propagate typeSpec to Lambda.captures (pre-existing bug fix)
    - [x] 3.4.5 — captures.mml: compiles, runs, 0 leaks
    - [x] 3.4.6 — Fix: split lambdaAllocates from termAllocates (only let-bound, not arg-position)
    - [x] 3.4.7 — Universal __free_closure with embedded dtor pointer in env struct field 0
    - [x] 3.4.8 — All tests pass: 333 unit, 18/18 mem (including closure-capture 0 leaks)
  - [ ] 3.4-QA — Codegen & ownership review fixes (spec: `context/specs/lambdas-work-review.md`)
    - [ ] 3.4-QA.1 [P1] Keep emitted env setup for let-bound capturing lambdas (`Applications.scala`)
    - [ ] 3.4-QA.2 [P1] Allocate unique symbols for let-bound lambda definitions (`Applications.scala`)
    - [ ] 3.4-QA.3 [P1] Size closure env allocations using LLVM struct layout (`ExpressionCompiler.scala`)
    - [ ] 3.4-QA.4 [P2] Pass real closure env on recursive capturing lambdas (`Applications.scala`)
    - [ ] 3.4-QA.5 [P2] Keep capture support in tail-recursive lambda path (`ExpressionCompiler.scala`)
    - [ ] 3.4-QA.6 [P2] Stop freeing non-capturing function values as closures (`OwnershipAnalyzer.scala`)
  - [ ] 3.5 — Heap-type captures (String, structs) + clone/free — spec: `context/specs/lambda-step3-ownership.md`

### Update language ref and memory model docs.

* lambdas are in, need to update.
* updates to the type checker (type annotations are not mandatory for lambdas but they might still be needed)
* see the changelog below and the git history.
* lang ref needs a link to the memory model doc.


### Update the design doc

* there are new phases in the semantic stage


### QA Test infra

* add comments to each test, describing in plain english what they do, what they expect, etc
* move all the newer extractors to the test extractors module (or new subppackage)
  - prefix them with TX
  - are we still using TXApp and where not why? and if we can use them.

### QA Parser

* Add commentary with examples to the parsers

## Recent Changes

- 2026-03-22: #188 Phase 3.4 — closure ownership integration
  - ClosureMemoryFnGenerator: synthesizes env TypeStruct (with embedded dtor ptr at field 0) + per-env free functions + universal `__free_closure`.
  - OwnershipAnalyzer: capturing lambdas tracked as owned values, free calls inserted at scope exit.
  - Codegen: env struct includes dtor pointer; universal free dispatches via dtor; env ptr extracted from fat pointer at call site.
  - Stdlib: `RawPtr` type, `mml_free_raw` function. C runtime: `mml_free_raw`.
  - TypeChecker fix: propagate typeSpec to Lambda.captures (pre-existing bug).
  - All mem tests pass, including closure-capture (0 leaks).
- 2026-03-22: #244 bottom-up lambda param inference
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
- 2026-03-21: Fix #243: `isMoveOnRebind` now moves native heap types
  - `isMoveOnRebind` uses `TypeUtils.isHeapType` instead of `isStructWithHeapFields`.
