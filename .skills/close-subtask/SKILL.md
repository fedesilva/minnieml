---
name: close-subtask
description: Verify, close, log, and commit one tracked subtask when the Author says close subtask. Leave its parent open; push only when requested.
---

# Close Subtask

Read `context/task-tracking-rules.md` and follow its `close subtask` operation.

Use this only for the named tracked checklist item, not the whole tracked item.
The close command supplies signoff and local commit authorization. Use local
`post-chores` and `draft-commit` as required by the tracking rules, then commit
only this subtask's changes and bookkeeping without asking for another approval.
Leave the parent and unfinished siblings open. Push only when requested.

If evidence is unclear, stop and ask.
