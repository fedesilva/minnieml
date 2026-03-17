## Finish SourceOrigin Migration

This is a high-priority consistency task.

Leaving this migration unfinished hurts compiler quality and keeps leaking fake source locations into
data paths that should use `SourceOrigin.Synth`.

## Non-Negotiable Invariant

- No AST node may carry a naked `SrcSpan` field.
- Source location is represented only by `SourceOrigin`:
  - `SourceOrigin.Loc(span)` when the node is from real source.
  - `SourceOrigin.Synth` when the node is compiler-synthesized.
- Any API that still requires a concrete span must explicitly pattern-match on `SourceOrigin`.
  It must never infer "real source" from sentinel coordinates.
- `SrcPoint(0,0,0)` is forbidden as a source-origin fallback.

## CRITICAL Scope Lock (Prominent, Non-Optional)

- This migration is **NOT** complete until **ALL** remaining AST nodes are migrated from raw
  `SrcSpan` fields to explicit `SourceOrigin`.
- Partial fixes are invalid for this workstream. Cleaning one area (e.g. stdlib injection) while
  leaving `terms.scala` / `types.scala` span-based is still an incomplete migration.
- Required end-state:
  - `common.scala`: source-bearing nodes use `SourceOrigin`.
  - `members.scala`: source-bearing nodes use `SourceOrigin`.
  - `terms.scala`: source-bearing nodes use `SourceOrigin`.
  - `types.scala`: source-bearing nodes use `SourceOrigin`.
  - No production fallback may fabricate coordinates for synthetic nodes.

## Audit Findings

1. `Name.synth` violates the `SourceOrigin` model contract.
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala:23`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala:29`
- `Name` currently carries a naked span field, which bypasses the model.
- `Name.synth` was implemented via fabricated coordinates, creating an implicit sentinel contract.
- This is exactly the anti-pattern we are removing globally.

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

- The model is only half-migrated: some nodes use `SourceOrigin`, while others still carry raw
  `SrcSpan` fields.
- There is no guardrail enforcing “no fake `Loc` spans in production code”.
- There is no explicit hard invariant banning naked spans in AST nodes.
- AI agents failed the basic job here: they did not report that the migration was incomplete.
- The human also failed by trusting the handoff and not doing an immediate verification pass.
- Both sides missed a preventable quality gate, and the bug survived longer than it should have.
- This is exactly why we need strict short-phase checkpoints, explicit signoff, and mandatory
  review before context switching.

## Execution Plan

### Phase A - Establish and enforce the AST source model at the root

Goal: remove naked span semantics at the root (`Name`) and lock the invariant with tests.

Target files:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala`

Tasks:
- Refactor `Name` to carry source via `SourceOrigin` only (no standalone `span` field).
- Ensure parser-created names use `SourceOrigin.Loc(realSpan)`.
- Ensure synthesized names use `SourceOrigin.Synth` (no fake source coordinates).
- Add tests that enforce this contract and fail on regressions.

Checkpoint:
- `Name` has no naked span field.
- `Name` source is always explicit (`Loc` or `Synth`), never inferred from coordinates.

### Phase B - Remove fake duplicate-location fallback

Goal: stop manufacturing fake locations during duplicate handling.

Target files:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DuplicateNameChecker.scala`

Tasks:
- Replace `getOrElse(SrcSpan(SrcPoint(0, 0, 0), ...))` fallback logic.
- Preserve real spans only when available via `SourceOrigin.Loc`; otherwise keep synthetic origin.
- Add regression tests for duplicate declarations involving synthetic nodes.

Checkpoint:
- Diagnostics must not include fake `0,0,0` locations for synthetic duplicates.

### Phase C - Remove naked spans from bootstrap/module ingest paths

Goal: remove fake module bootstrap spans from ingest/fallback paths.

Target files:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/IngestStage.scala`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/Compilation.scala`

Tasks:
- Replace dummy bootstrap spans with explicit synthetic origins.
- Refactor module/source-bearing nodes that still require naked spans to use `SourceOrigin`.

Checkpoint:
- Ingest and fallback compile paths carry synthetic origin explicitly and do not create fake spans.

### Phase D - Migrate ALL remaining AST nodes away from naked spans

Goal: complete the half-migrated model by removing raw `SrcSpan` fields from all remaining
source-bearing AST nodes.

Target files:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/types.scala`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/members.scala`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/common.scala`
- all direct call sites that construct or consume those nodes

Tasks:
- Refactor remaining span-based AST node definitions to carry `source: SourceOrigin`.
- Keep parser-origin nodes explicit with `SourceOrigin.Loc(realSpan)`.
- Keep compiler-synthesized nodes explicit with `SourceOrigin.Synth`.
- Update all constructors/call sites accordingly (parser, semantic phases, codegen, lsp, tests).
- Remove synthetic-span adapter patterns and sentinel-coordinate fallbacks.

Checkpoint:
- No remaining source-bearing AST node stores naked `SrcSpan`.
- Any concrete-span consumer reads from `source.spanOpt` / explicit `Loc` match only.

### Phase E - Remove naked span dependencies from stdlib injection and builders

Goal: keep stdlib injection synthetic without relying on fake source spans, after Phase D unblocks
the AST model.

Target files:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala`

Tasks:
- Remove span-sentinel usage from stdlib builders.
- Ensure injected nodes are created with explicit `SourceOrigin.Synth`.
- Preserve real locations only where parser-origin values are genuinely present.

Checkpoint:
- Stdlib-injected nodes are synthetic by explicit origin and do not depend on span sentinels.

### Phase F - Add guardrail

Goal: make recurrence hard.

Tasks:
- Add guard checks that fail when:
  - new naked `SrcSpan` fields are introduced in AST/source-bearing nodes
  - `SrcPoint(0,0,0)` is introduced as a source-origin fallback in production paths

Checkpoint:
- CI/local verification catches both naked-span reintroduction and fake-span fallback patterns.

## Order and Signoff Rhythm

- Execute in order: A -> B -> C -> D -> E -> F.
- Stop after each phase for review/signoff before proceeding.
- Keep each phase small and verifiable to avoid hidden migration drift.
