---
name: post-chores
description: Run MML's applicable finish checks, focused tracking review, and independent adversarial review before handing off compiler, tooling, technical documentation, or workflow work.
---

# Post Chores

Read `AGENTS.md`, `context/coding-rules.md`, `context/qa-rules-and-coding-style.md`,
and `context/dev-tools.md` directly. When tracking is involved, also read tracking rules,
working memory, and the relevant task. Derive finish requirements from those sources.

## Ordinary checks

1. Inspect status and the complete workstream diff, including untracked files. Preserve
   unrelated work. Remove temporary artifacts and diagnostics introduced in this scope.
2. Check comments, documentation, and TODOs against the current behavior and evidence.
   Repair authorized inconsistencies; raise decisions that need broader authority.
3. For compiler changes, execute every applicable formatting, lint, smoke, full-suite,
   publishing, benchmark, and memory gate in `context/coding-rules.md`. Coordinate shared
   build access: never run concurrent `sbtn` sessions. Run local `qa-enforcer` for compliance.
4. For context-only or documentation-only changes, check consistency, local links, and
   `git diff --check`; do not run compiler gates. For samples or tooling, use checks that
   establish their changed behavior, following applicable repository rules.
5. Record actual results, including failures, ignores, and explicit waivers. Never infer
   workstream signoff, task completion, or commit authority from passing checks.

## Focused tracking review

For tracking changes, invoke local `tracking-doc-review` directly in the current agent.
Give it the exact tracking scope, task-local Author instructions, and factual evidence.
Iterate authorized tracking-only fixes and focused rechecks. Keep this review separate
from code review. Pass its result as evidence when an independent review is also needed.

## Independent adversarial review

For code, tooling, workflow rules/skills, or affected technical documentation, invoke local
`code-review` after ordinary checks. Pure task bookkeeping needs only the focused review.
Give the fresh reviewer the exact scope, approved goal, source paths, verification evidence,
skipped gates, and limitations. The code-review skill owns isolation, per-claim verification,
fallback approval, reporting, and narrow re-review; do not substitute a compliance scan.

Fix confirmed findings within approved scope, rerun affected checks, and use that skill's
narrow re-review. Raise findings that need a new decision or unavailable evidence. Do not
silently waive a failing required gate.

## Handoff

Recheck status and the final diff. State changes, evidence, open findings, and verification
limits. Distinguish semantic checks, LLVM validation, execution, sanitizers, and performance
measurements. Keep task working memory current. Commit only when authorized, including
by a finish command under the tracking rules.
