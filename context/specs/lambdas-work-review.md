# MML Compiler — Codegen & Ownership Issues

## [P1] Keep emitted env setup for let-bound capturing lambdas

**File:** `Applications.scala:64-67`

When the bound expression is a capturing lambda, `compileLambdaLiteral` emits the `malloc`/`store`/`insertvalue` sequence that builds the closure value into `res.state.output`. Replacing that with `state.output` drops those instructions while keeping the returned register, so a common pattern like `let f = { x -> x + a }; ...` later uses an undefined closure value in the body.

This reset is only safe for non-capturing lambdas.

---

## [P1] Allocate unique symbols for let-bound lambda definitions

**File:** `Applications.scala:48-57`

This preallocates the deferred function name from `param.name` only, so two local bindings with the same name in different scopes/functions both emit the same module-level symbol (for example, two separate `let loop = { ... }` bindings both become `@<module>_loop`).

LLVM will reject the duplicate definition or later calls will bind to the wrong body; local lambdas need the same anon-id based uniquing as other deferred definitions.

---

## [P2] Pass the real closure env on recursive capturing lambdas

**File:** `Applications.scala:49-55`

For a let-bound lambda that both captures outer bindings and references itself, the temporary self entry is hardcoded to `{ ptr @fnName, ptr null }`. Recursive calls compiled against that entry therefore pass a null environment to the deferred function, so the second call loses all captures and can crash as soon as the body loads one.

This only appears for self-recursive closures; the placeholder needs the eventual fat pointer, not a non-capturing stub.

---

## [P1] Size closure env allocations using LLVM struct layout

**File:** `ExpressionCompiler.scala:337-342`

`envSize` is computed as a plain sum of field sizes, which is smaller than the real `%closure_env_*` layout whenever a capture is an aggregate like `String` or when padding is required between mixed-width fields.

In those cases the subsequent `store`s write past the malloc'd buffer, so capturing anything other than the simplest pointer-sized scalars corrupts memory.

---

## [P2] Keep capture support in the tail-recursive lambda path

**File:** `ExpressionCompiler.scala:199-225`

If a let-bound lambda is both capturing and marked tail-recursive, this branch bypasses `compileCapturingLambda` entirely. The returned value is always `{ ptr @fnName, ptr null }`, and `compileTailRecursiveLambda` never loads `lambda.captures` from `%env`, so any recursive closure that reads an outer binding either sees garbage or fails immediately.

The tail-recursive codegen path needs the same env setup/load logic as regular closures.

---

## [P2] Stop freeing non-capturing function values as closures

**File:** `OwnershipAnalyzer.scala:256-259`

This now treats every `TypeFn` as owned, even plain function values and non-capturing lambdas whose fat pointer is `{ fn, null }` and has nothing to release. In particular, a consuming parameter like `fn use(~f: Int -> Int)` will insert `__free_closure(f)` on exit, and the codegen path for `__free_closure` dereferences the env pointer unconditionally, which crashes as soon as the caller passes a non-capturing lambda or top-level function value.

---

## [P1] ClosureMemoryFnGenerator uses Lambda structural equality as map key

**File:** `ClosureMemoryFnGenerator.scala:302,343`

`lambdaMap: Map[Lambda, String]` uses case class `equals`/`hashCode`. If two capturing lambdas have identical structure (same params, body, captures), they collide in the map and one gets the wrong env struct name. Use a unique identifier (source position, counter-based ID, or object identity) instead of structural equality.

---

## [P1] Non-deterministic UUIDs in ClosureMemoryFnGenerator

**File:** `ClosureMemoryFnGenerator.scala:34`

`paramId` uses `UUID.randomUUID()`, making compilation non-deterministic. Two identical compilations produce different IDs. This breaks caching, reproducible builds, and makes debugging harder. Replace with a deterministic counter-based scheme.

---

## [P2] Mutable var in compileCapturingLambda violates coding style

**File:** `ExpressionCompiler.scala:211-234`

`var siteState` is mutated inside a `foreach` loop over captures. This violates the project's functional coding style. Replace with `foldLeft`.

---

## [P2] New lambda semantic tests are still shape-coupled and name-coupled

**Files:** `CaptureAnalyzerTests.scala`, `TypeCheckerTests.scala`, `LambdaLitTests.scala`

Several new tests locate lambdas by raw parameter names and assert against direct AST shape details such as
`params.head.name == "x"` or `find(_.params.map(_.name) == paramNames)`. That violates the QA rule to prefer
resolved-id/type-aware assertions and shared extractors over brittle representation checks.

These tests will fail on harmless refactors like parameter renaming, parser reshaping, or equivalent desugaring
changes even when semantics are unchanged. Move the traversal into shared test extractors and assert on resolved
identity, inferred types, captures, and other semantic facts instead of raw names where possible.

---

## [P2] mergeSubState is fragile and must be manually kept in sync

**File:** `ExpressionCompiler.scala:288-306`

`mergeSubState` explicitly copies individual fields from `CodeGenState`. Comment says "must be updated when new metadata fields are added." If someone adds a field and forgets to update this function, data is silently dropped. Consider a pattern that doesn't require manual sync (e.g., sub-state only tracks delta, or derive merge automatically).

---

## [P2] Two codepaths for closure free could diverge

**Files:** `Applications.scala:612` (`emitClosureFreeViaEnvDtor`) and `Applications.scala:588` (`extractEnvPtrFromArgs` for `__free___closure_env_N`)

Having both a universal dtor dispatch path and a per-env direct free path is a maintenance risk. If one is updated and the other isn't, closures will leak or double-free depending on which path is taken. Document clearly or consolidate.

