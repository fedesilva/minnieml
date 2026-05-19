# Memory Model Evolution

## Status: brainstorming

---

## Motivation

MML's current memory model works. The ownership analyzer tracks bindings through
the AST, inserts free and clone calls, and catches use-after-move errors at compile
time.

But it conflates three concerns that should be independent:

1. **Linearity** — must this value be consumed at most once?
2. **Resource cleanup** — does this value need destruction?
3. **Heap allocation** — does this value live on the heap?

Today, `isStructWithHeapFields` is the single predicate that answers all three
questions. A type is tracked by the ownership analyzer if and only if it has heap
fields. That works because every heap type currently needs cleanup and every cleanup
type is currently heap-allocated. But this collapses distinctions that matter:

- A file descriptor is an integer (not heap) but needs cleanup and must not be
  duplicated.
- A large array of floats is heap-allocated but could be freely copyable if we
  wanted reference-counted or arena-allocated variants.
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
(Unique/Clone protocols, type rows, shared refs) but do not change the
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
`__free_T`/`__clone_T` functions). It does not change *what* is enforced. The
generated code for existing programs should be identical.

The memory test suite (`mml/samples/mem/`) validates these invariants: zero
leaks, no double-free, no use-after-move, correct conditional cleanup, correct
struct destruction. All of these tests must continue to pass unchanged through
the evolution.

---

## Decisions

### Drop and Unique are one capability

A type that needs cleanup *must* be unique: otherwise it can be silently
duplicated and double-freed. A type that is unique *will* be cleaned up at scope
end: that is what makes the linearity discipline observable. The two properties
are co-extensive in every concrete case MML cares about, so they collapse into
one row capability.

The merged capability is named `Unique`. The protocol method that runs at scope
end is named `drop`. There is no separate `Drop` protocol and no separate
`unique` keyword on type declarations.

**Why:** Keeping them split was paying complexity tax for a phantom rung
("linear without cleanup", e.g. session-type tokens, type-state markers) that
MML has no concrete user for today. If that category becomes load-bearing
later, it can be reintroduced as a separate `MustConsume` capability that
suppresses scope-end `drop`. That is additive; we do not need to keep the split
around speculatively.

### A type is unique iff it implements `Unique`

Uniqueness is not declared by a keyword. It is declared by implementing the
`Unique` protocol (directly, or via auto-derivation from members). A type's row
contains `Unique` exactly when the protocol is implemented for it.

The freely-copyable types in MML form a small island: numbers, booleans, unit,
characters, and aggregates built only from those. Everything else --
`String`, `Buffer`, `IntArray`, file handles, textures, sockets, user structs
holding any of those -- is `Unique`.

### `Unique` and `Clone` both propagate, dually

Both capabilities are auto-derived for MML types. The compiler knows how to
recurse on the fields of an MML aggregate, so it can derive both `drop` and
`clone` automatically. The propagation rules are duals of each other:

- An aggregate is `Unique` iff **at least one** of its members is `Unique`.
  One unique field is enough to taint the whole -- you can't copy the
  aggregate freely if any part can't be copied.
- An aggregate is `Clone` iff **every** member is `Clone`. To duplicate the
  whole, you must be able to duplicate every part; one non-cloneable member
  is enough to make the aggregate non-cloneable.

A `struct { name: String, age: Int }` is `Unique & Clone`: `String` is
`Unique & Clone`, `Int` is freely copyable, so both capabilities flow through.
A `struct { handle: FileHandle, label: String }` is `Unique` but not `Clone`:
`FileHandle` is `Unique` without `Clone`, so the aggregate inherits the
non-cloneability.

Native types are the exception. The compiler has no insight into an opaque
`@native` representation, so both protocols must be declared explicitly when
they apply. Aggregates built on top of native types still auto-derive
normally.

Cost visibility is not lost by auto-deriving `Clone`. The cost shows up where
it is paid: every duplication is an explicit `^x` in source, and the reader
sees a copy happening regardless of whether the impl was derived or
hand-written.

---

## Layer 1: Unique protocol

Move linearity and cleanup into the type system as a single capability.

### The protocol

```mml
protocol Unique for T =
  fn drop(~self: T): Unit
end
```

A type is `Unique` iff it implements this protocol. The type checker enforces
linearity (single owner, no use-after-move, no silent duplication). The compiler
inserts `drop` calls at scope end on owned `Unique` values that were not
consumed by a `~` parameter or returned.

### Uniqueness vs ownership

