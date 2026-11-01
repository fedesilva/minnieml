# Centralize, and Unify ast queries

## Metadata

- **Owner:** The Author
- **Status:** in_progress
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
8. [ ] **design** — Review protocol visibility, candidate, and concrete-implementation queries; retain authoritative association lookup in the registry.
9. [ ] **design** — Review type canonicalization, declarations, native representation, and destruction requirements for shared domain boundaries.
10. [ ] **design** — Separate Clone target selection from call construction, preserving unavailable-target behavior.
11. [ ] **design** — Review destruction-target and memory-function queries, retaining or refining their existing shared boundaries.
12. [ ] **design** — Separate callable structural queries from value-flow analysis for origins, application structure, and parameter ownership.
13. [ ] **design** — Identify reusable cursor, definition, reference, and source-span queries while preserving LSP policy and recovery.

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
- Use `ast.queries` for shared queries over AST nodes and indexes. Step 7 moves the existing
  operator queries there; broader migrations retain their separate scope decisions.
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
| Protocol visibility, candidates, and concrete implementations | ProtocolRegistry instance methods | Keep association lookup with the registry; reuse its typed failures and authoritative state. |
| Type canonicalization, declarations, native representation, and destruction requirements | [TypeUtils](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeUtils.scala); used across parsing, semantics, and codegen | Already shared. Consider domain separation later without routing AST helpers through semantic phases. |
| Clone target selection and call construction | [CloneCalls](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CloneCalls.scala); used by struct generation and ownership analysis | Split selection from construction later; preserve the deliberate treatment of missing or rejected instances as unavailable targets. |
| Destruction targets and memory-function lookup | [DestructionTargets](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/DestructionTargets.scala) and [MemoryFunctions](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/MemoryFunctions.scala); used by ownership, destructor generation, and codegen | Retain these focused shared boundaries initially. |
| Callable origins, application structure, and parameter ownership | [CallableValues](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CallableValues.scala) and [CallStages](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CallStages.scala) | Distinguish structural queries from value-flow analysis before migration; retain the analysis owner. |
| Cursor lookup, definitions, references, and source spans | [AstLookup](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/AstLookup.scala); used by LSP features | Keep editor policy in LSP; compare recovery behavior before extracting reusable semantic facts. |

### Remaining query families

Checklist steps 7–13 follow the inventory order. Each family needs a bounded decision about
its shared queries, owner, inputs, results, and failure/recovery contract before implementation.
For each family, approve the plan, implement any agreed extraction or caller migration, and
complete applicable verification and review before signoff. Where an existing shared boundary
is retained, record the rationale and supporting evidence rather than forcing a relocation.
Listing these families does not authorize their implementation.

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
  Steps 8–13 remain open. Implementation approval does not authorize a commit or push.

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

Later query families require their own bounded migration decisions. This first slice establishes
the shared pattern without moving type helpers, registry operations, Clone construction, callable
analysis, or LSP policy.

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

## Risks / Notes

Follow-up after [Implement protocols](protocols.md). Protocol registration owns its associations;
centralizing queries must not duplicate registry state or introduce dependencies on phase objects.
The separate [explicit Clone work](explicit-clone.md) retains its own scope and signoff.

LSP alias lookup in AstLookup falls back to source names, while TypeUtils follows resolved
identities and guards alias cycles. These implementations are not interchangeable: unification
must explicitly preserve editor recovery. AstLookup's recursive alias lookup has no explicit
cycle guard; this is a suspected robustness issue, not a reproduced bug. Establish whether a
cyclic partial AST reaches that path before proposing a repair.

## Signoff

- Workstream signoff: metadata prerequisite, operator-query extraction, and direct grouping-outcome
  integration approved and complete.
- Step 7: implementation, verification, independent review, and scoped signoff complete.
- Tracked item completion: pending; query families in steps 8–13 remain open.
- Implementation approval: steps 1–7 complete. Steps 8–13 need their own bounded plans.
- Local commit authorization: step 7 source, tests, architecture documentation, and bookkeeping.
  Later implementation commits require separate authorization.

## Task Working Memory

The metadata prerequisite is complete in `2f807e96`. Operator-query extraction is complete on
`main`, with execution checks, independent review, and Author signoff. Its source, tests,
architecture documentation, and completion records are included in the
`Extract shared operator queries` commit.

`OperatorQueries` owns candidate inspection and shared conflict accumulation. `OperatorCoherence`
owns visible-declaration enumeration and module diagnostics; `ExpressionRewriter` owns narrowed
references and use-site diagnostics. The rewriter consumes grouping outcomes directly and reuses
unary and binary results for selection and conflict diagnostics. Required execution checks and
independent review pass; step 6 is signed off.

Direct grouping integration is committed in `626489d2`. Steps 1–7 are complete and signed off.
Steps 8–13 need bounded plans, implementation where agreed, verification, and signoff.

Step 7 implements DeclarationQueries and OperatorQueries in `ast.queries`. Semantic, diagnostic,
codegen, and test callers use the shared AST queries. Registry operations retain their owner.
The approved extraction preserves the helper contracts; compiler verification passes as recorded
above. Independent review passes with no actionable findings; scoped signoff is granted.

Next action: review the protocol visibility, candidate, and concrete-implementation query boundary
in step 8, retaining authoritative association lookup in the registry, and prepare its bounded plan.
