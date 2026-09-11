# By-name arguments

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** unassigned
- **External Reference (optional):** none

## Problem

Boolean `and` and `or` currently evaluate both operands eagerly. Their injected LLVM
native templates demonstrate native integration but cannot provide short-circuit behavior.
By-name parameters would let ordinary operator definitions control whether their right
operand is evaluated.

## Outcome

Support by-name parameters, with short-circuiting boolean operators as the motivating use:

```mml
op and(a: Bool, b: => Bool): Bool 40 left =
  if a then
    b;
  else
    false;
  ;
;
```

### Settled semantics

- The caller defers a by-name argument expression.
- Each use of the parameter evaluates that expression again.
- An unused parameter does not evaluate its argument.
- No implicit memoization: repeated uses must not silently reuse a cached result.

The Author considers implicit memoization contrary to the language's ethos. It would
also introduce unwanted interactions with future effects. Mutation is intended to become
a tracked effect eventually; designing that effect system is not part of this task.

### Decision: sugar over ordinary lambdas

The Author selected lambda/thunk desugaring as the implementation direction on 2026-09-10.
A by-name parameter `y: => T` lowers to an ordinary parameter `y: Unit -> T`.
The transformation has three parts:

1. Lower the parameter type to `Unit -> T`.
2. Wrap the supplied argument expression in a nullary lambda at the call site.
3. Rewrite each use of the by-name parameter into an invocation with `()`.

Conceptual desugaring:

```mml
// Source
fn twice(y: => Int): Int = y + y;;
twice (compute ());

// Lowered
fn twice(y: Unit -> Int): Int = (y ()) + (y ());;
twice { compute (); };
```

The lambda body runs only when invoked. Zero invocations skip the argument; multiple
invocations reevaluate it without memoization. For `and`, the right-hand thunk invocation
lives inside the true branch; for `or`, it lives inside the false branch.

Generated thunks borrow captures by default and use the ordinary lambda rules for captures,
ownership, lifetime, escape, consuming captures, and destruction. By-name arguments should
not introduce a separate ownership system. Existing nullary lambda syntax and ownership
rules are described in the [language reference](../../docs/language-reference.md#lambda-expressions)
and [memory model](../../docs/memory-model.md#closures).

The frontend must retain the by-name distinction until the affected calls and parameter uses
have been rewritten. An explicit `Unit -> T` argument and a `=> T` argument require different
source-level treatment: only the latter requests implicit wrapping and invocation. After
elaboration, ordinary lambdas should suffice. How this distinction travels through callable
types, aliases, forwarding, higher-order calls, and PAPs remains to be designed.

Heap-valued results inherit the existing return-ownership rules. Deferring `int_to_str n`
produces a fresh owned String on each invocation. Deferring an existing borrowed String `s`
becomes `{ s; }`, which encounters the current restriction on returning borrowed heap values.
Refinement must decide whether that restriction is acceptable initially or whether broader
borrowed-return support is needed. Boolean short-circuiting does not require that decision
to be generalized to every heap-valued case.

## Scope

- In scope: refine general by-name parameter semantics and syntax, then define a bounded
  implementation plan with short-circuiting `and` and `or` as its first acceptance cases.
- Out of scope: implicit memoization and implementation of the future effect system.

## Plan (Approval Gate)

- [x] Select ordinary lambda/thunk desugaring with no implicit memoization.
- [ ] Refine how the sugar exposes lambda ownership/lifetime rules and callable contracts.
- [ ] Agree on lowering placement and a bounded implementation/verification plan.

Approval: Task creation, the settled semantics, and the lambda-sugar direction are authorized.
The detailed implementation plan and remaining design decisions are unsettled;
implementation approval is pending.

## Implementation Checklist

- [ ] Define implementation steps after refinement; no compiler work is approved yet.

## Verification

Task-document consistency, local links, and whitespace checked; focused tracking review
found no actionable issues. No compiler implementation or runtime
verification has run for this task. Future coverage should establish skipped right-hand
evaluation for `false and ...` and `true or ...`, evaluation when needed, and reevaluation
on every parameter use, including observable effects and allocations.

## Risks / Notes

Open refinement topics:

- How source-level by-name uses expose ordinary lambda capture, escape, and consuming-capture
  restrictions, including repeated evaluation that would consume the same captured value.
- Heap-valued thunk results: retaining current borrowed-return restrictions versus broader
  borrowed-return support.
- Preservation of by-name contracts through function types, aliases, higher-order calls,
  forwarding, and partial application. Current eager PAP creation evaluates supplied
  expressions once; by-name arguments need an explicitly different evaluation contract.
- Evaluation order and behavior for zero, one, or multiple uses.
- Placement of wrapping/invocation rewrites and the lifetime of frontend by-name metadata.

## Signoff

- Workstream signoff: Task-document errand approved by the Author's `finish errand`
  instruction on 2026-09-10; feature implementation remains pending.
- Tracked item completion: pending
- Commit authorization: Local commit of this task document authorized by `finish errand`
  on 2026-09-10. No push authorized.

## Task Working Memory

The Author requested this task as a quick task-creation errand, with a brief discussion
first, and confirmed reevaluation on every use with no implicit memoization. The Author then
selected sugar over ordinary lambdas and requested that the decision and discussion be
recorded here. The decision above records the three rewrites, reuse of lambda ownership
rules, the frontend metadata requirement, and the borrowed-result caveat. This task still
needs refinement and has not been selected into active memory. Next action: settle the
remaining design topics and approve a bounded plan before implementation.

The task-document errand is signed off. Its local commit includes only this file; the
separate lambda implementation and other working-tree changes are outside that commit.
The feature stays `planned`. Task creation is administrative bookkeeping, so no product
changelog entry is added.
