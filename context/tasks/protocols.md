# Implement protocols

## Metadata

- **Owner:** The Author
- **Status:** in_progress
- **Kind:** feature
- **Priority:** unassigned
- **Created:** 2026-10-25
- **Target Branch:** dev/protocols
- **External Reference (optional):** None.

## Execution Checklist

1. [x] **complete** — Define the first-slice scope, resolution model, and Num-to-Clone delivery order.
2. [x] **complete** — Approve the [bounded implementation plan](#plan-approval-gate).
3. [x] **complete** — [P0: Write the AST nodes, then stop for Author review](#p0-ast-nodes-and-author-review).
4. [x] **complete** — [P0b: Build shared lookup machinery, then stop for Author review](#p0b-shared-lookups-and-author-review).
5. [ ] **planned** — [P1: Parse protocol signatures and concrete instances](#p1-declarations-and-syntax).
6. [ ] **planned** — [P2: Validate contracts and register concrete member functions](#p2-contracts-and-concrete-members).
7. [ ] **planned** — [P3: Enforce precedence/associativity coherence before grouping; preserve candidates](#p3-lookup-and-operator-grouping).
8. [ ] **planned** — [P4: Select concrete members using arguments and expected types](#p4-type-directed-selection-and-lowering).
9. [ ] **planned** — [P5: Deliver Num and migrate numeric operators](#p5-num-and-numeric-migration).
10. [ ] **planned** — [P6: Verify Num and obtain review and signoff](#p6-num-review-checkpoint).
11. [ ] **planned** — [P7: Integrate explicit Clone and generated instances](#p7-explicit-clone).
12. [ ] **planned** — [P8: Require explicit cloning for global ownership transfers](#p8-global-ownership-transfers).
13. [ ] **planned** — [P9: Complete documentation, verification, and final review](#p9-final-verification-and-documentation).

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
  and concrete instances. Every required member has a source-defined or compiler-generated
  instance binding.
- Instance uniqueness, contract checking, and type-based candidate and instance selection
  using argument and expected result types, resolving calls to lowered instance member bindings.
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

The operator inventory above follows `injectStandardOperators` in
[semantic/package.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala),
which supplies the compiler's built-in operators. `prelude.mml` is an unused placeholder,
not the source of those declarations. Integer remainder and shifts have no Float counterparts;
the plan keeps them as free operators.

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

The rules below govern the first slice. Deferred language features are identified separately.
Protocols declare constraints on type parameters; they are not ordinary value types. Future
`where` clauses will express those constraints on a type parameter. That syntax is deferred.

### Critical invariant: operator precedence and associativity coherence

**Every visible declaration of the same operator symbol and arity must agree on precedence
and associativity, across free operators and all protocols. Reject any conflict before
expression rewriting. Operand types and instance availability cannot resolve a conflict.**

Grouping must be unambiguous before type checking selects an instance member. The expression
rewriter uses the shared attributes and preserves the candidates for later type-based selection;
it must never choose one declaration's attributes by lookup order.

The rule applies separately to unary and binary forms of a symbol. Instance members
inherit their protocol's attributes; any explicit restatement must match exactly.

**P3 is a mandatory gate:** demonstrate conflict rejection and declaration-order-independent
grouping before proceeding to type-directed selection in P4.

### Member contracts and instance selection

Function members use named, typed parameters; instance headers omit `for`. The first slice
allows the protocol's single type parameter in member signatures and concrete types in
instance headers. It does not introduce member-local type variables or constraints.
See the [protocol syntax](../../docs/brainstorming/language/protocols.md#syntax) for examples;
the standard-library sketch includes deferred features beyond this slice.

[Instance identity and member resolution](../../docs/brainstorming/language/protocols.md#instance-identity-and-member-resolution)
requires a single instance per protocol and concrete type combination, rejecting duplicates.
Protocol members join ordinary function-name lookup as candidates without requiring the
protocol name. The type checker uses argument and expected result types to select the
applicable member and instance. Insufficient information, missing candidates or instances,
and unresolved ambiguity are errors. Functions and operators share this resolution model.

The protocol supplies member types and metadata, including operator precedence and
associativity. Instances may omit them or repeat them. Check every explicit restatement
against the instantiated contract and reject any divergence.
[Operator syntax](../../docs/brainstorming/language/protocols.md#operator-declaration-syntax)
uses the ordinary declaration shape: name, named parameters, return type, precedence and
associativity. A required member omits the body. Instance members supply Lambda bodies.

Instance members follow normal function ownership rules and must honor the declared parameter
and return ownership contract. See [Ownership](../../docs/brainstorming/language/protocols.md#ownership).

### Deferred features

The AST can represent an optional default Lambda body, but the first slice rejects default
members in source. Default checking, prerequisite protocols, member-local type variables,
and constraints remain outside this plan. Their broader semantics are described in the
[Protocols draft](../../docs/brainstorming/language/protocols.md).

## Plan (Approval Gate)

The stages below are approved, subject to their explicit review checkpoints. P0 defines the AST
and stops for Author review.
P0b adds shared lookups and a second Author review checkpoint before compiler-phase integration.
P1–P6 deliver the protocol mechanism through Num. Stop after P6 for review and signoff before
P7–P9 integrate Clone and global transfers.
Each stage has a concrete exit condition; record its evidence in Task Working Memory and
update the execution checklist before advancing. Stage completion does not authorize a commit.

### Implementation choices

- A protocol node contains member signatures, each with an optional Lambda body for a default.
  An instance node contains a protocol reference, a concrete type, and member `Bnd`s whose
  values contain Lambdas. Required protocol members have no body.
- Instance members already have the ordinary binding shape. Lowering supplies their concrete
  signatures from the protocol contract and places their bindings among the module's executable
  bindings. `BindingMeta` retains function/operator origin, arity, and source member name;
  operator precedence and associativity come from the protocol contract.
- Shared lookup machinery associates protocol, concrete type, and member identities with each
  instance member's binding ID. That association need not be encoded in the ID's spelling.
  Retrieve current bindings through the resolvables index rather than duplicating their bodies.
  Calls select those ordinary bindings during type checking; no runtime dictionary or separate
  protocol calling convention is introduced.
- The typer checks instance bodies as ordinary Lambdas against their concrete contracts.
  Ownership analysis and final code generation process those same Lambdas through their
  existing machinery; neither introduces an instance-specific callable pipeline.
- Register concrete signatures before checking bodies, so recursive member calls and forward
  references use the same dependency and recursion rules as ordinary functions.
- Use canonical resolved type identity for instance keys. Aliases of the same type cannot
  introduce duplicate instances; distinct nominal types must not collapse to an LLVM layout.
- Retain protocol-member identity during lookup even when an instance is absent. This allows
  a missing-instance diagnostic rather than incorrectly reporting an undefined member name.
- Keep `%`, `<<`, `>>`, unary integer `+`, Boolean operators, and string concatenation as
  ordinary free operators. Num owns only the Int/Float operations listed in Scope.
- Remove the injected dotted Float spellings and migrate their uses. Do not reserve those
  spellings against user-defined operators or introduce compatibility aliases.
- Preserve literal promotion and mixed ownership witnesses. The broader policy belongs to
  [Revisit implicit cloning](implicit-cloning.md); P8 changes only global-backed transfers.

### P0: AST nodes and Author review

- Write a protocol AST node with its identity, type parameter, and member declarations.
- Give each protocol member a signature containing its name, parameters, result type, and
  operator metadata. Required members have no body; represent a default member with an optional
  Lambda body. This representation does not enable default-member parsing or checking.
- Write an instance AST node containing a protocol reference, a concrete type node, and
  member bindings whose values contain Lambdas. Allow the protocol reference to retain its
  source name and acquire a resolved protocol ID during resolution.
- Model protocols, protocol members, and instances as `Decl`. Keep `Typeable` on declarations
  that use computed/ascribed types and `Resolvable` on named declarations. Adapt existing
  declaration consumers to those capabilities and verify existing behavior.
- Keep this step limited to the AST definitions, declaration-trait refactor, and required adjustments.
  Do not add parsing, resolution, lowering, or library injection yet.

Exit: compile the definitions, present the nodes and their fields to the Author, and **stop**.
Proceed to P0b only after explicit review and signoff of the AST shape.

### P0b: Shared lookups and Author review

- Provide one shared lookup API for protocols by resolved ID, signatures by protocol
  and member identity, instances by protocol ID and concrete type, and instance member bindings
  by instance and member identity.
- Provide visible candidate lookup by source name and operator arity, with explicit scope input
  for lexical shadowing. Return candidates without choosing an instance or grouping policy.
- Back lookups with stable IDs and the existing resolvables index. Keep registry state immutable
  and retrieve current nodes through the index rather than retaining stale copies of bindings.
- Make missing and duplicate entries explicit; never silently choose the first matching entry.
  Use canonical concrete type identity for instance keys, preserving nominal distinctions.
- Test the API with constructed AST nodes: successful and missing lookups, duplicate keys,
  alias identity, scoped candidates, and retrieval after an indexed binding is updated.
- Require resolution, contract checking, operator grouping, type checking, and Clone generation
  to use this machinery. Integrate those consumers in their later stages, not during this step.

Exit: present the API, its data ownership, and focused test results to the Author, then **stop**.
Proceed to P1 only after explicit review and signoff of the shared lookup machinery.

### P1: Declarations and syntax

- Use the reviewed AST nodes and retain source locations. Reuse ordinary function/operator
  parameter and type syntax, including consuming parameters.
- Parse one protocol type parameter, bodyless required members, and explicit instance bodies.
  Preserve omitted instance types and operator attributes until the contract supplies them.
  Do not apply free-operator defaults to omitted instance metadata.
- Reject defaults, prerequisite constraints, multiple protocol parameters, parameterized
  instances, and member-local polymorphism with focused unsupported-feature diagnostics.
- Extend declaration traversal, ID assignment, indexing, diagnostic rendering, AST printers,
  and existing editor consumers as required to handle the new nodes without losing source data.

Exit: parser tests cover named members, unary/binary operators, omitted annotations,
terminators, malformed declarations with recovery, and unsupported forms. Existing ordinary
function/operator syntax remains accepted.

### P2: Contracts and concrete members

- Resolve protocol parameter references in their own scope and concrete instance types through
  the existing type resolver. Populate the shared registry and use the reviewed P0b lookup API.
- Check duplicate protocols and instances; distinguish repeated names within a protocol from
  legal shared member names across protocols. Generated instances obey the same uniqueness rule.
- Match instance members to their contracts by member identity and callable shape. Reject
  missing, extra, duplicate, or mismatched members. Substitute the concrete type into every
  signature, including nested function types; check all explicit type and metadata restatements.
- Preserve ordinary parameter ownership rules: `~` is part of the callable contract, and a
  conflicting instance member cannot silently change it. Supply concrete parameter/result
  signatures for body checking in P4.
- Lower the instance's existing member bindings into the module's executable bindings before
  reference resolution. Preserve assigned binding and parameter IDs; use the existing ID supply
  for newly generated nodes. Retain source origins and instance/member associations through
  shared lookups, and refresh indexes at phase boundaries.
- Keep one executable copy of each member body. After lowering, instance metadata refers to
  binding IDs rather than retaining another traversable copy. Protocol signatures are not
  executable bindings.

Exit: semantic tests establish instance uniqueness through aliases, nominal type distinction,
contract inheritance and mismatch diagnostics, stable references, and concrete signatures on
lowered bindings. Instance body checking and execution are verified in P4.

### P3: Lookup and operator grouping

- Integrate the shared candidate lookup API into ordinary name resolution, preserving lexical
  parameter/local shadowing. Do not expose internal instance binding names as an additional
  source-level overload set.
- Validate shared precedence and associativity across visible operators of the same symbol
  and arity before expression rewriting, including declarations with no applicable instance.
- Replace first-match operator selection with grouping metadata plus the retained candidate
  set. Grouping selects unary/binary form and prefix/postfix position, not a concrete instance.
- Audit arity-dependent application rewriting so an overloaded function is not selected or
  rejected before argument and expected-type information becomes available.

Exit: tests show correct precedence and unary/binary grouping, candidate retention independent
of declaration order, lexical shadowing, and rejection of incompatible grouping attributes.

### P4: Type-directed selection and lowering

- Use shared lookups to retrieve contracts, instances, and their current member bindings.
  Check instance Lambda bodies against the concrete signatures supplied in P2.
- Pass expected types into reference and application checking. Consider the full enclosing
  application chain before rejecting an inner partial application as ambiguous.
- For each candidate, check supplied arguments and the expected result or remaining function
  type against its concrete signature. Use candidate-local checking state so rejected trials
  do not leak rewritten trees, inferred parameter types, or diagnostics into the winner.
- Use the same selection mechanism for full calls, function values, and partial applications.
  Expected callable types must reach lambda arguments and nested overloaded expressions.
  Respect invocation boundaries when a call returns another callable.
- Select exactly one applicable member and write its concrete binding ID and function type
  back to the expression. Never resolve by declaration order or by preferring a built-in.
- Diagnose missing instances, incompatible arguments, insufficient information, and ambiguity
  distinctly. Preserve independent source errors without emitting every rejected trial's errors.
- Reject unresolved overloads at binding boundaries. Use ordinary partial-application,
  ownership, and code-generation paths after selection; validate that no executable protocol
  reference remains unresolved before those phases consume it.

Exit: user-defined named and operator members compile and execute. Tests cover expected-result
selection of nullary calls, expected function types, partial applications, later-argument
selection, nested calls, recursive concrete members, and rejection of unresolved function values.
Inspect resolved identities and types; include runtime checks for argument evaluation once and
in source order. No polymorphic binding or runtime dispatch is introduced.

### P5: Num and numeric migration

- Inject the provisional Num contract and concrete Int/Float instances through the same
  registration and checking mechanism as source declarations. Reuse existing LLVM templates.
- Migrate binary arithmetic, equality, ordering, and unary negation as enumerated in Scope.
  Preserve precedence, associativity, integer division, floating comparison predicates, and
  the current unary Float implementation; no numeric conversion policy is added.
- Remove overlapping free numeric declarations and injected dotted Float declarations.
  Update programmatic library bodies, current samples, benchmarks,
  smoke/memory fixtures, and tests that depend on the split spellings. Preserve historical
  records and deliberately user-defined dotted operators.
- Reconcile current numeric documentation with actual injection. In particular, the compiler
  design examples show unary Float `+.` and `fneg`, whereas injection supplies only `-.`
  using subtraction from zero. The implementation is the compatibility baseline.

Exit: both numeric instances execute all listed operations, with grouping and Float edge
cases covered. Mixed Int/Float operands remain rejected without an applicable instance.
Integer-only free operators still work. Removed built-in spellings fail as undefined unless
explicitly declared by the program. Current shipped programs use the supported spellings.

### P6: Num review checkpoint

- Run the applicable compiler verification and review gates described under P9 for P0 through
  P5, including P0b. Record actual results, outstanding failures, and any explicit waivers.
- Present the code tour and working Num examples, including a user-defined protocol, a
  function value, a partial application, and representative rejection diagnostics.
- Obtain review and signoff for the Num deliverable before starting Clone integration.

Exit: Num is verified and signed off; unresolved required checks or findings keep this stage open.

### P7: Explicit Clone

- Inject the compiler-recognized Clone contract with borrowing unary `^`; propose precedence
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

### P9: Final verification and documentation

- Update the language reference, compiler design, memory-model documentation, and current
  examples to describe implemented protocol syntax, resolution, Num, Clone, and global transfers.
  Keep broader derivation and implicit-cloning proposals identified as separate work.
- Follow [coding rules](../coding-rules.md) and [development tools](../dev-tools.md): formatting
  and linting, smoke collection before publishing or expensive checks, full `testFull`, local
  publish, benchmarks, and the memory harness for ownership/lambda changes. Run sbtn sequentially.
- If ABI/C interoperability changes are required, run affected fixtures in both required Linux
  containers and offer optional cloud verification under the repository rules.
- Use local post-chores for compiler handoff, including QA enforcement and independent review.
  Record durable verification evidence in this task or versioned supporting files, distinguishing
  semantic tests, IR checks, execution, sanitizer results, and benchmark measurements.
- Resolve scoped findings, reconcile the execution checklist and tracker, and present final
  review evidence. Task closure and a local commit require the separate lifecycle authorization.

Exit: all scoped acceptance criteria and applicable checks pass, documentation matches behavior,
and final review is ready for signoff. No unverified stage is marked complete.

Approval: first-slice scope, delivery order, and P0–P9 plan (including P0b) approved.
P0, P0b, and P6 retain their explicit review and signoff gates.

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
- Reject duplicate instances, incompatible instance members, missing instances,
  unresolved ambiguity, and conflicting operator grouping attributes.

Design evidence: the scope, instance metadata rules, candidate-resolution reference, and
acceptance criteria above define the agreed first slice. Documentation consistency, local
link targets, and `git diff --check` have been checked.

Planning verification: whole-record tracking consistency, local links and heading anchors,
and `git diff --check` pass. Compiler tests are not applicable to these tracking-only changes.

P0 verification (2026-10-26):

- `sbtn 'scalafmtAll;scalafixAll;compile'` passed without compiler warnings.
- `sbtn testFull` passed 1,955 library tests and 9 CLI tests, with no failures or errors.
  The test launcher reported multiple main classes; this did not affect the test result.
- `./tests/smoke/run.sh all` passed all eight fixtures.
- `sbtn mmlcPublishLocal`, `make -C benchmark clean`, and `make -C benchmark mml` passed.
  Benchmark verification compiled the collection; it did not measure runtime performance.
- `git diff --check` and the manual QA compliance pass passed.
- Independent code review and fresh QA/style, test-quality, and readability checks completed
  with no actionable findings. Memory and Linux container gates are not applicable:
  this step changes declaration traits and AST definitions, not ownership, lambda execution,
  native calling conventions, or ABI layouts.

P0b verification (2026-10-26):

- The first full-suite attempt failed compilation on stray `lov` text before a test.
  Removing that typo restored compilation.
- `sbtn 'scalafmtAll;scalafixAll;testFull'` passed 2,001 library tests and 9 CLI tests,
  with no failures or errors and no compiler warnings. The launcher reported multiple main classes.
- `./tests/smoke/run.sh all` passed all eight fixtures.
- `sbtn mmlcPublishLocal`, `make -C benchmark clean`, and `make -C benchmark mml` passed.
  The benchmark collection was compiled; runtime performance was not measured.
- `git diff --check` and the manual QA compliance pass passed. Independent primary review and
  separate QA/style, test-quality, and readability checks completed. One independently verified
  finding required doc-comments on three public lookup methods; those comments are added and
  formatted. Fresh narrow primary review and all three dedicated checks found no actionable
  findings in the correction.
- Memory and Linux ABI gates are not applicable: registry identities and AST naming do not
  change ownership, lambda execution, native calling conventions, or ABI layouts.

The definitions in
[protocols.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/protocols.scala)
represent protocols, signatures, and instances as declarations. P0 is verified and
signed off. P0b is verified and signed off; P1 parsing is next. P1–P9 implementation remains pending.

## Risks / Notes

[Enums](enums.md) depends on protocol instance resolution for derived equality, but derivation
is outside this slice and tracked in [Protocol derivation](protocol-derivation.md).
Clone generation adapts the existing memory-functions phase. It does not approve a general
derivation facility or the full memory evolution design. The global-cloning change must
cover global provenance through aliases and aggregates; broader literal-cloning policy
is tracked separately in [Revisit implicit cloning](implicit-cloning.md).
The language draft defines semantics beyond the approved bounded plan.

## Signoff

- Workstream signoff: Bounded plan, P0 AST shape, and P0b lookup API approved. P0 and P0b
  verification and independent review are complete.
- Tracked item completion: Pending.
- Commit authorization: Granted for P0 and P0b source changes, documentation, and scoped bookkeeping.

## Task Working Memory

Deliver protocols first, validated by provisional Num instances for Int and Float. Then
inject Clone, adapt the memory-functions phase to generate its instances, and replace
implicit cloning of globals at ownership sinks with rejection unless `^` is explicit.
General derivation remains separate. Protocols supply all member types and metadata;
instances may omit or repeat them, but divergent restatements must be rejected.
Instance member bindings are lowered before reference resolution. Function values and partial
applications select concrete members during type checking, before ordinary callable lowering,
as detailed in the implementation reference.
The first slice has one protocol type parameter and required members only. Defaults,
prerequisites, and multiple protocol parameters are deferred.
The bounded P0–P9 plan, including P0b, is approved.
P0 defines `Protocol`, `ProtocolSignature`, `ProtocolRef`, and `ProtocolInstance` in
[protocols.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/protocols.scala).
Signatures reuse `FnParam`, `Type`, and `BindingMeta`; default bodies use `Option[Lambda]`,
and instance bodies remain ordinary `Bnd` values. Protocols, member signatures, and instances
extend `Decl`. Named declarations also implement
`Resolvable`; `Bnd`, `TypeDef`, and `TypeAlias` explicitly implement `Typeable`.
Instances carry their own optional ID and are unnamed declarations. Protocol references
retain `Name` plus an optional resolved protocol ID.
P0 is verified, signed off, and committed in `c3091ce6`.
P0b adds an immutable `ProtocolRegistry` with protocol/signature registration, concrete-instance
keys, member-binding associations, and lexical candidate lookup. Nodes are retrieved from the
supplied `ResolvablesIndex`. Keys follow aliases and preserve nominal identity; registry comments
explain the model with alias, Num/Clone, shadowing, and updated-binding examples.
The public registry API precedes its auxiliary types. Tests share concrete Num declarations but
show registration, the operation, and explicit expected results individually, without generated-case
loops or generic fixture wrappers. The signature node and lookup vocabulary use `ProtocolSignature`.
P0b is verified and signed off, with local commit authorization. Full-suite, smoke, local publish,
benchmark compilation, and independent review results are recorded under Verification.
P1 parsing is the next approved step: preserve omitted instance annotations and operator attributes,
then integrate source-aware traversal, IDs, indexes, diagnostics, printers, and editor consumers.
P6 is the Num signoff gate before Clone integration. Literal promotion and mixed
ownership witnesses remain supported; broader implicit-cloning policy belongs to
[Revisit implicit cloning](implicit-cloning.md).
