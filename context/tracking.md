# MML Task Tracking

## CRITICAL Rules

------------------------------------------------------------------------
/!\ /!\ Do not edit without explicit approval or direct command. /!\ /!\
/!\ /!\ Follow rules below strictly                              /!\ /!\
/!\ /!\ COMPLETING A TASK? -> ADD (COMPLETE) TAG. NEVER DELETE.  /!\ /!\
/!\ /!\ DO NOT ADD (COMPLETE) to the "change log" section        /!\ /!\
------------------------------------------------------------------------

* *Always read* `context/task-tracking-rules.md` 
  *before* working with this file - even if you read it before, 
  it might have changed in the meantime.
* These rules are mandatory unless the Author explicitly overrides them.

## Active Tasks

### Update the design doc

- GitHub: https://github.com/fedesilva/minnieml/issues/251

* there are new phases in the semantic stage
* there are lambdas now, and captures and the memory management model has changed.
      
### Unify lambdas

- GitHub: https://github.com/fedesilva/minnieml/issues/255

  - top level fn, and let bound lambdas (or inner functions) should be treated identically.
    - the code that handles them (sema and codegen diverges)
  - environment captures are also different for borrow and move lambdas
    - should be unified 
      - alloca vs malloc is an optimization that can be derived.
  - lambdas and captures are a single thing, so both unifications 
    above should unify too.
  
  - discuss, make a plan



### Call-site move 

- GitHub: https://github.com/fedesilva/minnieml/issues/254

  - (`~expr`) 
  - users cannot yet force-move a value at a call site without a consuming parameter. 
  - Will be implemented after borrow-by-default captures ship.

### carry escape metadata all the way to the codegen

- GitHub: https://github.com/fedesilva/minnieml/issues/249

* Propagate ownership / escape metadata to codegen
* Carry escape metadata in bindings
* Carry escape metadata in lambdas
* Metadata such as `NoEscapes`, `NoAlias`, and related facts should let codegen decide when
  `alloca` is safe instead of `malloc` and emit better IR
* Codegen should use that information for decisions such as:
  - `alloca` vs `malloc`
  - other optimizations driven by escape / alias facts


### Protocols (ad-hoc polymorphism)

- GitHub: https://github.com/fedesilva/minnieml/issues/165

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
* RefResolver should produce candidate sets, not resolved refs, for protocol-polymorphic
  references
  - overloading allowed by name + type signature; resolution deferred to the type checker
  - one free overload max per operator / function name
  - all overloads of an operator must share arity, precedence, and associativity so
    `ExpressionRewriter` can build a determinate AST before types are known
  - candidate entries for protocol members should carry symbolic `(ProtocolId, MemberName)` refs
    that the type checker patches to concrete implementations later

### QA Test infra

- GitHub: https://github.com/fedesilva/minnieml/issues/253

* add comments to each test, describing in plain english what they do, what they expect, etc
* move all the newer extractors to the test extractors module (or new subppackage)
  - prefix them with TX
  - are we still using TXApp and where not why? and if we can use them.

### QA Parser

- GitHub: https://github.com/fedesilva/minnieml/issues/252

* Add commentary with examples to the parsers

## Change Log

- 2026-03-29: #188 close loopified borrow-closure validator follow-ups
  - Codegen: loopified borrow-closure validation now clears rebound active names, respects lambda binder shadowing, and permits immediately-invoked borrow lambdas while keeping the borrow-closure forwarding/reuse checks.
  - Tests: added loopified regression coverage for rebinding shadowing, lambda-parameter shadowing, and the direct-invocation validator path.

- 2026-03-29: #188 loopified borrow-closure env hoist with tracked validator follow-ups pending
  - Codegen: function emission now supports an entry-block prologue so borrow closure env `alloca`s created on loopified paths can be hoisted out of repeated execution while move closures keep their existing heap/destructor flow.
  - Loopified borrow-closure validation: added conservative codegen-time checks for borrow closures that may survive across iterations; the current shadowing / immediate-invocation regressions are tracked above and remain pending.
  - Tests: added closure codegen coverage for entry-block hoisting and for rejecting loop-carried borrow closures.

