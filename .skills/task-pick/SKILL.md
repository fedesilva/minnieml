---
name: task-pick
description: Select the next development task from local repository context. Use when the author asks what to work on next based on context/memory.md, task files in context/tasks/, the current branch, and recent local changes.
---

# Task Pick

Read `context/task-tracking-rules.md` first so task status comes from the
source of truth.

Then combine `context/memory.md`, the relevant files in `context/tasks/`, and
repository state to pick one actionable next task. Prefer the current focus,
branch-aligned work, and unblocked tasks with clear acceptance criteria.

Keep recommendations within the selected focus unless the Author requests broader backlog
selection. Ask for plan approval before starting a recommendation that is not already approved. Do not change
task state here.

## Reporting the selection

Always report the full selection path:

`context/memory.md: <entry title> → context/tasks/<task-file>.md → <specific entry>`

Use the actual entry titles and include intervening section headings when needed to locate
the selected item in a long task file. Make the memory entry, task file, and selected entry
clickable using verified file locations. Follow the path with a brief explanation of the
chosen work and why it is next, so the Author can verify the selection and regain context.
An entry name alone is insufficient. If broader backlog selection is requested and the task
has no memory entry, state that explicitly instead of inventing a memory path.

## Trigger Keywords

- `task pick`
- `pick next task`
- `what should I work on next`
- `choose next task`
- `prioritize active tasks`
- `next task for this branch`
