# Global-origin ownership — deprecated

## Metadata

- **Owner:** The Author
- **Status:** deprecated
- **Created:** 2026-09-10
- **Target Branch:** `dev/global-origin-ownership`
- **External Reference (optional):** None recorded.

## Execution Checklist

No active implementation steps. The proposal is deprecated and archived without implementation.

## Deprecation decision

Globals live forever and can be borrowed without additional runtime ownership metadata.
They must not enter ownership sinks directly. Explicit cloning through the clone protocol
and `^` should produce an owned value that can enter a sink.

The proposed transparent flow of globals through ownership sinks is rejected. It would
require preserving owned/global distinctions across calls, returns, aggregate fields,
and closure environments. Passing runtime witnesses across those boundaries introduces
calling-convention and layout changes, storage and cleanup overhead, and native ABI
complications. Other representations would still require a broader representation design.
The borrow-only rule avoids that machinery for global values.

The compiler currently accepts globals at supported ownership sinks by implicitly cloning
them. The replacement direction is protocols and explicit `^` cloning, followed by rejecting
implicit global-to-owned conversion. That behavior is not implemented by this task.

## Signoff

- Deprecation and archival: authorized.
- Implementation completion: not claimed.
- Commit authorization: granted for the archival and accompanying tracking changes.

## Task Working Memory

No implementation remains active under this task. The retained proposal below is historical
and must not be used as an implementation plan. Replacement work requires its own task scope
and approval; no replacement task is created here.

## Historical proposal and baseline evidence

The following design, stages, acceptance criteria, and pending approvals belong to the
rejected proposal. They are retained as history, not current requirements.

## Historical Problem

Implicit cloning at global consuming boundaries obscures ownership and static storage.

## Historical Outcome

Preserve global provenance through value flow and never destroy global-backed storage.

## Historical Scope

The retained design below defines the proposed scope; implementation requires a bounded plan.

### Global ownership design

Retained semantic proposal; implementation remains open.

#### Why

MML currently gives globals special treatment. If they need to be moved, the compiler
clones them.

That works for the current compiler, but it is not a good long-term model. Static data
should stay static.

The compiler should not force clones just to make local ownership bookkeeping work.

Instead:

- globals should work in borrow positions
- globals should work in consuming positions
- ownership logic must never release global-backed data

#### The model

`Global` should be a regular ownership state, alongside:

- `Owned`
- `Borrowed`
- `Moved`
- `Global`

Its rules are simple:

- `Global` lives forever, regardless of whether its initializer runs at compile time or runtime
- `Global` is never freed
- `Global` is valid in borrow contexts
- `Global` is valid in consuming contexts
- consuming a `Global` value does not invalidate the source

This state may apply to whole values or to subvalues such as struct fields.
There is no destruction responsibility to transfer for global-backed storage.
`Static` is a separate, deferred immutability distinction; global lifetime alone does not
establish immutability.

#### Direct use

A global heap value should be accepted anywhere an owned value is accepted:

- borrowed
- passed to a consuming parameter
- passed to a consuming constructor field
- forwarded through other ownership-transfer paths

Using a global in a consuming position must not invalidate the source global.
You should be able to consume it again later, or borrow it later, and that should
still be valid.

#### Aggregates

Building a struct from globals does **not** mean the whole struct becomes global.

Example:

```mml
struct Person { name: String, age: Int };

let g_name = "fede";

fn f(): Unit =
  let p = Person g_name 42;
  ();
;
```

Here:

- `p` is an ordinary owned local value
- `p.name` is `Global`
- dropping `p` is fine
- dropping `p` must not free `p.name`

That is the important rule. The container can die. The global-backed field cannot be
released as part of that destruction.

#### Propagation

`Global` should survive ordinary value flow where the underlying data is still the same:

- assigning a global to a local
- storing a global in a struct field
- returning a global-backed value
- passing a global-backed value through functions

Fresh allocation still creates ordinary owned data.

Mixed values are allowed. A struct may contain:

- owned fields that should be released
- `Global` fields that should be left alone

The compiler has to keep those apart.

#### Destruction

Destructors and free insertion need to become `Global`-aware.

For aggregates, destruction should:

- release owned fields
- skip `Global` fields

More generally, any free path that reaches `Global` data must treat that data as
non-releasable.

That is the whole point of this change:

- stop cloning globals just to satisfy consuming flows
- keep normal ownership for containers and locals
- never free global-backed storage by mistake

#### What this is not

- Not call-site move syntax
- Not a mutation design
- Not "every value derived from a global becomes itself a global"
- Not a promise that all complex globals become statically initialized