- 2026-03-29: #188 stabilize forward-reference reorder and close borrow-closure return-wrapper escape
  - TypeChecker: topological reordering now derives queue seeding, dependency release order, and cycle fill from source-ordered member ids so unrelated bindings keep source order while forward references still reorder correctly.
  - OwnershipAnalyzer: `returnedBorrowClosures` now follows returned scoped-binding wrappers only when the wrapper body actually returns the bound value, covering parser-lowered `let` / local-`fn` `App(Lambda(...), arg)` escape cases without flagging non-escaping borrow closures.
  - Tests: added regression coverage for stable reorder preservation and for borrow-capturing closure escape through both `let` bindings and local inner-function returns.

- 2026-03-29: #188 Reject borrow-capturing closures that escape via return
  - OwnershipAnalyzer: added `returnedBorrowClosures` to detect borrow-capturing lambda literals in return position; unconditional check (not gated behind return type).
  - New error: `BorrowClosureEscapeViaReturn` with full printer/LSP/source-snippet wiring.
  - Tests: added ownership tests for borrow-escape rejection, move-escape acceptance, and non-capturing acceptance.
  - Sample: `mml/samples/mem/borrow-closure-escape.mml` demonstrates the error.

- 2026-03-29: Lambda body trailing semicolon now optional
  - Parser: `lambdaBodyExprP` accepts either `;` or lookahead `}` as body terminator.
  - `{ x -> x }` and `{ x -> x; }` are both valid; `}` acts as implicit terminator.

- 2026-03-29: #188 Borrow-by-default captures with explicit `~` move syntax
  - AST: added `Lambda.isMove: Boolean = false`; default = borrow captures, `true` = move.
  - Parser: `~` removed from operator charset; `~{...}` parses as move lambda, `fn ~name(...)` as move inner function.
  - ClosureMemoryFnGenerator: borrow env structs have no `__dtor` field; destructors and `__free_closure` generated only for move lambdas.
  - OwnershipAnalyzer: borrow captures leave outer binding owned (multiple closures can share); move captures transfer ownership (current behavior). Escape analysis extended to cover `TypeFn` return types.
  - Codegen: borrow closures use `alloca` (stack env, no malloc/free); move closures use `malloc` with destructors. Capture field offset parameterized (0 for borrow, 1 for move).
  - Tests: updated ownership, codegen, TBAA tests for borrow-by-default; added borrow sharing and alloca codegen tests.
  - Samples/mem: escaping closures updated to `~{...}`; new `borrow-capture.mml` mem test (21/21 ASan pass).

- 2026-03-29: #188 3.5.1 Literal heap capture auto-cloning
  - AST: added `Capture` enum (`CapturedRef` / `CapturedLiteral`) to distinguish plain captures from literals that need cloning; `Lambda.captures` changed from `List[Ref]` to `List[Capture]`.
  - Ownership: `Literal` state heap captures are no longer rejected; the ownership analyzer resolves the clone function ID and upgrades them to `CapturedLiteral`.
  - Codegen: `emitCallSiteEnv` emits an ABI-lowered `__clone_T` call for `CapturedLiteral` captures before storing into the env struct.
  - Mechanical: `CaptureAnalyzer`, `ClosureMemoryFnGenerator`, `TypeChecker`, `FunctionEmitter`, and tests updated for `Capture` unwrapping.

- 2026-03-29: TypeNameResolver nominal-metadata cleanup
  - Codegen metadata: deleted `TypeNameResolver.scala`, added a standalone top-level nominal-name helper, and rewired emitter call sites so TBAA, alias-scope, and related metadata paths derive names from nominal AST types instead of reverse-mapping raw LLVM layouts.
  - Correctness: metadata name resolution now fails fast when a bare `NativePrimitive` or `NativePointer` reaches a path that requires a nominal type name, exposing real upstream type leaks instead of guessing aliases such as `Int` or `Bool`.
  - Tests: updated `TbaaEmissionTest` fixtures to use legal nominal refs/resolvables rather than illegal bare-native struct fields so the coverage matches parser-produced AST shapes.

