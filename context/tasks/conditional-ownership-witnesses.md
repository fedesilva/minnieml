# Make conditional ownership explicit in ownership operations

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-12
- **Target Branch:** dev-lambdas-migration
- **External Reference (optional):** None

## Problem

Conditional ownership needs a coherent contract across cleanup, consumption, and returns.
The main goal is to harden these operations: make their ownership requirements explicit and
keep callers from having to reconstruct the same rules independently.

Mixed allocating/static branches use a Boolean witness to record whether the selected value
requires destruction. The compiler represents the binding as `Owned` with an optional witness.
This lets a helper mistake conditional ownership for unconditional ownership by checking only
`Owned`. The Boolean itself is suitable; the weakness is how ownership operations use it.

A concrete failure illustrates the need:

```mml
fn take(~text: String): Int = text.length;;
fn check(flag: Bool): Int =
  let value = if flag then int_to_str 123; else "abc"; ;
  take value;
;
pub fn main(): Int = check true - 3;;
```

When `flag` is true, `value` holds an allocated String. The consuming function `take` destroys
it, but the caller also emits witness-guarded destruction, causing a double-free. With
`check false`, `value` holds static storage. Treating it as unconditionally owned lets it
reach `take` without the required static-to-owned promotion, so `take` frees static storage.
Both executions should return zero: transfer allocated storage with no caller cleanup, and
establish ownership of the static branch according to the consuming-argument contract.

In [OwnershipAnalyzer.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala),
`withMixedOwnership` records this combination. `argNeedsClone` treats `Owned` as sufficient
for transfer without consulting the witness. The separate witness cleanup in `analyzeLambdaApplication`
does not check the final move state or returned-value escape before inserting a destructor.
Fixing only the duplicate cleanup leaves the static-storage failure intact: cleanup and
transfer must agree on what the witness means.

Returns need the same consistency. For example:

```mml
fn pick(flag: Bool): String =
  let value = if flag then int_to_str 123; else "abc"; ;
  value;
;
pub fn main(): Int =
  let a = pick true;
  let b = pick false;
  if a.length + b.length == 6 then 0; else 1; ;
;
```

The allocated result must remain live after `pick` returns. The static result must satisfy
the function's return-ownership contract so that caller cleanup cannot free literal storage.
A local witness guards local destruction; it does not by itself establish ownership across
a function boundary.

The [mixed-ownership consuming-transfer item](unify-lambdas.md#bug-preserve-mixed-ownership-through-consuming-transfers)
defines the consuming failure and its acceptance cases, including scoped conditional arguments.
This task implements that repair together with hardening the shared representation and operations
across cleanup, consumption, and returns, as one workstream within Unify lambdas.

## Outcome

Keep Boolean witnesses for runtime-dependent ownership. Cleanup, consumption, and return
decisions consistently account for the selected branch's ownership and the value's transfer
state. Strengthen the representation or centralize these decisions so checking `Owned` alone
cannot silently discard conditional ownership information.

## Scope

- In scope: conditional ownership of scoped bindings and argument temporaries; propagation
  through aliases, consuming boundaries, and returns; cleanup decisions; focused regressions
  and directly affected ownership documentation.
- In scope: inspect directly affected owning-field and closure-transfer callers when choosing
  the shared operation boundary; identify any larger work before extending implementation.
- Out of scope: replacing the Boolean witness with a new runtime representation, changing the
  general ownership or cloning policy, binding-construction consolidation, and a new LLVM
  optimization pass.

## Plan (Approval Gate)

- [ ] Trace witness creation, value aliases, ownership transfers, and cleanup obligations.
- [ ] Identify reusable consuming-transfer coverage and check the ownership proposals below
  for policy constraints.
- [ ] Present the smallest representation or shared-operation change that makes the conditional
  ownership contract explicit, including its return-boundary behavior, for Author approval.

Approval: The Author selected combined bug repair and ownership hardening within Unify lambdas
on 2026-09-12. The implementation design is pending approval.

## Implementation Checklist

- [ ] Preserve exactly-once evaluation of predicates and values, including nested conditionals.
- [ ] Route affected cleanup and transfer decisions through the approved ownership model/API.
- [ ] Suppress the former owner's cleanup after a valid move or return. Destroy untransferred
  allocated values exactly once; never destroy borrowed or static storage as locally owned.
- [ ] Preserve borrowed-source rejection at ownership sinks. Establish ownership at consuming
  and return boundaries using the approved contract, including existing static-promotion
  exceptions where applicable; introduce no additional implicit cloning policy.
- [ ] Cover allocated and static branches for ordinary cleanup, named/inline consumption,
  scoped aliases, and function returns. Include both examples above, running the consuming
  example with both Boolean inputs and checking returned values through caller cleanup.
- [ ] Verify homogeneous owned/static controls and nested mixed branches; retain the saved
  branch decision when predicates have observable effects.
- [ ] Document the shared ownership contract and link any shared consuming-transfer tests.
- [ ] Run applicable compiler gates, semantic regressions, native ASan/LSan checks, and review.

## Verification

Implementation verification is pending. Run the consuming and return examples above under
ASan/LSan at `-O 0`; require successful results, no invalid accesses or frees, and no leaks.
Semantic tests must also check transfer and cleanup obligations for both witness outcomes.

During implementation, inspect representative optimized IR to check how witnesses simplify.
Treat LLVM elimination as an optimization opportunity; correctness must also hold at `-O 0`.
Record the observed simplifications without assuming that LLVM eliminates every witness.

## Risks / Notes

- Consuming-transfer tests do not establish return correctness: returns must also preserve
  the result's lifetime and satisfy the caller's ownership expectations.
- [Document and encode the ownership rules](ownership-rules.md),
  [Global-origin ownership](global-origin-ownership.md), and
  [Unify ownership model](unify-ownership-model.md) contain broader policy work. Distinguish
  the current clone exceptions from those proposed rules and resolve any implementation
  conflict with the Author. This task does not approve or replace those proposals.
- Prefer a small, explicit ownership contract over a parallel hierarchy of wrapper types.
  A particular enum redesign or helper API is not predetermined by this task.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Task record only, authorized on 2026-09-12.

## Task Working Memory

Design direction: retain Boolean witnesses and make conditional ownership consistent across
ownership operations. Planned within Unify lambdas as a combined consuming-transfer repair and
hardening effort; implementation has not started. Next: trace the affected operations and
present one bounded implementation plan covering the bug and the shared ownership contract.