`String` is `Unique` -- a permanent property of the type. There is no
"non-unique String." Uniqueness means: this value must not be silently
duplicated, and exactly one owner is responsible for its destruction.

But unique values can still be *borrowed*. Most functions borrow: `println`
takes a `String`, uses it, and the caller keeps ownership. Borrowing a unique
value is safe as long as the borrower doesn't outlive the owner or try to
destroy it.

`~` on a parameter is not about the type -- it's about the function's
relationship to the argument. The type is `String` either way. `~` on the
parameter says "I take responsibility for this value":

- `fn println(s: String): Unit` -- borrows `s`. Caller keeps ownership.
- `fn consume(~s: String): Unit` -- takes ownership. Callee runs `drop` if it
  doesn't transfer the value out.
- `fn readline(): String` -- caller receives an owned value (return values are
  always owned).

The type doesn't change. `String` is `String`. What changes is the declaration
of responsibility.

### Native types

Native heap types declare `Unique` explicitly, calling their existing runtime
free functions:

```mml
implement Unique for String =
  fn drop(~self: String): Unit = free_string self
end
```

This replaces the `__free_T` naming convention and `[mem=heap, free=...]`
annotations. Native types are no longer special-cased -- they are just types
that implement `Unique` (and optionally `Clone`).

### Aggregates: auto-derivation by contagion

A struct with at least one `Unique` field is itself `Unique`. The compiler
auto-derives the implementation by recursing on members:

```mml
struct User { name: String, age: Int }
// User: Unique  (because String: Unique)
// User: Clone   (because every field is Clone: String is, Int is freely copyable)
// auto-derived drop recurses into name; age is a value, ignored
// auto-derived clone recurses into name; age is copied
```

The user only writes an explicit `Unique` implementation when the aggregate
needs custom destruction (flush before close, refcount, custom allocator), or
an explicit `Clone` implementation for custom copy semantics.

The same rule applies to tuples, sum types (a variant carrying a `Unique`
payload taints the whole sum), arrays of `Unique`, and any future aggregate
form. Stated once: **an aggregate is `Unique` iff at least one of its members
is `Unique`; it is `Clone` iff every member is `Clone`**.

### What this eliminates

- **`ReturnOwnershipAnalysis`** — no more fixed-point inference. If a type is
  `Unique`, its return values are always owned. Cross-module calls are sound
  because uniqueness is declared on the type.
- **`memEffect` annotations** — a function returning a `Unique` type implicitly
  returns an owned value. No side-channel needed.
- **Most witness booleans** — if the result type is `Unique`, the type checker
  ensures both branches agree on ownership. The non-allocating branch clones
  (when `T: Clone`) or errors. No runtime flags.
- **`isStructWithHeapFields` / `hasHeapFields`** — replaced by "does this type
  implement `Unique`?"
- **`MemoryFunctionGenerator` synthesizing `__free_T`** — replaced by protocol
  derivation.
- **The special-casing of native types** — native types are just types that
  implement `Unique` (and optionally `Clone`).

### What stays the same

- The ownership analyzer still exists, but it's now a thin pass: insert `drop`
  calls at scope end for `Unique` types, validate moves, done.
- Default parameters still borrow. `~` on parameters still means consumption.
- Struct constructors still consume `Unique` fields.

---

## Layer 2: Clone protocol

Make duplication explicit and protocol-driven.

### What changes

- Introduce a `Clone` protocol:

  ```mml
  protocol Clone for T =
    fn clone(self: T): T
  end
  ```

  Note the asymmetry with `Unique`: `drop(~self)` consumes because the value is
  being destroyed. `clone(self)` borrows because the original must survive the
  operation.

- When a value of type `T: Clone` is used in a context that requires duplication
  (passed to a consuming param while still needed, or explicitly cloned), the
  compiler calls `clone`.
- Types that are `Unique` but not `Clone` are unique resources — they cannot be
  duplicated at all (sockets, file handles, locks, textures).
- Types that are both `Unique + Clone` are the current heap types — they can be
  cloned when needed and are dropped when owned.
- A non-`Unique` type does not need `Clone`. It is freely copyable by virtue of
  being made of primitives.

### Clone is auto-derived for MML aggregates

An MML aggregate is `Clone` iff every one of its members is `Clone`. The
compiler derives the implementation by recursing on fields. This is the dual
of the `Unique` rule (any one unique field makes the whole unique; every
field must be cloneable to make the whole cloneable).

A struct containing a `Unique`-without-`Clone` field (e.g. a `FileHandle`,
`Texture`, socket) is itself `Unique` without `Clone`. Trying to `^` it is a
type error.

