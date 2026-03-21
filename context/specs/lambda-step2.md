# Plan: #188 Phase 2 — Lambda Codegen (non-capturing)

## Context

Phase 1 added parser support for lambda literals `{ params -> body }`. Phase 2 makes them functional: lambdas can be bound to variables, passed as arguments, and called. **Only non-capturing lambdas** — body references own params + module-level bindings only. Closures are Phase 3.

## Semantic Phases — Almost Ready

All semantic phases handle expression-position lambdas correctly:
- **RefResolver** (line 297-301): adds lambda params to scope, resolves body refs
- **ExpressionRewriter** (line 516-519): rewrites lambda bodies
- **TypeChecker** (line 335-337): `checkLambdaWithContext` builds `TypeFn` for the lambda
- **OwnershipAnalyzer**: no changes needed for non-capturing lambdas

**One small TypeChecker gap**: `checkLambdaWithContext` doesn't use `expectedType` from the call site to infer unannotated lambda param types. The `expectedType` is already computed and threaded through `checkTermWithContext` — it's just not passed to `checkLambdaWithContext`.

## Changes

### 0. TypeChecker: infer lambda param types from context

**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala`

**`checkLambdaWithContext`** (line 996): Add `expectedType: Option[Type] = None` parameter.

When a param has no annotation, extract its type from the expected `TypeFn`:
```scala
val expectedFn = expectedType.flatMap(extractTypeFn)
val paramsWithSpecs = lambda.params.zipWithIndex.map { (p, i) =>
  if p.typeSpec.isDefined then p
  else if p.typeAsc.isDefined then p.copy(typeSpec = p.typeAsc)
  else p.copy(typeSpec = expectedFn.flatMap(_.paramTypes.lift(i)))
}
```

**Call site** (line 337): Pass `expectedType` which is already in scope:
```scala
checkLambdaWithContext(lambda, module, paramContext, bindingName, expectedType)
```

This enables `apply { x -> x + 1 } 42` when `apply` declares `f: Int -> Int`. ~10 lines changed.

### 1. `getLlvmType` for `TypeFn` → `"ptr"`

**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/package.scala` (line 708-711)

Currently falls through to return type. Change to return `"ptr"` (LLVM opaque pointer for function values).

Safe because existing callers (Module.scala:239, FunctionEmitter.scala:217) extract `fnType.returnType` before calling `getLlvmType`, so they never hit this case with a raw `TypeFn`.

Also add `getMmlTypeName` case for `TypeFn` → `"Function"` (check if missing).

### 2. Add `deferredDefinitions` to `CodeGenState`

**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/package.scala` (line 259)

Generic mechanism for any nested function definition (lambdas now, inner `fn` later).

Add fields:
- `deferredDefinitions: List[String] = List.empty` — accumulated nested function defs
- `nextAnonFnId: Int = 0` — counter for anonymous function names (lambdas)

Add method:
- `allocAnonFnName: (CodeGenState, String)` — returns `(updatedState, "anon_N")`

### 3. Emit deferred definitions in module output

**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/Module.scala` (line 152-153)

After emitting regular function definitions (line 153), also emit `deferredDefinitions`.

### 4. Compile lambda literal as function pointer

**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/ExpressionCompiler.scala`

Add `case lambda: Lambda =>` in `compileTerm` (before `case other =>`).

New function `compileLambdaLiteral(lambda, state, functionScope)`:
1. Allocate unique name via `state.allocLambdaName`
2. Extract `TypeFn` from `lambda.typeSpec`
3. Get LLVM return type and param types
4. Compile lambda body in a **sub-state** (reset output + registers, keep everything else)
5. Build function definition: `define internal returnType @modulename_lambda_N(params) #0 { ... }`
6. Capture sub-state's output lines → append to `deferredDefinitions`
7. Merge sub-state metadata back (string constants, TBAA, alias scopes, etc.)
8. Return `CompileResult` with `literalValue = Some("@modulename_lambda_N")`, `typeName = "Function"`

### 5. Indirect call for function-pointer variables

**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/ExpressionCompiler.scala`

In `compileApp` (line 329-336), when `fnOrLambda` is a `Ref`, check if it resolves to a function-pointer local variable (in `functionScope`) with a `TypeFn` typeSpec. If so, branch to indirect call path.

**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Applications.scala`

New function `compileIndirectCall(ref, allArgs, app, state, functionScope, compileExpr)`:
1. Compile all args
2. Look up function pointer register from `functionScope`
3. Get return type from `app.typeSpec`
4. Build LLVM function type signature from the `TypeFn`
5. Emit: `%result = call returnType %fnPtrReg(paramType1 %arg1, ...)`

**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/package.scala`

New helper `emitIndirectCall` — like `emitCall` but uses `%reg` instead of `@name`.

### 6. Detection: is this an indirect call?

In `compileApp`, after `collectArgsAndFunction` yields a `Ref`:
- Check `functionScope.get(ref.name)` — if present, it's a local binding
- Check `ref.typeSpec` — if it's a `TypeFn`, it's a function value being called
- If both: indirect call path
- Otherwise: existing direct call path

Top-level `let f = { x: Int -> x + 1 }` is already compiled as a module-level function by `emitBinding` (Module.scala:214-217), so direct calls to those work unchanged.

## Files Modified

| File | Change |
|------|--------|
| `semantic/TypeChecker.scala` | `checkLambdaWithContext` gets `expectedType` param, infer lambda param types from context |
| `codegen/emitter/package.scala` | `getLlvmType(TypeFn)→"ptr"`, `getMmlTypeName(TypeFn)`, `deferredDefinitions`, `nextLambdaId`, `allocLambdaName`, `emitIndirectCall` |
| `codegen/emitter/ExpressionCompiler.scala` | `case lambda: Lambda` in `compileTerm`, indirect call detection in `compileApp` |
| `codegen/emitter/expression/Applications.scala` | `compileIndirectCall` function |
| `codegen/emitter/Module.scala` | Emit `deferredDefinitions` in output assembly |

## Test Programs

Minimal test that exercises all paths:
```mml
fn apply(f: Int -> Int, x: Int): Int = f x;

fn main(): Int =
  let result = apply { x -> x + 1 } 41;
  result
;
```

Tests: lambda literal compilation (step 4), param type inference from context (step 0), passing lambda as argument, indirect call inside `apply` (step 5), TypeFn as param type (step 1).

## Verification

1. `sbtn "run run mml/samples/hello.mml"` — existing programs still compile
2. `sbtn "run run mml/samples/quicksort.mml"` — existing programs still compile
3. `sbtn "run run mml/samples/astar2.mml"` — existing programs still compile
4. Create test `.mml` file with lambda usage, compile and run via `sbtn`
5. `sbtn "test"` — all tests pass
6. `make -C benchmark clean && make -C benchmark mml` — benchmarks compile

## Implementation Order

1. `TypeChecker.scala`: add `expectedType` to `checkLambdaWithContext`, infer param types
2. `package.scala`: `getLlvmType(TypeFn)`, `getMmlTypeName(TypeFn)`, new state fields, `emitIndirectCall`
3. `Module.scala`: emit deferred definitions
4. `ExpressionCompiler.scala`: `compileLambdaLiteral` + `case lambda: Lambda`
5. `Applications.scala`: `compileIndirectCall`
6. `ExpressionCompiler.scala`: indirect call detection in `compileApp`
7. Test with sample program
8. Full test suite + benchmarks
