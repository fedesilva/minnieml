# Design note: progressive token types for zero-cost safety

## Status: superseded

This document is kept for its problem statement and rationale, not as the live
design. The token / brand mechanism described here requires rank-2 polymorphism,
which is a distant dependency (see § Type-system requirements). The same
zero-cost bounds-safe access is achieved without rank-2 by combining
path-dependent index types with refinement types: path-dependence supplies the
provenance the brand was carrying, refinement supplies the range. Structural
invalidation on resize, the Phase 2 role, is an orthogonal mutation/linearity
concern, not something the brand needs to encode.

The live design is [`refinement-types.md`](./refinement-types.md). Read this
document for: the two-parameter provenance/domain analysis (§ Two parameters),
the requirements analysis that showed rank-2 was unavoidable for the brand
approach (§ Type-system requirements), the matmul acceptance test (§3), and the
Phase 3 effects-unification idea. Treat the rest as the exploration that led to
the refinement + path-dependence conclusion.

## Abstract

Token types are a compile-time mechanism for eliminating runtime bounds checks
(BCE) without giving up memory safety. The work is staged:

1. Size-guarding (Phase 1): in-place updates in loops.
2. Provenance-guarding (Phase 2): structural changes such as resizes.

The design also treats mutation as a capability, which is what allows Phase 3
to fold it into algebraic effects later.

---

## Two parameters, not one

The token carries two independent type parameters, written by juxtaposition
like any other type constructor (mml has no `[...]` bracket syntax):

* `Token 'Arr 'Idx`: `'Arr` ties the token to one specific array
  identity/generation; `'Idx` names the index domain valid for that generation.
* `Index 'Idx`: an integer checked into a reusable index domain. It carries
  `'Idx` only, never `'Arr`.

This separates the two things a single `Token 'Id` conflated: which array
generation is being protected (`'Arr`) versus which index domain is valid for
it (`'Idx`).

The distinction matters because bounds safety is not "this integer is less than
some size" but "this integer is valid for this specific array state." After a
structural change (resize, push, free) the array generation changes, so the new
token becomes `Token 'Arr2 'Idx2`, and existing values of `Index 'Idx` no
longer typecheck against it. Splitting the parameters gives zero-cost reuse for
ordinary reads and writes (the `'Idx` domain is stable across them) while making
structural invalidation explicit and statically enforced (a fresh `'Arr` forces
a fresh `'Idx`).

---

## Type-system requirements

The design relies on generative branding, not singleton or dependent types. A
brand guarantees only distinctness (`'Arr` ≠ `'Arr2`), never identity (the type
never names which array value). That is sufficient, and weaker than reflecting
the value or its size into the type, but it imposes two obligations.

### 1. The scope must be rank-2 (or existential)

For a brand to be unforgeable, `with_token` must introduce `'A`/`'I` as fresh
type variables that cannot escape its scope. The signature shown in Phase 1 is
incomplete; the binding is what makes it sound:

```minnieml
fn with_token (arr: Array T) (scope: (forall a i. Token a i -> R)) : R
```

The `forall a i.` under the function arrow is essential. Without it, `'A`/`'I`
unify with the caller's types and two arrays' tokens become interchangeable,
which is the bug branding exists to prevent. The following must be rejected:

```minnieml
with_token arr1 (\t1 ->            # t1 : Token 'A1 'I1
  with_token arr2 (\t2 ->          # t2 : Token 'A2 'I2
    get t2 (check t1 0)))          # ERROR: Index 'I1 vs 'I2 (good)
```

Nesting two `with_token` calls on the same array value still yields distinct
brands. That is more conservative than per-instance validity, and it shows that
branding is not value-identity (same value, two types).

Prerequisite: mml must support rank-2 polymorphism or existential quantification
at the argument position. If the type system is rank-1 only, this spec cannot be
implemented as written. This is not a near-term feature. Per
`simp-and-seq-typers.md`:

* Rank-2 is "out of scope for initial implementation" (§ polymorphism target);
  the MVP is rank-1 (prenex) only.
