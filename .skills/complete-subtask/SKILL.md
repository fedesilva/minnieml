---
name: complete-subtask
description: Verify, log, complete, and commit one tracked subtask when the Author says finish subtask or complete subtask. Leave its parent open; push only when requested.
---

# Finish or Complete Subtask

Thin wrapper skill.

Read `context/task-tracking-rules.md` first and follow the `finish subtask` /
`complete subtask` operation there. That file is the source of truth.

Use this only for the named tracked checklist item, not the whole tracked item.
The finish command supplies signoff and local commit authorization. Use local
`post-chores` and `draft-commit` as required by the tracking rules, then commit
only this subtask's changes and bookkeeping without asking for another approval.
Leave the parent and unfinished siblings open. Push only when requested.

If evidence is unclear, stop and ask.

Trigger Keywords:

- `finish subtask`
- `complete subtask`