Native types are the exception. The compiler cannot derive `clone` for an
opaque `@native` representation -- the binding author writes the
implementation explicitly, calling whatever C-runtime clone routine the type
uses.

Cost visibility is preserved at the *use site*: every duplication is an
explicit `^x` in source, and the reader sees a copy happening regardless of
whether the impl was hand-written or derived. Literals and globals of a
`Clone` type are auto-cloned at the use site (they have no owner to transfer
from); everything else requires `^` or produces a compiler error.

### What this eliminates

- **`wrapWithClone` / `argNeedsClone`** hard-coded logic — replaced by protocol
  dispatch.
- **The implicit assumption that all heap types are clonable** — unique
  resources are now expressible as `Unique` without `Clone`.

---

## Layer 3: Shared references and opt-in reference counting

The default is affine ownership: one owner, deterministic destruction, no
runtime cost. That is right for most code, but not all of it. Some values are
genuinely shared between holders that have no single best owner — an interned
string pool, a texture used by many sprites, a config record read from many
places. Cloning wastes work; plain uniqueness misrepresents the relationship.

For those cases MML adds a shared-reference type `&T` (the `Shared` row
capability on `T`) and two operators. Reference counting is opt-in. You ask for
it at the use site; nothing in the language pushes it on you by default.

See `docs/brainstorming/mem/shared-refs.md` for the longer rationale.

### Operators

| op  | input        | result     | effect                                              | requires    |
|-----|--------------|------------|-----------------------------------------------------|-------------|
| `&` | `T: Unique`  | `&T`       | move into a fresh refcount cell (rc = 1)            | —           |
| `&` | `&T`         | `&T`       | bump the refcount, return another handle            | —           |
| `^` | `T: Unique`  | `T`        | deep copy; original keeps ownership                 | `T: Clone`  |
| `^` | `&T`         | `T`        | deep copy of the inner value; handle stays alive    | `T: Clone`  |

`&` is the only consumer of uniqueness. `^` is the only way to obtain a fresh
unique value from an existing one, whether the source is `Unique` or `Shared`.
There is no implicit move in either direction.

### `&` is a primitive, `^` is a protocol operator

The two operators are not symmetric in implementation, only in feel.

`&` is a **compiler primitive**. The type checker has to understand that `&`
consumes a `Unique` value (or aliases an `&T`), and it has to track `&T`
through the `Shared` row. Codegen has to emit the retain on `&` and the release
at scope end. None of that can be expressed as a user-level protocol method,
because none of it is a function call: it is typing rules plus IR emission.
There is no `Share` protocol with a `share(self: T): &T` method; `&` lives in
the language, not in user space.

`^` is **just an operator**. It is the surface syntax for the `Clone`
protocol's `clone` method:

```mml
^x   ≡   Clone.clone x
```

Anything implementing `Clone` gets `^` automatically. Users can write their own
`Clone` implementations with custom semantics (deep copy, shallow copy plus
COW, reference-counted bump, whatever fits the type). The compiler does not
need to know `^` exists beyond desugaring it into a protocol call that the
normal monomorphisation path then handles.

This asymmetry is structural. Removing `^` from the language would leave
`Clone` intact and users would write `clone x` in source. Removing `&` would
require deleting the `Shared` row, the refcount runtime, and the retain/release
insertion pass. `&` does not survive without compiler support; `^` does.

```mml
let a = "String";    // a: String (Unique)
let b = &a;          // a moved; b: &String (rc = 1)
println a;           // error: use after move
let c = &b;          // alias; rc = 2
let x = ^b;          // deep copy; x: String, b still &String
println c ++ b;      // both shared handles still live
                     // scope end: c and b drop, rc hits 0, inner String drops
```

### `Shared` as a row capability

`Shared` lives in the same row as `Unique` and `Clone`. The operators dispatch
on the row of their operand; the programmer does not name a wrapper type. `&`
does not require `Clone` on `T`. `^` does. When the last `&T` goes out of
scope, the existing `Unique` machinery runs `drop` on the inner value. Nothing
about destruction or auto-derivation changes.

### Resources: `Unique` without `Clone`

This is the case the older model could not express. A `Texture` from an FFI
binding implements `Unique` but not `Clone`. With `&` and `^` separated by
capability, the type's behavior follows directly:

```mml
let t  = load_texture "wall.png";    // t: Texture (Unique)
let t2 = ^t;                         // error: Texture: !Clone
let s  = &t;                         // ok: Unique → &Texture (rc = 1)
let s2 = &s;                         // ok: alias another handle (rc = 2)
let u  = ^s;                         // error: Texture: !Clone
                                     // s and s2 drop at scope end;
                                     // last handle calls unload_texture
```

