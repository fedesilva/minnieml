# Protocol derivation

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** feature
- **Priority:** unassigned
- **Created:** 2026-10-26
- **Target Branch:** unassigned
- **External Reference (optional):** None.

## Execution Checklist

1. [ ] **design** — Define protocol derivation scope and semantics after the initial protocol system.
2. [ ] **planned** — Prepare a bounded implementation plan for approval.
3. [ ] **planned** — Implement and verify the approved derivation scope.

## Problem

The [initial protocol task](protocols.md) provides concrete instances. The
[explicit Clone task](explicit-clone.md) adapts memory-function generation to Clone. A general
derivation facility needs separate rules
for eligibility, generated member bodies, and interaction with explicit instances.

## Outcome

A defined and verified protocol derivation mechanism builds on the initial protocol system.
The targeted generation of Clone instances belongs to [explicit Clone](explicit-clone.md).

## Scope

- Placeholder for general protocol derivation, including compiler-derived enum equality
  described in the [Protocols draft](../../docs/brainstorming/language/protocols.md#derived-equality).
- [Implement protocols](protocols.md) supplies the protocol mechanism;
  [explicit Clone](explicit-clone.md) supplies targeted Clone generation, outside this facility.
- Broader ownership requirements remain in
  [Memory evolution](../../docs/brainstorming/mem/mem-evolution.md#aggregate-derivation).

## Plan (Approval Gate)

Define the supported derivations, eligibility rules, and implementation stages after the
initial protocol system. Reuse its instance mechanism and account for generated Clone instances.

Approval: Pending for design details and implementation.

## Verification

Implementation verification and acceptance cases will be defined with the bounded plan.

## Risks / Notes

Depends on the declaration, instance checking, resolution, and lowering mechanisms delivered
by [Implement protocols](protocols.md). This placeholder does not select a derivation API
or approve compiler changes.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Not granted.

## Task Working Memory

Protocol derivation is separate from the initial protocol slice. General derivation and
enum equality need a bounded design. Targeted Clone generation belongs to [explicit
Clone](explicit-clone.md) and does not depend on this general facility.
Next action: after the initial protocol system, define the general derivation scope and plan.
