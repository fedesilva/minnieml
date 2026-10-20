# Unions

Status: Draft
Task: [Implement unions](../../../context/tasks/unions.md)

---

## Goal

Give MML named alternatives with singleton or struct-like payload constructors.

---

## Scope

Unions cover constructors, inference, variant type ascription, and ownership.
Integer-backed named constants belong to [enums](enums.md).

---

## Declaration syntax

A declaration starts with `union`, followed by its name, any type parameters, and
`=`. Each variant starts with `|`; a semicolon ends the declaration. Type parameters
use the normal apostrophe-prefixed syntax and belong to the enclosing union.

```text
union Maybe 'T =
  | One 'T
  | None
;

union Session 'T 'P =
  | LoggedIn { id: 'T, payload: 'P }
  | NotLoggedIn
;
```

A variant name alone declares a nullary variant. A type after the name declares a
variant that wraps a single value of that type, as in `One 'T`. Braces declare named
payload fields, using comma-separated `name: Type` entries, as in `LoggedIn`.

Variants may use any of the enclosing union's type parameters. A nullary variant
still belongs to the parameterized union even though it carries no payload.

---

## Constructors

- A union declares a set of variants, each with a value constructor.
- Union names and variant names follow the normal type-identifier grammar, starting
  with an uppercase letter.
- A nullary constructor returns the variant's singleton value.
- A payload constructor works like a struct constructor.
- Payload constructors use normal function application, such as `One 42`; named
  payload fields supply constructor arguments in declaration order, as for structs.

---

## Typing

- Constructor results are inferred as the enclosing union type by default.
- An explicit type ascription can preserve a specific variant type or a subset of
  the union's variants.
- The union type denotes all its variants. A qualified variant type denotes one
  alternative; `|` combines alternatives from the same union into a variant set.
- Subtyping is limited to inclusion between variant sets of the same union. A value
  whose type denotes a smaller set can be used wherever a larger set is expected.
- A value typed as the enclosing union must be narrowed before it can be used where
  a smaller variant set is expected. An ascription alone cannot establish that an
  arbitrary union value belongs to that smaller set.
- Exhaustiveness checking uses the variants admitted by the scrutinee's type.

Type ascriptions use the normal `: Type` syntax. A specific variant is named with
`Union.Variant`; a variant set uses `|` between qualified variant types.

For example:

```text
union Shape =
  | Circle
  | Rectangle
  | Square
  | Triangle
;

fn squarelike(s: Shape.Rectangle | Shape.Square): Bool = ???;
```

`squarelike` accepts rectangles and squares. A value of type `Shape.Square` can be
passed directly, and a value of type `Shape.Rectangle | Shape.Square` can also be
used wherever `Shape` is expected. A value of type `Shape.Circle` cannot be passed
to `squarelike`; a value inferred as `Shape` requires narrowing first. Matching on
`s` needs only the `Rectangle` and `Square` cases to be exhaustive.

---

## Ownership

All ownership rules that apply to structs also apply to payload variants.

---

## Open questions

1. Variant inspection and payload access.
2. Runtime representation.
