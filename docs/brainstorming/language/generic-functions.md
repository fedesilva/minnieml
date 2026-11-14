# Generic functions

Status: Draft

## Goal

Allow a function to work with different types while preserving the relationships between
its parameters and result. Use the same type-variable notation and protocol constraints
as the [protocols design](protocols.md).

All array intrinsics motivate generic typing. Construction, reads, writes, and length
operate on arrays of any element type. Their signatures express the relationships
between `Array 'T`, element values, and results, whether their implementations are
compiler-provided or written in MML. Generic function typing is the foundation to
address before [intrinsic arrays](intrinsic-arrays.md).

This document describes proposed language behavior. Ordinary generic functions and
`where` constraints are not current compiler features.

## Scope

Type variables range over ordinary value types, including concrete structs, arrays, and
function types. Generic functions may accept functions as arguments. A fixed constructor
such as `Array` may be applied to a type variable.

Higher-kinded variables such as `'F 'T`, user-defined generic data types, and nested
modules are separate work. Passing a function that must itself remain polymorphic is
also outside the initial scope. Passing a concrete instance of a generic function is
included. Global inference and automatic generalization of arbitrary bindings are not
required.

## Signatures

A type variable has an apostrophe-prefixed name. Variables appearing in a function
signature belong to that function unless already bound in the enclosing type-variable
scope, such as by a protocol. There is no separate type-parameter declaration list.

```text
fn identity(~value: 'T): 'T = value;;
```

Every occurrence of `'T` refers to the same type. The consuming parameter makes this an
ownership-preserving identity: an owned argument transfers into the function and back
to its caller. It does not implicitly duplicate a non-scalar argument.

For the initial draft, generic declarations specify their parameter and result types.
Whether to infer omitted generic result annotations is a separate decision below.

### Type-variable resolution

