# Task Tracking Rules

This file is the source of truth for task state, working memory, and lifecycle operations.
Repo-local lifecycle skills are thin wrappers around these rules. The Author may explicitly
override them. Raise an unresolved conflict before proceeding with dependent work.

## Terminology and files

Keep the `context/` top level limited to `memory.md`, instruction files, and `changelog.md`.
Task definitions, working notes, designs, and supporting evidence belong under `context/tasks/`;
historical records for later review belong under `context/history/`.

- **Workstream**: the work being executed in the current session.
- **Errand**: a transient Workstream without a Tracked Item. It may use a short-lived
  Global Working Memory note, but has no task file or tracked status.
- **Tracked Item**: a durable task definition in `context/tasks/` or
  `context/tasks/completed/`. Only Author-selected tasks have entries in `context/memory.md`.
- `context/memory.md` contains active and near-term work selected by the Author, not the backlog.
- `context/tasks/_template_task.md` is the template for new task files.
- `context/tasks/` holds pending and unarchived task definitions and per-task working memory.
- `context/tasks/completed/` holds archived completed task files.
- `context/changelog.md` holds durable product-change summaries.
- Task files hold their relevant designs and plans alongside scope and working memory.
- `context/history/` holds historical evidence, not active instructions or current task state.

## Focused context loading

Read these rules before task work. Start with `context/memory.md`, tasks named by the Author,
and their directly relevant records. Read out-of-focus tasks only when the request needs
that information, such as listing the backlog or investigating an explicit dependency.
Read coding rules before code work. Avoid loading the backlog to steer an unrelated session.

## Working memory

Always retain **Global Working Memory** and **Active Tasks**, even when empty.

Global Working Memory holds concise cross-workstream observations, reminders, and future
candidates whose task creation is intentionally deferred. These notes are not tasks and
carry neither status nor permission to start work. Remove stale or completed notes. When a
candidate becomes a task, move its durable detail into that task file.

Active Tasks is the Author's selected working set. Add a task only when the Author asks to
put it in current or near-term focus. Listing a task does not authorize implementation.
Keep entries limited to the name, task-file link, status, and current focus. Synchronize
existing entries with task files; absence from memory is intentional. Remove completed
workstream notes instead of accumulating history there.

Update the relevant task's **Task Working Memory** at meaningful checkpoints before,
during, and after approved work: what changed, evidence, decisions, blockers, and the next
action. Keep detail there or in linked evidence records, not duplicated in active memory.
Do not rewrite historical evidence or treat another branch's completed checklist as current.

## Authority and planning

- Create or edit Tracked Items only when asked or within an approved task plan that includes
  that bookkeeping. Do not rewrite task meaning, selected focus, or top-level completion
  without the Author's instruction.
- Research and preparing a plan need no approval. Code, documentation, and structural
  changes require plan approval, per `AGENTS.md`. Once approved, execute work and steering
  within that scope without repeating the gate. Material scope changes need fresh approval.
- Direct, unambiguous non-code orders authorize their specified writes. Do not repeat an
  already settled decision as an approval question.
- Split large work into bounded stages. Obtain review and signoff before advancing to the
  next stage. Keep evidence and relevant task records current for handoff and commits.
- Creating, moving, or archiving a task file is bookkeeping, not evidence of product progress.
- When task intent or evidence is ambiguous, ask instead of inventing scope or completion.

## Branching

Use Git Town as specified in `context/coding-rules.md`. Record the actual working branch
in the task's **Target Branch** metadata. Leave it unassigned until known. Never infer a
Git Town parent from a branch name or historical checklist.

## Status and completion

Use `planned`, `in_progress`, `blocked`, `ready_for_signoff`, and `complete`.
Record deferral and missing approvals in task working memory; do not turn them into a
claim that implementation ran. Migrating an old task does not approve its implementation.

`finish task` and `complete task` mean the same operation: verify, log, mark complete,
and commit. The command supplies workstream signoff and local commit authorization for
that task; do not ask for them again. `finish errand` authorizes verification, logging,
and a local commit without tracked-task completion. Explicit limits such as "do not commit"
override these defaults. Push only when the Author requests it.

1. Run applicable verification from `context/coding-rules.md` and local `post-chores`.
   Record failures, ignores, and explicit waivers accurately. A finish command does not
   waive required checks or authorize unrelated implementation.
2. Before completing a task, verify that no open subtasks or unchecked plan items remain.
   Record the signoff supplied by the finish command. Report unfinished work or failing
   checks instead of claiming completion; scope reductions require Author direction.
3. Reconcile the task, active memory, and changelog using the operations below.
4. Use local `draft-commit` to inspect the exact scope and prepare a plain commit message,
   then commit the finished work and its bookkeeping under the existing authorization.
   Preserve unrelated changes. If the commit fails, report the failure and leave a clear
   task or errand note that the commit remains pending; do not report the finish as done.

Subtasks can be checked off within approved work when supported by evidence. Passing checks
alone does not authorize whole-task completion or a commit. Task creation, subtask completion,
and archive operations do not authorize commits. Never delete task history except under
explicit archive-cleanup authorization.

## Lifecycle operations

- **`task add`**: create a task from the template. Add it to memory only if selected by the
  Author. Do not log administrative creation to the changelog or automatically commit.
- **`complete subtask`**: verify the named checklist item's evidence, update that item and
  any existing memory reference, and log the completed product change. Leave the parent
  task open unless separately instructed and eligible for completion.
- **`finish task` / `complete task`**: verify the task, record signoff, mark it `complete`,
  remove its active-memory entry, log the completed work, and commit the scoped changes.
  Keep its file in `context/tasks/` until archival is requested.
- **`archive task` / `archive tasks`**: move only complete tasks with durably recorded
  outcomes to `context/tasks/completed/`. Remove active-memory references and repair
  links to the moved files and links inside them. Preserve historical task contents.
- **`archive cleanup`**: apply only to already completed archived task history. Present
  the exact deletion set and get confirmation before deleting. Repair affected links.
- **`errand`**: begin or continue untracked work, observing ordinary plan approval.
- **`finish errand`**: verify untracked work, log it, remove any completed Global Working
  Memory reminder, and commit the scoped changes. Do not create or close a tracked task.

Avoid duplicate changelog entries when a subtask's changes are already recorded. Finishing
never pushes by default. An explicit request such as `finish task and push` authorizes that
additional step, using the repository's Git Town guidance and the intended remote branch.

## Changelog

Log completed work when finishing a task or errand, including durable codebase, architecture,
documentation, feature, and workflow changes. Describe the result, not task administration,
ticket operations, or verification command transcripts.
Keep verification details in task files or linked evidence. Historical imported entries are
preserved as evidence even if they do not follow today's logging conventions.

## Local administration

Task tracking is local. No lifecycle operation creates, edits, closes, or synchronizes
GitHub issues or project items. Existing external references remain optional historical
links. External actions require an explicit request; do not recreate a sync obligation.

## Review routing

For tracking consistency, use `.skills/tracking-doc-review/SKILL.md` in the current agent.
For code and affected technical documents, use `.skills/code-review/SKILL.md` with its
independent reviewer and per-claim verification. Finish checks are routed by local
`post-chores`; QA rules and verification commands remain owned by their repository files.