* Rank-N is future work, gated: "Do not attempt Rank-N until Rank-1 is solid and
  tested."
* The SimpSeq typer that would introduce this is itself blocked on multi-module
  compilation → SeqTyper global-state design → SeqTyper implementation (its
  "Dependencies" section and Q6).

So the dependency chain before this spec is implementable is:

```
multi-module compilation
  → SeqTyper global-state design
  → SeqTyper implementation
  → rank-1 inference solid & tested
  → rank-N work begins
  → rank-2 available
  → THIS spec implementable
```

Treat this document as a forward design note, not a buildable plan. Phase 1 is
the first thing that could land, and only after the chain above clears. Nothing
here should block or assume near-term work.

### 2. Branding proves membership, not range

A brand certifies "this index belongs to this array's index domain." It says
nothing about whether the integer is in range, so any index derived by
arithmetic is not automatically safe:

```minnieml
sieve_loop t (idx + 1)   # idx : Index 'I, but (idx + 1) may be out of bounds
```

`idx + 1` cannot keep the type `Index 'I` soundly under pure branding. The
sound options are:

* Re-check: `check t (idx + 1)` on each step, paying the runtime cost the design
  set out to eliminate. Acceptable once per iteration in many loops, but not
  zero-cost.
* Bounded successor: a primitive
  `succ_in : Token 'A 'I -> Index 'I -> Option (Index 'I)` that folds the bound
  check into the increment. Still a check, but fused.
* Length-indexed redesign: reflect the size as a type-level `Nat` (a singleton
  or dependent-style design) so `idx + 1 < n` is discharged statically. This is
  more powerful and out of scope here; it is a different point in the design
  space, noted only so the limitation is explicit.

Phase 1's sieve example currently hand-waves this ("Assume +1 logic is
handled/checked"). It should be read as not yet sound; closing it requires one
of the three options above, and that choice is open.

### 3. Computed-index access (the matmul case)

The matmul benchmark is the case branding alone does not cover. In
`benchmark/mat-mul.mml` and `benchmark/mat-mul-opt.mml` every hot access is an
`unsafe_ar_int_*` call whose index is recomputed affine arithmetic, never a
checked cursor:

```minnieml
let idx_a = (i * n) + k;     # mat-mul.mml:23
let idx_b = (k * n) + j;     # mat-mul.mml:25
let idx_c = (i * n) + j;     # mat-mul.mml:39  (a WRITE)
```

The runtime (`mml_runtime.c`, `IntArray = { int64_t length; int64_t* data; }`)
shows the stakes: `ar_int_get`/`ar_int_set` differ from their `unsafe_`
counterparts by exactly `idx < 0 || idx >= arr.length` plus the abort path. A
brand removes provenance confusion but discharges none of that range predicate
for a computed `i*n + j`. Applied to matmul as specced, Phase 1 yields only two
outcomes:

* keep `unsafe_`: token types bought no safety here; or
* `check` every computed index in the inner loop: safe, but the per-access
  branch is back inside an O(n³) nest, removing the speed the design exists for.

Matmul is therefore a necessary test for the design. The candidate extensions
are:

* Multi-dimensional token: `Token2D 'A 'Rows 'Cols` with
  `check2d : Token2D 'A 'R 'C -> Int -> Int -> Option (Ix2 'R 'C)`. The
  `i*n + j` flattening lives behind the abstraction; the caller hoists one
  `check2d` per `(i,j)` out of the `k` loop, and the library carries the
  flattening-correctness argument once.
* Checked affine index: a combinator admitting `a*cursor + b` when `cursor` is
  already checked and `a`, `b` are bounded, propagating a range fact instead of
  re-checking.
* Length-indexed/dependent redesign: size as a type-level `Nat`; the most
  powerful and the furthest out (see § Type-system requirements).

This is a gap. The design as written is sound for cursor-style iteration and
incomplete for computed-offset access. It must continue to evolve, at minimum a
worked `Token2D` (or affine-index) treatment, before it can claim to make the
matmul benchmarks both safe and zero-cost. Until then, treat computed-index
access as out of scope, not solved.

