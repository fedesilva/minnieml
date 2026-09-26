# Intrinsic arrays

Status: Draft
Ticket: `#202` (`https://github.com/fedesilva/minnieml/issues/202`)

---

## Goal

Give MML a single array type that works for any element type.

Today the language has three separate array types, one per element type, each with
its own set of functions. This design replaces them with one `Array` type and one set
of operations.

- Arrays are a language primitive, like structs.
- `Array` takes one invariant element type parameter.
- Arrays own their elements.
- Array operations are intrinsics with dedicated AST nodes.
- Codegen chooses storage using size, scope, and escape information.
- General polymorphism is not required for this step.

---

## Scope

In scope:

- `Array` as a built-in type, written `Array Int`, `Array Float`, `Array String`, and
  so on.
- Array literals: `[1, 2, 3]`, and typed empty literals.
- Indexed reads: `xs[i]`.
- Sized construction, element access, and length.
- Type checking for array types and array literals.
- Code generation for all of the above.
- Ownership of storage and elements, destruction, and element-dependent cloning.
- Mutation effects and element-borrow tracking; the invalidation policy remains open.

Not in scope:

- User-written functions that are generic over the element type. Code can use
  `Array Int` or `Array String` directly — as values, parameters, and struct fields —
  but cannot yet write one function that works for any element type. That waits for
  the polymorphic type system.
- User-defined generic types.
- Optimizer work beyond storage placement and straightforward literal construction.

---

## What this replaces

The standard library currently provides three array types — `IntArray`,
`StringArray`, `FloatArray` — each a heap struct of a length and a data pointer. It
also provides a separate construction, element access, length, free, and clone
function for each, and the unchecked accessors exist only for some element types.

The compiler injects these declarations through `injectBasicTypes` and
`injectCommonFunctions` in
[`semantic/package.scala`](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala).
`prelude.mml` is a reference listing; the compiler does not load it.

This design replaces:

- The three injected types with intrinsic `Array`.
- Their injected functions and matching `mml_runtime.c` implementations with
  compiler-generated operations.

Element access and length are emitted directly. Heap allocation can use a small
runtime helper. Destruction helpers are generated as needed; clone helpers require
cloneable elements.

---

## The array type

### One type constructor

`Array` is a built-in type that takes exactly one type argument: the element type.

```mml
let xs: Array Int = [1, 2, 3];
let ys: Array Float = [1.0, 2.0, 3.0];
let zs: Array (Array Int) = [[1, 2], [3, 4]];
```

`Array` on its own is not a type; it must be applied to an element type. Applying it
to zero or more than one argument is an error.

### Element type matching

The element type parameter is invariant.

`Array A` and `Array B` are the same type only when `A` and `B` are the same type.
There is no widening between array types: an `Array Int` is never accepted where an
`Array Float` is expected, and the other way around.

### Arrays in other positions

An array type can be used anywhere a type can: variable bindings, function parameters
and results, and struct fields.

```mml
struct Grid { width: Int, height: Int, cells: Array Int };

fn row_sum(xs: Array Int): Int = ... ;
```

### Mutability

Arrays are mutable. `set` writes a slot in place. The length is fixed when the array
is created.

---

## Surface syntax

### Type syntax

An array type is written by juxtaposition, the element type following `Array`:

- `Array Int`
- `Array String`
- `Array (Array Int)` — parentheses group a nested array type.

### Literal syntax

An array literal is a comma-separated list of elements in square brackets:

- `[1, 2, 3]`
- `["a", "b"]`
- `[]`

All elements must have the same type. That type becomes the element type of the array.

### Empty literal

`[]` has no elements to take a type from, so its element type must come from context —
an ascription, or the expected type at its use site:

```mml
let xs: Array Int = [];
```

An `[]` with no such context is a compile error. There is no `Array Nothing` fallback.

### Indexed access

```mml
let xs = [1, 2];
let z = xs[1];
```

`xs[i]` is checked element access, equivalent to `array_get xs i`.
This sequence-notation subset includes literals and indexing only.

---

## Operations

Names below are provisional; final names are an open question (see the end of this
document).

### Construction

For freely copyable elements, `array_new n init` fills `n` slots with copies of `init`:

```mml
let xs = array_new 100 0;     // Array Int, 100 zeros
let ys = array_new 100 0.0;   // Array Float
```

- Every slot must contain a valid initialized value.
- Freely copyable values can be repeated across slots.
- Resource elements require a separate owned value per slot.
- Resource initialization remains open: explicit cloning or per-element construction.
- Repetition must not silently duplicate ownership or require every element type to clone.

Array literals evaluate elements once, in source order, and transfer resource elements
into their slots. The existing ownership rules for aggregate construction apply.

### Element access

- `array_get arr i` reads the element at index `i`.
- Freely copyable elements are copied; resource elements are borrowed.
- Reading does not clone a resource or move it out of its slot.
- `array_set arr i v` takes ownership of `v` and replaces the slot's value.
- Replacement destroys the previous occupant when its type requires destruction.
- Evaluate the replacement value before destroying the previous occupant.
- A borrowed resource element cannot supply ownership to `set` without duplication
  through an explicit clone operation supported by its type.

Both check that `i` is within bounds. An out-of-bounds index stops the program with an
error. This matches what the current checked accessors do.

### Unchecked element access