- 2026-03-28: #188 lambda/codegen QA batch — 3.4-QA.19, 3.4-QA.20, 3.4-QA.21, 3.4-QA.26, 3.4-QA.27, 3.4-QA.28
  - Test infra / lambda semantics: added shared `TX*` lambda/test extractors in `TestExtractors.scala` and rewrote the lambda grammar, capture-analysis, typechecker, and ownership tests to assert semantic intent through shared extractors/resolved ids instead of brittle shape- and name-coupled traversal helpers.
  - Closure memory generation: `ClosureMemoryFnGenerator` now traverses tuples and selection qualifiers, builds capture type maps with folds instead of mutable builders/early returns, and tags lambdas nested under qualifiers with semantic `envStructName` metadata; added focused coverage in `ClosureMemoryFnGeneratorTests`.
  - Closure env codegen/TBAA: capture-site env setup now resolves and uses the semantic closure env `TypeStruct`, emits stores against `%struct.<env-name>` rather than ad-hoc local names, and attaches field-level TBAA metadata to destructor/capture stores; `ClosureCodegenTest` now covers semantic env naming and capture-site TBAA tagging.
  - Type-name resolution: removed the old package-level `getMmlTypeName` helper, routed emitter call sites through `TypeNameResolver`, added single-element `TypeGroup` handling and broader fallback cases there, and preserved alias identity for `TypeAlias` refs so metadata paths keep distinct MML names such as `MyInt`, `Int`, and `SizeT`; `TbaaEmissionTest` now asserts aliased struct-field metadata preserves alias identity.
  - AST/tests: `Term.withTypeAsc` now spells out the ignored term cases explicitly, and `TermTests` pins the current behavior for supported literals/invalid expressions versus constructor passthrough.
  
- 2026-03-28: #188 QA cleanup batch — 3.4-QA.15, 3.4-QA.17, 3.4-QA.22, 3.4-QA.24
  - Ownership: replaced the anonymous 5-tuple free-tracking payload with an `OwnedBinding` case class in `OwnershipAnalyzer`.
  - Closure/codegen/runtime: removed the `asInstanceOf` cast from `ClosureMemoryFnGenerator.tagLambdas`, normalized the new closure/codegen paths onto Cats `.asRight`/`.asLeft`/`.some`/`.none` style, and dropped `FORCE_INLINE` from non-hot runtime string/IO helpers.
  

- 2026-03-28: #188 3.4-QA.14 TypeFn non-closure function-value lowering
  - Semantics/codegen: bare callable refs in argument position now eta-expand into first-class function values only when undersaturated, preserving local shadowing, and call lowering now routes non-direct callable refs through the shared indirect fat-pointer path instead of assuming every `TypeFn` ref is a direct symbol.
  - Tests/samples: added semantic and codegen coverage for higher-order named function arguments, shadowed local callable parameters, and global function-valued bindings; added `mml/samples/typefn-nonclosure-values.mml` as a focused sample.
  
- 2026-03-28: #188 3.4-QA.13 nullary callable canonicalization
  - Types/parser/typechecker: callable types now use a non-empty parameter list, nullary callables are canonicalized as `Unit -> R`, and the previous special compatibility path between zero-arity and `Unit` thunks was removed.
  - Codegen/LSP/printing: `TypeFn` consumers now traverse `NonEmptyList` parameter types consistently, and user-facing formatting/docs now present nullary callable signatures as `Unit -> ...`.
  - Tests: added semantic coverage for nullary function references and nullary lambdas having `Unit -> R` types, and updated affected grammar/codegen expectations.
  

