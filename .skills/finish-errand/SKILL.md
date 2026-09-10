---
name: finish-errand
description: Verify, log, and commit untracked work when the Author says finish errand. No task is created or closed; push only when requested.
---

# Finish Errand

Read `context/task-tracking-rules.md` and follow its `finish errand` operation.
Run applicable checks through local `post-chores`, then use local `draft-commit` to
prepare and execute the already authorized commit. Do not create or close a tracked task.
The finish command does not waive failing checks or authorize a push.