That last point matters. Runtime-initialized globals are a separate problem.

#### Design pressure

This is probably not just one analyzer `case` branch.

Whatever representation we choose, it has to preserve `Global` state through at least:

- global loads
- local bindings
- struct construction
- function calls
- returns
- destructor generation
- free insertion

The implementation is open. The semantics should not be.

#### Acceptance criteria

- A global heap value may be passed to a consuming parameter without an implicit clone.
- The source global remains usable after that consuming use.
- A struct built from global-backed fields may be dropped normally.
- Dropping that struct does not free its global-backed fields.
- Mixed aggregates free only their owned subvalues.
- Returned values preserve `Global` state where that distinction matters.
- Repeated consuming uses of the same global-backed value remain valid.

## Historical Plan (Approval Gate)

The following division is proposed. Representation and boundary policies remain open;
listing these stages does not authorize implementation. Stop for review and signoff after
each implementation stage before advancing.

### 1. Contracts and representation

- Revalidate the semantic design against the existing `Global`, `Mixed`, return-contract,
  and destruction machinery.
- Define how global provenance survives consuming MML parameters and returns. Ordinary
  borrowed values must not acquire global lifetime or permission to escape.
- Decide whether forwarding through borrowing helpers requires a parameter-dependent
  return contract, specialization, or an explicit restriction.
- Choose how runtime-selected owned/global alternatives and per-field provenance cross
  calls and storage boundaries. Compare explicit metadata, specialization, and runtime
  storage representations; none is selected yet. A cleanup flag alone does not distinguish
  a global from an ordinary borrow.
- Settle native consuming-call contracts. Native callees may free or retain arguments
  themselves; compiler-only provenance does not protect global storage inside such calls.
  Decide supported adapters or restrictions explicitly without silently narrowing the design.
- Decide whether removing implicit literal clones belongs in this task. Keep `Static`
  immutability and changes to global initialization outside the implementation division.
- Identify calling-convention, aggregate-layout, and LLVM alias-attribute implications.
- Produce a bounded implementation plan for stage 2 and obtain approval.

### 2. Calls and returns for simple heap values

- Preserve global provenance through local aliases, consuming MML calls, and returns
  without implicit global clones.
- Carry the distinction when a runtime branch selects owned or global-backed storage.
- Keep owned transfers affine, global sources reusable, and borrowed escapes rejected.
- Cover direct and indirect calls, forwarding, recursion, mixed return paths, and repeated
  consuming uses of a proven global.
- Apply the agreed native-call policy and review consuming-pointer `noalias` assumptions.

### 3. Aggregate storage and destruction

- Preserve provenance per field when constructing, moving, nesting, and returning aggregates.
- Keep local containers owned even when some or all fields refer to globals.
- Make generated destruction release owned fields and leave global-backed fields untouched.
- Preserve the distinction through field projections and runtime-selected field values.
- Verify nested aggregates, different ownership patterns for the same struct type, and
  independent cleanup of owned and global-backed fields.

### 4. Closure and partial-application environments

- Preserve global provenance and conditional owned/global payloads in stored captures
  and supplied partial-application arguments.
- Keep environment ownership separate from the provenance of each stored payload.
- Preserve provenance when invocation forwards or returns a payload.
- Verify uncalled environment cleanup, invocation cleanup, nested captures, and payload
  forwarding. Retain call-once restrictions wherever invocation can transfer owned payloads;
  settle reuse rules for proven global-only payloads.

### 5. Integration verification and documentation

- Verify global consuming paths contain no implicit clones and never destroy global backing.
- Verify owned alternatives and owned subfields are destroyed exactly once, while ordinary
  borrow escape and use-after-move errors remain rejected.
- Check evaluation order and single evaluation of conditions and arguments.
- Update the memory model and compiler design to describe the implemented contracts,
  representation, native boundaries, and remaining limitations.
- Run the applicable compiler checks, memory harness, and review gates. Calling-convention
  or C interoperability changes require host and both Linux container ABI checks under
  `context/coding-rules.md`.

Approval: Implementation plan and representation decisions pending.

## Historical Verification

Baseline verification on 2026-10-25:

```sh
sbtn "testOnly mml.mmlclib.semantic.MixedOwnershipTests mml.mmlclib.semantic.OwnershipAnalyzerTests"
```

Result: 148 tests passed, no failures. This establishes the existing behavior, including
implicit cloning and current capture restrictions; it does not verify the proposed design.

## Historical Risks / Notes

Imported design proposals are not evidence of implemented behavior. Recheck source-location
and baseline claims when starting this task.
