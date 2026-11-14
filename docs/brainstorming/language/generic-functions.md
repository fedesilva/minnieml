# Generic functions

Status: Draft

## Goal

Allow a function to work with different types while preserving the relationships between
its parameters and result. Use the same type-variable notation and protocol constraints
as the [protocols design](protocols.md).

This document describes proposed language behavior. Ordinary generic functions and
`where` constraints are not current compiler features.

## Scope

Type variables range over ordinary value types, including concrete structs and function
types. Generic functions may accept functions as arguments. Fixed type constructors may
be applied to a type variable.

Higher-kinded variables such as `'F 'T`, user-defined generic data types, and nested
modules are separate work. Passing a function that must itself remain polymorphic is
also outside the initial scope. Passing a concrete instance of a generic function is
included. Global inference and automatic generalization of arbitrary bindings are not
required.

## Signatures

A type variable has an apostrophe-prefixed name. Variables appearing in a function
signature belong to that function unless already bound by an enclosing function.
There is no separate type-parameter declaration list.

```text
fn identity(~value: 'T): 'T = value;;
```

Every occurrence of `'T` refers to the same type. The consuming parameter makes this an
ownership-preserving identity: an owned argument transfers into the function and back
to its caller. It does not implicitly duplicate a non-scalar argument.

Generic functions follow ordinary function annotation rules. When the result annotation
is omitted, infer it from the body using the declared parameter types and constraints,
independently of concrete callers. Inference does not introduce undeclared constraints.
For example, `fn identity(~value: 'T) = value;;` has inferred result type `'T`.

### Type-variable resolution

Resolve type-variable occurrences before checking the body or inferring call-site type
arguments. Each variable has a stable identity within its declaring scope. Two unrelated
functions may both use the spelling `'T` without referring to the same variable. A nested
function using its enclosing function's `'T` refers to that enclosing variable.

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

A `where` clause follows the parameter list and optional result annotation, and precedes `=`:

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
Separate constraints on distinct variables with commas:

```text
where 'T: Show & Clone, 'U: Show
```

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
deferred. Parentheses group compound type arguments, as in `identity (Int -> Int)`.

Type arguments follow first appearance in the signature: traverse parameter types left
to right, including nested type expressions, then the result type. Count each distinct
variable once. Enclosing type variables do not become additional explicit arguments of
an inner function.

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

The execution strategy is monomorphization: instantiate checked generic bodies for the
concrete type arguments used, and reuse the implementation for the same function and
resolved type arguments. Each concrete instance determines layout, selected protocol
implementations, and required cleanup; it does not add undeclared requirements to the
source signature.

## Ownership and closures

Generic functions use the existing parameter, return, capture, and invocation rules.
Parameters borrow by default; consuming parameters use `~`. Concrete instances pass
through the existing ownership analysis after monomorphization. Generic functions do not
introduce a separate abstract ownership checker or a new callable-ownership model.

Closures may borrow or own captures under the ordinary rules. Moving a capture out makes
the closure call-once; owning captures alone does not. Generic typing introduces no new
capture mode. See the [memory model](../../memory-model.md#move-closures-).

The existing representation separates callable types from parameter ownership:

- `TypeFn` contains parameter types and a result type.
- `FnParam.consuming` records the declaration's `~` marker.
- `CallableValues` follows callable origins through bindings, arguments, returns, and
  partial applications to recover remaining parameter contracts. Conflicting consuming
  and borrowing alternatives are rejected by ownership analysis.

A parameter written `f: String -> Int` can therefore receive a function declared with
`~text: String`; callable-flow analysis recovers that consuming argument contract.
Writing `~f: String -> Int` instead transfers ownership of the function value itself.
Monomorphization must preserve parameter markers, callable origins, captures, and result
metadata so the existing analyses retain their evidence. Abstract type and constraint
checking does not require caller-independent inference of callback ownership contracts.

See [CallableValues](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CallableValues.scala)
and the [recursive callable ownership tests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/RecursiveCallableOwnershipTests.scala).

## Function values, partial application, and recursion

An explicit type application such as `identity Int` selects a concrete function value
without invoking its body. Expected function types may also determine the substitution,
as in the `render_int` example. A value with unresolved type variables is rejected in
the initial scope rather than stored as a polymorphic callable.

Value partial application uses the existing application and ownership rules after type
arguments have been determined. Arguments and the expected remaining function type may
jointly provide that type information. Capturing an argument in a partial application
preserves its ordinary ownership and call-once behavior.

The first implementation supports recursion at the same type substitution, including
mutual recursion. When a dependency chain returns to the same generic function, its
resolved type arguments must match. Changing-type recursion is rejected, even when it
would produce only a finite set of specializations.

For example, `f Int -> g String -> f Int` is allowed; `f Int -> g Int -> f String`
is rejected. Separate nonrecursive uses of `f Int` and `f String` remain valid.

During monomorphization, track function identities and resolved type arguments along
the active expansion chain. Reuse a matching specialization when recursion returns to
it; report a changed substitution with the dependency chain. Check all specialization
dependencies, including function-value creation, not only direct calls.

Generic local functions preserve enclosing type-variable identity and ordinary capture
behavior.

## Pipeline placement

`FnMonomorphizer` runs immediately after `TypeChecker` and before
`PartialApplicationElaborator` in the semantic pipeline:

```text
CaptureAnalyzer
TypeChecker
FnMonomorphizer
PartialApplicationElaborator
ClosureMemoryFnGenerator
StructDestructorBodyGenerator
OwnershipAnalyzer
```

Type checking establishes generic type relationships, declared constraints, and use-site
substitutions. `FnMonomorphizer` emits concrete typed bodies and resolves their protocol
operations before partial-application lowering makes type-dependent capture decisions
and closure generation builds environment fields. The existing ownership analyzer then
checks concrete bodies and inserts cleanup.

Generated instances need fresh binding identities, remapped references and captures,
substituted type metadata, and refreshed indexes. Generic templates remain available for
instantiation but do not enter downstream executable-body processing.

See [SemanticStage](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/SemanticStage.scala),
[PartialApplicationElaborator](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/PartialApplicationElaborator.scala),
and [ClosureMemoryFnGenerator](../../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ClosureMemoryFnGenerator.scala).

## Implementation decisions

The compiler already represents protocol type variables, substitutes concrete instance
types into member signatures, and selects concrete protocol members. Ordinary generic
functions additionally need abstract type and constraint checking and instantiation at use sites.
See [compiler design](../../design/compiler-design.md).

The constraint model uses an extensible type alias:

```scala
type TypeConstraint = ProtocolRef
```

Reuse `ProtocolRef` directly: it carries the protocol's name, resolved identity, and source
information. Each `where` entry associates a scoped `TypeVariable` with a
`List[TypeConstraint]`; the listed requirements are combined with `&`.

Protocols are the only constraint form in the initial scope. Future structural requirements
could reuse `TypeOpenRecord`, extending the alias to `ProtocolRef | TypeOpenRecord` for forms
such as `'T: Show & { field: Type }`. Structural constraints remain outside this implementation.

