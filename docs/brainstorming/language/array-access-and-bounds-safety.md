# Array access and bounds safety

Status: **Needs review.**

This document discusses bounds checks, static bounds proofs, and validated indices.
It consolidates the refinement and token discussions, including path-dependent
indices. Octagon analysis and programmer-supplied proof hints remain candidates.

[Intrinsic arrays](intrinsic-arrays.md) are an independent proposal that can proceed
with runtime bounds checks. They do not depend on the analyses or type-system
extensions discussed here.

## Problem

We want safe array access with a clear cost model. Ordinary access must remain safe
when the compiler cannot prove an index valid. When it can establish validity, the
generated access should not repeat the check.

There are three cooperating parts to the proposed design:

| Part | Responsibility |
| --- | --- |
| Checked array operations | Define safe behavior, including the failure path. |
| Static bounds analysis | Infer facts that justify removing particular checks. |
| Refinements and validated indices | Express and preserve guarantees across interfaces. |

Ownership and effects determine how long those guarantees remain valid. A numeric
proof alone does not establish storage lifetime, permission to mutate, or absence
of aliasing.

## Bounds-check elimination

The compiler may omit a checked access's bounds test only when it establishes the
required conditions. Unknown facts leave the check in place. Check elimination must
preserve evaluation order and observable failure behavior. Moving a check earlier
needs its own justification, especially across effects or empty loops.

## Static bounds analysis

For an access to an array `a`, the central numeric obligation is:

```text
0 <= i < length(a)
```

The length fact must belong to that array at the access point. The compiler must
also know that storage is live and valid. A proof about another array's length,
or a length before an invalidating mutation, is insufficient.

### Intervals, zones, and octagons

