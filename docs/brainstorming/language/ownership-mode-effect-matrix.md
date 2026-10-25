# Ownership, access modes, and effects

Status: intuition draft. Describes a proposed model, not current compiler behavior.

## Thesis

A small set of rules governing the interaction between resource types, access modes,
and fundamental effects should allow local reasoning about resource use.

At a program point, the type, access mode, and effect contract should provide enough
information to determine:

- Which operations are permitted.
- Who carries the drop obligation.
- Whether an access may cross a boundary.
- What obligations transfer across that boundary.

Function and component contracts should preserve these guarantees without requiring
elaborate lifetime relationships throughout the program.

The ergonomic hypothesis is that well-structured, functionally decomposed programs
concentrate explicit resource decisions at **pivot points**: boundaries with the
external world or between major internal components.

There is no ambient garbage collection or reference counting. Sharing is an explicit
choice.

## The three dimensions

| Dimension   | Meaning                                               |
|-------------|-------------------------------------------------------|
| Type        | Resource classification and drop obligation.          |
| Access mode | Permission through a particular binding or reference. |
| Effect      | Operations a computation may perform.                 |

`Unique` identifies types requiring ownership tracking and dropping. Other
fundamental classifications remain open.

Candidate access modes are owning, borrowing, mutably borrowing, and sharing.
Access modes are distinct from flow state. A binding may become unavailable after
a move, and availability may depend on the path taken.

A value may have an owner and borrowers simultaneously. The access mode therefore
describes a particular access, rather than one exclusive label attached to the
underlying object.

`Mut`, `Yield`, and `Async` are candidate fundamental effects whose interactions
with access modes need definition.

## Access and responsibility

| Access mode      | Permission                   | Resource obligation      |
|------------------|------------------------------|--------------------------|
| Owning           | Use, lend, transfer.         | Drop or transfer.        |
| Immutable borrow | Read within borrowing scope. | Never drop the resource. |
| Mutable borrow   | Read and mutate exclusively. | End borrow; never drop.  |
| Sharing          | Retain an independent share. | Release the share.       |

- **Owning:** mutation additionally requires the appropriate access and effect
  conditions.
- **Immutable borrow (default):** access must remain within the permitted borrowing
  scope. Borrowing acquires no ownership.
- **Mutable borrow:** access must remain within a bounded exclusive scope.
- **Sharing:** each share keeps the resource alive independently of the originating
  holder. The last release destroys the payload. Mutation requires a separate
  permission or protocol.

`~` expresses ownership transfer. It is an operation that changes ownership state.

A shared handle has its own release obligation. How this fits into the `Unique`
classification remains to be specified.

## Candidate interaction matrix

The short cell labels refer to the explanations immediately below the table.
All effect rules remain proposals.

| Access mode      | Ordinary use | Mut              | Across Yield | Across Async |
|------------------|--------------|------------------|--------------|--------------|
| Owning           | Allowed      | Exclude conflict | Retain/move  | Transfer     |
| Immutable borrow | Scoped       | No write access  | Conditional  | Conditional  |
| Mutable borrow   | Exclusive    | Target mutation  | End borrow   | No escape    |
| Sharing          | Explicit     | Separate rule    | Retain share | Task share   |

### Owning

- **Allowed:** ownership remains until transfer or drop.
- **Exclude conflict:** candidate permission to mutate when conflicting accesses
  are excluded.
- **Retain/move:** candidate permission to retain ownership in suspended state,
  or transfer it as the yielded result.
- **Transfer:** candidate permission to transfer ownership into the task.

### Immutable borrow

- **Scoped:** ordinary use is allowed within the borrowing scope.
- **No write access:** cannot mutate through this access. Unrelated mutation is a
  separate question.
- **Conditional across Yield:** suspension must preserve the owner and the borrow's
  validity.
- **Conditional across Async:** the task must remain within a boundary that
  preserves the owner and valid access.

### Mutable borrow

- **Exclusive:** ordinary use is allowed within a bounded exclusive scope.
- **Target mutation:** candidate permission to mutate the borrowed target.
- **End borrow:** candidate restriction requiring the borrow to end before
  suspension.
