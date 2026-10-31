# Configurable toolchain and vendors

## Metadata

- **Owner:** unassigned
- **Status:** design
- **Kind:** feature
- **Priority:** MID
- **Created:** 2026-10-31
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Settle pipeline configuration, vendor definitions, and artifact contracts.
2. [ ] **planned** — Express the standard LLVM build as a configurable pipeline.
3. [ ] **planned** — Add vendor acquisition, build execution, and compiler integration.
4. [ ] **planned** — Deliver a runnable facil.io HTTP example with MML owning main.
5. [ ] **planned** — Deliver a runnable raylib graphics example with MML owning main.
6. [ ] **planned** — Verify default builds, vendor builds, and examples; document usage.

## Tags

- toolchain
- native-interop
- vendors
- examples

## Problem

The LLVM toolchain assembles its build sequence in compiler code. Native libraries can be
named through `@link`, but the directive does not acquire sources, compile dependencies,
or configure library search paths. Applications using native dependencies need a repeatable
way to build those dependencies and feed their outputs into the compiler.

Additional build steps are also needed for future platforms with different compilation,
linking, or packaging requirements.

## Outcome

The compiler constructs its standard build pipeline and allows configuration to add steps.
Vendor definitions describe downloadable libraries, their build pipelines, and the artifacts
and link requirements they provide. Vendor pipelines use the same pipeline building blocks
as the main compiler pipeline, but execute independently of it.

A project vendorfile lists its vendored dependencies and their definitions. Each vendor is
keyed by the library name used in `@link`. The compiler resolves those names against the
vendorfile, runs the matching vendor pipelines, and supplies their resulting
artifacts to the main pipeline. For example, `@link["raylib"]` selects the vendor keyed
`raylib`. Ordinary system-library links remain usable without a vendor definition.

Two small MML applications demonstrate the complete workflow: an HTTP server using facil.io
and a graphical application using raylib. Both have an MML `main`; C bridges adapt foreign
APIs only where needed.

## Scope

- Preserve default executable, library, and IR behavior when no customization is supplied.
- Represent steps with explicit inputs, outputs, dependencies, commands, and target context.
- Support additional steps at defined points in the standard pipeline, including steps
  producing inputs needed by later compilation or linking.
- Define vendors with source location, immutable revision or release identity, integrity
  verification, build configuration, and declared outputs.
- Provide a versioned project vendorfile listing vendored dependencies and their build
  definitions. Listing a dependency makes it available for selection through `@link`.
- Key vendor definitions by `@link` library name. Run selected vendor pipelines independently
  of the main pipeline, using the same building blocks and execution machinery.
- Feed vendor artifacts into the main build: library directories and names, explicit object
  or archive inputs, and necessary system libraries or platform frameworks.
- Specify reuse and invalidation for downloaded sources and compiled artifacts, including
  target, toolchain, recipe, flags, and bridge-source changes.
- Keep download/build products separate from versioned recipes, bindings, and example code.
- Provide focused Metal bindings and any required C adapters for facil.io and raylib.
- Document prerequisites and commands that work from a fresh clone.

Out of scope: a package registry, dependency version solver, complete library bindings,
production HTTP features, a game engine, and implementation of additional platform backends.

## Plan (Approval Gate)

### 1. Settle the configuration and execution model

Inspect `LlvmToolchain`, compiler configuration, CLI build entry points, and existing linking
tests. Define the smallest model that supports the standard build and both vendor examples.

Decide:

- Pipeline configuration location and format, CLI selection, and precedence over defaults.
- Vendorfile syntax, filename, discovery rules, and paths relative to the project.
- Step identity, ordering, artifact references, and validation of missing dependencies or cycles.
- Which standard stages may be configured or replaced, and which invariants must be preserved.
- Host tools versus target artifacts, including target triple, CPU, sysroot, and build flags.
- Vendorfile validation, duplicate-key diagnostics, and repeated `@link` references.
- Artifact kinds, link order, static/shared selection, runtime lookup, and library-mode behavior.
- Download timing, offline reuse, cache invalidation, and cleanup boundaries.
- Command arguments, working directories, environment, diagnostics, and cancellation behavior.

Present the design and obtain approval before implementation. Keep vendor-specific knowledge
in definitions and adapters rather than adding library-name branches to compiler code.

### 2. Build the standard pipeline through the configurable model

Preserve target resolution, runtime compilation, optimization, instrumentation, output modes,
and timing/diagnostic behavior. Reuse the toolchain command runner. Validate the planned
steps before execution and report failures with the step and affected artifact identified.

Demonstrate an added step whose output is consumed by a later standard step.

### 3. Add vendors and artifact integration

