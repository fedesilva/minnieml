## Finish SourceOrigin Migration

This is a high-priority consistency task.

Leaving this migration unfinished hurts compiler quality and keeps leaking fake source locations into
data paths that should use `SourceOrigin.Synth`.

## Audit Findings

1. `Name.synth` violates the `SourceOrigin` model contract.
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala:23`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala:29`
- `Name` always reports `SourceOrigin.Loc(span)`, but `Name.synth` fabricates `0,0,0`.
- Synthetic names become indistinguishable from real source-located names unless callers know the
  sentinel convention.

2. `DuplicateNameChecker` still manufactures fake `Loc(0,0,0)` when source is missing.
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DuplicateNameChecker.scala:48`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DuplicateNameChecker.scala:49`
- This defeats `spanOpt` semantics and can leak bogus locations into diagnostics.

3. Core pipeline bootstrap paths still seed dummy module spans with `0,0,0`.
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/IngestStage.scala:10`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/Compilation.scala:66`
- These are fallback/bootstrap paths, but they still encode fake source instead of synthetic origin.

4. Standard-library injection is heavily dependent on dummy spans.
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala:172`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala:373`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala:555`
- This is likely intentional for nodes that require `SrcSpan`, but it keeps the anti-pattern alive.

## Scope Snapshot

- `Name.synth(...)` usages: 123 in main, 2 in tests.
- `SourceOrigin.Synth` usages in main: 124.
- `SrcPoint(0,0,0)` in main: 8 hits across 5 files
  (`ast/common`, `DuplicateNameChecker`, `IngestStage`, `Compilation`, `semantic/package`).

## Why This Keeps Happening

- The model is only half-migrated: some nodes use `SourceOrigin`, others still require raw
  `SrcSpan`.
- There is no guardrail enforcing “no fake `Loc` spans in production code”.
- `Name` is structurally source-located, so synthetic names are currently forced through fake
  coordinates.
- AI agents failed the basic job here: they did not report that the migration was incomplete.
- The human also failed by trusting the handoff and not doing an immediate verification pass.
- Both sides missed a preventable quality gate, and the bug survived longer than it should have.
- This is exactly why we need strict short-phase checkpoints, explicit signoff, and mandatory
  review before context switching.

## Execution Plan

### Phase A - Fix `Name.synth` contract first

Goal: make synthetic names truly synthetic by origin, not fake source-located.

Target files:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala`

Tasks:
- Refactor `Name` so parser-created names preserve real `SourceOrigin.Loc(span)`.
- Update `Name.synth` so it produces `SourceOrigin.Synth` instead of a fabricated `Loc(0,0,0)`.
- Add tests that pin this behavior and protect against regressions.

Checkpoint:
- Verify no synthetic name reports a real `Loc` span.

### Phase B - Remove fake duplicate-location fallback

Goal: stop manufacturing fake locations during duplicate handling.

Target files:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DuplicateNameChecker.scala`

Tasks:
- Replace `getOrElse(SrcSpan(SrcPoint(0, 0, 0), ...))` fallback logic.
- Preserve real spans when available; keep synthetic origin when not.
- Add regression tests for duplicate declarations involving synthetic nodes.

Checkpoint:
- Diagnostics must not include fake `0,0,0` locations for synthetic duplicates.

### Phase C - Pipeline bootstrap cleanup

Goal: remove fake module bootstrap spans from ingest/fallback paths.

Target files:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/IngestStage.scala`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/Compilation.scala`

Tasks:
- Replace dummy `SrcSpan(SrcPoint(0, 0, 0), ...)` bootstrap values.
- If required, refactor `Module` source/origin representation to allow synthetic module origin.

Checkpoint:
- Ingest and fallback compile paths no longer create fake source `Loc` values.

### Phase D - Standard-library injection hygiene

Goal: keep stdlib injection synthetic without relying on fake source spans.

Target files:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala`

Tasks:
- Remove `0,0,0` sentinel spans from stdlib injection paths where feasible.
- Where a raw `SrcSpan` remains structurally required, use a dedicated synthetic sentinel
  convention (`-1`-based), never `Loc(0,0,0)`.

Checkpoint:
- Stdlib-injected nodes are consistently synthetic by origin and no longer leak fake source coords.

### Phase E - Add guardrail

Goal: make recurrence hard.

Tasks:
- Add a guard test/check that fails on newly introduced `SrcPoint(0, 0, 0)` in production code
  paths relevant to AST/source-origin.

Checkpoint:
- CI/local verification catches reintroduction of fake source-located sentinels.

## Order and Signoff Rhythm

- Execute in order: A -> B -> C -> D -> E.
- Stop after each phase for review/signoff before proceeding.
- Keep each phase small and verifiable to avoid hidden migration drift.
