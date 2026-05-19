# Design note: refinement types

## Status

Forward design note. Seeded by the working sketch
[`refinements.mml`](./refinements.mml); that file is the source of the decisions
below. This note develops the model around it and connects it to
[`token-types.md`](./token-types.md). Read the sketch first; it is short, and
its choices are treated here as the established baseline rather than open
questions.

## Why

Refinement types are base types carrying a predicate in a decidable logic,
e.g. `Int { i | 0 <= i && i < len }`, discharged statically where possible and
by a runtime validator otherwise. They are a middle point between today's
unrefined types and full dependent types: no Π/Σ over arbitrary terms, no
proofs-as-values, checking stays decidable, predicates live in a bounded logic.

They are relevant to this codebase for one reason: combined with
path-dependent index types (§ Path-dependent index types) they recover the
safety [`token-types.md`](./token-types.md) was chasing, namely zero-cost
bounds-safe array access, without rank-2 and without a phantom brand.
Path-dependence supplies provenance (which array), refinement supplies range
(in bounds). This combination supersedes the token / brand mechanism for the
size-guarding role and removes the distant rank-2 dependency that gated it. The
remaining concern, invalidating indices when an array is structurally mutated,
is orthogonal: it is handled by linearity in the ownership model, not by typing
(see § Relation to token types).

## Scope: core vs. arithmetic-discharge tier

Refinement use cases factor into two tiers with different design demands.
This factoring is load-bearing for the rest of the note: most of what looks
"unresolved" below belongs to the second tier and does not block the first.

**Core tier.** Refinements whose predicate is validated once at construction
and never participates in static reasoning beyond literal-bounded range
containment. Examples: `MidiNote = Int { i | 0 <= i < 128 }`,
`Angle [0..360]`, `Port < 65536`, `Day [1..31]`, `NonEmptyList`,
`Sized [<= 100]`, `EmailAddress`, and cursor-style array indexing (sieve,
quicksort partition) augmented with a bounded successor primitive
`succ : a.Idx -> Option a.Idx` that fuses increment and range check.
Requirements: a validating constructor (D4), erasure to the base
representation (a codegen requirement stated in § Prior art), and the `succ`
primitive. No algebra protocols, no decision procedure beyond the
constructor's runtime check, no overflow apparatus. This tier is the
immediate target.

**Arithmetic-discharge tier.** The strict extension that activates only when
the goal is to statically delete a per-access bounds check on a *computed*
index inside a hot loop: matmul, FFT, stencil kernels. Here the checker must
reason about arithmetic identities (associativity of `+`, distributivity,
order under multiplication by positives) and the wall is whether those laws
hold on the operand type, which is the overflow question. Requirements:
algebra protocols carrying laws, a decision procedure that consumes them,
and a way to encode "laws hold modulo no overflow" for machine-integer
types. This tier is deferred; O2, O4, and O5 sketch it but it is not on the
near-term path.

For the core tier, the open work is bounded and largely mechanical: define
the validating-constructor lowering against the path-dependent index type
and the `succ` primitive.

## Established decisions (from `refinements.mml`)

Settled by the sketch and carried forward, not re-litigated:

### D1. Refinement is a type with a predicate binder

```minnieml
type IntChico = Int { i | i <= 5 }
```

`{ i | P(i) }` binds the value and states a predicate over it. *Chico* means
small; the naming is incidental.

### D2. Generic refinement is Protocol-bounded

```minnieml
type TChico 'T = 'T { i | i <= 5 }
```

`TChico` refines any `'T` whose Protocol provides the operators the predicate
uses (here `<=` against an `Int`). The refinement predicate language is ordinary
operators resolved through Protocols, not a separate sub-language. See O2 for
the tier split this forces.

### D3. Subsumption is row-polymorphic, never nominal subtyping

An `Int` literal satisfying `IntChico` is not modeled by subtyping. It is
modeled with row polymorphism, consistent with the "NO SUBTYPING, use rows"
decision in [`simp-and-seq-typers.md`](./simp-and-seq-typers.md). This resolves
the tension previously noted in token-types §4: refinement subsumption does not
reintroduce structural or nominal subtyping. The mechanism still needs an
entailment oracle; see O1.

### D4. Static for literals, validating constructor for the rest

* Literal ascription is discharged at compile time: `let a: IntChico = 3` (the
  compiler proves `3 <= 5`); `let b: IntChico = 20` is a compile error
  (`20 <= 5` is false).
* For non-literal values the type name doubles as a validating constructor:
  `IntChico 5` runs the predicate at runtime and returns a `Maybe`/`Result`
  that must be folded. It bifurcates the computation.

