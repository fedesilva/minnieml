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

### Copying

Copying an `&T` value is allowed.

That copy increments the reference count.

### Drop

Dropping an `&T` decrements the reference count.

When the count reaches zero:

1. the inner `T` value is dropped
2. the RC cell storage is freed

This preserves deterministic destruction while allowing shared structure.

### Borrowing

Borrowing and shared ownership are different concepts.

- plain parameters still borrow by default
- `&T` is not a borrow, it is a shared owner handle

This distinction should stay crisp in the type system and in diagnostics.

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

- `clone x` duplicates a value
- `&x` changes the ownership regime of `x`

That distinction matters.

`clone` says:

- "I want two independent values"

`&` says:

- "I want one value, but I want it to be shared"

The language should support both because they solve different problems.

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

Questions:

- is `&expr` the final syntax for promotion into shared ownership?
- how do we spell the type form in annotations and fields?
- do we want pattern-level or parameter-level support for shared refs later?

### 3. Runtime representation

Possible representations:

- inline `RcCell[T]`
- header + payload
- pointer to heap object with metadata prefix

This should be chosen based on codegen simplicity and predictable performance.

### 4. Protocol integration

Questions:

- does `&T` get its own `Drop` behavior via protocol derivation?
- does `&T` require `Clone`-like protocol support for retain semantics, or is that
  built in?
- how does `&T` interact with future `Drop` / `Clone` / protocol dispatch design?

### 5. Borrowing from shared refs

What does it mean to pass `&T` to a normal parameter?

Likely answer:

- the parameter borrows the shared handle, not the inner value directly

But this needs to be made precise.

---

## Current direction

The intended memory ladder is:

1. unique ownership by default
2. explicit `clone` for duplication
3. explicit `&` for shared ownership

That keeps the simple path fast and predictable while still giving a workable model
for richer data structures.

This is the direction to return to after protocol-based `Clone` lands.