### 4. Refinement types: a lighter path than full dependence

Refinement types are base types carrying a predicate in a decidable logic,
e.g. `{ v: Int | 0 <= v && v < len }`, discharged by an SMT solver. They are a
middle point worth pursuing instead of, or before, the heavier machinery, and
they stay short of full dependent types: no Π/Σ over arbitrary terms, no
proofs-as-values, checking stays decidable, predicates confined to the solver's
logic.

What they offer here:

* They can subsume Phase 1. `check` returns
  `{ idx: Int | 0 <= idx && idx < len }`; `get`/`set` are zero-cost-safe for
  linear cursor patterns (`idx`, `idx + 1` with `idx + 1 < len` in scope) with
  no rank-2 and no brand. This sidesteps the distant rank-2 dependency above and
  could land earlier on the roadmap.
* They compose with the brand rather than competing. Brand is provenance (which
  array); refinement is range (in bounds). An `Index` that is both brand-tagged
  and refined closes membership and range together, which is cleaner than either
  alone.

The limit is the matmul limit again: `i*n + j < n*n` with both `i` and `n`
variable is nonlinear integer arithmetic. Pure linear-arithmetic refinement
(Presburger / QF_LIA) will not discharge it automatically, and nonlinear SMT is
undecidable in general and unreliable in practice. The realistic answers are
the same two as above: prove the flattening lemma
`i < n && j < n ⇒ i*n + j < n*n` once (as a trusted/ghost lemma the solver
reuses), or hide the multiplication behind a 2D-indexed abstraction so user
code never emits the nonlinear obligation. Refinement types make the linear
bulk free; the nonlinear flattening step still needs a one-time lemma or an
abstraction boundary.

Cost to weigh: the v1 stance avoids an SMT dependency entirely, using property
tests on a curated prelude algebra hierarchy (Spire-style `discipline`) and a
small hand-rolled decision procedure. The subtyping tension is resolved per
`refinements.mml`: refinement subsumption is modeled with row polymorphism,
not nominal subtyping, consistent with the NO SUBTYPING stance in
`simp-and-seq-typers.md`. The remaining question — algebra-on-protocols with
an overflow modifier — is rescoped to the arithmetic-discharge tier and does
not gate the core refinement story (validators + erasure + bounded successor
for cursor patterns). See `refinement-types.md` for the tier factoring and
the deferred discharge-tier design.

Assessment: refinement types recover much of the safety this document is
chasing without waiting on rank-2, and they degrade in the same way as branding
(lemma or 2D abstraction) at the same point. They are an alternative spine for
Phases 1–2, not only an add-on. The full treatment, the seed sketch, and the
open questions are in [`refinement-types.md`](./refinement-types.md) (seeded by
`refinements.mml`); consult it for the detailed model.

---

## Phase 1: size-guarding tokens (the "easy" case)

Goal: allow indices validated once to be reused efficiently in loops and
read/write operations, provided the array structure (size) remains constant.

### Core concept

A `Token 'Arr 'Idx` is proof that the array generation `'Arr` exists with a
fixed size, and that `'Idx` is the index domain valid for it.

* Reads (`get`) require a token and an index drawn from the token's `'Idx`
  domain.
* Writes (`set`) require a token but do not invalidate it, because they change
  neither the generation `'Arr` nor the domain `'Idx`.

### Type primitives

* `Array T`: the heap object.
* `Token 'Arr 'Idx`: a phantom witness binding array generation `'Arr` to index
  domain `'Idx`.
* `Index 'Idx`: an integer checked against the `'Idx` domain of some token.

### Operations

