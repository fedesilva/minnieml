# Task Tracking Rules

These rules are mandatory for working with `context/tracking.md`, unless the Author explicitly overrides them.
If a rule is unclear or conflicts with the current task, raise the conflict and wait for direction.

## Terminology

- **Workstream**: the work currently being executed in this session.
- **Tracked Item**: an entry in `context/tracking.md` and its linked GitHub issue/project item.

## Active Tracked Items

- Tracked Items are created only by the user, or on their behalf when explicitly instructed.
- Seek confirmation before editing Tracked Items.
- Never change a Tracked Item description unless explicitly instructed.
- If a Tracked Item is misleading or incorrect, raise it and stand by.

## Completion Flow

- Workstream completion and Tracked Item completion are separate actions.
- Subtasks inside a Tracked Item may be marked complete when completion evidence is present.
- Top-level Tracked Item completion requires explicit Author signoff.
- Before requesting top-level signoff, run required post-task rules from `context/coding-rules.md` when applicable.
- After signoff, ask whether to mark the top-level Tracked Item `[COMPLETE]`, then stand by for instruction.
- When instructed, update Tracked Item status and log the change under `Recent Changes`.
  - log codebase or documentation changes **only**.
- Never delete Tracked Items. Mark status only.
  - if explicitely told to delete, ask for confirmation.

## Recent Changes

- Log changes in `Recent Changes` when the Author requests it, or when Tracked Item status changes.

## Synchronization

- `context/tracking.md` is the local working cache:
  - it is faster and cheaper to update during active work
  - it is the preferred interface for Author interaction
- Keep larger specifications locally in `context/specs/`, and keep GitHub aligned with the corresponding summary/checklist state.
- Keep `context/tracking.md` synchronized with:
  - Repo: MinnieML
  - GitHub repo: `https://github.com/fedesilva/minnieml`
  - GitHub project: `https://github.com/users/fedesilva/projects/3`
- Use local helper scripts under `bin/` first for GitHub sync operations; use raw `gh` only when
  no helper script covers the operation.
  - Rationale: helper-script commands keep escalation prompts shorter and easier for the Author to
    review and approve safely.
  - For issue body updates/checklist toggles, do not use raw `gh issue edit --body*`; use
    `bin/gh-issue-body-*` / `bin/gh-issue-check` / `bin/gh-issue-uncheck`.
  - `bin/gh-issue-get <issue> [repo] [fields]`: fetch issue metadata/body.
  - `bin/gh-issue-list [--repo ...] [--state ...] [--label ...] [--limit ...]`:
    list/filter issues.
  - `bin/gh-issue-body-get <issue> [repo]`: fetch issue body.
  - `bin/gh-issue-body-grep <issue> <pattern> [repo]`: grep issue body with line numbers.
  - `bin/gh-issue-body-set <issue> <body-file> [repo]`: replace issue body from file.
  - `bin/gh-issue-body-replace <issue> <perl-expression> [repo]`: apply in-place issue body edit.
  - `bin/gh-issue-check <issue> <item-text> [repo]`: mark checklist item checked.
  - `bin/gh-issue-uncheck <issue> <item-text> [repo]`: mark checklist item unchecked.
  - `bin/gh-project-item-add <issue-number-or-url> [--repo ...] [--owner ...] [--project ...]`:
    add an issue to a GitHub project item list.
  - `bin/gh-project-items [--owner ...] [--project ...] [--limit ...]`: inspect project items.
  - Default repo for issue helpers is `fedesilva/minnieml`.
- Synchronize with GitHub at these points:
  - when creating a top-level Active Tracked Item
  - when creating a subtask representation (checklist item or sub-issue)
  - when marking a subtask or top-level Tracked Item complete
  - for other updates only when not in the middle of active Workstream execution, unless the Author asks otherwise
- Every top-level Active Tracked Item must have a corresponding GitHub ticket.
- Creating a GitHub ticket for a top-level Active Tracked Item is not complete until that issue is
  added to GitHub project `fedesilva/projects/3`.
  - This is mandatory for every new top-level Tracked Item.
  - Use `bin/gh-project-item-add <issue-number-or-url>` for this step.
  - Only if this helper script is unavailable, use raw `gh` (for example:
    `gh project item-add 3 --owner fedesilva --url <issue-url>`).
- Creation protocol for top-level Tracked Items (mandatory, in order):
  1. Create the GitHub issue.
  2. Add the issue to project `fedesilva/projects/3`.
  3. Only then report ticket creation as done.
- When the Author asks to "create a GH ticket" for a top-level Tracked Item, treat project-add as
  included by default unless explicitly told to create an issue only.
- Ticket and Tracked Item must reference each other.
- Subtask tracking rules:
  - In GitHub, use checklist items for small steps.
  - In GitHub, use sub-issues for larger efforts.
  - In `tracking.md`, use checkbox lists (`[ ]`) for subtasks.
  - For larger subtasks, create `context/specs/<task-name-short>.md`.
- For long tickets that need deeper planning, discuss creating a separate local spec file.
- DO NOT LOG ticket creation/modifications to RECENT CHANGES.

--------------------------------------------------------------------------------------------------------
