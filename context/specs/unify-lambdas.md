# Unify lambdas

## Context

Tracked item: #255 Unify lambdas.

MML already has a lambda-shaped core. Top-level functions, local functions, lambda literals,
local `let`, statement sequencing, and direct lambda application are all represented with
`Lambda` somewhere in the pipeline.

The design goal is to make that regularity real across semantics and codegen:

- A lambda is a scope.
- Function-ness is not a separate semantic category.
- Closure materialization is a representation decision.
- Direct calls, closure values, stack environments, and heap environments are lowering choices
  derived from usage and lifetime.

This is the heart of the CPS-like core: `let`, sequencing, functions, and lambdas are all modeled
as lambda application.

## Non-goals

Do not introduce a semantic split like `CallableLambda` vs `ScopeLambda`.

That split would encode the wrong abstraction. It would keep forcing later phases to ask whether a
lambda is "really" a closure or "just" scope machinery, when the intended model is that lambdas are
the scope machinery.

Also avoid making closure literals the center of the design. A closure is one possible
materialized representation of a lambda value, not the semantic identity of a lambda.

## Core invariant

Every lambda introduces a scope with parameters and a body.

For every lambda, the compiler should be able to derive:

- the bindings introduced by the lambda parameters
- the free variables referenced by the lambda body
- the ownership relationship between the lambda and those free variables
- whether the lambda value must be materialized
- whether an environment object is needed
- whether that environment can stay stack-local or must be heap-backed
- whether a direct entry point can be used

Those facts may be optimized away for immediately applied lambdas, but they should not be modeled
as a different semantic kind of lambda.

## Ownership compatibility constraints

Lambda unification must preserve the ownership rules currently implemented by the compiler. The
change may make those rules apply through a more regular lambda model, but it must not weaken or
skip them.

Current ownership states:

1. `Owned`: the binding owns a heap value and must be freed at scope end unless ownership escapes
   or moves away.
2. `Moved`: ownership has transferred away; later use of the binding reports `UseAfterMove`.
3. `Borrowed`: the binding is only borrowed; the current scope must not free it or move it into a
   consuming use.
4. `Literal`: the binding refers to static/literal data and is never freed as owned local state.
5. `Global`: the binding has module lifetime and is borrow-only in local ownership analysis.

### Lambda values are ordinary unique values

A core idea of this unification: a lambda value participates in the same ownership rules as any
other value of its type class. Reasoning about closures stops being a parallel system.

- A **move-capturing lambda value** behaves as an owned heap value. Standard owned-heap rules
  govern transfer through `~` parameters, return-to-caller, struct-sink construction, rebind
  moves, and use-after-move.
- A **borrow-capturing lambda value** behaves as a borrowed value. Standard borrowed-value rules
  govern what it can satisfy: not a consuming parameter, not an owned return, not a struct-sink.
- A **non-capturing lambda value** has no owned environment. It is borrow-only at the ownership
  layer — there is nothing to free.
- A **captured borrowed value** remains owned by the enclosing scope.
- A **captured moved value** becomes unavailable in the enclosing scope.

How the lambda escapes (return, consuming-param transfer, struct sink, indirect store via HO
argument) is *not* a closure-specific concept. The ownership analyzer already discovers each of
those escape paths at the use site for any owned heap value. Lambda values use the same
machinery.

Struct construction is an ownership sink. Storing a function value into a struct field must apply
the same transfer rules as any other sink: a borrow-capturing closure cannot enter the struct,
a move-capturing closure transfers ownership into the struct, and a non-capturing/null-env function
value carries no owned environment to free.

Open bug found during S9 inspection: a borrowed closure can currently be laundered through a struct
field and later passed to a consuming function parameter. Direct argument and `let` alias paths
reject the borrowed closure, and return escape rejects, but this shape compiles and can detonate at
runtime:

```mml
struct Holder { f: Int -> Int };

fn consume_fn(~f: Int -> Int, x: Int): Int =
  f x;
;

pub fn main(): Int =
  let seed = 10;
  let add_seed = { x: Int -> x + seed; };
  let holder = Holder add_seed;
  consume_fn holder.f 10;
;
```

The rules below remain authoritative. Items 17–25 specialize the generic rules in items 1–16
with closure-specific diagnostic phrasing; the underlying checks become instances of the generic
ownership pipeline, not parallel closure-only logic.

