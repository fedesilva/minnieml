# Brainstorming: intrinsic arrays as true types

Status: Draft
Ticket: `#202` (`https://github.com/fedesilva/minnieml/issues/202`)

---

## Goal

Define `Array 'T` as a real language type (not a codegen-only pseudo-type) and support
array literals as first-class typed expressions.

---

## Scope and non-goals

In scope:
- `Array 'T` represented explicitly in AST/type AST.
- Parsing and typing of array literals (`[e1, e2, ...]`).
- Type-directed codegen for array literals and core array ops.
- Keep implementation practical for current compiler architecture.

Out of scope (for this phase):
- Full parametric polymorphism everywhere in the language.
- User-defined generic types/functions.
- Advanced optimizer work beyond straightforward literal lowering.

---

## Type model

### 1. `Array 'T` is a true type constructor

`Array` is a built-in type constructor of arity 1.

Examples:
```mml
let xs: Array Int = [1, 2, 3];
let ys: Array Float = [1.0, 2.0, 3.0];
```

### 2. Equality and compatibility

`Array 'A` is compatible with `Array 'B` only when `'A == 'B`.
No covariance for phase 1.

### 3. Empty literal typing

`[]` requires expected type context or explicit ascription:
```mml
let xs: Array Int = [];
```

Without context, `[]` is a type error.

---

## Surface syntax

### 1. Type syntax

Keep juxtaposition form already discussed in project docs:
- `Array Int`
- `Array String`
- `Array (Array Int)`

### 2. Term syntax

Array literal term:
- `[e1, e2, ..., en]`

Rules:
- Elements are comma-separated.
- All elements must unify to one element type.
- Literal type is `Array 'T`.

---

## AST and parser

### 1. Type AST

Add a canonical built-in generic application node that can encode `Array 'T`
without special-casing at string level.

### 2. Term AST

Add/confirm node equivalent to:
- `LiteralArray(items: Seq[Term])`

### 3. Parser behavior

- Parse `Array <TypeSpec>` in type positions as the type-constructor form.
- Parse `[ ... ]` in term positions as array literal.
- Preserve existing list/seq grammar expectations; avoid ambiguous overlap.

---

## Typechecker and resolver behavior

### 1. Type resolution

`Array` resolves as intrinsic type constructor, not user-definable in this phase.
Arity must be exactly one.

### 2. Literal typing

For `[e1, ..., en]`:
- infer each `ei`
- unify all inferred element types to `'T`
- result type: `Array 'T`

For `[]`:
- use expected type if present and shape `Array 'T`
- otherwise emit dedicated error (`CannotInferEmptyArrayType` or equivalent)

### 3. Diagnostics

Prefer explicit errors:
- mixed element types in literal
- invalid `Array` arity
- unknown context for empty literal

---

## Runtime and codegen model

### 1. Representation

Keep runtime container representation aligned with existing container approach:
- array value carries length + data pointer semantics
- element layout is type-directed from `'T`

### 2. Literal lowering

For `[v1, ..., vn]: Array 'T`:
- determine `sizeof('T)` and align from resolved type
- allocate contiguous storage for `n` elements
- initialize each element (constant bulk copy when safe; element stores otherwise)
- construct array value (length + data pointer)
- use llvm's ir [iii x iii] syntax

### 3. Ownership/memory interaction

Because `Array 'T` is a true type, ownership rules should treat it as a heap-bearing
container when `'T` requires ownership-sensitive handling.

Phase 1 expectation:
- ownership semantics follow existing memory policy framework
- no ad-hoc string-name checks for array behavior (PLEASE, no stringy matches)

---

## Intrinsic operations (phase slicing)

Keep minimal starter ops for first delivery:
- construction via literals
- length
- indexed get/set (exact API naming to align with existing resolver constraints)

Longer-term ergonomics (defer):
- richer stdlib APIs (`map`, `fold`, etc.)
- bounds-checking policy refinement

---

## Implementation slice proposal

1. Parser + AST for `Array 'T` and `[ ... ]`.
2. Type resolution and literal typing.
3. Codegen for typed literals (`Array Int` first, then general `'T`).
4. Ownership/runtime integration for the produced representation.
5. Tests (parser, typer, codegen, ownership where applicable).

This keeps work incremental while preserving the "true type" decision.

---

## Open decisions

1. Canonical runtime representation type naming in prelude/runtime.
2. Final public API names for array ops under current overloading constraints.
3. Bounds behavior policy (trap vs recoverable error) for indexed access.
4. Whether array literal constant-packing optimizations are phase-1 or phase-2.

---

## Cross-reference

Tracked by issue `#202`: `https://github.com/fedesilva/minnieml/issues/202`