A `Texture` is aliasable but not duplicable. That is the right semantics for a
unique resource shared across a program. The compiler does not need a
"resource" category; the behavior falls out of `Unique` without `Clone`.

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
  row alongside `Unique` and `Clone` rather than living in user-space as a
  generic wrapper.
- The implicit assumption that aliasing is impossible without cloning.
  Resources (`Unique`, no `Clone`) can now be shared at all.

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

## Summary: before and after

| Concern | Current | After evolution |
|---------|---------|-----------------|
| "Is this unique / does it need cleanup?" | `isStructWithHeapFields` | `T: Unique` |
| Linearity enforcement | Flow analysis (OwnershipAnalyzer) | Type system (`T: Unique`) |
| "Can be duplicated?" | All heap types, always | `T: Clone` |
| Return ownership | `ReturnOwnershipAnalysis` fixed-point | Implicit: `Unique` types always return owned |
| Conditional ownership | Runtime witness `__owns_x` | Type checker rejects mixed branches |
| Free insertion | `wrapWithFrees` + `__free_T` | `drop` calls via `Unique` protocol |
| Clone insertion | `wrapWithClone` + `__clone_T` | `clone` calls via `Clone` protocol |
| Native type metadata | `memEffect` annotations | Protocol implementations |
| Unique resources | Not expressible | `Unique` without `Clone` |
| Aliasing without copy | Not expressible | `&` produces `&T` (opt-in refcount) |
| Explicit duplication | Implicit via codegen | `^` invokes `Clone` at the use site |

---

## Concrete example: prelude before and after

The prelude (`prelude.mml`) is where stdlib types are declared. It is the
binding layer between MML and the C runtime. A raylib or any other FFI binding
would be another `.mml` file with the same structure.

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

implement Unique for String =
  fn drop(~self: String): Unit = free_string self
end

implement Clone for String =
  fn clone(self: String): String = clone_string self
end

fn free_string(~s: String): Unit = @native;
fn clone_string(s: String): String = @native;
fn readline(): String = @native;
fn concat(a: String, b: String): String = @native;
```

`String` is `Unique` because it implements the protocol. Return types are just
`String` -- the compiler knows `String` is `Unique`, so return values are always
owned. No `[mem=alloc]`, no naming conventions, no side-channel metadata.

### FFI binding (e.g. raylib)

```mml
type Texture = @native { id: Int, width: Int, height: Int };

fn unload_texture(~t: Texture): Unit = @native;

implement Unique for Texture =
  fn drop(~self: Texture): Unit = unload_texture self
end

// No Clone -- Texture is a unique resource.

fn load_texture(path: String): Texture = @native;
```

Same mechanism, same place. The binding author declares the type and implements
the protocols. The compiler enforces the rest.

---

## Enforcement of `Unique`

A type system that allows the user to declare `Unique` freely is only safe if
the compiler prevents mistakes. The question is who decides that a type must
implement `Unique`: the user, the compiler, or both?

### The risk

If `Unique` is purely opt-in for aggregates, a user can define:

```mml
struct Leaker { s: String }
```

without implementing `Unique`. The `String` field is never dropped. The
compiler must prevent this.

### The rule

The constraint propagates from members to containers for both capabilities,
in opposite directions:

- **Any `Unique` member** → the aggregate is `Unique`. The compiler
  auto-derives the implementation (recursive `drop` on members). The user
  cannot opt out -- a freely-copyable aggregate containing a `Unique` field
  would allow duplicating the unique value by copying the aggregate.
- **Every member is `Clone`** → the aggregate is `Clone`. The compiler
  auto-derives the implementation (recursive `clone` on members). If any
  member is not `Clone`, the aggregate is not `Clone`.

Users may write an explicit `Unique` or `Clone` implementation to override the
derived one (custom destruction order, flush-before-close, custom copy
semantics, etc.). Native types must always be explicit -- the compiler has no
insight into an opaque `@native` representation.

### The hierarchy

```
T                       — value type, freely copyable, no cleanup
T: Unique               — affine + auto drop at scope end (method: drop)
T: Unique + Clone       — unique, droppable, explicitly clonable (current heap types)
T: Shared               — refcounted handle on a previously-Unique T:
                          - `&` on Unique mints one (rc=1); further `&` aliases (rc++)
                          - `^` deep-copies the inner value out, requires T: Clone
                          - drop runs on the inner value when rc reaches 0
