# Modal reasoning for MML memory evolution

**Status: living design notes.** Work on this document to develop a better version of
[Memory Model Evolution](mem-evolution.md). It collects connections, corrections, and
questions for that revision; it does not replace the proposal or the
[current memory contract](../../memory-model.md).

MML reached its ownership and callable rules through concrete compiler problems before
the Author encountered OxCaml's modal type work. The close correspondence gives us a
formal vocabulary to investigate and a useful check on our design intuition. It does
not mean the two languages have identical memory models.

## A light introduction

Types describe what a value is. Modes describe how it may be used.

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

Shared handles illustrate why cleanup and exclusive access to the payload must
remain distinct: each owned handle needs release even though the payload has multiple
holders. An aggregate containing an owned shared handle needs cleanup even when the
cell contains a value without `Drop`. The handle's release obligation is separate
from payload cleanup. Specify how the shared ownership form and its cleanup appear
in capability rows and function contracts.

### Borrowing needs an owner relationship

Locality provides a useful vocabulary for escape restrictions. MML still needs to
know which owner keeps a borrow valid and when moving or destroying that owner is
permitted. Being in the same lexical scope is insufficient by itself.

Keep escape permission separate from physical allocation. A heap-allocated environment
can still contain a borrow that must not escape; owning the environment storage does
not extend the borrowed payload's lifetime. The current stack/heap closure choices
implement MML's rules but should not be their entire semantic definition.

OxCaml's [borrowing documentation](https://oxcaml.org/documentation/uniqueness/borrow/)
is useful here: it describes borrow regions, restrictions on unique use during a
borrow, and limitations around closure capture and locality. Check its current status
when using examples. In particular, `global` is escape permission, not an equivalent
of MML's move-capture syntax. Repeated borrowing of owned captures also does not by
itself establish a non-reentrancy restriction in MML.

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

### Keep effects and access guarantees distinct

Effects can describe computation, while modes can constrain value usage. They can
inform one another without being interchangeable.

An async boundary does not necessarily introduce parallel access. Atomic reference
counting protects the count, not arbitrary mutation of the payload. Neither `Send`
alone nor the presence of async establishes that shared mutation is safe, and CAS
cannot make every operation safe automatically. Define transfer, simultaneous access,
and synchronization requirements before selecting a representation. OxCaml's
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
are type capabilities, value modes, or representation choices. Resolve questions here
before folding the resulting decisions into the evolution proposal. Adoption of a full
OxCaml-style mode system remains a design option, not a settled requirement.

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

## Appendix: sequential borrowing and temporal exclusivity

Without concurrency, accesses through different aliases execute sequentially. This
suggests a useful intuition: a borrow may give its user temporary control of access
even though other references exist. Multiple read-only aliases need not undermine
ownership or introduce runtime ownership tracking, provided the owner remains alive.

There are three distinct guarantees to keep apart:

| Guarantee                    | Meaning                                         |
|------------------------------|-------------------------------------------------|
| Single cleanup owner         | One owner is responsible for destruction        |
| Sequential execution         | Accesses do not execute concurrently            |
| Exclusive access over a span | Other aliases cannot interfere during that span |

MML's ownership discipline supplies the first. Excluding concurrency supplies the
second. The third requires restrictions or proof about aliases and intervening calls.
Sequential execution alone does not make a borrow unique in the aliasing sense.

Consider a possible interaction involving a borrowed view and a callback:

1. A function borrows a value and retains a view into its storage.
2. It invokes a callback before finishing with the view.
3. The callback reaches the value through another alias and changes or invalidates
   the storage.
4. The function resumes using the retained view.

Every step is sequential. Safety depends on whether the callback's operations are
permitted while the view is live. This is a design example, not a claim that MML
currently accepts such a program. Nested calls and reentrancy can create interference
without threads; mutation that preserves the view may be harmless, while freeing or
invalidating its storage is not.

For the evolution, describe a borrow as preserving the owner's ownership while
granting bounded access. Investigate when that access can also be proven exclusive
for a particular operation or interval. Such a proof could allow stronger operations
or optimizations without requiring that every other reference cease to exist.

The question to resolve is which operations require only a live owner, which require
non-interference during a borrow, and how those requirements survive callbacks and
higher-order calls. Absence of concurrency is useful evidence, but not the entire
proof of exclusivity.

### Candidate: noalias and exclusive borrowing

A separate `noalias` mode could express a stronger access guarantee than MML's
`Unique` ownership discipline. Its strict form would require a single live
reference, with no other aliases. This would still permit loops and recursion
that move the reference onward, returning it if the caller needs it again.

