# Shared Refs

## Status: brainstorming

---

## Motivation

MML's default ownership model should stay unique-by-default.

That gives us deterministic destruction, no tracing GC, and a cost model that is
visible in the source.

But some shapes are naturally shared:

- graphs
- DAGs
- intrusive indexes
- caches with multiple readers
- object networks where one node is reachable from many places

Unique ownership alone makes these structures awkward. The answer should not be
"everything is reference counted all the time and we optimize it away later."

The answer should be an explicit, opt-in shared ownership form.

---

## Goal

Introduce `&T` as an explicit shared reference-counted ownership form.

The important design constraint is that `&T` is **not** the default and is **not**
just a borrow. It is a different ownership regime with an explicit runtime cost.

The intended split is:

- `T` means unique ownership
- `&T` means shared ownership through a reference-counted cell

---

## Core idea

Converting a unique value into a shared ref is a one-way ownership transfer.

Example:

```mml
let a = Struct1("name");
let b = &a;
```

Operationally:

1. `a` is moved.
2. A reference-counted cell is allocated.
3. That cell owns the original value.
4. `b` becomes a handle to that cell.

Conceptually, the runtime shape is something like:

```text
RcCell[T] = {
  counter: Int,
  value:   T
}
```

or equivalently:

```text
RcCell[T] = {
  counter: Int,
  ptr:     ptr T
}
```

The exact layout is an implementation detail. The source-level type is `&T`, not
`ptr`.

After conversion:

- the original unique binding no longer owns the value
- the RC cell is now the owner
- every `&T` handle participates in retain/release

---

## Semantics

### Creation

`&a` consumes `a` and produces `&T`.

This must be explicit. There should be no silent promotion from `T` to `&T`.

### Aliasing an existing shared ref

`&b` where `b: &T` does not move `b`. It bumps the reference count and returns
another `&T` handle. Both bindings are live afterwards.

So `&` is overloaded by the row of its operand:

- on a `unique T`, `&` moves the value into a fresh RC cell (rc = 1)
- on an `&T`, `&` aliases the existing cell (rc++)

This is one operator with one mental model: "give me a shared handle to this
value." How the handle is produced depends on whether one already exists.

### Drop

Dropping an `&T` decrements the reference count.

When the count reaches zero:

1. the inner `T` value is dropped via its `Drop` implementation (see
   `mem-evolution.md`, Layer 2)
2. the RC cell storage is freed

This preserves deterministic destruction while allowing shared structure. `&T`
does not need its own `Drop` derivation; it reuses the inner `T`'s.

### Clone-out via `^`

`^` is the dual operator: "give me a fresh unique value from this one."

- `^x` where `x: unique T` and `T: Clone` produces a `unique T` deep copy;
  `x` retains its ownership
- `^x` where `x: &T` and `T: Clone` produces a `unique T` deep copy of the
  inner value; the handle stays alive

`^` requires `T: Clone`. `&` does not. That asymmetry is load-bearing: it makes
resources (types that are `Drop` but not `Clone`) shareable but never
duplicable. See "Resources" below.

There is no `^` from `unique T` back to `&T` and no operator that converts `&T`
back to `unique T` without copying. Once a value is shared, the only path to
unique ownership is a clone.

`^` is surface syntax for the `Clone` protocol:

```mml
^x   ≡   Clone.clone x
```

The compiler desugars `^` into a protocol call; nothing else about `^` is
special. Users can implement `Clone` for their own types with whatever copy
semantics make sense, and `^` will pick it up.

### `&` is a primitive, `^` is a protocol operator

The two operators are not symmetric in implementation, only in feel.

`&` is a **compiler primitive**. The type checker has to understand that `&`
consumes a `unique T` (or aliases an `&T`), and it has to track `&T` through
the `Shared` row. Codegen has to emit the retain on `&` and the release at
scope end. None of that can be expressed as a user-level protocol method,
because none of it is a function call: it is typing rules plus IR emission.
There is no `Share` protocol with a `share(self: T): &T` method; `&` lives in
the language, not in user space.

`^` is **just an operator**, as above: surface syntax for `Clone.clone`. The
compiler does not need to know `^` exists beyond desugaring it into a protocol
call.

Removing `^` from the language would leave `Clone` intact and users would
write `clone x` in source. Removing `&` would require deleting the `Shared`
row, the refcount runtime, and the retain/release insertion pass. `&` does not
survive without compiler support; `^` does.

### Borrowing

Borrowing and shared ownership are different concepts.

- plain parameters still borrow by default
- `&T` is not a borrow, it is a shared owner handle

A function parameter of type `&T` participates in retain/release like any other
`&T` binding. A function parameter of type `T` borrows whatever the caller
hands it. Passing an `&T` to a `T` parameter is not allowed without an
explicit `^` to clone the inner value out.

This distinction should stay crisp in the type system and in diagnostics.

---

## Resources: `Drop` without `Clone`

The split between `&` (needs no `Clone`) and `^` (needs `Clone`) makes resources
expressible for the first time.

A resource is a type that has cleanup semantics but cannot be duplicated:
textures, file handles, sockets, locks. Each has identity tied to something the
runtime cannot copy.

```mml
unique type Texture = @native { id: Int, width: Int, height: Int };

fn unload_texture(~t: Texture): Unit = @native;

implement Drop for Texture =
  fn drop(~self: Texture): Unit = unload_texture self
end

// No Clone. Texture cannot be duplicated.
```