Rules to preserve:

1. Heap-typed owned bindings are freed at the end of the scope that owns them.
2. Free insertion remains CPS-shaped: bind the result, run the required frees, then return the
   preserved result.
3. Values returned from a function are not freed locally; ownership transfers to the caller.
4. Returning a borrowed value as an owned return type is rejected with `BorrowEscapeViaReturn`.
5. Allocating expressions are recognized through native `Alloc` effects, known allocating user
   functions, heap-field struct constructors, and move-capturing lambdas with non-empty captures.
6. Allocating call arguments that are not bound by the user are lifted into synthetic temporaries
   and freed after the call unless consumed by the callee.
7. Conditional expressions with mixed owned/non-owned branches keep a witness boolean and free only
   on the owned branch.
8. Conditional branches that move an outer owned value merge back to a moved outer state.
9. When a function returns a heap type and one conditional return branch is static/non-allocating,
   that branch is cloned so the caller consistently receives an owned value.
10. Consuming parameters move owned arguments into the callee.
11. Passing an already moved value to a consuming parameter remains a use-after-move error.
12. Passing a borrowed local binding to a consuming parameter is rejected with
    `BorrowedValuePassedToConsumingParam`.
13. A move caused by a consuming parameter must be the last use in the enclosing body; otherwise
    `ConsumingParamNotLastUse` is reported.
14. Partial application is rejected when any remaining unapplied parameter is consuming.
15. Constructor calls with consuming parameters auto-clone supported non-owned inputs such as
    string literals and globals; owned values move in without cloning.
16. Rebinding an owned heap value through local `let` moves ownership into the new binding when the
    source is an owned heap value without mixed-ownership witness state.
17. Borrow-capturing lambdas may borrow owned, borrowed, literal, or global bindings.
18. Borrow-capturing lambda values cannot be returned; escaping borrow closures remain rejected
    with `BorrowClosureEscapeViaReturn`.
19. Move-capturing lambdas move owned heap captures into their environment.
20. Capturing an already moved heap binding remains an error.
21. Capturing a borrowed heap binding into a move lambda remains an error.
22. Capturing a literal/static heap value into a move lambda uses the appropriate clone function and
    records a literal capture.
23. Heap captures inside the lambda body are treated as borrowed from the environment.
24. Capturing function values must continue to honor the same ownership and escape restrictions as
    other owned values when a real environment is owned.
25. Non-capturing/null-environment function values must be handled explicitly so unification does
    not invent a heap ownership obligation where no environment exists.

Concrete ownership references:

- `tests/mem/borrow-capture.mml`: sibling local functions borrow the same heap binding and the
  outer owner remains responsible for cleanup.
- `tests/mem/closure-capture.mml`: repeated move-closure creation returns owned closure values
  whose environments must be freed.
- `tests/mem/closure-heap-capture.mml`: a move closure owns a captured `String` passed through a
  consuming parameter.
- `tests/mem/test_temporaries.mml`: anonymous allocating arguments and nested concat temporaries
  are lifted and freed after their consuming use.
- `tests/mem/mixed_ownership_test.mml`: conditional string results mix static and heap branches
  and require witness-based conditional cleanup.
- `tests/mem/cond-consume.mml`: one conditional branch consumes an owned value while the other
  borrows it; the outer ownership state and frees must remain correct.
- `mml/samples/mem/use-after-move.mml`: using a binding after it moves into a consuming parameter
  is rejected.
- `mml/samples/mem/consume-not-last.mml`: moving a binding into a consuming parameter before its
  last use is rejected.
- `mml/samples/mem/move-borrow-fails.mml`: borrowed local data cannot satisfy a consuming
  parameter.
- `mml/samples/mem/borrow-escape.mml`: returning borrowed heap data as an owned result is
  rejected.
- `mml/samples/mem/partial-consume.mml`: partially applying a function while leaving a consuming
  parameter unapplied is rejected.

## Current friction

The current implementation still has several places where the uniform lambda model leaks:

- Capture analysis is described as filling captures for "real closure literals".
- Parser-lowered scoped bindings are treated as exceptions rather than ordinary lambda scopes.
- Top-level functions are represented as `Bnd(Lambda)` with `BindingMeta`, while local lambdas and
  lambda literals take different paths in later phases.
