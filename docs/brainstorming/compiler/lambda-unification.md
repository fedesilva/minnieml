# Lambda / Function Unification

## Status

Draft design / handoff spec.

This document captures the gap between:

- top-level `fn` declarations
- local let-bound lambdas
- inner `fn` syntax as sugar over local function values

The current compiler already uses `Lambda` as the body representation for both
top-level functions and lambda literals, but the surrounding binding,
resolution, type-checking, and codegen paths still diverge in important ways.

## Goal

Close the semantic and implementation gap so that named functions and
let-bound lambdas are the same kind of callable in the compiler, with surface
syntax differences only.

In particular, inner `fn` should not need special-case semantics beyond parser
lowering.

## User-facing invariant

These forms should be equivalent modulo scope and visibility:

```mml
fn inc(x: Int): Int = x + 1;;
```

```mml
let inc: Int -> Int = { x: Int -> x + 1; };
```

Inside an expression, this:

```mml
fn loop(i: Int): Unit =
  if i < 3 then
    loop (i + 1);
  ;
;
```

should be surface syntax for the same callable representation as:

```mml
let loop: Int -> Unit = { i: Int ->
  if i < 3 then
    loop (i + 1);
  ;
};
```

This spec intentionally uses the outer binding ascription in the desugared
form because that is the shape the current type-checker accepts for typed local
recursion.

## Current state

### Shared already

- Both top-level functions and lambda literals use the `Lambda` AST node for
  the callable body.
- Both eventually compile to LLVM functions.
- Both support recursion in at least some cases.

### Still different

#### Parser shape

Top-level `fn` parses as:

- `Bnd(value = Expr(List(lambda)), meta = Some(BindingMeta(...)))`

Local let-bound recursion parses as:

- `App(fn = Lambda([binding], restExpr), arg = Expr(List(lambdaValue)))`

This means the top-level path is declaration-shaped, while the local path is
value-shaped.

#### Name resolution

Top-level recursion resolves `loop` as a module/member binding.

Let-bound recursion resolves `loop` through the synthetic binding parameter of
the scoped-binding lambda introduced by expression-level `let`.

That is a real semantic split today:

- top-level self-reference is declaration lookup
- local self-reference is parameter lookup

#### Type checking

Top-level functions go through member-oriented logic:

- mandatory ascription lowering for `Bnd` with `BindingMeta`
- dedicated recursive-function error path
- direct function type construction from declaration metadata

Local recursive lambdas go through the special-case
`checkImmediatelyAppliedLambda` path used for expression-level let desugaring.

This means local recursion currently depends on a special let/lambda typing
bridge rather than sharing the same callable checker as top-level `fn`.

#### Code generation

Top-level functions are emitted as direct named function definitions.

Let-bound lambdas are compiled as function values via expression codegen,
including deferred definitions and closure construction where needed.

This is the biggest runtime/codegen split:

- top-level `fn` is declaration-first
- let-bound lambda is value-first

## Findings from the #245 workstream

### 1. Inner `fn` is not semantically different

Working nullary recursive let-bound lambdas prove the existing pipeline already
supports the intended meaning:

```mml
let loop = {
  println "tick";
  loop();
}: Unit;
```

So inner `fn` should not invent new semantics.

### 2. Typed local recursion exposes an existing gap

This form does **not** currently type-check:

```mml
let loop = { i: Int ->
  if i < 3 then
    loop (i + 1);
  ;
}: Unit;
```

The current typer reports `Unable to infer type for 'loop'`.

This form **does** type-check:

```mml
let loop: Int -> Unit = { i: Int ->
  if i < 3 then
    loop (i + 1);
  ;
};
```

So the current type-checker treats:

- outer binding function ascription as sufficient
- lambda return ascription alone as insufficient when the lambda has typed params

This matters for inner `fn`, because surface syntax naturally contains:

- param types
- return type

but not an explicit outer `let` ascription.

### 3. The parser-only lowering that currently fits the existing typer

Given current behavior, the least invasive lowering for:

```mml
fn loop(i: Int): Unit = ... ;
```

is:

```mml
let loop: Int -> Unit = { i: Int -> ... };
```

not:

```mml
let loop = { i: Int -> ... }: Unit;
```

This is still parser-only sugar, but it exposes that local recursion and
top-level recursion are not yet unified semantically.

### 4. `raytrace2` found a separate closure/codegen gap

Attempting to refactor `raytracer` into inner functions uncovered an additional
limitation:

