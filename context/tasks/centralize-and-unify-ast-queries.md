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
6. [ ] **planned** — [Consume grouping outcomes directly in ExpressionRewriter](#follow-up-consume-grouping-outcomes-directly).

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
- Settle the subpackage location and domain boundaries. `semantic.queries.OperatorQueries` is a
  candidate for the first extraction; broader placement depends on the inventory.
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
| Operator classification, candidates, precedence, and associativity | [OperatorQueries](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/queries/OperatorQueries.scala); consumed by OperatorCoherence and ExpressionRewriter | Shared query extraction implemented; preserve the division between facts, diagnostics, and AST construction. |
| Declaration metadata, source names, and instance-member IDs | [ProtocolRegistry companion](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ProtocolRegistry.scala); used by registration, coherence, ID assignment, diagnostics, and entry-point selection | Separate AST facts from registry operations in a subsequent slice. |
| Protocol visibility, candidates, and concrete implementations | ProtocolRegistry instance methods | Keep association lookup with the registry; reuse its typed failures and authoritative state. |
| Type canonicalization, declarations, native representation, and destruction requirements | [TypeUtils](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeUtils.scala); used across parsing, semantics, and codegen | Already shared. Consider domain separation later without routing AST helpers through semantic phases. |
| Clone target selection and call construction | [CloneCalls](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CloneCalls.scala); used by struct generation and ownership analysis | Split selection from construction later; preserve the deliberate treatment of missing or rejected instances as unavailable targets. |
| Destruction targets and memory-function lookup | [DestructionTargets](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/DestructionTargets.scala) and [MemoryFunctions](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/MemoryFunctions.scala); used by ownership, destructor generation, and codegen | Retain these focused shared boundaries initially. |
| Callable origins, application structure, and parameter ownership | [CallableValues](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CallableValues.scala) and [CallStages](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CallStages.scala) | Distinguish structural queries from value-flow analysis before migration; retain the analysis owner. |
| Cursor lookup, definitions, references, and source spans | [AstLookup](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/AstLookup.scala); used by LSP features | Keep editor policy in LSP; compare recovery behavior before extracting reusable semantic facts. |

### Approved operator-query package and contracts

Start with `semantic.queries.OperatorQueries` for queries requiring semantic context.
Keep existing AST-only helpers beneath `ast` until a separate migration establishes their
destination. Organize query modules by domain, with explicit node, index, and registry inputs.
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

`ExpressionRewriter` uses separate adapters for successful grouping and conflict lookup. Both
call `OperatorQueries.grouping`, so the same term and arity can be analyzed repeatedly during
one rewriting decision. Consume the three-way result directly before migrating another query
family.

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

Dependency: operator-query extraction complete and signed off. Implementation approval is pending.

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

- Workstream signoff: metadata prerequisite and operator-query extraction approved and complete.
- Tracked item completion: pending.
- Commit authorization: granted for operator-query extraction; no implementation or commit
  authorization for the direct grouping-outcome follow-up.

## Task Working Memory

The metadata prerequisite is complete in `2f807e96`. Operator-query extraction is complete on
`main`, with execution checks, independent review, and Author signoff. Its source, tests,
architecture documentation, and completion records are included in the
`Extract shared operator queries` commit.

`OperatorQueries` owns candidate inspection and shared conflict accumulation. `OperatorCoherence`
owns visible-declaration enumeration and module diagnostics; `ExpressionRewriter` owns narrowed
references and use-site diagnostics. The rewriter retains separate success/conflict adapters.

Next action: approve step 6, direct grouping-outcome integration, before selecting another query
family. Later query families remain subject to separate bounded plans.
