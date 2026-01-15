# Brainstorming Spec: MML Effect System & Compile-Time Injection

**Status:** Draft / Brainstorm
**Goal:** Direct-Style Functional Systems Programming
**Core Mechanism:** Static Monomorphization via Whole-Program Optimization

---

## 1. The Core Philosophy

MML treats **Algebraic Effects** not as a runtime dispatch mechanism, but as a **Compile-Time Dependency Injection** system.

* **Syntax:** Direct style (like OCaml). No monad transformers, no callback hell.
* **Semantics:** Effects are dynamically scoped capabilities (like Reader Monad / Type Classes).
* **Compilation:** Effects are resolved statically. The compiler generates specialized copies of functions for each specific handler context.
* **Performance:** Zero runtime overhead for dispatch. `Async` compiles to zero-allocation state machines via `llvm.coro`.

---

## 2. Semantics: "Effects as Imports"

The mental model is **Lexical Shadowing**. A handler is essentially a local import that shadows a global one.

### 2.1 Resolution Order
When a function calls an effectful operation (e.g., `Log.write`), the compiler resolves the implementation by searching scopes strictly upwards:

1.  **Local Scope:** Is there a `handle Log` or `import Log` inside the current function/block?
2.  **Module Scope:** Is there a top-level `import Log` in the file?
3.  **Global/Package Scope:** Is there a default implementation provided by the package?

### 2.2 The "Shadowing" Pattern (Testing)
This allows dependency injection without boilerplate.

```rust
// Production Code (Uses Global Import)
import System.Log // Provides default 'Log' handler

fn save_user(u) =
    Log.info("Saving...") // Binds to System.Log
    db_insert(u)
;

// Test Code (Uses Local Shadowing)
fn test_save() =
    // LOCALLY handle the effect.
    // This shadows the global 'System.Log' for the scope of this block.
    handle Log with MockLogger in {
        save_user({name: "Test"}) 
        // The compiler generates a SPECIALIZED version of 'save_user' 
        // that hardcodes calls to 'MockLogger'.
    }
;
```

---

## 3. The Compilation Pipeline: Static Monomorphization

We assume a **Whole-Program Optimization** context (e.g., loading serialized ASTs from library `.mlp` files).

### 3.1 Linkage Models: The Two Artifact Types
We distinguish between distributing code for MML consumers versus Native consumers.

* **MML Packages (`target="pkg"`) -> Open Polymorphism**
    * **Artifact:** `.mlp` (Zip containing Parquet serialized ASTs + Metadata).
    * **Semantics:** **Deferred Compilation.**
    * **Effect Handling:** The ASTs retain their abstract effect nodes (e.g., `Call(Effect.Log.write)`).
    * **Resolution:** The *consumer* performs the Static Monomorphization. This preserves full Effect Polymorphism (the library author defines the effect, the library user provides the handler).

* **Native Libraries (`target="lib"`) -> Closed World**
    * **Artifact:** `.so` / `.dll` / `.a` (Machine Code).
    * **Semantics:** **Final Compilation.**
    * **Effect Handling:** The compiler must resolve all effects before emitting machine code.
    * **Resolution:** The library author *must* provide default handlers or internal `handle` blocks. The C ABI cannot express "requires capability <Log>".

### 3.2 Phase 1: Deep Analysis & Coloring
* **Inference:** Walk the AST. If `f()` calls `Log.write()`, infer `f`'s signature as `() -> <Log> Unit`.
* **Coloring:** Mark functions as:
    * **Pure:** Standard C ABI.
    * **Effectful:** Needs context injection.
    * **Resumable:** Uses `Async`/`Yield`. Needs State Machine transform.

### 3.3 Phase 2: The "Weaving" (Reachability)
The compiler behaves like a Linker. It starts at `main` (or `public` exports) and traverses the call graph.

* **State:** It carries a `Context` map: `{ EffectID -> ConcreteImplementation }`.
* **Traversal:**
    * At `main`, Context is `{}` (or System Defaults).
    * At `handle Log with Mock`, push `{ Log -> Mock }` to Context.
    * At call `save_user()`:
        * Check Cache: Do we have `save_user` compiled for `{ Log -> Mock }`?
        * **No?** -> **Clone & Rewrite.**
* **The Rewrite:**
    * Load the AST of `save_user` from the library.
    * Replace generic node `Call(Log.write)` with static node `Call(MockLogger.write)`.
    * Emit this new function as `@save_user_specialized_01`.

**Result:** The final LLVM IR contains only direct function calls. No vtables. No hidden arguments.

---

## 4. Async & Control Flow (`llvm.coro`)

We strictly avoid global CPS. We use **Delimited Continuations** implemented via LLVM Coroutines.

### 4.1 The Mapping
* **Pure/Standard Effects:** Compile to normal functions.
* **Resumable Effects (`Async`, `Gen`):** Compile to `llvm.coro` intrinsics.

