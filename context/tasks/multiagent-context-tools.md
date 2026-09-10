# Context Tools

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

Add verbiage and design for multiagent execution.

  * controller agent
    * keeps a tight focus on the task and the spec
    * keeps the repo rules (coding rules, dev tools, qa) fresh in memory
      * and advises fresh sub agents about them.
    * keeps current state in task working memory and selected focus in memory.md so we can reset or compact often
    * schedules and delegates agents to
      * research, each answering a specific question
      * execution
        * it depends on the task but might benefit from spawning parallel agents
        * even if not parallelizabe separate agents keep the controller agent free to focus on its main responsability with an uncluttered context.
      * qa enforcement
        * run an agent to review the changes using the qa enforcement skill

## Outcome

Agree on controller and delegated-agent responsibilities, context handoff, and QA for multiagent work.

## Scope

- In scope: The problem and outcome above; detailed scope is settled in the approved plan.
- Out of scope: Unrelated work and changes not covered by an approved plan.

## Plan (Approval Gate)

- [ ] Confirm a bounded plan and acceptance with the Author before implementation.

Approval: Pending implementation planning; tracking migration approved on 2026-09-10.

## Implementation Checklist

- [ ] Execute and verify the scope once its plan is approved.

## Verification

No new implementation verification during the tracking migration.

## Risks / Notes

Migrated from the former tracker on 2026-09-10. Migration preserves pending scope;
it does not establish implementation progress, approval, or completion.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Not granted for this task by the migration request.

## Task Working Memory

The Author authorized migration into a task file. Implementation is not authorized by
that bookkeeping request. Next: discuss and approve a bounded plan before changing code or docs.
