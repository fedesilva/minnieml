# Ownership & Lifetime: Agent Spec (Machine-Checkable + Execution Checklist)

This document has **two parts**:

1) **Machine-checkable invariants** (what must be true in the compiler/IR/runtime model)  
2) **Agent execution checklist** (how an agent systematically tests and validates them)

No nested fences. Examples are indented code only.

---

## Part 1 — Machine-Checkable Invariants

### Terminology

- **Owned String**: a `String` value that is responsible for freeing its heap buffer.
- **Non-owned String**: a `String` value that must not free its buffer (static / borrowed).
- **Moved-from**: a value whose ownership has been transferred; it must be treated as invalid for freeing or deref.
- **Clone**: deep copy of an owned allocation so two values can be freed independently.
- **Aggregate**: record/array/tuple-like value that may contain owned fields.

---

### I0. String Representation Invariants (Choose One Policy)

You must pick exactly one global OOM policy and enforce it everywhere.

#### Policy A: Null => Empty (non-crashing)
Invariant A1:
- If `ptr == null` then `len == 0` and `tag == 0`.

Invariant A2:
- Functions consuming `String` must accept `{len=0, ptr=null, tag=0}` safely.

Invariant A3:
- Any producer that detects OOM must canonicalize to the empty string shape above.

#### Policy B: Trap on OOM (fail fast)
Invariant B1:
- Any allocation failure must trap/abort before returning a `String`.

Invariant B2:
- No live `String` value may ever have `ptr == null` at runtime.

Agent note: whichever policy is selected, tests must verify the invariant holds on all paths.

---

### I1. Unique Ownership Safety

Invariant U1 (No double-free):
- For any heap allocation `H`, there is at most one dynamic `free(H)` along any program execution.

Invariant U2 (Ownership graph):
- At any program point, each owned heap buffer is owned by at most one live value.

Invariant U3 (Move invalidation):
- After `let b = a` if that operation is a move, then `a` becomes moved-from.
- A moved-from value may not be:
  - freed
  - dereferenced (e.g., printed assuming it has content)
  - stored into aggregates as owned

Invariant U4 (Copy requires clone):
- Any operation that duplicates an owned value (assignment by value, pass-by-value, return-by-value, structural copy)
  must either:
  - be rejected at compile time, or
  - be lowered into an explicit `clone` of the owned buffer.

---

### I2. Binding / Rebinding Semantics

Invariant R1 (Rebind cleanup):
- If `a` currently owns a heap buffer and `a = something_else` occurs,
  then the old owned buffer must be:
  - freed exactly once, OR
  - transferred (if the assignment is actually a move-out), OR
  - preserved only if it was moved earlier (so `a` no longer owns it).

Invariant R2 (Use-after-move):
- Any use of a moved-from binding is either a compile-time error or is lowered into safe behavior
  (e.g., treating moved-from string as empty). Pick one strategy and make it consistent.

---

### I3. Aggregate Ownership

Invariant A1 (Destructor completeness):
- For any aggregate type `T` with owned fields, `__free_T` must:
  - call the correct free routine on every owned field that is live.

Invariant A2 (No implicit shallow copy of owned fields):
- Copying an aggregate containing owned fields must not create two aggregates that both believe they own the same buffer.
- Therefore: aggregate copy must be:
  - rejected, OR
  - deep-copy owned fields, OR
  - compile into move-only semantics.

Invariant A3 (Recursive aggregates):
- If aggregates can nest, destructors must be structurally recursive and still satisfy U1.

---

### I4. Control-Flow Join Correctness

Invariant C1 (Single cleanup at join):
- If a variable owns a buffer and control flow merges (phi-like),
  cleanup must happen exactly once on all paths where ownership remains live at function exit or rebinding.

Invariant C2 (Path-sensitivity):
- For each owned value, the compiler must prove at each join:
  - which path provides the live owner
  - or that ownership was moved/consumed on a path

Invariant C3 (No missing frees):
- Any owned value created on a path must be either:
  - moved out, OR
  - freed on all paths, OR
  - returned (with ownership transferred to caller)

---

### I5. IR-Level Structural Checks (Compiler-Friendly)

These invariants are checkable from emitted LLVM IR (or your lower IR).

Invariant IR1 (Destructor calls are visible):
- Aggregates’ `__free_*` calls appear in IR on all necessary paths.

Invariant IR2 (No duplicate frees of same pointer SSA root):
- For any pointer value that is provably the same allocation, do not emit two frees in a single dynamic path.

Invariant IR3 (Canonical empty string for Policy A):
- All OOM returns normalize to `{len=0, ptr=null, tag=0}` (or equivalent layout).

---

## Part 2 — Agent Execution Checklist

The agent should run this as a deterministic workflow.

### Step 0 — Declare Global Settings

