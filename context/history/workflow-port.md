# Workflow migration record

## Source and authority

- Date: 2026-09-10.
- MML baseline: `91e9fe797a7180cba7154f44c3ea93caac430b19`, clean working tree.
- y0y source: `/Users/f/Workshop/mine/y0y-dev/y0y`, revision
  `daf325cb8d4eb3b015c68bbe06dad9c7c9ced1a8`.
- The source AGENTS, rules, template, and skills have no uncommitted changes. Source task
  `first-class-clock-and-transport.md` has uncommitted progress updates; those are not imported.
  The source also has unrelated code/docs changes and a deleted flash script; none are imported.
- The Author approved the workflow plan, separately authorized pending-task cleanup and
  removal of GitHub helpers, and reserved existing specs unchanged for phase two.
- Current scope and approvals: [workflow migration task](../tasks/local-workflow-port.md).

## Final lifecycle decision

The Author signed off the migration and requested logging, task completion, and a local
commit. `finish task` and `complete task` are aliases handled by `finish-task`; both verify,
log, mark complete, remove the memory entry, and commit. `finish errand` verifies, logs,
and commits without creating or closing a tracked task. Pushing requires a request.
This supersedes the initial separate-commit policy and ambiguity recorded in the phase-one
notes and walkthroughs below. Workflow changes are included in the changelog. The completed
migration task is removed from active memory; the lambda migration remains selected.
Final staging normalized nine whitespace-only lines in the imported changelog; all original
text remains, and the archived tracker retains the exact source including whitespace.

## File mapping

| Former material | Maintained location |
| --- | --- |
| Selected current work | [memory.md](../memory.md) |
| Pending tracker entries | Individual template-based files below |
| Change Log | [changelog.md](../changelog.md), original entries verbatim |
| Entire old tracker | [historical snapshot](tracking-before-workflow-port.md), verbatim after archival preface |
| Former tracker path | Removed at the Author’s request; use task files directly |
| Existing specifications | Migrated to task content and review history; see phase two below |
| Lambda provenance and verification | Handoff and JSON merged with the lambda task; restart inventory in history |
| Completed task storage | [tasks/completed/](../tasks/completed/); no completions invented from historical claims |
| GitHub scripts | All 12 `bin/gh-*` helpers removed at the Author's request |

| Former pending heading | Task |
| --- | --- |
| Port the local collaboration workflow from y0y | [local-workflow-port](../tasks/local-workflow-port.md) |
| Revisit Linux sanitizer verification | [linux-sanitizer-verification](../tasks/linux-sanitizer-verification.md) |
| Bug: can't use ??? in an annotated fn. | [typed-hole-annotated-function](../tasks/typed-hole-annotated-function.md) |
| Document and encode the ownership rules | [ownership-rules](../tasks/ownership-rules.md) |
| QA: unify alias resolution | [unify-alias-resolution](../tasks/unify-alias-resolution.md) |
| Owned Strings | [owned-strings](../tasks/owned-strings.md) |
| Bug: function annotation arity must be disambiguated by the binder | [function-annotation-binder-arity](../tasks/function-annotation-binder-arity.md) |
| Add lambda test harness | [lambda-test-harness](../tasks/lambda-test-harness.md) |
| Context Tools | [multiagent-context-tools](../tasks/multiagent-context-tools.md) |
| Unify ownership model | [unify-ownership-model](../tasks/unify-ownership-model.md) |
| Replace magic `__stmt` detection for sequence lambdas | [sequence-lambda-metadata](../tasks/sequence-lambda-metadata.md) |
| #255 Unify lambdas | [unify-lambdas](../tasks/unify-lambdas.md) |
| Lambda lifting bug | [lambda-lifting-bug](../tasks/lambda-lifting-bug.md) |
| define new tasks | [check-command-native-validation](../tasks/check-command-native-validation.md) |
| define new tasks | [runtime-cache-location](../tasks/runtime-cache-location.md) |
| define new tasks | [sanitizer-runtime-cache](../tasks/sanitizer-runtime-cache.md) |
| define new tasks | [runtime-cache-commands](../tasks/runtime-cache-commands.md) |

The four requests under “define new tasks” are separate task files. This is bookkeeping,
not approval to implement them. Every task uses the template sections; original descriptions,
external references, and meaningful checklists are preserved or explicitly mapped. Creation
dates record task-file migration, not an inferred date for the original request.