Implement vendorfile loading and validation, source acquisition with pinned identity and
integrity checks, vendor build steps, and compiler consumption of the resulting artifacts.
Resolve `@link` names to vendor
keys and execute each selected vendor build independently of the main pipeline. Supply its
declared outputs to the main build without splicing vendor steps into the main pipeline.
Preserve required link order, including repeated library references where needed.
Do not mark failed or partial builds as reusable successes. A warm build should reuse valid
outputs; changed sources, recipes, targets, or relevant flags should invalidate them.

Choose and verify exact facil.io and raylib versions during implementation. Record recipes
and required license notices in versioned files. Distinguish vendor dependencies from system
development tools and platform dependencies that users must install.

### 4. Deliver the facil.io example

Use an MML entry point to start a local server at `127.0.0.1:3000`. MML handles routing:
`GET /` returns text, `GET /health` returns fixed JSON, and unknown paths return 404.

Compile the facil.io implementation and any C bridge through the vendor/build pipeline.
Use an opaque `Request` declared with `@metal:repr[t=ptr]`. Verify the callback ABI and symbol
retention in executable builds. Keep request handles borrowed during callbacks and make
response-buffer ownership explicit. No C `main` is permitted.

### 5. Deliver the raylib example

Build raylib through a vendor definition. The MML application opens a window, draws text
and a simple animated shape, processes the close condition, and releases resources.
MML owns the entry point and application loop; any C adapter is limited to ABI adaptation.

Verify the foreign signatures, including booleans, strings, and any by-value structs used.
Declare platform link requirements through the vendor artifact contract. Document graphics
and display prerequisites and distinguish compilation from an observed windowed run.

### 6. Verify and document

Run the required compiler checks and focused integration checks below. Document the
configuration model, vendorfile format, default behavior, and example build/run commands.
Include versioned vendorfiles for the runnable examples.
Obtain review and signoff at bounded stage boundaries before advancing.

Approval: pending for design and implementation choices.

## Verification

Pending implementation:

- Existing default build modes retain their behavior without vendor configuration.
- An additional step produces an artifact consumed by the standard pipeline.
- An `@link` name matching a vendor key selects its independent pipeline; ordinary system
  library names retain their existing behavior. Repeated references do not redundantly build
  the vendor, while required link ordering and repetitions are preserved.
- Vendorfile dependencies resolve by their declared keys; malformed definitions and duplicate
  keys produce actionable diagnostics. Example builds work from versioned vendorfiles.
- Vendor and main pipelines use the same building blocks without merging their step sequences.
- Invalid dependencies, missing outputs, failed commands, and checksum failures produce
  actionable errors and do not reach a successful link or poison reusable build state.
- Clean builds acquire and compile pinned vendor sources; warm builds reuse valid artifacts.
- Changes to target, toolchain, recipe, relevant flags, or bridge sources rebuild affected outputs.
- Link input ordering and declared system dependencies work for both examples.
- facil.io serves the expected status, content type, and body for all three routes, handles
  repeated requests, reports listener startup failure, and shuts down cleanly.
- raylib opens and renders its window, animates the shape, and closes cleanly on a supported
  graphical host. Headless compile checks alone do not establish this result.
- Run applicable formatting, lint, smoke, full test, and post-task checks from
  `../coding-rules.md`. Apply memory checks when ownership or callback work requires them.
- Apply required Linux ABI/container verification if ABI changes are introduced; record
  unavailable environments as unmet checks rather than successful cross-platform execution.

Record commands, results, platform details, and evidence in this task or linked versioned files.

## Risks / Notes

- Configuration must remain understandable without growing into an unrelated build system.
- MML function values carry closure environments; a foreign callback needs an explicit ABI
  contract. The facil.io example should first use a fixed handler adapter.
- Vendor artifacts must match the MML target and relevant runtime/instrumentation settings.
- Static archive linking and a fully static executable are different requirements; platform
  system dependencies may remain dynamically linked.
- Raylib's graphics requirements need a real display for runtime acceptance.
- Export naming and module integration must be checked against current compiler behavior.
  Generic callback support or module redesign requires separate scope approval if needed.

## Signoff

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: not granted

## Task Working Memory

The required deliverables are a configurable standard toolchain, downloadable/buildable
vendors consumed by the compiler, and runnable facil.io and raylib examples with MML entry
points. Vendor pipelines run independently of the main pipeline using the same building blocks.
Vendor keys match `@link` names, which trigger the compiler to run the corresponding builds.
A project vendorfile lists those dependencies and their build definitions.
Vendorfile and pipeline configuration syntax, artifact contracts, library versions, and
supported example targets remain design decisions. No implementation or runtime verification
is claimed.

Next action: inspect the existing toolchain boundaries and propose the configuration and
artifact model for approval.
