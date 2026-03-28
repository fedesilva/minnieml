# MML Task Tracking

## CRITICAL Rules

------------------------------------------------------------------------
/!\ /!\ Do not edit without explicit approval or direct command. /!\ /!\
/!\ /!\ Follow rules below strictly                              /!\ /!\
/!\ /!\ COMPLETING A TASK? -> ADD [COMPLETE] TAG. NEVER DELETE.  /!\ /!\
/!\ /!\ DO NOT ADD [COMPLETE] to the "recent changes" entry      /!\ /!\
------------------------------------------------------------------------

* *Always read* `context/task-tracking-rules.md` 
  *before* working with this file - even if you read it before, 
  it might have changed in the meantime.

* Follow the rules stated above.
* These rules are mandatory unless the Author explicitly overrides them.

## Active Tasks



### Update the design doc

* there are new phases in the semantic stage
* there are lambdas now, and captures and the memory management model has changed.


### #188 Literal lambdas and captures

- GitHub: https://github.com/fedesilva/minnieml/issues/188
- Reference: `docs/brainstorming/language/lambda-syntax-design.md`
- Pending follow-ups:
  - QA / codegen / ownership review
    - Spec: `context/specs/lambdas-work-review.md`
    - [ ] 3.4-QA.6 [P1] Pass real closure env on recursive capturing lambdas (`Applications.scala`)
    - [ ] 3.4-QA.10 [P2] mergeSubState fragile manual field sync (`ExpressionCompiler.scala`)
    - [ ] 3.4-QA.11 [P2] Two codepaths for closure free could diverge (`Applications.scala`)
    - [ ] 3.4-QA.13 [P3] Nullary/Unit thunk unification may mask type errors (`TypeChecker.scala`)
    - [ ] 3.4-QA.14 [P3] TypeFn globally mapped to fat pointer may affect non-closure contexts (`codegen/emitter/package.scala`)
    - [ ] 3.4-QA.15 [P3] OwnershipAnalyzer 5-tuples should be a case class (`OwnershipAnalyzer.scala`)
    - [ ] 3.4-QA.16 [P3] Term.withTypeAsc silently ignores unknown term types (`ast/terms.scala`)
    - [ ] 3.4-QA.17 [P3] FORCE_INLINE on non-hot-path runtime functions (`mml_runtime.c`)
    - [ ] 3.4-QA.19 [P2] Replace shape-coupled/name-coupled lambda semantic tests with semantic extractors (`CaptureAnalyzerTests.scala`, `TypeCheckerTests.scala`, `LambdaLitTests.scala`)
    - [ ] 3.4-QA.20 [P3] Remove new `TODO:QA` by extracting ownership test helpers or tracking them properly (`OwnershipAnalyzerTests.scala`)
    - [ ] 3.4-QA.21 [P2] Mutable traversals + early returns in ClosureMemoryFnGenerator — replace var/builder/return with folds and if-else (`ClosureMemoryFnGenerator.scala`)
    - [ ] 3.4-QA.22 [P2] asInstanceOf cast in ClosureMemoryFnGenerator.tagLambdas breaks no-exceptions rule (`ClosureMemoryFnGenerator.scala:312`)
    - [ ] 3.4-QA.23 [P3] Hardcoded string name matching for closure free dispatch (`Applications.scala:312,316`)
    - [ ] 3.4-QA.24 [P3] Inconsistent Cats syntax in new codegen code — use .asRight/.asLeft/.some/.none (`ExpressionCompiler.scala`, `Applications.scala`)
    - [ ] 3.4-QA.25 [P1] Closure env fields of `TypeFn` fail struct/TBAA type-name lowering (`TypeNameResolver.scala`, `TbaaEmitter.scala`, `raytrace2` fully-local helper case)

  - Heap-capture follow-up
    - [ ] 3.5.1 Literal heap captures: design decision
      - Literal String bindings (`let s = "hello"`) have `Literal` ownership state, so freeing a captured literal String can free `.rodata`.
      - Struct constructors already auto-clone literals for consuming params (`argNeedsClone`).
      - Options: auto-clone at capture site, reject literal heap captures for now, or track heap-vs-static captures explicitly.
      - Related: `let-shadow-in-lambda.mml` sample reproduces the issue.



### Protocols (ad-hoc polymorphism)

**Next major feature.** Gives us Clone, Drop, and a foundation for everything else.

* Implement protocols (type classes / ad-hoc polymorphism)
* Drop protocol → free functions (`__free_T`)
* Clone protocol → clone functions (`__clone_T`)
* Native types: user provides protocol instances
* Structs: auto-derive both Drop and Clone
* Reuse and extend existing MemoryFunctionGenerator machinery to generate protocol instances
* Operators in protocols deferred for later (complex)
* Touches: parser, ref resolver, type checker (NOT expression rewriter)
* Start simple: single-type instances, nothing fancy
* Unblocks 3.5.1 once Clone protocol exists (user writes `clone x` via protocol dispatch)

### QA Test infra

