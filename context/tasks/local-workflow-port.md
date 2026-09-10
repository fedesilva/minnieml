# Port the local collaboration workflow from y0y

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Created:** 2026-09-10
- **Target Branch:** dev-lambdas-migration
- **External Reference (optional):** None recorded.

## Problem

MML's context mixes backlog, current work, historical status, and verification
records. Mandatory GitHub synchronization adds distraction. The Author approved porting
y0y's local workflow, cleaning up all pending tracking into template-based task files, and
removing the GitHub helper scripts.

The current repository rules and this task define the workflow. The superseded pre-port
spec is removed; its unsettled questions are resolved by the decisions below.

## Outcome

Local context, task lifecycle, and skills follow the approved workflow port, with useful design material in tasks and superseded specs in history for Author review.

## Scope

- In scope: Local collaboration rules, skills, task migration, history, and removal of GitHub helpers; specifications in phase two.
- Out of scope: Compiler behavior, external issue operations, pushes, and branch reconfiguration.

## Plan (Approval Gate)

- [x] **Phase 1:** Port the local workflow and skills; migrate every pending tracker entry
  with the task template; preserve history; remove GitHub helper scripts; validate and
  present for workstream review.
- [x] **Phase 2:** Remove `context/specs/`, retain useful designs in relevant task files,
  archive superseded plans/reviews for the Author, and verify content and reference migration.

Approval: Phase one approved by the Author on 2026-09-10, including the requirement for separate commit authorization,
pending-task cleanup, and GitHub helper removal. The Author subsequently authorized phase two:
remove the specs directory, keep useful material in relevant tasks, and archive the remainder
for later review. This instruction authorizes document migration, not compiler implementation.
The Author then signed off the work, requested logging, task completion, and a commit, and
defined finish commands to include those actions. Pushing remains optional and unrequested.

## Implementation Checklist

- [x] Adapt rules and local skill metadata, preserving independent review and MML verification.
- [x] Establish curated memory, task files, completed-history location, and product changelog.
- [x] Migrate pending entries and preserve historical evidence without inventing completion.
- [x] Remove GitHub helpers and active synchronization obligations.
- [x] Validate links, preservation, skill discovery, lifecycle authority, and Git Town guidance.
- [x] Obtain phase-one review and signoff.
- [x] Migrate and verify specification content and root-level context documents.
- [x] Obtain Author review and signoff on phase two.

## Verification

Preservation and structural checks pass: 14 local skills and UI metadata validate; 25 task
files use the template; 82 maintained local Markdown links and anchors resolve; the historical
tracker matches the baseline exactly; the imported changelog preserves its text with nine
whitespace-only lines normalized. The existing code-review
skill is unchanged. Phase-two checks confirm exact archived spec bodies, retained task
content, byte-identical JSON evidence, and the memory/instructions/changelog-only root layout. All 12 GitHub helpers are removed. `git diff --check` passes.

Focused tracking review found no remaining inconsistencies in the content mapping and task state. Static lifecycle walkthroughs
confirmed the initial separate-commit policy and selected-memory behavior; the final finish-command
policy supersedes that initial commit boundary. Independent phase-one workflow review
found no actionable findings; no candidates required per-claim verifiers. Details: [migration validation](../history/workflow-port.md#phase-one-validation).

## Risks / Notes

Migrated from the former tracker on 2026-09-10. Migration preserves pending scope;
it does not establish implementation progress, approval, or completion.

## Signoff

- Workstream signoff: Granted by the Author’s instruction to finish on 2026-09-10.
- Tracked item completion: Authorized and recorded on 2026-09-10.
- Commit authorization: Explicitly granted for the migration and final lifecycle rules.
- Push authorization: Not requested.

## Task Working Memory

Both migration phases are implemented and signed off. Source revision, content mappings,
preservation checks, and independent phase reviews are recorded in
[workflow-port.md](../history/workflow-port.md). The workflow task is complete; the migrated
compiler and QA tasks retain their own pending state.

Local skills use name/description discovery and read instructions only when needed.
The Author dropped picker configuration. `finish-task` handles both `finish task` and
`complete task`: verify, log, mark complete, remove from memory, and commit. `finish-errand`
verifies, logs, and commits without tracking changes. Push only when requested.

Final lifecycle-rule review found no actionable findings; no candidates required independent
verifiers. Focused tracking review, preservation checks, skill validation, and whitespace
checks pass. The Author authorized the migration commit; no push is requested. Compiler
builds are inapplicable to this workflow-only change. Historical compiler assessments were
not revalidated.
