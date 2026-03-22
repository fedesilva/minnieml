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