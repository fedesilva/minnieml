---
name: close-task
description: Verify, close, log, and commit a tracked task when the Author says close task. Push only when requested.
---

# Close Task

Read `context/task-tracking-rules.md` and follow its `close task` operation.
Use this for the full tracked item, not a subtask or errand.

First check which unfinished work is actually in flight: a task, an errand, or both.
If the request names the wrong kind, both are in flight, or the target is unclear, ask
the Author to confirm which workstream to close and wait. Do not silently reinterpret
the command. An open entry in `context/tracker.md` alone does not establish work in flight.

Run applicable checks through local `post-chores`, then use local `draft-commit` to
prepare and execute the already authorized commit. The close command supplies signoff
and commit authority; it does not waive failing checks or authorize a push.
