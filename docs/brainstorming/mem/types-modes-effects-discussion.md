# Types, modes, and effects discussion

**Status: living design notes.** Work on this document to develop a better version of
[Memory Model Evolution](mem-evolution.md). It collects connections, corrections, and
questions for that revision; it does not replace the proposal or the
[current memory contract](../../memory-model.md).

MML reached its ownership and callable rules through concrete compiler problems before
the Author encountered OxCaml's modal type work. The close correspondence gives us a
formal vocabulary to investigate and a useful check on our design intuition. It does
not mean the two languages have identical memory models.

## A light introduction

Types describe what a value is. Modes describe how it may be used. Effects describe
what a computation does. The intended design combines all three in the rules for
using values and crossing effect boundaries.

A value can have type `String` while the compiler also tracks whether it may escape a
scope, whether references to it may be aliased, or whether a closure may be invoked
again. These facts need not introduce a different ordinary data type for each use.
They can be inferred and checked alongside types, including at function boundaries.

OxCaml distinguishes locality (`global` / `local`), uniqueness (`unique` / `aliased`),
and callable usage (`many` / `once`), among other axes. Modes have orderings: giving up
a guarantee can be safe where inventing a stronger guarantee would not be. The axes
also interact with types and with one another; they are not just independent flags.
See the [mode introduction](https://oxcaml.org/documentation/modes/intro/).

This suggests a direction for MML: make the usage contracts we already enforce
explicit in the compiler's model, while preserving borrow-by-default source code.
Whether programmers need additional annotations is a separate question.

## The connection already present in MML

[pap-ownership.mml](../../../mml/samples/pap-ownership.mml) is the starting example.
Its three cases separate capture ownership from invocation behavior:

| Case           | Stored value                 | Invocation                              | Lifetime constraint                          |
|----------------|------------------------------|-----------------------------------------|----------------------------------------------|
| Owning closure | Owned by the environment     | Borrows the capture; repeatable         | Captured resource lives with the environment |
| Borrowing PAP  | Borrowed from an outer owner | Borrows the payload; repeatable         | PAP cannot outlive that owner                |
| Consuming PAP  | Owned by the environment     | Transfers the payload; consumes the PAP | Uncalled PAP must destroy its payload        |

An owned environment can support repeated calls. A call becomes consuming when its
body transfers a stored resource, including through a return or nested move capture.
Moving the callable to another owner and consuming it by invocation are distinct
operations. Supplying a fresh argument to a remaining consuming parameter does not
by itself make the callable call-once.

OxCaml's `once` mode addresses the corresponding problem of invoking a closure that
consumes a captured unique value. Its
[uniqueness introduction](https://oxcaml.org/documentation/uniqueness/intro/)
explains this connection. For MML, the useful next step is to describe how these
contracts survive aliases, aggregate fields, returns, partial application, and
higher-order calls with one consistent set of rules.

## Distinctions the evolution should preserve

### Type capabilities and value usage

The proposed `Unique` protocol describes a type’s resource discipline. Every value of
that type must have an owner. It can be borrowed without transferring ownership.
`Drop` supplies cleanup when the type requires it; the owner is responsible for that
cleanup. `Clone` describes an available duplication operation. These capabilities
alone do not tell us which binding is responsible for cleanup or whether a particular
invocation consumes a closure.

### Decision: separate Unique, Drop, and Clone

`Unique` constrains ownership and forbids implicit duplication. It is a marker
protocol with no runtime method. `Drop` supplies a consuming cleanup operation;
`Clone` supplies a duplication operation that borrows the original. They describe
separate properties even when a type implements all three.

An opaque newtype around an `Int` can represent a one-use initialization permission.
Construction is controlled, and initialization consumes the token. The token can
move or be borrowed, but cannot be copied or cloned. Abandoning the permission
requires no cleanup: this type is `Unique`, without `Drop` or `Clone`.

An integer file handle, by contrast, is `Unique + Drop` without `Clone`: an unused
owner must close the file. The distinction is cleanup obligation, not allocation.
An owned string can implement all three capabilities.

Ownership and cleanup derive separately through aggregates. A unique member requires
ownership tracking; a member needing cleanup requires aggregate cleanup. An aggregate
containing only the permission token needs `Unique`, but neither `Drop` nor `Clone`.
An opaque newtype's hidden representation must not silently grant it cloning.

Separating the protocols still requires controlled ownership of cleanup obligations.
It does not permit freely copying file handles or duplicating destruction obligations.

### Questions to keep separate

Separate these questions in the revised proposal:

- **Type capabilities:** what requires cleanup, what can be copied freely, and what
  supports explicit cloning?
- **Value and binding contracts:** who owns this resource, what is borrowed from whom,
  and which operations remain permitted?
- **Callable contracts:** which parameters consume, what the result supplies, whether
  invocation consumes the environment, and what may escape?
- **Effect contracts:** which effects a computation performs, which combinations
  with value modes are permitted, and which effect boundaries a borrow may cross?
- **Representation:** where storage lives and what runtime cleanup mechanism it uses.

These categories constrain one another. They need not all become public syntax, nor
must their implementation occupy separate compiler passes.

Use *affine* for at-most-once consumption. An unused owner runs cleanup when `Drop`
applies; a value without a cleanup obligation can simply expire. Reserve an
exactly-once obligation for a separate decision. `Unique` without `Drop` does not
require the value to be used.

### MML's `Unique` is not OxCaml's `unique`

MML's proposal makes `Unique` a persistent type capability for affine ownership,
with cleanup expressed separately by `Drop`. OxCaml's `unique` is an aliasing
guarantee about a value. MML permits temporary borrowed aliases while retaining
one owner; one owner does not mean only one reference exists at every instant.

MML also has a stricter default resource discipline. OxCaml can relinquish uniqueness
and rely on the GC. MML must preserve or transfer cleanup responsibility. The proposed
sharing operation introduces a runtime cell and release obligations; it cannot be
treated as merely forgetting a static guarantee. See OxCaml's
[uniqueness reference](https://oxcaml.org/documentation/uniqueness/reference/).

Shared handles carry cleanup responsibility for a payload with multiple holders.
Each owned handle needs release. An aggregate containing an owned shared handle needs
cleanup even when the cell contains a value without `Drop`. The handle's release
obligation is separate from payload cleanup. Specify how the shared ownership form
and its cleanup appear in capability rows and function contracts.

### Borrowing needs an owner relationship

The owner manages storage and retains cleanup responsibility while lending access.
Sequential borrows may use aliases within that ownership relationship. The compiler
tracks which owner keeps a borrow valid and when ownership may move or end. Locality
provides a useful vocabulary for expressing the corresponding escape restrictions.

Keep escape permission separate from physical allocation. A heap-allocated environment
can still contain a borrow that must not escape; owning the environment storage does
not extend the borrowed payload's lifetime. The current stack/heap closure choices
implement MML's rules but should not be their entire semantic definition.

OxCaml's [borrowing documentation](https://oxcaml.org/documentation/uniqueness/borrow/)
is useful here: it describes borrow regions, restrictions on unique use during a
borrow, and limitations around closure capture and locality. Check its current status
when using examples. In particular, `global` is escape permission, not an equivalent
of MML's move-capture syntax.

## Corrections to resolve before revising the evolution

### State the return contract before removing inference

Declaring that every resource result supplies ownership can simplify function
interfaces. That is a language contract to specify and enforce, including for native
functions, globals, and callable results. A `Unique` capability alone does not make
every result newly allocated, nor does it describe a returned closure's invocation
contract.

Identify which analyses become unnecessary under that contract and which facts still
need inference. Moving checks into the type system does not remove control-flow joins,
borrow provenance, partial consumption, or cleanup placement.

### Decide what happens to conditional ownership

The proposal promises preserved behavior and identical generated code while also
proposing to replace mixed ownership with cloning or rejection. Those promises cannot
all hold where the current model accepts a mixed branch and conditionally cleans up
its result without cloning.

For each such boundary, decide whether to retain the distinction, normalize ownership
with a stated cost, or restrict accepted programs. A modal description can expose and
check the distinction; it does not automatically eliminate its runtime representation.
Also distinguish mixed result ownership from a resource consumed on only one branch.

### Make duplication rules internally consistent

The proposal describes duplication as explicit, with literal/global exceptions, but
also suggests cloning a consumed argument that is needed later. Settle that boundary:
does the programmer request the clone, or does the compiler insert it?

Aggregate clone derivation accepts both freely copyable members and members with
`Clone`; opaque newtypes such as the permission token do not inherit cloning from
their hidden representation. Specify whether cloning a shared handle means cloning
its payload or retaining the cell; those have different results and costs.

### Define the interaction between types, modes, and effects

A sequential call may mutate a value through an alias, and its caller may observe
that change afterward. This is ordinary mutation behavior. Borrowing preserves the
ownership relationship; it does not by itself promise that the value stays unchanged.

Mutation is a first-class tracked effect. Types, modes, and effects jointly
determine which combinations are legal, such as whether mutation is
permitted through a borrow. Async boundaries, suspension, and continuation resumption
also have effects and corresponding modal rules. These contracts give precise meaning
to permitted access and passage across effect boundaries.

The [effect-system notes](../effects/mml_effects_brainstorming-1.md) propose `Mut` as
mutation permission. The specific rules for combining effects with types and modes
remain open design work. Sequential borrowing is the foundation on which those rules
are defined. Representation and optimization follow the language contracts.
OxCaml's
[parallelism introduction](https://oxcaml.org/documentation/tutorials/01-intro-to-parallelism-part-1/)
provides a useful comparison through contention and portability modes.

## How to use this launchpad

Start with the three PAP cases and write their ownership, borrowing, invocation, and
cleanup rules without choosing new syntax. Extend those rules through fields,
higher-order parameters, returned callables, and branch joins. Use an integer file
handle, a permission token without cleanup, and an aggregate containing a shared
handle to test the separate `Unique`, `Drop`, and `Clone` rules.

Then revisit the claims in `mem-evolution.md`: separate preserved behavior from intended
changes, identify runtime distinctions that remain necessary, and record which rules
are type capabilities, value modes, effect contracts, or representation choices.
Resolve questions here before folding the resulting decisions into the evolution
proposal. Adoption of a full OxCaml-style mode system remains a design option, not a
settled requirement.

## Reading path

- [Oxidizing OCaml with Modal Memory Management](https://homepages.inf.ed.ac.uk/slindley/papers/mode-inference.pdf)
  — Anton Lorenzen, Leo White, Stephen Dolan, Richard A. Eisenberg, and Sam Lindley;
  ICFP 2024, [DOI](https://doi.org/10.1145/3674642). Sections 2 and 6 introduce the
  programming model and borrowing; sections 3–5 develop inference and the formal account.
- [Modes: introduction](https://oxcaml.org/documentation/modes/intro/) — start here for
  the axes and their meanings.
- [Uniqueness: introduction](https://oxcaml.org/documentation/uniqueness/intro/) and
  [borrowing](https://oxcaml.org/documentation/uniqueness/borrow/) — closest to the PAP
  example and MML's default lending behavior.
- [Modes: reference](https://oxcaml.org/documentation/modes/reference/) and
  [syntax](https://oxcaml.org/documentation/modes/syntax/) — consult when turning the
  analogy into precise rules, especially for propagation through fields and signatures.

Documentation consulted on 2026-09-17. The paper, current implementation, and evolving
documentation may describe different stages of the design; a conceptual translation
of an MML example is not evidence that the corresponding OxCaml program compiles.

## Appendix: borrowing, modes, and effect boundaries

Sequential borrowing is a foundation of MML's memory model. The owner manages
storage and retains cleanup responsibility; borrowers receive access bounded by
that ownership.

Types, modes, and effects jointly determine which uses are valid, including which
effects may occur during a borrow and which effect boundaries a borrow may cross.
Restrictions such as prohibiting borrowed mutation or carrying a borrow across an
async boundary express this principle. Suspension and continuation resumption are
governed by the same interaction between types, modes, and effects.

### Candidate: noalias

A separate `noalias` mode would require one live reference. Creating an alias with
`let b = a` while leaving `a` usable would be forbidden. Ordinary borrowing would
also be forbidden because it creates another reference.

Moving the reference remains valid: `b` takes it and `a` becomes unavailable.
Loops and recursion can move the reference onward, returning it if the caller needs
it again. Normal ownership permits one owner with borrowed aliases; `noalias` adds
the stronger requirement of one live reference.

Whether MML needs this mode depends on the operations and effect contracts it chooses.
Its scope through fields, reachable values, and higher-order calls belongs to that
design. Ordinary sequential borrowing remains valid without requiring this mode.

### Proposed rule: borrows cannot cross async boundaries

A likely rule is to require every borrow to end before crossing an async effect
boundary. Borrowing before the boundary remains valid if the borrow ends first;
keeping a borrow live across it would be rejected. Closures, PAPs, and views carrying
borrows would inherit this restriction.

A computation that needs a resource beyond the boundary would need ownership, an
explicit clone, or a shared handle permitted by the relevant transfer and access
rules.

The effect contract of a higher-order call must account for effects performed by its
callbacks. The same boundary rule then applies to direct and higher-order calls.
Mutation has its own mode-effect rules; whether a borrowed mutation is permitted is
a separate part of that contract.

This proposed policy favors simple boundary contracts. Define exactly which effects
create such boundaries, whether the restriction covers unrelated live borrows, and
how borrow endings are inferred.

### Shared references across async boundaries

Shared references provide the explicit alternative when work on both sides of an
async boundary needs continued access. Each side owns a handle; the resource remains
alive until the last handle is released, independently of the original owner's scope.
This gives the borrowing restriction a practical counterpart without requiring
lifetime annotations or a clone of the payload:

- Borrow for synchronous access bounded by the owner's lifetime.
- Move ownership for an exclusive handoff to async work.
- Share explicitly with `&` when both sides need continued access.

Each handle supplies an ownership relationship that keeps the payload alive. The
payload's permitted uses are governed by its type, mode, and the computation's tracked
effects, including mutation and any overlapping execution.

Implementation choices follow those contracts. Atomic reference-count updates may
implement handle bookkeeping; atomic payload operations, including compare-and-swap
(CAS), are a separate candidate for operations with defined atomicity and retry
semantics. Suspension and parallel execution have different effects and may call for
different representations. The representation for each permitted combination remains
an implementation choice to specify.

## Appendix: shared-handle ownership and transparent access

**Status: pending further elaboration.** Consider requiring explicit `&` when entering
shared ownership, while ordinary bindings and ownership transfers maintain the
reference count afterward. Requiring another `&` for every additional handle may be
too verbose.

### Candidate rules derived from the examples

1. **Entering sharing is explicit.** For a uniquely owned resource, `&a` moves the
   value into a fresh shared cell and produces its first owned handle. The original
   binding becomes unavailable. Applying `&` directly to construction has the same
   ownership effect without an intermediate binding.
2. **Binding an existing shared value acquires a handle.** `let b = shared` retains
   another owned reference to the same cell. The original handle remains usable;
   the payload is not cloned. Binding a uniquely owned resource instead moves it.
3. **Ordinary parameters borrow.** Passing a shared value to a non-consuming parameter
   lends access through its handle. The caller retains cleanup responsibility; the
   call itself requires no retain or release.
4. **Field access is transparent.** `b.field` grants ordinary borrowed access to the
   payload's field without `.value` or reference-count operations merely to read it.
   Acquiring an independently owned resource from that field is a separate operation.
5. **Consuming parameters receive cleanup responsibility.** Where the parameter's
   contract accepts sharing, passing a shared argument gives the callee an owned
   handle, retaining it when the caller keeps its handle. Passing a unique argument
   moves ownership. The parameter's type, mode, and effect contract determines the
   permitted payload operations.
6. **Drop follows the ownership form.** An untransferred unique owner runs cleanup
   at scope end when its type implements `Drop`; `Unique` alone requires no call.
   An untransferred shared handle releases one reference. The last release drops
   the payload if it implements `Drop` and frees the cell. Borrowers do neither.
7. **Reference-count elision preserves those semantics.** The compiler may transfer
   a handle at its last use or eliminate redundant retain/release pairs when it proves
   the resource remains alive. Escape analysis can inform that proof; non-escape
   alone does not establish it.

These are candidate semantics inferred from the examples. The parameter contracts
needed to support them remain open, as described below.

### Commented examples

The intended surface behavior is illustrated below. This is discussion syntax, not
a claim about the current parser or an agreed function-signature design:

```text
// Assume Struct owns resources and therefore moves rather than copying freely.
let a = Struct{ fields ... };;
let shared = &a;                       // move into a fresh cell; one owned handle
                                      // a is unavailable; shared owns a release

// Equivalent when the intermediate binding is unnecessary:
// This line is an alternative to the two bindings above.
let shared = &Struct{ fields ... };;

fn takes(~s: Struct) = ???;;            // owns its argument; sharing contract is TBD
                                      // if shared and not transferred onward:
                                      // release s at scope end
fn borrows(s: Struct) = ???;;           // borrows through the shared handle
                                      // no retain/release for this borrowed access

let b = shared;                        // another owned handle; conceptually retain
                                      // shared remains usable; no payload clone
println b.field;                       // borrows through the cell transparently
                                      // no .value, retain, or release for the read
borrows shared;                        // borrow; no new owned handle
takes shared;                          // callee receives an owned shared handle
                                      // retain if caller keeps its handle;
                                      // transfer may suffice at caller's last use
                                      // callee releases unless it transfers onward

// Scope end releases b and any shared handle still owned here.
// The final release destroys the payload and frees the cell.
// Redundant retain/release pairs may be omitted when lifetime is proven.
```

Field access would be transparent: no `.value` projection or source-level calls to
retain and release. Borrowing grants access to the payload through a live handle
without acquiring another owned reference. Each owned handle has a release obligation;
the cell owns the payload and destroys it when the last handle is released.

### Syntax and cost visibility

The evolution proposal currently uses `let b = &shared` to request another handle.
This alternative would allow `let b = shared`, with retention implied by acquiring
shared ownership. The initial conversion still uses `&a`, or `&Struct{ ... }` for a
fresh value. Decide whether `&shared` remains an explicit spelling for retention.

This changes the cost-visibility boundary: entering sharing is explicit, while later
ownership operations on shared values can imply retain/release. Payload cloning stays
a separate operation. Resolve this difference before updating the original proposal.

Define additional owned handles semantically before optimizing reference counts.
The compiler may omit a retain/release pair when another handle provably keeps the
cell alive throughout the additional handle's use. Non-escape alone is insufficient
if that other handle can be released or transferred earlier. At a last use, handing
the existing handle to a consuming callee may avoid a retain entirely.

### Owning a handle is not exclusive ownership of the payload

For `takes shared` to work as sketched, `~s` must accept ownership of a shared handle.
It cannot promise exclusive ownership of the underlying `Struct`. Dropping this
argument releases a handle; a uniquely owned argument needs a cleanup call only
when its type implements `Drop`.

The compiler must preserve that distinction even if both forms display the ordinary
type `Struct`. A callee cannot move a resource field out of a shared payload merely
because its parameter is consuming. Mutation permissions belong to the combined type,
mode, and effect contract described above.

Determine whether such functions are polymorphic over ownership forms, whether the
sharing capability appears in their signatures, and how a function requires exclusive
ownership when necessary. The plain `Struct` annotation above leaves those questions
open.
