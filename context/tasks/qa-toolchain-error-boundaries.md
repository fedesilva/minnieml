# Consolidate toolchain error boundaries

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

These wrap genuinely throwing JDK/process APIs, but the exception boundary is scattered.
  Some paths intentionally degrade to missing-tool or typed error results, while others ignore
  failures, including the existing `// TODO: do not swallow exceptions` near marker invalidation.

## Outcome

Revalidate and address the recorded QA concern within its stated scope.

## Scope

The retained design below defines the proposed scope; implementation requires a bounded plan.

### Retained QA report

Imported from the QA debt record. Counts, locations, freshness, and proposed
fixes describe that review; none have been reverified during this document migration.

- Location: `modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala`
- Current sites: `:100`, `:139`, `:152`, `:186`, `:203`, `:532`, `:571`, `:813`, `:825`,
  `:882`, `:891`, `:923`, `:927`, `:946`, `:956`, `:977`, `:1005`, `:1011`, `:1014`,
  `:1016`.
- Recorded status: fresh.
- Problem: These wrap genuinely throwing JDK/process APIs, but the exception boundary is scattered.
  Some paths intentionally degrade to missing-tool or typed error results, while others ignore
  failures, including the existing `// TODO: do not swallow exceptions` near marker invalidation.
- Smell level: medium
- Impact: medium. Failures from `llc`/`clang`/`opt` invocations and file operations can be
  misreported or silently dropped, masking toolchain breakage during codegen.
- Suggested direction: wrap JDK/process calls once at the FFI boundary, using
  `IO.blocking(...).attempt` or an equivalent typed adapter, and return
  `Either[LlvmCompilationError, A]` to the rest of the pipeline. Where a best-effort operation
  genuinely ignores a failure, make that boundary explicit with a short present-tense comment.
- Scope decision: file-local refactor; coordinate with whoever owns the toolchain runner.

## Plan (Approval Gate)

- [ ] Revalidate the draft against current code and agree on a bounded implementation plan.

Approval: Spec-to-task migration authorized on 2026-09-10; implementation pending.

## Implementation Checklist

- [ ] Implement and verify the approved scope.

## Verification

Document migration only; no implementation checks run.

## Risks / Notes

Imported design proposals are not evidence of implemented behavior. Recheck source-location
and baseline claims when starting this task.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Not granted.

## Task Working Memory

Created from the retained spec during context cleanup. Not selected for active memory.
Next: discuss scope when the Author selects this task.