* add comments to each test, describing in plain english what they do, what they expect, etc
* move all the newer extractors to the test extractors module (or new subppackage)
  - prefix them with TX
  - are we still using TXApp and where not why? and if we can use them.

### QA Parser

* Add commentary with examples to the parsers

## Recent Changes

- 2026-03-27: #245 Inner function syntax
  - Parser: added local `fn name(...): Ret = ... ; expr` surface syntax in `expressions.scala`.
  - Tests: grammar, capture analysis, and typechecker coverage added for typed and recursive inner functions.
  - Docs: language reference now documents inner functions as sugar for local let-bound lambdas.
  - Samples: added `mml/samples/readline-loop-inner-fn.mml`; `mml/samples/raytrace2.mml` now documents
    the shipped sample compromise and the deferred fully-local-helper `TypeFn` closure bug.
  - Verification already completed in-session: targeted tests, fast samples, full suite, publish,
    benchmarks, installed `mmlc` recursive inner-function check, and direct runs of the new
    readline sample / built `raytrace2`.

- 2026-03-26: No-`end` syntax
  - Parser: removed `end` from conditional syntax; nested parser frames now close with semicolons only.
  - Parser: function, operator, conditional, and lambda bodies now require their own final expression `;`.
  - Tests/tooling: migrated Scala parser/semantic/codegen/LSP fixtures to the new semicolon ownership rules.
  - Repo-wide `.mml`: migrated samples, benchmarks, mem harness inputs, and intentional negative fixtures so
    positives compile and negatives fail for their intended reason instead of stale syntax.
  - Docs: refreshed syntax documentation, including `docs/language-reference.md`.
  - Verification: `sbtn "scalafmtAll; scalafixAll; test; mmlcPublishLocal"` passed (`337/337`);
    `make -C benchmark mml` passed; `tests/mem/run.sh` passed (`19/19`);
    positive standalone front-end sweep passed for all compileable checked-in `.mml` files.

- 2026-03-25: #246 LLVM IR VS Code extension
  - Added a standalone VS Code extension under `tooling/vscode-llvm-ir` for `.ll` files.
  - Implemented editor-side symbol indexing, hover, go-to-definition, find-references, and
    document outline support for LLVM IR symbols.
  - Added explicit LLVM IR output/context-menu commands and a separate installer script
    `tooling/install-vscode-llvm-ir-extension.sh`.
- 2026-03-24: #188 Phase 3.5 — heap-type capture ownership (move semantics + env field free)
  - OwnershipAnalyzer: heap-type captures move into env; borrowed captures → error.
  - Captured heap bindings set to Borrowed inside lambda body (env owns them).
  - Codegen: env destructor emits GEP+load+free for each heap field, ABI-lowered args.
  - `sizeOfLlvmStructResolved`: state-aware struct sizing resolves named types for env malloc.
  - New `CapturedBorrowedHeapBinding` error + printer/LSP/extractor wiring.
  - New mem test: `tests/mem/closure-heap-capture.mml` (19/19 ASan+LSan pass).
- 2026-03-24: #188 3.4-QA P1 batch — QA.3, QA.4, QA.5, QA.8
  - QA.3: `sizeOfLlvmStruct` replaces naive sum with alignment-padded struct sizing for env malloc.
  - QA.4: `IdentityHashMap` for lambda→envStructName (reference equality, not structural).
  - QA.4: Refactored `collectCapturingLambdas` from mutable vars to pure fold.
  - QA.5: Removed UUID from `paramId`; counter-based env names already ensure uniqueness.
  - QA.8: Null guard in `emitClosureFreeViaEnvDtor` — non-capturing fns (null env) skip dtor.
  - QA.8: `exitBlock` on `CompileResult` so tail-rec PHI tracks through null-guard blocks.
  - Also closes QA.18 (same root cause as QA.3).
  - 336/336 tests, 18/18 mem tests, benchmarks compile.
- 2026-03-24: #188 Nested lambda workstream A+B+C — capturing lambdas + TCO
  - QA.1 (Phase A): Capturing lambdas preserve call-site IR; output reset conditioned on `isLiteral`.
  - QA.2 (Phase B): Counter-suffixed unique symbols for let-bound lambda definitions.
  - QA.7 (Phase C): TCO for capturing lambdas.
    - Extracted `emitCallSiteEnv` + `EnvSetupResult` (shared env setup for regular and tail-rec paths).
    - Extracted `emitCaptureLoads` as package-level function in FunctionEmitter.
    - `compileTailRecursiveLambda`: loads captures in entry block, adjusted phi start, merges capture scope.
    - `extractSelfCallFromAccumulated`: handles OwnershipAnalyzer `__ownership_result` wrapper in tail position.
  - 336/336 unit tests, 18/18 ASan mem tests pass. Leaks-mode pending.
  - Known limitation: heap-type captures share buffer pointer (Phase 3.5).
- 2026-03-22: #188 3.4-QA.9 — Replace mutable vars in compileCapturingLambda with foldLeft.
- 2026-03-22: Update language reference and memory model docs for lambdas/closures.
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
