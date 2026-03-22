# Plan: #188 Phase 3.4–3.5 — Closure Ownership Integration

## Context

Steps 3.1–3.3 are done. `CaptureAnalyzer` populates `Lambda.captures`, and the fat pointer 
`{ ptr fn, ptr env }` works. `env` struct codegen allocates and fills the environment when 
creating the closure. Right now, the environment is never freed—the `closure-capture` test 
shows 100k leaks at `-O 0`.

## Rules

Capturing closures are **ownership sinks**. They work exactly like structs:

* **Move on Capture**: Captured `Owned` variables are `Moved` at the lambda literal site.
* **No Borrowed Captures**: You cannot capture `Borrowed` variables. Clone them first if you 
  need to.
* **isHeapType**: `TypeFn` is a heap type if and only if `Lambda.captures.nonEmpty`.

## Design Principle

**Closure environments are first-class structs in the ownership model.** The `Lambda` node has 
enough info for `OwnershipAnalyzer` to track allocations and moves. We don't need new AST nodes.

## Step 3.4 — Ownership integration (value-type captures)

### 3.4.1 New Phase: ClosureMemoryGenerator

A new phase, `ClosureMemoryGenerator`, will run after `CaptureAnalyzer` (Phase 10) and before 
`OwnershipAnalyzer` (Phase 12).

- **Task**: Find `Lambda` nodes where `captures.nonEmpty`.
- **Action**: Generate a top-level `__free_closure_env_<name>` function for each unique environment.
- **Optimization**: Closures with the same capture types can share one free function.

### 3.4.2 OwnershipAnalyzer Updates

- **Allocation Detection**: `termAllocates` and `bndAllocates` must treat capturing `Lambda` 
  literals as allocating expressions.
- **Move Tracking**: Mark source bindings as `Moved` for every capture.
- **Free Insertion**: Existing `wrapWithFrees` logic will insert calls to the new 
  `__free_closure_env_<name>` at scope end.

### 3.4.3 isHeapType for closures

`TypeUtils.isHeapType` is `true` for `TypeFn` if the lambda captures. Non-capturing lambdas 
stay value types (plain pointers).


## Step 3.5 — Heap-type captures (String, structs)

### 3.5.1 Capture mode: Move

The **Move on Capture** rule means:
- Ownership moves from the outer scope to the closure's environment.
- You can't use the binding in the outer scope after it's captured.
- This avoids extra clones and fits MML's affine type system.

### 3.5.2 Env drop with heap fields

If the environment captures heap types (like `String`), the generated `__free_closure_env_<name>` 
must:
1. Drop each heap-typed field (`__free_String`, `__free_StructName`, etc.).
2. Free the environment pointer itself.

`ClosureMemoryGenerator` handles this logic.


### 3.5.3 OwnershipAnalyzer changes for heap captures

- When a lambda captures a heap binding, `OwnershipAnalyzer` marks it as `Moved`.
- `ClosureMemoryGenerator` ensures the environment drop chains all field-level frees.


## Non-escaping vs Escaping Closures

### Non-escaping

These closures are applied immediately and never stored or returned.
- `apply { x -> x + a } 42` — created and consumed in one go.
- `let f = { x -> x + a }; f 42` — used locally without escaping the scope.

Options for later:
- Stack-allocate the environment or optimize it away.
- LLVM already does this at `-O 3` (it constant-folded our entire test loop).

### Escaping

These are returned from functions, stored in structs, or passed to unknown code. The closure 
must own its environment, and dropping the closure must free that environment.

**For now**: we treat all closures as escaping. The ownership model already handles this correctly. 
We'll leave escape analysis and stack allocation for later.

## Signal from the nested-closures sample

The `nested-closures.mml` test covers:
- Nested captures (inner lambda captures from outer scope).
- Outer lambdas with empty environments.
- Passing closures as values through `apply`.
- Calling through function pointers.

LLVM reduced the whole thing to a constant at `-O 3`. The fat pointer ABI doesn't get in the 
way of optimizations—this is a great sign.
