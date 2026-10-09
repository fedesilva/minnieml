# Enums

Status: Draft
Task: [Implement enums](../../../context/tasks/enums.md)

---

## Goal

Give MML typed, integer-backed named alternatives with a C-compatible representation.

---

## Scope

Enums have named members without payloads. Alternatives with payloads belong to
[unions](unions.md).

---

## Dependency

Enums require [protocols](protocols.md) for ad hoc polymorphism and compiler-derived equality.
Integer-to-enum conversion through `FromInt` is deferred and does not block enums.

Protocols take type parameters, written `'T`, to express contracts such as
`CanEqual 'T` and `FromInt 'T`. Concrete instances supply the implementations.
This introduces type-parameter syntax that will also serve polymorphic definitions
later. Protocols alone do not require generic functions or generic data types.

A `FromInt` result of `Maybe 'T` would additionally require unions and polymorphic
data types. That result contract belongs to the deferred conversion work; neither
unions nor general polymorphism is required for derived enum equality.

---

## Members and typing

- Each member is an integer-backed singleton value.
- A member value is inferred as the enclosing enum type by default.
- An explicit type ascription can give a value its specific member type.
- This is a dedicated enum typing rule, not subtyping.
- There are no implicit conversions between enums and integers or between unrelated enums.

---

## Surface syntax

An enum declaration lists pipe-prefixed members and ends with a semicolon:

```mml
enum EventKind =
  | Seed
  | Select
  | Skip
  | Consider
  | Accept
  | Reject
  | Finish
  | Found
  | Exhausted
;
```

Member references require the fully qualified name, including in type ascriptions.
Bare member names do not resolve from the expected enum type.

```mml
let kind = EventKind.Seed;                   // EventKind
let seed: EventKind.Seed = EventKind.Seed;   // specific member type
```

---

## Ownership

Enums are freely copyable scalar values, like their backing integers. They own no
resources and require no allocation or destructor. Copying or passing an enum does
not consume the original, and storing one in a struct adds no ownership obligations.
A specific member type ascription does not change these rules.

---

## Integer conversions

- Explicit conversion from an enum to its backing integer is always valid.
- Automatic integer-to-enum conversion is deferred. Handwritten `if`/`else` tables
  can compare raw integers and return declared members, with caller-defined handling
  of unmatched values.
- Arbitrary integer values and bitmask combinations require separate treatment.

---

## Compiler-derived protocols

The first implementation automatically derives equality for each enum:

- **CanEqual** (name provisional): equality between values of the same enum.
  Members of unrelated enums do not gain equality through their integer backing.

### Deferred integer conversion

The intended compiler-derived conversion uses **FromInt 'T**, a general-purpose
integer conversion protocol. For an enum, its `toElement` member converts an integer
to an enum value. The compiler synthesizes an `if`/`else` chain comparing the input
against member backing values and returning the matching enum member. The behavior
when no member matches remains open.

The compiler knows which types are enums from their declarations; derivation does
not require a fundamental `Enum` type. `FromInt` can also have instances for other
types. Illustrative declaration and instance headers:

```text
protocol FromInt 'T = ...
instance FromInt for EventKind = ...
```

---

## C integration

C interop uses the backing integer representation at the ABI boundary without weakening
MML's type rules. Incoming raw integer values require validation.

---

## Open questions

1. **Backing integer type:** what is the default, and can declarations select a
   different backing type for C integration?
2. **Member values:** where does automatic numbering start, how does it advance,
   and what syntax assigns explicit values?
3. **Enum-to-integer conversion:** what operation explicitly converts an enum to
   its backing integer?

Deferred conversion questions, not blockers for the first enum implementation:

1. **Failed integer-to-enum conversion:** what does `FromInt.toElement` return or
   do when no member matches? A `Maybe 'T` result requires unions and polymorphic
   data types; a panic does not provide recoverable failure.
2. **Conversion syntax:** how is `FromInt.toElement` invoked, and how is the target
   enum selected?
