# Plan: #188 Phase 3 — Capturing Lambdas (Closures)

## Context

Non-capturing lambdas work (Phase 1 parser, Phase 2 codegen). Phase 3 adds **capture analysis**,
**closure representation**, and **ownership integration** so lambdas can reference bindings from
enclosing scopes.

Target programs:
```mml
fn makeLambda(): String =
  let a = 1;
  { int_to_str (2 * a) }       // captures Int
;

fn makeGreeter(name: String): String -> String =
  { concat "Hello " name }     // captures String (heap)
;

let add = { a -> { b -> a + b } };  // nested capture
```

## Design: Fat Pointer Representation

Every function value becomes `{ ptr fn, ptr env }` in LLVM IR.

- **Non-capturing**: `{ @fn, null }` — no heap allocation.
- **Capturing**: `{ @fn, %env_ptr }` — env is a malloc'd struct holding captured values.
- **Indirect call**: extract fn + env, pass env as hidden last argument.
- **All deferred lambda functions** gain trailing `ptr %env` parameter (ignored when null).

## Implementation Steps

### Step 3.0: Update tracking document

Update `context/tracking.md` with Phase 3 subtasks. Link this spec.

### Step 3.1: CaptureAnalyzer — new semantic phase

**Goal**: Populate `Lambda.captures: List[Ref]` (currently always `Nil`).

**New file**: `semantic/CaptureAnalyzer.scala`
**Modified**: `compiler/SemanticStage.scala` — insert after `TypeChecker`, before `ResolvablesIndexer`

**Algorithm**:
1. Walk each top-level `Bnd` expression tree, tracking a scope of "local names" (let-binding
   params and function params encountered along the way).
2. When entering a **standalone lambda** (not `App(Lambda, arg)` which is a let-desugaring),
   record which `Ref`s in its body refer to names from the outer local scope (not the lambda's
   own params, not module-level globals).
3. Set `lambda.captures` to the deduplicated `Ref` list.
4. For **nested lambdas**: inner lambda captures propagate outward — if inner captures `a` from
   grandparent scope, the outer lambda must also capture `a` to make it available.

**How to distinguish let-lambdas from real lambdas**: `App(Lambda(...), arg)` is a let-desugaring.
A `Lambda` appearing as a standalone term (not as `App.fn`) is a real lambda that can capture.

**Tests**: Unit tests in `CaptureAnalyzerTests.scala` verifying captures list for:
- Simple capture of a let-binding
- No capture of globals/module-level fns
- No false capture of lambda's own params
- Nested captures propagate outward

### Step 3.2: Fat pointer calling convention

**Goal**: Change `TypeFn` LLVM representation from `ptr` to `{ ptr, ptr }`. All existing lambda
tests must still pass (non-capturing lambdas use `{ @fn, null }`).

**Files**:
- `codegen/emitter/package.scala`:
  - `getLlvmType(TypeFn)` returns `"{ ptr, ptr }"` instead of `"ptr"`
  - `emitIndirectCall` updated to handle fat pointer extraction (or new helper)
- `codegen/emitter/ExpressionCompiler.scala`:
  - `compileRegularLambdaLiteral`: return `{ ptr @fn, ptr null }` literal
  - `compileTailRecLambdaLiteral`: same
  - All deferred lambda function signatures get trailing `ptr %env` param
- `codegen/emitter/expression/Applications.scala`:
  - `compileIndirectCall`: extract fn+env from `{ ptr, ptr }` value, pass env as last arg
  - `compileLambdaApp` (let-desugaring): when the arg is a lambda value, handle fat pointer
    in scope entry

**Existing helpers to reuse**:
- `emitExtractValue` (package.scala:116) — for extracting fn/env from fat pointer
- `emitInsertValue` (package.scala:120) — for building fat pointer
- `withFunctionDeclaration` (package.scala:591) — for declaring malloc/free

**Key invariant**: Non-capturing lambdas produce `{ ptr @fnName, ptr null }`. The hidden `ptr %env`
param is present in the function signature but unused in the body.

### Step 3.3: Env struct allocation — value-type captures

**Goal**: When `lambda.captures.nonEmpty` and all captures are value types, generate:
1. Env struct type definition (e.g. `%closure_env_0 = type { i64, float }`)
2. `malloc` the env, store captured values via GEP+store
3. Build fat pointer `{ ptr @fn, ptr %env }`
4. In the deferred function body: load captures from env via GEP+load into local scope

**Files**:
- `codegen/emitter/ExpressionCompiler.scala`: `compileRegularLambdaLiteral` — when captures
  present, emit env allocation and population
- `codegen/emitter/Module.scala`: emit closure env type definitions
- `codegen/emitter/package.scala`: add `malloc`/`free` to function declarations

**Env struct naming**: `%closure_env_N` where N comes from `CodeGenState.nextAnonFnId` (already
tracks anonymous function IDs).

**Inside deferred function**: The last param is `ptr %env`. Cast and load each field:
```llvm
%cap_ptr = getelementptr %closure_env_0, ptr %env, i32 0, i32 0
%cap_val = load i64, ptr %cap_ptr
```
These are added to `functionScope` so the lambda body can reference them.

### Steps 3.4–3.5: Ownership and heap captures

Detailed spec: `context/specs/lambda-step3-ownership.md`

## Critical Files

| File | Role |
|------|------|
| `ast/terms.scala:87-125` | Lambda AST with `captures` field |
| `semantic/CaptureAnalyzer.scala` | **NEW** — capture analysis phase |
| `compiler/SemanticStage.scala` | Wire CaptureAnalyzer into pipeline |
| `codegen/emitter/package.scala` | `getLlvmType(TypeFn)`, emit helpers |
| `codegen/emitter/ExpressionCompiler.scala` | Lambda codegen with env struct |
| `codegen/emitter/expression/Applications.scala` | Indirect call with fat pointer |
| `codegen/emitter/Module.scala` | Env type definitions, declarations |
| `semantic/OwnershipAnalyzer.scala` | Closure ownership tracking |
| `ast/TypeUtils.scala` | `isHeapType` for closures |

(All paths relative to `modules/mmlc-lib/src/main/scala/mml/mmlclib/`)

## Verification

After each step:
1. `sbtn "test"` — full test suite
2. `sbtn "scalafmtAll"` — formatting
3. Sanity: `sbtn "run run mml/samples/hello.mml"`, quicksort, astar2
4. Lambda samples: `sbtn "run run mml/samples/lambda_test.mml"`, lambda_test_readline

After step 3.3+:
5. `sbtn "run run mml/samples/captures.mml"` — must print captured value
6. ASan: `mmlc run -s mml/samples/captures.mml`

After step 3.5:
7. String capture sample with ASan + leaks
8. `./tests/mem/run.sh all` — full memory test harness
