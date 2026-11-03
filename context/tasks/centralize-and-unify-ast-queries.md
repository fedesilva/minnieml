# Centralize, and Unify ast queries

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** improvement
- **Priority:** unassigned
- **Created:** 2026-10-29
- **Target Branch:** main
- **External Reference (optional):** None.

## Execution Checklist

1. [x] **complete** — Inventory AST queries and settle the first operator-query boundary and contracts.
2. [x] **complete** — Make operator grouping attributes mandatory in binding metadata.
3. [x] **complete** — Approve the bounded operator-query extraction plan.
4. [x] **complete** — Extract operator queries and migrate coherence checking and rewriting.
5. [x] **complete** — Verify operator behavior preservation, update architecture documentation, and review.
6. [x] **complete** — [Consume grouping outcomes directly in ExpressionRewriter](#follow-up-consume-grouping-outcomes-directly).
7. [x] **complete** — [Extract declaration queries and move OperatorQueries to ast.queries](#step-7-declaration-queries-and-operator-package-move).
8. [x] **complete** — [Share scope construction and name matching; simplify registry lookup](#step-8-scope-and-name-queries).
9. [x] **complete** — [Settle type-query domains and migrate their callers](#step-9-type-query-domains).
10. [x] **complete** — Separate Clone target selection from call construction, preserving unavailable-target behavior.
11. [x] **complete** — Review destruction-target and memory-function queries, retaining or refining their existing shared boundaries.
12. [x] **complete** — Separate callable structural queries from value-flow analysis for origins, application structure, and parameter ownership.
13. [x] **complete** — Identify reusable cursor, definition, reference, and source-span queries while preserving LSP policy and recovery.

## Tags

- architecture
- ast
- protocols

## Problem

Reusable AST queries are distributed among phase objects and helper modules, making shared
operations difficult to discover. Shared queries need explicit contracts and owners independent
of compiler phases. Operator candidate inspection and grouping establish the first shared domain;
other query families need their own boundary and migration decisions.

## Outcome

Domain-specific query modules live in a discoverable subpackage with a consistent pattern.
Compiler phases share queries without depending on one another's implementations. Equivalent
queries use one implementation, with their inputs, results, and failure contracts made explicit.

## Scope

- Inventory AST queries, their callers, duplicated logic, and dependency direction across
  compiler and editor code. Distinguish queries from traversals, analyses, and code construction.
- Start with operator queries: separate reusable lookup and coherence facts from module traversal
  and diagnostic accumulation. Keep the coherence phase and expression rewriter using shared rules.
- Use `ast.queries` for shared queries over AST nodes and indexes. Operator and declaration
  queries use that package; broader migrations retain their separate scope decisions.
- Use explicit AST, index, and registry inputs. Queries return facts or typed lookup failures;
  phase execution, `CompilerState` updates, and replacement-code generation stay with their owners.
- Examine other query/construction boundaries, including selection of a Clone target versus
  construction of a Clone call, before selecting further bounded migrations.
- Preserve resolved identities, candidate sets, diagnostic causes, and partial-AST behavior.
  Language semantics, new derivation features, and a general query caching framework are out of scope.

## Plan (Approval Gate)

### Query inventory

This source inventory identifies query families and boundaries for staged migration. It is not
an exhaustive audit of private helpers or evidence that similarly named queries are equivalent.

| Family | Current home and consumers | Proposed treatment |
| --- | --- | --- |
| Operator classification, candidates, precedence, and associativity | [OperatorQueries](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/OperatorQueries.scala); consumed by OperatorCoherence and ExpressionRewriter | Shared query extraction implemented; preserve the division between facts, diagnostics, and AST construction. |
| Declaration metadata, source names, and instance-member IDs | [DeclarationQueries](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/DeclarationQueries.scala); used by registration, coherence, ID assignment, diagnostics, and entry-point selection | AST facts extracted; registry operations retain their owner. |
| Protocol visibility, candidates, and concrete implementations | [ValueScope](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/ValueScope.scala), DeclarationQueries, and ProtocolRegistry instance methods | Shared AST scope/name queries; registry retains strict lookup, typed failures, and authoritative associations. |
| Type canonicalization, declarations, native representation, and destruction requirements | [TypeQueries](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/TypeQueries.scala), NativeTypeQueries, TypeFailureQueries, and TypeMemoryQueries; used across parsing, semantics, and codegen | Four shared domain owners extracted in [step 9](#step-9-type-query-domains); execution checks, independent review, and Author signoff are complete. |
| Clone target selection and call construction | [CloneTargets](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CloneTargets.scala) and [CloneCalls](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CloneCalls.scala); used by struct generation and ownership analysis | Registry-backed selection is separate from call construction; missing or rejected instances remain unavailable targets. |
| Destruction targets and memory-function lookup | [DestructionTargets](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/DestructionTargets.scala) and [MemoryFunctions](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/MemoryFunctions.scala); used by ownership, destructor generation, and codegen | Both focused query owners live in ast.queries; selection order and pending-generation behavior are preserved. |
| Callable origins, application structure, and parameter ownership | [ApplicationQueries](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/ApplicationQueries.scala), CallableValues, and CallStages | Shared structural queries serve semantic and codegen consumers; value-flow, parameter agreement, and invocation availability retain their analysis owners. |
| Cursor lookup, definitions, references, and source spans | [AstLookup](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/AstLookup.scala); used by LSP features | Share source-span facts and canonical alias traversal; keep cursor selection, reference policy, and guarded source-name recovery in LSP. |

### Query-family boundaries

Phases 9–13 have explicit query owners, inputs, results, identity, ordering, and failure/recovery
contracts. Step 13 records the deliberate editor recovery improvements. Existing shared boundaries
are retained where their contracts differ. Implementation, execution verification, independent
review, and combined Author signoff are complete.

### Shared query package and operator contracts

The approved destination is `ast.queries`. Its queries depend on AST nodes and indexes, not
semantic phases, CompilerState, or registry state. `OperatorQueries` uses DeclarationQueries
for metadata inspection. Other AST helpers retain their existing locations until their inventory
steps establish a destination.
Organize query modules by domain, with explicit inputs.
Queries return facts or typed lookup failures. Phase execution, diagnostic accumulation,
CompilerState updates, and AST construction remain with callers.

Operator queries should share candidate analysis for successful grouping and conflict reporting.
Distinguish no matching operator, coherent grouping, and conflicting grouping. Return candidate
identities and grouping attributes; ExpressionRewriter constructs the narrowed reference.
Preserve candidate order, arity filtering, diagnostic causes, and partial-AST behavior. A single
protocol signature must remain unresolved until operand types select an implementation.

The approved outcomes are `Absent`, `Coherent` with ordered candidates and grouping attributes,
and `Conflict` with declaration pairs. Operator metadata carries
mandatory precedence and associativity; missing associativity is not a query outcome. Registry
associations remain authoritative; the query layer must not copy them or depend on phase objects.

### Approved prerequisite: complete operator metadata

`BindingMeta.kind` stores a `BindingKind` whose `Operator` case owns mandatory precedence
and associativity. Constructor defaults are precedence 50 and left associativity. Parsers
preserve arity-specific defaults, including right associativity for unary operators; instance
members inherit the signature's metadata. Other callable kinds carry no grouping attributes.
Derived metadata accessors support shared classification and presentation. Operator coherence
consumes the concrete operator case directly.

Scope: migrate metadata producers and consumers, verify the type restriction and existing
operator behavior, and review. The operator-query extraction is approved as a separate bounded slice.

### Step 7: declaration queries and operator package move

Package decision and bounded implementation plan: approved. Implementation, verification,
independent review, and scoped signoff: complete.

Use one `ast.queries` package for this slice. AST facts serve semantic processing, diagnostics,
and codegen; their callers do not determine their package. Registry associations remain owned
by `ProtocolRegistry`. No second query package or forwarding compatibility layer is needed.

1. Add `ast.queries.DeclarationQueries` and extract these three existing operations from the
   `ProtocolRegistry` companion, preserving signatures and behavior:
   - `metadata(Resolvable): Option[BindingMeta]`: return a binding's optional metadata or a
     protocol signature's metadata; return None for other nodes.
   - `sourceName(Resolvable): String`: return metadata.originalName when available, otherwise
     node.name. This is source spelling, not resolved identity or registry validation.
   - `loweredMemberIds(Module): Set[String]`: collect memberIds from the module's direct
     protocol-instance members, deduplicated as a set. Do not recursively traverse, consult
     the index, or filter by registration success. These IDs describe recorded AST facts.
2. Move `OperatorQueries` from `semantic.queries` to `ast.queries`, replacing its sole
   semantic dependency, `ProtocolRegistry.metadata`, with `DeclarationQueries.metadata`.
   Preserve candidate order, identities, arity filtering, reference deduplication, missing-node
   recovery, same-name preconditions, and Absent/Coherent/Conflict results.
3. Migrate every production and test caller. Declaration-query consumers include
   ProtocolRegistry, ProtocolLookupScope, ProtocolChecker, IdAssigner, OperatorCoherence,
   ProtocolError, codegen EntryPoint, and PreCodegenValidatorSuite. Operator-query consumers
   include OperatorCoherence, ExpressionRewriter, and ProtocolGroupingTests. Remove the old
   helper definitions and old package imports after migration.
4. Update the operator-query description and source-tree overview in
   [compiler-design.md](../../docs/design/compiler-design.md), plus this task's current inventory
   links, to match the implemented locations. Document the AST-only dependency boundary and
   preserve caller ownership of diagnostics, AST construction, and entry-point policy.
5. Verify behavior and complete compiler handoff review, then stop for scoped signoff.

Excluded: registry association/candidate APIs, resolveDeclaration, typeKey, TypeUtils, Clone,
destruction, callable analysis, and LSP extraction. Those retain their existing owners and
separate inventory decisions. No language, diagnostic, ABI, or ownership changes are intended.

Verification and acceptance:

- Inspect existing declaration, grouping, protocol, and entry-point coverage. Preserve its
  assertions; add focused semantic regressions only for uncovered contracts, especially source
  name fallback and exclusion of instance implementations from executable entry selection.
  Do not add tests that merely repeat the extracted match expressions.
- Confirm no imports of semantic code remain in ast.queries, no old helper callers remain,
  and no executable source or test imports semantic.queries. Keep historical evidence intact.
- Run `sbtn "scalafmtAll; scalafixAll; testFull"`, then `./tests/smoke/run.sh all`.
  After smoke success, run `sbtn mmlcPublishLocal`, `make -C benchmark clean`, and
  `make -C benchmark mml`, sequentially. Record results and compiler warnings accurately.
- Run local post-chores, including QA enforcement and independent code review, for the compiler
  handoff. No ABI or ownership/lambda behavior changes are planned; Linux ABI verification and
  the full memory harness apply only if implementation actually touches those boundaries.
- Check documentation links and `git diff --check`; reconcile step 7 evidence and working memory.
  Later-family decisions and the authorization to complete phases 9–13 are recorded below.

### Step 8: scope and name queries

Implementation, execution verification, correction reviews, and scoped signoff complete.
Shared scope and name facts belong in `ast.queries`; registration, signature ownership, concrete
implementation lookup, and rejection evidence stay in ProtocolRegistry.

1. Move ProtocolLookupScope to `ast.queries.ValueScope`, including module construction and nested
   parameter levels. Preserve ID order, omission of unassigned IDs, and exclusion of lowered
   instance implementations. This scope describes value lookup, not types or access permissions.
2. Add DeclarationQueries.matchesName: bindings match actual or original names; other resolvable
   nodes match sourceName. Move lookupNames from semantic/package.scala into DeclarationQueries,
   preserving its module-binding-only result and using the shared matcher. Migrate its test callers.
   Use the same matcher in registry lookup and the binding branch of duplicate-name checking.
3. Replace equivalent source-name computations in duplicate-name checking, protocol diagnostics,
   type-checker diagnostics, and editor lookup with DeclarationQueries.sourceName. Preserve the
   callers' classification, display, and recovery policies.
4. Remove the test-only ProtocolRegistry.candidates arity API and its private accepts plumbing.
   Keep visibleCandidates as the registry's lexical lookup. Preserve strict index validation,
   first-occurrence deduplication, nearest-name shadowing, signature registration checks, and
   rejected-declaration causes. Arity selection remains in OperatorQueries.
5. Migrate registry tests to visibleCandidates and the actual operator-grouping path. Preserve
   behavioral assertions and add focused controls for strict visited-level index validation and
   hidden outer scopes. Inspect scope construction coverage; avoid tests that repeat predicates.
6. Update affected architecture documentation and complete compiler checks, independent review,
   and scoped signoff.

Verification: formatting, lint, full test suite, smoke collection, local publishing, and benchmark
builds under the repository rules. Extra ABI-container and full memory-harness checks apply only
if their respective behavior boundaries change. Record actual results and any unmet gates.

Retained boundary: protocol/signature/instance/member lookups and signatureCandidates rely on
registry associations and typed failures. Moving them into ast.queries would introduce semantic
state dependencies or duplicate authoritative state. ProtocolChecker's raw instance-member shape
matching retains its distinct pre-metadata contract. Later inventory steps retain separate scope.

### Step 9: type-query domains

**Status:** complete. Implementation, execution checks, independent review, and Author signoff are complete.

Type-property queries use AST nodes and indexes without phase or registry dependencies.
Their four owners live in `ast.queries`:

| Owner | Operations | Boundary |
| --- | --- | --- |
| `TypeQueries` | `canonical`, `declaration`, `getTypeName` | Alias expansion and nominal declarations; display spelling remains separate from lookup identity. |
| `NativeTypeQueries` | `resolveNativeType`, `isPointerNativeType`, `isPointerLike` | Native representation recorded in the AST, independent of allocation, target layout, and LLVM emission. |
| `TypeFailureQueries` | Both `containsInvalidType` overloads, `invalidTypeIds` | Explicit parse/resolution failures, independent of whether a value is usable. |
| `TypeMemoryQueries` | `requiresDestruction`, `isHeapType`, `hasHeapFields`, `isStructWithHeapFields`, `containsFunction`, `freeFnFor` | Recursive type properties and destructor names, independent of value ownership and registered target selection. |

These boundaries distinguish facts that callers must not conflate: a pointer need not own storage,
a type without explicit failures need not be usable, and a destructor name does not establish a
registered target. Native and memory queries use TypeQueries; failure discovery retains its own
AST traversal. Keep the mutually recursive destruction/heap checks and their private native-heap
classification together in TypeMemoryQueries. TypeTraversal and TermTraversal retain their owners.

Contracts to preserve:

- `canonical` follows resolved alias IDs, single-element groups, and all schemes. It prefers an
  alias's computed `typeSpec` over `typeRef`, returns `None` for alias cycles, and preserves a
  reference whose identity is absent or missing from the index. It does not validate index identity.
  `declaration` then selects an indexed nominal declaration or a direct TypeStruct. Neither guesses
  from names. `getTypeName` reads written nominal spelling without canonicalizing it.
- Native lookup returns a direct NativeType or the direct native `typeSpec` of a resolved TypeDef.
  Pointer classification accepts NativePointer and the opaque `ptr` primitive. Backend allocation
  attributes, Unit lowering, target layout, and ABI classification remain caller decisions.
- Failure discovery follows alias `typeRef`, both TypeDef annotations, struct fields, compound
  types, and annotations inside refinements. It deliberately does not substitute cached alias
  `typeSpec`. Missing identities and cycles alone are not explicit failures. The summarized-ID
  overload must use failure IDs from the same declaration index; ValueAvailability owns reuse.
- Memory queries preserve nominal allocation effects and recursive struct identity guards.
  A direct function requires destruction but is not itself a nominal heap type; a struct containing
  a function is classified for cleanup. These facts do not establish ownership of a particular
  value. `freeFnFor` preserves explicit native destructors and declaration-based default names.
  MemoryFunctions retains registered/pending target selection and owning-module policy; step 11
  moves that focused query owner into ast.queries.

Retained boundaries and follow-ups:

- [TypeResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeResolver.scala)
  constructs alias metadata from reference chains and preserves nominal targets. It is not a query
  relocation. TypeChecker's struct lookup also includes name recovery and NativeStruct-to-TypeStruct
  construction; its value-alias inference is a separate operation.
- [ProtocolRegistry.typeKey](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ProtocolRegistry.scala)
  validates indexed identities, rejects quantified schemes, normalizes built-in Unit, and reports
  typed failures. TypeCompatibility and ValueAvailability retain comparison and usability policy.
- [DestructionValidator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DestructionValidator.scala)
  rejects missing nominal references and does not unwrap schemes. Its proposed canonicalization
  unification belongs to [destruction contract generalization](destruction-contract-generalization.md),
  where those differences need an explicit decision.
- [Backend type helpers](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/package.scala)
  retain LLVM mapping and NativeStruct construction. `resolveToTypeFn` also differs from canonical:
  it leaves schemes intact, retries alias `typeRef` after an unsuitable cached result, and has no
  cycle guard. Step 12 retains that distinct callable-query contract instead of substituting
  canonicalization; application-chain collection is shared independently.
- [PreCodegenValidator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/PreCodegenValidator.scala)
  uses name recovery and entry-type policy. Retain that behavior in this migration; replacing its
  alias resolver needs a separate contract decision. AstLookup uses shared canonical traversal
  and retains guarded source-name recovery for incomplete editor indexes, as described in step 13.
  PreCodegenValidator's unguarded alias recursion remains a source-level concern, not a reproduced
  reachable failure.

Approved implementation and acceptance:

1. Extract the four owners, preserving public operation names, inputs, results, and private
   recursion. Migrate all production and test callers and remove TypeUtils without a forwarding
   layer. Add concise public contracts where needed; introduce no query cache or policy switches.
2. Replace the equivalent constructor-result lookup in
   [CallableTargetAnalysis](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/CallableTargetAnalysis.scala)
   with `TypeQueries.declaration(...).collect { case struct: TypeStruct => struct }`. Its current
   canonicalization followed by direct-struct or indexed-reference selection has the same result.
   Keep value-flow analysis and constructor eligibility in the caller; no new struct adapter is needed.
3. Update the type-query sections and source-tree overview in compiler-design.md. Repair direct
   source links affected by the move, including the QA spacing example, without rewriting historical
   verification claims or unrelated task scope. Reconcile this task's inventory and working memory.
4. Preserve existing semantic assertions. TypeUtilsTests covers alias chains, same-name identities,
   missing identities, recursive aggregates, cycles, destructor names, and target ownership.
   ParseTypeErrorTests covers compound/refinement failures, recursion, summarized-ID agreement,
   original alias bodies, and both nominal annotations. FunctionSignatureTest covers native pointer
   attributes; LocalCallableCodegenTests covers known targets through constructor fields. Add focused
   regressions only if migration exposes an uncovered contract; avoid predicate-mirroring tests.
5. Run `sbtn "scalafmtAll; scalafixAll; testFull"`, then `./tests/smoke/run.sh all`. After smoke
   success, run `sbtn mmlcPublishLocal`, `make -C benchmark clean`, `make -C benchmark mml`, and
   `./tests/mem/run.sh all`, sequentially. The memory harness covers the relocated destruction
   classification and its consumers. No ABI changes are planned; Linux ABI-container checks apply
   if implementation reaches that boundary. Check imports, obsolete callers, source links, and
   `git diff --check`; complete local post-chores. Step 9 review is included in the final Author review under the
   2026-11-03 authorization to continue through the remaining phases.

Relevant behavior-preservation suites are
[TypeUtilsTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeUtilsTests.scala),
[ParseTypeErrorTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ParseTypeErrorTests.scala),
[FunctionSignatureTest](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/FunctionSignatureTest.scala),
and [LocalCallableCodegenTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/LocalCallableCodegenTests.scala).
Execution and review results are recorded below. Steps 10–13 follow the query-family boundaries
above. The separate destruction task retains its own scope and approval gate.

### Steps 10–11: Clone and destruction targets

- `semantic.CloneTargets` owns the Clone protocol/signature identities and registry-backed
  selection. Missing or rejected instances yield no target. `CloneCalls` retains the unavailable
  diagnostic and typed call construction; selection-only callers use the new query directly.
  Registry state remains authoritative and does not enter `ast.queries`.
- Move MemoryFunctions and DestructionTargets into `ast.queries`, preserving their focused APIs.
  MemoryFunctions selects registered owning-module bindings, allows pending struct helpers only
  for the generating module, then considers an explicitly named native stdlib destructor.
  DestructionTargets preserves environment-layout matching and destructor-origin name priority.
- Preserve existing owner-identity, alias, native fallback, generation, and closure-layout tests.
  Add focused availability/precedence coverage only where the extraction lacks a behavioral guard.

### Step 12: callable structure and analysis

- `ast.queries.ApplicationQueries` owns application-chain nodes, ordered arguments, maximal
  application traversal, and canonical callee-signature lookup. A node's own type takes precedence
  over the indexed declaration type, including when that type is unsuitable as a function.
- CallStages retains value-availability checks and completed-invocation decisions. CallableValues
  retains origin propagation, lexical substitutions, cycle closure, and agreement between possible
  parameter-ownership contracts. These facts require analysis rather than structural inspection.
- Replace equivalent codegen argument-chain helpers with the shared query. CallableValues uses
  TypeQueries.declaration for the same direct/indexed struct lookup as CallableTargetAnalysis;
  constructor-field identity and argument-flow decisions stay in the analysis. Keep backend
  `resolveToTypeFn`: it retries an alias's original body after an unsuitable cached type and leaves
  schemes intact, so substituting canonical lookup would change its contract.
- Add focused structure and signature-precedence tests; preserve invocation/ownership regressions.

### Step 13: editor lookup and source facts

- `ast.queries.SourceQueries` owns half-open span containment and valid-coordinate facts.
  AstLookup retains cursor traversal order, synthetic-node filtering, constructor source mapping,
  protocol-reference expansion, display, and source-name recovery.
- Replace AstLookup's alias recursion with TypeQueries canonical traversal for resolved IDs.
  Preserve source-name fallback when an editor type lacks a usable indexed declaration. Guard
  recovered aliases against cycles; missing or cyclic type information yields no field target.
- Exercise real definition/reference paths for cached/grouped aliases, identity precedence,
  incomplete-ID name recovery, and cycles. Canonical traversal can recover nested cached aliases
  that the old single-cache-step helper missed; describe that improvement explicitly.

These plans are within the Author's 2026-11-03 authorization to complete all remaining phases.
After integration, update the architecture and run formatting, lint, full tests, smoke, local
publishing, benchmark builds, and the memory harness sequentially. Complete QA enforcement,
tracking review, and the independent primary review with all four dedicated checks before handoff.

### First implementation slice: operator queries

1. Extract candidate inspection, operator classification, and grouping comparison from
   OperatorCoherence into OperatorQueries.
2. Unify successful grouping and conflict detection around the same candidate analysis and
   explicit outcomes described above.
3. Adapt ExpressionRewriter to consume query facts and construct narrowed references, preserving
   candidate identities, order, arity, and unresolved protocol signatures.
4. Keep visible-declaration enumeration and diagnostic accumulation in
   `OperatorCoherence.checkModule`, using the shared grouping rules.
5. Verify behavior preservation, update the affected architecture documentation, complete the
   required compiler checks and review, then stop for Author review and signoff.

This first slice establishes the shared pattern. Later-family decisions and implementations are
recorded in steps 7–13; registry associations, code construction, callable analysis, and editor
policy retain their respective owners.

Approval: metadata prerequisite complete; bounded operator-query extraction approved.

### Follow-up: consume grouping outcomes directly

Consume the three-way grouping result directly before migrating another query family. Reuse
each arity result throughout one rewriting decision for operator selection and diagnostics.

- Analyze each relevant arity once per rewriting decision and reuse its `Absent`, `Coherent`,
  or `Conflict` result for operator selection and diagnostics.
- Remove separate success/conflict query adapters where direct result handling replaces them.
  Keep narrowed-reference and expression construction in `ExpressionRewriter`.
- Preserve prefix, postfix, and binary selection, precedence thresholds, bare operator values,
  candidate order and identities, unresolved protocol signatures, recovery, and diagnostic causes.
- Keep the documented same-name precondition on candidate-list queries. Strengthening it with
  another type is outside this follow-up; no query cache is required.

Verification: retain grouping and diagnostic regression assertions, add focused coverage only
for uncovered decision paths, and complete the required compiler checks and independent review.

Dependency: operator-query extraction complete and signed off. Implementation approved;
verification, independent review, and scoped signoff complete.

## Verification

For each approved migration, preserve existing semantic assertions and add regression coverage
where unification exposes a gap. Check identity-based lookup, coherent and conflicting operators,
unresolved candidates, and partial/error ASTs. Run applicable compiler verification and independent
review under the repository rules. Operator-extraction results are recorded below.

[ProtocolGroupingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolGroupingTests.scala)
already covers declaration order, mixed free/protocol operators, unary/binary selection,
shadowing, unresolved candidates, and grouping conflicts. Preserve those assertions and add
focused regression coverage only for uncovered query contracts. Source inspection supports the
inventory; implementation verification is recorded separately below.

### Metadata prerequisite verification — 2026-10-31

Host: Darwin arm64, Homebrew Clang 23.1.1; sbt 2.0.10 on GraalVM Java 25.0.4.1.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed; 2,497 library tests and 9 CLI tests.
- `./tests/smoke/run.sh all`: 8/8 passed.
- Final Cats-style accessor cleanup: `sbtn "scalafmtAll; scalafixAll; test; mmlcPublishLocal"`
  passed; 2,497 library tests and 9 CLI tests, followed by local installation.
- `make -C benchmark clean` and `make -C benchmark mml`: passed. These build the
  benchmark executables; no performance comparison is claimed.
- `./tests/mem/run.sh all`: 52/52 passed with ASan and LSan (85 seconds).
- No compiler source warnings. sbt reports multiple discovered main classes.
- `BindingMetaTests` checks accepted concrete associativity, rejected `None`, and constructor
  defaults. Existing grouping, parser recovery, and protocol inheritance tests remain passing.
- `git diff --check`: passed. No ABI changes; additional Linux ABI-container verification is
  not applicable to this metadata-only representation change.
- Independent primary review and separate QA, test-quality, readability, and code-documentation
  checks completed with no actionable findings. Three documentation candidates were independently
  rejected as unchanged pre-existing issues. Review was read-only; execution evidence is above.

### Operator-query extraction verification — 2026-10-31

Host: Darwin arm64, Homebrew Clang 23.1.1; sbt 2.0.10 on GraalVM Java 25.0.4.1.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed; 2,499 library tests and 9 CLI tests.
- `./tests/smoke/run.sh all`: 8/8 passed; `sbtn mmlcPublishLocal` then passed.
- `make -C benchmark clean` and `make -C benchmark mml`: passed (executable builds only).
- `./tests/mem/run.sh all`: 52/52 passed with ASan and LSan (89 seconds).
- No compiler source warnings; sbt reports multiple discovered main classes.
- Two added `ProtocolGroupingTests` cover recovery filtering, stable candidate order,
  deduplication, absent arities, and agreement between query conflicts and module diagnostics.
  Existing conflict assertions use the explicit query outcome; expression assertions remain.
- `git diff --check`: passed. No ABI change; additional Linux ABI-container checks are not
  applicable.
- Independent primary review and all four dedicated checks completed. Four independently verified
  documentation findings were corrected: architecture dependency, same-name query preconditions,
  grouping outcomes/recovery, and recognition contract. Fresh narrow checks confirmed the corrections
  with no remaining findings. No behavioral defect was found.
- Final `sbtn scalafmtAll` and `git diff --check` passed after comment-only corrections;
  extraction executable code is unchanged from the tested version. Separate edits to
  `ast/common.scala` and `mml/samples/hole-test.mml` are outside this extraction's verification scope.

### Direct grouping-outcome verification — 2026-10-31

Host: Darwin arm64; sbt 2.0.10 on GraalVM Java 25.0.4.1.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed; 2,499 library tests and 9 CLI tests.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn "scalafmtAll; mmlcPublishLocal"`: passed after comment-only clarification.
- `make -C benchmark clean` and `make -C benchmark mml`: passed (executable builds only).
- Existing grouping and diagnostic regressions retain their assertions; no tests were changed.
- No compiler source warnings. No ABI or ownership/lambda behavior changes; additional Linux
  ABI checks and the full memory harness are not applicable.
- `git diff --check`: passed. Independent primary review and four fresh dedicated checks
  (QA, test quality, readability, and code documentation) completed with no actionable findings.

### Declaration-query extraction verification — 2026-11-01

Host: Darwin arm64, Homebrew Clang 23.1.1; sbt 2.0.10 on GraalVM Java 25.0.4.1.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed; 2,499 library tests and 9 CLI tests.
  The initial sandboxed launcher could not write the sbt boot lock; the retry with cache access
  passed. No compiler source warnings; sbt reports multiple discovered main classes.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn "scalafmtAll; mmlcPublishLocal"`: passed; final formatting changed import layout only.
- `make -C benchmark clean` and `make -C benchmark mml`: passed (executable builds only,
  with no performance comparison).
- Existing tests retain their assertions. ProtocolCheckerTests exercises source-name fallback
  for raw instance bindings; ProtocolRegistryTests covers parameter shadowing and mixed operator
  metadata. ProtocolGroupingTests covers recovery, ordered candidates, deduplication, and conflicts.
  PreCodegenValidatorSuite and EntryPointTests cover instance-member exclusion and source main
  selection, including native executable behavior. No additional test was needed for the extraction.
- No imports from semantic or compiler remain in ast.queries, and no source/test callers use the
  extracted ProtocolRegistry helpers or the old semantic.queries package.
- `git diff --check` and current task/tracker local-link checks pass. No ABI or ownership/lambda
  behavior changes; additional Linux ABI-container checks and the full memory harness do not apply.
- Parent QA compliance pass and independent primary review complete. Four separate fresh checks
  (QA, test quality, readability, and code documentation) completed with no actionable findings.
  No candidate findings required independent claim verification. Review was read-only.

### Scope and name-query verification — 2026-11-01

Host: Darwin arm64, Homebrew Clang 23.1.1; sbt 2.0.10 on GraalVM Java 25.0.4.1.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed; 2,499 library tests and 9 CLI tests.
  No compiler source warnings; sbt reports multiple discovered main classes.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn "scalafmtAll; mmlcPublishLocal"`: passed; final formatting changed import layout only.
- `make -C benchmark clean` and `make -C benchmark mml`: passed (executable builds only,
  with no performance comparison).
- ProtocolRegistryTests retains identity, order, deduplication, rejection, and shadowing assertions
  on visibleCandidates. Arity/conflict assertions use the production operator-grouping path.
  The obsolete ordinary-value-filter test is removed; unary filtering is covered by the mixed
  visibility/grouping test. Strict-index controls cover a same-level missing ID after a name match,
  an identity mismatch before outer lookup, and malformed outer IDs hidden by a nearer name.
- Existing constructor, nested/recursive scope, instance-exclusion, diagnostic, and LSP tests pass.
  No imports of semantic/compiler code occur in ast.queries; source/test searches find no old scope
  type, removed registry arity API calls, or semantic.lookupNames imports.
- `git diff --check` and task/tracker local-link checks pass. No ABI or ownership/lambda behavior
  changes; additional Linux ABI-container checks and the full memory harness do not apply.
- The primary review and four separate checks (QA, test quality, readability, and code documentation)
  reported no findings but missed unrelated registry qualifiers in DeclarationQueries comments.
  The corrected object, lookupNames, and loweredMemberIds comments describe their local purpose
  and result. A fresh primary review and four fresh dedicated checks of those three comments
  found no remaining issues. Review was read-only; no new candidates needed claim verification.
- The comment correction passes `git diff --check`, line-length, and task/tracker link checks.
  Executable code matches the tested version. Compiler checks were not rerun for wording changes.

### Lookup-condition correction — 2026-11-01

Review identified a redundant `failed.isEmpty` alternative in visibleCandidates. Its input `named`
is nonempty, so an empty failed partition guarantees a nonempty usable partition. The condition is
`usable.nonEmpty`; lookup results and rejection causes are unchanged. The initial step 8 review
missed this readability issue.

Host and toolchain match the scope/name-query verification above. After the correction:

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed; 2,499 library tests and 9 CLI tests,
  with zero failures or errors. No compiler source warnings; sbt reports multiple main classes.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn mmlcPublishLocal`, `make -C benchmark clean`, and `make -C benchmark mml`: passed.
  Benchmark checks build executables; no performance comparison is claimed.
- Existing lookup tests cover usable candidates, rejected declarations, mixed valid/rejected names,
  absent names, shadowing, candidate order, and strict index validation. Tests are unchanged.
- `git diff --check` passes. Additional ABI-container and full memory-harness checks do not apply;
  no ABI or ownership/lambda behavior changed. Parent QA compliance pass is complete.
- Fresh primary correction review and four separate checks (QA, test quality, readability, and code
  documentation) completed with no actionable findings. No new candidate findings required claim
  verification. Review was read-only; execution evidence is recorded above.

### Type-query extraction verification — 2026-11-03

Host: Darwin arm64, Homebrew Clang 23.1.1; sbt 2.0.10 on GraalVM Java 25.0.4.1.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed; 2,499 library tests and 9 CLI tests,
  zero failures or errors. The initial sandboxed launcher could not write the sbt boot lock;
  the retry with cache access passed. No compiler source warnings; sbt reports multiple main classes.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn "scalafmtAll; mmlcPublishLocal"`: passed. Final formatting changed import layout only.
- `make -C benchmark clean` and `make -C benchmark mml`: passed (37 executable builds,
  with no performance comparison).
- `./tests/mem/run.sh all`: 52/52 passed with ASan and LSan (74 seconds).
- All 20 extracted public/private method signatures and bodies match TypeUtils at `1259a348` after
  ignoring owner qualifications, comments, and whitespace. Production and test caller migrations
  preserve executable expressions and assertions apart from the approved equivalent constructor-result
  lookup in CallableTargetAnalysis. TypeUtilsTests retains its existing suite name and cases.
- No production or test calls to TypeUtils remain. The query package has no semantic, compiler,
  or codegen imports. No added Scala line exceeds 100 characters. Parent QA compliance pass complete.
- `git diff --check` and changed-document local-link checks pass. No ABI behavior changes;
  additional Linux ABI-container verification does not apply.
- The independent primary review and all four dedicated checks completed. One independently
  confirmed documentation gap required a local contract for refinement expression types and
  parameters omitted by term traversal. The scanner comment explains both traversal boundaries
  and its reference predicate. A fresh primary correction review and four fresh dedicated checks
  (QA, test quality, readability, and code documentation) completed with no remaining findings.
  The correction passes diff and line-length checks. Executable code and tests are unchanged from
  the verified implementation, so execution checks were not repeated for wording alone.
- Refinement expression type coverage is supported by source inspection and existing failure
  tests. The inspected tests do not isolate computed refinement annotations or Lambda/BindOwnership
  parameter failures; the extraction preserves those paths without adding a coverage claim.
- ValueScope and handcrafted bubblesort whitespace edits are outside this verification/review scope.
  This verification checkpoint precedes combined Author signoff for phases 9–13.

### Integrated type, target, callable, and editor queries — 2026-11-03

Host: Darwin arm64, Homebrew Clang 23.1.1; sbt 2.0.10 on GraalVM Java 25.0.4.1.
Scope: the combined phases 9–13 relative to `1259a348`.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed twice, with 2,513 library tests and
  9 CLI tests, zero failures or errors. The second run includes the final equivalent constructor
  declaration lookup in CallableValues. No compiler source warnings; sbt reports multiple mains.
- `./tests/smoke/run.sh all`: 8/8 passed.
- `sbtn mmlcPublishLocal`: passed after smoke; the verified compiler is installed locally.
- `make -C benchmark clean` and `make -C benchmark mml`: passed, with 37 executable builds.
  These are build checks, not performance measurements.
- Fourteen added regressions cover Clone alias identity/current indexed bindings, missing/rejected
  targets and operand diagnostics, destructor-origin precedence, application order and metadata,
  signature identity/precedence, editor alias/name/cycle recovery, and source-span endpoints.
- MemoryFunctions and DestructionTargets retain their executable query bodies; the move changes
  package/import ownership and local contracts. Clone selection keeps the registry's authoritative
  associations. Application and constructor-declaration migrations preserve query results.
- Editor tests deliberately exercise controlled partial metadata. Shared canonical traversal finds
  fields through nested cached/grouped/scheme aliases, cyclic aliases yield no field target, and
  indexed nonstruct types cannot fall back to a different same-name struct. Name recovery preserves
  source declaration order. Existing hover, definition, references, and protocol suites pass.
- Parent QA checks pass: no new mutation, exception flow, focused/ignored tests, or Scala lines
  over 100 characters. Shared AST queries have no semantic/compiler/codegen/LSP imports.
  `git diff --check` and changed Markdown file/heading links pass.
- No ABI contract change; additional Linux ABI-container checks do not apply. Hosted verification
  is not run. The ValueScope and handcrafted bubblesort whitespace edits are outside this scope.
- `./tests/mem/run.sh all`: 52/52 passed with ASan and LSan (74 seconds).
- A fresh independent primary review and four separate fresh checks (QA/style, test quality,
  human readability, and code documentation with humanizer) completed with no actionable findings.
  Documentation review covered all 40 comments in new/moved production files and new tests, plus
  affected caller documentation. No candidate findings required claim verification or corrections.
  Review was read-only and used the execution evidence above without repeating builds.
- Final handoff tracking review confirms complete implementation and verification, with the separate
  application-reconstruction task still open. Local file and heading links and `git diff --check`
  pass. Author signoff and delivery authorization are recorded in the Signoff section.

### Delivery checks — 2026-11-03

- The approved delivery also includes the existing ValueScope alignment and handcrafted bubblesort
  spacing edits. Both have empty whitespace-insensitive diffs. The ValueScope edit was present
  during the integrated compiler checks above.
- `sbtn "run run mml/samples/handcrafted/bubblesort.mml"`: passed; output is `1`, `2`, `3`, `4`.
  The JVM reports Scala LazyVals use of deprecated sun.misc.Unsafe APIs; no compiler error occurs.
- A fresh independent review of the two additional whitespace hunks completed all four dedicated
  checks with no actionable findings. No candidates required claim verification.
- Closure bookkeeping marks all 13 phases complete, records combined Author signoff, removes the
  active tracker entry, and adds the completed product changes to the changelog.

## Risks / Notes

Follow-up after [Implement protocols](protocols.md). Protocol registration owns its associations;
centralizing queries must not duplicate registry state or introduce dependencies on phase objects.
The separate [explicit Clone work](explicit-clone.md) retains its own scope and signoff.

AstLookup uses TypeQueries for resolved alias traversal and keeps explicit source-name recovery
for incomplete editor indexes. Controlled partial-AST regressions exercise cached/grouped aliases,
missing identities, source-order recovery, indexed and recovered cycles, and a nominal/struct name
collision. These tests establish navigation behavior on those trees, not that every malformed tree
can be produced by parsing a source program. Cached/grouped/scheme traversal and cycle termination
extend recovery; rejecting an indexed nonstruct preserves identity instead of guessing by name.

Backend `resolveToTypeFn` and PreCodegenValidator retain distinct alias-resolution contracts.
Their missing cycle guards remain source-inspection concerns without a reproduced reachable
failure. This task does not change backend fallback policy or the separate destruction-validator
canonicalization decision.

## Signoff

- Workstream and tracked-item signoff: granted for all 13 phases.
- Implementation, required execution verification, and independent review: complete.
- Local commit and remote delivery: authorized for all current non-broken changes, including
  the ValueScope and handcrafted bubblesort whitespace edits.
- Delivery target: `origin/main`; the current checkout is already on `main`.

## Task Working Memory

Earlier deliveries: metadata in `2f807e96`, operator queries in `2d8614db`, direct grouping
integration in `626489d2`, declaration queries in `9ecc467f`, and lexical scopes/name queries
in `a9fb2e94`. Their verification evidence is preserved above.

All 13 phases are complete and signed off. Shared AST/index queries cover declaration metadata,
operator grouping, lexical value scopes, types, destruction targets, application structure, and
source spans. Registry associations, Clone call construction, callable value-flow analysis,
completed-invocation decisions, and editor selection/recovery policy retain their owners.

Phases 9–13 replace TypeUtils with four domain owners, separate Clone selection from construction,
move the memory-target queries into ast.queries, and share application walkers across semantic and
codegen consumers. AstLookup follows shared canonical aliases while retaining guarded name recovery.
Cached/grouped aliases reach fields, cycles terminate, and indexed nonstruct types cannot select
same-name structs. Architecture documentation and affected source links match these boundaries.

Formatting, lint, 2,513 library tests, 9 CLI tests, 8 smoke checks, local publishing, 37 benchmark
builds, and 52 ASan/LSan memory fixtures pass. The independent primary review and all four dedicated
checks pass with no actionable findings. The 14 new regressions cover selection availability,
identity and ordering, signature precedence, editor recovery, and span boundaries.

The separate application-reconstruction task remains open for rebuilding and short-list behavior.
No implementation or verification work remains for this task. Delivery consists of one commit on
main and Git Town synchronization to origin/main; the two approved whitespace edits are included.
