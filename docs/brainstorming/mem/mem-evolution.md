# Memory Model Evolution

## Status: brainstorming

See [Types, modes, and effects discussion](types-modes-effects-discussion.md), a living companion
document to work on toward a more precise revision of this proposal.

---

## Motivation

MML's current memory model works. The ownership analyzer tracks bindings through
the AST, inserts free and clone calls, and catches use-after-move errors at compile
time.

But it conflates three concerns that should be independent:

1. **Affine ownership** — must this value be consumed at most once?
2. **Resource cleanup** — does this value need destruction?
3. **Heap allocation** — does this value live on the heap?

Today, `isStructWithHeapFields` is the single predicate that answers all three
questions. A type is tracked by the ownership analyzer if and only if it has heap
fields. That works because every heap type currently needs cleanup and every cleanup
type is currently heap-allocated. But this collapses distinctions that matter:

- A file descriptor is an integer (not heap) but needs cleanup and must not be
  duplicated.
- A one-use permission token can be an opaque newtype around an integer. It must
  not be duplicated, but abandoning the permission requires no cleanup.
- An array of floats owns its elements regardless of its storage strategy.
  Sharing storage does not permit implicit duplication of the array.
- A struct wrapping only primitive fields needs no ownership tracking today, but
  if it holds a unique resource handle, it should.

The ownership analyzer also carries responsibilities that belong in the type system.
`ReturnOwnershipAnalysis` infers which functions return owned values via a fixed-point
loop — but the return type should declare this. The `__owns_x` witness booleans
encode conditional ownership as runtime flags — but if the type system enforced that
conditional branches agree on ownership, witnesses would be unnecessary. The
`memEffect` annotations on native functions are metadata about the implementation
living outside the type — but ownership is a property of the value, not the function
that produced it.

The right fix is to move the load-bearing invariants into the type system
where the compiler can enforce them structurally.

This document describes a layered evolution plan. Each layer adds a capability,
each is independently useful, and the later layers simplify what came before.

---

## Core principle: borrow by default, move explicitly

MML borrows by default and moves explicitly (via `~` on the parameter). This is
the inverse of Rust, where values move by default and borrowing requires `&`.

Most functions just take arguments, use them, and return. The caller never loses
ownership. No annotation needed. `~` is the exception, not the rule.

This must be preserved through the evolution. The layers below add structure
(Drop/Clone protocols, type rows, shared refs) but do not change the
default: if a function doesn't say `~`, it borrows.

---

## Goal: preserve existing semantics

The current memory model (`docs/memory-model.md`) describes the behavioral
contract: scope-end cleanup, single ownership, borrow by default, consuming
parameters, conditional ownership, struct move semantics, borrow-escape
prevention. These semantics are correct and must be preserved.

The evolution changes *where* the compiler enforces them (type checker vs
separate analyzer pass -- though the type checker already does its own form of
flow analysis) and *how* cleanup/cloning are expressed (protocols vs generated
`__free_T`/`__clone_T` functions). Existing implicit-clone boundaries need
explicit migration under the scalar-only implicit-duplication rule.

The memory test suite (`mml/samples/mem/`) validates these invariants: zero
leaks, no double-free, no use-after-move, correct conditional cleanup, correct
struct destruction. Preserve these guarantees through the evolution; update
source examples where explicit cloning becomes required.

---

## Decisions

- Ownership is the base rule. Each owned value has one owner.
- Moves transfer ownership. Borrows leave ownership with the owner.
- Only scalars may be implicitly duplicated. Scalar-only aggregates are still aggregates.
- `Drop` consumes an owned value and performs its cleanup. Cleanup may be empty.
- `Clone` borrows a value and produces a separate owned value. Duplication is explicit.
- No separate `Unique` marker is needed. Ownership does not imply `noalias`.

