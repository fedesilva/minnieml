---
name: task-list
description: Read and display available tasks from the local repo task setup. Use when the author wants to see open tasks from context/memory.md and context/tasks/.
---

# Task List

Read `context/task-tracking-rules.md` first so task status comes from the
source of truth.

For current work, read `context/memory.md` and its task links. For open tasks or the
backlog, enumerate `context/tasks/*.md`, excluding the template, and inspect titles and
status before loading details. Include completed-but-unarchived tasks only when requested.
Do not treat memory membership as the full backlog. Do not modify tracking state while
listing.

## App session name

Once the Author confirms which task or subtask to work on, including in a later follow-up,
rename the current app session using `set_thread_title` when available. Use the selected
task or subtask title, including its identifier if present, and omit the thread ID to target
the current session. Selection confirmation authorizes this rename without another prompt.
Update the session name if the Author changes the selection. Listing tasks alone does
not trigger a rename.

## Trigger Keywords

- `task list`
- `list tasks`
- `available tasks`
- `open tasks`
- `what tasks are left`
- `show current work`
