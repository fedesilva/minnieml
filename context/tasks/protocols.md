# Implement protocols

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** feature
- **Priority:** unassigned
- **Created:** 2026-10-25
- **Target Branch:** dev/protocols
- **External Reference (optional):** None.

## Execution Checklist

1. [x] **complete** — Define the first-slice scope, resolution model, and Num-to-Clone delivery order.
2. [ ] **planned** — Prepare a bounded implementation plan for approval.
3. [ ] **planned** — Deliver and verify protocols through Num and unified numeric operators.
4. [ ] **planned** — Inject Clone and adapt memory-function generation to produce its instances.
5. [ ] **planned** — Reject global ownership transfers that lack explicit cloning through `^`.

## Problem

MML needs ad hoc polymorphism for type-based function and operator selection. Enum
equality requires concrete instances, and memory evolution depends on compiler-recognized
`Unique` and `Clone` contracts.

## Outcome

A functional protocol system unifies numeric operator spellings for `Int` and `Float`
and introduces the `Clone` protocol through the unary `^` operator. Protocol declarations,
concrete instances, contract checking, member resolution, and executable lowering support
both capabilities through the same mechanism.

## Scope

The [Protocols draft](../../docs/brainstorming/language/protocols.md) describes the full
design. This task implements only the smallest end-to-end slice needed for the outcomes
below. Completing this slice does not mean implementing the entire specification.

### First implementation slice

Deliver the protocol system through `Num` first. Then integrate `Clone` and require explicit
cloning of globals at ownership sinks, in the order shown in the execution checklist.

- Protocol declarations with one type parameter, required function and operator members,
  and concrete instances. Every member implementation is explicit or compiler-generated.
- Instance uniqueness, contract checking, and type-based candidate and instance selection
  using argument and expected result types, with executable lowering of selected members.
- Shared operator precedence and associativity checked before expression rewriting.
- A provisional `Num` protocol with concrete `Int` and `Float` instances supplies unified
  numeric operators. A fuller numeric hierarchy belongs to a later Spire port:
  - Binary arithmetic: `+`, `-`, `*`, `/` replace the corresponding dotted Float spellings.
  - Equality and ordering: `==`, `!=`, `<`, `>`, `<=`, `>=` replace their dotted Float spellings.
  - Unary negation: `-` replaces `-.` for Float.
  - Preserve existing numeric behavior and grouping; migrate affected library declarations,
    samples, and tests. Addition is only one case of this migration.