Before implementing monomorphization, trace
`identity`, `render`, and a generic higher-order function through type resolution, body
checking, calls, ownership analysis, and code generation. Settle:

- Representation of resolved type-variable identities and the scoped environment in
  `TypeResolver`.
- Parsing explicit type arguments and constraints, including compound types.
- Shared call inference for intrinsics, ordinary functions, and protocol members.
- Preservation of existing callable-flow and ownership metadata when instantiating bodies.
- Representation of monomorphization keys, instance reuse, recursion checks, template storage,
  and remapping of generated identities and references.

## Acceptance examples

The implementation plan should demonstrate:

- Inferred and explicit calls agree, including compound and function type arguments.
- Repeated occurrences of a variable reject inconsistent concrete types.
- Explicit type arguments follow first-appearance order and are supplied all or none.
- Omitted result annotations infer the same types as equivalent annotated functions.
- Comma-separated variable constraints and `&`-separated requirements are checked.
- Expected types resolve function values and partial applications; insufficient evidence
  produces a focused diagnostic.
- Missing constraints fail in the generic body; missing instances fail at concrete calls.
- Constraints propagate through calls between generic functions.
- Consuming identity transfers an owned aggregate without duplication or double cleanup.
- Generic higher-order calls preserve capture ownership and call-once restrictions.
- Same-substitution direct and mutual recursion reuse specializations. Changing-type
  cycles are diagnosed, including finite cycles and dependencies through function values.
- Independent nonrecursive uses may instantiate the same function at different types.
- Names, shadowing, captures, and source diagnostics survive specialization.

These are implementation acceptance requirements, not claims of passing tests.
