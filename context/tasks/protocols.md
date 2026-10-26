# Implement protocols

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** feature
- **Priority:** unassigned
- **Created:** 2026-10-25
- **Target Branch:** dev/protocols
- **External Reference (optional):** None.

## Execution Checklist

1. [ ] **design** — Settle protocol semantics and the initial implementation scope.
2. [ ] **planned** — Prepare a bounded implementation plan for approval.
3. [ ] **planned** — Implement and verify the approved scope.

## Problem

MML needs ad hoc polymorphism for type-based function and operator selection. Enum
equality requires concrete instances, and memory evolution depends on compiler-recognized
`Unique` and `Clone` contracts.

## Outcome

Parameterized protocol contracts and concrete instances support functions and operators
through a shared instance-resolution mechanism, including compiler-derived enum equality.

## Scope

The [Protocols draft](../../docs/brainstorming/language/protocols.md) defines the design scope:

- Protocol declarations, type parameters, concrete instances, and instance resolution.
- Function and operator members, with shared precedence and associativity checked before
  expression rewriting and candidate selection based on type information.
- Compiler-derived equality for values of the same enum.
- Define the relationship to `Unique` and `Clone` in
  [Memory evolution](../../docs/brainstorming/mem/mem-evolution.md#fundamental-protocols).
- General polymorphic functions and data types are out of scope.
- `FromInt` and compiler-derived integer conversion are deferred.

## Design

See the [Protocols draft](../../docs/brainstorming/language/protocols.md) and its
[syntax section](../../docs/brainstorming/language/protocols.md#syntax), which links
to the standard-library examples. Function members use named, typed parameters;
instance headers omit `for`. Function signatures introduce their own type variables,
with constraints in a `where` clause after the return type. Protocol and instance
headers retain parameter constraints using `:` and `&`.

## Plan (Approval Gate)

Settle the draft's open questions before choosing implementation stages:

- Operator member syntax and optional type annotations in instance implementations.
- Equality protocol name and members, including the relationship between `==` and `!=`.
- Instance selection and diagnostics for missing or ambiguous instances.
- Instance visibility, duplicate instances, and compiler derivation.
- Migration of built-in operators beyond equality.
- Support for multiple protocol type parameters in the first implementation.

Approval: Pending for implementation.

## Verification

Scope is based on the linked design draft. No compiler changes or implementation checks
have been performed for this task.

## Risks / Notes

[Enums](enums.md) depends on parameterized protocols and instance resolution for derived
equality. The initial implementation boundary and the extent of `Unique` and `Clone`
integration require design decisions; the draft is not an approved implementation plan.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Granted for the design task, tracker entry, draft, and sample.

## Task Working Memory

Next action: resolve the open questions in the Protocols draft and agree on the initial
implementation scope before preparing a bounded plan.
