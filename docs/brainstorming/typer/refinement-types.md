# Design note: refinement types

## Status

Forward design note. The working sketch [`refinements.mml`](./refinements.mml)
is the seed — read it first, it's short, and its decisions are baseline here.
This note builds the model around the sketch and wires it up to
[`token-types.md`](./token-types.md).

## Why

A refinement type is a base type plus a predicate the compiler can decide —
`Int { i | 0 <= i && i < len }` and friends. The compiler proves what it can
at compile time and falls back to a runtime validator for the rest. It's a
middle ground between today's unrefined types and full dependent types: no
types whose return or pair element is computed from a runtime value, no
proofs-as-values, the checker always terminates, predicates live in a bounded
logic.

It matters here for one reason: combined with path-dependent index types
(§ Path-dependent index types), refinements recover the safety property
[`token-types.md`](./token-types.md) was chasing — zero-cost bounds-safe array
access — without rank-2 and without a compile-time-only brand. Path-dependence
says *which array*; refinement says *in range*. Together they replace the
token/brand story for size-guarding, and the rank-2 dependency that gated it
falls away. Invalidating indices when an array is structurally mutated is a
separate problem, handled by linearity in the ownership model rather than by
typing (see § Relation to token types).

## Scope: core vs. arithmetic-proof tier

Refinement use cases split cleanly in two. The split drives most of what looks
unresolved below — most "open" questions live in the second tier and don't
block the first.

**Core tier.** Refinements whose predicate is validated once at construction
and never participates in static reasoning beyond literal-bounded range
containment. Examples: `MidiNote = Int { i | 0 <= i < 128 }`,
`Angle [0..360]`, `Port < 65536`, `Day [1..31]`, `NonEmptyList`,
`Sized [<= 100]`, `EmailAddress`, and cursor-style array indexing (sieve,
quicksort partition) paired with a bounded successor primitive
`succ : a.Idx -> Option a.Idx` that fuses increment and range check. What it
needs: a validating constructor (D4), erasure to the base representation
(codegen requirement, see § Prior art), and the `succ` primitive. No algebra
protocols, no decision procedure beyond the runtime check, no overflow
apparatus. This is the immediate target.

**Arithmetic-proof tier.** Activates when the goal is to statically delete a
per-access bounds check on a *computed* index inside a hot loop — matmul,
FFT, stencil kernels. Here the checker has to reason about arithmetic
identities (associativity of `+`, distributivity, order under multiplication
by positives), and the wall is whether those laws hold on the operand type,
i.e. the overflow question. What it needs: algebra protocols carrying laws,
a decision procedure that consumes them, and a way to encode "laws hold
assuming no overflow" for machine-integer types. Deferred. O2, O4, O5 sketch
it but it's not on the near-term path.

For the core tier, the open work is bounded and mostly mechanical: define the
validating-constructor lowering against the path-dependent index type and the
`succ` primitive.

## Established decisions (from `refinements.mml`)

These come from the sketch. Not re-litigating them here.

### D1. Refinement is a type with a predicate binder

```minnieml
type IntChico = Int { i | i <= 5 }
```

`{ i | P(i) }` binds the value and states a predicate over it. *Chico* means
small — naming is incidental.

### D2. Generic refinement is Protocol-bounded

```minnieml
type TChico 'T = 'T { i | i <= 5 }
```

`TChico` refines any `'T` whose Protocol provides the operators the predicate
uses (here `<=` against an `Int`). The predicate language is ordinary
operators resolved through Protocols, not a separate sub-language. See O2 for
the tier split this forces.

### D3. Compatibility is row-polymorphic, never nominal subtyping

When you can use a value of one refinement where another refinement is
expected ("compatibility"), that's row polymorphism, not subtyping. An `Int`
literal satisfying `IntChico` isn't modeled by saying `IntChico` is a subtype
of `Int`; it's modeled by rows, consistent with the "NO SUBTYPING, use rows"
stance in [`simp-and-seq-typers.md`](./simp-and-seq-typers.md). That resolves
the tension noted in token-types §4: refinement compatibility doesn't drag in
structural or nominal subtyping. The mechanism still needs an implication
checker — see O1.

### D4. Static for literals, validating constructor for the rest

* Literal ascription is checked at compile time: `let a: IntChico = 3`
  (compiler proves `3 <= 5`); `let b: IntChico = 20` is a compile error
  (`20 <= 5` is false).
* For non-literal values the type name doubles as a validating constructor:
  `IntChico 5` runs the predicate at runtime and returns a `Maybe`/`Result`
  the caller must fold. The computation bifurcates.