---

## [P2] No guard against capturing heap types before phase 3.5

**File:** `ClosureMemoryFnGenerator.scala:146-152`

`mkFreeFunction` always emits just `mml_free_raw(p)`. Capturing a `String` or struct with heap fields today will leak on free — the env malloc is freed but the captured heap values inside aren't. There's no compile-time error or warning to prevent this until phase 3.5 is implemented.

The current memory regression coverage does not catch this yet because `tests/mem/closure-capture.mml` only
captures an `Int`, so the mem suite can stay green while heap-capturing closures still leak.

---

## [P3] Nullary/Unit thunk unification may mask type errors

**File:** `TypeChecker.scala:1144-1149`

`areTypesCompatible` treats `() -> T` and `(Unit) -> T` as interchangeable. While needed for calling convention, a user writing `{ x: Unit -> ... }` shouldn't silently unify with `{ -> ... }`. Consider restricting this to internal/synthetic contexts.

---

## [P3] TypeFn globally mapped to fat pointer may affect non-closure contexts

**File:** `codegen/emitter/package.scala:749`

`getLlvmType(TypeFn)` now always returns `"{ ptr, ptr }"`. This means every function type — including higher-order function params receiving named top-level functions — gets mapped to a fat pointer. Verify this doesn't break contexts where `TypeFn` appeared before closures existed.

---

## [P3] OwnershipAnalyzer 5-tuples should be a case class

**File:** `OwnershipAnalyzer.scala` (multiple locations)

`ownedBindings` returns `List[(String, Option[Type], Option[String], Option[String], Option[String])]`. The growing tuple with `_1`..`_5` destructuring is fragile and hard to read. Extract a `case class OwnedBinding(name, tpe, id, witness, freeFn)`.

---

## [P3] New TODO:QA left in OwnershipAnalyzerTests

**File:** `OwnershipAnalyzerTests.scala:10`

The branch adds a fresh `TODO:QA` note about moving local extractors into a common module. Leaving new QA debt in
the changed code is the opposite of what the review is trying to enforce, and it will be easy to forget because the
extractors are already being expanded for the lambda workstream.

Either extract the helpers now or track the follow-up in the appropriate QA/tracking document instead of leaving a
new in-file reminder.

---

## [P3] Term.withTypeAsc silently ignores unknown term types

**File:** `ast/terms.scala` (withTypeAsc method)

The default `case _ => this` means any new `Term` subclass added in the future silently ignores type ascription instead of failing at compile time. Consider making it exhaustive or throwing on unhandled cases.

---

## [P3] FORCE_INLINE on non-hot-path runtime functions

**File:** `mml_runtime.c`

`FORCE_INLINE` was added to `readline`, `print`, `println`, `concat`, `substring`, `free_string`, `string_builder_append`, `string_builder_finalize`, `to_cstr`. These are I/O-bound or allocation-heavy — inlining them increases code size without meaningful speedup. Reserve `FORCE_INLINE` for genuinely hot small functions.

---

## [P2] ClosureMemoryFnGenerator mutable traversals and non-idiomatic control flow

**File:** `ClosureMemoryFnGenerator.scala`

`collectCapturingLambdas` (line 41) uses `var counter` + `List.newBuilder` + `Unit`-returning recursive walk.
`buildIdTypeMap` (line 74) uses `Map.newBuilder` + `Unit`-returning recursive walk.
Both `tagLambdas` (line 292) and `rewriteModule` (line 340) use early `return` statements.

These violate QA Rules 1 (functional default), 2 (mutation boundaries), and 3 (prefer folds/recursion).
Replace with `foldLeft` or recursive functions returning accumulated results, and convert `return` to `if-then-else` expressions.

---

## [P2] asInstanceOf cast in ClosureMemoryFnGenerator.tagLambdas

**File:** `ClosureMemoryFnGenerator.scala:312`

```scala
val newFn = rewriteTerm(fn).asInstanceOf[Ref | App | Lambda]
```

This bypasses the type system and can throw `ClassCastException`, violating QA Rule 8 (no exceptions).
Either add a specific overload for `Ref | App | Lambda`, or pattern-match the result.

---

## [P3] Hardcoded string name matching for closure free dispatch

**File:** `Applications.scala:312,316`

```scala
if fnRef.name == "__free_closure" then ...
val isClosureEnvFree = fnRef.name.startsWith("__free___closure_env_")
```

Closure free dispatch is keyed on raw string prefixes, coupling codegen to the naming convention chosen by
ClosureMemoryFnGenerator. If the naming changes, this code silently falls through to the wrong codepath.
Consider a flag on the AST node or a shared constant.

---

## [P3] Inconsistent Cats syntax in new codegen code

**Files:** `ExpressionCompiler.scala`, `Applications.scala`

New code uses bare `Right(...)`, `Left(...)`, `Some(...)`, `None` instead of Cats syntax extensions (`.asRight`, `.asLeft`, `.some`, `.none`). This is inconsistent with the rest of the codebase and violates QA Rule 4.

---

## [P3] sizeOfLlvmType ignores struct alignment/padding

**File:** `ExpressionCompiler.scala:207` / `codegen/emitter/package.scala:sizeOfLlvmType`

`envSize` is computed as `sizeOfLlvmType("ptr") + capLlvmTypes.map(sizeOfLlvmType).sum`. This ignores alignment padding. Currently safe because all capture fields are pointers (8-byte aligned), but will break when phase 3.5 introduces aggregate captures like `String` (`{ i64, ptr }` = 16 bytes with possible padding). Needs a TODO or real struct size computation.