With this declaration, the capability matrix constrains usage:

```mml
let t  = load_texture "wall.png";    // unique Texture
let t2 = ^t;                         // error: Texture: !Clone
let s  = &t;                         // ok: unique → &Texture (rc = 1)
let s2 = &s;                         // ok: alias (rc = 2)
let u  = ^s;                         // error: Texture: !Clone
                                     // scope end: s and s2 drop;
                                     // last handle calls unload_texture
```

A `Texture` is aliasable but not duplicable. The compiler has no special case
for "resource"; the behavior is what falls out of `Drop` without `Clone` once
`&` and `^` are decoupled.

---

## Why this is better than ambient RC

The language should keep unique ownership as the default model.

That means:

- most values pay no retain/release cost
- the source reveals where sharing begins
- the compiler does not need to start from "everything is shared" and then try to
  recover uniqueness later

This is the opposite of systems that use RC pervasively and optimize it away as a
backend improvement.

In MML, shared ownership should be a deliberate choice by the programmer.

---

## Example: graph-like structures

This is the motivating family of examples.

A graph root may uniquely own:

- a collection of nodes
- a collection of vertices
- auxiliary metadata

But vertices may need references to nodes, and many vertices may point to the same
node.

With `&T`, the rough story becomes:

1. the graph root builds nodes with unique ownership
2. selected node values are promoted into shared refs
3. vertices store `&Node`
4. dropping the graph drops the vertex collection and any root-owned containers
5. each `&Node` release decrements counts
6. nodes are destroyed when the last shared reference disappears

This gives a usable "complex shared structure" story without a tracing garbage
collector.

---

## Relationship to `Clone`

Shared refs do not replace cloning.

These are different operations:

- `^x` (clone) duplicates a value
- `&x` changes the ownership regime of `x`

That distinction matters.

`^` says:

- "I want two independent values"

`&` says:

- "I want one value, but I want it to be shared"

The language should support both because they solve different problems.

`^` is the surface syntax for the `Clone` protocol (see `mem-evolution.md`,
Layer 3). It works on both `unique T` and `&T` and requires `T: Clone` in both
cases.

---

## Constraints

### Explicitness

Creation of shared refs must remain explicit in source.

No implicit insertion of `&`.

### Determinism

Destruction must remain deterministic.

`&T` is still automatic memory management, but not tracing GC.

### Interoperation with unique ownership

The transition from `T` to `&T` must be simple and predictable.

The most important invariant is:

- after `let b = &a`, `a` is moved and cannot be used as a unique owner anymore

### Cost visibility

Programs should make sharing visible.

`&T` should signal:

- allocation of an RC cell
- retain/release traffic
- possible indirection

---

## Open questions

These are intentionally unresolved for now.

### 1. Cycles

Plain reference counting does not reclaim cycles.

We will need to choose one of:

- forbid or strongly discourage cyclic `&T` graphs
- add weak refs
- add explicit cycle-breaking patterns
- add a separate facility for cycle-heavy structures

This is the main semantic question left open by the model.

### 2. Syntax surface

Resolved for the value-level operators:

- `&expr` promotes a `unique T` into `&T`, or aliases an existing `&T`
- `^expr` produces a `unique T` from a `unique T` or an `&T` (requires
  `T: Clone`)
- the type form is `&T` in annotations and fields

Still open:

- pattern-level support for shared refs (binding through `&` in patterns)
- whether parameter sigils (analogous to `~` for consume) make sense for
  expressing "this parameter takes a shared ref and bumps the count for the
  duration of the call" vs the default of plain participation in retain/release

### 3. Runtime representation

Possible representations:

- inline `RcCell[T]`
- header + payload
- pointer to heap object with metadata prefix

This should be chosen based on codegen simplicity and predictable performance.

Atomic refcounting fits the same machinery. An `&T` whose `T` also carries
`Send` would compile to atomic increments; a non-`Send` `&T` stays non-atomic.
No new type form, no new sigil. See `mem-evolution.md`, Layer 4.

### 4. Protocol integration

Resolved:

- `&T` reuses the inner `T`'s `Drop` implementation when the count reaches zero.
  No separate `Drop` derivation for `&T` itself.
- `&` does not require `Clone` on `T`. That is what makes resources
  (`Drop` without `Clone`) shareable.
- `^` is the surface syntax for the `Clone` protocol. It requires `T: Clone` on
  both `unique T` and `&T` inputs.
- Retain semantics are built in to `&` (not protocol-dispatched). Refcount
  bumps and drops are compiler-emitted, the same way `Drop` calls are
  auto-inserted at scope end.

### 5. Borrowing from shared refs

Resolved:

- a function parameter of type `&T` participates in retain/release like any
  other `&T` binding
- a function parameter of type `T` borrows whatever the caller hands it;
  passing an `&T` to a `T` parameter is rejected
- to feed an `&T` to a function that wants a plain `T`, clone the inner value
  out with `^` and pass the result

---

## Current direction

The intended memory ladder is:

1. unique ownership by default
2. explicit `^` for duplication (Clone protocol)
3. explicit `&` for shared ownership (this document)

That keeps the simple path fast and predictable while still giving a workable
model for richer data structures.

Layer 3 of `mem-evolution.md` lands `Clone` and `^`. This document's design
follows from that and from the row capability framing in Layer 4.
