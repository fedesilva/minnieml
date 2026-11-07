# Struct construction as capture

Status: discussion draft. Proposed language behavior, not current compiler behavior.

## Motivation

Struct construction currently takes ownership of heap fields. This prevents a local record
from grouping borrowed values without cloning them. Lambda environments already support
borrowing captures and owning captures, selected where the lambda is constructed.

Apply that same choice to ordinary structs. This would make structs more flexible while
keeping ownership consistent with lambdas and requiring little annotation.

## Proposed rule

The declaration describes the fields and their types. The construction site selects ownership
for the whole record. There are no ownership annotations on individual fields.

| Construction | Ownership-tracked inputs | Scalar inputs |
|---|---|---|
| `Person ...` | Borrowed; existing owners retain cleanup responsibility | Copied |
| `~Person ...` | Moved into the record; the record takes cleanup responsibility | Copied |

The notation is provisional. The intended distinction matches `{ ... }` and `~{ ... }`:
borrow all or move all. Per-field selection and partially owning records are outside this
proposal. Both forms capture inputs; `~` selects owning capture.

A borrowing record can group values for local use without invalidating their bindings or
duplicating storage. Its borrows must remain valid throughout its use. Merely staying inside
a function is insufficient if a backing owner is destroyed or moved earlier.

An owning record can escape its construction scope because it carries responsibility for
its fields. Every ownership-tracked input must supply ownership. Moving a record cannot turn
borrowed fields into owned fields; explicit cloning can supply independent ownership where
Clone is supported. Literal and global handling needs reconciliation with the language's
ownership-promotion rules.

## Relationship to lambda environments

Lambda environments already provide the relevant semantic precedent. They use generated
struct layouts, capture ownership metadata, and cleanup that distinguishes borrowed from
owned captures. Ordinary struct cleanup currently follows field types and assumes ownership
of fields requiring destruction.

The implementation question is how to extend that machinery to named structs whose different
construction sites may select different modes. The proposal does not settle representation,
allocation placement, or whether ownership metadata can be shared with closure analysis.

## Questions for further discussion

- How are borrowing dependencies preserved through helpers, aliases, field projections,
  nested records, and conditionals selecting different construction modes?
- What retains a fresh temporary supplied to a borrowing constructor, and for how long?
- How does moving or capturing an existing borrowing record behave?
- Can cloning a borrowing record produce an owning record using the same Clone instance?
- How should constructor aliases, partial applications, and higher-order uses select a mode?
- What is the precise syntax and precedence of `~Person ...`?

## Call-site moves

General call-site moves are related but remain a separate design question. Selecting owning
construction need not first require mode-generic ordinary functions.

[Ownership, access modes, and effects](ownership-mode-effect-matrix.md#mode-generic-functions)
proposes one function usable under borrowing or owning invocation modes. The historical
[optional moves proposal](../../../context/history/optional-moves.md#2-call-site-move)
also mentions call-site moves, with early cleanup as motivation; it is superseded and does
not define current syntax.

## Scope

This proposal is separate from [Explicit Clone and global ownership transfers](
../../../context/tasks/completed/explicit-clone.md). Whether to implement it, and when, remain open.
The [struct capture ownership task](../../../context/tasks/struct-capture-ownership.md)
holds the reminder to resume design.
