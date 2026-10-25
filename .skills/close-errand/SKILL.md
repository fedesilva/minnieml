---
name: close-errand
description: Verify, log, and commit untracked work when the Author says close errand. No task is created or closed; push only when requested.
---

# Close Errand

Read `context/task-tracking-rules.md` and follow its `close errand` operation.

First check which unfinished work is actually in flight: an errand, a task, or both.
If the request names the wrong kind, both are in flight, or the target is unclear, ask
the Author to confirm which workstream to close and wait. Do not silently reinterpret
the command. An open entry in `context/tracker.md` alone does not establish work in flight.

Use local `post-chores` only for compiler work; otherwise run checks relevant to the change.
Then use local `draft-commit` to prepare and execute the already authorized commit.
Do not create or close a tracked task.
The close command does not waive failing checks or authorize a push.