A more permissive form could allow sequential, exclusive borrows: other references
may remain, but only the current borrower has a usable access path during the
borrow. The owner retains cleanup responsibility while its access is suspended.
A loop could finish one borrow before starting the next. A recursive call could
reborrow, suspending the outer borrow until the inner one ends.

This relaxation requires proof of exclusive access over the whole borrow interval,
including intervening calls. A callback reaching the same value through a captured
alias would violate that contract even without concurrency. Merely observing that
individual accesses execute one after another is insufficient.

OxCaml's [borrowing rules](https://oxcaml.org/documentation/uniqueness/borrow/)
temporarily permit `aliased`, `local` access and restore unique use after the
region ends, subject to their restrictions. Multiple borrows may coexist in that
region. This is useful precedent for recovering a guarantee after borrowing,
but does not establish exclusive borrowed access for MML.

Keep literal absence of aliases distinct from temporary exclusivity. Decide which
operations accept each guarantee, whether it extends through fields and reachable
values, and how it is preserved through higher-order calls. `noalias` is a candidate
mode, not an additional meaning of `Unique` or a settled implementation requirement.

Future non-sequential access should interact with the effect system. Effects could
identify where execution may suspend, spawn work, or permit overlapping access;
ownership and borrow contracts would constrain the values available across those
boundaries. This could let the compiler preserve simpler access rules where it proves
execution remains sequential and require stronger guarantees where access may overlap.
The effect rules must distinguish suspension from parallel execution and account for
effects propagated through callbacks. The exact contracts and their consequences for
synchronization and reference-count representation remain to be designed.

### Proposed rule: borrows cannot cross async boundaries

A likely rule is to require every borrow to end before crossing an async effect
boundary. Borrowing before the boundary remains valid if the borrow ends first;
keeping a borrow live across it would be rejected. Closures, PAPs, and views carrying
borrows would inherit this restriction.

A computation that needs a resource beyond the boundary would need ownership, an
explicit clone, or a shared handle permitted by the relevant transfer and access
rules. A shared handle alone would not authorize mutation of its payload.

This would particularly constrain borrowing combined with mutation effects: borrowed
mutation must finish, and the borrow must end, before yielding or handing work off
across the boundary. Effects must propagate through higher-order calls so a callback
cannot conceal an async boundary from a caller holding a live borrow.

This is a proposed conservative policy, not an implemented rule. It could reject
programs whose safety a more elaborate lifetime system could prove, in exchange for
simpler contracts. Define exactly which effects create such boundaries, whether the
restriction covers unrelated live borrows, and how borrow endings are inferred.
Synchronous callback interference still needs its own rules.

### Shared references across async boundaries

Shared references provide the explicit alternative when work on both sides of an
async boundary needs continued access. Each side owns a handle; the resource remains
alive until the last handle is released, independently of the original owner's scope.
This gives the borrowing restriction a practical counterpart without requiring
lifetime annotations or a clone of the payload:

- Borrow for synchronous access bounded by the owner's lifetime.
- Move ownership for an exclusive handoff to async work.
- Share explicitly with `&` when both sides need continued access.

Shared mutation would remain subject to effect-based access restrictions. Sharing
extends the resource's lifetime; it does not grant unrestricted mutation. The effect
system should establish where access can overlap and which operations are permitted
there, including effects propagated through callbacks.

One possible implementation direction is to lower supported operations on references
shared across async boundaries to atomic operations, including compare-and-swap (CAS).
This would connect effect and sharing information to representation and operation
selection. Async alone would not require CAS if access remains sequential.

Distinguish atomic reference-count updates, which protect lifetime bookkeeping, from
atomic payload operations, which coordinate access to the shared value. CAS-based
payload updates need a defined atomic unit and retry semantics. A compiler cannot
safely retry arbitrary effectful update code or make a multi-step invariant atomic
merely by replacing individual writes with CAS. Specify which operations admit this
lowering and which require another synchronization mechanism or remain disallowed.

These are directions for the effect and shared-reference design, not settled lowering
rules. They let MML keep the source-level sharing decision explicit while using proven
access constraints to select its runtime implementation.

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
   moves ownership. `~` alone must not imply exclusive access to a shared payload.
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
type `Struct`. A callee cannot move a resource field out of a shared payload or assume
exclusive mutation merely because its parameter is consuming. Existing effects and
access requirements still apply.

Determine whether such functions are polymorphic over ownership forms, whether the
sharing capability appears in their signatures, and how a function requires exclusive
ownership when necessary. The plain `Struct` annotation above leaves those questions
open; it does not authorize silently treating a shared payload as uniquely owned.