### D5. Type-as-validator reuses the synthesized-constructor mechanism

`IntChico` being both a type and a function is not a new name-resolution
problem. It is how a struct name (`Struct1`) is both a type and its
constructor: the compiler already synthesizes a constructor binding (internal
mangled name, e.g. `__new_IntChico`) that the type name resolves to in value
position. The codebase already has synthesized constructor/destructor bindings
for structs; the refinement validator is the same machinery.

The only difference from a struct constructor is that the synthesized body runs
the predicate and returns `Maybe (TChico 'T)` (fallible) instead of an
infallible allocation:

```minnieml
# chico 'T : 'T -> Maybe (TChico 'T)
fn chico (a) = IntChico a
```

Cleanup nit, not a design hole: in the sketch `chico`'s annotated return
`Maybe TChico 'T` and its body `IntChico a` (the `Int`-specialised refinement)
should be made consistent, either as the generic `TChico` validator or the
`IntChico` one. Pick one when the sketch is tidied.

## Prior art: Scala `eu.timepit.refined`

The closest shipped system. It answers two of the open questions by example
rather than speculation, and its architecture is worth following.

* `Refined[T, P]`: base `T` plus a phantom predicate `P` built from type-level
  combinators (`Less[N]`, `Greater[N]`, `Interval.Closed[L,H]`, `And/Or/Not`,
  `Size`, `Forall`). This corresponds to D1/D2.
* One predicate semantics (`Validate[T, P]`): the same definition is evaluated
  in a macro for literals (compile-time, the D4 static path) and at runtime for
  dynamic values. Lesson: do not build two predicate languages; one semantics,
  constant-folded when the input is statically known. Tightens D2/D4.
* Runtime construction is a divergent type: `refineV[P](x)` returns
  `Either[String, T Refined P]`. The refined type inhabits only the success
  branch; the failure branch is forced on the caller. This is D4's
  bifurcation. Lesson: the validator return should be `Result`-shaped (it
  carries why it failed), not a bare `Maybe`.
* Implication is a curated lemma lattice, not a solver. `Inference[P1, P2]`
  (`==>`) is a hand-written set of implicit instances (`Greater[10] ==>
  Greater[5]`, `Interval ==> Positive`, and so on). Where no instance exists,
  you must `refineV` again at runtime. This is a concrete answer to O1/O5: the
  pragmatic entailment oracle is a fixed lemma lattice; SMT is the heavier
  alternative; "no lemma, so forced runtime re-check" is an acceptable fallback.
* It is not dependent typing: predicate parameters must be type-level
  constants. You cannot write `Less[arr.length]` for a runtime `length`. A
  custom `Validate` can close over a runtime bound, but then the type `P` no
  longer records which bound; proof identity is erased. This confirms O3
  (refinement alone cannot express array-length bounds) and O4 (a numeric-fact
  lattice does not crack the matmul nonlinear case).
* The companion is manual in Scala, automatic in mml. The canonical pattern
  needs an explicit `object Angle extends RefinedTypeOps[Angle, Double]` to get
  `Angle.from(x): Either[String, Angle]` plus the literal macro. mml's
  synthesized `__new_*` (D5) is that companion, generated automatically by the
  same mechanism as struct constructors, with no per-type boilerplate. This is
  an improvement, not just parity.
