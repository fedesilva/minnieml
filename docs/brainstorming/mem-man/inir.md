# Brainstorm Spec: Interaction Network & LFE Memory Optimization

**Date:** January 2026
**Status:** Draft / Spec
**Context:** No-GC environment. Arena-based memory management.
**Goal:** Use a topological graph view (Interaction Network) to identify systemic memory pressure, enforce Arena Passing Style (APS), and apply algebraic optimizations via effect analysis.

---

## 1. Core Concept

Current analysis looks at linear traces (`Trace A: Lambda X -> Lambda Y`).
The proposed analysis looks at `Graph G`: The aggregate set of all interactions between compute units.

**The "We Know™" Advantage:**
By analyzing the full interaction topology and dataflow graph, we can apply optimizations based on **proven constraints** (purity, lifetimes, dependencies) rather than conservative heuristics. We move from "Observing" to "Rewriting."

---

## 2. The Data Model

### Nodes (Compute Units)

- `id`: Unique Resource ID.
- **`effect_profile`:**
  - `PURE`: Deterministic, no side effects.
  - `READ_ONLY`: Reads external state, mutates nothing.
  - `MUTATOR`: Modifies local/global state.
  - `IO`: Performs Network/Disk IO.
- `churn_rate`: Rate of arena creation/destruction.

### Edges (Interactions)

- `source` / `target`.
- `payload_size`: Data volume.
- **`lifetime_constraint`:**
  - `SCOPED`: Callee strictly nested in Caller (APS candidate).
  - `DETACHED`: Callee outlives Caller (Ownership transfer).
- **`protocol_conformance`:** Flags if the interaction follows a known law/interface (e.g., `Functor`, `Monoid`, `Foldable`).

---

## 3. Analysis Heuristics

### A. The "Arena Split" Penalty

Detects when data is passed to a node that immediately allocates a _new_ Arena.

- **Goal:** Eliminate the split by passing the parent Arena pointer.

### B. Leak & Capture Detection

Verify that functions marked `PURE` do not capture mutable references from the outer scope ("Effect Leaking").

- **Check:** The graph validates that all captured environments are immutable or strictly owned.

### C. The "Zombie" Arena

Identify long-lived arenas holding sparse active data, blocking reset.

---

## 4. Compiler & Codegen Optimizations

### A. Algebraic Fusion (The "Cat Theory" Layer)

- **Mechanism:** The compiler recognizes standard instance protocols (Functors, Applicatives, Monoids) on container types.
- **Optimization:** Focus graph search on chained invocations of these protocols to perform **Deforestation** (fusion).
  - **Map Fusion:** `Vector.map(f).map(g)` $\rightarrow$ `Vector.map(x => g(f(x)))`.
    - _Memory Win:_ Eliminates the intermediate vector allocation completely.
  - **Filter/Map Fusion:** `Vector.filter(p).map(f)` $\rightarrow$ Single pass loop.
- **Result:** Reduced Arena bumps, improved instruction cache locality.

### B. APS (Arena Passing Style) Promotion

- **Analysis:** Graph confirms `Node B` is `SCOPED` to `Node A`.
- **Codegen:** Pass `A`'s arena pointer to `B` as a hidden argument. `B` allocates directly into `A`'s hot cache lines.
- **Result:** Zero-copy, zero-setup allocation.

### C. Adaptive Layout Synthesis (AoS vs. SoA)

- **Analysis:** Check how downstream nodes access data.
  - **Vectorized Access:** If iterating a single field (e.g., `sum(items.price)`), generate **Structure of Arrays (SoA)** layout. The Arena fills with `[price, price, price...]` followed by `[id, id, id...]`.
  - **Entity Access:** If accessing whole objects (e.g., `get_user(id)`), generate **Array of Structures (AoS)** layout. The Arena fills with `[id, price, id, price...]`.
- **Result:** Memory layout generated JIT to match the specific query pattern.

### D. Semantic Vectorization (LFE)

- **Analysis:** Detect lack of data dependencies between computations via effect tracking.
- **Optimization:** Fuse independent, homogenous instructions into vector intrinsics (AVX/NEON) in the Lower Front End.
- **Why:** We know the memory layout in the Arena is contiguous (bump allocated), making vectorization trivial compared to heap-scattered objects.

### E. Opportunistic Mutation

- **Analysis:** "Last-Use" tracking on the graph.
- **Optimization:** If `Data X` is never read again after passing to a pure function, allow the function to mutate `X` in place rather than copying.

---

## 5. Actionable Outputs

### A. Instruction Generation

- **`alloc_in_parent(size)`:** Context-aware APS allocation.
- **`arena_reset_root()`:** A single reset call at the root node that cleans up the entire request lifecycle.

### B. Infrastructure Tuning

- **Concurrency throttling:** Based on "Fan-Out" memory pressure analysis.
- **Right-Sizing:** Adjusting memory limits based on actual "Transitive Cost".

---

## 6. Scalability (Parallelism)

- **Localized Rewrites:** Fusion and Layout optimizations are local to the subgraph (Partitionable).
- **Sharded Analysis:** Effect tracking and purity checks can be run per-module in parallel ("Embarrassingly Parallel").

---

## 7. Open Questions

1.  **Polymorphic Layouts:** If Function A calls B (SoA preferred) AND C (AoS preferred) with the same data, do we duplicate the data or penalize one path?
2.  **Effect Boundaries:** How do we handle "Unsafe" blocks where the user explicitly breaks the effect tracking? (Likely taint the graph node as `OPAQUE`).
