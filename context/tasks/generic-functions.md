# Generic functions

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** feature
- **Priority:** HIGH
- **Created:** 2026-11-13
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — [S1: Extend the AST and parse generic function syntax](#s1-ast-and-parsing).
2. [ ] **planned** — [S2: Resolve scoped variables and share type substitution](#s2-resolution-and-substitution).
3. [ ] **planned** — [S3: Check generic types and constraints; infer use-site arguments](#s3-type-checking-and-inference).
4. [ ] **planned** — [S4: Implement FnMonomorphizer and recursion checks](#s4-fnmonomorphizer).
5. [ ] **planned** — [S5: Integrate local functions, callable values, and existing ownership](#s5-callables-and-ownership).
6. [ ] **planned** — [S6: Complete documentation, verification, and signoff](#s6-documentation-and-verification).

## Tags

- generics
- type-system
- protocols

## Problem

Ordinary generic functions and `where` constraints are proposed language features.
Generic signatures must preserve relationships between parameter and result types across
calls while checking bodies against their declared constraints. The
[generic functions draft](../../docs/brainstorming/language/generic-functions.md)
defines the proposed behavior and identifies decisions needed before implementation.

## Outcome

Support generic functions with scoped type-variable identities, declared protocol constraints,
inferred or explicit type arguments, and type and constraint checking independent of concrete
callers. Preserve existing ownership and callable contracts through instantiation and code
generation.

## Scope

- In scope: ordinary value-type variables, fixed type constructors, generic calls,
  concrete function values, partial application, local functions, and same-substitution recursion.
- In scope: `FnMonomorphizer`, abstract type and constraint checking, and
  inference under declared constraints.
- Out of scope: higher-kinded variables, user-defined generic data types, polymorphic callable
  values, global generalization, and nested modules.

## Existing machinery and required extensions

- **Type variables and schemes:** [type nodes](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/types.scala)
  already include `TypeVariable`, `TypeScheme`, and `TypeApplication`. Variables and scheme
  binders use name strings. Schemes have traversal and display support, but no ordinary
  generic-function construction path. Extend these representations with scoped identities;
  do not add a parallel generic type system.
- **Traversal and substitution:** [TypeTraversal](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeTraversal.scala)
  visits function parameter types before the result and traverses nested types.
  [ProtocolChecker.substitute](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ProtocolChecker.scala)
  uses that traversal to replace one named variable. Generalize substitution to resolved
  variable identities and reuse it for protocol signatures and generic functions.
- **Resolution and parsing:** [TypeResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeResolver.scala)
  already traverses function parameters, bodies, and annotations, but leaves type variables
  unchanged and has no scoped variable environment. The
  [type parser](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/types.scala)
  permits type variables only in protocol contexts. Extend these existing paths.
- **Constraints:** [ProtocolRef](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/protocols.scala)
  retains protocol names, resolved identities, and source information. Reuse it directly with
  `type TypeConstraint = ProtocolRef`, extensible with `|` for future forms. Each `where` entry
  associates a scoped variable with a `List[TypeConstraint]` of requirements.
  Existing `where` parsing in
  [protocols.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/protocols.scala)
  diagnoses unsupported member constraints; it is not generic constraint support.
- **Explicit type arguments:** [App](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala)
  holds a value-expression argument. `TypeApplication` applies a type constructor and cannot
  represent `identity Int`. Add a distinct representation for type application to a function,
  retaining its callee, ordered type arguments, and source locations.

## Plan (Approval Gate)

Implementation approval: pending for S1–S6. Execute in checklist order and obtain review and
signoff at each stage before advancing. The decisions in Task Working Memory are settled;
the remaining implementation details below are the proposed plan.

Each stage includes its tests. Frontend stages use phase-level fixtures until concrete
instantiation is available; incomplete generic forms must not reach executable lowering
as ordinary monomorphic functions. Compiler handoffs follow the verification rules below.

### S1: AST and parsing

- Extend `TypeVariable` and `TypeScheme` to retain binder identities and ordered generic
  parameters while preserving source spellings. Retain declared constraints on the existing
  function representation as `where` entries pairing a variable with `List[TypeConstraint]`.
- Define `type TypeConstraint = ProtocolRef`, reusing the existing protocol reference node.
  Future constraint forms extend the alias with `|`, for example `ProtocolRef | TypeOpenRecord`;
  only protocol constraints are in scope.
- Introduce function type-application syntax in the term AST, separate from value `App`
  and type-constructor `TypeApplication`. Update traversal and display consumers together.
- Parse type variables in ordinary function signatures and body annotations, optional result
  annotations, `where 'T: Show & Clone, 'U: Show`, and explicit type arguments before values.
  Keep constructor values and ordinary value application working; handle grouped function
  types without introducing alternate surface syntax.

Acceptance: parser fixtures retain the intended nodes and source spans for `identity`,
`render`, multiple variables, and `identity (Int -> Int)`. Malformed constraints and type
arguments receive focused diagnostics with recovery. Existing function and protocol parsing
remains covered.

### S2: Resolution and substitution

- Extend `TypeResolver` with an immutable lexical type-variable environment. Collect new
  variables from each signature in first-appearance order, excluding enclosing binders;
  resolve body annotations and nested signatures against that environment.
- Resolve constraint variables and protocol references. Constraints do not introduce variables;
  unknown variables or protocols produce diagnostics.
- Generalize the existing substitution operation to a map from resolved variable identities
  to types. Apply it through nested types and schemes without capturing unrelated binders.
  Adapt protocol substitution and matching to use the same identities.

Acceptance: repeated occurrences share one binder; unrelated functions using `'T` have
different binders; nested functions preserve enclosing variables and introduce their own.
Ordering includes nested parameter types and the result, counting each variable once.
Substitution preserves nominal identities and source evidence; existing protocol resolution
and substitution tests pass.

### S3: Type checking and inference

- Extend the existing type checker and call-selection machinery to distinguish rigid variables
  while checking a generic definition from substitutions inferred at a use. Retain schemes
  at generic-use boundaries instead of discarding their binders during canonicalization.
- Check protocol operations in generic bodies using declared requirements. Retain enough
  identity and substitution information to choose concrete implementations during
  monomorphization. Validate concrete instances at concrete uses and propagate requirements
  between generic calls.
- Infer substitutions from arguments and expected results or remaining function types.
  Explicit arguments use the settled order and must supply all variables. Use ordinary
  result inference when the return annotation is absent; do not infer undeclared constraints.
- Publish typed instantiation information for `FnMonomorphizer`, including uses that produce
  function values. Keep candidate diagnostics and substitutions isolated.

Acceptance: inferred and explicit calls agree; repeated-variable conflicts, missing type
evidence, wrong type-argument counts, missing constraints, and missing instances are diagnosed.
An unused invalid generic body still fails type/constraint checking. Omitted results, generic
calls within generic bodies, function arguments, and expected-type-directed partial calls work.

### S4: FnMonomorphizer

- Insert `FnMonomorphizer` after `TypeChecker` and before `PartialApplicationElaborator` in
  [SemanticStage](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/SemanticStage.scala).
  Keep checked templates available for instantiation but outside downstream executable bodies.
- Discover concrete uses in executable bindings and follow dependencies in instantiated bodies,
  including function-value uses. Key instances by source function identity and canonical
  resolved type arguments; include enclosing substitutions needed by local functions.
- Reserve an instance before expanding its body. Reuse matching instances; reject a dependency
  cycle returning to the same function at different type arguments, including finite cycles.
  Preserve cycle evidence independently of discovery order or cache reuse.
- Substitute body types, resolve constrained protocol operations, and allocate fresh definition
  identities using [BindingIds](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/BindingIds.scala).
  Remap references and capture identities, preserve source origins and ownership metadata,
  and refresh indexes before downstream passes.

Acceptance: direct calls to `identity` at two concrete types create distinct implementations;
repeated uses reuse one instance. `render` selects the required concrete protocol member.
Same-type direct and mutual recursion work; changing-type cycles produce bounded diagnostics.
No abstract generic template or unresolved generic operation enters executable lowering.
Verify typed identities, LLVM validity, and native results through existing test APIs.

### S5: Callables and ownership

- Complete concrete function-value, partial-application, and local-function paths, preserving
  enclosing type substitutions, captures, shadowing, and ordinary value evaluation order.
- Preserve `FnParam.consuming`, callable origins, capture metadata, and result evidence through
  instantiation. Reuse `CallableValues`, partial-application lowering, closure generation,
  and the existing ownership analyzer; add no abstract ownership checker or ownership syntax.
- Ensure separate instances do not share local definition identities or closure environments.
  Type arguments select an implementation without evaluating its body.

Acceptance: owned identity transfers without duplication or double cleanup; callbacks retain
consuming versus borrowing parameter contracts through aliases, returns, and partial calls.
Conflicting callable contracts, use after transfer, and invalid call-once reuse are rejected
under existing rules. Nested closures and local recursion preserve captures and lifetimes.
Verify cleanup and runtime behavior with sanitizers and retain the existing callable-ownership
regressions, including
[RecursiveCallableOwnershipTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/RecursiveCallableOwnershipTests.scala).

### S6: Documentation and verification

- Check the complete acceptance list in the design draft against implemented tests, including
  error locations, recovery, and source navigation through generated instances.
- Update the language reference and compiler design for implemented generic functions,
  constraint syntax, result inference, monomorphization, and the recursion restriction.
- Run the required compiler verification and review gates, record results and remaining issues,
  and present the source/test code tour for final signoff.

Acceptance: all scoped implementation and verification items have evidence, documentation
matches implemented behavior, and final signoff is explicit.

## Verification

Documentation checks (2026-11-13): all 39 local links in this task and the generic-functions
and intrinsic-arrays drafts resolve, including heading anchors and versioned source references.
Whitespace and tracking consistency checks passed. Compiler verification was not run for
these design and planning changes.

Implementation verification is pending. Stage acceptance checks use existing parser, semantic,
codegen, and runtime test helpers; assertions target types, resolved identities, behavior, and
diagnostics rather than generated symbol spellings.

At compiler handoffs, follow [coding rules](../coding-rules.md#before-closing---post-task-chores)
and local [post-chores](../../.skills/post-chores/SKILL.md): formatting/linting, smoke checks,
the full suite, publishing before benchmarks and memory runs, and required QA/code review.
Run `sbtn` commands sequentially. Include memory/sanitizer checks for ownership and lambda
integration; ABI changes require the specified Linux container checks. Record actual results
at the applicable stage, including failures and unmet gates.

## Risks / Notes

- `FnMonomorphizer` runs after `TypeChecker` and before `PartialApplicationElaborator`.
  Concrete instances continue through closure generation and the existing ownership analyzer.
- Changing-type recursion is unsupported, including finite cycles and mutual recursion
  that returns to a function with different type arguments.
- Preserve existing `FnParam.consuming`, callable origins, capture metadata, and result
  ownership evidence. `CallableValues` recovers callable parameter contracts through value
  flow; generic functions require no new callable-ownership model or abstract ownership checker.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The [draft](../../docs/brainstorming/language/generic-functions.md) is the design input.
Settled decisions:

- Use `where 'T: Show & Clone, 'U: Show`: commas separate variables; `&` joins requirements.
- Use `type TypeConstraint = ProtocolRef`, reusing protocol references directly. Each `where`
  entry associates its variable with a list of requirements using that alias. Future forms
  can extend it with `| TypeOpenRecord`; only protocol constraints are in the initial scope.
- Order explicit type arguments by first appearance, traversing parameter types left to right
  including nested types, then the result type. Count each variable once; exclude enclosing
  variables. Supply all type arguments explicitly or infer all of them.
- Infer omitted result annotations under the same rules as ordinary functions, using the
  body and declared constraints independently of callers.
- Recursive dependency cycles must return to each function with the same resolved type
  arguments. Include function-value dependencies; allow independent nonrecursive uses
  at different types.
- Use monomorphization: instantiate checked generic bodies for concrete type arguments used,
  reusing each implementation for the same function and resolved type arguments.
- Place `FnMonomorphizer` immediately after `TypeChecker`, before partial-application lowering.
- Reuse existing callable-flow and ownership analysis on concrete instances. Abstract type
  and constraint checking does not impose caller-independent callback ownership contracts.

The implementation baseline is documented in [Existing machinery](#existing-machinery-and-required-extensions).
The six-stage plan extends those components and preserves the settled language and ownership
rules. Next action: review the implementation plan for approval; S1 is the first implementation
stage. Target branch remains unassigned until implementation is authorized.
