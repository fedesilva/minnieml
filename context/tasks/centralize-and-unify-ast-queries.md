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

Inventory existing query families and propose the package dependency direction, naming pattern,
result types, and first migration. Keep modules organized by domain rather than collecting every
query in one utility object. Present the bounded implementation plan for approval.

Approval: design and implementation plan pending.

## Verification

For each approved migration, preserve existing semantic assertions and add regression coverage
where unification exposes a gap. Check identity-based lookup, coherent and conflicting operators,
unresolved candidates, and partial/error ASTs. Run applicable compiler verification and independent
review under the repository rules. Implementation verification is pending.

## Risks / Notes

Follow-up after [Implement protocols](protocols.md). Protocol registration owns its associations;
centralizing queries must not duplicate registry state or introduce dependencies on phase objects.
The separate [explicit Clone work](explicit-clone.md) retains its own scope and signoff.

## Signoff

- Workstream signoff: design approval pending.
- Tracked item completion: pending.
- Commit authorization: not granted for implementation.

## Task Working Memory

The first concrete boundary is module-wide operator-coherence checking versus the query API
consumed by `ExpressionRewriter`. The query subpackage location and remaining migration scope need design.
Next action: inventory query families and propose a bounded package design and extraction plan.