```

Users can move *up* this ladder (a struct gains `Unique` by containing a
`Unique` field) but cannot move *down* -- an aggregate containing a `Unique`
field cannot opt out of `Unique`. Moving from `Unique` to `&T` is one-way: `&`
consumes the unique value, and the only path back to a unique value is `^`,
which is a clone, not a recovery.

### Auto-derivation

For MML aggregates, the compiler derives both `Unique` and `Clone` from the
member set:

- `Unique` is derived when at least one member is `Unique`.
- `Clone` is derived when every member is `Clone`.

The user only writes explicit implementations when they need custom logic
(flush before close, custom copy semantics, reference counting, arena
deallocation).

Native types must declare both protocols explicitly when they apply. The
compiler cannot recurse on an opaque `@native` representation -- the binding
author has to name the C-runtime functions that implement `drop` and `clone`.

The default experience doesn't change: define a struct, the compiler handles
cleanup and (where the field set allows it) cloning. But the mechanism is
uniform rather than hard-coded to heap-type detection.

### The uniqueness tradeoff

Some types don't need to be unique from a semantic standpoint. An immutable
string is safe to share -- multiple readers, nobody mutates, no data race. The
only reason `String` must be `Unique` is memory management: someone has to free
it, and without uniqueness, answering "when is nobody using it anymore?"
requires either lifetime annotations (Rust's borrow checker) or runtime
tracking (reference counting, GC).

MML chose uniqueness + clone: only one owner, if you need a second reference
you pay for a copy. Strings get copied more than strictly necessary, but the
model stays simple and the compiler stays small. Every clone is explicit, every
free is deterministic.

Layer 3 covers the cases where copying is genuinely too expensive. `&` on a
unique value moves it into a refcounted handle; further `&` operations alias
the handle. The default ownership story stays affine; the escape hatch is
visible in source wherever it is used.

### Type rows

`Unique`, `Clone`, `Shared` are properties in the same space -- a row of
capabilities attached to the type. Protocol implementations add row members.
Propagation infers row members for aggregates (`Unique` propagates from
members; `Clone` does not).

Uniqueness is not a separate mechanism from protocols -- it lives in the same
row. Other properties (`Send`, `Sync`, etc.) can be added later without new
keywords each time.

Exact syntax for type-level row constraints is TBD.

### Fundamental protocols

`Unique` and `Clone` are **fundamental protocols**. They are defined in MML
(not special syntax), but the compiler knows about them and emits code based
on their presence. Future additions (`Send`, `Sync`, effects) would work the
same way.

What makes a protocol fundamental:
- The compiler auto-derives instances for MML aggregates (`Unique` from any
  unique member; `Clone` when every member is `Clone`). Native types declare
  both explicitly.
- The compiler inserts calls implicitly (`drop` at scope end; `clone` at every
  `^x` use site and on literal/global use of `Clone` types).
- The protocol participates in type-level rules (`Unique` and `Clone` both
  propagate through aggregation, dually; `&` requires `Unique` on its operand;
  `^` requires `Clone`).

User-defined protocols are just dispatch mechanisms. The compiler doesn't
care about their semantics, it only monomorphises the calls.

### Resolved questions

- **Drop and Unique are merged.** One protocol named `Unique`, one method
  named `drop`. There is no separate `Drop` protocol and no `unique` keyword.
  See the Decisions section above.

- **Clone auto-derivation:** Clone *is* auto-derived for MML aggregates whose
  members are all `Clone`. Native types must declare it explicitly. Cost
  visibility is preserved at the use site: every duplication is an explicit
  `^x` regardless of whether the impl was derived or hand-written. Literals
  and globals of `Clone` types are auto-cloned at the use site (they have no
  owner to transfer from).

- **Protocol dispatch interaction:** None. `Unique` and `Clone` are
  monomorphised at compile time. No vtable, no dynamic dispatch. The protocol
  is a structured way to name the functions the compiler already generates.

### Open questions

- How type rows interact with type inference and generic constraints. When
  writing `fn foo[T](x: T)`, what operations are available on `x`? If `T` is
  unconstrained, you can only borrow or move — no clone, no drop. Constraints
  like `T: Clone` would unlock specific operations. Exact syntax and semantics
  TBD.

- Whether a `MustConsume` capability (unique without auto-drop, errors on
  scope-end leak) is worth adding later for session-type tokens or type-state
  markers. Not in scope for this evolution; recorded so the decision to merge
  `Drop` and `Unique` does not foreclose it.
