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

A comparison with Neut (a language with linearity built into the type system from
day one) makes the gap visible. Neut's type system prevents misuse by construction.
MML's analyzer detects misuse after the fact. The right fix is to move the
load-bearing invariants into the type system where the compiler can enforce them
structurally.

This document outlines a layered evolution plan: each layer adds a capability,
each is independently useful, and the later layers simplify what came before.

---

## Core principle: borrow by default, move explicitly

MML borrows by default and moves explicitly (via `~` on the parameter). This is
the inverse of Rust, where values move by default and borrowing requires `&`.

Most functions just take arguments, use them, and return. The caller never loses
ownership. No annotation needed. `~` is the exception, not the rule.

This must be preserved through the evolution. The layers below add structure
(uniqueness on types, Drop/Clone protocols, type rows) but do not change the
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

## Layer 1: Uniqueness in the type system

Move linearity from the analyzer into the type checker.

### Uniqueness vs ownership

A type like `String` is *unique* -- declared as such at the type level. That
is a permanent property of the type. There is no "non-unique String." Uniqueness
means: this value must not be silently duplicated, and exactly one owner is
responsible for its destruction.

But unique values can still be *borrowed*. Most functions borrow: `println` takes
a `String`, uses it, and the caller keeps ownership. Borrowing a unique value is
safe as long as the borrower doesn't outlive the owner or try to destroy it.

`~` on a parameter is not about the type -- it's about the function's relationship
to the argument. The type is `String` either way. `~` on the parameter says
"I take responsibility for this value":

- `fn println(s: String): Unit` -- borrows `s`. Caller keeps ownership.
- `fn consume(~s: String): Unit` -- takes ownership. Caller loses it. Callee
  is responsible for drop.
- `fn readline(): String` -- caller receives an owned value (return values are
  always owned).

The type doesn't change. `String` is `String`. What changes is the declaration
of responsibility.

### What changes

- Type declarations can mark a type as unique (exact syntax TBD -- possibly
  `unique struct Foo { ... }` or a keyword in the `@native` annotation).
  Uniqueness is a property of the type itself, not of individual values.
- `~` on parameters means "I take ownership", same as today, but now the type
  checker validates linearity (use-after-move becomes a type error).
- The type checker tracks linearity. Use-after-move is a type error, not a
  semantic analysis error.

### What this eliminates

- **`ReturnOwnershipAnalysis`** — no more fixed-point inference. If a type is
  unique, its return values are always owned. Cross-module calls are sound because
  uniqueness is declared on the type.
- **`memEffect` annotations** — a function returning a unique type is implicitly
  returning an owned value. No side-channel needed.
- **Most witness booleans** — if the result type is unique, the type checker
  ensures both branches agree on ownership. The non-allocating
  branch clones automatically (or errors, depending on the type). The analyzer no
  longer needs runtime flags to track conditional ownership.

### What stays the same

- The ownership analyzer still inserts free/clone calls. It just has much better
  information — the type checker has already validated the linearity invariants.
- Default parameters still borrow. `~` on parameters still means consumption.
- Struct constructors still consume heap fields.

---

## Layer 2: Drop protocol

Decouple "needs cleanup" from "is a heap type" by making cleanup a protocol
obligation.

### What changes

- Introduce a `Drop` protocol:

  ```mml
  protocol Drop for T =
    fn drop(~self: T): Unit
  end
  ```

- Any type implementing `Drop` gets automatic scope-end cleanup. The compiler
  calls `drop` when an owned unique value goes out of scope without being consumed.
- Native heap types (`String`, `Buffer`, `IntArray`, etc.) implement `Drop` by
  calling their existing runtime free functions — replacing the `__free_T` naming
  convention and `[mem=heap, free=...]` annotations.