## Adaptations and decisions

- Curated memory contains this workflow migration and the lambda migration only.
- Lifecycle commands authorize their bookkeeping but not commits. Direct combined commands
  such as `log and commit` preserve their explicit commit authority.
- `finish errand` is canonical; `finish task` requires clarification instead of guessing.
- Local lifecycle skills defer to one task-rules file. Tracking review runs in the current
  agent; code/workflow review retains the independent primary and per-claim protocol.
- The existing MML `code-review` skill and its metadata are preserved unchanged.
- MML formatting, lint, full suite, smoke, publish, benchmark, and conditional memory gates
  are retained. The conflicting suggestion to parallelize warm `sbtn` sessions is removed:
  all `sbtn` invocations remain sequential or batched, as required by `dev-tools.md`.
- Context-only work runs consistency, link, and diff checks rather than compiler gates.
- Git Town 24.0.0 help confirms `hack`, `sync`, and noninteractive shipping with an explicit
  message. Repository branch creation can publish (`share-new-branches = "push"`);
  branch operations require checking their remote effects and existing authorization.
- Git Town parent for `dev-lambdas-migration` is not recorded; `develop` is configured as
  perennial in local Git config. Configuration and branch state are untouched.
- Historical specs can contain stale tracking paths and GitHub-helper instructions. They
  remain historical design evidence, not authority to run removed helpers or synchronize
  issues. Phase two will reconcile them under the current task rules.

## Phase-one validation

- `python3 /tmp/mml-validate-workflow.py`: passes. It uses the bundled skill validator and
  checks all 14 skill definitions/UI metadata, 17 task-template structures, the two selected
  memory links, and 78 maintained local Markdown links/anchors.
- The same check compares the archived tracker and imported changelog against baseline Git
  content exactly, confirms all 12 helpers were removed, and confirms specs and the existing
  code-review skill are unchanged. Archival content retains its documented original path context.
- `git diff --check`: passes. No compiler builds are required for this workflow-only diff.
- Git Town commands were checked through installed `--help` only; no branch, sync, ship,
  configuration, commit, push, or external issue operation was executed.
- Focused tracking review: current-agent review of migration mapping, template sections,
  descriptions, pending state, historical evidence, selected memory, links, and approvals
  found no remaining actionable inconsistencies.
- Independent workflow review: a fresh primary reviewer read the source-derived packet
  without inherited conversation, inspected the complete workflow diff, and returned no
  actionable findings. It independently checked skill metadata, exact history, preserved
  specs/review files, helper removal/callers, and `git diff --check`. No candidate findings
  required per-claim verifiers. Review was read-only. Live lifecycle and Git Town mutations
  were not exercised; no compiler correctness claim is made.
- Final tracking-only review updates the recorded evidence and phase-one handoff state.
  Author signoff, phase-two work, and commit authorization remain pending.

### Static lifecycle walkthroughs

These are instruction traces, not lifecycle executions against live task state.

| Request | Authorized behavior | Commit authority |
| --- | --- | --- |
| `task add` | Create from template; memory membership only when selected | None |
| `complete subtask` | Check evidence, update only that item and existing memory, log product change | None |
| `complete task` | Require signoff and no open work; mark complete and remove memory entry | None |
| `archive task` | Move a complete task, preserve content, repair inbound and relative links | None |
| `archive cleanup` | Present exact deletion set and obtain confirmation before deletion | None |
| `finish errand` | Verify and log untracked product work; remove its reminder | None |
| `finish task` | Clarify intended lifecycle operation | None |
| `log and commit` | Execute the explicitly scoped log and commit; preserve unrelated work | Explicit |
| `show current work` | Read selected memory and linked tasks | Read-only |
| `list open tasks` | Enumerate task files, excluding template and completed tasks | Read-only |

Required review isolation remains in the unchanged MML code-review protocol: fresh primary
reviewer, one fresh verifier per atomic claim, read-only investigation, and confirmed findings
only. Tracking review itself stays in the current agent.


### Tracker redirect removal

The Author requested removing `context/tracking.md` after phase-one review. The redirect
file and its active rule/mapping references are removed. Historical records and the
phase-two specs remain unchanged. This navigation-only follow-up uses focused tracking
review; the independent workflow review above predates this removal.

Follow-up validation passes: 52 maintained links resolve, historical content matches the
baseline, specs remain unchanged, and `git diff --check` is clean. Focused tracking review
found no remaining navigation inconsistencies.

