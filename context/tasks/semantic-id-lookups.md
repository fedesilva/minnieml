# Eliminate name-based identity throughout the semantic pipeline

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** CORRECTNESS
- **Priority:** HIGH
- **Created:** 2026-09-15
- **Target Branch:** unassigned
- **External Reference:** none

## Execution Checklist

1. [ ] **planned** — Inventory every semantic phase and shared identity lookup or comparison.
2. [ ] **planned** — Establish ID requirements at phase boundaries and approve bounded migration stages.
3. [ ] **planned** — Replace name-based semantic identity throughout all phases and their shared helpers.
4. [ ] **planned** — Remove obsolete fallback paths, duplicate state, and compatibility machinery.
5. [ ] **planned** — Verify every phase, complete independent review, and obtain signoff.

## Problem

Semantic analysis mixes stable definition IDs with names as identity. Ownership analysis,
for example, records moves by binding ID but also stores binding state and merges branches
by name. Correctness then depends on surrounding scope restoration and other implicit
invariants. Parallel identity mechanisms accumulate into a lava flow antipattern: old
name-based paths remain embedded beneath newer ID-based logic.

Ordinary branch-local shadowing is protected by `analyzeLambdaApplication` restoring the
incoming scope and propagating inherited moves by ID. The name/ID split alone is not proof
of a failing shadowing case. This task removes the inconsistent identity model throughout
the semantic pipeline, including paths without a demonstrated runtime failure.

## Outcome

Every semantic phase uses stable IDs wherever it identifies an already-resolved definition.
`Resolvable.id`, `Ref.resolvedId`, ownership `bindingId`, and `ResolvablesIndex` keys refer to
the same definition identity. Names cannot silently substitute for missing IDs.

All phases must be reviewed. Completion requires removal of every name-based identity path
after resolution, not just the ownership analyzer or sites exposed by failing tests.

## Scope

- Every phase scheduled by
  [SemanticStage.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/SemanticStage.scala),
  including injection, resolution, rewriting, capture analysis, type checking, elaboration,
  generated constructors/destructors, ownership, TCO detection, and validation.
- Shared semantic and AST utilities, indexes, caches, sets, maps, equality checks, alias
  tracking, callable provenance, capture tracking, ownership witnesses, and cleanup selection.
- Generated bindings and references, identity preservation through rewrites, and index
  freshness. Audit helper code outside `semantic/` when semantic phases depend on it.
- Name fallbacks, reconstructed IDs used to rediscover resolved definitions, scans comparing
  `.name`, and indirect wrappers around these operations. Searching for `lookup` is insufficient.
- Remove obsolete code and duplicated name/ID state once callers migrate. Do not retain a
  permanent compatibility layer or exempt a phase because a scope invariant masks the risk.
- Source-name resolution and duplicate-name diagnostics necessarily inspect spelling before
  references are resolved. Audit those boundaries too; document each surviving name operation
  as source resolution, diagnostics, or presentation, never as post-resolution identity.
- Unrelated language changes and independent backend refactoring are outside scope. Update
  downstream consumers where required by the semantic identity contract.

## Plan (Approval Gate)

Build an inventory from the actual pipeline and its dependencies. For every phase, record
its input identity guarantees, all name-sensitive operations, the intended ID-based
replacement, regression coverage, and any legitimate remaining use of spelling.

Use that inventory to propose bounded stages with explicit contracts. Migrate callers and
shared helpers together, preserve IDs for existing definitions, allocate fresh IDs for new
definitions, and publish current nodes to the index. Remove replaced paths in each stage.
Keep incomplete phases visible until the full inventory is closed.

Approval: task scope established; implementation plan pending.

## Verification

Implementation verification is pending. Acceptance requires:

- A completed inventory covering every semantic phase and its shared dependencies, with
  explicit reasons for every remaining name-sensitive operation.
- No name-based fallback for resolved identity, including ownership state and branch merges.
- Explicit diagnostics or validation for missing, stale, or invalid IDs at the appropriate
  phase boundary. Preserve error accumulation and recovery for unresolved or invalid source;
  do not introduce exceptions or require IDs before resolution can assign them.
- Regressions for shadowing, identical names in distinct scopes, aliases, branch moves,
  captures, consuming parameters, generated bindings, and definition relocation/reindexing.
  Check resolved identity and observable behavior, not just diagnostic text or symbol spelling.
- Consistent IDs across definitions, references, ownership records, and index entries.
  Removed compatibility paths must have no remaining callers.
- Required formatting, lint, full-suite, smoke, publishing, benchmark-build, and memory gates
  from [coding-rules.md](../coding-rules.md), plus QA and independent review for each handoff.

## Risks / Notes

- Existing optional ID fields also represent pre-resolution and error-recovery states.
  Separate those states from violated post-resolution contracts.
- Name-based lookup can be hidden in shared helpers or duplicated metadata. Replacing direct
  map access alone does not meet the scope.
- Avoid inventing another identity system. Reuse the stable IDs and allocation/indexing
  contracts already defined by `BindingIds`, `LocalBindings`, and `ResolvablesIndex`.
- Coordinate with [lambda migration](unify-lambdas.md) and
  [conditional ownership hardening](conditional-ownership-witnesses.md) where changes overlap.
  Their narrower repairs do not substitute for the complete semantic-phase audit.

## Signoff

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: task-definition commit only; implementation commits not granted

## Task Working Memory

- **Starting evidence:** `OwnershipAnalyzer` has name-keyed `bindings` and branch merging,
  ID-keyed `movedBindingIds`, and name fallbacks in `sameBinding` and `bindingMoved`.
  Scope restoration protects the ordinary branch-shadowing cases checked during investigation.
- **Identity contract:** ownership binding IDs are definition IDs used by the resolvables
  index, not a separate ownership identifier.
- **Next action:** inventory every phase and shared dependency, then present bounded
  implementation stages for approval.
