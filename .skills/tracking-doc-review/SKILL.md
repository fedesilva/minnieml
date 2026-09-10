---
name: tracking-doc-review
description: Quickly review and iteratively repair MML tracking documents directly in the current agent for internal consistency, factual support, and compliance with task-tracking rules. Use for context/memory.md, context/tasks/*.md, task-lifecycle bookkeeping, and tracking-related context/changelog.md changes. Keep the review tracking-focused; never spawn reviewers, invoke the code-review skill, or perform a deep code review.
---

# Tracking Document Review

Review tracking documents quickly. Stay focused on tracking consistency,
factual support, and `context/task-tracking-rules.md`. This work is not a code
review.

## Scope and Authority

1. Read `AGENTS.md`, `context/task-tracking-rules.md`, and
   `context/memory.md`.
2. Use the scope named by the author. Otherwise inspect only changed tracking
   files and task files directly referenced by those changes.
3. Keep `review`, `audit`, `inspect`, and `look at` requests read-only. Fix only
   when the author also asks to fix, apply, address, or reconcile.
4. Ask the author before changing task meaning, selected focus, completion
   state, or a claim that depends on missing or ambiguous evidence.
5. Route direct lifecycle commands through their lifecycle skills.

Never invoke `code-review`, `qa-enforcer`, or `post-chores` from this skill.
Never spawn reviewers, verifiers, or other subagents.

## Build Focused Review Context

Keep only the following task-local context in the current agent:

- the repository root and exact tracking diff or named scope;
- the author's goal and whether fixes are authorized;
- paths to the tracking rules and directly relevant tracking files;
- the affected task files and their relevant current state;
- validation commands already run and their exact results when they support a
  tracking claim;
- the user's commands, answers, or questions immediately related to the
  affected tasks and review scope;
- factual evidence and known gaps unavailable in the repository.

Pass only task-local context. Do not pass unrelated conversation, backlog
details, suspected findings from other workstreams, or a desired verdict.

Review the tracking documents directly. Keep findings ordered by severity with
file and line references, ask focused questions where author input is needed,
and state explicitly when no actionable findings remain.

## Check Tracking Consistency

Check only what applies:

- required memory sections remain present and correctly used;
- active entries are unique, concise, linked, and synchronized with their task
  files;
- temporary notes are current and completed or stale notes are removed;
- task scope, checklist state, status, signoff, and file location obey the
  lifecycle rules;
- completion and verification claims have recorded support;
- changelog entries describe product changes, not tracking administration;
- links, headings, dates, branch metadata, and Markdown remain coherent;
- the diff contains no duplicate bookkeeping, accidental churn, or unrelated
  tracking edits.

Do not scan unrelated tasks or the repository backlog.

## Cross-check Claims Lightly

Inspect the exact commits, diffs, code, tests, or command records referenced by
a tracking claim when needed to confirm that the record is factually supported.
Use narrow read-only commands such as `git show`, `git diff`, `rg`, and focused
file reads.

This cross-check answers questions such as:

- does the named commit contain the described change;
- does the referenced file, symbol, or test exist;
- does recorded command output support the stated verification level;
- does the tracking summary match the visible scope of the implementation.

Do not assess implementation correctness, architecture, concurrency, ownership
safety, performance, test quality, or target behavior. Do not run build,
test, publishing, or memory gates. If a tracking decision requires that deeper
judgment or unavailable evidence, ask the author a focused question and stop
that line of review.

## Fix and Re-review

Confirm each finding against the tracking rules and cited source before making
an authorized fix.

1. Apply the smallest tracking-only fix.
2. Re-read only the finding, exact fix diff, cited rule, invariant, and directly
   affected tracking text.
3. Confirm directly that the fix closes the finding without creating another
   tracking inconsistency.
4. Repeat the focused fix and recheck until clean.

Ask the author instead of guessing whenever intent or evidence is unclear. Do
not broaden scope during follow-ups.

## Speed Guardrail

Keep the review much faster than code review:

- current agent only;
- no subagents or handoffs;
- no broad repository exploration;
- no deep code reasoning;
- no compiler builds or runtime verification;
- stop as soon as the tracking obligations are settled.

## Report

For review-only work, report findings, questions, and residual evidence gaps.
For review-and-fix work, report the tracking files fixed and the final review
result.

Do not commit unless the author explicitly asks.