- 2026-03-28: #188 3.4-QA.30 duplicate heap capture rejection
  - Ownership: capturing the same owned heap binding into a second closure now fails during ownership analysis with a dedicated moved-capture diagnostic instead of slipping through to runtime double-free.
  - Tests/samples: added `OwnershipAnalyzerTests` coverage for duplicate-vs-borrowed helper capture flows, and updated `mml/samples/raytracer3.mml` so `render_rows` is the sole heap-state owner while sibling helpers take `buf`/row arrays by parameter.
  
- 2026-03-28: #188 Raytracer 3 sample follow-up in progress
  - Samples: added `mml/samples/raytracer3.mml` as the fully local-helper follow-up to `raytracer2`, keeping the original `raytracer2` header comments as historical context and documenting the current ownership/codegen caveats inline in the new sample.
  - Tracking: added QA.29 under the lambda/codegen follow-ups for the newly exposed P1 bug where capturing a whole struct value in local-helper closures can emit invalid LLVM IR (`raytracer3` whole-`Camera` capture case).
  

- 2026-03-28: #188 3.4-QA.11 closure free dispatch consolidation
  - Codegen: closure destructors now carry explicit `DestructorKind` metadata, closure free calls adapt fat pointers to env pointers once, and `__free_closure` now emits its null-guarded dtor dispatch in the generated function body instead of inlining it at each call site.
  - Closure memory generation: generated per-env and universal closure destructors are tagged explicitly, removing raw string-prefix coupling from codegen dispatch and closing the paired QA.23 naming-coupling debt.
  - Tests: added `ClosureCodegenTest` coverage for escaped closures freeing through generated `__free_closure` and local capturing closures freeing through their specific env destructor.
  

- 2026-03-28: #188 3.4-QA.10 mergeSubState deferred-state sync
  - Codegen: deferred lambda-body state merge now preserves the sub-run `CodeGenState` wholesale and restores only the parent output/register context, removing the manual field-sync maintenance hazard in `ExpressionCompiler`.
  - Tests: added `ClosureCodegenTest` coverage that exercises deferred lambda-body string/TBAA emission so metadata produced inside deferred bodies must survive into the final module IR.
  

- 2026-03-28: #188 3.4-QA.6 real closure env on recursive capturing lambdas
  - Codegen: recursive let-bound capturing lambdas now rebuild their self fat-pointer from the live hidden `%env` parameter inside the deferred function body instead of self-calling through `{ ptr @fn, ptr null }`.
  - Applications/codegen plumbing: recursive let-binding preallocation now reserves the non-capturing self stub only for non-capturing lambdas; capturing lambdas defer self binding until function-body codegen.
  - Tests/samples: added `ClosureCodegenTest` coverage for the null-env regression and added `mml/samples/recursive-tail-inner-captures-sibling.mml` alongside the existing non-tail sibling-capture sample.
  

- 2026-03-27: #188 3.4-QA.25 TypeFn closure env TBAA lowering
  - Codegen/TBAA: `TypeNameResolver` now gives `TypeFn` a stable MML type name (`Function`) so closure env structs with captured function values lower through TBAA/type-name resolution instead of failing.
  - Struct layout: closure env TBAA generation now accepts captured `TypeFn` fields in env structs, including the sibling-local-helper shape exercised by inner functions.
  - Tests/samples: added `TbaaEmissionTest` coverage for closure env fat-pointer fields and a focused sample for the inner-function sibling capture case.

- 2026-03-27: #188 Ptr parsing and lowering
  - Parser/native types: `@native[t=ptr]` now parses as `NativePrimitive("ptr")`; `@native[t=*...]` remains `NativePointer(...)`.
  - Stdlib/codegen: `RawPtr` now lowers as opaque `ptr`; pointer-like classification and native `noalias` checks now include opaque pointers.
  - LLVM emission: load/store helper paths now emit opaque-pointer operands as `ptr`, avoiding invalid `ptr*` IR in raw-pointer flows.
  - Tests: added grammar/codegen coverage for `t=ptr`, opaque-pointer `noalias`, and closure env/TBAA expectations for raw-pointer slots.
  -

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