- `array_unsafe_get arr i`
- `array_unsafe_set arr i v`

The same as the checked forms, with no bounds check. They exist for hot loops where
the index is already known to be valid; an out-of-bounds index here is undefined
behavior. The performance-sensitive sample programs depend on these, so they are
included from the start.

Checked and unchecked operations have identical ownership and mutation rules.

### Length

The length is a field of the array. Read it directly:

```mml
let n = xs.length;
```

---

## How it is built

### Type AST

- A dedicated array type node carries the element type.
- Surface type application resolves `Array T` to that node.
- Arity and invariance are checked without general polymorphism.

### Term AST

- Array literals have a node containing their element expressions.
- Construction, reads, writes, and length have intrinsic nodes.
- Access nodes retain whether bounds checking is required.
- Binding, scoping, and sequencing retain the lambda/application representation.

### Parser

- In type position, parse `Array <type>` as the applied type.
- In term position, parse `[ e1, e2, ... ]` as an array literal.
- Parse `xs[i]` as checked array access.
- Do not add sequence patterns or alternative-container desugaring in this step.

### Type checking

- An array type resolves through `Array` as a built-in constructor with exactly one
  argument.
- For a non-empty literal: infer each element's type, check they all agree, and give
  the literal the type `Array <element>`.
- For `[]`: take the element type from the ascription or the expected type; with
  neither, report a dedicated error.
- Report a clear error for mixed element types in a literal, and for `Array` applied
  to the wrong number of arguments.

### Code generation

- Element types determine slot size, alignment, and layout.
- Codegen chooses backing storage using size, scope, and escape information.
- Known-length local storage can use LLVM `alloca [N x T]`.
- Runtime-length local storage can use `alloca T, count`.
- Storage that must outlive the invocation requires heap allocation.
- Stack placement requires proof that uses stay within the storage lifetime.
- A length/data-pointer representation can describe either storage choice.
- `getelementptr` addresses elements in either representation.
- Checked access emits a bounds check; unchecked access omits it.
- Reads and writes emit loads and stores, plus required element cleanup on replacement.
- Literals allocate storage and initialize each slot. Freely copyable constants may
  use a block copy.
- Operation lowering is specialized to each concrete element type.

### Memory and ownership

- Arrays own their storage and elements, including on the stack.
- Existing ownership, borrowing, transfer, and escape rules apply.
- Storage placement does not change ownership semantics.
- Array destruction runs required element cleanup, then releases heap backing storage.
- Stack-backed arrays run required element cleanup; their storage expires automatically.
- Cloning produces independently owned storage and elements.
- Freely copyable elements use value copies; resource elements require a clone contract.
- Non-cloneable elements are allowed. Their arrays have no clone operation.

### Mutation effects and borrows

- Element borrows depend on their owning array.
- Replacing an element can invalidate borrows even while the array remains alive.
- Struct field replacement has the same ownership and borrow-validity requirements.
- Mutation effects identify the affected aggregate or argument.
- Effects and borrow dependencies must survive aliases, captures, and function calls.
- Checking only direct writes in the caller is insufficient.

Proposed first rule — not settled:

1. Track the owning array of each resource-element borrow.
2. Any write to that array invalidates all existing element borrows from it.
3. Reject later uses of invalidated borrows; a fresh read is allowed.
4. Copied scalar values remain valid.
5. Index-specific tracking is optional later precision.

```mml
let x = xs[0];
array_set xs 0 replacement;
println x;                  // Rejected under the proposed rule.
```

The complete rule must also cover mutation through aliases during an active call.
Borrow invalidation remains an open design decision.

The [replacement probe](../../../mml/samples/mem/array-element-replacement.mml)
demonstrates the existing runtime gap: overwriting leaks the original string.
Freeing it without a borrow rule would expose a use-after-free.

---

## Migration

1. Add `Array` and the array literal; keep the existing array types in place.
2. Move the sample programs and tests over to `Array`.
3. Remove `IntArray`, `StringArray`, `FloatArray` and their functions from
   `injectBasicTypes` and `injectCommonFunctions` in `semantic/package.scala`.
4. Remove the matching code from `mml_runtime.c`.
5. Update the reference listing in `prelude.mml`.

Intermediate breakage between these steps is fine.

---

## Implementation order

1. Type and term AST, parser: array types, literals, indexing, and intrinsics.
2. Type checking: array types, literal typing, the empty-literal error.
3. Code generation for an element type that does not own heap memory first
   (`Array Int`), then any element type.
4. Memory handling: destruction, conditional clone support, ownership integration.
5. Construction and access operations, replacement cleanup, and mutation/borrow checks.
6. Tests at each step; migrate the samples last.

---

## Open questions

1. Final names for the operations (`array_new`, `array_get`, and the rest).
2. Whether to also allow a one-argument `array_new n` for element types that have a
   natural zero, as a shorthand.
3. Resource initialization: explicit cloning or per-element construction; syntax and
   ownership of the initializer.
4. Borrow invalidation policy, mutation-effect contracts, and propagation through calls.
5. Whether constant array literals get the single-copy optimization in the first
   version or a later one.

---

## Related documents

- [Sequence notation](seq-notation.md): broader sequence syntax and other container types.
  This design covers array literals and indexing only.
- [Struct field mutation](../../../context/tasks/struct-field-mutation.md): the shared
  replacement and borrow-invalidation problem.
