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
5. [x] **complete** — [P1: Parse protocol signatures and concrete instances](#p1-declarations-and-syntax).
6. [x] **complete** — [P2: Validate signatures and register concrete member functions](#p2-signatures-and-concrete-members).
7. [x] **complete** — [P3: Enforce precedence/associativity coherence before grouping; preserve candidates](#p3-lookup-and-operator-grouping).
8. [x] **complete** — [P4: Select concrete members using arguments and expected types](#p4-type-directed-selection-and-lowering).
9. [x] **complete** — [P5: Deliver Num and migrate numeric operators](#p5-num-and-numeric-migration).
10. [x] **complete** — [P6: Review the verified Num deliverable](#p6-num-review-checkpoint).
11. [ ] **planned** — [P6a: Infer concrete protocol functions from local binding uses](#p6a-local-inference-through-bindings).
12. [ ] **planned** — [P9: Complete documentation, verification, and final review](#p9-final-verification-and-documentation).

## Problem

MML needs ad hoc polymorphism for type-based function and operator selection. Enum
equality requires concrete instances, and memory evolution depends on compiler-recognized
`Unique` and `Clone` protocols.

## Outcome

A functional protocol system unifies numeric operator spellings for `Int` and `Float`
and supports concrete protocol functions inferred from local binding uses. Protocol declarations,
concrete instances, signature checking, member resolution, and executable lowering provide
the foundation for [explicit Clone and global ownership transfers](explicit-clone.md).

## Scope

The [Protocols draft](../../docs/brainstorming/language/protocols.md) describes the full
design. This task implements only the smallest end-to-end slice needed for the outcomes
below. Completing this slice does not mean implementing the entire specification.

### First implementation slice

Deliver the protocol system through `Num`, then local binding inference and final verification.
[Explicit Clone and global ownership transfers](explicit-clone.md) follow as a separate task.

- Protocol declarations with one type parameter, required function and operator members,
  and concrete instances. Every required member has a source-defined or compiler-generated
  instance binding.
- Instance uniqueness, signature checking, and type-based candidate and instance selection
  using argument and expected result types, resolving calls to lowered instance member bindings.
- Best-effort local inference of one concrete protocol function from later uses of a binding,
  without requiring an annotation or depending on full SimpSeq. If local evidence cannot
  determine a unique concrete member, stop with a diagnostic.
- Shared operator precedence and associativity checked before expression rewriting.
- A provisional `Num` protocol with concrete `Int` and `Float` instances supplies unified
  numeric operators. A fuller numeric hierarchy belongs to a later Spire port:
  - Binary arithmetic: `+`, `-`, `*`, `/` replace the corresponding dotted Float spellings.
  - Equality and ordering: `==`, `!=`, `<`, `>`, `<=`, `>=` replace their dotted Float spellings.
  - Unary negation: `-` replaces `-.` for Float.
  - Preserve existing numeric behavior and grouping; migrate affected library declarations,
    samples, and tests. Addition is only one case of this migration.
- Explicit Clone and global ownership transfers belong to
  [Explicit Clone and global ownership transfers](explicit-clone.md).

The operator inventory above follows `injectStandardOperators` in
[StandardOperators.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/StandardOperators.scala),
which supplies the compiler's built-in operators. `prelude.mml` is an unused placeholder,
not the source of those declarations. Integer remainder and shifts have no Float counterparts;
the plan keeps them as free operators.

### Beyond the first slice

- General [protocol derivation](protocol-derivation.md), including compiler-derived enum
  equality, is separate follow-up work. Targeted Clone generation
  belongs to [Explicit Clone and global ownership transfers](explicit-clone.md).
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

### Member signatures and instance selection

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
against the concrete signature and reject any divergence.
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

P0–P6 and P9 are approved, subject to their explicit review checkpoints. The added P6a scope is
accepted; its bounded implementation design requires approval. P0 defines the AST
and stops for Author review.
P0b adds shared lookups and a second Author review checkpoint before compiler-phase integration.
P1–P6 deliver the protocol mechanism through Num. Stop after P6 for review and signoff before
P6a adds local binding inference, then P9 verifies this task. P7 and P8 belong to
[Explicit Clone and global ownership transfers](explicit-clone.md).
Each stage has a concrete exit condition; record its evidence in Task Working Memory and
update the execution checklist before advancing. Stage completion does not authorize a commit.

### Implementation choices

- A protocol node contains member signatures, each with an optional Lambda body for a default.
  An instance node contains a protocol reference, a concrete type, and member declarations whose
  `Bnd` values contain Lambdas. Instance declarations retain written operator attributes separately
  from concrete callable metadata. Required protocol members have no body.
- Instance members already have the ordinary binding shape. Lowering supplies their concrete
  signatures from the protocol and places their bindings among the module's executable
  bindings. `BindingMeta` retains function/operator origin, arity, and source member name;
  operator precedence and associativity come from the protocol signature. Precedence is an `Int`;
  omitting it in source means a free-operator default or inheritance from the protocol signature.
- Shared lookup machinery associates protocol, concrete type, and member identities with each
  instance member's binding ID. That association need not be encoded in the ID's spelling.
  Retrieve current bindings through the resolvables index rather than duplicating their bodies.
  Calls select those ordinary bindings during type checking; no runtime dictionary or separate
  protocol calling convention is introduced.
- The typer checks instance bodies as ordinary Lambdas against their concrete signatures.
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
- Require resolution, signature checking, operator grouping, type checking, and Clone generation
  to use this machinery. Integrate those consumers in their later stages, not during this step.

Exit: present the API, its data ownership, and focused test results to the Author, then **stop**.
Proceed to P1 only after explicit review and signoff of the shared lookup machinery.

### P1: Declarations and syntax

- Use the reviewed AST nodes and retain source locations. Reuse ordinary function/operator
  parameter and type syntax, including consuming parameters.
- Parse one protocol type parameter, bodyless required members, and explicit instance bodies.
  Preserve omitted instance types and operator attributes until the signature supplies them.
  Do not apply free-operator defaults to omitted instance metadata.
- Reject defaults, prerequisite constraints, multiple protocol parameters, parameterized
  instances, and member-local polymorphism with focused unsupported-feature diagnostics.
- Extend declaration traversal, ID assignment, indexing, diagnostic rendering, AST printers,
  and existing editor consumers as required to handle the new nodes without losing source data.

Exit: parser tests cover named members, unary/binary operators, omitted annotations,
terminators, malformed declarations with recovery, and unsupported forms. Existing ordinary
function/operator syntax remains accepted.

### P2: Signatures and concrete members

- Resolve protocol parameter references in their own scope and concrete instance types through
  the existing type resolver. Populate the shared registry and use the reviewed P0b lookup API.
- Check duplicate protocols and instances; distinguish repeated names within a protocol from
  legal shared member names across protocols. Generated instances obey the same uniqueness rule.
- Match instance members to their signatures by member identity and callable shape. Reject
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
signature inheritance and mismatch diagnostics, stable references, and concrete signatures on
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

- Use shared lookups to retrieve signatures, instances, and their current member bindings.
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
- P4 rejects unresolved overloads at binding boundaries; P6a extends local inference across
  those boundaries before requiring a concrete selection. Use ordinary partial-application,
  ownership, and code-generation paths after selection; validate that no executable protocol
  reference remains unresolved before those phases consume it.

Exit: user-defined named and operator members compile and execute. Tests cover expected-result
selection of nullary calls, expected function types, partial applications, later-argument
selection, nested calls, recursive concrete members, and rejection of unresolved function values.
Inspect resolved identities and types; include runtime checks for argument evaluation once and
in source order. No polymorphic binding or runtime dispatch is introduced.

### P5: Num and numeric migration

- Inject the provisional Num protocol and concrete Int/Float instances through the same
  registration and checking mechanism as source declarations. Reuse existing LLVM templates.
- Migrate binary arithmetic, equality, ordering, and unary negation as enumerated in Scope.
  Preserve precedence, associativity, integer division, floating comparison predicates, and
  the current unary Float implementation; no numeric conversion policy is added.
- Remove overlapping free numeric declarations and injected dotted Float declarations.
  Update programmatic library bodies, current samples, benchmarks,
  smoke/memory fixtures, and tests that depend on the split spellings. Preserve historical
  records and deliberately user-defined dotted operators.
- Keep numeric documentation aligned with injection: Float negation uses subtraction from zero,
  and Float has no unary `+`. The existing LLVM behavior is the compatibility baseline.

Exit: both numeric instances execute all listed operations, with grouping and Float edge
cases covered. Mixed Int/Float operands remain rejected without an applicable instance.
Integer-only free operators still work. Removed built-in spellings fail as undefined unless
explicitly declared by the program. Current shipped programs use the supported spellings.

### P6: Num review checkpoint

- [Protocol error recovery and integration repairs](protocols-review-repairs.md) are complete
  and signed off. The child task records the corrections and renewed compiler-gate results.
- Resolve broader choices through [separate design discussions](protocols-design-discussions.md),
  one topic per iteration. Function-value wrapping stays in place pending its discussion.
- Run the applicable compiler verification and review gates described under P9 for P0 through
  P5, including P0b. Record actual results, outstanding failures, and any explicit waivers.
- Present the code tour and working Num examples, including a user-defined protocol, a
  function value, a partial application, and representative rejection diagnostics.
- Obtain review and signoff for the Num deliverable before starting Clone integration.

Exit: Num is verified and signed off; unresolved required checks or findings keep this stage open.

### P6a: Local inference through bindings

- Prepare and approve a bounded design for propagating local use-site type information back
  to a protocol function binding. Define the local inference boundary and stopping conditions.
- Accept `let render_int = render;` followed by `render_int "function value: " 5;` in the
  local body of [protocol-functions.mml](../../mml/samples/protocol-functions.mml), selecting
  the `Render Int` member without an explicit function-type annotation.
- Select one concrete member and function type for the binding, consistent with all its local
  uses. Do not select different instances at different uses of the same binding.
- Make a best-effort inference attempt within the defined local boundary. Diagnose insufficient
  information, ambiguity, or incompatible uses when no unique consistent selection is possible.
  Do not guess by candidate order or require whole-program inference.
- Reuse the shared candidate-selection machinery and preserve explicit annotations as constraints.
  Extend the existing bidirectional push/pull technique across local bindings: pull evidence
  from uses and push the resulting expected function type back into the initializer.
  Resolve executable protocol references before ordinary callable lowering and ownership analysis.
- Do not depend on full SimpSeq, let-polymorphism, generalized protocol constraints, or
  cross-module inference. Document unsupported local propagation cases explicitly.
- Verify inferred member identity and concrete type, successful execution of the unannotated
  example, consistent repeated uses, conflicting uses, insufficient evidence, ambiguity,
  annotation conflicts, and isolation between separately bound or shadowed names.
- Update the sample's annotation requirement and relevant documentation after implementation;
  run the applicable compiler verification and review gates before handoff.

Exit: the unannotated local example compiles and executes using `Render Int`; unresolved or
inconsistent local evidence produces a clear diagnostic. The binding remains monomorphic.
Implementation plan approval and verification are pending.

### P9: Final verification and documentation

- Update the language reference, compiler design, memory-model documentation, and current
  examples to describe implemented protocol syntax, resolution, Num, and local binding inference.
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

Approval: protocol scope, delivery order, and P0–P6/P9 plan (including P0b) approved.
The approved P7/P8 plan is in [Explicit Clone and global ownership transfers](explicit-clone.md).
P0, P0b, and P6 retain their explicit review and signoff gates.
P6a local binding inference is included in scope before Clone; implementation design approval
is pending. Existing checkpoint verification does not cover P6a.

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
- P6a permits deferred selection through a local binding while gathering evidence from its uses.
  Reject references that remain insufficiently constrained or ambiguous at the local inference
  boundary. Every use of a binding must agree on one concrete implementation; selecting different
  implementations at different uses would introduce polymorphism beyond this scope.
- Once resolved, use the ordinary function-value and partial-application lowering paths.

Implementation anchors:

- [RefResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/RefResolver.scala)
  retains multiple matches in `candidateIds`.
- [TypeChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala):
  checks retained candidates using the full enclosing application chain, independently known
  argument types, and expected types. Candidate-local trees and diagnostics are committed only
  for a unique applicable concrete member.
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

P1 verification (2026-10-26):

- `sbtn 'scalafmtAll;scalafixAll;testFull'` passed formatting and linting. An accidental
  repository-wide removal of `unsafe_` caused duplicate runtime definitions and one failing
  `AsanInstrumentationTests` case. The entire accidental replacement is reverted while
  preserving P1 changes.
- The final `sbtn testFull` passed 2,030 library and 9 CLI tests, with no failures or errors.
  The launcher reports multiple main classes; there are no compiler warnings.
- Focused coverage includes 28 `ProtocolParserTests` plus the documented-protocol/grouped-type
  token regression in `SemanticTokensTests`.
- `./tests/smoke/run.sh all` passed all eight fixtures.
- `sbtn mmlcPublishLocal`, `make -C benchmark clean`, and `make -C benchmark mml` passed.
  The benchmark collection was compiled; runtime performance was not measured.
- Independent primary review and separate QA/style, test-quality, and readability checks completed.
  Three independently confirmed findings are corrected: function members use ordinary binding-name
  validation, the recovery test observes the second signature's diagnostic, and precedence rendering
  fits the line limit. Formatting, linting, and all 28 parser tests pass after the corrections.
  The first correction attempt failed compilation on a parser tuple pattern; the passing run
  includes its corrected nested pattern. Fresh narrow primary review and separate QA/style,
  test-quality, and readability checks completed with no actionable findings. Full-suite, smoke,
  publish, and benchmark compilation passed on the corrected source.
- `git diff --check`, the main-agent QA pass, and whole-record tracking review passed.
- Memory and Linux ABI gates are not applicable: parsing and declaration consumers do not
  change ownership, lambda execution, native calling conventions, or ABI layouts.

P2 verification:

- Type resolution passed 39 focused tests, including six protocol-specific cases.
- Instance checking passed 31 focused tests, covering signature matching, registry associations,
  identity/source preservation, diagnostic consumers, editor lookup, and repeated lowering.
- `sbtn 'scalafmtAll;scalafixAll;testFull'` passed 2,074 library and 9 CLI tests, including the
  shared type traversal. No compiler warnings were reported.
- Initial focused verification exposed a missing `CanEqual` derivation on the member lookup key
  and an array-identity comparison in a new token test. Both are corrected and the focused suite passes.
- Shared type traversal replaces repeated walks in parsing, resolution, signature substitution,
  and invalid-type checks. Its seven focused regressions pass within the full suite.

P3 verification:

- First focused run: 119 of 122 tests passed. Three grouping failures exposed binary/postfix
  selection and prefix operators in value position; both are corrected with explicit regressions.
- The first full run passed 1,992 of 2,107 library tests and all 9 CLI tests. All 21 grouping
  cases passed, including conflict rejection, reversed declaration order, and callable values.
- The 115 full-suite failures share undefined generated constructor references. Shared lookup
  omitted their internal binding names; it accepts both source and internal names, with an
  additional constructor regression. The corrected `sbtn 'scalafmtAll;scalafixAll;testFull'` run
  passed all 2,108 library and 9 CLI tests. No compiler warnings were reported.
- B6 stale-reference selection has a passing rerun regression. See [Bug notes](bug-notes.md).

P4 verification:

- `sbtn 'scalafmtAll;scalafixAll;testFull'` passed 2,199 library and 9 CLI tests.
  Formatting and linting pass; no Scala compiler warnings were reported. The launcher reports
  multiple main classes. Forked programs report the Scala runtime's deprecated Unsafe call.
- Earlier full runs exposed grouped annotation loss, duplicate parser annotations, bare-function
  ownership marking, and stale wrapper-annotation assertions. Focused tests also exposed repeated
  operand checking and premature selection in the presence of an unresolved signature. Corrections
  and explicit regressions are included in the passing full suite; see B10–B15 in
  [Bug notes](bug-notes.md).
- Both new examples execute successfully via `sbtn 'run run -s <path>'` with sanitizers:
  `mml/samples/protocol-functions.mml` prints `answer: 42`, `ready: true`, `function value: 5`,
  and `partial: 15`; `mml/samples/protocol-operators.mml` prints `42`, `true`, `5`, and `15`.
- The initial frontend corpus scan completed 182 sources, then hit a parser stack overflow in
  `mml/samples/stack-depth-1000-lets.mml`. The retry with
  `set mmlclib / Test / javaOptions += "-Xss16m"` completed all 262 sources: 224 clean and
  38 with diagnostics, with no infrastructure failures. The larger stack is a corpus-run
  setting; [Compiler stack safety](compiler-stack-safety.md) remains open.

<details>
<summary>P4 corpus baseline: sources with diagnostics</summary>

All other sources in `mml/samples` (recursive), `benchmark`, `tests/smoke`, and `tests/mem`
(direct files in the latter three) are clean. Counts include dependent diagnostics; messages
list the visible diagnostic roots.

| Source | Count | Visible diagnostics |
|---|---:|---|
| `mml/samples/InterleavedErrors.mml` | 6 | Parse error in expression: Expected expression; Undefined type: 'Fsck'; Type mismatch: expected Int64Alias, got Int; Type mismatch: expected TestInt, got String; Type mismatch: expected TestInt, got Int |
| `mml/samples/bad-line.mml` | 2 | Type mismatch: expected Unit, got Int |
| `mml/samples/concat_borked_partial.mml` | 2 | Type mismatch: expected String, got (String) -> String (expected by println) |
| `mml/samples/convobroken.mml` | 17 | Type mismatch: expected Int, got String |
| `mml/samples/duplicates.mml` | 1 | Duplicate definition: 'a' |
| `mml/samples/error-acum.mml` | 19 | Parse error in identifier: Invalid identifier '0'. Identifiers must start with a lowercase letter (a-z) followed by letters, digits, or underscores; Parse error in expression: Expected expression; Parse error in expression: Expected '='; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Duplicate definition: 'a'; Duplicate definition: 'end'; Undefined reference: 'call_me'; Unexpected term after expression - expected an operator; Cannot resolve type for 'end' |
| `mml/samples/hole-test.mml` | 2 | Typed hole in 'a' requires type annotation |
| `mml/samples/if-with-int.mml` | 2 | Type mismatch: expected Bool, got Int |
| `mml/samples/lambda-forms/borrow-capture-consuming-fail.mml` | 1 | Cannot pass borrowed value 'add_seed' to consuming parameter 'f' |
| `mml/samples/lambda-forms/struct-field-borrow-fail.mml` | 1 | Cannot pass borrowed value 'add_seed' to consuming parameter 'f' |
| `mml/samples/mem/borrow-escape.mml` | 1 | Cannot return borrowed value 's' from a function that returns a heap type |
| `mml/samples/mem/consume-not-last.mml` | 3 | Use of 's' after move at 4:11; Use of 's' after move at 10:11; Consuming parameter 's' must be the last use of 's' |
| `mml/samples/mem/move-borrow-fails.mml` | 1 | Cannot pass borrowed value 's' to consuming parameter 's' |
| `mml/samples/mem/struct-field-mutation.mml` | 5 | Undefined reference: '=' |
| `mml/samples/mem/use-after-move.mml` | 2 | Use of 's' after move at 7:11; Consuming parameter 's' must be the last use of 's' |
| `mml/samples/member_error.mml` | 6 | Parse error in expression: Expected expression; Parse error in expression: Expected '='; Parse error in expression: Expected expression |
| `mml/samples/module-parsing-error-2.mml` | 1 | Parse error in member: Failed to parse member |
| `mml/samples/module-parsing-error-3.mml` | 1 | Parse error in member: Failed to parse member |
| `mml/samples/module-parsing-error-4.mml` | 7 | Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member |
| `mml/samples/module-parsing-error-5.mml` | 2 | Parse error in member: Failed to parse member; Parse error in member: Failed to parse member |
| `mml/samples/module-parsing-error-6.mml` | 14 | Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in expression: Expected expression; Parse error in expression: Expected ';'; Duplicate definition: 'a'; Duplicate definition: 'b'; Duplicate definition: 'c'; Unexpected term after expression - expected an operator |
| `mml/samples/module-parsing-error-7.mml` | 16 | Parse error in expression: Expected expression; Parse error in expression: Expected expression; Parse error in expression: Expected '='; Parse error in expression: Expected ';'; Duplicate definition: 'z'; Unexpected term after expression - expected an operator; Type mismatch: expected Int, got Float (expected by y) |
| `mml/samples/module-parsing-error-8.mml` | 8 | Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in identifier: Invalid identifier '1'. Identifiers must start with a lowercase letter (a-z) followed by letters, digits, or underscores; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member; Parse error in member: Failed to parse member |
| `mml/samples/module-parsing-error.mml` | 1 | Parse error in member: Failed to parse member |
| `mml/samples/native-no-return.mml` | 1 | Missing return type for 'is_bad_native' |
| `mml/samples/nullary_app_fail.mml` | 2 | Type mismatch: expected String, got (Unit) -> String (expected by println) |
| `mml/samples/pap-borrowed-return-diagnostics.mml` | 2 | Cannot capture borrowed heap binding 'borrowed' in a closure; Cannot return borrowed value 'callee' from a function that returns a heap type |
| `mml/samples/parse-error-later.mml` | 2 | Type mismatch: expected String, got Bool (expected by println) |
| `mml/samples/parser-type-recovery.mml` | 7 | Parse error in type: Invalid type syntax; Undefined reference: 'missing' |
| `mml/samples/parser_error1.mml` | 2 | Parse error in expression: Invalid expression syntax |
| `mml/samples/parser_error2.mml` | 4 | Parse error in expression: Expected expression; Parse error in expression: Expected '=' |
| `mml/samples/should_fail.mml` | 2 | Type mismatch: expected String, got Int (expected by println) |
| `mml/samples/term-ascriptions.mml` | 2 | Type mismatch: expected String, got Int |
| `mml/samples/test-poisoning.mml` | 6 | Type mismatch: expected Int, got String |
| `mml/samples/test_negative_add.mml` | 4 | Type mismatch: expected String, got Int (expected by println) |
| `mml/samples/test_return_type_inference.mml` | 1 | Parse error in member: Failed to parse member |
| `mml/samples/type-error.mml` | 8 | Parse error in expression: Expected expression; Undefined reference: 'Pepe'; Type mismatch: expected String, got Int |
| `mml/samples/undefined_mult_op.mml` | 3 | Unexpected term after expression - expected an operator |

</details>

P5 verification:

- The first full run passed 2,215 of 2,222 library tests and all 9 CLI tests. All 15 initial
  Num selection tests and 8 Num execution tests passed. Five existing failures exposed lambda
  inference bypassing protocol signature evidence; two were test setup assumptions about the
  first protocol and the first identity-assignment checkpoint. These are corrected without
  changing the existing inference-conflict or recovery assertions.
- A follow-up build required a field-access correction in a new capture-ID assertion.
  The corrected `sbtn 'scalafmtAll;scalafixAll;testFull'` passes 2,227 library and 9 CLI tests,
  including 20 explicit Num selection cases and 8 O0/O3 sanitizer execution cases. There are
  no Scala compiler warnings. The launcher reports multiple main classes; forked programs
  report the Scala runtime's deprecated Unsafe call.
- Both examples execute with sanitizer checks. The named example prints `answer: 42`,
  `ready: true`, `function value: 5`, and `partial: 15`. The operator example includes Int,
  Float, and Bool instances and Num expressions; it prints `42`, `4`, `true`, `5`, `15`, `14`, `14`.
- The complete frontend corpus comparison covers all 262 sources with the same `-Xss16m`
  setting as the baseline: 224 clean, 38 with diagnostics, no new failing sources and no
  infrastructure failures. `convobroken.mml` reports incompatible `*` protocol arguments
  (19 raw errors instead of 17); `should_fail.mml` reports incompatible `+` arguments/expected
  type (3 instead of 2). `test_negative_add.mml` retains the same visible mismatch with 3 raw
  errors instead of 4. Every other record matches the P4 baseline exactly.
- Shared application-chain traversal cleanup is covered by the P6 verification below.

Initial P6 verification (baseline for the review repairs):

- `sbtn 'scalafmtAll;scalafixAll;testFull'` passes after the shared application-node traversal
  cleanup: 2,227 library and 9 CLI tests, no failures/errors or Scala compiler warnings.
- `./tests/smoke/run.sh all` passes all eight fixtures. `sbtn mmlcPublishLocal` installs the
  verified compiler locally. `make -C benchmark clean` and `make -C benchmark mml` pass;
  this establishes benchmark compilation, not runtime performance measurements.
- `./tests/mem/run.sh all` passes all 52 fixtures with ASan and LSan in 72 seconds.
- Host verification uses macOS arm64, Homebrew Clang/LLVM 23.1.1, target
  `arm64-apple-darwin25.6.0`. No native ABI layouts or calling conventions change;
  Linux container and hosted verification gates are not applicable.
- `git diff --check`, main-agent QA, and whole-record tracking consistency/local links pass.
  Independent primary review and separate fresh QA/style, test-quality, and readability checks
  completed. Four findings were confirmed by separate fresh verifiers: candidate-isolation
  fixtures filtered out the losing trial, AST test navigation was repeated, sanitizer test
  orchestration was repeated, and `where` was incorrectly documented as reserved.
- Corrections are limited to tests, shared test helpers, and the language reference. Both
  candidate orders check two concrete candidates, with independent-inference and losing-only
  controls. AST navigation and sanitizer execution share helpers; literal fixtures and expected
  results remain visible. The `where` appendix matches contextual parser recognition.
- The first correction run passed 2,228 of 2,230 library tests and all 9 CLI tests. Two fixture
  issues required correction: a missing declaration terminator and an assertion forbidding a
  properly suppressed consequence diagnostic. The latter now checks its original diagnostic root.
  `sbtn 'scalafmtAll;scalafixAll;testOnly mml.mmlclib.semantic.ProtocolSelectionTests;testFull'`
  then passed all 57 selection cases, 2,230 library tests, and 9 CLI tests. No production source
  changed during these corrections, so the smoke/publish/benchmark/memory evidence above remains
  applicable.
- Fresh narrow primary review and separate QA/style, test-quality, and readability checks
  confirmed the corrections. One independently verified spacing finding in a nested test match
  is corrected. `sbtn scalafmtAll` and `git diff --check` pass; comparison establishes that only
  three blank lines changed. Final fresh primary review and all three separate fresh dedicated
  checks report no actionable findings. One earlier spacing-review attempt ended before dedicated
  checks after a skill-discovery error; the successful fresh review supplies complete coverage.
- These checks establish the baseline before the independently confirmed error-recovery and
  repeated-checking findings. The repair child records the additional verification and review.

The definitions in
[protocols.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/protocols.scala)
represent protocols, signatures, and instances as declarations. P0 is verified and
signed off. P0b is verified and signed off; P1 parsing and P2 signature checking/lowering are
complete. P3 lookup and operator grouping are verified and complete. P4 type-directed selection
is complete. P5 Num integration is complete. P6 error-recovery repairs and four additional standalone
corrections pass compiler and Linux verification and independent review and are signed off.
The broader Num checkpoint is signed off.
P6a and P9 remain pending; P7/P8 are tracked in [explicit Clone](explicit-clone.md). The
verification above does not cover local binding inference.

## Risks / Notes

[Enums](enums.md) depends on protocol instance resolution for derived equality, but derivation
is outside this slice and tracked in [Protocol derivation](protocol-derivation.md).
The separate [explicit Clone task](explicit-clone.md) adapts the memory-functions phase. It does not
approve a general derivation facility or the full memory evolution design. The global-cloning change must
cover global provenance through aliases and aggregates; broader literal-cloning policy
is tracked separately in [Revisit implicit cloning](implicit-cloning.md).
The language draft defines semantics beyond the approved bounded plan.

Incidental findings and their fix status are collected in [Bug notes](bug-notes.md).

## Signoff

- Workstream signoff: Bounded plan, P0 AST shape, and P0b lookup API approved. P0 and P0b
  verification and independent review are complete.
- Num deliverable (P6): complete and signed off, including the repair child and its four
  standalone corrections.
- Tracked item completion: Pending.
- Commit authorization: granted for all implemented corrections and related records.
  Push is not authorized. Parent task completion remains pending.

## Task Working Memory

P0 and P0b are verified, signed off, and committed in `98e4c831` and `be195134`.
The implemented P1–P6 review checkpoint is committed in `7ce9c5dd`. P1–P5 are complete.
The [review repairs](protocols-review-repairs.md), including all four standalone corrections,
are complete and signed off. P6 Num checkpoint is complete and signed off. The parent task
remains open; P6a implementation requires approval of its bounded design.

P6a extends the scope with best-effort local inference of one concrete protocol function from
later binding uses, independent of full SimpSeq. Its implementation design and verification
are pending. Next action for P6a: define the bounded local propagation strategy and stopping
conditions for approval.

The separate factorial sample omits the `make_fac` annotation.
`sbtn 'run run mml/samples/partial-fac2-escape.mml'` prints `120` and `6`.
The uncommented `println consumed_text` in `mml/samples/pap-ownership.mml` is rejected as a
use after move by `sbtn 'run mml/samples/pap-ownership.mml'`. These sample edits are preserved
separately from the compiler repair scope; the PAP sample is not a passing runnable fixture.

Protocols, signatures, and instances are declarations. Protocols constrain a single type
parameter; they are not ordinary value types. Required signatures and concrete instances are
supported; defaults, prerequisites, general generics, and derivation remain outside this slice.
Instance syntax retains written attributes separately. Concrete callable precedence is always
an Int; omitted instance precedence inherits the signature. Public registry APIs precede
auxiliary types, with examples and explicit tests.

The two added phases are ProtocolChecker before reference resolution and OperatorGrouping
before expression rewriting. The first validates signatures and lowers instance bodies to
ordinary functions; the second enforces consistent grouping while preserving candidates.
TypeChecker selects concrete members using full application chains, known operands, and
expected types. It preserves written annotations, isolates failed candidate trials, and shares
signature evidence with lambda inference. Shared type identity follows aliases and declaration
IDs. Function values and partial calls share late callable lowering and ordinary ownership rules.
Application-node traversal is consolidated in CallableValues; original nodes retain annotations
and invocation boundaries.

Num supplies eleven arithmetic/comparison/negation signatures with Int and Float instances.
LLVM behavior and Int implementation IDs are preserved. Integer-only operations, Boolean
operators, and string concatenation remain free operators. Dotted Float built-ins are removed;
current samples, smoke fixtures, tests, and documentation use unified symbols. Source-declared
dotted operators remain valid. The named and operator examples in `mml/samples` run successfully
with sanitizers; the latter includes Int, Float, and Bool instances, function values, partial
calls, and ordinary Num expressions.

P5 formatting, linting, and the full suite pass 2,227 library and 9 CLI tests. The 262-source
frontend corpus retains all 224 clean sources and 38 diagnostic sources. It uses `-Xss16m`
for the known 1,000-let stack limitation; complete comparison evidence is under Verification.
B10–B13, B15, and B17 are corrected and verified. The B14 failed-operand repair passes focused
and full tests and independent review. Findings and follow-up links are in [Bug notes](bug-notes.md).

The four standalone P6 corrections cover inference shadowing, native instance symbols, editor
protocol syntax, and the expected-result description. Verification passes 2,292 library/9 CLI tests,
eight smoke fixtures, local publishing, benchmark compilation, 52 ASan/LSan memory fixtures,
and all 27 affected execution tests in each Linux builder. Formatting, linting, and grammar checks
pass. Fresh narrow primary review and separate QA/style, test-quality, and readability checks
complete without actionable findings. The repair child and broader Num checkpoint are
complete and signed off. Optional cloud verification is deferred.
Detailed commands, toolchains, and limits are in the repair task.
Broader design choices have separate discussion subtasks.
No ABI layout or calling-convention changes are present.
P6a adds local binding inference before final verification.
[Explicit Clone and global ownership transfers](explicit-clone.md) owns P7 and P8. Literal promotion
and mixed ownership witnesses remain supported.
General derivation and broader
implicit-cloning policy remain separate tasks.
