# Ownership & Lifetime: Next Dragons to Kill (Agent Test Plan)

## Context

Current tests mostly exercise **leaf values**:
- Values are returned.
- Freed locally.
- Minimal aliasing.

Next phase must intentionally break things via **aliasing, copies, aggregates, and control flow**.

This spec defines the concrete tests an agent must generate, run, and reason about.

---

## 1. Copy / Duplicate Ownership Bugs

### Problem
If `String` is passed or returned *by value*, it is easy to create **two live copies pointing to the same owned allocation**, leading to double-free.

### Required Tests
- Store a heap-allocated `String` in two distinct bindings.
- Free both bindings.

### Expected Outcome
One of:
- Compiler **rejects** the program.
- Compiler **inserts a clone** (deep copy) so each free is valid.
- Compiler enforces **move semantics** and invalidates one binding.

---

## 2. Move Semantics Across Bindings

### Problem
Ownership transfer must invalidate the source, or enforce safety.

### Required Tests

Case A (double free via move):

    let b = a;
    free(a);
    free(b);

Expected: rejected or made safe.

Case B (rebind source):

    let b = a;
    a = something_else;
    free(b);

Expected: `b` remains valid; the old value of `a` is invalidated.

### Agent Task
Verify that:
- Source bindings are invalidated on move, or
- Copies are made explicit and safe.

---

## 3. Records / Arrays Containing Owned Fields

### Problem
Aggregates multiply ownership complexity.

### Test Type

    type Foo = { s: String }

### Required Checks
- `__free_Foo` must call `__free_String` on field `s`.
- Copying `Foo` must **not** lead to double-free of `s`.

### Expected Outcome
One of:
- Deep copy of owned fields.
- Move-only aggregates.
- Compiler rejection of implicit copies.

---

## 4. Control-Flow Joins

### Problem
Cleanup logic is hardest at control-flow joins.

### Existing Coverage
- Simple `phi` node in `test_inline_conditional`.

### Required Extensions
- Nested conditionals.
- Conditional + reassignment.
- Early-return–like control flow (once supported).

### Agent Task
Ensure frees are:
- Emitted exactly once.
- Placed on all live paths.
- Never duplicated across joins.

---

## 5. Malloc-Failure Invariants

### Problem
In optimized IR, `to_string` may produce:

    { len = ?, ptr = null, tag = -1 }

If downstream code assumes `ptr != null` when `len > 0`, crashes are possible.

### Required Decision
Choose and enforce **one global invariant**.

Option A (safe empty string):

    if ptr == null:
        len = 0
        tag = 0

Option B (fail fast):
- Trap or abort on OOM.

### Agent Task
Verify all producers and consumers of `String` obey the chosen invariant.

---

## 6. Design Tweak (Optional, Forward-Looking)

### Observation
The third field currently encodes **capacity + ownership** using a sentinel.

Current model:
- `cap > 0`  → heap-owned
- `cap == -1` → static / non-owned

### Possible Refinement
- `cap >= 0` → heap string
- `cap == 0` → non-owned/static
- Or split into `{ cap, flags }`

### Rationale
Sentinel values work, but become awkward once introducing:
- Slices
- Substrings
- Shared buffers
- Ref-counted or borrowed views

---

## Bottom Line

- The current mechanism works for leaf values.
- Optimized IR shows it is compiler-friendly.
- Next focus areas are:
  - Preventing double-free via aliasing and copies.
  - Correct freeing inside aggregates.
  - Correct cleanup across control-flow joins.

