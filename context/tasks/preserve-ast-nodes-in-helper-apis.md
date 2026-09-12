# Eliminate unnecessary AST field decomposition

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-12
- **Target Branch:** Unassigned
- **External Reference (optional):** None

## Problem

Compiler helpers accept long lists of fields taken from the same AST node. This spreads the
node's structure across signatures and callers, even when the helper uses only part of it.
Passing the node directly makes that relationship explicit. Where helpers also reconstruct
the node, manual field forwarding adds the risk of losing metadata.

Concrete examples in
[OwnershipAnalyzer.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala)
are `analyzeLambdaApplication` and `analyzeLambda`, which receive all eight fields of a
`Lambda`, and `analyzeCond`, which receives all six fields of a `Cond`. The lambda-application
calls carry `FIXME:QA` markers. Passing every field is an extreme example, not a requirement
for identifying the pattern.

## Outcome

Prefer the existing node when a helper receives several of its fields, whether or not it
uses every field or returns a rewritten node. More than two or three fields from one node
is a strong signal to pass the node. Even two or three warrant doing so when their meaning
is tied to that node and they have no useful independent source.

Keep a small set of separate arguments when the helper defines a useful independent operation
and those inputs can meaningfully come from other sources. Keep analysis context separate.
For rewrites, use `copy` to preserve unchanged fields. Apply this criterion throughout the compiler.

## Scope

- In scope: compiler helper definitions, callers, node reconstruction, and affected references.
- Out of scope: semantic changes, AST redesign, and unrelated ownership or construction fixes.

## Plan (Approval Gate)

- [ ] Inventory full and partial field lists across compiler sources, starting with
  OwnershipAnalyzer, and present a bounded migration plan for approval.

Approval: Task creation and active-memory selection authorized on 2026-09-12.
Implementation plan pending.

## Implementation Checklist

- [ ] Replace unnecessary field lists with the existing node; update every caller.
- [ ] Use `copy` for rewrites, preserving source, types, identities, captures, and other metadata.
- [ ] Remove addressed `FIXME:QA` markers and update affected documentation references.
- [ ] Repeat the compiler-wide search; inspect remaining candidates and explain any retained
  decomposition by the helper's actual contract.
- [ ] Run applicable compiler checks and review the final changes for behavior preservation.

## Verification

Implementation verification is pending. Check signatures and callers through compilation,
run the required compiler gates, and verify metadata preservation in the changed rewrites.

## Risks / Notes

Judge the relationship between the arguments, their sources, and the helper's purpose; field
count is a guide, not a mechanical cutoff. Neither partial use nor absence of reconstruction
justifies a long list of fields from one node. Preserve useful independent operations and
pattern matching. Do not add wrapper types to replace the AST.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Task record only, authorized on 2026-09-12.

## Task Working Memory

Selected in active memory as planned. Implementation has not started. Next: inventory the
compiler-wide occurrences and propose the migration scope.
