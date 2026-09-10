---
name: finish-task
description: Verify, log, mark a tracked task complete, and commit its changes when the Author says finish task or complete task. Push only when requested.
---

# Finish Task

Read `context/task-tracking-rules.md` and follow its `finish task` operation.
`complete task` is an alias. Use this for the full tracked item, not a subtask or errand.
Run applicable checks through local `post-chores`, then use local `draft-commit` to
prepare and execute the already authorized commit. The finish command supplies signoff
and commit authority; it does not waive failing checks or authorize a push.
