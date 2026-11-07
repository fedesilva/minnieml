# Allow globals at owning boundaries without cloning

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** feature
- **Priority:** HIGH
- **Created:** 2026-10-30
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Settle mode propagation, explicit-drop requirements, and boundary contracts.
2. [ ] **planned** — Approve bounded stages for replacing mandatory cloning at global owning boundaries.
3. [ ] **planned** — Implement and verify the approved stages, with review between stages.

## Tags

- ownership
- globals
- protocols

## Problem

The compiler rejects global-backed values at owning boundaries unless explicitly cloned.
Global storage already outlives local calls and escaping values. Requiring independent storage solely because an argument is global
prevents useful zero-copy retention and forwarding.

Static lifetime does not grant permission to destroy storage. Accepting globals requires their
mode to survive every transfer, so neither generated cleanup nor explicit destruction can free
storage still reachable through the global binding.

## Outcome

Allow global-backed values at consuming calls, returns, aggregate fields, move captures, and
partial-application payloads without implicit cloning. Preserve global availability and mode
through those operations. Destroy only owned storage. Keep explicit `^` for independent copies
and operations that require actual ownership.

Replace the blanket requirement to clone globals at owning boundaries with mode-aware
acceptance. Preserve explicit Clone support: `^` borrows its input and produces an independent
owned copy. Migrate rejection diagnostics and fixtures to distinguish valid global retention
from operations that require ownership. These are proposed changes, not current behavior.

## Scope

- Forward propagation of global and owned modes through aliases, projections, calls, returns,
  nested aggregates, conditionals, closures, and partial applications.
- Cleanup that preserves global-backed storage while destroying owned storage exactly once.
- Backward inference of ownership requirements from future explicit `Unique.drop` operations.
- Contracts for higher-order calls, native boundaries, and separate compilation.
- Out of scope: general permission for local borrows to escape, automatic cloning, and a full
  implementation of Unique unrelated to the destruction contract needed here.

## Design direction

### Retention and destruction are separate permissions

A consuming parameter may accept owned or global-backed storage. Both can be retained or
forwarded beyond the call; only owned storage carries destruction responsibility. Passing a
global does not invalidate its binding or create an independent copy. Ordinary local borrows
retain their lifetime restrictions and do not acquire global mode by entering a consuming call.

Generated cleanup follows the value's mode. Known global storage receives no drop. Where a
runtime branch chooses global or owned storage, cleanup must use the selected mode. A record
containing fresh allocations and global-backed fields must preserve their distinct cleanup
responsibilities, including through nesting and subsequent transfers.

### Explicit destruction establishes an ownership requirement

Treat the future fundamental protocol member `Unique.drop` by resolved semantic identity,
not by the spelling `drop`. Reject explicit destruction of a global-backed argument, including
through aliases and indirect calls. Compiler-inserted cleanup does not establish this requirement;
it remains conditional on mode.

A local check at the explicit-drop call is sufficient when the argument's mode is known there.
For functions analyzed independently of their callers, infer a requirement and propagate it
backward through parameter and value flow to callers. Schematic example, not MML syntax:

```text
dispose(x): drop(x)
forward(y): dispose(y)
caller:    forward(GLOBAL)
```

`dispose` requires owned `x`; `forward` inherits that requirement for `y`; the final call is
rejected. Function summaries can carry inferred requirements without requiring source
annotations. Recursive dependencies need a fixed-point analysis. Higher-order callable
contracts must retain the requirement when the function is passed, stored, or returned.

Modes flow forward to determine cleanup and result provenance. Explicit destruction
requirements flow backward to determine valid inputs. Neither direction alone replaces the other.

### Lifetime is not uniqueness

Global mode proves storage lifetime, not exclusive access. Future Unique operations requiring
exclusivity need a separate check. A consuming parameter must not imply uniqueness merely
because it accepts a global. Native ownership-taking functions that unconditionally free their
inputs require owned storage or an explicit copy.

## Plan (Approval Gate)

Approval: design direction captured; representation and implementation plan pending.

