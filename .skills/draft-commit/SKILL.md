---
name: draft-commit
description: Review repository changes and prepare a concise commit draft for explicit approval. Use when the author asks to draft, prepare, or review a commit message, check commit scope or tracking before committing, or says the work is ready to commit.
---

# Draft commit

Prepare a commit from the repository's real state and rules. Do not assume a
particular tracking filename or workflow from another repository.

## Load repository rules

1. Read `AGENTS.md` and follow its skill lookup, write, tracking, and commit
   rules.
2. If `context/task-tracking-rules.md` exists, treat it as the tracking source
   of truth. Read `context/memory.md` and the linked active task when the work
   is tracked.
3. If those files do not exist, use the repository guidance that does exist.
   Report a genuinely missing requirement, but do not stall because another
   repository used a different filename.

## Review the proposed commit

1. Inspect `git status`, staged changes, unstaged changes, and untracked files.
2. Read the complete relevant diff. Preserve unrelated work by the author and identify
   the exact file set proposed for the commit.
3. Check that tracking and changelog claims match the evidence. Do not mark a
   task or subtask complete merely to make the commit look finished.
4. Review available verification results and call out failures, skipped gates,
   or target/runtime checks that remain outstanding.
5. Split the proposal when the changes contain materially independent work.
   Keep one commit when the files form one coherent workstream and separating
   them would make either commit misleading or incomplete.

## Draft the message

Write a short imperative subject that describes the product change. Add a
brief body only when it explains important behavior or scope that the subject
cannot carry.

Keep the language plain and specific. Remove inflated claims, boilerplate,
AI-sounding phrasing, generated-by trailers, and unnecessary lists. Follow any
commit-message convention documented by the repository.

Present:

- the proposed subject and body;
- the included file scope, especially any surprising file;
- tracking state and relevant verification evidence;
- any material inconsistency that must be resolved before committing.

## Approval and commit

Drafting is read-only unless the author separately authorized staging or tracking
updates. Never create the commit merely because a draft was requested.

A `finish task`, `complete task`, or `finish errand` command authorizes the scoped commit.
When a commit was already authorized for this scope, execute that authorization
without requesting it again. Otherwise wait for explicit approval of the draft. A direct response such as `yes`, `y`,
`commit it`, or an approved replacement message is sufficient.

After approval:

1. Recheck `git status` and the proposed diff.
2. Stop and redraft if the scope changed materially after approval.
3. Stage only the approved files when staging is still needed.
4. Commit with the approved message.
5. Report the commit hash and whether the working tree is clean.
