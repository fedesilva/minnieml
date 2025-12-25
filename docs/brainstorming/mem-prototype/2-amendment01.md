# Amendment 01: Hybrid Ownership & Borrow-by-Default

**Parent Document:** Simple Memory Management Prototype
**Topic:** Refining the ownership model to improve ergonomics and safety.

## 1. Ownership Semantics: Borrow-by-Default

To align MML with its goal of being a high-level, ergonomic language, we invert the default ownership rule proposed in the original document.

### The Shift
* **Old Proposal:** Move-by-Default. Passing a variable transferred ownership; you had to explicitly lend (`&`) to keep it.
* **New Semantics:** **Borrow-by-Default.** Passing a variable to a function lends a reference. The caller retains ownership. This matches the flow of high-level logic where variables are read multiple times before being discarded or consumed.

### The Rules
1.  **Default (Borrow):** `foo(x)` passes a pointer. `x` remains valid in the caller's scope.
2.  **Explicit Move (`~`):** To transfer ownership (e.g., to a collection or consuming function), use the **Move Sigil** `~`.
    * Syntax: `consume(~x)`
    * Effect: `x` is marked **Moved** and cannot be used again in the current scope.
    * Constraint: The callee must accept an owned value (e.g., `fn consume(~s: String)`).

**Example:**
```mml
let s = readline()
println(s)       -- Default: Borrow. 's' remains valid.
vector_push(~s)  -- Explicit Move. 's' is now invalid.
println(s)       -- Compile Error: Use after move.
```

## 2. Native Annotations (The Contract)

We **keep** the `@native` attributes to define the behavior of foreign functions. The compiler uses these to seed the ownership analysis.

| Attribute | Meaning | Compiler Action |
| :--- | :--- | :--- |
| `[mem=alloc]` | Function allocates new memory. | Mark the return value as **Owned** in the current scope. |
| `[mem=view]` | Function returns a pointer to existing/static memory. | Mark the return value as **Borrowed** (do not insert free). |
| *None* | Default assumption for "Pure" natives. | Treated as **Borrowed/Pure** (no tracking). |

**Example:**
```mml
fn readline(): String = @native[mem=alloc]
fn get_env(key: String): String = @native[mem=view]
```

## 3. Runtime Capacity (The Safety Net)

We **add** a `cap` field to the runtime struct layout for Resource Types (`String`, `Buffer`). Its role is strictly to handle **Control Flow Variance** (merging Owned and Static paths) safely.

**The Logic:**
The compiler treats literals and allocations uniformly as "Owned" when branches merge. The runtime uses `cap` to decide if `free()` is valid.

* **Heap Allocation:** `cap > 0`. `free()` proceeds.
* **Literal (Static):** `cap = -1`. `free()` is a no-op.

**Refined Role:**
This allows the compiler to be **conservative** (over-approximate ownership) without requiring complex static analysis to distinguish literals from heap pointers in every possible branch.

## 4. Revised Analysis Logic

The analysis phase uses the annotations to decide what enters the "Owned Set" and the Sigil to decide what leaves it.

1.  **Entry (Creation):**
    * `App(Ref(fn))` where `fn` is `[mem=alloc]` -> Add result to **Owned Set**.
    * `LiteralString` -> Add to **Owned Set** (safe due to cap check).

2.  **Propagation (Usage):**
    * `App(..., Ref(s))` (Borrow) -> `s` remains in **Owned Set**.
    * `App(..., ~Ref(s))` (Move) -> `s` removed from **Owned Set** (marked Moved).

3.  **Exit (Cleanup):**
    * At scope end, generate `free()` wrappers for all variables remaining in the **Owned Set**.