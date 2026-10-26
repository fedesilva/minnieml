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

## Syntax

Protocol members use the ordinary function declaration shape, with named, typed
parameters and a return type, but no body:

```text
protocol Semigroup 'T =
  fn combine(left: 'T, right: 'T): 'T;
;

instance Semigroup Int =
  fn combine(left: Int, right: Int): Int = left + right;;
;
```

Instance headers apply the protocol to a type directly, without `for`. A bodyless
member has one declaration terminator; an implementation also terminates its body
expression. Nullary members use `()`, as in `fn empty(): 'T;`.

See the [standard-library syntax sample](../snippets/lib.mml.txt) for the protocol
family and the full `Option` Semigroup example. The sample explores future features;
it is not a compilable library or an expansion of the initial implementation scope.

### Type parameters and constraints

Type variables have an apostrophe: `'T` is a type variable and `'F 'T` applies a
type-constructor variable to it. Concrete constructors have no apostrophe, as in
`Option 'T`.

Function signatures introduce type variables that are not already bound by an enclosing
protocol or instance. There is no type-parameter list between the function name and
its value parameters:

```text
fn empty(x: Maybe 'T): Bool = ...

protocol Functor 'F =
  fn map(f: 'A -> 'B, values: 'F 'A): 'F 'B;
;
```

Here `'F` belongs to the protocol; `'A` and `'B` belong to `map`. Function constraints
follow the return type in a `where` clause, before `=`:

```text
fn empty(x: Maybe 'T): Bool
  where 'T: Clone = ...

fn combine(left: Option 'T, right: Option 'T): Option 'T
  where 'T: Semigroup & Clone = ...
```

These headers illustrate constraint placement; checking whether an option is empty
does not itself require cloning. Multiple constraints on one variable use `&`.

Protocol and instance headers retain constraints on their parameters, using `:` and `&`:

```text
protocol Monoid 'T: Semigroup =
  fn empty(): 'T;
;

instance Semigroup (Option 'T: Semigroup) = ...
instance Semigroup (Option 'T: Semigroup & Clone) = ...
```

The instance headers introduce `'T`; the constraints apply to that element type.
The first requires its Semigroup instance. The second additionally requires its
Clone instance. In the sample, two `Some` values combine their contents using the
Semigroup instance for `'T`, while `None` acts as an identity. Union and match syntax
in that example is illustrative. Parameterized instances and multiple constraints
are future syntax, beyond the initial concrete-instance scope.

---

## Derived equality

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

1. Operator member syntax and whether instance implementations may omit type annotations.
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
