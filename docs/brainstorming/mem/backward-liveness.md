# Backward liveness for borrow invalidation

Status: Draft

---

## Goal

Check whether a non-scalar element or field borrow is used after an operation
invalidates its storage.
[Intrinsic arrays](../language/intrinsic-arrays.md#mutation-effects-and-borrows) are the
first application. Struct field replacement has the same requirement.

The approach follows Evan Ovadia's
[reverse borrow checking](https://verdagon.dev/blog/reverse-borrow-checking): establish
borrow provenance and mutation effects, then check future uses with a backward walk.
The invalidation policy and its implementation in MML remain open.

---

## Required information

| Fact | Meaning |
|------|---------|
| Borrow provenance | The owner or storage path on which a borrow depends. |
| Mutation effect | The storage paths an operation may invalidate. |
| Liveness | The borrow values that later execution may use. |

Track individual borrows by resolved binding identity. Several borrows may depend on
the same owner, but a fresh read must remain distinct from an older borrow. Propagate
dependencies through aliases and captures.

Copied scalar values have no element-borrow dependency. Scalar-only aggregates are
non-scalar values: reading an aggregate element borrows it rather than copying it.

---

## Backward walk

Walk operations in reverse evaluation order, carrying the borrows required by the
continuation:

- A use adds a requirement for its borrow and dependencies.
- A binding removes the requirement for the value it defines and propagates any
  required source borrows through its initializer. Binding an alias preserves the
  source dependency. Always check the initializer's own uses and effects.
- An invalidating operation rejects requirements whose storage paths overlap its
  effect. Check the continuation's requirements before adding the operation's own
  operand uses.
- A conditional checks each branch with the continuation's requirements, then unions
  the incoming requirements before checking the predicate.

This uses ordinary [backward liveness](https://www.cs.cornell.edu/courses/cs4120/2023sp/notes/livevar/)
with an additional invalidation check. The walk direction determines when facts are
available; provenance and effect precision determine which programs are accepted.

---

## Array replacement

The array draft proposes an initial rule: any write invalidates all existing non-scalar
element borrows from that array. Index-specific tracking can refine this later.
The effect covers element borrows in `xs[]`; the array value itself remains usable.

Using the proposed syntax, with `xs: Array String` and an owned `replacement`:

```mml
let x = xs[0];
array_set xs 0 replacement;
println x;
```

The backward walk reaches `println x` first, so `x` is required. At the write, its
dependency on `xs` conflicts with the invalidation effect. Reject the program.

This order is allowed:

```mml
let x = xs[0];
println x;
array_set xs 0 replacement;
```

At the write, `x` has no future use. Its binding remains in scope, but the borrow is
dead. A fresh read after the write is also allowed: its definition discharges that
borrow's requirement before the backward walk reaches the write.

Under this rule, writing `xs[1]` also conflicts with a live non-scalar element borrow from
`xs[0]`. Liveness narrows the duration of the restriction without proving indices
distinct. Checked and unchecked accesses follow the same rule.

---

## Calls and captures

Effects must identify the affected owner through aliases and function arguments.
Checking direct writes alone misses mutations inside helpers or closures.

A borrow can be needed during a call even when it has no use afterward. A function
that receives both an array and an element borrow must not replace the element and
then use that borrow. Call checking must account for aliases between arguments and
captured values, as well as the continuation's requirements.

Borrowing closures and partial applications retain dependencies on their captured
values. Later use of a callable must preserve those dependencies even when the owner
has no later direct reference.

---

## Fit with ownership analysis

The [ownership analyzer](../../design/compiler-design.md#semantic-phase-14-ownershipanalyzer)
threads ownership state forward through initializers and continuations, then inserts
cleanup while unwinding scopes. It checks moves and borrow escape; mutation
invalidation is not implemented.

A backward pass could use the ownership and provenance facts established forward.
MML's scoped binding representation already exposes the continuation to analyze first.
Future-use information could also support consuming-parameter last-use checks.

Ownership still determines who destroys a value, including conditional ownership
witnesses. Liveness alone does not permit earlier destruction: destructor effects
must retain their ordering.

MML expresses iteration through recursion. Ownership analysis precedes loopification.
If checking later moves to a control-flow graph, back edges require explicit dataflow
handling; reverse traversal alone does not resolve cycles.

---

## Open decisions

- Effect contracts for direct, native, recursive, and higher-order calls.
- How call contracts express conflicts between argument and capture borrows.
- Whether the backward pass is separate from ownership analysis or shares its traversal.
- The initial invalidation granularity for
  [struct fields](../../../context/tasks/struct-field-mutation.md).
