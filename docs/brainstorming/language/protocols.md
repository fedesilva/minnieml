# Protocols

Status: Draft

---

## Goal

Provide ad hoc polymorphism through parameterized contracts and concrete instances.
Protocols let comparison support grow beyond `Int`, `Float`, and `String`—the three
types with dedicated arrays—to types such as [enums](enums.md). They also let integer
and floating-point arithmetic share operator names, replacing the split between
`+` and `+.` with type-based instance selection.

Protocols are also a prerequisite for
[Memory evolution](../mem/mem-evolution.md#fundamental-protocols): `Unique` defines
ownership and dropping, while `Clone` provides explicit duplication. The compiler
enforces their rules for both user-supplied and derived implementations.

---

## Scope

- Protocol declarations, type parameters, instances, and instance resolution.
- Function and operator members; operators use the same protocol mechanism as functions.
- Compiler-derived equality instances for enums.
- General polymorphic functions and data types: out of scope.

---

## Type parameters

- Type parameter syntax: apostrophe-prefixed names, such as `'T`.
- Protocols declare parameterized contracts; concrete instances supply implementations.
- Future polymorphic definitions use the same type-parameter syntax.

---

## Declarations and instances

Provisional headers:

```text
protocol CanEqual 'T = ...
instance CanEqual for EventKind = ...
```

- Member syntax: undecided.
- Equality protocol name: provisional (`CanEqual`).
- The compiler derives an equality instance for each enum.
- Derived enum equality accepts values of the same enum.
- Shared integer backing does not permit equality between unrelated enums.

---

## Operators and protocols

- Free operators allow at most one unary and one binary definition per symbol.
- Within each protocol, the same unary/binary overload limit applies.
- Operator resolution searches free operators and protocols in scope.
- Across protocols and free operators, multiple candidates for the same symbol and
  arity are allowed only when all share the same associativity and precedence.
- Conflicting associativity or precedence is an error before expression rewriting;
  operand types cannot rescue the conflict.
- The expression rewriter determines grouping and arity using those shared attributes.
  It preserves the matching candidates for the type checker to select using type
  information. No applicable candidate or unresolved ambiguity is an error.

- Protocol operators use the same instance-resolution mechanism as protocol functions.

---

## Deferred integer conversion

- Status: deferred; not a prerequisite for enums.
- Proposed protocol: `FromInt 'T`.
- Proposed enum derivation: `toElement` uses a compiler-generated `if`/`else` table.
- Failure contract: undecided.
- A `Maybe 'T` result requires unions and polymorphic data types.

---

## Open questions

1. Complete declaration, member, and instance syntax.
2. Equality protocol name and members, including the relationship between `==` and `!=`.
3. How calls select instances and report missing or ambiguous instances.
4. Instance visibility, duplicate instances, and interaction with compiler derivation.
5. Migration of built-in operators beyond equality to protocol instances.
6. Whether the first implementation supports multiple protocol type parameters.

---

## Related documents

- [Enums](enums.md): the immediate consumer of derived equality.
- [Operator precedence and associativity](../../articles/2025-02/2025-02-24-custom-operators.md#future-work-and-open-problems):
  candidate preservation and shared operator attributes before type-based selection.
- [Mechanical sympathy](../mechanical-sympathy.md#protocol-based-operations): numeric
  protocol examples.
- [Memory evolution](../mem/mem-evolution.md#fundamental-protocols): proposed `Unique`
  and `Clone` contracts recognized by the compiler.
