# Lambdas

## Scope

This document covers the full design space for lambda literals and closures in MinnieML.

It covers:

- surface syntax
- name binding and lexical scope
- typing
- captures
- runtime representation
- ownership and escape rules
- interaction with placeholder partial application


## Goals

The feature should support:

```mml
let add = { a, b -> a + b };

let inc_all =
  map _ { x -> x + 1 }
;

fn make_adder(n) =
  { x -> x + n }
;

let add5 = make_adder 5;
let y = add5 10;
```

The intended capability is not only anonymous syntax. The feature includes:

- anonymous function values
- lexical capture of outer locals
- nested lambdas
- use of lambdas in higher-order calls
- returning lambdas

## Core model

### Lambdas are function values

A lambda expression evaluates to a callable value.

Its type is a function type:

```mml
{ x -> x + 1 } :: Int -> Int
```

Lambdas and named functions should use the same call semantics and the same `TypeFn` shape.

### Lambdas are lexically scoped

Names inside a lambda are resolved in lexical scope order:

1. lambda parameters
2. local bindings inside the lambda body
3. outer lambda parameters and locals
4. enclosing function parameters and locals
5. module-level bindings

Module-level references are ordinary global references, not captures.

References to outer local values are captures.

### Lambdas are not tupled by default

`{ a, b -> body }` denotes a two-parameter function, not a unary function taking a tuple.

Application follows the existing callable model:

```mml
let add = { a, b -> a + b };
let z = add 1 2;
```

## Surface syntax

### Literal form

Proposed core syntax:

```mml
{ x -> x + 1 }
{ a, b -> a + b }
{ x ->
  let y = x + 1;
  y * 2
}
```

Structure:

- `{`
- zero or more parameters, comma-separated
- `->`
- body expression
- `}`

### Body form

The body is a normal expression.

This includes:

- a single expression
- a sequence with local `let` bindings
- conditionals
- nested lambdas

Examples:

```mml
{ x -> x + 1 }
{ x ->
  let y = f x;
  g y
}
{ x ->
  if p x then a else b end
}
```

### Trailing lambdas

No separate trailing-lambda syntax is required.

This is ordinary application:

```mml
map list { x -> x + 1 }
fold 0 list { acc, x -> acc + x }
```

The parser only needs lambdas to be valid terms in expression position.

### Nested lambdas

Nested lambdas are ordinary terms:

```mml
let add = { a -> { b -> a + b } };
```

### Zero-parameter lambdas

Resolved: 

`{ a * b}`
`{ 42 }`

Candidate forms:

```mml
{ -> 42 }
{ () -> 42 }
{ 42 }   // preferred
```

This should be decided explicitly. It affects grammar shape and interaction with `()`.

### Parameter syntax

Lambda parameters should use the same parameter model as `fn` declarations where possible.

That includes:

- names
- type ascriptions
- consuming markers such as `~x`, if supported
- doc-comment behavior, if any

Examples:

```mml
{ x: Int -> x + 1 }
{ ~buf: Buffer -> consume buf }
```

## Placeholder interaction

`_` already has a separate proposed role in placeholder partial application.

That work is a separate project.
This document records the interaction point, but does not resolve placeholder design.
Placeholder syntax should be specified separately and aligned with lambdas later.

That proposal is expression-level:

```mml
_ + 1
map _ f
concat "x" _
```

Lambda parameters introduce a second possible meaning for `_`:

```mml
{ _, x -> x }
```

This is a language design conflict. The language should not rely on context-sensitive 
guesswork without deciding it explicitly.

### Decision: `_` is allowed as both binder and placeholder

`_` in parameter position (before `->`) is a discard binder. `_` in expression position 
(after `->`) is a placeholder. The parser distinguishes by position — no ambiguity.

```mml
{ _, x -> x }       -- _ is a discard binder
{ x -> _ + x }      -- _ is a placeholder (separate feature)
```

This is the standard approach in ML-family and Scala-family languages.

## Typing

### Function type

The type of a lambda is:

```mml
TypeFn(paramTypes, returnType)
```

Examples:

```mml
{ x: Int -> x + 1 }        :: Int -> Int
{ a: Int, b: Int -> a + b } :: Int -> Int -> Int
```

The exact printed surface form for multi-parameter functions should match the existing function
type notation.

### Decision: parameter type ascriptions are optional

Parameter types may be provided but are not required:

```mml
{ x: Int -> x + 1 }
{ x -> x + 1 }
map list { x -> x + 1 }
```

When omitted, types are inferred from context (expected-type propagation, body constraints, or
both).

### Decision: return type ascription is optional

Syntax uses `: Type` after the closing brace:

```mml
{ x -> x + 1 }: Int
{ 42 }: Int
```

Optional — useful for recursive lambdas, diagnostics, and parity with named functions, but not
required when body inference is sufficient.

### Generalization

Not applicable until polymorphism is added. When it is, lambdas will generalize the same as
everything else — `fn` is just syntax for lambdas, `op` is just syntax for lambdas with surface
metadata for the rewriter to unflatten the term soup. There is no separate generalization story.