| Example | Protocols | Drop behavior |
|---------|-----------|---------------|
| Permission token | `Drop`, no `Clone` | Consume; no runtime cleanup |
| File handle | `Drop`, no `Clone` | Close the file |
| String | `Drop + Clone` | Free its storage |

An opaque token's integer representation does not make it implicitly copyable
or grant it `Clone`. Its drop operation can simply be:

```mml
instance Drop for Token =
  fn drop(~s: Token): Unit = ();;
;
```

The compiler may eliminate a no-op drop while retaining ownership checks.

---

## Layer 1: Drop protocol

### The protocol

Proposal syntax:

```mml
protocol Drop for T =
  fn drop(~self: T): Unit
;
```

`drop` has the contract `~T -> Unit`. At scope end, the compiler drops owned
values that have not been transferred or consumed.

### Ownership and borrowing

- `fn println(s: String): Unit` borrows. The caller keeps ownership.
- `fn consume(~s: String): Unit` takes ownership and its cleanup responsibility.
- `fn readline(): String` returns an owned value to the caller.

The type remains `String`. The binding's ownership state determines who may
move or destroy the value. Borrows must remain valid while used.

### Native types

Native resource types implement `Drop` explicitly:

```mml
implement Drop for String =
  fn drop(~self: String): Unit = free_string self;;
;
```

The implementation names the cleanup operation, replacing the
`[mem=heap, free=...]` cleanup contract. Allocation and representation remain
separate concerns.

### Aggregates

- Aggregates own their elements, including aggregates containing only scalars.
- Derived `Drop` drops owned members and releases any container storage.
- A scalar-only inline aggregate can have a no-op drop.
- Custom `Drop` implementations must satisfy the members' cleanup obligations.
- Opaque native types declare their cleanup contract explicitly.

### Intended simplifications

- Replace heap-field predicates for ownership with ownership rules.
- Replace generated `__free_T` contracts with `Drop` implementations.
- Express owned results in signatures so callers need no body inspection.
- Remove return-ownership inference only once signatures carry that contract.
- Remove conditional witnesses only where branch ownership is statically established.
  A branch needing duplication must explicitly clone or fail checking.

`Drop` alone does not establish a function's result ownership or allocation effect.
The ownership analyzer still tracks moves, borrows, and cleanup responsibility.
Default parameters borrow; `~` parameters and constructors consume owned arguments.

---

## Layer 2: Clone protocol

```mml
protocol Clone for T =
  op ^(self: T): T;
;
```

- `drop(~self)` consumes. `^self` borrows and returns another owned value.
- `Clone` declares the `^` operator.
- Passing a value to a consuming parameter moves it. Later use does not trigger a clone.
- Non-scalar duplication requires `Clone`, including duplication of literals and globals.
- Native types supply their clone implementation explicitly.

### Aggregate derivation

Derive `Clone` only when every member is scalar or implements `Clone`.
Copy scalar members; invoke `Clone` for the others. Derivation does not permit
implicit duplication of the aggregate.

A struct containing a file handle or token without `Clone` cannot derive it.
Opaque newtypes expose only their declared capabilities; a hidden scalar
representation does not grant `Clone`.

---

## Layer 3: Shared references and opt-in reference counting

The default is affine ownership: one owner, deterministic cleanup when `Drop`
applies, no runtime ownership cost. That is right for most code, but not all of it.
Some values are genuinely shared between holders that have no single best owner — an interned
string pool, a texture used by many sprites, a config record read from many
places. Separate owned copies may waste work.

For those cases MML adds a shared-reference type `&T` (the `Shared` row
capability on `T`) and two operators. Reference counting is opt-in. You ask for
it at the use site; nothing in the language pushes it on you by default.

See `docs/brainstorming/mem/shared-refs.md` for the longer rationale.

### Operators