### D5. Type-as-validator reuses the synthesized-constructor mechanism

`IntChico` being both a type and a function isn't a new name-resolution
problem. It's the same way a struct name (`Struct1`) is both a type and its
constructor: the compiler synthesizes a constructor binding (internal mangled
name, e.g. `__new_IntChico`) that the type name resolves to in value position.
The codebase already does this for structs; the refinement validator is the
same machinery.

The only difference vs a struct constructor: the synthesized body runs the
predicate and returns `Maybe (TChico 'T)` (fallible) instead of an infallible
allocation:

```minnieml
# chico 'T : 'T -> Maybe (TChico 'T)
fn chico (a) = IntChico a
```

Cleanup nit (not a design hole): in the sketch, `chico`'s annotated return
`Maybe TChico 'T` and its body `IntChico a` (the `Int`-specialised refinement)
should be made consistent — either both generic `TChico` or both `IntChico`.
Pick one when tidying the sketch.

## Prior art: Scala `eu.timepit.refined`

The closest shipped system. It answers two of the open questions by example
rather than speculation, and its architecture is worth following.

* `Refined[T, P]`: base `T` plus a compile-time-only predicate `P` built from
  type-level combinators (`Less[N]`, `Greater[N]`, `Interval.Closed[L,H]`,
  `And/Or/Not`, `Size`, `Forall`). Matches D1/D2.
