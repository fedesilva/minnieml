# Fix correctness bugs exposed by the animated A* sample

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG, CORRECTNESS, CRASH
- **Priority:** HIGH
- **Created:** 2026-10-06
- **Target Branch:** unassigned
- **External Reference:** None

## Execution Checklist

1. [ ] **planned** — Reproduce and reduce the five findings below; establish repair boundaries.
2. [ ] **planned** — Repair global integer constants, conditional IR generation, and string
   return ownership with focused regression coverage.
3. [ ] **planned** — Initialize every wall-map cell in `astar3.mml` before searching or printing.
4. [ ] **planned** — Diagnose the copied executable's failure on macOS M2 and verify a
   compatible build configuration.
5. [ ] **planned** — Run applicable compiler and sample verification and obtain signoff.

## Problem

Five findings concern ordinary MML source patterns, the console A* sample, and portability
of the animated executable to macOS M2.
The [animated sample](../../mml/samples/astar3_animated.mml) uses working source forms and
explicit grid initialization, but does not repair the compiler or the console sample.
Keep these findings together for triage; separate repair tasks only when their boundaries
are established.

### 1. Global integer literals fail code generation

This standalone program should print `30`:

```mml
pub fn main(): Unit = println (int_to_str value);;
let value = 30;
```

Observed diagnostic:

```text
Code generation error: Unresolved type reference: Int
(TypeRef with no resolvedId - probably typechecker bug)
```

Explicit annotations did not solve the failure: the standalone `Int32`-annotated form
and an `Int`-annotated version of the sample's integer constants also failed. The minimal
program compiled when its initializer was `0 + 30`; this difference is diagnostic evidence,
not an acceptable source requirement. The animated sample expresses named codes as fields
of scalar records and uses nullary inline functions for individual integer constants.

Acceptance: inferred and explicitly typed integer constants compile and execute correctly
without arithmetic or record wrappers. Cover references before and after their declaration.

### 2. Conditional expressions can produce invalid LLVM PHI nodes

#### Conditional call argument in a tail-recursive local function

In `make_grid` in the linked animated sample, replace:

```mml
let value = if wall then 1; else 0;;
ar_int_set walls i value;
```

with the observed failing form:

```mml
ar_int_set walls i (if wall then 1; else 0;);
```

Keep the surrounding local `fill` function, its earlier conditional `wall` binding, and
its final recursive call. LLVM assembly verification reported:

```text
PHI node entries do not match predecessors!
Instruction does not dominate all uses!
```

The emitted loop PHI named an earlier conditional merge as its back-edge predecessor,
while the recursive call followed another merge. Binding the argument before the call
compiled successfully. The diagnostic appeared with the installed compiler; the corrected
source also compiled with the repository compiler. Reduce and recheck the failing form
against the repository compiler when beginning repair.

#### Allocating caption-return branches

A separate occurrence used the caption reproduction in finding 3, with the static terminal
messages replaced by concatenations:

```mml
// Terminal branches of event_caption:
elif event.kind == event_kind.found then "Goal reached! " ++ "Reveal the final path.";
else "No path found. " ++ "The queue is empty. Close the window when done.";

// First branch of event_detail:
if event.kind == event_kind.exhausted then
  "All reachable candidates " ++ "have been processed.";
elif event.kind == event_kind.skip then
  // Keep the remaining skip and cost branches from the linked sample.
```

With these changes, the repository compiler emitted a string-valued PHI whose incoming
label was `else6` although the value came through `merge25`. LLVM also reported that the
incoming value did not dominate its use. These labels identify the observed symptom;
tests should check valid control flow rather than literal label names.

Whether the two occurrences share a root cause is unconfirmed.

Acceptance: both source shapes generate LLVM that passes verification, preserve branch
evaluation and cleanup, and execute correctly. Cover tail calls and nested allocating branches.

### 3. Mixed literal/allocated string returns can free invalid storage

The failing caption version can be reconstructed from the linked animated sample:

1. In `draw_scene`, replace the special handling of `found` and `exhausted` events with
   unconditional calls to `draw_label (event_caption event)` and
   `draw_label (event_detail event)`, keeping their existing coordinates and colors.
2. In `event_caption`, replace its final `else` branch with:

   ```mml
   elif event.kind == event_kind.finish then "Finished checking neighbors of " ++ pos ++ ".";
   elif event.kind == event_kind.found then "Goal reached! Reveal the final path.";
   else "No path found. The queue is empty. Close the window when done.";
   ```

3. In `event_detail`, prepend the following branch and change the existing first `if`
   for `event_kind.skip` to `elif`:

   ```mml
   if event.kind == event_kind.exhausted then "All reachable candidates have been processed.";
   ```

Compile with AddressSanitizer (`-s`) and exercise a full-height barrier with wall arguments
`25 0 10`, using a high fourth argument such as `100001` to reach the final caption quickly.
Raylib must initialize a window successfully for this graphical reproduction.