| Op | Input | Result | Effect | Requires |
|----|-------|--------|--------|----------|
| `&` | Owned non-scalar `T` | `&T` | Move into a fresh cell; rc = 1 | — |
| `&` | Scalar `T` | `&T` | Copy into a fresh cell; rc = 1 | — |
| `&` | `&T` | `&T` | Retain another handle; rc++ | — |
| `^` | Borrowed `T` | `T` | Clone; original stays valid | `T: Clone` |
| `^` | Borrowed `&T` | `T` | Clone the payload; handle stays valid | `T: Clone` |

`&` transfers ownership into shared storage or explicitly retains a shared handle.
`^` duplicates the payload through `Clone`. Neither operation happens implicitly.

### `&` is a primitive, `^` is a protocol operator

The two operators are not symmetric in implementation, only in feel.

`&` is a **compiler primitive**. The type checker has to understand that `&`
consumes an owned value, copies a scalar, or explicitly retains an `&T`,
and it has to track `&T`
through the `Shared` row. Codegen has to emit the retain on `&` and the release
at scope end. None of that can be expressed as a user-level protocol method,
because none of it is a function call: it is typing rules plus IR emission.
There is no `Share` protocol with a `share(self: T): &T` method; `&` lives in
the language, not in user space.

`^` is the operator declared by `Clone`. Implementations may be derived or
user-defined; dispatch is monomorphised.

```mml
let a = readline();  // a: owned String
let b = &a;          // a moved; b: &String (rc = 1)
println a;           // error: use after move
let c = &b;          // alias; rc = 2
let x = ^b;          // deep copy; x: String, b still &String
println c ++ b;      // both shared handles still live
                     // scope end: c and b drop, rc hits 0, inner String drops
```

### `Shared` as a row capability

`Shared` participates in the row alongside `Drop` and `Clone`. Each handle
is owned and releases one reference when dropped. At count zero, the payload
is dropped and the cell freed. A scalar or no-op-drop payload still requires
release of the cell. Shared handles are not implicitly duplicated.

### Resources: Drop without Clone

A texture can be shared without duplicating the resource:

```mml
let t  = load_texture "wall.png"; // owned Texture
let t2 = ^t;                      // error: Texture has no Clone
let s  = &t;                      // move into shared storage; rc = 1
let s2 = &s;                      // retain another handle; rc = 2
let u  = ^s;                      // error: Texture has no Clone
                                  // last handle dropped calls unload_texture
```

`&` does not require `Clone` on the payload. Each handle owns a reference;
the shared cell owns the texture.

### Why opt-in, not ambient

Refcounting is a real runtime cost and a real shift in the ownership story.
Roc, Koka, Lean 4, and Swift refcount by default and lean on optimizer-driven
uniqueness inference to elide bumps where they can. That is convenient, but it
hides the cost and ties the language to whatever the optimizer can prove on a
given day.

MML inverts that. Affine is the default. Affine has no runtime ownership cost.
When you write `&`, the program pays for sharing in source where the reader can
see it. The model stays small and the compiler stays honest.

Atomic refcounting fits on the same machinery. A `&T` that also carries
`Send` would compile to atomic increments; a non-`Send` `Shared` stays
non-atomic. No new sigil, no new wrapper type.

### What this eliminates

- The need for a separate sharing mechanism bolted on later. `Shared` joins the
  row alongside `Drop` and `Clone` rather than living in user-space as a
  generic wrapper.
- The need to clone a resource for independently owned holders.
  Resources with `Drop` and no `Clone` can use shared handles.

### Open questions

- Whether `&T` surfaces in error messages and inference as a wrapper head or as
  a row capability on `T`. The row framing is cleaner for inference, the
  wrapper framing is clearer in diagnostics. Probably both, with the row as the
  canonical form.
- `^` on a deeply nested `Shared` graph is a deep copy. For nested shared
  structures, that may not be what the user wanted. A lint, or a finer variant
  of `^` that stops at `Shared` boundaries, may be worth the complexity. Worth
  naming even if the answer is no.