* One predicate semantics (`Validate[T, P]`): the same definition runs in a
  macro for literals (compile-time, D4's static path) and at runtime for
  dynamic values. Lesson: don't build two predicate languages. One semantics,
  constant-folded when the input is statically known. Tightens D2/D4.
* Runtime construction returns a sum: `refineV[P](x): Either[String, T Refined P]`.
  The refined type inhabits only the success branch; the failure branch is
  forced on the caller. This is D4's bifurcation. Lesson: the validator
  return should be `Result`-shaped (it carries *why* it failed), not a bare
  `Maybe`.
* Implication is a curated rule table, not a solver. `Inference[P1, P2]`
  (`==>`) is a hand-written set of implicit instances (`Greater[10] ==> Greater[5]`,
  `Interval ==> Positive`, etc.). When no instance exists, you `refineV`
  again at runtime. Concrete answer to O1/O5: the pragmatic implication
  checker is a fixed rule table; SMT is the heavier alternative; "no rule,
  so re-check at runtime" is an acceptable fallback.
* It's not dependent typing: predicate parameters must be type-level
  constants. You can't write `Less[arr.length]` for a runtime `length`. A
  custom `Validate` can close over a runtime bound, but then the type `P` no
  longer records which bound — proof identity is erased. Confirms O3
  (refinement alone can't express array-length bounds) and O4 (a numeric-fact
  table doesn't crack the matmul nonlinear case).
* The companion is manual in Scala, automatic in mml. The canonical Scala
  pattern needs an explicit `object Angle extends RefinedTypeOps[Angle, Double]`
  to get `Angle.from(x): Either[String, Angle]` plus the literal macro. mml's
  synthesized `__new_*` (D5) is that companion, generated automatically by
  the same mechanism as struct constructors, with no per-type boilerplate.
  Improvement, not just parity.
* Pay-once is real; full erasure is mml's responsibility, not inherited. Two
  separate facts:
  * The predicate `P` is compile-time-only and unconditionally erased, no
    runtime footprint. The check runs once at `refineV`; the predicate costs
    nothing thereafter. `refined` ships this, and it's the same shape as
    token-types' "validate once, then the proof is free" (the refined-by-
    `refineV` `Angle` plays the same role token-types' checked `Index`
    plays).
  * The wrapper `Refined[T, P]` erasing to the base is not unconditional in
    Scala: it's a value class (`extends AnyVal`), erased only in monomorphic
    direct use and boxed at generic-argument, `Any`, array, and collection
    boundaries (the standard AnyVal limitation).

  Lesson for mml: don't assume carrier erasure for free. mml controls its own
  codegen, so it can make a refined type representationally identical to its
  base (newtype, no box, the way token-types' `Index` is a raw int), making
  erasure total and unconditional. State this as a design requirement, not
  something inherited from `refined`.

The architecture this points at for mml: adopt the split — one predicate
semantics, evaluated statically when constant-foldable and dynamically
otherwise (like `Validate`), plus a curated implication table for
compatibility (like `Inference`) with runtime re-validation as the no-rule
fallback. A general SMT backend is a later, optional upgrade to the table,
not the v1 mechanism.

## Path-dependent index types

Previously a separate note (`path-dependent-types.md`). That note is gone;
its substance is folded in here, because path-dependence is what answers O3
and, together with refinement, replaces the token/brand design.

Every array value `a` carries an abstract index type `a.Idx`. The only way to
get an `a.Idx` is a checked constructor against `a`; reads and writes consume
an `a.Idx`, so they don't branch per-access. Properties:

* **Provenance over arithmetic.** Proving "this index was issued by that
  array" replaces proving `0 <= i < size` at every use site. Cross-array
  misuse (`b.get a_idx`) is a compile error.
* **Predictable and O(1).** The judgement is path/nominal identity, not
  constraint solving. Unlike full dependent types, the checker never times
  out on a complicated `i`.
* **Erasure.** Compile-time only; the backend lowers `a.Idx` to the native
  integer width. No structs, wrappers, or runtime cost — same erasure
  requirement as for refinement carriers.
* **Validator/worker split.** A safe shell does the one-time checks; the hot
  core takes already-validated indices and runs as raw pointer arithmetic.
* **Type-level LICM.** Putting the check in the type signature forces it out
  of the loop, so the inner loop lowers to C-equivalent code without relying
  on the optimizer to prove redundancy.
* **Aliasing is separate.** Path-dependence proves safety, not non-aliasing.
  For vectorization the backend still needs `!noalias` / `!alias.scope`
  metadata — codegen concern, orthogonal to the type system.

Relation to refinement: path-dependence supplies provenance (which array,
predictably); refinement supplies range (in bounds, within a slice the
compiler can decide). Composed, they land at the Dependent ML /
index-refinement design point: a restricted, predictable slice of dependent
typing, not full dependent types. The nonlinear `i*n + j < n*n` wall (O4) is
where that slice ends.

Carryover limitation from the original note, which assumed immutable size
("valid indefinitely, assuming immutable size"): that assumption is exactly
the resize residual. Path-dependence ties an index to a path, not to a
version of that path's contents; if `a.length` mutates in place, an `a.Idx`
proven at one length is unsound at another. More typing doesn't solve this.
It's a mutation/linearity concern: model a structural change as consuming
`a` and rebinding a fresh path (a move), so stale `a.Idx` values no longer
match, or track a generation through the ownership model. Either way, the
compile-time-only brand / rank-2 token machinery isn't needed.

## Open questions

### O1. Rows are the carrier; implication still needs a checker

D3 makes compatibility row-polymorphic. That gives the structural plumbing
(unification, the existing `HasField`-style machinery, a future `HasPred`
analogue) but doesn't by itself decide whether `p` implies `q`. `i <= 3` and
`i <= 5` are individual predicates; "a value of `{i|i<=3}` is usable where
`{i|i<=5}` is expected" is implication, not syntactic predicate-containment.
The real shape: rows carry the proven-predicate set; an implication check
(SMT or a fixed decision procedure) settles implication between sets. Row
polymorphism is necessary but not sufficient — name the checker explicitly
rather than implying it via "modeled via rows."

### O2. Algebra-on-protocols and the arithmetic-proof tier (deferred)

Previously framed as "interpreted vs. uninterpreted predicates," which
assumed a builtin operator set. mml has no builtin operators: `+`, `*`, `<`
are `Num 'T` protocol instances backed by `@native` LLVM templates, the same
mechanism a user instance uses. So the framing changes.

The decision procedure doesn't need operators to be "builtin"; it needs them
to be **law-backed**. The protocol/instance machinery is the natural home
for the laws. Intended trajectory: a port of Scala's Spire, which already
ships:

* A layered algebra hierarchy (`AdditiveSemigroup`, `Monoid`, `Group`,
  `Ring`, `Field`, `Order`, `PartialOrder`, ...) so each type opts in only
  to the laws it actually satisfies — machine ints differ from floats differ
  from saturating arithmetic.
* `discipline`, which expresses each typeclass's laws as runnable property
  tests, so instances confirm conformance by passing the law tests rather
  than producing proof terms.

Adopting that shape in mml:

* `Num 'T` carries operations; algebra is a separate, opt-in protocol layer
  (`OrderedRing 'T`, etc.). Implementing `Num` commits the instance to
  nothing algebraic, which keeps exotic implementations (saturating, modular,
  fixed-point) honest.
* The `@native` template is the instance's runtime face; conformance to an
  algebra protocol is its static face. The decision procedure reads the
  algebra layer to prove predicates over any `'T` known to satisfy it.
* Compiler attitude on laws in v1: trust the curated prelude, verify via
  property tests at instance definition (Spire's `discipline` posture). User
  code doesn't have to prove anything to the compiler. User code in v1
  implements `Num` but doesn't declare algebra-protocol conformance for its
  own types; refinements over user numeric types stay runtime-only by
  default.

The machine-integer overflow seam is first-class under this scheme: an
`OrderedRing I64 modulo overflow_nsw` instance declares "these laws hold
provided no operation overflows," and every compile-time proof that depends
on them is gated on also proving the no-overflow side. IEEE-754 floats take
a weaker instance (`PartialOrder` not `Order`, no `Field` because of `NaN`).
Saturating types take no algebra instance and never participate in static
proofs.

A `SafeNumbers` family (e.g. Spire's `SafeLong`, bignum) has non-`@native`
`Num` implementations that branch or grow to avoid overflow. Their algebra
instances hold unconditionally, so static proof over them doesn't need the
no-overflow check. The trade-off is runtime cost; same factoring Spire
ships.

Scope reminder: none of this is needed for the core refinement tier. It
activates only when statically deleting a per-access bounds check on a
computed index is the goal — the matmul-class workload.

### O3. No term names a specific array's length

The predicate that actually eliminates an array bounds check is
`{ i | 0 <= i && i < len }`, where `len` is *this* array's length, a runtime
value tied to a specific array identity. The term that names a specific
array is the array's own path: `a.Idx`, with the predicate reading
`a.length` (see § Path-dependent index types). Path-dependence supplies the
provenance the token brand was carrying, refinement supplies the range, so
the brand isn't needed. What's still open isn't the naming but the
integration with in-place mutation: a refinement mentioning `a.length` is
unsound if `a.length` can change without rebinding the path. That's the
resize residual, handled by linearity rather than by typing (see O4 and
§ Path-dependent index types).

### O4. Nonlinear arithmetic wall (arithmetic-proof tier only)

Even within a law-backed algebra, `i*n + j < n*n` with `i` and `n` both
variable is nonlinear. Linear-arithmetic solvers won't prove it
automatically, and nonlinear SMT is undecidable in general and unreliable in
practice. Two realistic answers, same as in token-types §3:

* Prove the flattening rule `i < n && j < n => i*n + j < n*n` once as a
  trusted one-time rule the prover reuses; or
* Hide the multiplication behind a 2D-indexed abstraction so user code
  never emits the nonlinear obligation.

The linear bulk is free; the nonlinear flattening step still wants a
one-time rule or an abstraction boundary. Arithmetic-proof tier only — the
core tier never emits nonlinear obligations because its refinements are
literal-bounded or cursor-stepped.

### O5. Compiler-weight cost (resolved for v1)

v1 stance, set in O2: curated prelude algebra hierarchy with laws checked by
property tests (Spire's `discipline` posture) and a small hand-rolled
decision procedure that consumes the named theories directly. No SMT solver
in the compiler. SMT remains an opt-in upgrade for extended theories later;
not on the v1 path. Like O2 and O4, this is meaningful only for the
arithmetic-proof tier.

## Relation to token types

This note supersedes the standalone token/brand mechanism in
[`token-types.md`](./token-types.md). Mapping from each token concept to its
replacement here:

| token-types concept | replacement | note |
| :--- | :--- | :--- |
| brand `'Arr` = provenance | path-dependent `a.Idx` | provenance without rank-2 (§ Path-dependent index types) |
| `Index 'I` range, proven by `check` | refinement predicate `0 <= i < a.length` | range within a slice the compiler can decide |
| `check t i : Option (Index 'I)` | `a.Idx` checked constructor yielding `Maybe`/`Result` | same bifurcating-validator shape (D4) |
| needs rank-2 (distant) | needs path-dependence + rows + implication checker | no rank-2; may land earlier |
| `Index` erases to a raw int | `a.Idx` erases to native int width | same zero-cost target |
| Phase 2 brand regeneration on resize | linearity: structural change rebinds a fresh path | mutation concern, not a typing one |

What token-types still contributes: the problem statement, the two-parameter
provenance/domain analysis, the matmul acceptance test (§3), and the Phase 3
effects-unification idea. Read it as the exploration that motivated this
note, not as the live design. token-types §4 already points here.

## References

- [`refinements.mml`](./refinements.mml): seed sketch (decisions D1–D5)
- [`token-types.md`](./token-types.md): provenance/brand model, §3 matmul gap, §4 refinement path
- [`simp-and-seq-typers.md`](./simp-and-seq-typers.md): NO SUBTYPING / row-polymorphism stance (grounds D3)
- Refinement/Liquid types literature (Liquid Haskell, F*, Rondon–Kawaguchi–Jhala)
