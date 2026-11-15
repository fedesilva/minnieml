# Generic functions

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** feature
- **Priority:** HIGH
- **Created:** 2026-11-13
- **Target Branch:** dev/generic-functions

## Execution Checklist

1. [x] **complete** — [S1: Extend the AST and parse generic function syntax](#s1-ast-and-parsing).
2. [x] **complete** — [S2: Resolve scoped variables and share type substitution](#s2-resolution-and-substitution).
3. [x] **complete** — [S3: Check generic types and constraints; infer use-site arguments](#s3-type-checking-and-inference).
4. [x] **complete** — [S4: Implement FnMonomorphizer and recursion checks](#s4-fnmonomorphizer).
5. [x] **complete** — [S5: Integrate local functions, callable values, and existing ownership](#s5-callables-and-ownership).
6. [x] **complete** — [S6: Complete documentation, verification, and signoff](#s6-documentation-and-verification).

## Tags

- generics
- type-system
- protocols

## Problem

Generic signatures must preserve relationships between parameter and result types across
calls while checking bodies against their declared constraints. Concrete functions must preserve
those relationships, protocol selections, and existing ownership contracts through execution.
The [generic functions design](../../docs/brainstorming/language/generic-functions.md)
defines the scoped behavior.

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
  include `TypeVariable`, `TypeScheme`, and `TypeApplication`. Variables retain source names
  and optional resolved identities; scheme binders are ordered variable nodes. Lambda nodes
  retain generic parameters and requirements. S2 assigns and preserves scoped binder identities.
- **Traversal and substitution:** [TypeTraversal](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeTraversal.scala)
  visits function parameter types before the result and traverses nested types.
  [TypeSubstitution](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeSubstitution.scala)
  replaces resolved variable identities throughout types while protecting scheme binders.
  Protocol instantiation uses this shared operation; protocol matching also uses binder IDs.
- **Resolution and parsing:** [TypeResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeResolver.scala)
  traverses function parameters, bodies, and annotations with an immutable lexical variable
  environment. Signature collection excludes enclosing variables and parser-generated binding
  scopes. Local initializer resolution preserves shared signature annotation occurrences. The
  [type parser](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/types.scala)
  accepts variables in function signatures and body annotations, including grouped constructor
  applications. Resolution has direct phase-boundary coverage and feeds concrete instantiation
  through the executable pipeline.
- **Constraints:** [ProtocolRef](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/protocols.scala)
  retains protocol names, resolved identities, and source information. Reuse it directly with
  `type TypeConstraint = ProtocolRef`, extensible with `|` for future forms. Each `where` entry
  is a `TypeVariableConstraints` record associating a variable with ordered constraints.
  These declarations live with the type model in `ast/types.scala`; `ProtocolRef` lives with protocols.
  Existing `where` parsing in
  [protocols.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/protocols.scala)
  diagnoses unsupported member constraints; it is not generic constraint support.
- **Explicit type arguments:** [App](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala)
  holds a value-expression argument. `TypeApplication` applies a type constructor and cannot
  represent `identity Int`. `FnTypeApp` retains a callee, ordered candidate type arguments,
  and source locations; named types in ordinary term syntax become explicit arguments
  during reference resolution.

## Plan (Approval Gate)

Implementation approval and scoped signoff: granted for S1–S6. Each high-level item has completed
implementation, verification, and review. Parent task signoff and closure are complete.
The decisions in Task Working Memory are settled; the detailed stages below define the approved scope.

Each stage includes its tests. Frontend stages retain focused phase-level fixtures alongside
full-pipeline tests. Invalid generic forms must not reach executable lowering as ordinary
monomorphic functions. Compiler handoffs follow the verification rules below.

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

S1 verification (2026-11-13):

- `sbtn scalafmtAll` and `sbtn scalafixAll` passed.
- `sbtn testFull` passed 2,695 library tests and 9 CLI tests, including generic parser,
  AST-consumer, guard, and local-function annotation regressions.
- `./tests/smoke/run.sh all` passed all 8 checks.
- `sbtn mmlcPublishLocal` succeeded.
- `make -C benchmark clean` and `make -C benchmark mml` passed all 37 builds.
  No benchmark timing measurements were taken.
- `./tests/mem/run.sh all` passed all 52 ASan+LSan fixtures.
- Local file links and `git diff --check` passed. The QA compliance scan found no violations.
- There are no ABI changes requiring Linux container verification.

Independent S1 review completed four dedicated checks and separate claim verification. Corrections
preserve local return annotations with unannotated parameters, strengthen argument-order/span
and recovered-type-diagnostic assertions, and clarify AST/parser contracts.

Focused review under QA section 11 independently confirmed seven design/readability findings:
constraint-model ownership, grouped-constraint vocabulary, shared parser context and helper names,
the variable-parser contract, the parser-test coverage claim, and pipeline-gate attribution.
All seven corrections are implemented. The verification commands above passed on the corrected
code. Fresh narrow review completed all four dedicated checks with no actionable findings;
one documentation candidate was independently checked and rejected. See
[QA-006](../history/qa-misses-completed.md#qa-006-give-generic-syntax-concepts-clear-names-and-owners).

At later compiler handoffs, follow [coding rules](../coding-rules.md#before-closing---post-task-chores)
and local [post-chores](../../.skills/post-chores/SKILL.md). Run `sbtn` commands sequentially;
include the required ownership, lambda, and ABI checks when those stages change their behavior.

S2 verification (2026-11-13):

- `sbtn scalafmtAll` and `sbtn scalafixAll` passed.
- `sbtn testFull` passed 2,710 library tests and 9 CLI tests. Fifteen new tests cover
  lexical binders, constraints, protocol identity matching, and shared substitution.
- The initial full run found one protocol-resolution assertion requiring unresolved variables.
  The updated assertion checks shared binder identities and preserves source/ownership checks;
  the complete rerun passed.
- `./tests/smoke/run.sh all` passed all 8 checks.
- `sbtn scalafmtCheckAll` and `sbtn mmlcPublishLocal` passed.
- `make -C benchmark clean` and `make -C benchmark mml` passed all 37 builds.
  No benchmark timing measurements were taken.
- `./tests/mem/run.sh all` passed all 52 ASan+LSan fixtures on macOS arm64.
- Diff whitespace and local documentation links passed.
- Independent review completed all four dedicated checks and separately confirmed three
  documentation findings. Corrections explain binding-scope annotations, type-parameter
  membership/order, and variable-resolution identity/recovery. No runtime defect was established.
  See [QA-007](../history/qa-misses-completed.md#qa-007-explain-generic-binder-membership-and-resolution-contracts).
  Fresh narrow review completed all four dedicated checks with no actionable findings.
  The corrections change comments only; formatting and diff checks passed.
- No ABI change requires Linux container verification.

S3 verification (2026-11-14):

- `sbtn scalafmtAll`, `sbtn scalafixAll`, and `sbtn scalafmtCheckAll` passed.
- `sbtn testFull` passed 2,795 library tests and 9 CLI tests after the QA-008 corrections.
  [GenericTypeCheckingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeCheckingTests.scala)
  covers annotation preservation, inherited local requirements, contextual callback inference,
  dependent callbacks, and conjunction failures with resolved-identity and type assertions.
  [GenericInferenceTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericInferenceTests.scala)
  covers complete and partial inference, including missing argument-scheme binders.
- The initial focused build hit a read-only sbt cache artifact through a generated metadata
  symlink. `sbtn clean` cleared the generated outputs; the focused and full reruns passed.
- `./tests/smoke/run.sh all` passed all 8 checks before local publication.
- `sbtn mmlcPublishLocal` succeeded.
- `make -C benchmark clean` and `make -C benchmark mml` passed all 37 builds.
  No benchmark timing measurements were taken.
- `./tests/mem/run.sh all` passed all 52 ASan+LSan fixtures on macOS arm64.
- Diff whitespace, local documentation links, and the QA compliance scan passed.
- The original independent review completed all four dedicated checks and separately
  confirmed eight findings recorded in [QA-008](../history/qa-misses-completed.md#qa-008-preserve-generic-use-site-evidence-and-local-requirements).
  The correction review completed all four dedicated checks and separately confirmed five
  test/QA/documentation findings: duplicated test helpers and local selectors, an overly broad
  failure assertion, ambiguous type-name prose, and the missing local-template field contract.
  These corrections consolidate all 47 integration cases in one suite, tighten the missing-type
  diagnostic assertion, and clarify the documentation. Formatting, linting, the full suite,
  and local publication passed again. Narrow review verified test preservation and corrected
  one further documentation error: value alternatives require every name in the selected prefix.
  Fresh review of that correction and QA-009 completed all four dedicated checks with no
  actionable findings. The runtime gates above cover the unchanged compiler behavior.
- Generic behavior is verified through phase APIs. The executable pipeline guard remains
  until concrete instantiation is implemented. No ABI change requires Linux container checks.

S4 dependency-model checkpoint (2026-11-14):

- `FnInstantiationDependenciesTests` passes all 12 tests. These exercise the dependency model,
  not frontend discovery, emitted diagnostics, LLVM, or execution of generic functions.
- Initial build attempts failed on a missing `CanEqual` declaration and inconsistent source
  snapshots during compilation. The equality declaration is corrected; the separate focused
  test invocation passes.
- `sbtn scalafmtCheckAll` and `sbtn scalafixAll` passed. `sbtn testFull` passed
  2,807 library tests and 9 CLI tests.
- This checkpoint covers the isolated dependency model. Full S4 verification and independent
  review remain pending; see Task Working Memory for the current integration state.

S4–S6 integrated verification (2026-11-14):

- `./tests/mem/run.sh all` passed all 52 ASan+LSan fixtures on macOS arm64.
- Final QA compliance, documentation links/anchors, tracking consistency, and `git diff --check`
  passed. No implementation or verification items remain; Author signoff is pending.
- `sbtn scalafmtAll`, `sbtn scalafmtCheckAll`, and `sbtn scalafixAll` passed.
- Final `sbtn testFull` passed 2,840 library tests and 9 CLI tests, with no failures.
  The focused S4/S5 run passed 31 tests, including eight native O0/O3 ASan+LSan cases.
- An initial diagnostic assertion expected `Int`; the canonical printed type is `Int32`.
  The corrected assertion checks the full typed recursion path; focused and full reruns pass.
- `./tests/smoke/run.sh all` passed all eight checks. An initial sandboxed invocation could
  not access the sbt boot lock; the rerun with cache access passed all eight commands.
- `sbtn mmlcPublishLocal` succeeded after smoke verification. Both development and installed
  compiler runs of `mml/samples/generic-functions.mml` print `42`, `true`, `7`.
- `make -C benchmark clean` and `make -C benchmark mml` passed all 37 builds.
  No benchmark timing measurements were taken.
- S4 independent review completed all four dedicated checks and four separate claim verifiers;
  S5 completed all four checks and five separate claim verifiers. The confirmed documentation,
  readability, and test-coverage findings are recorded in [QA-010](qa-misses.md#qa-010-explain-specialization-records-and-test-recursion-through-cached-instances)
  and [QA-011](qa-misses.md#qa-011-verify-local-specialization-identities-and-capture-execution).
  Corrections pass a fresh narrow review with all four dedicated checks and no actionable findings.
- S6 independent review completed all four dedicated checks with no actionable findings.
  Reviews did not rerun builds; execution evidence comes from the commands above.

Type-argument precedence verification (2026-11-15):

- Type names in the explicit generic prefix take precedence over constructor values. The
  dual-reading field, checker branch, and ambiguity diagnostic are removed. Parenthesized names
  retain type precedence; value ascriptions and aliases retain ordinary value application.
- `sbtn scalafmtAll`, `sbtn scalafixAll`, and `sbtn scalafmtCheckAll` passed.
- Focused resolution, type-checking, and callable-ownership suites passed all 81 tests.
  The initial negative test incorrectly demanded a concrete type from a secondary diagnostic
  containing rigid `'T`; it now checks the specific inference conflict and both concrete type IDs.
- `sbtn testFull` passed 2,848 library tests and 9 CLI tests. Two added native cases exercise
  type selection and constructor values at O0/O3 with ASan+LSan.
- Development and installed sample runs print `42`, `true`, `7`. The exact top-level
  `let unresolved = identity String;` compiles with the installed compiler. All nine commented
  sample counterexamples fail independently with their documented diagnostic.
- Smoke checks passed 8/8 before successful local publication. Benchmark clean/build passed all
  37 builds; the full memory harness passed all 52 ASan+LSan fixtures on macOS arm64.
- No ABI conventions changed. No Linux execution or performance measurements are claimed.
- Focused QA, local links/anchors, and diff whitespace checks pass. Independent correction review
  completed all four dedicated checks and two separate claim verifications. Two documentation
  findings are recorded in [QA-014](qa-misses.md#qa-014-explain-generic-prefix-arity-and-function-value-checking).
  Both comments are corrected and formatting passes. Narrow review separately confirmed one
  further wording error distinguishing protocol validation from signature checking; its correction
  passes a fresh review with all four dedicated checks and no actionable findings. Executable
  behavior is unchanged by the comment corrections.

Additive specialization verification (2026-11-15):

- Top-level checked templates remain module members; local checked templates retain their source
  identities in the existing `ResolvablesIndex`. Ordinary function bodies are not snapshotted.
  Rewritten uses retain source identities separately from their concrete execution targets.
- Source navigation, references, hover, symbols, and tokens have regressions for distinct
  instantiations, unused and shadowed locals, function values, partial calls, and explicit type
  tokens. A generic `main` specialization cannot supply the program entry point.
- Initial focused runs exposed incorrect fixture columns, test compilation errors, and runtime
  lookups that still require generated function names. Those corrections pass focused reruns.
  Editor names are obtained from the source declaration through the existing index.
- `sbtn testFull` passed 2,855 library tests and 9 CLI tests. Formatting and linting passed.
  Smoke passed 8/8 before local publication; development and installed sample output is
  `42`, `true`, `7`. Benchmark clean/build passed all 37 builds; the memory harness passed
  all 52 ASan+LSan fixtures on macOS arm64 with LLVM 23.1.1.
- The final focused rerun passed 75 tests after comment and Option-syntax normalization;
  formatting, linting, sample execution, and local publication also pass. No performance
  measurements or Linux execution are claimed; calling conventions and native ABI handling
  are unchanged.
- Independent S4–S6 correction review completed all four dedicated checks. Separate fresh
  verifiers confirmed two documentation gaps recorded in
  [QA-015](qa-misses.md#qa-015-explain-retained-templates-at-editor-and-ownership-boundaries).
  The comments explain local-template token handling and return discovery's executable-member
  precondition. Formatting passes; fresh narrow review completed all four dedicated checks
  with no actionable findings. No runtime defect was established by the independent review.
- Final QA compliance, tracking consistency, local links/anchors, and diff whitespace checks pass.
  S4–S6 implementation, verification, and scoped Author signoff are complete.

### Acceptance coverage

The design's acceptance examples map to these versioned fixtures. Phase assertions, LLVM
validation, native execution, and sanitizer observations establish different properties.

| Requirement | Evidence | Verification level |
| --- | --- | --- |
| Syntax, argument order, spans, malformed-input recovery | [GenericFunctionParserTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/GenericFunctionParserTests.scala) | Parser nodes, source spans, diagnostics and recovery |
| Lexical binders and capture-free substitution | [GenericTypeResolutionTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeResolutionTests.scala), [TypeSubstitutionTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/ast/TypeSubstitutionTests.scala) | Resolved identities and types |
| Inferred/explicit agreement, compound arguments, repeated-variable conflicts, complete argument counts, omitted results | [GenericTypeCheckingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeCheckingTests.scala), [GenericPipelineTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericPipelineTests.scala) | Typed phase and full semantic pipeline assertions |
| Multiple constraints, missing body requirements, missing concrete instances, forwarding constraints | [GenericTypeCheckingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeCheckingTests.scala) | Positive and negative typed/diagnostic assertions |
| Function values, partial applications, insufficient evidence | [GenericInferenceTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericInferenceTests.scala), [GenericCallableOwnershipTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericCallableOwnershipTests.scala) | Inference and ownership assertions; partial-call evaluation order at O0/O3 |
| Type-name precedence, grouped names, no constructor fallback, explicit constructor values | [GenericTypeArgumentResolutionTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeArgumentResolutionTests.scala), [GenericTypeCheckingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeCheckingTests.scala), [GenericCallableOwnershipTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericCallableOwnershipTests.scala) | Resolved type IDs, concrete function types, specific conflicting-type diagnostics, native O0/O3 sanitizers |
| Reuse, distinct concrete implementations, protocol selection, retention of unused templates | [FnMonomorphizerTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/FnMonomorphizerTests.scala) | Concrete and source definition/reference identities and LLVM validation |
| Direct/mutual recursion, changing-type paths, finite cycles, function-value dependencies, cached callees | [FnInstantiationDependenciesTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/FnInstantiationDependenciesTests.scala), [FnMonomorphizerTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/FnMonomorphizerTests.scala) | Graph invariants, executable lowering, bounded diagnostics with type/path/source evidence |
| Owned transfer, callback contracts, call-once restrictions, captures and local recursion | [GenericCallableOwnershipTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericCallableOwnershipTests.scala) | Positive/negative ownership checks; ten native O0/O3 ASan+LSan cases |
| Local identities, shadowing and enclosing substitutions | [FnMonomorphizerTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/FnMonomorphizerTests.scala), [GenericCallableOwnershipTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericCallableOwnershipTests.scala) | Distinct binding IDs, matching self/continuation references, native captured values |
| Source declarations retained in the existing index for editor queries | [GenericNavigationTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/GenericNavigationTests.scala) | Source-definition/reference identities, hover, symbols, unused/shadowed locals, function values and partial calls |
| Runnable user example | [generic-functions.mml](../../mml/samples/generic-functions.mml) | Native output: `42`, `true`, `7` |

Sanitizer results cover executed paths, not a proof of every possible lifetime. Generic-specific
hover, references, semantic tokens, symbols, shadowing, function values, and partial applications
have direct editor-query assertions. Mixed protocol navigation uses the existing regression suites;
generic token-end navigation has static review coverage. Benchmark builds are compatibility
checks; no performance measurements or Linux execution are claimed. Instantiation uses the existing
ABI and callable/ownership lowering without changing calling conventions, so the ABI-specific
Linux-container gate does not apply.

## Risks / Notes

- S4 places `FnMonomorphizer` after `TypeChecker` and before `PartialApplicationElaborator`.
  Concrete instances continue through closure generation and the existing ownership analyzer.
- Changing-type recursion is unsupported, including finite cycles and mutual recursion
  that returns to a function with different type arguments.
- Preserve existing `FnParam.consuming`, callable origins, capture metadata, and result
  ownership evidence. `CallableValues` recovers callable parameter contracts through value
  flow; generic functions require no new callable-ownership model or abstract ownership checker.

## Signoff

- S1 signoff: granted, including QA-006.
- S2 signoff: granted, including QA-007.
- S3 signoff: granted, including QA-008 and QA-009.
- S4–S6 signoff: granted, including QA-010, QA-011, QA-014, and QA-015.
- Tracked item signoff and closure: granted; all scoped work is complete.
- Commit authorization: granted for S1–S6, their QA corrections, supporting documentation,
  bookkeeping, and the toolchain formatting and QA comments. Parent closure and squash delivery
  to `main` are authorized.

## Task Working Memory

Additive specialization is approved: retain checked generic declarations, use the existing
resolvables index for local templates and source navigation, and remove the saved source module.
Implementation and compiler verification pass. Independent S4–S6 review and fresh narrow review
of QA-015 are complete. S4–S6 and their QA corrections have scoped signoff and local commit
authorization. Parent signoff and closure are complete. Squash delivery to `main` is authorized;
no implementation or verification work remains.

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
rules. S1 implementation is authorized on `dev/generic-functions`, created from `main` at
`03cfda6e`, without remote publication. Parser, AST, diagnostic, display, and editor changes
are implemented, including the S1 naming and placement corrections, with compiler verification
and independent review complete. S1–S3 and QA-006 through QA-009 are signed off and complete.
S4–S6 implementation, self-review per high-level item, independent reviews, and scoped Author
signoff are complete.

Approved type-argument precedence: in a generic application's explicit type-argument prefix,
a name resolving to a type denotes that type, even when the same name denotes a constructor.
The checker does not retry a constructor-value interpretation when type application fails.
A value ascription such as `identity (Person: Int -> Person)` or a value alias passes the
constructor explicitly. Ordinary non-generic calls and constructor values after a complete
explicit type-argument prefix keep their value meaning.

The precedence correction is implemented within S6. Dual-reading metadata and its obsolete
diagnostic are removed. Resolution/checking regressions, native examples, and language/design
documentation follow the precedence rule. All 81 focused tests, 2,848 library/9 CLI tests,
eight smoke checks, 37 benchmark builds, and 52 memory fixtures pass. Focused independent
review and fresh narrow reviews of the QA-014 comment corrections are complete.

S2 implements lexical variable resolution, constraint lookup, and shared identity-based
substitution. Parser-generated local binding and statement lambdas are marked as binding scopes;
they cannot introduce type variables. Initializers resolve before continuations so cached local
function signatures retain their binder identities without exporting them. Generic resolution
and checking have focused phase-level fixtures as well as executable pipeline tests.
Compiler verification and independent review are complete, including
fresh narrow review of the three documentation corrections. S3 implements rigid-variable
compatibility, declared protocol requirements, and use-site substitution inference.

The committed parser typo in `mkScopedBinding` is corrected under explicit authorization.
Independent review completed all four dedicated checks and separately verified eight findings,
recorded in [QA-008](../history/qa-misses-completed.md#qa-008-preserve-generic-use-site-evidence-and-local-requirements).
The behavioral and test/QA/documentation corrections pass verification and independent review.
S4 implements concrete instantiation and dependency checks, including cached callees and
function-value uses. S5 preserves local identities, captures, callable contracts, and evaluation
order. The additive-specialization correction retains source templates in the existing index and
adds source identity to rewritten references. Top-level templates remain module members;
local templates remain indexed outside runtime binding sites. Concrete lowering and LLVM
emission exclude templates, and generated specializations cannot become the program entry point.

S6 includes the type-name precedence rule, language/compiler documentation, the runnable sample,
and editor queries through the single module/index. Focused additive-specialization checks passed
72 tests, followed by 22 editor tests including function values, partial application, and explicit
type-argument tokens. The full run including the entry-point regression passed 2,855 library and
9 CLI tests; all eight smoke checks, 37 benchmark builds, and 52 memory fixtures pass. The final
75-test focused rerun and QA checks pass. Independent S4–S6 correction review and fresh narrow
review of QA-015 are complete, including all four dedicated checks. No actionable findings
remain. S4–S6 have scoped signoff and local commit authorization. Parent task signoff and closure
are complete.

Completed documentation correction:
[QA-009](../history/qa-misses-completed.md#qa-009-explain-how-bindingidswithin-chooses-an-allocation-scope) explains
`BindingIds.within`'s ID-prefix contract. Formatting and independent documentation review pass;
the operation's behavior is unchanged. QA-009 is signed off with S3.
