# Document source origins and ownership diagnostic contracts

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** LOW
- **Tags:** `component:ast`, `component:ownership`, `component:compiler-design`, `concern:documentation`
- **Created:** 2026-10-21
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Trace the field and source-origin contracts and revise comments and design prose together.
2. [ ] **planned** — Check documentation against provenance behavior and review links and formatting.

## Problem

Findings F6, F7, F9:
- `SemanticError.UseAfterMove.source` positions the diagnostic; `ref` supplies its name and
  can be a backing owner; `ownerId` can differ from `ref.resolvedId`; `dependency` is suppression
  evidence. The doc comment does not explain these distinctions, unlike sibling error conventions.
- Document `SourceOrigin.Loc`, `Derived`, and `Synth` explicitly. “Token identity” is undefined.
  `LocalBindings.Local` retains a replaced expression's span, not the expression itself;
  fresh locals without a replacement origin remain synthetic.
- The local-binding paragraph in `docs/design/compiler-design.md` has an awkward short wrap.
  Separate generated-source/diagnostic normalization from borrowed-return suppression, and
  document the written-reference preference used when generated and written reports coincide.
  Scope normalization claims to actual behavior until the provenance follow-up is implemented.

## Outcome

AST API comments and compiler-design documentation describe the same precise source/diagnostic contract.

## Scope

The component and concerns described above are independent backlog work, outside Unify lambdas
completion and merge requirements. Preserve language semantics, ownership enforcement, evaluation
order, and runtime behavior; any discovered correctness defect needs separate assessment.

## Plan (Approval Gate)

Prepare a bounded implementation plan from the checklist and embedded evidence.
Approval: task definition and deferral authorized; implementation plan pending.

## Verification

The evidence above comes from source inspection and the lambda branch review. No repair or
verification for this task is complete. Run the applicable repository checks when implementing it.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Deferred at LOW priority. Next action: prepare the bounded plan when this task is selected.