Miné's octagon domain represents conjunctions of constraints `±x ±y <= c`, including
single-variable bounds. Variables may include an unknown array length; only `c` must
be constant. Zones handle difference constraints `x - y <= c`; octagons also retain
sums. Intervals track variables separately. The octagon representation uses quadratic
space and cubic worst-case operations in the number of tracked variables. Variable
packs limit the size of relational groups. See [Miné, 2006](https://mine.perso.lip6.fr/publi/article-mine-HOSC06.pdf).

The following are our applications of those constraint forms. Arithmetic here is
mathematical integer arithmetic; machine operations need the overflow treatment below.

```text
0 <= i < n       becomes       -i <= 0   and   i - n <= -1
```

Given `0 <= i`, `i <= j`, and `j < n`, combining `i - j <= 0` with
`j - n <= -1` proves `i < n`. If `n` is the valid length of `a`, this establishes
the numeric condition for `a[i]`. Zones already suffice for this example.

| Relationship | Direct octagon representation? |
| --- | --- |
| `0 <= i < n` | Yes. |
| `i + j <= 63` | Yes. |
| `i + j < n` | No: three independent variables. |
| `2*i + j <= 63` | No: coefficients outside this form. |
| `i*n + j < n*n` | No: multiplication of variables. |

Failure to fit directly does not rule out proving a particular instance through
constants, simplification, or another analysis. Introducing a temporary does not
automatically preserve the relation that defines it.

### Inferring and consuming facts

Abstract interpretation approximates possible executions with facts the analysis can
represent. A candidate bounds pass would refine facts at branches, transfer them
through operations, combine incoming paths, and approximate recursive or looping
control flow until analysis converges. Facts must hold on every path to an access.

Representing an invariant does not guarantee that the analysis discovers it. Joining
paths and widening for convergence can lose precision; narrowing may recover some.
[APRON's octagon documentation](https://antoinemine.github.io/Apron/doc/api/c/oct_doc.html)
describes these operations and their precision limits. The domain choice does not
itself choose the control-flow representation, recursion summaries, or analysis budget.

For MML, recursive functions and loopified tail calls need an explicit treatment.
We have not selected whether analysis runs before or after loopification, which
relations cross function boundaries, or which variables share a pack. An initial
comparison should establish what intervals or zones miss before selecting octagons.

Analysis results can justify a local rewrite that omits a check, as envisaged in the
[persistent compiler graph discussion](inir-brainstorming.md#analysis-and-parallel-work).
Record the array identity, index expression, relevant length/state, and dependencies
of each result. Rewrites, changed callee effects, or mutation must preserve or invalidate
those dependencies. This architecture does not require interaction nets or a persistent
graph before a first bounds pass can exist.

An inferred fact may also help check a refinement. Source-level contracts and the
algorithm that establishes them are separate choices. A general SMT solver is not
required for zones or octagons, and none is selected here.

### Note: programmer-supplied proof hints

Eduardo León's [Index safety with proofs](https://gist.github.com/eduardoleon/dfb47b0662d9eeaa3304bd0b53b4dbc1)
uses Hoare partitioning to explore bounds that depend on array contents: a sentinel
stops each scan before it leaves the array. Its invariants and short contradiction
arguments suggest checked proof hints as a possible fallback when inference fails.
Such hints would need verification, including whether mutation preserves the element
facts they use; numeric bounds analysis alone does not establish those facts.
This is a useful example to revisit, not a commitment to proof syntax or a verifier.

## Refinements and validated indices

### Refinement model

The seed design treats a refinement as a base type and predicate binder, for example
`Int { i | i <= 5 }`. A generic form refines `'T` using operators supplied by its
protocol constraints. These are proposed forms, not executable examples for today's
compiler.

The carried-forward decisions are:

- Literal ascriptions are checked statically: a literal satisfying the predicate is
  accepted; a false predicate is a compile error.
- The type name also denotes a validating constructor. Dynamic construction returns
  a `Maybe`/`Result` whose success case contains the refined value. The precise result
  API is open; an error-bearing result would explain failure.
- Static and dynamic evaluation share predicate semantics.
- Compatibility uses rows rather than nominal subtyping. Rows carry facts; a separate
  implication check must establish that the available predicates imply the required ones.
- Refinement carriers should erase to the base representation without boxing. That is
  a code-generation requirement, including generic uses, not an automatic consequence
  of having a phantom predicate.

An ordinary checked access retains its runtime check when proof fails. A validating
constructor returns failure. An explicit static ascription has its own diagnostic
contract. These outcomes should not be conflated; unknown-proof handling for general
ascriptions remains to be specified.

The earlier proposal favors a small curated implication procedure with runtime
revalidation where the API permits it. Simple range containment does not require
general symbolic arithmetic.

Scala's [refined](https://github.com/fthomas/refined) supplied the prior-art direction:
validation, implication rules, and separating predicate evidence from representation.
The MML proposal requires its own erasure guarantee. It cannot assume a library's
wrapper representation or a fixed numeric predicate records a particular runtime array.

### Path-dependent indices

The proposed `a.Idx` ties an index to the array path `a`. A checked constructor
establishes its range against `a.length`; successful construction permits repeated
access without repeated bounds tests. Path identity supplies provenance and the
refinement supplies range. Passing an index for `a` to a distinct array `b` must fail
unless an explicit rule establishes compatibility.

The representation target is a native integer. Access may reuse the validated index;
it does not consume its validity merely by reading or writing an element. Function
signatures should be able to relate an array parameter and its index parameter.
Path stability, alias equivalence, and substitution across calls remain type-system
work. The notation alone does not solve them.

A validator/worker split can perform a check outside repeated accesses and pass the
validated index to the worker. It does not automatically hoist arbitrary checks out
of loops. In particular, `idx + 1` is not automatically another `a.Idx`.

One candidate is `succ : a.Idx -> Option a.Idx`, combining advancement with its range
check. It still performs a runtime decision; reuse of its successful result avoids
additional checks at accesses. The primitive itself must handle overflow correctly.
This core use case does not need the general arithmetic-proof machinery below.

### Why the token design is historical

The token proposal separated two parameters: `Token 'Arr 'Idx` tied an array
identity/generation to an index domain, while `Index 'Idx` belonged to that domain.
Element updates preserved both. Resize or push consumed the old capability and
issued a fresh generation and domain, making old indices incompatible.

Its proposed generative scope needs rank-2 quantification or existential packaging.
The simple rank-1 callback signature in the old sketch cannot ensure fresh,
unforgeable brands. Independently branding two scopes for the same array also makes
their indices incompatible. The [typer direction](../typer/simp-and-seq-typers.md)
puts rank-N beyond the initial rank-1 work.

Branding identifies an index domain but does not prove arithmetic stays within it.
That limitation affects both `idx + 1` and matrix offsets. Path-dependent indices
with refinements are the preferred proposal carried forward here; their integration
still needs design and verification. They avoid requiring the particular rank-2
branding interface, not all type-system work.

The token note also proposed mutation capabilities carried by effect handlers, with
structural changes advancing the valid state. Preserve this as a future direction:
effects could express permissions and invalidation, but no handler API or state-threading
semantics is settled. Bounds validity is not itself permission to mutate.

## Arithmetic proofs and machine semantics

The arithmetic-proof tier targets computed offsets in matrix multiplication, FFTs,
and stencil kernels. The [matrix benchmarks](../../../benchmark/mat-mul.mml) include
reads at `i*n + k` and `k*n + j`, and writes at `i*n + j`.

Even with `0 <= i < n` and `0 <= j < n`, showing `0 <= i*n + j < n*n`
requires reasoning about variable multiplication. A direct octagon representation
cannot express this general relation. General nonlinear integer solving is not a
predictable default for the compiler.

Candidate approaches carried forward are:

- A checked 2D view or index abstraction that owns the flattening calculation.
- A trusted flattening rule, with nonnegative dimensions, coordinate bounds, storage
  extent, and no-overflow preconditions all established.
- Checked affine-index combinators that propagate a range under explicit restrictions.
- Length-indexed types or stronger solvers as further alternatives, with additional cost.

A 2D abstraction must still account for both coordinates and all arrays used by a
kernel. It does not make a changing inner-loop coordinate free by declaration.
The acceptance case is safe computed reads and writes without per-access checks in
the hot loop, verified in generated code. Retaining unsafe calls or validating every
computed offset does not meet that performance goal.

Every arithmetic proof must use the semantics of the resolved operation. An operator
spelled `+` is not evidence that mathematical addition laws apply. The earlier design
proposes separating numeric operations from opt-in algebra protocols, inspired by
[Spire](https://github.com/typelevel/spire), and initially trusting only curated prelude
laws. User numeric implementations remain runtime-validated unless sound static
semantics are supplied. Property tests can exercise laws but do not prove them for
all inputs; trusted laws remain part of the compiler's correctness assumptions.

Machine integers require either a proof that the relevant operations cannot overflow
or an analysis that soundly models their actual behavior. An LLVM `nsw` promise cannot
stand in for that proof. Floating-point rounding/NaN, saturation, and modular arithmetic
need distinct rules. Arbitrary-precision or checked numeric representations change the
cost model and do not remove allocation/address-size obligations.

The carried-forward v1 direction avoids a mandatory SMT dependency and uses bounded,
curated reasoning. Octagons are an additional candidate within that direction.
Neither selecting an algebra protocol nor adding a domain solves the nonlinear
matrix obligation by itself.

## Validity of bounds evidence

An index proof belongs to a particular array and length. Replacing an element without
changing the length preserves its numeric bounds, although it may invalidate facts
about that element's contents used by a proof.

For resizable containers, a refinement mentioning `a.length` cannot survive arbitrary
length changes. Consuming the old array and issuing a fresh path, or tracking a
structural generation, are candidates. Both must account for aliases and outstanding
indices; merely renaming a binding does not invalidate evidence elsewhere.

A valid index does not grant access to dead storage. Storage lifetime, element borrows,
and mutation permissions remain separate obligations of the ownership model.

## Open decisions and evaluation cases

The consolidation does not select an implementation. The remaining decisions are:

1. Validating-constructor result type, path identity/substitution, implication rules,
   unknown-proof diagnostics, and erasure through generic code.
2. Static-analysis placement, recursive summaries, convergence strategy, variable
   grouping, and whether zones suffice before introducing octagons.
3. Trusted operator semantics, overflow handling, and a sound matrix-flattening strategy.
4. Preservation and invalidation of bounds evidence across calls and structural mutation.

Evaluate these mechanisms separately before combining them:

| Case | What it should establish |
| --- | --- |
| Literal and guarded access | Correct bounds diagnostics/checks; simple proof elimination. |
| `0 <= i <= j < n` | Relational reasoning with an unknown length. |
| Sum constrained by a constant | Benefit beyond difference constraints. |
| Cursor advancement | Safe termination and explicit remaining advancement cost. |
| Validated index across calls | Provenance and range survive the intended interface. |
| Wrong array, aliases, resize | No reuse of invalid bounds evidence. |
| Empty arrays and integer limits | Failure behavior and overflow remain sound. |
| Matrix reads and writes | Computed-index safety and actual hot-loop check elimination. |
| Large functions and recursive groups | Bounded compilation cost and conservative fallback. |

Generated IR establishes whether checks remain; runtime checks and sanitizers exercise
behavior; benchmark timings measure the resulting program. None substitutes for all
the others. Preserve the existing [checked-matrix evidence](../../../benchmark/results/2026-09-16-safe/Readme.md)
and [October checked-access report](../../../benchmark/results/report-2026-10-04-aarch.md#cost-of-checked-array-access).
Those measurements include optimizer effects and are not isolated check-instruction costs.

## Sources and superseded discussions

- [Refinement types](../typer/refinement-types.md): deprecated proposal, including the
  folded-in path-dependent index discussion.
- [Token types](../typer/token-types.md): deprecated branding/provenance exploration.
- [Refinement seed](../typer/refinements.mml): deprecated syntax sketch. Its generic
  `chico` return annotation and specialized `IntChico` body do not agree; it is not
  an executable acceptance example.
- [INIR discussion](inir-brainstorming.md#analysis-and-parallel-work): broader compiler
  architecture; its array-bounds discussion is superseded here.
- [Index safety with proofs](https://www.reddit.com/r/ProgrammingLanguages/comments/1xmh6b4/index_safety_with_proofs/):
  the discussion that prompted examining octagons. The quoted Lain comparison with
  Rust is not verified benchmark evidence; no reproducible example was examined.
- [Miné, The Octagon Abstract Domain (2006)](https://doi.org/10.1007/s10990-006-8609-1):
  primary source for the numerical domain.
- [APRON octagon documentation](https://antoinemine.github.io/Apron/doc/api/c/oct_doc.html):
  implementation operations, precision, widening, and narrowing.

The language reference and benchmark reports remain current documentation and historical
evidence respectively. Their purpose differs from the design discussions deprecated here.
