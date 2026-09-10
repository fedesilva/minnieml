---
name: task-add
description: Create a new tracked item in context/tasks when the author asks to add a task, adding it to context/memory.md only when explicitly requested.
---

# Task Add

Thin wrapper skill.

Read `context/task-tracking-rules.md` first. That file is the source of truth
for task lifecycle bookkeeping.

Use this when the author wants a new tracked item created. Create the task from
`context/tasks/_template_task.md`. Add a short link to `context/memory.md` only
when the author explicitly asks to put the task in the current focus. Follow the tracking rules for explicit commit authority; task creation alone does not
authorize a commit.

Trigger Keywords:

- `task add`
- `add task`
- `create task`
- `new tracked item`
- `track this task`