The observed crash reported a write fault in AddressSanitizer's allocator deallocation,
followed by `free` and the generated `draw_scene` function. The failing terminal branches
return string literals while other branches return allocated concatenations. This identifies
the source pattern and cleanup failure, but does not establish the exact faulty ownership pass.

The animated sample draws static terminal messages directly and keeps the dynamic caption
functions on allocating return paths. Its caption paths pass sanitizer checks in that form.

Acceptance: both static and allocated return branches remain valid after the callee returns;
caller cleanup never frees literal storage and releases owned storage exactly once. Reduce
the failure to a headless regression so raylib and monitor access are not test dependencies.

### 4. `astar3.mml` reads uninitialized wall-map cells

In [astar3.mml](../../mml/samples/astar3.mml), `demo` constructs `Grid` with
`ar_int_new size`, then writes only obstacle cells in `build_vertical_wall`. Search and
printing read the other cells without initialization.

The `ar_int_new` implementation in
[mml_runtime.c](../../modules/mmlc-lib/src/main/resources/mml_runtime.c) allocates with
`malloc` and returns that storage without clearing it. Open cells therefore have unspecified
contents; apparent success with fresh zero-filled memory does not establish correctness.

Acceptance: initialize all cells to open before placing walls. Verify the default obstacle,
no wall, clipped walls, and a full-height barrier without relying on allocator contents.

### 5. Copied executable fails on macOS M2 with an illegal hardware instruction

Reported setup: the animated executable was built on the development Mac and copied to
an Apple M2 machine, also running macOS. Raylib was installed on the destination, and the
library search path was configured as on the development machine. Launching the copied
executable produced:

```text
illegal hardware instruction
```

This failure has not been independently reproduced. The exact executable build flags,
destination macOS and raylib versions, and faulting instruction remain unconfirmed.
Collect those details and a crash backtrace, then check the generated code's CPU feature
requirements and compare the copied executable with a build made on the M2. The message
alone does not establish the cause or identify which executable or library instruction failed.

Acceptance: identify the cause, document the supported build and deployment configuration,
and verify that an executable built for that configuration runs on the macOS M2 machine.

## Outcome

Ordinary global constants, conditional expressions, and mixed string return paths compile
and execute safely. The console A* grid is deterministic and fully initialized. A supported
build configuration for the animated executable is verified on macOS M2. Every
finding has a focused regression or sample check that fails for the defective behavior.

## Scope

- In scope: the five findings, their reductions, directly affected compiler paths, regression
  coverage, wall-map initialization in `astar3.mml`, and executable portability diagnosis.
- Out of scope: animation controls, new language features, raylib changes, and unrelated cleanup.

## Plan (Approval Gate)

- [ ] Reproduce the embedded examples and source substitutions, then propose bounded repairs.
- [ ] Establish whether the PHI occurrences share a cause and whether string-return repair
  belongs with [conditional ownership hardening](conditional-ownership-witnesses.md).
- [ ] Diagnose the macOS M2 launch failure and establish a compatible build configuration.
- [ ] Implement approved repairs, add regressions, and run applicable verification.

Approval: pending for bug-fix implementation.

## Verification

Evidence for findings 1–4: 2026-10-06, macOS arm64, repository base `8143f27`.

- The minimal global-literal failure and conditional-argument PHI failure were observed with
  installed compiler `1629660`; the full sample's global-constant failure was also observed
  through `sbtn` at the repository base.
- The caption-return rewrite failed LLVM verification through the repository compiler.
- The mixed-return no-path caption crashed in an AddressSanitizer build after successful
  raylib window initialization. The diagnostic stack and reconstruction are embedded above.
- The uninitialized grid follows directly from the linked sample and runtime source.
- Finding 5 is a user-reported macOS M2 launch failure dated 2026-10-06; independent
  reproduction, binary identification, and diagnosis remain pending.
- Minimal headless reductions for the PHI and string-return findings remain pending.
  No compiler repair or regression-suite pass is claimed for this task.

## Risks / Notes

- [Conditional ownership hardening](conditional-ownership-witnesses.md) already covers mixed
  return ownership. Reconcile overlapping repairs during triage without assuming the same cause.
- A raylib window-initialization failure is a separate environmental condition, not evidence
  of the mixed-return bug. Require successful initialization before interpreting a GUI crash.
- LLVM PHI failures and runtime cleanup failures are distinct observations even when the
  triggering source involves the same caption functions.

## Signoff

- Workstream signoff: pending for bug repairs.
- Tracked item completion: pending.
- Commit authorization: granted for this task definition only; bug-fix implementation pending.

## Task Working Memory

The five findings are retained as one planned task. Reproduction details and evidence limits
are embedded in this file and reference versioned source. Next action: reduce the compiler
failures, diagnose the M2 launch failure, reconcile the ownership overlap, and propose repair
boundaries before implementation.
