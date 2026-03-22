# Plan: #188 Phase 3.4–3.5 — Closure Ownership Integration

## Context

Steps 3.1–3.3 are complete: CaptureAnalyzer populates `Lambda.captures`, the fat pointer
`{ ptr fn, ptr env }` calling convention works, and env struct codegen allocates/populates
the env at closure creation. The env is never freed — confirmed by the `closure-capture`
memory test (100k leaks at `-O 0`).

## Design Principle

**Closure envs are first-class structs in the ownership model.** No special-casing — they get
the same treatment as any user-defined struct with owned fields.

## Step 3.4 — Ownership integration (value-type captures)

### 3.4.1 Generated env type and drop logic

Each capturing lambda gets a compiler-generated env struct. This struct should be treated
like any other struct by the ownership system:

- The env type is already emitted as `%closure_env_<name> = type { ... }`
- Generate a drop function: `__free_closure_env_<name>` that frees the env pointer
- For value-type-only captures (Int, Float, Bool): drop is just `free(env_ptr)`
- The OwnershipAnalyzer must recognize capturing lambdas as allocating expressions

### 3.4.2 Closure values own their env

The runtime shape `{ code_ptr, env_ptr }` means: **the closure value owns `env_ptr`
when non-null.**

Dropping a closure means:
1. Run env drop glue (free captured fields if heap-typed, then free env storage)
2. For value-type captures: just `free(env_ptr)`

### 3.4.3 isHeapType for closures

`isHeapType` should return `true` for any closure that captures (it owns a malloc'd env).
Non-capturing lambdas (`{ ptr @fn, ptr null }`) are NOT heap types — no allocation.

This means the OwnershipAnalyzer's existing `wrapWithFrees` logic will insert a free for
the closure binding when it goes out of scope — if we give it a way to know the free
function name.

### 3.4.4 Approach options

**Option A — Static knowledge (preferred):** The compiler knows at codegen time which
closures capture and what their env type is. No need for a stored destructor pointer.
The free function is derived from the env type name. MML is monomorphic enough at codegen
time that this works.

**Option B — Stored destructor pointer:** Add a third field to the closure
`{ code_ptr, env_ptr, drop_ptr }`. The drop pointer is called when the closure is freed.
This is needed only if closures become polymorphic (passed through generic interfaces where
the concrete env type is unknown). Defer unless needed.

### 3.4.5 Implementation sketch

- **OwnershipAnalyzer**: When analyzing a standalone lambda with `captures.nonEmpty`:
  - Mark the lambda expression as allocating (similar to struct constructors)
  - The let-binding receiving it should track the closure as `Owned`
  - On scope exit, emit a free call for the env

- **Codegen**: The free call should call `free(env_ptr)` where `env_ptr` is extracted
  from the fat pointer via `extractvalue { ptr, ptr } %closure, 1`.

- **Question**: How does the OwnershipAnalyzer emit the free? Options:
  - Generate a `__free_closure_env_<name>` function in MemoryFunctionGenerator (or a new
    phase) — but MemoryFunctionGenerator runs before CaptureAnalyzer
  - Generate the free inline at the codegen level when freeing a closure binding
  - Have the OwnershipAnalyzer insert a synthetic `App(Ref("free"), env_extract)` — but
    `free` is not an MML function

## Step 3.5 — Heap-type captures (String, structs)

### 3.5.1 Capture mode

For each captured binding, determine capture mode:

- **By value (copy)**: Int, Float, Bool — trivial copy into env. No ownership implications.
- **By clone**: String, user structs — clone the value into the env. The closure owns the
  clone, the outer scope retains its original. This is the safe default.
- **By move**: Transfer ownership from outer scope to env. The outer scope can no longer use
  the binding after the lambda literal. More efficient but restrictive — save for later.
- **By borrow**: The closure borrows the value. Only safe for non-escaping closures.
  Requires escape analysis. Defer.

For step 3.5, use **clone** for heap-typed captures.

### 3.5.2 Env drop with heap fields

When the env contains heap-typed captures (e.g. String), dropping the env must:
1. Drop each heap-typed field (call `__free_String`, `__free_StructName`, etc.)
2. Free the env struct itself

This is exactly what `MemoryFunctionGenerator` does for user structs. The env struct's
drop function follows the same pattern.

### 3.5.3 Clone at capture site

At the point where the lambda literal is compiled (call site), for each heap-typed capture:
- Emit a clone call: `call %struct.String @__clone_String(%struct.String %val)`
- Store the cloned value into the env struct

The outer scope's ownership of the original is unaffected.

### 3.5.4 OwnershipAnalyzer changes for heap captures

- When a lambda captures a heap binding by clone, the clone is implicit at the lambda
  literal expression — no explicit clone in the AST
- The OwnershipAnalyzer should recognize that capturing a heap binding = cloning it
- The closure's env drop must chain field-level frees

## Non-escaping vs Escaping Closures

### Non-escaping

A closure that is immediately applied and not stored/returned. Examples:
- `apply { x -> x + a } 42` — lambda created and consumed in same expression
- `let f = { x -> x + a }; f 42` — used locally, doesn't escape the scope

Opportunities:
- Stack-allocate the env (or optimize it away entirely)
- LLVM already does this at higher opt levels (as seen: constant-folded the entire loop)

### Escaping

A closure returned from a function, stored in a data structure, or passed to unknown code.
The closure value must own the env, and dropping the closure drops the env.

**For now**: treat all closures as escaping. The ownership model handles it correctly.
Escape analysis and stack allocation are optimizations for later.

## Signal from the nested-closures sample

The `nested-closures.mml` test exercises:
- Nested capture (inner lambda captures from outer lambda scope)
- Outer lambda with empty env
- Inner lambda with non-empty env
- Closure passed as value through `apply`
- Indirection through function pointer calls

LLVM reduced the entire computation to a constant at `-O 3`. This confirms the fat pointer
ABI is not obstructive to optimization — the strongest positive signal so far.
