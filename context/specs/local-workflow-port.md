# Port the local collaboration workflow from y0y

Status: planned. Created 2026-09-10 at the Author's request.

## Timing and authority

Do this after the Author reviews the current typed closure destruction changes, before
starting the next compiler slice in the lambda workstream. Creating this task does not
sign off that code or start the workflow migration. No commit or push is authorized here.
Keep this task local; do not create or synchronize GitHub issues or project items.

## Problem and outcome

MML's context mixes backlog, current work, historical status, and verification records.
Mandatory GitHub synchronization adds distraction. Global skills can carry assumptions
that differ from this repository's files and approval rules.

Port the newer workflow from `../../y0y-dev/y0y/`, adapting its rules and repo-local
`.skills/` to Scala and compiler construction. The result should make current work easy
to resume, preserve durable history, and keep task administration local.

## Agreed direction

- `context/memory.md` contains active tasks and tasks interesting in the short term,
  selected by the Author. It is not the full backlog. Being listed does not authorize
  starting work. Keep cross-workstream notes in Global Working Memory.
- Durable tasks live in `context/tasks/`, with a template and per-task working memory.
  Completed task history lives in `context/tasks/completed/`; product changes belong
  in `context/changelog.md`.
- Support errands: small untracked workstreams need not become durable tasks.
- Prefer repo-local `.skills/` over global skills. Lifecycle skills should be thin
  wrappers around one source of task-tracking rules.
- Remove mandatory GitHub tracking/synchronization from the workflow. Preserve useful
  historical external references without treating them as synchronization obligations.
- Use Git Town for the branch lifecycle; the Author confirmed MML uses it too.
- Keep the Author involved. Distinguish research, approved implementation, review,
  workstream signoff, tracked-item completion, and commit authority.

## Review and Scala adaptation

Preserve y0y's isolated adversarial review protocol: a fresh primary reviewer receives
the scope, source paths, rules, and evidence without inherited conversation or suggested
conclusions. Each atomic candidate finding gets its own fresh independent verifier,
instructed to try to disprove it. Report only confirmed findings; disclose verification
limitations without presenting unresolved claims as findings. Preserve the explicit
fallback approval when independent agents are unavailable and the narrow re-review of fixes.

Carry over the design taste: each boundary earns its existence, simplicity is measured
by reader effort, domain APIs expose intent, and mechanical repetition and compressed
cleverness are both review concerns. Review the code and the accusations adversarially.

Adapt Rust/firmware-specific checks to compiler obligations: phase responsibilities and
invariants, type and symbol resolution, ownership transfers, evaluation order, error
accumulation, and ABI boundaries. Retain MML's immutable state threading, Cats conventions,
named state records, and semantic-ID test assertions. Separate semantic-test evidence,
LLVM validity, runtime behavior, and performance measurements.

Keep tracking-document review lightweight and separate from code review. Port local
post-chores and QA routing, replacing Cargo, flashing, and embedded checks with applicable
MML tools. Reconcile verification policy explicitly rather than silently dropping existing
formatting, lint, suite, smoke, publishing, benchmark, or memory requirements.

## Plan

- [ ] Re-read the source system and inventory MML rules, skills, links, and task history.
  Record the y0y source revision and any relevant uncommitted source changes.
- [ ] Resolve lifecycle command semantics and document the migration/file mapping.
- [ ] Adapt `AGENTS.md`, coding/QA/tracking rules, and repo-local skills, including their
  discovery metadata. Keep one authoritative rule for each workflow obligation.
- [ ] Establish memory, task template, task files, completed history, and changelog.
  Select the initial working set with the Author; do not populate it with every task.
- [ ] Migrate current work and preserve old tracking, specifications, migration provenance,
  verification evidence, and historical completion claims without rewriting their meaning.
  Reconcile references so old and new systems do not remain competing sources of truth.
- [ ] Verify representative lifecycle commands, skill discovery, links, approval boundaries,
  review isolation, and Git Town instructions; obtain review before adoption is complete.

## Decisions to settle during implementation

- Does a direct completion/finish command authorize its associated commit as in y0y,
  or does MML retain a separate commit approval? The Author has not settled this yet.
- Use consistent errand terminology: y0y's `finish-task` skill refers to `finish task`,
  while its tracking rules define `finish errand`.
- Align every definition of memory membership with the Author's active/near-term rule.
- Determine the current branch's intended Git Town parent before changing configuration.
  At inspection, `dev-lambdas-migration` had no recorded parent and `develop` was configured
  as perennial. Its reflog alone does not prove how it was created.
- Decide which historical documents move and which remain linked references. Preserve
  the lambda migration's source/history evidence and distinguish abandoned-branch status
  from current implementation status.

## Acceptance

- Current focus is short and readable; the durable backlog remains discoverable separately.
- An agent can resume selected work from its task file without reconstructing conversation.
- Repo-local skills resolve to MML paths and commands, without stale Rust or firmware rules.
- Rules agree on approvals, lifecycle commands, commits, memory membership, and Git Town.
- Code review uses independent review and per-claim verification; tracking review stays focused.
- No automatic GitHub operations, lost task history, broken local links, or invented completion.
- No compiler behavior changes, performance work, or unrelated branch manipulation in this task.

## Working memory

The Author requested discussion first, then authorized this spec and local tracking entry.
The y0y rules and skills were inspected as source material, not installed or invoked.
The Author clarified memory membership, confirmed Git Town usage, endorsed independent
adversarial review, and placed this work before the next compiler slice. Implementation
and task signoff remain pending. Current destructor code remains under review.

The Author subsequently approved porting just `code-review` ahead of the broader migration.
`.skills/code-review/SKILL.md` and its `agents/openai.yaml` are now adapted for Scala/compiler
review; `AGENTS.md` points to local skill discovery. Isolated review, per-claim verification,
fallback approval, and narrow re-review are preserved. The broader lifecycle/context port
remains planned. The requested review of uncommitted compiler changes is paused until this
skill port is ready; no review verdict or compiler signoff is implied by creating the skill.
