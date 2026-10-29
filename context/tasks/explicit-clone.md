# Explicit Clone and global ownership transfers

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** feature
- **Priority:** unassigned
- **Created:** 2026-10-28
- **Target Branch:** unassigned
- **External Reference (optional):** None.

## Execution Checklist

1. [ ] **planned** — [P7: Integrate explicit Clone and generated instances](#p7-explicit-clone).
2. [ ] **planned** — [P8: Require explicit cloning for global ownership transfers](#p8-global-ownership-transfers).
3. [ ] **planned** — Complete documentation, compiler verification, and final review.

## Tags

- protocols
- ownership

## Problem

Ownership transfers from global-backed values currently rely on implicit cloning. Explicit
cloning needs a compiler-recognized protocol and concrete native and generated instances.

## Outcome

`^` borrows its input and produces an independent owned copy. Ownership sinks require an
explicit copy of global-backed values while ordinary borrowing and scalar copies remain valid.

## Scope

- After Num verifies the protocol system, inject `Clone`, declaring unary `^`. Adapt the
  existing memory-functions phase to generate Clone instances for eligible concrete types,
  reusing its cloning machinery. This does not require a general derivation facility.
  `^value` borrows its input and returns a separate owned value, leaving the input valid.
  Follow the contract in [Memory evolution](../../docs/brainstorming/mem/mem-evolution.md#layer-2-clone-protocol).
  Include the native clone implementations needed by generated instances.
- Remove implicit cloning of globals at ownership sinks. Reject transfers of global-backed
  values into owned storage; the programmer must explicitly use `^` to produce an owned copy.
  This applies to transfers requiring ownership, not ordinary scalar value copies.

General derivation, Unique integration, and broader literal-cloning policy are out of scope.
Follow [memory evolution](../../docs/brainstorming/mem/mem-evolution.md#layer-2-clone-protocol).

## Plan (Approval Gate)

Approval: P7 and P8 implementation scope and delivery order approved. Execute after
[Implement protocols](protocols.md), preserving review and signoff between bounded stages.

### P7: Explicit Clone

- Inject the compiler-recognized Clone protocol with borrowing unary `^`; propose precedence
  95 and right associativity, matching existing prefix operators.
- Register explicit native Clone instances using the existing runtime implementations for
  String, Buffer, Rng, IntArray, StringArray, and FloatArray. Check their allocating-result
  metadata and borrow-input contracts; add missing native support only where required by
  the approved eligible types. Heap representation alone must not imply Clone support.
- Adapt memory-function generation to register eligible concrete struct Clone instances and
  their ordinary member bindings. Register signatures before generating bodies so
  nested struct dependencies resolve by identity rather than declaration order.
- Copy scalar fields and invoke registered Clone members for non-scalar fields. Preserve
  rejection of unsupported function-bearing structs and types without cloneable members.
  Include scalar-only struct copies without introducing general protocol derivation or Unique.
- Use the shared instance/member lookup API for explicit Clone calls and the existing internal
  clone machinery. Retain internal helpers where needed for literal promotion; do not remove mixed
  witnesses, conditional cleanup, or unrelated destruction generation.
- Ensure generated and user-supplied Clone instances satisfy ordinary ownership checking
  and return a separate owned result for ownership-tracked types. Native bodies remain trusted
  implementations with runtime verification. Merely returning the borrowed input is invalid.

Exit: explicit cloning of native and nested struct values executes correctly; the source stays
valid and the copy has independent owned storage. Missing support and duplicate explicit/generated
instances fail clearly. Test Clone calls as ordinary callable values and partial applications,
plus cleanup of both source and result under sanitizers.

### P8: Global ownership transfers

- Trace global origin through local aliases, aggregate field projections, conditional results,
  and statically proven helper returns. Do not relabel a global-backed value as owned merely
  because it passes through a local binding or aggregate.
- Reject global-backed alternatives where a consuming parameter, owning field, owning return,
  move capture, or partial-application payload requires ownership. Diagnose the transfer site
  and suggest an explicit `^` copy. Continue accepting ordinary borrowing and scalar copies.
- Remove global-specific implicit clone insertion at those boundaries, including mixed
  alternatives. Preserve literal-only promotion and witness-based moves and cleanup.
- Verify that `^` produces independent ownership and ends the cloned result's global provenance.
  Migrate affected executable examples and fixtures to explicit cloning before transfer.

Exit: paired rejection/acceptance tests cover each boundary, including aliases and nested fields.
Mixed owned/literal cases keep their existing behavior; global alternatives require explicit
cloning only when ownership is demanded. Runtime tests establish that local cleanup never frees
original global storage and explicit copies are cleaned up exactly once.

### Documentation and final verification

Update the language reference, compiler design, memory model, and executable examples for
Clone and explicit global transfers. Follow [coding rules](../coding-rules.md) and
[development tools](../dev-tools.md), including local post-chores, smoke tests, full tests,
publishing, benchmark compilation, memory checks, and independent review. Apply required
Linux checks if ABI/C interoperability changes; offer optional cloud verification when applicable.
Record actual results and resolve scoped findings before signoff.

## Verification

- Compile and execute `^` through generated Clone instances and their native dependencies;
  verify the returned value's ownership and the original value's continued validity.
- Reject global-backed values at ownership sinks without implicit cloning; accept explicit
  `^` copies at those sinks, including cases reached through aliases and aggregate fields.

Verification is pending. Protocol checkpoint evidence in [Implement protocols](protocols.md)
does not verify Clone or global-transfer behavior.

## Risks / Notes

Global provenance must survive aliases and aggregates. Heap representation alone does not
establish Clone support. Preserve literal promotion, mixed ownership witnesses, and cleanup.
General derivation and [implicit cloning policy](implicit-cloning.md) remain separate tasks.

## Signoff

- Workstream signoff: P7/P8 plan approved; implementation signoff pending.
- Tracked item completion: Pending.
- Commit authorization: Not granted.

## Task Working Memory

Depends on the protocol mechanism and local binding inference in [Implement
protocols](protocols.md).
P7 precedes P8. Next action: after that dependency completes, integrate explicit Clone using
shared instance lookup and existing memory-function generation. Implementation and verification
are pending; preserve the approved scope and stage review gates.