- For user-defined structs with `Drop` fields, the compiler generates a `Drop`
  implementation (recursive field destruction) and the protocol wiring. This is
  the same work `MemoryFunctionGenerator` does today, but the output is a proper
  protocol implementation rather than a `__free_T` function wired by naming
  convention.
- Non-heap types that need cleanup (file handles, sockets, locks) implement `Drop`
  and get the same automatic cleanup as heap types.

### What this eliminates

- **`isStructWithHeapFields` / `hasHeapFields`** — replaced by "does this type
  implement `Drop`?"
- **`MemoryFunctionGenerator`** synthesizing `__free_T` / `__clone_T` — replaced
  by protocol derivation.
- **`memEffect` annotations entirely** — a function returning a unique type that
  implements `Drop` tells the compiler everything it needs to know.
- **The special-casing of native types** — native types are just types that
  implement `Drop` (and optionally `Clone`).

### What stays the same

- The ownership analyzer still exists, but it's now a thin pass: insert `drop`
  calls at scope end for `Drop` types, validate moves for unique types, done.
- The type checker enforces linearity (Layer 1). The Drop protocol just tells the
  compiler *what to do* when a linear value is consumed by scope exit.

---

## Layer 3: Clone protocol

Make duplication explicit and protocol-driven.

### What changes

- Introduce a `Clone` protocol:

  ```mml
  protocol Clone for T =
    fn clone(self: T): T
  end
  ```

  Note the asymmetry with `Drop`: `drop(~self)` consumes because the value is
  being destroyed. `clone(self)` borrows because the original must survive the
  operation.

- When a value of type `T: Clone` is used in a context that requires duplication
  (passed to a consuming param while still needed, or explicitly cloned), the
  compiler calls `clone`.
- Types that are `Drop` but not `Clone` are unique resources — they cannot be
  duplicated at all (sockets, file handles, locks).
- Types that are both `Drop + Clone` are the current heap types — they can be
  cloned when needed and are freed when owned.
- Types that are `Clone` but not `Drop` are value types with custom copy semantics
  (unusual, but possible).

### What this eliminates

- **`wrapWithClone` / `argNeedsClone`** hard-coded logic — replaced by protocol
  dispatch.
- **The implicit assumption that all heap types are clonable** — unique resources
  are now expressible.

---

## Summary: before and after

| Concern | Current | After evolution |
|---------|---------|-----------------|
| Linearity enforcement | Flow analysis (OwnershipAnalyzer) | Type system (`unique` types) |
| "Needs cleanup?" | `isStructWithHeapFields` | `T: Drop` |
| "Can be duplicated?" | All heap types, always | `T: Clone` |
| Return ownership | `ReturnOwnershipAnalysis` fixed-point | Implicit: unique types always return owned |
| Conditional ownership | Runtime witness `__owns_x` | Type checker rejects mixed branches |
| Free insertion | `wrapWithFrees` + `__free_T` | `drop` calls via `Drop` protocol |
| Clone insertion | `wrapWithClone` + `__clone_T` | `clone` calls via `Clone` protocol |
| Native type metadata | `memEffect` annotations | Protocol implementations |
| Unique resources | Not expressible | `Drop` without `Clone` |

---

## Concrete example: prelude before and after

The prelude (`prelude.mml`) is where stdlib types are declared. It is the binding
layer between MML and the C runtime. A raylib or any other FFI binding would be
another `.mml` file with the same structure.

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

Ownership information is scattered across ad-hoc annotations: `[mem=heap]` on the
type, `[free=free_string]` linking to a free function by name, `[mem=alloc]` on
every function that returns an owned value, `__clone_String` naming convention.

### After evolution

```mml
unique type String = @native {
  length: Int64,
  data: CharPtr
};

implement Drop for String =
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

`unique` on the type declaration marks it. `Drop` and `Clone` implementations
call the existing C runtime functions. Return types are just `String` -- the
compiler knows `String` is unique, so return values are always owned. No
`[mem=alloc]`, no naming conventions, no side-channel metadata.

### FFI binding (e.g. raylib)

```mml
unique type Texture = @native { id: Int, width: Int, height: Int };