- **No escape:** candidate restriction preventing transfer of the borrow into an
  independently running task.

### Sharing

- **Explicit:** ordinary use is allowed through an explicitly acquired share.
- **Separate rule:** sharing alone grants no mutation permission.
- **Retain share:** candidate permission to retain a share in suspended state.
- **Task share:** candidate permission to transfer or acquire a share for the task,
  subject to the payload's concurrency rules.

“Conditional” means that the cell needs a precise local rule. It does not mean that
the compiler should reconstruct arbitrary lifetime relationships.

These rules apply to the **access that crosses the boundary**. A function having an
effect does not necessarily mean every local access crosses that effect's boundary.

## Boundaries to distinguish

| Boundary         | Central question                          |
|------------------|-------------------------------------------|
| Ordinary call    | Borrow, consume, or retain a share?       |
| Return or escape | What access and obligation leave scope?   |
| Suspension       | What state and obligations remain alive?  |
| Yielding a value | Transfer ownership or lend access?        |
| Starting a task  | What lifetime and concurrency guarantees? |
| Cancellation     | Who drops the abandoned state?            |

- **Ordinary call:** does the callee borrow, consume, or retain a share?
- **Return or escape:** does the result carry ownership, a share, or a borrow whose
  validity would need to extend beyond the scope?
- **Suspension:** which values and obligations remain in suspended state?
- **Yielding a value:** is ownership transferred to the consumer, or is access lent
  temporarily?
- **Starting a task:** can the task outlive the calling scope? Can it execute
  concurrently or on another thread?
- **Cancellation or abandonment:** who drops the resources held by the suspended
  computation?

`Yield` and `Async` need definitions precise enough to answer these questions.
Their names alone do not settle them.

## Local reasoning contract

The intended division of responsibility is:

1. The caller establishes the required access mode.
2. The callee checks its body against that mode and its effect contract.
3. Crossing an effect boundary checks the applicable matrix rule.
4. Ownership and release obligations are recorded explicitly.
5. A separate rewriter implements those obligations without rediscovering ownership
   relationships.

An effect declaration should carry the relevant guarantees. We should not need to
inspect every handler implementation at every call site.

## Mode-generic functions

Proposed extension: a caller may supply `~` even when the function's parameter is
borrowing by default.

The intended result is one source-level function usable under different access
modes.

For example, a borrowing `map` preserves its input. An owning invocation can consume
the input and may reuse its storage where representation permits.

This requires defining how the selected mode propagates through:

- Element access.
- Callback parameters.
- Result construction.
- Cleanup.

Transferring ownership of a callback's captures and allowing it to consume an element
are distinct decisions. A callback used by `map` must still support repeated
invocation.

## Mutation and functional reasoning

The hypothesis is that statically established ownership can permit in-place mutation
while preserving the observable behavior of a pure transformation.

The required conditions include:

- No other valid access observes the previous value changing.
- Mutation does not escape through shared state.
- Callback effects remain accounted for.
- Storage reuse preserves the transformation's semantics.

Where exclusive access is established statically, reuse should not require a runtime
reference-count uniqueness test.

## Implementation direction

Resolve ordinary effect dispatch statically where possible and emit ordinary calls
and control flow.

Operations that genuinely require suspension or resumption may use a representation
similar to closures: an entry point and captured state, with explicit ownership
obligations.

This does not imply a general CPS calling convention at the LLVM level.

## Questions to test

- Can the borrowing rules remain local without rejecting too many useful programs?
- Which accesses may survive suspension?
- What guarantees distinguish scoped tasks from independently escaping tasks?
- How do cancellation and abandoned continuations discharge drop obligations?
- Can mode-generic functions avoid duplicated source implementations without
  excessive compiler machinery?
- Do explicit decisions stay at pivot points as programs grow?
- Does the resulting code remain predictable, fast, and pleasant to write?

The readable specification, formal model, compiler tests, and real MML programs
should test the same rules. The matrix is the starting hypothesis.
