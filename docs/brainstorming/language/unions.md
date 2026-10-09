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

## Constructors

- A union declares a set of variants, each with a value constructor.
- A nullary constructor returns the variant's singleton value.
- A payload constructor works like a struct constructor.

---

## Typing

- Constructor results are inferred as the enclosing union type by default.
- An explicit type ascription can give a value its specific variant type.
- This is a dedicated union typing rule, not subtyping.

---

## Ownership

All ownership rules that apply to structs also apply to payload variants.

---

## Open questions

1. Declaration, constructor, and variant type ascription syntax.
2. Variant inspection and payload access.
3. Runtime representation.