fn unload_texture(~t: Texture): Unit = @native;

implement Drop for Texture =
  fn drop(~self: Texture): Unit = unload_texture self
end

// No Clone -- Texture is a unique resource.

fn load_texture(path: String): Texture = @native;
```

Same mechanism, same place. The binding author declares the type, implements the
protocols, and the compiler enforces the rest.

---

## Open question: enforcement of Drop and uniqueness

A type system that allows the user to declare `unique` and `Drop` freely is only
safe if the compiler prevents mistakes. The question is who decides that a type
must be unique and must implement `Drop`: the user, the compiler, or both?

### The risk

If it's purely opt-in, a user can define:

```mml
struct Leaker { s: String }
```

without implementing `Drop`. The `String` field is never freed. The compiler must
prevent this.

### The rule

The constraint propagates from fields to containers:

- **Contains a `Drop` field** → must implement `Drop`. Compiler error otherwise.
  The compiler can auto-derive `Drop` (recursive field destruction) when all fields
  are `Drop`, but the obligation is enforced.
- **Contains a unique field** → the containing struct is implicitly unique. A
  freely-copyable struct cannot contain a unique field — that would allow
  duplicating the unique value by copying the struct.
- **`Drop` implies unique**. If a value needs cleanup, it must be unique. Otherwise
  you can copy it and get a double-free. `Drop` is a refinement of uniqueness, not
  independent of it.

### The hierarchy

```
T               — value type, freely copyable, no cleanup
unique T        — unique/affine, must be consumed at most once, no automatic cleanup
unique T + Drop — unique, compiler inserts drop at scope end
unique T + Drop + Clone — unique, droppable, explicitly clonable (current heap types)
```

Users can move *up* this ladder (make an `Int` unique if they want) but cannot
move *down* — a struct containing a `Drop` field cannot opt out of `Drop`.

### Auto-derivation

For the common case (struct with heap fields), the compiler derives `Drop` and
`Clone` automatically. The user only writes explicit implementations when they
need custom logic (flush before close, reference counting, arena deallocation).

This means the default experience doesn't change: define a struct, the compiler
handles cleanup. But the mechanism is uniform rather than hard-coded to heap-type detection.

### The uniqueness tradeoff

Some types don't need to be unique from a semantic standpoint. An immutable string
is safe to share -- multiple readers, nobody mutates, no data race. The only reason
`String` must be unique is memory management: someone has to free it, and without
uniqueness, answering "when is nobody using it anymore?" requires either lifetime
annotations (Rust's borrow checker) or runtime tracking (reference counting, GC).

MML chose uniqueness + clone: only one owner, if you need a second reference you
pay for a copy. Strings get copied more than strictly necessary, but the model
stays simple and the compiler stays small. Every clone is explicit, every free is
deterministic.

If this becomes too expensive for specific use cases, an opt-in shared wrapper
with reference counting could be added later without changing the core model.

### Type rows

`Unique`, `Drop`, `Clone` are properties in the same space -- a row of
capabilities attached to the type. The `unique` keyword on a declaration adds
`Unique` to the row. Protocol implementations add `Drop`, `Clone`, etc.
Propagation infers row members (a struct with a `Unique` field is itself
`Unique`).

This means uniqueness is not a separate mechanism from protocols -- it lives in
the same row. This also opens the door for other properties later (`Send`, `Sync`,
etc.) without new keywords each time.

Exact syntax for type-level row constraints is TBD.


### What this doesn't answer yet

- Whether `Clone` should be auto-derived or always explicit.
- How this interacts with the protocol dispatch mechanism (still in design).
- Whether `Drop` should be a "special" protocol with compiler support or just a
  regular protocol that the compiler happens to call at scope end.
- How type rows interact with type inference and generic constraints.
