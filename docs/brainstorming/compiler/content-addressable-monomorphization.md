# Brainstorming: Content-Addressable Monomorphization

**Status:** Draft  
**Goal:** Minimize binary bloat and maximize I-Cache locality in a statically monomorphized language.

---

## 1. The Problem: The "Monomorphization Tax"

MML will rely heavily on static monomorphization for both **Generics** (`List 'T`) and **Effects** (`handle Log`). 
While this will provide zero-cost abstractions, it will lead to code bloat:
*   A utility function used with 10 different types will generate 10 copies.
*   A function used with 5 different but behaviorally identical effect handlers will generate 5 copies.
*   "Newtypes" (e.g., `type UserId = Int`) will often cause redundant specializations of the same logic.

## 2. The Solution: Structural Deduplication

We will treat specialization as a pure function:  
`Specializer(TemplateAST, Context) -> ConcreteAST`

Instead of naming specialized functions based on their *types* (e.g., `map_UserId`), we will identify them by the 
**Hash of their normalized structure**.

### 2.2 Canonicalization Rules (Pre-Hashing)

To maximize cache hits, the AST must be aggressively normalized before hashing:

1.  **Alpha-Renaming:** All local bindings and parameters will be renamed to de Bruijn indices or canonical sequential names (`_v0`, `_v1`).
2.  **Structural Normalization:**
    *   Implicit returns will be made explicit.
    *   No-op nodes (like unit literals in void contexts) will be stripped.
    *   Nested blocks that don't introduce scope will be flattened.
3.  **Ordering (Safe subsets only):** While we cannot reorder effectful statements, purely declarative blocks (like adjacent `let` bindings of constants) could ostensibly be sorted, though strict lexical order is safer for initial implementation.

### 2.3 Core Principle: Semantic Identity

Monomorphization is **semantic, not nominal**.

Two specializations are identical if and only if they lower to the same behavior under the same effect and region topology.
This ties directly into MML's Topological Capabilities and effect weaving: we are deduping based on the *topology of execution*, not the names of types.

---

## 3. The "Collapse" Scenarios

### 3.1 Newtype/Alias Erasure
If `UserId` and `OrderId` are both aliases for `Int64`, their specializations will be structurally identical.
```rust
type UserId = Int
type OrderId = Int

fn increment(x: 'T) = x + 1

# These two calls will collapse to the same machine code symbol:
let a = increment (1 : UserId)
let b = increment (1 : OrderId)
```

### 3.2 Identical Effect Handlers
Two different handlers that implement an effect in the same way (e.g., two different loggers that both resolve 
to a direct call to `System.write`) will result in identical specialized ASTs.

### 3.3 Type Relevance Analysis (Phantom Collapse)
The compiler will perform a "Relevance Analysis" to decide if a type variable `'T` actually necessitates a unique compilation. `'T` is relevant **only if** it affects:

1.  **Memory Layout:** Does it change the size or alignment of data?
2.  **Calling Convention (ABI):** Does it change how arguments are passed (e.g. float vs int registers)?
3.  **Control Flow:** Is it used in a match or conditional that changes behavior based on type?
4.  **Effect Selection:** Does it determine which effect handler is resolved?

**If none of these are true**, the type variable is "Phantom" regarding code generation. The compiler will erase it (replace with `Unit` or a canonical `Void` token) before hashing.

*Example:* `List.length` for a linked list of pointers depends only on the node structure, not the payload type. `List.length (l: List Int)` and `List.length (l: List String)` will collapse to the same binary code.

---

## 4. Implementation Strategy

### 4.1 The Golden Moment
Hashing must occur at a precise point in the pipeline to be effective:
*   **AFTER:** Effect weaving, handler substitution, and trivial wrapper elimination. The AST must represent the *concrete logic* to be executed.
*   **BEFORE:** LLVM lowering, platform ABI decoration, and debug metadata generation.

This ensures that the hash captures the *pure semantic intent* of the function, unpolluted by backend artifacts or non-deterministic metadata.

### 4.2 Merkle Call-Graph & Inlining
Hashes will be computed bottom-up. A function's hash will include the hashes of the specific specialized versions 
of the functions it calls. This will form a Merkle Tree of the call graph.

**Crucially**, hashing occurs on **logical calls**, not after physical inlining.
*   Inlining strategy will be applied *after* dedup.
*   If `CallerA` and `CallerB` both call deduped `CalleeX`, they become structurally identical (`Call(HashX)`).
*   This propagates the collapse up the graph. If we inlined too early, we might create divergent bodies that hide the underlying shared structure.

### 4.3 Cache Levels
*   **AST Level:** This will provide quick deduplication during the "Weaving" phase.
*   **LLVM IR Level:** This will act as a backstop. LinkOnceODR / COMDAT provides insurance, but the primary dedup engine is the semantic AST hash.

### 4.4 Linker Integration
We will emit these specialized functions with "LinkOnceODR" (Link-Once, One-Definition-Rule) or as COMDAT groups. 
This will allow the system linker to deduplicate identical functions even across different object files/modules 
that were not visible to each other during the MML compilation phase.

---

## 5. Benefits

1.  **Instruction Cache (I-Cache) Efficiency:** The CPU will not waste cache lines on identical logic 
    stored at different addresses.
2.  **Compilation Speed:** We will avoid sending redundant ASTs to the expensive LLVM backend.
3.  **Zero-Cost Safety:** Users will be able to create as many domain-specific types (`UserId`, `Email`, `Price`) 
    as they want without worrying about binary size or performance penalties.

## 6. Constraints & Risks

*   **Debugability:** Mapping a hash-named symbol back to a human-readable source location will require 
    excellent DWARF/SourceMap support. The compiler will maintain a mapping of `Hash -> List<Aliases>`.
*   **Hash Collisions:** While statistically improbable with SHA-256, the compiler will handle them 
    gracefully or use a large enough bit-width to make the risk negligible.
