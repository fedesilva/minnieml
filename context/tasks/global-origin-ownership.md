# Global-origin ownership

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

Implicit cloning at global consuming boundaries obscures ownership and static storage.

## Outcome

Preserve global provenance through value flow and never destroy global-backed storage.

## Scope

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

- `Global` is static
- `Global` is never freed
- `Global` is valid in borrow contexts
- `Global` is valid in consuming contexts
- consuming a `Global` value does not invalidate the source

This state may apply to whole values or to subvalues such as struct fields.

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

## Plan (Approval Gate)

- [ ] Revalidate the draft against current code and agree on a bounded implementation plan.

Approval: Spec-to-task migration authorized on 2026-09-10; implementation pending.

## Implementation Checklist

- [ ] Implement and verify the approved scope.

## Verification

Document migration only; no implementation checks run.

## Risks / Notes

Imported design proposals are not evidence of implemented behavior. Recheck source-location
and baseline claims when starting this task.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Not granted.

## Task Working Memory

Created from the retained spec during context cleanup. Not selected for active memory.
Next: discuss scope when the Author selects this task.
