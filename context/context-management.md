## Context Management

Execute these instructions ONLY when triggered by literal commands in `commands.md`.

## activeContext.md Structure

The file has three sections in order:

1. **Active** - Tasks in progress. Mark "COMPLETED" when done. Do not remove.
2. **Next Steps** - Queued future tasks.
3. **Recent Changes** - Completed work history.

## Rules

### Task Creation
- Do not create tasks unless directed by Author.

### Task Modification
- Do not remove tasks. Mark as COMPLETED instead.
- Ask for confirmation before any changes.

### Task Completion
When a task is finished:
1. Mark task as COMPLETED in Active section.
2. Add summary to Recent Changes.
3. If task came from Next Steps, mark that entry as completed.

## Commands

### compact context
- Keep max 10 items in Recent Changes.

### cleanup context
- Move completed tasks from Next Steps to Recent Changes (summarize).
- Remove completed entries from Next Steps.

### clear context
- DESTRUCTIVE. Ask for confirmation.
- If approved: clear Recent Changes.
- If approved: clear Next Steps.

### update context
- Read code and verify context is accurate.
- If outdated: report verbally, wait for instructions, take no action.