## Phase two: retire the specs directory

The Author authorized removing `context/specs/`, retaining valuable material in relevant
tasks, and archiving the remainder for later review. This supersedes phase one's temporary
requirement to leave specs unchanged. No task implementation or completion is implied.

| Former spec | Destination |
| --- | --- |
| `238-compile-prelude.md` | [Prelude task](../tasks/compile-prelude.md#retained-prelude-design) |
| `global-origin-ownership.md` | [Global ownership task](../tasks/global-origin-ownership.md#global-ownership-design) |
| `owned-string-literals.md` | [Owned strings proposal](../tasks/owned-strings.md#owned-string-literal-proposal) |
| `bug-lambda-lifter.md` | [Capture report](../tasks/lambda-lifting-bug.md#duplicate-capture-report) |
| `pap-ownership-model.md` | [Lambda task PAP contract](../tasks/unify-lambdas.md#partial-application-ownership-contract) |
| `unify-lambdas.md` | [Semantic goals](../tasks/unify-lambdas.md#semantic-goals); [full source design for review](unify-lambdas-design.md) |
| `unify-lambdas-plan.md` | [Historical implementation plan](unify-lambdas-plan.md) |
| `lambda-unify-rescue.md` | [Source-branch architectural review](lambda-unify-rescue.md) |
| `lambdas-work-review.md` | [Earlier closure review](lambdas-work-review.md) |
| `optional-moves.md` | [Superseded move proposal](optional-moves.md) |
| `qa-lsp.md` | [Dated LSP review](qa-lsp.md) |
| `typed-closure-destruction.md` | [Signed-off implementation plan](typed-closure-destruction.md) |
| `local-workflow-port.md` | Removed as redundant with current rules and the migration task |

Archived source bodies are preserved verbatim after a provenance preface. Their old paths,
commands, suggestions, and completion claims are historical, not current instructions.
Task material preserves the design and reproduction details; global ticket-writing advice
and the obsolete PAP slice label are omitted. The prelude and global ownership proposals
become two planned tasks without adding them to selected memory. The existing 17 tasks remain.

Phase-two validation passes: exact source archives, retained task material, migrated evidence,
reference targets, and the root layout. Compiler builds are inapplicable to this migration.

### Root-level context consolidation

The Author extended the cleanup to root-level context documents. Current lambda evidence
and its standing handoff requirement are in [the lambda task](../tasks/unify-lambdas.md#migration-handoff),
with its [JSON inventory](../tasks/unify-lambdas-migration.json). The historical
[restart inventory](unify-lambdas-salvage.md) remains available for review.

[Readability](../tasks/qa-readability.md) and the five QA debt reports have planned tasks:
[ownership assertions](../tasks/qa-ownership-assertions.md),
[toolchain errors](../tasks/qa-toolchain-error-boundaries.md),
[topological ordering](../tasks/qa-topological-order.md),
[nonempty invariants](../tasks/qa-head-invariants.md), and
[printer line lengths](../tasks/qa-error-printing-lines.md).
Source-review status and counts are retained as dated evidence, not fresh findings.
No new tasks were added to selected memory or marked complete.

### Final consolidation validation

- `python3 /tmp/mml-check-spec-transfer.py` passes: seven exact archived spec bodies,
  five retained design/report transfers, and the lambda semantic sections.
- `python3 /tmp/mml-validate-workflow.py` passes: 14 local skills and metadata, 25 task
  files following the template, 84 maintained inline links/anchors, exact original tracker
  and changelog history, absent specs/helpers, and the two selected memory tasks.
- Reference-style task links resolve. The full lambda handoff transfers with mechanical
  path/heading changes and removal of obsolete workflow scheduling. JSON is byte-identical;
  the restart archive preserves its source body exactly.
- The context root contains only memory, changelog, and four instruction files; its only
  subdirectories are tasks and history. The unused empty images directory is removed.
- `git diff --check` passes. No compiler code, build outputs, Git Town configuration,
  commits, pushes, or external issue state changed.
- A fresh independent primary reviewer found no actionable consolidation findings after
  checking preserved content, JSON, archives, reference targets, and root boundaries.
  No candidates required per-claim verification. Historical compiler assessments and
  execution results were not revalidated. Final tracking-only handoff edits were checked
  in the current agent; Author signoff and tracked-item completion remain pending.