```minnieml
# 1. validation (one-time runtime cost)
# Creates a scope where 'arr' is locked as generation 'A with domain 'I
fn with_token (arr: Array T) (scope: (Token 'A 'I -> R)) : R

# 2. check (the hoist)
# Returns an index in the token's domain 'I (not tied to the generation 'A)
fn check (t: Token 'A 'I, i: Int) : Option (Index 'I)

# 3. access (zero-cost)
# Compiles to raw pointer arithmetic: *(base + idx)
fn get (t: Token 'A 'I, idx: Index 'I) : T

# 4. in-place mutation (non-invalidating)
# Compiles to raw store. returns unit (or same token)
# Crucial: 'idx' remains valid after this call: both 'A and 'I are unchanged.
fn set (t: Token 'A 'I, idx: Index 'I, val: T) : Unit
```

### Example: the "safe" sieve loop

Because `set` changes neither the generation `'A` nor the domain `'I`, the
recursive loop can reuse `idx` without re-checking.

```minnieml
fn sieve_loop (t: Token 'A 'I, idx: Index 'I) : Unit =
  if (get t idx) == 1 then
     # Safe Mutation: 't' is still 'Token 'A 'I'
     set t idx 0;
     # Recursion: 'idx' is still in domain 'I'
     sieve_loop t (idx + 1) # (Assume +1 logic is handled/checked)
  else
     sieve_loop t (idx + 1)
```

---

## Phase 2: structural provenance (the "full" case)

Goal: handle operations that change memory layout (resize, push, free) by
forcing the invalidation of old indices at the type level.

### Core concept

Structural mutation advances the array generation. The system must issue a new
token with both a fresh generation `'Arr2` and a fresh index domain `'Idx2`,
representing the post-mutation state. Tying the new generation to a new domain
is what mechanically invalidates the old `Index` values.

### Operations

```minnieml
# 1. structural mutation (invalidating)
# 't_old is consumed. 't_new is born with a new generation AND a new domain.
# Indices of type (Index 'Oi) are NOT compatible with (Token 'Na 'Ni)
fn resize (t: Token 'Oa 'Oi, new_size: Int) : Token 'Na 'Ni

# 2. append
fn push (t: Token 'Oa 'Oi, val: T) : Token 'Na 'Ni
```

### Flow

1.  User calls `resize(t1, 500)` where `t1 : Token 'A1 'I1`.
2.  Compiler returns `t2 : Token 'A2 'I2`.
3.  User tries to access `get(t2, idx1)` where `idx1 : Index 'I1` was checked against `t1`.
4.  Compile error: type mismatch `Index 'I1` vs `Index 'I2`.
5.  Fix: user must call `check(t2, ...)` again to obtain an `Index 'I2`.

---

## Phase 3: unification with algebraic effects

Mutation is an effect; the token is the capability. When MinnieML introduces
algebraic effects, the token is the payload of the effect handler.

### The unification

1.  Effect definition. The `Mutate` effect requires proof of ownership (the
    token).
    ```minnieml
    effect Mutate {
      fn write(idx: Index 'Idx, val: T) : Unit
    }
    ```

2.  The handler (the "with" block). The `with_token` function becomes an effect
    handler that:
    * instantiates the token (the capability),
    * handles `perform Write`,
    * compiles down to raw memory ops.

3.  State threading. For Phase 2 (resizing), the effect system handles the
    linear state threading:
    * the handler maintains the current valid token,
    * if a `Resize` effect is performed, the handler updates its internal token,
    * subsequent computations receive the new context implicitly.

### Comparison table

| Operation | Phase 1 (Size) | Phase 2 (Structure) | Phase 3 (Effects) |
| :--- | :--- | :--- | :--- |
| **Read** | `get(t, idx)` | `get(t, idx)` | `perform Read(idx)` |
| **Update** | `set(t, idx, v)` | `set(t, idx, v)` | `perform Write(idx, v)` |
| **Resize** | *Not Supported* | `let t2 = resize(t1)` | `perform Resize(size)` |
| **Generation `'Arr`** | Constant | Changes on resize | Managed by Handler |
| **Domain `'Idx`** | Constant | New domain on resize | New domain on resize |
| **Indices** | Reusable | Invalidated on resize | Invalidated on resize |
