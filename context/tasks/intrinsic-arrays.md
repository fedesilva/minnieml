# Intrinsic arrays

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** feature
- **Priority:** HIGH
- **Created:** 2026-11-07
- **Target Branch:** `dev/intrinsic-arrays`

## Execution Checklist

1. [ ] **design** — Specify the sized-initializer API and analysis representation for required call effects.
2. [ ] **planned** — Approve a bounded implementation and migration plan.
3. [ ] **planned** — Implement intrinsic arrays and migrate existing array uses.
4. [ ] **planned** — Verify compiler behavior and ownership, review, and obtain signoff.
5. [ ] **design** — [Optimize storage placement](#storage-placement-optimization) after the heap-backed implementation.

## Problem

Separate monomorphic array types and operation families duplicate array behavior.
The language needs one intrinsic array type with an element type parameter, literals,
and indexed access, without requiring general polymorphism.

## Outcome

`Array T` provides element-aware construction, access, replacement, length, destruction,
and conditional cloning, with explicit ownership and borrow-validity rules.

## Scope

Follow the [intrinsic-array draft](../../docs/brainstorming/language/intrinsic-arrays.md).
Cover array types, literals, indexing, checked and unchecked operations,
element ownership, and migration from the existing array families.
General polymorphism, user-defined generic types, and static bounds proofs are separate work.
The initial implementation uses heap-backed storage. String unification is out of scope.
Storage placement optimization is a later subtask.

## Plan (Approval Gate)

Settle the draft's open decisions before preparing bounded implementation stages.
Literals use a `Sequence` AST node. Construction does not clone elements; sized
non-scalar construction uses an initializer. Optional lowering to an internal lambda
may reuse type checking and ownership analysis. Specify the sized-initializer API
and analysis representation for required mutation-effect propagation through calls.
Any array write invalidates all existing non-scalar element borrows from that array,
regardless of index. Reads do not invalidate borrows; copied scalar values remain valid.
Deliver implementation in small sequential stages with review between stages.

Approval: pending for implementation.

### Storage placement optimization

- **Status:** design; follows the initial heap-backed implementation.
- Determine when array backing storage can safely use the stack using size, lifetime,
  and escape information. Bound stack use and retain heap allocation when proof is absent.
- Preserve element ownership, destruction, and borrow semantics across storage choices.
- Verify local and escaping arrays, target-derived size and alignment, and element cleanup.
- Prepare a bounded implementation plan before starting this optimization.

## Verification

Implementation checks remain to be defined with the approved plan. Cover type and syntax
diagnostics, bounds behavior, initialization, replacement, ownership, cleanup, and migration.

## Risks / Notes

Replacement cleanup and element borrows must be designed together. Coordinate with
[struct field mutation](struct-field-mutation.md) and the candidate
[backward-liveness analysis](../../docs/brainstorming/mem/backward-liveness.md).

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The initial scope uses heap-backed arrays and excludes string unification. Any write
invalidates all existing non-scalar element borrows from the affected array.
Literals use `Sequence`, with optional internal lambda lowering. Construction does not
clone elements. Borrow invalidation follows aliases, captures, and transitive calls,
including borrow uses within a call. Next action: specify the sized-initializer API
and analysis representation, then prepare small implementation stages.
