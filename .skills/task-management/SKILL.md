---
name: task-management
description: Index for repo-local task lifecycle skills.
---

# Task Management

Read `context/task-tracking-rules.md` first. That file is the source of truth
for task lifecycle operations.

Before task implementation, apply its [branching rule](../../context/task-tracking-rules.md#branching):
default to `dev/<task-name>` unless directed otherwise. Assess an existing branch's theme,
history, and the change's size and risk before deciding to continue there or ask to create
a separate branch.

Use the narrower skills for execution:

- `task-add`
- `task-list`
- `task-pick`
- `close-subtask`
- `close-task`
- `archive-task`
- `archive-cleanup`
- `close-errand`

This skill exists only to route discovery. Do not use it to execute a tracking
change.
