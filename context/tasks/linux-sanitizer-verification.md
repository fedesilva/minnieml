# Revisit Linux sanitizer verification

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** None recorded.

## Problem

Deferred at the Author's request; keep this item local to the repository.

The Docker setup (`packaging/docker/linux-builder-shell.sh`) runs ARM64 Ubuntu 25.10
with LLVM/Clang 20.1.8. Ordinary smoke programs and benchmark builds pass, except for
the known `partial-fac1` exit 139. The memory harness reports 0/34: all fail compilation
before sanitizer execution. An `arrays-mem.mml` diagnostic shows generated
`asan_globals` assembly rejected with `Linkage must be 'comdat'`.

ASan itself works in the container: a C probe correctly reports a deliberate
heap-use-after-free. This is a sanitizer build blocker, not a memory-safety result.


Details and logs: [Linux verification follow-up](unify-lambdas.md#linux-verification-follow-up).

## Outcome

Classify and resolve the Linux sanitizer build blocker, then record smoke, benchmark, and memory-harness results separately.

## Scope

- In scope: The problem and outcome above; detailed scope is settled in the approved plan.
- Out of scope: Unrelated work and changes not covered by an approved plan.

## Plan (Approval Gate)

- [ ] Reproduce and isolate the assembly rejection using the existing Docker scripts.
- [ ] Fix the toolchain/integration issue without hardcoded CPUs or disabling sanitizers.
- [ ] Rerun Linux smokes, benchmarks and the full memory harness; classify remaining
  compiler failures separately from runtime ownership failures.

Approval: Pending implementation planning; tracking migration approved on 2026-09-10.

## Implementation Checklist

- [ ] Execute and verify the scope once its plan is approved.

## Verification

No new implementation verification during the tracking migration.

## Risks / Notes

Migrated from the former tracker on 2026-09-10. Migration preserves pending scope;
it does not establish implementation progress, approval, or completion.

Deferred by the Author. The recorded sanitizer failure is a build blocker, not a runtime memory-safety result.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: Not granted for this task by the migration request.

## Task Working Memory

Deferred. Resume only when selected by the Author. Preserve the diagnostic and
verification limits in the linked migration handoff; no GitHub changes are authorized.