- Cycle detection. Reference counting cannot collect cycles. The current type
  system makes cycles hard to construct (no mutation through `Shared`, no
  `RefCell` analog) but the property should be stated, not implied.

---

## Summary

| Concern | Proposed rule |
|---------|---------------|
| Ownership | One owner; moves transfer responsibility |
| Borrowing | Owner retains responsibility; borrow must remain valid |
| Destruction | Consuming `Drop`, possibly a no-op |
| Duplication | Implicit only for scalars; otherwise explicit `Clone` |
| Aggregate protocols | Derived from member contracts |
| Native protocols | Declared by the binding author |
| Shared ownership | Explicit `&`; reference counting |
| Result ownership | Signature contract; details remain open |

---

## Concrete example: prelude before and after

`prelude.mml` is an uncompiled reference listing. The compiler injects the
actual declarations programmatically. The examples below show the binding
contracts; other FFI bindings use the same structure.

### Current prelude (excerpt)

```mml
type String = @native[mem=heap, free=free_string] {
  length: Int64,
  data: CharPtr
};

fn free_string(~s: String): Unit = @native;
fn clone_String(s: String): String = @native[mem=alloc, name="__clone_String"];
fn readline(): String = @native[mem=alloc];
fn concat(a: String, b: String): String = @native[mem=alloc];
```

Ownership information is scattered across ad-hoc annotations: `[mem=heap]` on
the type, `[free=free_string]` linking to a free function by name, `[mem=alloc]`
on every function that returns an owned value, `__clone_String` naming
convention.

### After evolution

```mml
type String = @native {
  length: Int64,
  data: CharPtr
};

implement Drop for String =
  fn drop(~self: String): Unit = free_string self;;
;

implement Clone for String =
  op ^(self: String): String = @native[name="__clone_String"];
;

fn free_string(~s: String): Unit = @native;
fn readline(): String = @native;
fn concat(a: String, b: String): String = @native;
```

These proposed functions return owned values. Omitting `[mem=alloc]` assumes
that the result ownership contract is available from the signature; `Drop`
alone does not provide that information.

### FFI binding (e.g. raylib)

```mml
type Texture = @native { id: Int, width: Int, height: Int };

fn unload_texture(~t: Texture): Unit = @native;

implement Drop for Texture =
  fn drop(~self: Texture): Unit = unload_texture self;;
;

// No Clone -- the resource cannot be duplicated.

fn load_texture(path: String): Texture = @native;
```

Same mechanism, same place. The binding author declares the type and implements
the protocols. The compiler enforces the rest.

---

## Enforcement of ownership and cleanup

- Ownership is mandatory for non-scalar values; it is not an opt-in protocol.
- Aggregates cannot opt out of their members' cleanup obligations.
- Derived `Drop` may be a no-op. That does not remove ownership checks.
- Derived `Clone` requires every member to be scalar or `Clone`.
- Opaque native types and newtypes declare their public protocols explicitly.
- Custom implementations must preserve these contracts.

Borrowing permits multiple references within the owner's lifetime. An independently
owned copy requires `Clone`; shared ownership uses explicit `&` operations.

### Type rows

`Drop`, `Clone`, and `Shared` participate in the proposed type rows.
Ownership remains a language rule. Exact row syntax and generic constraints are TBD.

### Fundamental protocols

`Drop` and `Clone` are defined in MML and recognized by the compiler:

- Aggregate derivation supplies implementations where valid.
- Scope-end cleanup invokes `Drop`; explicit duplication invokes `Clone`.
- Calls are monomorphised. No vtable or dynamic dispatch is required.

### Open questions

- How signatures express owned, borrowed, and static results.
- Which conditional ownership cases still require witnesses.
- How generic constraints provide destruction and cloning operations.
- Migration of existing literal/global implicit-clone boundaries to explicit duplication.
- Whether to add `MustConsume` later. A no-op `Drop` permits an unused token
  to expire; requiring an explicit use is a separate obligation.
