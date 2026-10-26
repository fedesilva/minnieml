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
- Explicit imports: out of scope.

---

## Type parameters

- Type parameter syntax: apostrophe-prefixed names, such as `'T`.
- Protocols declare parameterized contracts; concrete instances supply implementations.
- Future polymorphic definitions use the same type-parameter syntax.

---

## Syntax

Protocol members use the ordinary function declaration shape, with named, typed
parameters and a return type. A member without a default implementation has no body:

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

Instance implementations may omit parameter and return type annotations supplied by the
protocol contract. For example, the Semigroup instance above may be written as:

```text
instance Semigroup Int =
  fn combine(left, right) = left + right;;
;
```

The contract supplies `Int` for both parameters and the result. Explicit annotations
remain allowed and must agree with the contract. This rule applies to function and
operator implementations alike.

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

## Instance identity and member resolution

There is one instance per protocol and concrete type combination. A second instance
for the same combination is an error; instance selection never chooses between duplicate
implementations.

Protocol members participate in ordinary function-name lookup without requiring the
protocol name. Lookup collects matching functions and protocol members as candidates;
final selection is deferred until the type checker has the necessary type information.

```text
let total = combine 2 3;
let zero: Int = empty ();
```

Normal type checking uses argument and expected result types to select the applicable
member and instance. With the Semigroup and Monoid declarations above, the integer
arguments select `Semigroup Int`, and the result annotation selects `Monoid Int`.
Insufficient type information, no applicable candidate or instance, and unresolved
ambiguity are errors.

Functions and operators share this candidate-based resolution model. It permits ordinary
operator syntax while giving named protocol functions the same lookup behavior. Operator
grouping still requires compatible precedence and associativity before type checking.
Explicit imports remain out of scope.

---

## Protocol requirements and default implementations

A protocol constraint requires a separate instance for the same type. For example,
`Monoid 'T: Semigroup` requires `Semigroup Int` before `Monoid Int` can be satisfied.
The Monoid instance implements its own members; it cannot replace the Semigroup
instance's `combine`. There is one meaning for that operation, whether the caller
requires Semigroup alone or Monoid.

Requirements apply transitively. A future function constrained by `'T: Monoid` can
also use the operations guaranteed by Semigroup. These operations belong to their
original protocols and are supplied by the required instances, not reimplemented in
the dependent instance. The sample's `Monad Maybe` therefore has no member bodies;
its requirements are supplied by the separate Functor, FlatMap, and Applicative instances.

Protocol members may supply default bodies:

- A member without a body must be implemented by every instance.
- A member with a body may be omitted by an instance, which uses the default.
- An instance may override a default for a member declared by its own protocol.
- A default may use other protocol members and declared requirements. It is checked
  using only those guarantees, without assuming operations available on a particular
  concrete type.

Defaults apply to both functions and operators. For example, an equality protocol could
require `==` and define `!=` as its negation. The equality protocol's exact contract
remains a separate design decision.

---

## Ownership

Instance methods and protocol default bodies follow normal function ownership rules.
Implementations must honor the declared parameter and return ownership contract.
Protocols introduce no separate ownership rules. See the
[normal function ownership rules](../../memory-model.md#non-consuming-parameters-default)
and [return ownership rules](../../memory-model.md#return-values-and-borrow-escape).

---

## Derived equality

- Equality protocol name: provisional (`CanEqual`).
- The compiler derives an equality instance for each enum.
- Derived enum equality accepts values of the same enum.
- Shared integer backing does not permit equality between unrelated enums.

---

## Operators and protocols

### Operator declaration syntax

Operator member syntax is the ordinary operator declaration syntax. There is no separate
protocol-specific operator signature form: the name is followed by named, typed parameters,
the return type, and the normal precedence and associativity attributes.

A required member omits the body:

```text
op +(left: 'T, right: 'T): 'T 60 left;
```

A default implementation adds `= body`, with the ordinary expression and declaration
terminators. For example, a protocol that requires `==` may supply:

```text
op !=(left: 'T, right: 'T): Bool 40 left = not (left == right);;
```

Instance operators use that same declaration shape and may omit types supplied by the
contract. Ordinary operator rules govern precedence and associativity syntax and defaults.
The required-versus-default distinction and type-annotation rules are the same as for
function members.

### Operator resolution

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

1. **First implementation scope.** Which protocols and concrete instances ship first? Keep the future polymorphic examples distinct from that deliverable.

### Additional open details

- Equality protocol name and members, including the relationship between `==` and `!=`.
- Diagnostics for missing or ambiguous instances.
- Interaction between explicit instances and compiler derivation.
- Migration of built-in operators beyond equality to protocol instances.
- Whether the first implementation supports multiple protocol type parameters.

---

## Related documents

- [Enums](enums.md): the immediate consumer of derived equality.
- [Operator precedence and associativity](../../articles/2025-02/2025-02-24-custom-operators.md#future-work-and-open-problems):
  candidate preservation and shared operator attributes before type-based selection.
- [Mechanical sympathy](../mechanical-sympathy.md#protocol-based-operations): numeric
  protocol examples.
- [Memory evolution](../mem/mem-evolution.md#fundamental-protocols): proposed `Unique`
  and `Clone` contracts recognized by the compiler.
