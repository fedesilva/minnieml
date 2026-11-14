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
- Initial backing storage is heap allocated; storage placement optimization is a later subtask.
- General polymorphism is not required for this step.


String unification is out of scope. `String` remains a distinct type; arrays may still
contain strings as elements.

---

## Scope

This proposal can be implemented with runtime bounds checks. Static bounds proofs,
refinement types, and validated index types are independent follow-on work, not
prerequisites for intrinsic arrays.

In scope:

- `Array` as a built-in type, written `Array Int`, `Array Float`, `Array String`, and
  so on.
- Array literals: `[1, 2, 3]`, and typed empty literals.
- Indexed reads: `xs[i]`.
- Sized construction, element access, and length.
- Type checking for array types and array literals.
- Code generation for all of the above.
- Ownership of storage and elements, destruction, and element-dependent cloning.
- Mutation effects and element-borrow tracking, including propagation through calls.

Not in scope:

- User-written functions that are generic over the element type. Code can use
  `Array Int` or `Array String` directly — as values, parameters, and struct fields —
  but cannot yet write one function that works for any element type. That waits for
  the polymorphic type system.
- User-defined generic types.
- Storage placement optimization in the initial implementation. It has a
  [later subtask](../../../context/tasks/intrinsic-arrays.md#storage-placement-optimization).
- String unification and optimizer work beyond straightforward literal construction.

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

The typed `ar_*` function families are replaced by one intrinsic per operation, with no
element type in its name. For example, `array_get arr i` derives its result type and
lowering from the element type of `arr`. These are compiler operations, not wrappers
around the existing C implementation. The per-element-type C array implementation is
removed; array layout, access, initialization, and element cleanup are lowered by the
compiler. A small runtime allocation helper may remain.

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

### Ownership and duplication

This proposal follows the [memory-evolution decisions](../mem/mem-evolution.md#decisions):
only scalars may be implicitly duplicated. Aggregates, including scalar-only structs
and arrays, retain ownership even when their drop operation is a no-op. Reads borrow
non-scalar elements; duplicating them requires an explicit `Clone` operation.
These are proposed array semantics, not a description of current compiler support.

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

An `[]` with no such context is a compile error. MML has no `Nothing` type; there is no
`Array Nothing` fallback.

### Indexed access

```mml
let xs = [1, 2];
let z = xs[1];
```

`xs[i]` is checked element access, equivalent to `array_get xs i`.
This sequence-notation subset includes literals and indexing only.


### Indexed mutation

```mml
let xs = [ 0, 2 ];
xs[0] = 1;;
```

---

## Operations

Names below are provisional; final names are an open question (see the end of this
document).

### Construction

For scalar elements, `array_new n init` fills `n` slots with copies of `init`:

```mml
let xs = array_new 100 0;     // Array Int, 100 zeros
let ys = array_new 100 0.0;   // Array Float
```

- Every slot must contain a valid initialized value.
- Scalars can be repeated across slots.
- Non-scalar elements require a separate owned value per slot, including scalar-only
  aggregates.
- Sized non-scalar construction uses an initializer that supplies an owned value for
  each element. Its surface syntax remains to be specified.
- Construction does not clone elements or require `Clone`. Repeating one non-scalar
  seed value is not a construction operation.

Array literals evaluate elements once, in source order, and transfer non-scalar elements
into their slots. The existing ownership rules for aggregate construction apply.
The literal is represented directly by a `Sequence` AST node; it does not require
an initializer lambda.

### Element access

- `array_get arr i` reads the element at index `i`.
- Scalar elements are copied; non-scalar elements are borrowed, including scalar-only
  aggregates.
- Reading does not clone a non-scalar element or move it out of its slot.
- `array_set arr i v` takes ownership of `v` and replaces the slot's value.
- Replacement destroys the previous occupant when its type requires destruction.
- Evaluate the replacement value before destroying the previous occupant.
- A borrowed non-scalar element cannot supply ownership to `set` without duplication
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

- A `Sequence` node contains an ordered list of expressions, with an empty list for `[]`.
  Every element is an expression: a literal, constructor call, function call, conditional,
  or any other expression whose result becomes an array element.
- Construction, reads, writes, and length have intrinsic nodes.
- Access nodes retain whether bounds checking is required.
- Binding, scoping, and expression sequencing retain the lambda/application representation.
  `Sequence` represents collection literals, not statement sequencing.

For example, `let a = [1, 2, 3]` has a `Sequence` initializer with three literal
children. `[(Struct a b), (Struct c d)]` has two constructor-application children.
Type checking establishes their common element type and the resulting array type.

Keep `Sequence` directly in the AST initially. If reusing lambda type checking or
ownership analysis is useful, the compiler may lower it to a generated lambda that
calls array intrinsics. Generated construction code may receive uninitialized array
storage internally. It must initialize every slot exactly once before exposing the
finished array, and must not read or destroy uninitialized elements. This is an
internal lowering choice, not a user-facing initialization API or required desugaring.
Either representation preserves source evaluation order and ownership transfers.

The node also provides a place to support the broader [sequence notation](seq-notation.md)
proposal: retain the element expressions before choosing how to construct a container.
Only array construction is included here. Alternative containers, their desugaring
protocol, and sequence patterns remain separate work.

### Parser

- In type position, parse `Array <type>` as the applied type.
- In term position, parse `[ e1, e2, ... ]` into `Sequence`.
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
- Cache concrete element layout information and reuse generated destruction and clone
  helpers for the same element type within a compilation. Reads and writes lower directly;
  they do not require generated accessor functions.
- Initial backing storage is heap allocated, matching the existing array families.
- A candidate heap-allocation interface takes the element count, slot size, and required
  alignment. The compiler supplies target-derived layout information; the runtime helper
  allocates backing storage without knowing the source element type.
- Arrays use a length/data-pointer representation; `getelementptr` addresses elements.
- A later storage optimization may use size, scope, and escape information to select
  stack storage. It requires lifetime proof and bounded stack use.
- Checked access emits a bounds check; unchecked access omits it.
- Reads and writes emit loads and stores, plus required element cleanup on replacement.
- Literals allocate storage and initialize each slot. Scalar constants may use a block
  copy. A block copy must not introduce implicit duplication of non-scalar elements.
- For a scalar constant literal, codegen may emit a constant data blob, allocate backing
  storage, and copy the blob with `memcpy`. Element widths, byte counts, and alignment come
  from the target layout, not fixed assumptions about `Int`. This remains the optional
  literal optimization listed under Open questions.
- Operation lowering is specialized to each concrete element type.

### Memory and ownership

- Arrays own their heap backing storage and elements.
- Existing ownership, borrowing, transfer, and escape rules apply.
- Storage placement does not change ownership semantics.
- Array destruction runs required element cleanup, then releases heap backing storage.
- Future stack placement must preserve element cleanup while letting backing storage
  expire automatically.
- Cloning produces independently owned storage and elements.
- Cloning copies scalar elements and explicitly invokes the clone contract for each
  non-scalar element, including scalar-only aggregates.
- Non-scalar elements without `Clone` are allowed. Their arrays have no clone operation.

### Mutation effects and borrows

- Element borrows depend on their owning array.
- Replacing an element can invalidate borrows even while the array remains alive.
- Struct field replacement has the same ownership and borrow-validity requirements.
- Mutation effects identify the affected aggregate or argument.
- Effects and borrow dependencies must survive aliases, captures, and function calls.
- Checking only direct writes in the caller is insufficient.

Initial invalidation rule:

1. Track the owning array of each non-scalar element borrow.
2. Any write to that array invalidates all existing element borrows from it.
3. Reject later uses of invalidated borrows; a fresh read is allowed. Reads alone do
   not invalidate borrows.
4. Copied scalar values remain valid.
5. Index-specific tracking is optional later precision.

```mml
let x = xs[0];
array_set xs 0 replacement;
println x;                  // Rejected: x was invalidated by the write.
```

Enforcement follows calls, including transitive calls, aliases, and captured arrays.
A helper that writes an array invalidates its caller's existing element borrows from
that array. A callee must also be checked when it receives both an array and a borrow
of an element: writing the array and then using that borrow is invalid even if the
caller never uses the borrow again. The same requirement applies to captured borrows.
The analysis must carry the information needed to enforce this rule across call
boundaries; checking direct writes or only uses after a call is insufficient.

See [Backward liveness for borrow invalidation](../mem/backward-liveness.md) for a
candidate analysis using borrow provenance, mutation effects, and future uses.

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

## Related documents

- [Sequence notation](seq-notation.md): broader sequence syntax and other container types.
  This design covers array literals and indexing only.
- [Struct field mutation](../../../context/tasks/struct-field-mutation.md): the shared
  replacement and borrow-invalidation problem.

---

## Appendix: Open questions

The core array decisions above are settled. The following questions guide the execution
plan; they do not reopen construction without cloning or invalidation through calls.

1. **Sized-construction API.** What is the initializer's signature and invocation contract?
   The candidate signature is `Int -> (Int -> 'T) -> Array 'T`: a size and a function
   producing an owned element from its index. Specify invocation order,
   zero and negative lengths, and allocation-size overflow. Literals already supply their
   expressions through `Sequence`; this question concerns runtime-sized construction.

2. **Call-effect analysis.** How will mutation effects and borrow dependencies be represented
   and propagated through recursion, higher-order functions, native calls, and captures?
   Following calls is required. The plan must explain how it enforces invalidation both
   within a call and in its continuation.

3. **Implementation order.** How should the work be split into small executable stages?
   Each stage needs its required ownership handling and cleanup. Aggregate replacement
   must arrive together with borrow invalidation. Refine the implementation outline above
   into stages with explicit dependencies and review points.

4. **Planning gate.** Which details should the execution plan propose rather than require
   settled before drafting? These include operation names, analysis representation, and
   whether to defer constant-literal block-copy optimization. The task currently requires
   open decisions settled before preparing stages; reconcile that wording with a plan
   that can present concrete proposals. Also settle whether scalar construction needs a
   one-argument zero-initialization shorthand.

5. **Verification and signoff.** What are the acceptance checks for each stage? Separate
   initial heap-backed array signoff from later storage-optimization signoff. Include
   typing, evaluation order, initialization, bounds, ownership, replacement cleanup,
   calls and captures, and migration in the relevant stages.

6. **Supporting-document consistency.** The backward-liveness draft still describes the
   array invalidation policy as open. The policy is settled here; the analysis approach
   remains a candidate. Reconcile that distinction when updating the supporting draft.

### Generic functions and an Array module

[Generic functions](generic-functions.md) specifies the typing foundation to address
before intrinsic arrays, including inference, explicit type arguments, constraints,
and the `array_init` example. Initializers follow ordinary closure ownership rules;
construction requires a reusable initializer supplying owned non-scalar results, not
move capture or cloning.

The intrinsic-array scope does not itself implement ordinary generic functions. Their
implementation stages and relationship to the array plan need to be planned together.

Nested modules offer a home for the operations. A file is a module, and the proposed
inner-module form is:

```text
module X =
  ...
;
```

The compiler could synthesize an `Array` module containing intrinsic operations, exposing
`Array.init` rather than a naked `array_init`. Names remain provisional. Name resolution
must support `Array` as the type constructor in `Array Person` and the module in
`Array.init`. Module qualification does not itself provide generic typing.

Nested-module implementation remains a separate scope decision.
