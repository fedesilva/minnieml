# Struct field mutation

## Metadata

- **Owner:** Author
- **Status:** design
- **Kind:** feature
- **Priority:** unassigned
- **Created:** 2026-09-25
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Define field mutation, replacement ownership, and borrow invalidation.
2. [ ] **planned** — Prepare an implementation and verification plan for approval.

## Problem

MML supports struct construction and field access but has no field mutation operation.
Replacing an owned field must account for destruction of its previous value and any
outstanding borrows of that value.

## Outcome

A design for struct field mutation that preserves ownership and prevents invalid use
of borrowed fields after replacement.

## Scope

- Mutation syntax and typing for struct fields.
- Ownership transfer into the field and destruction of the previous occupant.
- Mutation through aliases and validity of outstanding field borrows.
- Evaluation order and the information needed by ownership analysis and codegen.
- Shared ownership principles with array element replacement.

Array implementation and compiler diagnostic recovery are separate work.

## Design example

Source: [struct-field-mutation.mml](../../mml/samples/mem/struct-field-mutation.mml).

```mml
struct Thing { name: String };

pub fn main(): Unit =
  let thing = Thing "Fede";
  let x = thing.name;
  thing.name = "coco";
  println x;
;
```

The intended replacement exposes a design question: what permits or rejects the use
of `x` after the string it borrows is replaced? The borrow-invalidation rule is open.

## Plan (Approval Gate)

- [ ] Settle mutation and borrow semantics before proposing compiler changes.
- [ ] Define bounded implementation steps and tests for the agreed semantics.

Approval: design exploration only; implementation pending.

## Verification

Verification on 2026-09-25: reference resolution rejects `=` as undefined. Further
diagnostics include an ownership single-term-expression error on `Thing "Fede"`.
Removing the assignment compiles and prints `Fede`; this does not exercise mutation.

Reproduce with `sbtn "run run mml/samples/mem/struct-field-mutation.mml"`.

The [array replacement probe](../../mml/samples/mem/array-element-replacement.mml)
compiles and prints `Fede` followed by `coco`. With leak detection enabled,
AddressSanitizer reports a 5-byte leak. The runtime setter overwrites the slot without
destroying the original string, leaving its borrow readable through leaked storage.

## Risks / Notes

Adding destruction alone can invalidate outstanding borrows. Replacement cleanup and
borrow validity must be designed together. Struct fields have static identities;
array indices can require additional alias analysis or conservative restrictions.

## Signoff

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: not granted

## Task Working Memory

Next action: define the permitted mutation operation and its contract for outstanding
borrows. The sample expresses the design question; it is not a passing ownership test.
