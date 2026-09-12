# QA misses

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-12
- **Target Branch:** Unassigned
- **External Reference (optional):** None

## Purpose

Collect concrete QA findings without interrupting ongoing work to fix each one. Select items
individually, group related items into a cleanup session, or create a dedicated task when an
item needs its own plan. A cleanup campaign can work through several approved groups.

## Recording and selection

- Give each finding a stable ID, a descriptive title, source links, the problem, and an
  expected outcome. Distinguish observed behavior from suspected consequences.
- Recording an item does not start its implementation. Agree on a bounded plan when selecting
  an item or group; follow the normal verification and signoff rules for the affected code.
- When an item becomes a separate task, link it here and keep implementation detail there.
- Mark an item resolved only with verification evidence. Keep its ID and a concise resolution
  so later references remain meaningful.

## Items

### QA-001: Preserve resolved type identity in type utilities

- **Status:** planned
- **Source:** [TypeUtils.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeUtils.scala),
  `findTypeByName`, `requiresDestruction`, `isHeapType`, and related name-based queries.
- **Problem:** `findTypeByName` receives a bare name, tries
  `stdlib::typedef::<name>` and `stdlib::typealias::<name>`, then scans indexed declarations
  for a matching name. Callers that reduce a resolved type to its name discard the declaration
  identity and force this helper to guess it again. The stdlib preference and first matching
  declaration are not substitutes for resolving the intended type by its identity.
- **Context:** [SemanticStage.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/SemanticStage.scala)
  injects stdlib declarations into each module. This explains their presence in the index,
  but does not justify preferring them in a general type-property query.
  [ResolvablesIndex.lookupType](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/module.scala)
  already supports lookup by declaration ID. `resolveNativeType` uses `resolvedId` first,
  while other queries accept only a name.
- **Phase contract:** the production callers run after type resolution. Valid nominal type
  references should identify their declarations, including references created by later
  generators. Verify those producers and handle error placeholders explicitly; an index miss
  must not silently select another declaration by name.
- **Evidence:** source inspection establishes identity loss and stdlib-first lookup. Selecting
  an unintended declaration when names collide is a risk; no failing runtime case is recorded.
- **Expected outcome:** preserve resolved types or declaration identities through type-property
  queries and their callers. Eliminate name-based guessing where identity is available.
  Define any necessary handling of unresolved references explicitly at the appropriate phase.
- **Verification:** check that distinct declarations with the same short name retain their
  identity in queries, including stdlib names and aliases. Verify native-type and destruction
  queries against the selected declaration, and run the applicable compiler gates.

## Working memory

No cleanup group is selected for implementation. Next: continue recording findings, or select
a bounded group and prepare its plan.