- After Num verifies the protocol system, inject `Clone`, declaring unary `^`. Adapt the
  existing memory-functions phase to generate Clone instances for eligible concrete types,
  reusing its cloning machinery. This does not require a general derivation facility.
  `^value` borrows its input and returns a separate owned value, leaving the input valid.
  Follow the contract in [Memory evolution](../../docs/brainstorming/mem/mem-evolution.md#layer-2-clone-protocol).
  Include the native clone implementations needed by generated instances.
- Remove implicit cloning of globals at ownership sinks. Reject transfers of global-backed
  values into owned storage; the programmer must explicitly use `^` to produce an owned copy.
  This applies to transfers requiring ownership, not ordinary scalar value copies.

The operator inventory above follows the
[current prelude](../../modules/mmlc-lib/src/main/resources/prelude.mml). Operations without
an integer/float spelling split, such as integer remainder and shifts, do not require new
Float counterparts. Their protocol placement belongs in the bounded implementation plan.

### Beyond the first slice

- General [protocol derivation](protocol-derivation.md), including compiler-derived enum
  equality, is separate follow-up work. The targeted adaptation of Clone generation above
  belongs to this task.
- General parametric functions and data types, parameterized instances, higher-kinded
  parameters, explicit imports, and `FromInt` are outside this slice.
- Full `Unique` integration and the broader memory evolution design are outside this slice.
- Default member implementations, prerequisite protocols, and multiple protocol type
  parameters are deferred. Reject these unsupported forms clearly in the first slice.

## Design

The following semantics describe the full design; they do not expand the first slice.

See the [Protocols draft](../../docs/brainstorming/language/protocols.md) and its
[syntax section](../../docs/brainstorming/language/protocols.md#syntax), which links
to the standard-library examples. Function members use named, typed parameters;
instance headers omit `for`. Function signatures introduce their own type variables,
with constraints in a `where` clause after the return type. Protocol and instance
headers retain parameter constraints using `:` and `&`.

[Instance identity and member resolution](../../docs/brainstorming/language/protocols.md#instance-identity-and-member-resolution)
requires a single instance per protocol and concrete type combination, rejecting duplicates.
Protocol members join ordinary function-name lookup as candidates without requiring the
protocol name. The type checker uses argument and expected result types to select the
applicable member and instance. Insufficient information, missing candidates or instances,
and unresolved ambiguity are errors. Functions and operators share this resolution model.

[Protocol requirements and defaults](../../docs/brainstorming/language/protocols.md#protocol-requirements-and-default-implementations)
use separate prerequisite instances, with transitive availability of their operations.
Dependent instances cannot replace prerequisite implementations. Protocol members may have
default bodies, checked against declared guarantees; instances may override their own
protocol's defaults and must implement members without defaults.

The protocol supplies member types and metadata, including operator precedence and
associativity. Instances may omit them or repeat them. Check every explicit restatement
against the instantiated contract and reject any divergence.
[Operator syntax](../../docs/brainstorming/language/protocols.md#operator-declaration-syntax)
is the ordinary declaration shape: name, named parameters, return type, precedence and
associativity, then an optional default body. Required members omit the body; defaults
use `= body` and ordinary terminators. There is no separate protocol operator signature form.

Instance methods and protocol default bodies follow normal function ownership rules and
must honor the declared parameter and return ownership contract. There are no separate
protocol ownership rules; see [Ownership](../../docs/brainstorming/language/protocols.md#ownership).

## Plan (Approval Gate)

The first-slice scope and resolution model are settled. Prepare a bounded implementation
plan for Num first, then Clone, covering:

- Num declarations and Int/Float instances for the listed numeric operations. The later
  Spire hierarchy is outside this task.
- Adaptation of memory-function generation and native clone implementations to Clone
  instances, preserving eligibility checks and the borrow-input/owned-result contract.
- Global-origin tracking through aliases and aggregate fields at ownership sinks, rejection
  diagnostics, and migration of affected programs to explicit `^` calls.
- Diagnostics for missing instances, insufficient type information, and ambiguous candidates.
- Function values and partial applications use candidate resolution as described in the
  implementation reference below.
- Migration of the affected operator uses and treatment of obsolete dotted spellings.

Implementation planning must cover expected result types reaching application candidate
selection, partial applications and function values, and concrete instance lowering.
The current `checkNormalApplication` path does not receive an expected result type.
Select the concrete lowering strategy in the implementation plan. Default-body checking
is deferred with default members; the full draft retains its abstract-checking requirements.

Approval: Delivery order, Num validation, Clone generation, explicit global cloning,
instance metadata rules, and the single-parameter required-member boundary defined;
implementation plan pending approval.

## Implementation reference: candidate resolution

Function values and partial applications use the same candidate-resolution mechanism as
full calls. For concrete instances, selecting an instance member produces an ordinary typed
function; no separate protocol function-value or partial-application mechanism is needed.

Before function-value or partial-application lowering, every protocol reference must identify
one concrete instance member and have a concrete function type.

- Preserve candidates through name lookup and expression rewriting until type checking has
  enough information. Check applicability using supplied argument types and expected types.
- Do not reject an inner application as ambiguous before considering later arguments in the
  enclosing application and its expected result type. A partial application can use the
  expected remaining function type; a function value can use its expected function type.
- Reject references that remain insufficiently constrained or ambiguous. Do not carry an
  unresolved overload into a binding and select different implementations at later uses;
  that would introduce polymorphism beyond this concrete-instance scope.
- Once resolved, use the ordinary function-value and partial-application lowering paths.

Implementation anchors:

- [RefResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/RefResolver.scala)
  retains multiple matches in `candidateIds`.
- [TypeChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala):
  `checkRef` reads a resolved reference's type without an expected type;
  `checkNormalApplication` checks the callee before the argument and does not receive the
  application's expected result type. These paths need candidate-aware checking and
  expected-type propagation rather than premature selection or ambiguity errors.
- [PartialApplicationElaborator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/PartialApplicationElaborator.scala):
  `rewriteApplication` uses the callee's concrete `TypeFn` to elaborate partial applications.
  Resolution must supply that concrete callable before this phase.

Verification should cover selection by an expected function type, selection from supplied
arguments and an expected remaining function type, disambiguation by later arguments in an
application, and rejection of unresolved function values. These are resolution tests, not
separate language features.

## Verification

Acceptance criteria for the first slice:

- Compile and execute integer and floating-point arithmetic, equality, ordering, and unary
  negation using the unified spellings listed above, preserving their numeric behavior
  and operator grouping.
- Compile and execute `^` through generated Clone instances and their native dependencies;
  verify the returned value's ownership and the original value's continued validity.
- Reject global-backed values at ownership sinks without implicit cloning; accept explicit
  `^` copies at those sinks, including cases reached through aliases and aggregate fields.
- Accept omitted or matching instance types and metadata; reject divergent restatements.
- Exercise user-declared protocols and concrete instances through named members as well as
  operators, establishing a usable protocol mechanism beyond built-in numeric operations.
- Reject duplicate instances, incompatible member implementations, missing instances,
  unresolved ambiguity, and conflicting operator grouping attributes.

Design evidence: the scope, instance metadata rules, candidate-resolution reference, and
acceptance criteria above define the agreed first slice. Documentation consistency, local
link targets, and `git diff --check` have been checked.

Compiler implementation and runtime verification remain pending.

## Risks / Notes

[Enums](enums.md) depends on protocol instance resolution for derived equality, but derivation
is outside this slice and tracked in [Protocol derivation](protocol-derivation.md).
Clone generation adapts the existing memory-functions phase. It does not approve a general
derivation facility or the full memory evolution design. The global-cloning change must
cover global provenance through aliases and aggregates; broader literal-cloning policy
is not settled by this scope.
The draft defines semantics beyond the selected deliverable and is not an approved
implementation plan.

## Signoff

- Workstream signoff: First-slice scope and design refinement approved; implementation pending.
- Tracked item completion: Pending.
- Commit authorization: Granted for the protocol documentation and tracking refinements.

## Task Working Memory

Deliver protocols first, validated by provisional Num instances for Int and Float. Then
inject Clone, adapt the memory-functions phase to generate its instances, and replace
implicit cloning of globals at ownership sinks with rejection unless `^` is explicit.
General derivation remains separate. Protocols supply all member types and metadata;
instances may omit or repeat them, but divergent restatements must be rejected.
Function values and partial applications use candidate resolution to concrete members before
lowering, as detailed in the implementation reference.
The first slice has one protocol type parameter and required members only. Defaults,
prerequisites, and multiple protocol parameters are deferred.
Next action: prepare a bounded implementation plan, with a review checkpoint after Num
and before Clone integration.