- Codegen has separate reasoning for direct callable entries, closure entries, non-capturing
  closure values, and capturing closure values.
- Borrow vs move capture handling is tied too closely to closure environment allocation.
- `alloca` vs `malloc` is still partly treated as a closure-model distinction instead of a
  lifetime and materialization consequence.

The desired endpoint is not fewer representations in LLVM. The desired endpoint is fewer semantic
special cases before lowering.

## Semantic model

### Lambda as scope

All of these are lambda scopes:

```mml
fn add(a: Int, b: Int): Int = a + b;
```

```mml
fn outer(a: Int): Int =
  fn inner(b: Int): Int = a + b;
  inner 1
;
```

```mml
let x = value;
rest
```

```mml
{ x -> x + 1 }
```

```mml
{ x -> x + a } 1
```

The parser and rewriters may keep using immediate lambda application to represent `let` and
sequencing. Later phases should preserve the conceptual model: a lambda scope applied to an
argument extends the current computation with a new binding.

### Free variables and captures

Free-variable analysis should be phrased for lambda scopes, not closure literals.

For each lambda:

- parameters are local to the lambda
- references to bindings from an enclosing lambda scope are free variables
- references to module-level bindings are global references
- nested lambdas may require free variables to be propagated through enclosing lambdas

The output of this analysis should be usable by both ownership analysis and codegen. A lambda may
have free variables even when no runtime environment object is ultimately allocated.

### Borrow and move

Borrow vs move is a relationship between a lambda scope and its free variables.

- Borrow capture means the lambda observes a binding owned by an enclosing scope.
- Move capture means the lambda receives ownership of the captured value.
- Non-heap values can still be copied or passed by value according to their normal type rules.

The move marker should affect capture ownership, escape legality, and environment construction. It
should not create a separate semantic class of lambda.

Top-level functions normally have no local free variables. That should fall out of resolution and
free-variable analysis, not from a special top-level function model.

## Materialization model

A lambda does not require a closure object merely because it exists.

Closure materialization is required when a lambda value must be represented as first-class data,
for example when it is:

- bound as a value and used indirectly
- passed to a higher-order function
- returned
- stored in aggregate data
- moved into another ownership domain

Immediate application does not require materialization by default:

```mml
{ x -> x + a } 1
```

can lower like direct scope application. It still has the same semantic free-variable facts as any
other lambda.

Non-capturing lambdas can materialize as `{ fn, null }` when a function value is required.
Capturing lambdas materialize as `{ fn, env }` when a function value is required.

## Environment model

An environment is a lowering artifact for free variables that must survive across a boundary where
direct lexical access is no longer enough.

Environment allocation should be derived from:

- whether the lambda has free variables
- whether the lambda is materialized
- whether the materialized value escapes the current scope
- whether the captures are borrowed or moved
- whether the backend can prove a stack allocation is sufficient

Useful defaults:

- no free variables: no env, materialized value uses `null` env
- non-escaping borrow environment: stack allocation is allowed
- escaping borrow environment: reject unless converted to an owning move form
- move environment: heap allocation is valid by default
- proven non-escaping move environment: stack allocation may be a later optimization

This keeps `alloca` vs `malloc` out of the semantic model. The semantic model should describe
ownership and lifetime constraints; codegen chooses the representation.

## Direct and closure entries

The backend may still emit multiple entry points:

- a direct entry for calls where arguments and environment data are statically available
- a closure entry with an explicit environment pointer for first-class function values
- wrappers when needed to adapt one ABI to another

These are ABI choices, not separate kinds of source or semantic lambda.

The direct-call path should not be limited to top-level functions. A local lambda with known
captures can be directly called if the compiler has the needed values at the call site.

The closure-call path should not be limited to lambda literals. A top-level function can be passed
as a first-class value and represented as `{ ptr @entry, ptr null }`.

## Pipeline direction

### 1. Normalize terminology

Update docs and comments away from "real closure literal" language.

Preferred terms:

- lambda scope
- free variables
- materialized closure value
- direct entry
- closure entry
- environment

### 2. Make free-variable analysis lambda-wide

Capture analysis should analyze every lambda scope uniformly.