1. Define the parameter/result and field-mode contracts, including mixed alternatives and
   explicit-drop requirements. Settle the open issues below with concrete examples.
2. Inventory reusable ownership witnesses, capture metadata, and global-provenance analysis.
   Choose representation and analysis boundaries; document any ABI or aliasing changes.
3. Prepare bounded implementation stages with acceptance cases and migration of global-transfer
   restrictions, diagnostics, and fixtures, with review/signoff between stages. Decide when
   Unique.drop support becomes a dependency.
4. Implement the approved stages and update the memory model, language reference, compiler
   design, examples, and affected tests. Apply the repository's compiler verification gates.

## Verification

Acceptance coverage to refine with the implementation plan:

- Repeated global use after consuming calls and after destruction of returned records/closures.
- No hidden Clone invocation or allocation solely to cross a global ownership boundary.
- Owned controls still transfer and clean up exactly once; explicit copies remain independent.
- Nested records mixing global-backed fields with fresh allocations clean up only owned fields.
- Global/owned conditionals retain correct cleanup through helper returns and further calls.
- Direct, aliased, forwarded, recursive, and higher-order explicit drops reject global inputs.
- Ordinary generated cleanup does not make a function owned-only.
- Borrowed locals cannot escape by being mistaken for global-backed or owned values.
- Closures, partial applications, native adapters, and external contracts preserve requirements.
- Runtime cases at O0/O3 with ASan/LSan, plus applicable ABI/container checks if lowering changes.

No implementation verification is claimed by this design task.

## Risks / Notes

Open decisions:

- Choose static specialization, runtime mode metadata, or a combination. Define how modes
  survive function results and separately compiled boundaries without relying on inlining.
  - no runtime metadata as much as possible. needs to work with not owned native values and types.
  - 
- Define field-level cleanup representation. The borrowing-struct proposal excludes partially
  owning records, while this design must handle owned records containing global-backed fields.
- Define mixed-mode explicit-drop behavior: a value with a possible global alternative must
  be rejected unless analysis proves the argument owned on the path reaching the operation.
- Decide precision for conditional drops, field projections, result-dependent requirements,
  recursion, and unknown higher-order targets; conservative rejection must remain explainable.
- Specify diagnostics showing the global call site and the explicit destruction that requires
  ownership, including requirements propagated through several helpers.
- Audit uniqueness and aliasing assumptions, including consuming-parameter LLVM attributes;
  global values can remain reachable and may be passed repeatedly or through multiple aliases.
- Decide native/external contract encoding and how unknown destruction behavior is handled.
- Settle whether literal storage shares this policy or remains a separate follow-up.
- Clarify the source meaning and naming of consuming/owning parameters under mode-dependent
  destruction, and the exact dependency on future Unique integration.

Related tasks and designs (optional context):

- [Explicit Clone and global ownership transfers](completed/explicit-clone.md): explicit copying, global
  provenance analysis, and the mandatory-cloning policy this design replaces.
- [Revisit implicit cloning](implicit-cloning.md): overlapping promotion, literal/global,
  and mixed-witness policy; reconcile scope to avoid competing ownership rules.
- [Struct capture ownership](struct-capture-ownership.md) and
  [its design](../../docs/brainstorming/language/struct-capture-ownership.md): borrowing versus
  owning construction and the unresolved representation of field ownership.
- [Unify ownership model](unify-ownership-model.md): broader memory evolution and consistency.
- [Document and encode the ownership rules](ownership-rules.md): durable ownership reference
  and elimination of hidden global cloning.
- [Owned Strings](owned-strings.md): shareable literals and older global-cloning assumptions.
- [Implement protocols](completed/protocols.md): protocol infrastructure; full Unique integration is
  outside its initial slice.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Design target: permit zero-copy global retention at owning boundaries, preserve modes forward,
and infer explicit-drop ownership requirements backward. Representation, callable contracts,
and implementation stages need approval. Next: settle mixed-field representation and function
summaries, then plan migration from mandatory global cloning to mode-aware acceptance,
including consistent treatment of implicit cloning and mixed ownership.