Resolve type-variable occurrences before checking the body or inferring call-site type
arguments. Each variable has a stable identity within its declaring scope. Two unrelated
functions may both use the spelling `'T` without referring to the same variable; a member
using its enclosing protocol's `'T` refers to that protocol variable.

For each function signature, collect variables not already bound in the enclosing
type-variable scope and assign them identities. Resolve occurrences throughout the
signature and body annotations against that scope. Resolve `where` clauses against the
in-scope variables and named protocols; constraints do not introduce additional variables.
Nested functions retain enclosing variable identities and introduce their own variables
according to the same signature rule.

Resolution establishes which variable an occurrence denotes. Call-site inference later
determines the type substituted for that variable. Substitution uses resolved identities,
not source spellings.

The proposed implementation extends `TypeResolver` with a scoped type-variable environment,
reusing its traversal of function and protocol signatures. Confirm traversal coverage and
scope handling during implementation planning before deciding whether a separate phase
is needed.

### Protocol constraints

A `where` clause follows the result type and precedes `=`:

```text
fn render(value: 'T): String
  where 'T: Show =
  show value;
;
```

Here `Show` supplies `show(value: 'T): String`, as in the language reference. The body
may call that member because the signature requires its protocol. Without the
constraint, a body that needs `Show 'T` is rejected, even if every observed caller uses
a type with a `Show` instance.

Multiple requirements on one variable use `&`, for example
`where 'T: Show & Clone`. Constraints are minimum requirements: a type may implement
additional protocols. Protocol names describe constraints, not parameter value types.
The clause constrains variables already in scope; it does not introduce unrelated ones.
Syntax for constraints on several distinct variables remains to be specified.

## Type arguments and inference

Calls may infer type arguments from value arguments and the expected result type:

```text
let answer = identity 42;
let render_int: Int -> String = render;
```

Explicit type arguments use ordinary juxtaposition and precede all value arguments:

```text
let answer = identity Int 42;
let int_identity = identity Int;
```

Supply all function type arguments explicitly or infer them all. Type arguments cannot
follow value arguments. Partial explicit type application and `_` placeholders are
deferred. Parentheses group compound type arguments, as in `identity (Array Int)`.

For functions with several type variables, the proposed argument order is first
appearance in the signature: parameter types from left to right, then the result type.
Enclosing type variables do not become additional explicit arguments of an inner
function. This ordering proposal needs review because changing signature order could
change explicit calls.

Inference produces a local substitution for each call candidate. All occurrences of
one variable must agree. Conflicting evidence is an error; unresolved variables require
more type information. Constraints validate a substitution; the compiler does not pick
an arbitrary type just because it has a suitable protocol instance.

Inside another generic body, substitution may map a callee's variable to the caller's
variable. The caller's declared constraints must then establish the callee's requirements.
Concrete calls must satisfy those requirements through the protocol instance rules.

## Body checking

Check a generic body against its declared types and constraints independently of the
concrete calls that happen to exist. A type variable cannot silently become `Int` because
the body uses an operation available only for `Int`.

Calls, returns, and protocol operations must preserve the declared type relationships.
Ownership checking also applies to the generic body: specialization must not make an
otherwise invalid duplication or transfer acceptable for selected callers.

The implementation may specialize checked bodies for concrete types. Specialization
determines layout, selected protocol implementations, and required cleanup; it does not
add undeclared requirements to the source signature.

## Ownership and closures

Generic functions use the existing parameter, return, capture, and invocation rules.
Parameters borrow by default; consuming parameters use `~`. A type variable is not
permission to copy a value. Operations requiring explicit duplication need the appropriate
clone contract. Substituting a concrete type preserves these obligations.

Closures may borrow or own captures under the ordinary rules. Moving a capture out makes
the closure call-once; owning captures alone does not. Generic typing introduces no new
capture mode. See the [memory model](../../memory-model.md#move-closures-).

### Array initialization

Array initialization is one example of a generic array intrinsic. Its proposed signature is:

```text
fn array_init(size: Int, init: Int -> 'T): Array 'T;
```

Assuming `make_person: Int -> Person`, both calls describe the same operation:

```text
let people = array_init 10 make_person;
let people_explicit = array_init Person 10 make_person;
```

`Array` is a fixed type constructor and `'T` is an ordinary element type. Accepting a
function makes `array_init` higher-order; it does not require higher-kinded type variables.

The initializer is reusable. Each call supplies the value for one slot, transferring
ownership of non-scalar results into the array. Construction does not duplicate a seed
value and requires no `Clone` constraint.

The array owns the results, not the initializer or its captures. A borrowing closure may
read configuration and construct a fresh owned result on every call. A move closure is
also suitable when reusable. A closure that transfers an owned capture on invocation is
call-once and cannot satisfy this reusable-initializer contract. Enforce the ordinary
closure rules; do not require move capture merely because the array owns its elements.

The signature above shows the type relationship. The compiler must also preserve the
initializer's invocation and result-ownership contract across function boundaries; the
arrow spelling alone is not evidence that a result is owned. How existing callable
metadata carries that contract is an implementation concern, not a new closure rule.

Array allocation, index invocation order, invalid lengths, and overflow belong to the
array specification.

## Function values, partial application, and recursion

An explicit type application such as `identity Int` selects a concrete function value
without invoking its body. Expected function types may also determine the substitution,
as in the `render_int` example. A value with unresolved type variables is rejected in
the initial scope rather than stored as a polymorphic callable.

Value partial application uses the existing application and ownership rules after type
arguments have been determined. Arguments and the expected remaining function type may
jointly provide that type information. Capturing an argument in a partial application
preserves its ordinary ownership and call-once behavior.

The proposed first implementation supports recursion at the same type substitution.
Recursion that changes type arguments, including through mutual recursion, needs a
separate decision and must not cause unbounded specialization. Generic local functions
must preserve enclosing type-variable identity and ordinary capture behavior.

## Implementation decisions

The compiler already represents protocol type variables, substitutes concrete instance
types into member signatures, and selects concrete protocol members. Ordinary generic
functions additionally need abstract body checking and instantiation at use sites.
See [compiler design](../../design/compiler-design.md).

Concrete specialization is a candidate execution strategy. Before implementation, trace
`identity`, `render`, and an array-initializer wrapper through type resolution, body
checking, calls, ownership analysis, and code generation. Settle:

- Representation of resolved type-variable identities and declared constraints, and the
  proposed scoped environment in `TypeResolver`.
- Explicit type-argument ordering and parsing, including compound types.
- Constraint syntax for multiple variables and policy for omitted result annotations.
- Shared call inference for intrinsics, ordinary functions, and protocol members.
- Representation of reusable callable and result-ownership facts through generic calls.
- Specialization identity, reuse, recursion handling, and phase placement relative to
  ownership analysis and closure generation.
- Whether member-local generic functions ship with ordinary generic functions.

An `Array` module may later provide qualified wrappers such as `Array.init`. Module
qualification does not provide generic typing, and nested-module implementation is not
part of this proposal.

## Acceptance examples

The implementation plan should demonstrate:

- Inferred and explicit calls agree, including compound and function type arguments.
- Repeated occurrences of a variable reject inconsistent concrete types.
- Expected types resolve function values and partial applications; insufficient evidence
  produces a focused diagnostic.
- Missing constraints fail in the generic body; missing instances fail at concrete calls.
- Constraints propagate through calls between generic functions.
- Consuming identity transfers an owned aggregate without duplication or double cleanup.
- Borrowed captures can produce owned array elements; a call-once initializer is rejected.
- Recursive calls reuse a specialization; unsupported changing-type recursion is diagnosed.
- Names, shadowing, captures, and source diagnostics survive specialization.

These are requirements for a future implementation plan, not claims of passing tests.