### 4.2 LLVM IR Strategy
Instead of manually building state machines (like Rust's MIR), we emit linear IR with markers.

**MML Source:**
```rust
fn fetch() = 
    Log.msg("Start")
    Async.wait(100) // Suspend point
    Log.msg("End")
;
```

**Generated LLVM (Concept):**
```llvm
define i8* @fetch_specialized() {
  ; Setup
  %id = call token @llvm.coro.id(...)
  %hdl = call i8* @llvm.coro.begin(...)

  ; Body
  call void @Log_msg("Start")

  ; Suspend Point
  %suspend = call i8 @llvm.coro.suspend(...)
  switch i8 %suspend, label %cleanup [ i8 0, label %resume ]

resume:
  call void @Log_msg("End")
  br label %cleanup

cleanup:
  call i1 @llvm.coro.end(...)
  ret i8* %hdl
}
```

### 4.3 Zero-Allocation Optimization (`CoroElide`)
Because we Monomorphize, the compiler sees the creation (`fetch()`) and consumption (`handle Async`) in the same IR module.
* LLVM's **`CoroElide`** pass kicks in.
* It determines `fetch`'s state frame size (e.g., 32 bytes).
* It replaces the heap allocation with an `alloca` in the caller's stack frame.
* **Result:** Async code runs with stack-like performance and zero GC pressure.

### 4.4 Safety Constraint: Interaction with Regions
When `Async` interacts with `Mem` (Regions), we must prevent temporal memory bugs (use-after-free).

* **The Risk:** A coroutine captures a reference to data in a Region, suspends, and the Region is destroyed before the coroutine resumes.
* **The Rule:** A resumable function may only capture regions whose lifetime **strictly dominates** the coroutine frame lifetime.
* **Enforcement:** The compiler verifies that the `handle Async` block (which drives the coroutine) is strictly nested *within* the `handle Mem` scope of any captured references.

---

## 5. The "Baked-In" Fundamental Effects

These are intrinsics that look like effects but map to specific compiler logic.

| Effect | Semantics | Compiler Implementation |
| :--- | :--- | :--- |
| **`FFI`** | Foreign Function Interface | Validates `extern` usage. Emits `call` to external symbols. Can be mocked by handling `FFI` with a local implementation. |
| **`Mem`** | Memory Allocation | Default: Calls `malloc`. **Region Handler**: Lowers to pointer-bump instructions relative to a Region struct. |
| **`Mut`** | Mutation Permission | **Type-Check only.** No runtime codegen. Used to enforce "Pure Functional" safety rules on mutable pointers. |
| **`Async`** | Suspension | Triggers the `llvm.coro` transformation described above. |

---

## 6. Effect-Driven LLVM Optimization

We leverage the `Mut` effect and Region Inference to generate rich LLVM metadata, enabling optimizations that are impossible for C/C++. We call these **Topological Capabilities**.

### 6.1 "Topological" Alias Analysis (TBAA)
Since MML guarantees that distinct Regions are disjoint in memory, we map Regions to LLVM TBAA (Type-Based Alias Analysis) metadata.

* **Concept:** A write to `Region A` is tagged `!tbaa !A`. A read from `Region B` is tagged `!tbaa !B`.
* **Optimization:** LLVM proves `store A` cannot affect `load B`. It can reorder instructions, vectorize loops (SIMD), and hoist loads without fear of aliasing.

### 6.2 Function Attributes (`readonly`, `noalias`)
Static Monomorphization allows us to apply attributes to *specialized* functions based on their specific effect context.

* **`readnone` (Pure):** If a specialized function has NO effects and NO `Mut` capability.
    * *Result:* LLVM optimizes it away entirely if the result is unused, or constant-folds it.
* **`readonly` (Immutable):** If a function has `Read` capability but NO `Mut` capability.
    * *Result:* LLVM assumes memory is immutable across the call.
* **`noalias` (Disjoint):** If arguments belong to distinct Regions.
    * *Result:* Enables aggressive memcpy/memmove optimizations and auto-vectorization.

### 6.3 The "Virtuous Cycle"
The more specific the user (or inference) is about Effects and Regions, the faster the code runs. Safety violations become compile errors; Safety *proofs* become runtime speed.

---

## 7. Constraints: The "Closed World"

To make this rigorous, we enforce boundaries.

### 7.1 Executables (`target="bin"`)
* Entry point is `main`.
* All effects must be resolved by the time `main` exits.
* The compiler guarantees a "Complete World" for optimization.

### 7.2 Libraries (`target="lib"`) (Native Exports)
* Entry points are `public` / `extern` functions.
* **Rule:** A `public` function exposed to C **MUST** resolve all its effects internally.
    * It cannot bubble an effect up to C (C has no handler stack).
    * It must wrap logic in `handle` blocks or use imports.
* This ensures the generated `.so`/`.dll` has a standard C ABI with no hidden runtime requirements.

---

## 8. Summary of "The MML Way"

1.  **Write like OCaml:** High-level, declarative, inferred.
2.  **Link like C++:** Templates/Monomorphization resolve abstract interfaces to concrete symbols.
3.  **Run like Rust:** Async becomes stack-allocated state machines; effects become direct calls.

## Feedback and Notes collection

### The Monomorphization Trade-off
   * Pros: This achieves the "Holy Grail" of zero-cost effects. No vtables, no dictionary passing at runtime, and perfect inlining
     opportunities for LLVM.
   * Cons: Code Bloat. If a common utility (like List.map) is used with 50 different effect contexts, you generate 50 distinct functions.
     This puts massive pressure on the instruction cache (I-Cache) and compilation times.
   * Mitigation: The compiler might need a "deduplication" pass for instantiations that end up identical in IR (e.g., two different Reader
     effects that both just return a constant 0)