It can still record lowering hints that say a lambda is immediately applied or never materialized,
but those should be derived facts, not semantic categories.

### 3. Separate free variables from environment materialization

Represent "this lambda has free variables" separately from "this lambda needs an env object".

This may require metadata beyond the current `captures` field, or a clearer interpretation of
`captures` as semantic free-variable facts rather than "fields that must be stored in a closure
env".

### 4. Unify ownership handling

Treat lambda values as ordinary unique values. The ownership analyzer already encodes the rules;
lambda values slot into them without a parallel closure-specific system.

- A move-capturing lambda value is an owned heap value. Generic owned-heap rules govern transfer,
  return, struct-sink, and consuming-parameter behavior.
- A borrow-capturing lambda value is a borrowed value. Generic borrow rules govern what it can
  and cannot satisfy.
- A non-capturing lambda value has no owned environment; there is nothing to free.
- Captured borrowed values remain owned by the enclosing scope; captured moved values become
  unavailable there.

Closure-specific diagnostic phrasing (`BorrowClosureEscapeViaReturn`,
`CapturedBorrowedHeapBinding`) is kept for user experience, but the underlying checks become
instances of the generic ownership rules at return-position, consuming-parameter, and
struct-sink sites — not parallel closure-only entry points.

### 5. Derive codegen representation

Codegen should decide:

- direct call vs indirect closure call
- direct entry vs closure entry
- null env vs concrete env
- stack env vs heap env
- wrapper emission

from semantic facts and usage, not from whether the lambda originated as a top-level `fn`, local
`fn`, or lambda literal.

## Implementation notes

Likely pressure points:

- `CaptureAnalyzer`
- `ClosureMemoryFnGenerator`
- `TypeChecker` lambda inference and `TypeFn` propagation
- `OwnershipAnalyzer` capture and function-value ownership
- `TailRecursionDetector`
- `ExpressionCompiler`
- `Applications`
- `FunctionEmitter`

The work should probably land in small slices:

1. Documentation and terminology cleanup.
2. Free-variable analysis cleanup without changing codegen behavior.
3. Ownership cleanup for non-capturing and materialized function values.
4. Codegen unification for direct vs closure entries.
5. Environment allocation phase 1: `malloc` for move closures, `alloca` for borrow.
6. Tail-recursive local lambda follow-up after the unified model is stable.
7. Stack-promotion for non-escaping move-capturing lambdas — env on the stack when the
   closure's owner does not outlive the current frame; derived from the unified
   ownership analysis introduced in (3).

## Open questions

- Should `Lambda.captures` continue to mean semantic free variables, or should it be split into
  free-variable facts and materialized-environment fields?
- Where should materialization requirements be recorded: semantic metadata, ownership analysis,
  or codegen-local demand analysis?
- Can immediate lambda application always avoid materialization, or are there cases where debug
  info, ownership wrappers, or higher-order lowering force a value representation?
- Should top-level functions and local functions share exactly the same `BindingMeta`, or should
  binding metadata be reduced to source-origin and export/name information?
- How much of direct-entry eligibility should be computed before codegen?
- Should move-capturing non-escaping lambdas initially stay heap-backed for simplicity, with stack
  promotion as a later optimization?

## Decisions

These resolve the open questions above. Detailed rationale, the multi-use join rule, and
per-slice acceptance criteria live in `context/specs/unify-lambdas-plan.md`. The slice
reference (S2, S3, …) points at that plan file.

### Q1 — `Lambda.captures` stays as a single field (S2)

`Lambda.captures: List[Capture]` is the source of truth for the bindings a lambda
references from enclosing scopes. It is populated by `CaptureAnalyzer` for every lambda
scope, regardless of whether an env is ultimately built. Whether to build an env is a
separate decision recorded on `LambdaMeta` (see Q2). When an env *is* built, codegen
uses the same `captures` list as the layout source — `CapturedRef` / `CapturedLiteral`
continue to live inside `Capture`, and literal-clone upgrades happen on this list as
they do today.

No `freeVars` / `envFields` split. The 3-way materialization state (no value / value
with empty env / value with real env) is fully derivable from one annotation
(`isDirect`) plus `captures.isEmpty`.

### Q2 — Materialization metadata is one field on `LambdaMeta` (S3)

