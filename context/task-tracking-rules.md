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
- Never delete Tracked Items. Mark status only.

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
- Use `gh` to keep Active Tracked Items aligned with GitHub tickets.
- Synchronize with GitHub at these points:
  - when creating a top-level Active Tracked Item
  - when creating a subtask representation (checklist item or sub-issue)
  - when marking a subtask or top-level Tracked Item complete
  - for other updates only when not in the middle of active Workstream execution, unless the Author asks otherwise
- Every top-level Active Tracked Item must have a corresponding GitHub ticket.
- Ticket and Tracked Item must reference each other.
- Subtask tracking rules:
  - In GitHub, use checklist items for small steps.
  - In GitHub, use sub-issues for larger efforts.
  - In `tracking.md`, use checkbox lists (`[ ]`) for subtasks.
  - For larger subtasks, create `context/specs/<task-name-short>.md`.
- For long tickets that need deeper planning, discuss creating a separate local spec file.

--------------------------------------------------------------------------------------------------------