* Pay-once is real; full erasure is mml's responsibility, not inherited. Two
  separate facts:
  * The predicate `P` is phantom and unconditionally erased, with no runtime
    footprint. The check runs once at `refineV`; the predicate costs nothing
    thereafter. `refined` ships this, and it is structurally the token-types
    property "validate once, then the proof is free" (`refineV → Angle` ≡
    `check → Index`).
  * The wrapper `Refined[T, P]` erasing to the base is not unconditional in
    Scala: it is a value class (`extends AnyVal`), erased only in monomorphic
    direct use and boxed at generic-argument, `Any`, array, and collection
    boundaries (the standard AnyVal limitation).

  Lesson for mml: do not assume carrier erasure for free. mml controls its own
  codegen, so it can make a refined type representationally identical to its
  base (a newtype with no box, the way token-types' `Index` is a raw int),
  making erasure total and unconditional. State this as a design requirement
  rather than assuming it from `refined`.

Architecture this implies for mml: adopt the split, a single predicate
semantics evaluated statically when constant-foldable and dynamically
otherwise (as with `Validate`), plus a curated implication lattice for
subsumption (as with `Inference`) with runtime re-validation as the no-lemma
fallback. Treat a general SMT backend as a later, optional upgrade to the
lattice, not the v1 mechanism.

## Path-dependent index types

This material was previously a separate note (`path-dependent-types.md`). That
note is removed; its substance is kept here, since path-dependence is the
mechanism that answers O3 and, together with refinement, replaces the token /
brand design.

Every array value `a` carries an abstract index type `a.Idx`. The only way to
obtain an `a.Idx` is a checked constructor against `a`; reads and writes require
an `a.Idx`, so they need no per-access branch. Properties worth keeping:

* Provenance over arithmetic. Proving "this index was issued by that array"
  replaces proving `0 <= i < size` at every use site. Cross-array misuse
  (`b.get a_idx`) is a compile error.
* Decidable and O(1). The judgement is path/nominal identity, not constraint
  solving. Unlike full dependent types, the checker never times out on a
  complex `i`.
* Erasure. Compile-time only; the backend lowers `a.Idx` to the native integer
  width. No structs, wrappers, or runtime cost, which is the same erasure
  requirement stated for refinement carriers above.
* Validator/worker split. A safe shell performs the one-time checks; the hot
  core takes already-validated indices and runs as raw pointer arithmetic.
* Type-level LICM. Putting the check in the type signature forces it out of the
  loop, so the inner loop lowers to C-equivalent code without relying on the
  optimizer to prove redundancy.
* Aliasing is separate. Path-dependence proves safety, not non-aliasing. For
  vectorization the backend must still inject `!noalias` / `!alias.scope`
  metadata; that is a codegen concern, orthogonal to the type system.

Relation to refinement: path-dependence supplies provenance (which array,
decidably); refinement supplies range (in bounds, within a decidable fragment).
Composed, they are the Dependent ML / index-refinement design point: a
restricted, decidable slice of dependent typing, not full dependent types. The
nonlinear `i*n + j < n*n` wall (O4) is where that slice ends.

Limitation carried over from the original note, which assumed immutable size
("valid indefinitely, assuming immutable size"): that assumption is exactly the
resize residual. Path-dependence ties an index to a path, not to a version of
that path's contents; if `a.length` mutates in place, an `a.Idx` proven at one
length is unsound at another. This is not solved by more typing. It is a
mutation/linearity concern: model a structural change as consuming `a` and
rebinding a fresh path (a move), so stale `a.Idx` values no longer match, or
track a generation through the ownership model. Either way the phantom-brand /
rank-2 token machinery is not required.

## Open questions

### O1. Rows are the carrier; entailment still needs an oracle

D3 makes subsumption row-polymorphic. That gives the structural plumbing
(unification, the existing `HasField`-style machinery, a future `HasPred`
analogue) but does not by itself decide `p ⇒ q`. `i <= 3` and `i <= 5` are
distinct predicate atoms; "a value of `{i|i<=3}` is usable where `{i|i<=5}` is
expected" is entailment, not syntactic atom-containment. The real shape is:
rows carry the proven-predicate set; an entailment check (SMT or a fixed
decision procedure) discharges implication between sets. Position: row
polymorphism is necessary but not sufficient, so the oracle should be named
explicitly rather than implied by "modeled via rows."

### O2. Algebra-on-protocols and the discharge tier (deferred)

This was previously framed as "interpreted vs. uninterpreted predicates,"
assuming a builtin operator set. mml has no builtin operators: `+`, `*`, `<`
are `Num 'T` protocol instances backed by `@native` LLVM templates, the same
mechanism a user instance uses. The framing is therefore reworked.

The decision procedure does not need operators to be "builtin"; it needs them
to be **law-witnessed**. The protocol/instance machinery is the natural place
for those laws. The intended trajectory is a port of Scala's Spire, which
already ships:

* a layered algebra hierarchy (`AdditiveSemigroup`, `Monoid`, `Group`, `Ring`,
  `Field`, `Order`, `PartialOrder`, ...) so each type opts in only to the laws
  it actually satisfies — machine ints differ from floats differ from
  saturating arithmetic;
* `discipline`, which expresses each typeclass's laws as runnable property
  tests, so instances confirm conformance by passing the law tests rather
  than by producing proof terms.

Adopting this shape in mml yields:

* `Num 'T` carries operations; algebra is a separate, opt-in protocol layer
  (`OrderedRing 'T`, etc.). Implementing `Num` commits the instance to nothing
  algebraic, which leaves exotic implementations (saturating, modular,
  fixed-point) honest.
* The `@native` template is the instance's runtime face; conformance to an
  algebra protocol is its static face. The decision procedure reads the
  algebra layer to discharge predicates over any `'T` known to satisfy it.
* Compiler attitude on laws in v1: trust the curated prelude, verify via
  property tests at instance definition (Spire's `discipline` posture). No
  proof obligations. User code in v1 implements `Num` but does not declare
  algebra-protocol conformance for its own types; refinements over user
  numeric types remain runtime-only by default.

The machine-integer overflow seam is first-class under this scheme: an
`OrderedRing I64 modulo overflow_nsw` instance declares "these laws hold
provided no operation overflows," and every static discharge that depends on
them is conditioned on discharging the no-overflow side obligation. IEEE-754
floats take a weaker instance (`PartialOrder` rather than `Order`, no `Field`
because of `NaN`). Saturating types take no algebra instance and never
participate in static discharge.

An alternative `SafeNumbers` family (e.g. Spire's `SafeLong`, bignum) has
non-`@native` `Num` implementations that branch or grow to avoid overflow.
Their algebra instances hold unconditionally, so static discharge on them does
not require the no-overflow side condition. The trade-off is runtime cost;
the factoring is the same Spire ships.

Scope reminder: none of this is needed for the core refinement tier. It
activates only when statically deleting a per-access bounds check on a
computed index is the goal — the matmul-class workload.

### O3. No term names a specific array's length

The predicate that actually eliminates an array bounds check is
`{ i | 0 <= i && i < len }`, where `len` is this array's length, a runtime
value tied to a specific array identity. The term that names a specific array
is the array's own path: `a.Idx`, with the predicate reading `a.length` (see
§ Path-dependent index types). Path-dependence supplies the provenance the
token brand was carrying, refinement supplies the range, so the brand is not
needed. What remains open is not the naming but the integration with in-place
mutation: a refinement mentioning `a.length` is unsound if `a.length` can
change without rebinding the path. That is the resize residual, handled by
linearity rather than by typing (see O4 and § Path-dependent index types).

### O4. Nonlinear arithmetic wall (discharge tier only)

Even within a law-witnessed algebra, `i*n + j < n*n` with `i` and `n` both
variable is nonlinear. Linear-arithmetic refinement (Presburger / QF_LIA)
will not discharge it automatically, and nonlinear SMT is undecidable in
general and unreliable in practice. The two realistic answers are the same
as in token-types §3:

* prove the flattening lemma `i < n && j < n ⇒ i*n + j < n*n` once as a
  trusted/ghost lemma the discharge procedure reuses; or
* hide the multiplication behind a 2D-indexed abstraction so user code never
  emits the nonlinear obligation.

The linear bulk is free; the nonlinear flattening step still needs a
one-time lemma or an abstraction boundary. This is meaningful only for the
discharge tier — the core tier never emits nonlinear obligations because its
refinements are literal-bounded or cursor-stepped.

### O5. Compiler-weight cost (resolved for v1)

The v1 stance, adopted in O2, is a curated prelude algebra hierarchy with
laws checked by property tests (Spire's `discipline` posture) and a small
hand-rolled decision procedure that consumes the named theories directly.
No SMT solver in the compiler. SMT remains an opt-in upgrade for extended
theories later; it is not on the v1 path. Like O2 and O4, this question is
meaningful only for the discharge tier.

## Relation to token types

This note supersedes the standalone token / brand mechanism in
[`token-types.md`](./token-types.md). The mapping from each token concept to its
replacement here:

| token-types concept | replacement | note |
| :--- | :--- | :--- |
| brand `'Arr` = provenance | path-dependent `a.Idx` | provenance without rank-2 (§ Path-dependent index types) |
| `Index 'I` range, proven by `check` | refinement predicate `0 <= i < a.length` | range within a decidable fragment |
| `check t i : Option (Index 'I)` | `a.Idx` checked constructor → `Maybe`/`Result` | same bifurcating-validator shape (D4) |
| needs rank-2 (distant) | needs path-dependence + rows + entailment oracle | no rank-2; may land earlier |
| `Index` erases to a raw int | `a.Idx` erases to native int width | same zero-cost target |
| Phase 2 brand regeneration on resize | linearity: structural change rebinds a fresh path | mutation concern, not a typing one |

What token-types still contributes: the problem statement, the two-parameter
provenance/domain analysis, the matmul acceptance test (§3), and the Phase 3
effects-unification idea. It should be read as the exploration that motivated
this note, not as the live design. token-types §4 already points here.

## References

- [`refinements.mml`](./refinements.mml): seed sketch (decisions D1–D5)
- [`token-types.md`](./token-types.md): provenance/brand model, §3 matmul gap, §4 refinement path
- [`simp-and-seq-typers.md`](./simp-and-seq-typers.md): NO SUBTYPING / row-polymorphism stance (grounds D3)
- Refinement/Liquid types literature (Liquid Haskell, F*, Rondon–Kawaguchi–Jhala)