- Record the chosen OOM policy: A (Null => Empty) or B (Trap).
- Record whether `String` is:
  - move-only, or
  - copyable via implicit clone, or
  - copyable only via explicit clone.

The rest of the checks must align to those decisions.

---

### Step 1 — Generate Target Programs (Negative + Positive)

Create a test matrix. Each row is a program; expected outcome is {compile error | compiles and runs safely}.

#### 1. Copy / Duplicate Ownership Bugs

T1 (two bindings):
    let a = to_string(123);
    let b = a;
    free(a);
    free(b);

Expected:
- If move-only: compile error on free(a) or on let b=a as copy.
- If clone-on-copy: compiles and runs; b is independent.
- If explicit clone: compile error unless clone inserted.

T2 (store in two places):
    let s = to_string(123);
    let x = s;
    let y = s;
    free(x);
    free(y);

Expected: must not double-free.

#### 2. Move Semantics Across Rebinding

T3 (rebind source):
    let a = to_string(1);
    let b = a;
    a = to_string(2);
    free(b);

Expected:
- No crash; b remains valid if b owns old allocation.
- Old a allocation must be freed exactly once (by b’s free), not by rebind.

T4 (free moved-from):
    let a = to_string(1);
    let b = a;
    free(a);

Expected: compile error or safe no-op depending on policy.

#### 3. Aggregates

T5 (record destructor):
    type Foo = { s: String };
    let f = Foo { s = to_string(1) };
    free(f);

Expected: exactly one free of the inner buffer.

T6 (record copy hazard):
    let f1 = Foo { s = to_string(1) };
    let f2 = f1;
    free(f1);
    free(f2);

Expected: rejected OR deep-copy OR move-only.

T7 (array/container):
    let a = [to_string(1), to_string(2)];
    free(a);

Expected: frees both strings exactly once.

T8 (same string inserted twice):
    let s = to_string(9);
    let a = [s, s];
    free(a);

Expected: rejected OR clones OR move invalidates one insertion.

#### 4. Control Flow

T9 (simple join):
    let s =
      if cond then
        to_string(1)
      else
        to_string(2);
    free(s);

Expected: exactly one free for whichever branch executed.

T10 (nested):
    let s =
      if c1 then
        if c2 then to_string(1) else to_string(2)
      else
        to_string(3);
    free(s);

Expected: exactly one free.

T11 (branch consumes ownership):
    let s = to_string(1);
    if cond then
      free(s)
    else
      println(s);
    # function ends

Expected:
- If s freed in one branch, compiler must not free again at end.
- If s still live in else, must be freed at end (or ownership returned).

#### 5. OOM Invariants

T12 (forced OOM path):
- Create a harness that makes allocation fail (malloc shim or artificial failure counter).
- Call to_string and then println.

Expected:
- Policy A: observe safe empty behavior; no crashes; invariant {ptr=null => len=0,tag=0}.
- Policy B: observe trap/abort, not a bad string.

---

### Step 2 — For Each Program, Run the Pipeline

For each test Ti:

1) Compile:
- Capture diagnostics (must match expected compile error vs success)

2) If compilation succeeds:
- Run executable
- Capture exit code + stdout/stderr

3) Inspect emitted IR:
- Confirm presence/absence of `__free_*` calls as expected
- Confirm no obviously duplicated frees on same path

4) Optional but recommended:
- Run with ASan/UBSan if your toolchain supports it
- Run with a malloc/free tracer (debug runtime) to detect double-free and leaks

---

### Step 3 — Runtime Instrumentation Rules (If Available)

If you can modify the runtime for debug builds, add:

- Allocation table: map ptr -> {allocated, freed}
- On free(ptr):
  - assert ptr was allocated and not already freed
- On program exit:
  - assert all owned allocations freed (unless intentionally leaked)

Agent should prefer these checks over eyeballing logs.

---

### Step 4 — Success Criteria

A test run is “green” only if:

- All negative tests:
  - fail compilation for the right reason, OR
  - compile but behave safely by explicit policy (e.g., clone insertion)

- All positive tests:
  - compile
  - run without crash
  - show no leaks and no double-frees (per instrumentation)
  - satisfy chosen OOM invariant

- IR checks:
  - cleanups appear on all necessary paths
  - no duplicated cleanup on merged paths

---

### Step 5 — Output Format (Agent Report)

For each Ti, emit:

- Test ID + name
- Expected outcome
- Compile result (ok / error)
- Run result (exit code, key output)
- Ownership verdict:
  - double-free: yes/no
  - leak: yes/no
  - use-after-move: yes/no
- OOM invariant verdict (if applicable)
- IR notes (brief)

---

## Bottom Line

Your current tests show the mechanism works for leaf values and optimizes well.
This next battery exists to break it where compilers usually fail:

- aliasing + copies
- move invalidation
- aggregates with owned fields
- cleanup placement across control flow
- consistent OOM invariants
