---
name: task-pick
description: Select the next development task from local repository context. Use when the author asks what to work on next based on context/memory.md, task files in context/tasks/, the current branch, and recent local changes.
---

# Task Pick

Read `context/task-tracking-rules.md` first so task status comes from the
source of truth.

Then combine `context/memory.md`, the relevant files in `context/tasks/`, and
repository state to pick one actionable next task. The order of entries under
**Active Tasks** in `context/memory.md` expresses priority: higher entries have
higher priority. Select the highest-priority actionable work unless the Author
explicitly directs otherwise. Branch alignment alone does not override this order.

Keep recommendations within the selected focus unless the Author requests broader backlog
selection. Ask for plan approval before starting a recommendation that is not already approved. Do not change
task state here.

## Check what is actually open

Before selecting, make a focused, read-only pass through the active entries from
top to bottom. Compare each entry's stated status and focus with its task's execution
checklist, working memory, verification evidence, and signoff. Follow relevant dependency
records and inspect recent commits or narrowly scoped code/tests where needed to resolve
contradictions; do not expand this into a backlog audit or full verification run.

Distinguish unfinished implementation, pending verification/review/signoff, explicit
deferrals or blockers, and completed work still listed as open. An unchecked box or
`in_progress` label alone does not establish that implementation remains. Conversely,
completed repairs do not complete a parent task whose verification or signoff is pending;
that closeout work is a valid next selection.

Report stale entries or conflicting evidence with links, and state uncertainty where the
available evidence cannot settle the status. Do not mark tasks complete, remove memory
entries, or infer approval during this pass. When selecting a lower entry, explain why
each higher-priority entry is being skipped, including its actual remaining work or blocker.

## Reporting the selection

Always report the full selection path as an indented Markdown list, with each level
on its own line. Use this structure, making each title a clickable link:

```markdown
- **Memory:** entry title from context/memory.md
  - **Task:** task title from context/tasks/<task-file>.md
    - **Section / checklist item:** specific entry title
```

Do not compress the path into a single line or an arrow-separated chain. Use short,
descriptive link labels rather than displaying full file paths.

Use the actual entry titles and include intervening section headings when needed to locate
the selected item in a long task file. Make the memory entry, task file, and selected entry
clickable using verified file locations. Follow the path with a brief explanation of the
chosen work and why it is next, so the Author can verify the selection and regain context.
An entry name alone is insufficient. If broader backlog selection is requested and the task
has no memory entry, state that explicitly instead of inventing a memory path.

## App session name

Once the Author confirms which task or subtask to work on, including in a later follow-up,
rename the current app session using `set_thread_title` when available. Use the selected
task or subtask title, including its identifier if present, and omit the thread ID to target
the current session. Selection confirmation authorizes this rename without another prompt.
Update the session name if the Author changes the selection. A recommendation alone does
not trigger a rename.

## Trigger Keywords

- `task pick`
- `pick next task`
- `what should I work on next`
- `choose next task`
- `prioritize active tasks`
- `next task for this branch`
