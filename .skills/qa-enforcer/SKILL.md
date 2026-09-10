---
name: qa-enforcer
description: Audit MML changes against the repository coding and QA rules when asked for rule compliance or during compiler handoff. Use code-review for behavioral and architectural findings requiring independent verification.
---

# QA Enforcer

Read the current `context/coding-rules.md` and `context/qa-rules-and-coding-style.md`.
Derive the checklist from those files, not a remembered subset of their examples.

Inspect the exact workstream diff, including staged, unstaged, and untracked changes.
Check each changed hunk against the applicable rules and available verification evidence.
Report concrete rule violations with file/line, violated rule, and the smallest correction.
Report skipped or failing required checks accurately; do not equate ignored tests with passes.

Keep pure review requests read-only. During approved implementation, fix compliance misses
within scope and rerun affected checks. Do not close tasks, stage, or commit merely because
this pass succeeds.

Route correctness, architecture, test-quality, and behavioral claims through local
`code-review`; that skill owns reviewer isolation and independent per-claim verification.
A compliance scan does not replace that review. Tracking-internal consistency belongs to
local `tracking-doc-review`, not this scan. Do not invoke `post-chores` from this skill.
