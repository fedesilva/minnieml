# Test editor lookup against generated references

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:lsp`, `concern:test-quality`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Execute the qualified-field fixture and confirm that it discriminates the lookup filter.
2. [ ] **planned** — Assert the resolved field definition, hover/reference behavior where affected, and tokens.

## Problem

Finding F4: `SemanticTokensTests` uses `take (text ())`. Its written `text` reference wins
lookup by traversal order and shorter span even without the new `AstLookup` exclusion.
The test protects semantic tokens but does not establish that generated references cannot
shadow written definitions. A candidate fixture is a struct field `h.text` passed to
`read(text: String, n: Int): Int`, partially applied as `let p = read h.text; p 1`.
The generated reference and written field have equal spans, exposing the lookup tie.

## Outcome

A definition-lookup regression fails without generated-reference filtering and passes with it.

## Scope

The component and concerns described above are independent backlog work, outside Unify lambdas
completion and merge requirements. Preserve language semantics, ownership enforcement, evaluation
order, and runtime behavior; any discovered correctness defect needs separate assessment.

## Plan (Approval Gate)

Prepare a bounded implementation plan from the checklist and embedded evidence.
Approval: task definition and deferral authorized; implementation plan pending.

## Verification

The review established the current test gap by source tracing. The proposed qualified-field
fixture was reasoned, not executed. Keep the existing semantic-token guard.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Deferred at LOW priority. Next action: prepare the bounded plan when this task is selected.
