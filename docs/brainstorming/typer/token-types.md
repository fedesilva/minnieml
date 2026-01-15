# Design Note: Progressive Token Types for Zero-Cost Safety

## Abstract
This document proposes **Token Types** as a compile-time mechanism to eliminate runtime bounds checks (BCE) while maintaining memory safety.
The implementation is staged into two phases:
1.  **Size-Guarding (Phase 1):** Optimizing in-place updates (loops).
2.  **Provenance-Guarding (Phase 2):** Handling structural changes (resizes).

Crucially, this design treats **Mutation as a Capability**, paving the way for future integration with Algebraic Effects.

---

## Phase 1: Size-Guarding Tokens (The "Easy" Case)

**Goal:** Allow indices validated once to be reused efficiently in loops and read/write operations, provided the array structure (size) remains constant.

### Core Concept
A `Token 'id` represents proof that an array exists with a fixed, specific size.
* **Reads** (`get`) require a Token and a matching Index.
* **Writes** (`set`) require a Token but **do not invalidate it**, because they do not change the size.

### Type Primitives
* `Array T`: The heap object.
* `Token 'id`: A phantom witness of the array's bounds.
* `Index 'id`: An integer checked against `Token 'id`.

### Operations
```minnieml
# 1. Validation (One-time runtime cost)
# Creates a scope where 'arr' is locked as 'Token 'a
fn with_token (arr: Array T) (scope: (Token 'a -> R)) : R

# 2. Check (The Hoist)
# Returns an index tied to the token's lifetime
fn check (t: Token 'a, i: Int) : Option (Index 'a)

# 3. Access (Zero-cost)
# Compiles to raw pointer arithmetic: *(base + idx)
fn get (t: Token 'a, idx: Index 'a) : T

# 4. In-Place Mutation (Non-Invalidating)
# Compiles to raw store. Returns Unit (or same Token)
# Crucial: 'idx' remains valid after this call.
fn set (t: Token 'a, idx: Index 'a, val: T) : Unit
```

### Example: The "Safe" Sieve Loop
Because `set` does not change the Token type, the recursive loop can reuse `idx` without re-checking.

```minnieml
fn sieve_loop (t: Token 'a, idx: Index 'a) : Unit =
  if (get t idx) == 1 then
     # Safe Mutation: 't' is still 'Token 'a
     set t idx 0;
     # Recursion: 'idx' is still valid for 't'
     sieve_loop t (idx + 1) # (Assume +1 logic is handled/checked)
  else
     sieve_loop t (idx + 1)
```

---

## Phase 2: Structural Provenance (The "Full" Case)

**Goal:** Handle operations that change memory layout (resize, push, free) by forcing the invalidation of old indices at the type level.

### Core Concept
Structural mutation destroys the validity of existing indices. The system must issue a **New Token** representing the post-mutation state.

### Operations
```minnieml
# 1. Structural Mutation (Invalidating)
# 't_old is consumed. 't_new is born.
# Indices of type (Index 'old) are NOT compatible with (Token 'new)
fn resize (t: Token 'old, new_size: Int) : Token 'new

# 2. Append
fn push (t: Token 'old, val: T) : Token 'new
```

### Flow
1.  User calls `resize(t1, 500)`.
2.  Compiler returns `t2`.
3.  User tries to access `get(t2, idx1)` where `idx1` was checked against `t1`.
4.  **Compile Error:** Type mismatch `Index 'old` vs `Index 'new`.
5.  **Fix:** User MUST call `check(t2, ...)` again.

---

## Phase 3: Unification with Algebraic Effects

**Future Vision:** Mutation is an Effect. The Token is the Capability.

When MinnieML introduces Algebraic Effects, the **Token** becomes the payload of the Effect Handler.

### The Unification
1.  **Effect Definition:**
    The `Mutate` effect requires proof of ownership (the Token).
    ```minnieml
    effect Mutate {
      fn write(idx: Index 'id, val: T) : Unit
    }
    ```

2.  **The Handler (The "With" Block):**
    The `with_token` function essentially becomes an effect handler that:
    * Instantiates the `Token` (Capability).
    * Handles `perform Write`.
    * Compiles down to raw memory ops.

3.  **State Threading:**
    For Phase 2 (Resizing), the Effect system handles the **Linear State Threading**.
    * The handler maintains the *current* valid Token.
    * If a `Resize` effect is performed, the handler updates its internal Token.
    * Subsequent computations receive the new context implicitly.

### Comparison Table

| Operation | Phase 1 (Size) | Phase 2 (Structure) | Phase 3 (Effects) |
| :--- | :--- | :--- | :--- |
| **Read** | `get(t, idx)` | `get(t, idx)` | `perform Read(idx)` |
| **Update** | `set(t, idx, v)` | `set(t, idx, v)` | `perform Write(idx, v)` |
| **Resize** | *Not Supported* | `let t2 = resize(t1)` | `perform Resize(size)` |
| **Token ID** | Constant | Changes on resize | Managed by Handler |
| **Indices** | Reusable | Invalidated on resize | Invalidated on resize |