- a recursive local helper that captures a sibling local function value can
  produce a closure environment field of `TypeFn`
- current codegen then fails when trying to lower that env field type

Observed failure shape:

- closure env contains a field like `compute_row: TypeFn`
- codegen error: cannot extract LLVM type name from that env field

This is not the same issue as parser lowering. It is a callable/closure
representation gap that should be considered part of unification work.

## Desired architecture

### High-level direction

Move toward one callable model with:

- one canonical binding representation for named callables
- one callable checker for recursion / param typing / return typing
- one codegen abstraction for named callables vs callable values

The surface syntax (`fn`, `let name = { ... }`, inner `fn`) should be a parse
front-end choice, not a semantic fork.

### Canonical properties

Any named callable should have:

- a name
- parameters
- a body `Lambda`
- an optional callable metadata record
- a full function type, or a principled way to derive it

Any callable value should have:

- the same body/param semantics
- a representation that can be named, captured, passed, and recursively called

The compiler should not need one path for “real functions” and another path for
“lambda values with a synthetic binder”.

## Proposed closure plan

### Phase 1. Make the semantic model explicit

Introduce a clear internal concept of a callable binding that covers both:

- top-level named functions/operators
- local named callables introduced by `let` / inner `fn`

This may still be represented with existing nodes at first, but the semantic
phase should normalize them into one conceptual form.

### Phase 2. Unify recursion typing

Remove dependence on the special immediate-applied-lambda recursion bridge as
the only way local recursion works.

Local recursion and top-level recursion should both use the same rule for
pre-seeding self type.

### Phase 3. Unify reference resolution model

A self-reference should not fundamentally differ because it is:

- a top-level declaration
- a local named callable

Resolver output may still differ by lexical scope, but the callable identity
model should be shared.

### Phase 4. Unify codegen abstraction

Make codegen compile named callables and local callable values from a shared
abstraction:

- declaration emission
- value emission
- closure env typing
- recursive self-reference

This phase should also close the `TypeFn`-in-closure-env gap found by
`raytrace2`.

## Non-goals

- No immediate redesign of capture ownership rules in this spec.
- No immediate change to user-facing syntax beyond inner `fn`.
- No commitment yet to removing `BindingMeta` or `Bnd`.

## Open questions

### Q1. What is the canonical IR boundary?

Should unification happen by:

- lowering top-level `fn` into the same value/binding model as local lambdas, or
- lifting local callables into the same declaration model as top-level `fn`, or
- introducing a new normalized callable form used only after parsing?

### Q2. Where should the full function type live for local recursion?

Should local recursive callables derive their self type from:

- outer binding ascription only
- lambda param types + lambda return ascription
- both, with one canonical lowering rule

Current behavior strongly suggests the compiler wants an outer binding function
type.

### Q3. Should `BindingMeta` apply to local named callables?

If inner `fn` is truly the same as named functions, should local callables also
carry:

- origin/function metadata
- inline hints
- operator-like metadata in future

### Q4. What is the runtime representation of captured function values?

If closures can capture sibling local functions or recursive helper functions,
what is the canonical env-field representation?

Current codegen is not ready for arbitrary `TypeFn` fields in closure envs.

### Q5. How far should the unification go for operators and constructors?

The compiler already models constructors/operators with `Bnd + Lambda + meta`.
Should the unification target include all of them, or focus only on:

- top-level `fn`
- let-bound lambdas
- inner `fn`

## Acceptance criteria

This workstream should not be considered done until all of the following hold:

- Inner `fn` is parser sugar only.
- Typed recursive inner `fn` works without bespoke parser-seeded `typeSpec`
  hacks.
- Top-level recursive functions and local recursive named callables use the same
  semantic recursion rule.
- Codegen can represent captured callable values consistently, including the
  `raytrace2`-style local-helper case.
- The difference between “function declaration” and “lambda value” is reduced to
  scope / visibility / linkage, not distinct semantic machinery.

## Suggested next work session

1. Read this spec and the current `#245` tracking notes.
2. Decide the target canonical callable representation.
3. Trace one nullary recursive pair and one typed recursive pair end-to-end:
   - top-level `fn loop(): Unit = ...`
   - local `let loop = { ... }: Unit`
   - local `let loop: Int -> Unit = { i: Int -> ... }`
4. Decide whether to unify in parser lowering, semantic normalization, or both.
5. Revisit `raytrace2` only after the callable model is settled.
