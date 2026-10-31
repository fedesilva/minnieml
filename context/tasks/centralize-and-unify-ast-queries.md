# Centralize, and Unify ast queries

## Metadata

- **Owner:** The Author
- **Status:** needs_design
- **Kind:** improvement
- **Priority:** unassigned
- **Created:** 2026-10-29
- **Target Branch:** unassigned
- **External Reference (optional):** None.

## Execution Checklist

1. [ ] **needs_design** — Inventory AST queries and settle package boundaries and query contracts.
   - Initial inventory and extraction proposal are below; next, settle the proposed query contracts.
2. [ ] **planned** — Approve a bounded extraction and unification plan, starting with operator queries.
3. [ ] **planned** — Migrate approved query families and their callers; remove duplicate queries.
4. [ ] **planned** — Verify behavior preservation, update architecture documentation, and review.

## Tags

- architecture
- ast
- protocols

## Problem

Reusable AST queries are distributed among phase objects and helper modules, making shared
operations difficult to discover. The operator-coherence phase combines module-wide checking
with the prefix, binary, postfix, operator-classification, and conflict queries used by
`ExpressionRewriter`. One compiler phase therefore depends on another phase's query API.

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

### Initial query inventory

This source inventory identifies query families and boundaries for staged migration. It is not
an exhaustive audit of private helpers or evidence that similarly named queries are equivalent.

| Family | Current home and consumers | Proposed treatment |
| --- | --- | --- |
| Operator classification, candidates, precedence, and associativity | [OperatorCoherence](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OperatorCoherence.scala); used throughout ExpressionRewriter | Extract first: one compiler phase owns another phase's query API. |
| Declaration metadata, source names, and instance-member IDs | [ProtocolRegistry companion](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ProtocolRegistry.scala); used by registration, coherence, ID assignment, diagnostics, and entry-point selection | Separate AST facts from registry operations in a subsequent slice. |
| Protocol visibility, candidates, and concrete implementations | ProtocolRegistry instance methods | Keep association lookup with the registry; reuse its typed failures and authoritative state. |
| Type canonicalization, declarations, native representation, and destruction requirements | [TypeUtils](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeUtils.scala); used across parsing, semantics, and codegen | Already shared. Consider domain separation later without routing AST helpers through semantic phases. |
| Clone target selection and call construction | [CloneCalls](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CloneCalls.scala); used by struct generation and ownership analysis | Split selection from construction later; preserve the deliberate treatment of missing or rejected instances as unavailable targets. |
| Destruction targets and memory-function lookup | [DestructionTargets](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/DestructionTargets.scala) and [MemoryFunctions](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/MemoryFunctions.scala); used by ownership, destructor generation, and codegen | Retain these focused shared boundaries initially. |
| Callable origins, application structure, and parameter ownership | [CallableValues](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CallableValues.scala) and [CallStages](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CallStages.scala) | Distinguish structural queries from value-flow analysis before migration; retain the analysis owner. |
| Cursor lookup, definitions, references, and source spans | [AstLookup](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/AstLookup.scala); used by LSP features | Keep editor policy in LSP; compare recovery behavior before extracting reusable semantic facts. |

### Proposed package and contracts

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

The exact result types and treatment of incomplete candidate metadata need agreement before
implementation. Registry associations remain authoritative; the query layer must not copy them
or depend on phase objects.

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

Approval: proposed design and initial implementation plan pending approval.

## Verification

For each approved migration, preserve existing semantic assertions and add regression coverage
where unification exposes a gap. Check identity-based lookup, coherent and conflicting operators,
unresolved candidates, and partial/error ASTs. Run applicable compiler verification and independent
review under the repository rules. Implementation verification is pending.

[ProtocolGroupingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolGroupingTests.scala)
already covers declaration order, mixed free/protocol operators, unary/binary selection,
shadowing, unresolved candidates, and grouping conflicts. Preserve those assertions and add
focused regression coverage only for uncovered query contracts. Source inspection supports the
inventory; no compiler checks or runtime reproductions were run for this design pass.

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

- Workstream signoff: design approval pending.
- Tracked item completion: pending.
- Commit authorization: not granted for implementation.

## Task Working Memory

The initial inventory and proposed operator extraction are in the Plan section. The first
boundary is module-wide coherence checking versus reusable grouping facts consumed by
ExpressionRewriter. Package placement, result contracts, and implementation approval remain open.

Next action: review the proposed `semantic.queries.OperatorQueries` boundary and grouping outcomes,
settle incomplete-candidate behavior, and approve a bounded first implementation slice.