## Name binding and capture

### Capture definition

A lambda captures every free reference in its body that resolves to a non-module local defined in
an outer scope.

Examples:

```mml
fn make_adder(n) =
  { x -> x + n }
;
```

Captures:

- `n`

```mml
fn f(a) =
  let b = a + 1;
  { x -> x + b }
;
```

Captures:

- `b`

Not captures:

- module-level functions
- module-level values
- types

### Shadowing

Inner binders shadow outer names.

Example:

```mml
fn f(x) =
  { x -> x + 1 }
;
```

The lambda parameter `x` shadows the outer `x`.

### Capture representation in the AST

The existing `captures: List[Ref]` field is a reasonable target shape.

Open question:

- should captures be explicit in the parsed AST
- or discovered after name resolution as a semantic rewrite

The second option is cleaner. Capture is semantic, not syntactic.

## Runtime model

Non-capturing lambdas and capturing lambdas do not have the same runtime requirements.

### Non-capturing lambdas

A non-capturing lambda can be represented as a plain function.

This can be implemented either as:

- an emitted top-level synthetic function
- a lifted function with no environment

### Capturing lambdas

A capturing lambda needs both:

- code
- environment

At runtime, a closure value therefore needs at least:

- a code pointer
- an environment pointer or inlined environment payload

### Representation options

#### Option A: closure objects

Represent lambdas uniformly as runtime closure values.

Non-capturing lambdas use an empty environment.

Pros:

- one model
- straightforward higher-order semantics

Cons:

- every function value pays closure machinery costs

#### Option B: split representation

Represent:

- named functions and non-capturing lambdas as plain functions
- capturing lambdas as closure objects

Pros:

- cheaper non-capturing case

Cons:

- more complex call lowering
- more type/runtime distinction

#### Option C: lambda lifting plus explicit environment parameters

Lift capturing lambdas to synthetic functions and thread environments explicitly.

Pros:

- keeps generated code explicit

Cons:

- still needs a runtime closure value for first-class use
- more transformation machinery

The language design does not require choosing the exact lowering strategy immediately, but it does
require a consistent semantic model: lambdas are first-class callable values, and capturing lambdas
preserve lexical scope.

## Ownership and escape rules

Captures are an escape point.

This is the hardest part of the design in MinnieML because ownership is already part of the
language model.

### Borrow capture

Borrow capture is the most natural default if outer values are not explicitly moved.

Example:

```mml
fn f(s: String) =
  { -> len s }
;
```

If `s` is captured by borrow, the resulting closure cannot outlive `s`.

That immediately raises the question:

- can the closure be returned
- can it be stored
- can it be inserted into containers

### Move capture

If lambdas can move captured values into their environment, the language needs a way to say so.

Candidate directions:

- `move { x -> ... }`
- per-capture modifiers
- explicit capture lists

### Capture policy options

#### Option A: implicit borrow capture, explicit move capture

Pros:

- conservative default
- matches the intuition that capture does not consume unless stated

Cons:

- returning or storing closures requires escape checks
- borrow lifetimes become part of closure typing

#### Option B: implicit move capture

Pros:

- simpler escape story

Cons:

- surprising ownership transfer
- likely too aggressive for ordinary higher-order code

#### Option C: explicit capture lists

Examples:

```mml
{ [n] x -> x + n }
move { [n] x -> x + n }
```

Pros:

- precise
- makes ownership visible

Cons:

- heavier syntax
- more to learn

The existing memory notes point toward:

- borrow by default
- explicit move when ownership transfer is intended
- borrowed captures may not escape their lifetime

That is a coherent direction, but it needs a concrete user-facing syntax.

### Escape rules

At minimum, the language needs rules for:

- returning a closure that borrow-captures a local
- storing such a closure in a longer-lived binding
- passing such a closure to another function
- capturing a consuming parameter
- capturing a moved value

Possible rule:

- borrow-captured values may not escape the scope that owns them

If that rule is adopted, diagnostics should point at both:

- the captured value
- the escaping closure use

## Higher-order usage

The intended design includes ordinary higher-order use:

```mml
map list { x -> x + 1 }
filter list { x -> p x }
fold 0 list { acc, x -> acc + x }
```

This implies:

- lambdas are valid argument values
- lambdas are valid return values
- lambdas can be stored in bindings
- lambdas can flow through generic code

This is part of the design, not an optional extension.

## Errors and diagnostics

The language should report at least:

- duplicate lambda parameter names
- undefined references in lambda bodies
- invalid placeholder/binder usage involving `_`
- escaping borrow captures
- invalid move captures
- missing or incompatible parameter types, depending on the typing design adopted

## Compiler consequences

The design implies work in:

- parser
- AST shaping for user-written lambdas
- name resolution
- capture discovery
- type checking and inference
- ownership analysis
- code generation for closure values
- LSP features over nested scopes and synthetic bindings

These are consequences of the design. They are not a phased implementation decision.

## Decisions still open

- whether capture syntax is implicit, explicit, or mixed
- whether borrow capture is the default
- how escaping borrow-capturing closures are diagnosed
- exact runtime representation of closure values
