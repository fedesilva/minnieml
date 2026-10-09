# Mixed-ownership transfers, cleanup, and returns

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG
- **Priority:** HIGH
- **Created:** 2026-09-12
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference:** None

## Execution Checklist

1. [x] **complete** — Investigate current failures, ownership operations, policy constraints,
   and reusable tests; see [baseline evidence](mixed-ownership-transfers-evidence.md).
2. [ ] **planned** — [Stage 1: explicit ownership, local transfers, and cleanup](#stage-1-explicit-ownership-local-transfers-and-cleanup).
3. [ ] **planned** — [Stage 2: returns and branch-specific escape cleanup](#stage-2-returns-and-branch-specific-escape-cleanup).
4. [ ] **design** — [Stage 3: capture boundaries](#stage-3-capture-boundaries) requires a separate
   bounded design and scope decision before implementation.
5. [ ] **planned** — Complete applicable verification, ownership documentation, and task signoff.

## Problem

A binding can select either allocated storage or a literal. The compiler records it as
`Owned` with an optional Boolean witness indicating whether local destruction is required.
Several ownership operations inspect only `Owned`, losing that distinction at a transfer.

The consequences include double-free and invalid static-storage deallocation when a named
or scoped mixed value reaches a consuming parameter. Named mixed returns can expose freed
storage to the caller. Local aliases lose their conditional ownership information, while
an allocation returned on only one branch leaks on the other branch.

The failures also reach owning struct fields, ordinary move captures, and stored consuming
partial-application payloads. A partial application (PAP) stores supplied arguments until
its remaining arguments are supplied. Its capture policy differs from ordinary calls.

This standalone task owns the repair plan, acceptance criteria, evidence, and signoff.
[Unify lambdas](unify-lambdas.md#bug-preserve-mixed-ownership-through-consuming-transfers)
retains a dependency on the result. The completed
[A* caption repair](astar-animation-correctness-bugs.md#string-return-ownership-repair)
has a separate scope; its conditional return-lifetime follow-up belongs here.

## Outcome

Retain Boolean witnesses while making ownership explicit across cleanup, local rebinding,
consuming calls, owning fields, and returns. Every transferred allocation has one final
owner. Untransferred allocations are destroyed once on each applicable path. Borrowed and
static storage are never destroyed as locally owned values.

Preserve existing implicit-clone exceptions and borrowed-source rejection. Capture-boundary
support must have an explicit scope disposition; passing call and return tests alone cannot
close the recorded capture failures.

## Scope

- Core repair: scoped bindings and argument temporaries, aliases, named and inline consuming
  calls, owning struct fields, named mixed returns, and branch-dependent escape cleanup.
- Core repair: shared ownership operations, focused semantic and native regressions, and
  directly affected ownership documentation.
- Capture extension: investigate and plan ordinary move captures and consuming PAP payloads;
  implementation requires the separate stage 3 approval described below.
- Out of scope: replacing Boolean witnesses with a new runtime representation, changing the
  general ownership or cloning policy, global-provenance redesign, binding-construction
  consolidation, a new LLVM optimization pass, and unrelated lambda/codegen repairs.

## Plan (Approval Gate)

Investigation and plan preparation are approved and complete. Implementation approval is
pending for stages 1 and 2. Stage 3 requires a separate bounded design and scope approval.
Stop for review and signoff after each implemented stage before advancing.

### Stage 1: explicit ownership, local transfers, and cleanup

1. Replace the ambiguous `Owned` plus optional witness combination with an explicit
   conditional ownership state. The proposed state carries a resolved reference to the
   saved Boolean and the possible non-owning origins: literal, global, or borrowed.
   `Owned` means unconditional ownership. Keep precise borrowed dependencies and binding
   identities for diagnostics and lifetime checks.
2. Centralize three operations: derive cleanup from final ownership state; transfer a
   complete ownership obligation when rebinding; and establish ownership at a consuming
   boundary. Prefer a small explicit contract over parallel wrapper hierarchies.
3. Preserve the witness and provenance through aliases and nested local scopes. Borrowed
   alternatives must stay distinguishable from static alternatives; a false witness alone
   does not authorize cloning or transfer. Preserve use-after-move diagnostics by identity.
4. For an eligible mixed owned/static consuming argument, use the saved value and witness:
   transfer the owned outcome; clone only the permitted static outcome. Never clone an
   allocated outcome or silently clone a borrowed alternative. Do not re-evaluate a
   predicate, initializer, argument, or callee.
5. Push consuming requirements into scoped result expressions while the witness is in scope.
   An outer local receiving an inner mixed result must retain valid witness scope and value
   lifetime. A result annotation alone cannot refer to a witness whose binding has ended.
6. Derive all local and complementary-branch cleanup from the final ownership obligation.
   Remove cleanup after a successful transfer. If one branch consumes and the other retains
   the value, preserve the original witness on the retaining branch's cleanup.
7. Cover named and inline consumers, scoped results, aliases, ordinary borrowing, and owning
   struct fields through the shared operations. Constructor fields already use consuming
   parameters; they should not require a parallel implementation.
8. Audit every affected `Owned` decision, including captures, so the explicit state cannot
   silently fall through to unsafe storage or destruction. Resolve capture support or
   diagnostic behavior at the stage 3 design gate before claiming the shared contract complete.

Acceptance: the consuming and field cases in the evidence pass on both ownership outcomes;
all four ownership-choice × consumption-choice combinations destroy exactly once. Alias
transfers preserve the same contract. Borrowed-source and later-use rejection remain intact.
Homogeneous owned/static and direct-conditional controls retain their behavior.

### Stage 2: returns and branch-specific escape cleanup

1. Preserve conditional ownership information through return analysis and named aliases.
   The current `Option[Type]` allocation summary cannot prove unconditional ownership of
   a named mixed result. Separate the callable's result contract from the local evidence
   required to establish that contract.
2. Propagate an owning-result requirement through scopes and conditional branches. Promote
   static outcomes only where the existing function contract promises ownership to the
   caller; transfer allocated outcomes without cloning. Entirely static returns keep their
   existing behavior, including when local temporaries allocate incidentally.
3. Record returned ownership transfers on the paths where they occur. Destroy an allocated
   local on a path that returns something else, using its original witness where applicable.
   A union of names returned by either branch must not suppress cleanup on both branches.
4. Preserve borrowed-return and field-alias escape diagnostics, source evaluation order,
   and witness binding scope. Keep the completed A* direct/scoped caption regressions passing.

Acceptance: callers read and destroy named mixed returns safely on both outcomes, including
aliases and nested scopes. The allocated-before-conditional reproducer has no leak on the
non-returning path. No unnecessary clone or callee destruction occurs on the owned return path.

### Stage 3: capture boundaries

The baseline confirms the same double-free/static-deallocation failures in ordinary move
closures and consuming PAPs. Their environment destruction assumes owned payload storage;
fixing ordinary consuming calls does not establish capture correctness.

- Prepare a bounded capture-site design before implementation. Ordinary move closures need
  unconditional ownership established at creation using the existing literal-promotion
  exception. This may require capture-site rewriting or emission changes because environment
  layouts exist before ownership analysis. Preserve layout identities and destruction contracts.
- Stored consuming PAP payloads retain the explicit-move/no-implicit-clone policy. A conditional
  static alternative must not gain a new clone exception merely because it is wrapped in a
  mixed binding. Define the appropriate diagnostic under that existing policy.
- Check invocation and dropping an uncalled closure/PAP, owned payload transfer, call-once
  behavior, and borrowed capture rejection. Preserve rejection of consuming a field payload
  through a conditionally owned aggregate.
- If capture support requires a larger follow-up, obtain an explicit scope decision and link
  that task with its acceptance obligations before completing this task. Unsafe acceptance
  must not be hidden by treating the conditional state as unconditional ownership.

## Verification

### Investigation evidence

[Baseline evidence and embedded reproducers](mixed-ownership-transfers-evidence.md) record
2026-10-08 execution at commit `4194c17`, macOS ARM64, Homebrew LLVM/Clang 23.1.1, `-O0`,
with ASan/LSan. They also preserve the scoped-transfer and A* return-leak evidence.
These are failing baselines and controls, not implementation verification.

### Regression matrix

| Area | Required coverage |
| --- | --- |
| Consumption | Named and inline consumers; named/scoped mixed values; direct conditional control. |
| Aliases | Multiple aliases, nested scopes, shadowing, later-use rejection, and scoped result rebound for borrowing. |
| Branch cleanup | Independent ownership and consumption choices, all four combinations. |
| Returns | Named mixed binding and aliases; owned local returned on one path; nested alternatives. |
| Effects | Predicate and value evaluation exactly once, selected nested predicates only, source-order argument effects. |
| Rejection | Borrowed alternatives at consuming, return, and owning-field boundaries; no implicit clone of borrows. |
| Controls | Homogeneous owned/static values, existing allocated references, entirely static returns with incidental allocations. |
| Captures | Stage 3 approved behavior for invocation/drop, call-once transfers, static and borrowed alternatives. |

Use existing semantic helpers and assertions about resolved identities and ownership behavior.
Check destruction and promotion placement in the transformed tree and LLVM, in addition to
runtime results. Generated witness or helper names alone are not a semantic oracle.

Reuse coverage and helpers from
[PapOwnershipTest](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/PapOwnershipTest.scala),
[OwnershipAnalyzerTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala),
[StringReturnTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/StringReturnTests.scala), and
[CallExitBlockTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/CallExitBlockTests.scala).
The native tests already use compiler/toolchain APIs and runtime argument selection.
The memory harness has source-order fixtures in
[pap-argument-ownership.mml](../../tests/mem/pap-argument-ownership.mml) and
[argument-expression-order.mml](../../tests/mem/argument-expression-order.mml).

Run positive native cases under ASan/LSan at `-O0` and `-O3`; require correct results, no invalid
access/free, and no leaks. Inspect representative optimized IR for witness simplification,
without relying on that optimization for correctness. Add durable regression fixtures alongside
implementation and reconcile any affected ignored-test inventory without claiming unrelated ignores.

Each compiler handoff follows [coding rules](../coding-rules.md) and local post-chores: focused
checks, formatting/lint and full tests, smoke verification before publishing, benchmark builds,
all applicable memory checks, QA enforcement, tracking consistency, and independent code review.
Run `sbtn` commands sequentially. ABI changes would additionally require both Linux architectures;
no ABI change is proposed. The [Linux ASan failure](linux-asan-internalization.md) is a known
verification limitation, not a passing result or an automatic waiver for this task.

## Risks / Notes

- The Boolean witness answers whether storage needs destruction. It does not distinguish
  static storage from a borrow or prove that ownership is still available after a move.
- Existing clone exceptions are documented in [the memory model](../../docs/memory-model.md#clone-operations).
  [Ownership rules](ownership-rules.md), [global-origin ownership](global-origin-ownership.md),
  and [unified ownership policy](unify-ownership-model.md) are broader proposals; this task
  does not approve those policy changes.
- Keep call/return repair, capture extension approval, and final task completion distinct.
  The exact enum/API and capture integration remain subject to implementation-plan approval.
- The M2 executable portability issue is independent and needs the unavailable destination
  machine. It is not a prerequisite for these host ownership regressions.

## Signoff

- Investigation: complete; plan and baseline evidence available.
- Implementation approval: pending for stages 1 and 2; separate bounded approval pending for stage 3.
- Stage implementation signoffs: pending.
- Tracked item completion: pending.
- Implementation commit authorization: not granted.
- Push authorization: not granted.

## Task Working Memory

The failing baseline establishes consuming, return, branch-cleanup, field, and capture defects.
Direct conditional consumption passes; mixed aliases are rejected as borrowed, while actual
borrowed mixtures remain correctly rejected. The proposed repair keeps Boolean witnesses,
explicit conditional ownership/provenance, and shared transfer/cleanup operations.

Next: approve the stage 1 implementation design, including how affected capture decisions remain
safe while the separately gated capture integration is pending. Then implement and verify stage 1,
stop for review/signoff, and advance to returns only after that checkpoint. This task owns progress
and signoff; A* and Unify lambdas retain references to its result rather than duplicate plans.