A new pass `MaterializationAnalyzer` runs between `CaptureAnalyzer` and
`ClosureMemoryFnGenerator`. It populates:

```
isDirect: Boolean   // never materialized as a value — scope-only / immediate-application
```

The 3-way materialization state is derived, not stored:

- `meta.isDirect`                       → **Direct**: no env, no fat pointer.
- `!isDirect && captures.isEmpty`       → **NullEnv**: first-class value is `{ ptr @entry, ptr null }`.
- `!isDirect && captures.nonEmpty`      → **Materialized**: real env, fat pointer `{ ptr @closure_entry, ptr env }`.

`Lambda.isMove: Boolean` stays as the borrow-vs-move marker (no `CaptureMode` enum;
that would be a paint job over the existing field).

**No `escape` field.** Earlier drafts proposed an `Escape` enum recording how a closure
value leaves its definition site. Reframing lambda values as ordinary unique values
made it unnecessary: the ownership analyzer already discovers return-position,
consuming-parameter, and struct-sink behavior at the use site for every value, and
applies the same rules to lambda values. Re-encoding those distinctions on
`LambdaMeta` would duplicate analysis the ownership pass already runs and would invite
the kind of closure-specific reasoning this workstream is removing.

**Multi-use join rule.** `isDirect` joins by AND across use sites: it starts `true` and
downgrades to `false` on the first value-position use (HO argument, return, store,
alias). Once `isDirect = false`, the ownership rules for the resulting value-type
classification (owned heap for move closures, borrowed for borrow closures) take over;
no further metadata is needed to gate ownership decisions.

### Q3 — Immediate application avoids materialization (S3)

Yes, with one principled exception: a lambda is `isDirect = true` iff every reference
to it (or to its binding) is in App.fn position with full arity. Pure
`App(Lambda(...), arg)` shape — and ownership wrappers around it — leave `isDirect`
true. A binding occurrence whose binder is a lambda flips `isDirect` to false at the
*binding occurrence* if that occurrence is used as a value.

### Q4 — `BindingMeta` keeps shape; semantic phases never branch on `origin` (S10)

`origin: BindingOrigin` (TopLevel | Local | Inner) stays on `BindingMeta` for
diagnostics, source positions, and codegen entry-point naming. Semantic phases
(capture, materialization, ownership, type checker) must not branch on `origin` —
behavior is derived from `LambdaMeta.isDirect` and `Lambda.isMove`, plus the generic
ownership classification of the lambda value. `destructorKind` is removed from
`BindingMeta` and lives only on env-struct metadata, where an env actually exists.

### Q5 — Direct-entry eligibility is computed before codegen (S6)

Recorded as `LambdaMeta.isDirect` and consumed alongside `captures.isEmpty`. Codegen
consults these and never re-derives "is this called directly?". Lowering rules:

- `isDirect`                              → direct entry only; no wrapper.
- `!isDirect && captures.isEmpty`         → direct entry + closure-entry wrapper; first-class
  value is `{ ptr @entry, ptr null }`.
- `!isDirect && captures.nonEmpty`        → closure entry with env param + optional direct
  entry when at least one statically-known direct call site exists.

### Q6 — Non-escaping move-capturing lambdas stay heap-backed in phase 1 (S7; S11 deferred)

Phase-1 allocation rule reads `Lambda.isMove` only:

- `isMove == false` → env on stack (`alloca`).
- `isMove == true`  → env on heap  (`malloc`).

Stack-promotion for non-escaping move closures is the deferred slice S11 in the plan
— tracked but not scheduled as part of #255. When S11 lands, it needs to know whether
the binding that owns the closure value outlives the current stack frame; that is an
ownership-lifetime property of the binding, not a precomputed escape state on the
lambda. The ownership analyzer's lifetime conclusions are the input, not a separate
metadata field.

## Success criteria

#255 is done when:

- top-level functions, local functions, lambda literals, and let-bound lambdas follow the same
  semantic rules
- free-variable/capture analysis applies to lambda scopes uniformly
- borrow and move capture behavior is not duplicated across separate closure models
- direct vs closure calls are ABI/lowering decisions
- stack vs heap env allocation is derived from materialization and lifetime
- non-capturing function values do not get treated as owning heap closure environments
- tests cover equivalent behavior across top-level, local, let-bound, and literal lambda forms
