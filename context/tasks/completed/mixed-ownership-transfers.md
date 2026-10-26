# Mixed-ownership transfers, cleanup, and returns

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG
- **Priority:** HIGH
- **Created:** 2026-09-12
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference:** None

## Execution Checklist

1. [x] **complete** — Investigate current failures, ownership operations, policy constraints,
   and reusable tests; see [baseline evidence](../mixed-ownership-transfers-evidence.md).
2. [x] **complete** — [Implement the complete ownership repair](#plan-approval-gate): local transfers,
   cleanup, calls, fields, returns, and captures.
3. [x] **complete** — [Integrated verification](#verification), ownership documentation,
   independent review, and task signoff.

## Problem

A binding can select either allocated storage or a literal. A Boolean witness indicates
whether local destruction is required. Treating both alternatives as unconditional `Owned`
loses that distinction at a transfer.

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
consuming calls, owning fields, returns, and captures. Every transferred allocation has one final
owner. Untransferred allocations are destroyed once on each applicable path. Borrowed and
static storage are never destroyed as locally owned values.

Preserve existing implicit-clone exceptions and borrowed-source rejection. Deliver one complete
repair across these boundaries, including closure creation, invocation, and destruction.

## Scope

- Scoped bindings and argument temporaries, aliases, named and inline consuming calls,
  owning struct fields, named mixed returns, and branch-dependent escape cleanup.
- Ordinary move captures, borrowing captures, and consuming partial-application payloads
  under their existing ownership and cloning policies.
- Shared ownership operations, focused semantic and native regressions, and
  directly affected ownership documentation.
- Out of scope: replacing Boolean witnesses with a new runtime representation, changing the
  general ownership or cloning policy, global-provenance redesign, binding-construction
  consolidation, a new LLVM optimization pass, and unrelated lambda/codegen repairs.

## Plan (Approval Gate)

Investigation is complete. The explicit `Mixed` ownership representation and inclusion of
captures in the complete repair are approved. The integrated implementation plan is approved.
The sections below describe implementation order within one delivery. Review and signoff
cover the integrated result; temporary capture restrictions and partial releases are excluded.

### Explicit ownership, local transfers, and cleanup

1. Represent conditional ownership as `OwnershipState.Mixed`, carrying a resolved reference to the
   saved Boolean and the possible non-owning origins: literal, global, borrowed, or resource-free.
   `Owned` means unconditional ownership. Keep precise borrowed dependencies and binding
   identities for diagnostics and lifetime checks. Store the witness with `Mixed`; retain
   the existing Boolean and the value's runtime representation.
2. Centralize three operations: derive cleanup from final ownership state; transfer a
   complete ownership obligation when rebinding; and establish ownership at an owning
   boundary. Calls, fields, returns, and captures share the operation while retaining their
   existing clone permissions. Prefer a small explicit contract over parallel wrapper hierarchies.
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
8. Audit every affected `Owned` decision, including return analysis and captures, so the
   explicit state cannot silently fall through to unsafe storage or destruction.
9. Transport scoped mixed results with compiler-internal `YieldOwnership(value, owns)` and
   `BindOwnership(valueParam, witnessParam, initializer, body)` terms. Preserve the value binding
   identity and give the destination a fresh resolved Boolean. Emit separate value and Boolean
   SSA joins, keeping one continuation and the existing runtime value representation. Save both
   operands before scope cleanup; destroy unrelated locals at their original scope boundary.
   Extend traversal, indexing, callable analysis, and tail-position handling for these terms.

Acceptance: the consuming and field cases in the evidence pass on both ownership outcomes;
all four ownership-choice × consumption-choice combinations destroy exactly once. Alias
transfers preserve the same contract. Borrowed-source and later-use rejection remain intact.
Homogeneous owned/static and direct-conditional controls retain their behavior.

### Returns and branch-specific escape cleanup

1. Preserve conditional ownership information through return analysis and named aliases.
   An `Option[Type]` allocation summary cannot prove unconditional ownership of
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

### Capture boundaries

The baseline confirms the same double-free/static-deallocation failures in ordinary move
closures and consuming PAPs. Their environment destruction assumes owned payload storage;
fixing ordinary consuming calls does not establish capture correctness.

1. Establish unconditional ownership at ordinary move-closure creation. Use the saved value
   and resolved witness: transfer the owned outcome, clone the eligible literal outcome using
   the existing exception, and reject borrowed alternatives. Do not broaden global clone policy.
2. Extend capture creation to perform that conditional promotion before storing the payload.
   Preserve capture identities, environment field identities, and source evaluation order.
   Layouts exist before ownership analysis; retain those layouts and keep the witness in the
   creating scope. The environment receives an owned value, without a new witness field or ABI.
3. Transfer the source's complete cleanup obligation at creation. Preserve environment cleanup
   on drop and invocation ownership when a closure transfers captures. Borrow closures retain
   the outer ownership state and borrow dependencies, including for mixed values.
4. Stored consuming PAP payloads retain the explicit-move/no-implicit-clone policy. Reject mixed
   payloads with static or borrowed alternatives at that boundary with an appropriate diagnostic;
   this is the existing language policy, not a temporary restriction. Owned payloads must still
   transfer once on invocation or be destroyed when the uncalled PAP is dropped.
5. Preserve rejection of consuming a field payload through a conditionally owned aggregate.

Acceptance: ordinary mixed owned/literal move captures work on both outcomes, including aliases,
nested scopes, returning closures, repeated borrowing calls, nested move captures, and call-once
transfers. Dropping an uncalled closure destroys its payload exactly once. Borrowing and PAP
controls retain their established policy, with no blanket rejection of mixed move captures.

## Verification

### Investigation evidence

[Baseline evidence and embedded reproducers](../mixed-ownership-transfers-evidence.md) record
2026-10-08 execution at commit `4194c17`, macOS ARM64, Homebrew LLVM/Clang 23.1.1, `-O0`,
with ASan/LSan. They also preserve the scoped-transfer and A* return-leak evidence.
These are failing baselines and controls, not implementation verification.

### Integrated verification — 2026-10-10

Host: Apple M5, macOS 26.6.2 ARM64, Homebrew LLVM/Clang 23.1.1,
GraalVM Community Java 25.0.4.1.

- `sbtn 'scalafmtAll;scalafixAll;test'`: passed with no compiler warnings;
  1,101 library tests and 9 CLI tests passed, with 37 existing ignores and no new ignores.
- `./tests/smoke/run.sh all`: 8/8 passed before publication.
- `sbtn mmlcPublishLocal`: passed; compiler installed locally.
- `make -C benchmark clean` and `make -C benchmark mml`: all 12 MML benchmark builds passed.
  This verifies compilation, not comparative performance.
- `./tests/mem/run.sh all`: 51/51 passed with ASan/LSan at `-O0`.
- `MixedOwnershipCodegenTests`: all three repository fixtures assembled with `llvm-as` and ran
  with ASan/LSan at `-O0` and `-O3`, using four runtime argument-count seeds and all four
  ownership/operation choices within each run. The fixtures are
  [transfers](../../../tests/mem/mixed-ownership-transfers.mml),
  [captures](../../../tests/mem/mixed-ownership-captures.mml), and
  [invocation](../../../tests/mem/mixed-ownership-invocation.mml).
- Optimized IR generated with `mmlc -I` for the transfer and capture fixtures: the `named` consumer
  and `repeated` capture branch directly on the source choice after optimization; only the
  literal branch calls `__clone_String`. The owned branch retains the allocated payload.
  The recursive capture emits a loop. These are IR observations, not timing measurements.
- QA compliance, focused tracking review, and `git diff --check`: passed.
- Independent adversarial review and focused re-reviews: complete, with all confirmed findings
  repaired and no open actionable findings.

Resolved entry-scope retention and semantic/native regressions cover borrowing-call temporaries,
conditional local selection, and unconditional scoped transfers. Literal-returning helper proofs
cover aliases, constructor fields, and repeated nested wrappers while rejecting unknown callable
alternatives and borrowed formals. Direct invocation of mixed call-once/resource-free functions
is covered alongside destruction without invocation and borrowed-source rejection. These cases
are in [MixedOwnershipTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/MixedOwnershipTests.scala),
[MixedInvocationOwnershipTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/MixedInvocationOwnershipTests.scala),
and the native fixtures above.

No MML value, callable, or closure-environment ABI changed. Linux container execution was not
performed; host sanitizer evidence does not establish cross-platform behavior.

### Regression matrix

| Area | Required coverage |
| --- | --- |
| Consumption | Named and inline consumers; named/scoped mixed values; direct conditional control. |
| Aliases | Multiple aliases, nested scopes, shadowing, later-use rejection, and scoped result rebound for borrowing. |
| Branch cleanup | Independent ownership and consumption choices, all four combinations. |
| Returns | Named mixed binding and aliases; owned local returned on one path; nested alternatives. |
| Effects | Predicate and value evaluation exactly once, selected nested predicates only, source-order argument effects. |
| Rejection | Borrowed alternatives at consuming, return, owning-field, and move-capture boundaries; no implicit clone of borrows. |
| Controls | Homogeneous owned/static values, existing allocated references, entirely static returns with incidental allocations. |
| Captures | Creation, invocation, drop, nested captures, returned closures, repeated borrowing, and call-once transfers on both ownership outcomes. |
| PAPs | Mixed stored consuming payload rejection; owned invocation/drop; borrowed payload lifetimes; no new clone exception. |
| Composition | Scoped mixed result through aliases into a returned move closure; capture transferred onward into a consumer, field, or return. |

Exercise capture creation on only one branch against retention on the other, using all four
ownership-choice × capture-choice combinations. Cover lambda and local move-function forms,
multiple captures with independent witnesses, and shadowed names. Check destruction at each
layer when an outer closure returns an inner closure or PAP. A result made unconditionally owned
by return or ordinary capture promotion may enter a consuming PAP without another clone;
the original mixed binding must still be rejected at that same boundary.

Use existing semantic helpers and assertions about resolved identities and ownership behavior.
Check destruction and promotion placement in the transformed tree and LLVM, in addition to
runtime results. Generated witness or helper names alone are not a semantic oracle.

Reuse coverage and helpers from
[PapOwnershipTest](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/PapOwnershipTest.scala),
[OwnershipAnalyzerTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala),
[StringReturnTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/StringReturnTests.scala), and
[CallExitBlockTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/CallExitBlockTests.scala).
Capture and field composition cases can extend
[MoveCaptureConsumptionTest](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/MoveCaptureConsumptionTest.scala)
and [PapStructOwnershipTest](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/PapStructOwnershipTest.scala).
The native tests already use compiler/toolchain APIs and runtime argument selection.
The memory harness has source-order fixtures in
[pap-argument-ownership.mml](../../../tests/mem/pap-argument-ownership.mml) and
[argument-expression-order.mml](../../../tests/mem/argument-expression-order.mml).

Run positive native cases under ASan/LSan at `-O0` and `-O3`; require correct results, no invalid
access/free, and no leaks. Inspect representative optimized IR for witness simplification,
without relying on that optimization for correctness. Add durable regression fixtures alongside
implementation and reconcile any affected ignored-test inventory without claiming unrelated ignores.
Exercise both ownership outcomes and all independent branch choices explicitly. The memory harness
runs fixtures without arguments at `-O0`; fixtures must exercise their own combinations or use the
existing native test runners for runtime argument selection and optimized coverage.

Each compiler handoff follows [coding rules](../../coding-rules.md) and local post-chores: focused
checks, formatting/lint and full tests, smoke verification before publishing, benchmark builds,
all applicable memory checks, QA enforcement, tracking consistency, and independent code review.
Run `sbtn` commands sequentially. ABI changes would additionally require both Linux architectures;
no ABI change is proposed. The [Linux ASan failure](linux-asan-internalization.md) is a known
verification limitation, not a passing result or an automatic waiver for this task.

## Risks / Notes

- The Boolean witness answers whether storage needs destruction. It does not distinguish
  static storage from a borrow or prove that ownership is still available after a move.
- Existing clone exceptions are documented in [the memory model](../../../docs/memory-model.md#clone-operations).
  [Ownership rules](../ownership-rules.md), [global-origin ownership](global-origin-ownership.md),
  and [unified ownership policy](../unify-ownership-model.md) are broader proposals; this task
  does not approve those policy changes.
- The explicit `Mixed` representation and integrated capture scope are approved. Implementation
  approval and final task completion remain distinct; no partial ownership repair is a delivery.
- The M2 executable portability issue is independent and needs the unavailable destination
  machine. It is not a prerequisite for these host ownership regressions.

## Signoff

- Investigation: complete; plan and baseline evidence available.
- Ownership representation: approved — explicit `Mixed` with a resolved Boolean witness,
  non-owning provenance, and preserved borrow dependencies; unconditional `Owned`.
- Capture scope: approved — included in the complete repair, with no interim blanket rejection.
- Implementation-plan approval: granted for the integrated repair.
- Integrated implementation signoff: granted.
- Tracked item completion: complete.
- Implementation commit authorization: granted.
- Push authorization: granted for Git Town synchronization of the target branch.

## Task Working Memory

The integrated implementation carries mixed ownership through local/scoped transfers, calls,
fields, returns, and captures. Rebind destinations preserve existing conditional borrows while
transferring fresh results and direct aliases. Owning boundaries normalize only permitted static
alternatives. Borrow dependencies and consuming PAP restrictions remain enforced.

All applicable repository compiler gates, QA enforcement, focused tracking review, and independent
adversarial review pass; see integrated verification above. Ownership documentation covers the
complete repair. Integrated signoff and local commit authorization are granted. The lambda and
A* ownership dependencies are satisfied; their remaining work stays in their respective tasks.
