# Fix correctness bugs exposed by the animated A* sample

## Metadata

- **Owner:** The Author
- **Status:** in_progress
- **Kind:** BUG, CORRECTNESS, CRASH
- **Priority:** HIGH
- **Created:** 2026-10-06
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference:** None

## Execution Checklist

1. [ ] **in_progress** — Establish repair boundaries for findings 2 and 5.
2. [ ] **in_progress** — [Global literal repair](#global-literal-repair) and
   [string-return ownership repair](#string-return-ownership-repair) are complete;
   conditional IR generation remains pending.
3. [x] **complete** — [Integer-array fill and wall-map repair](#integer-array-fill-and-wall-map-repair)
   passes compiler checks, sample verification, and independent review; repair signoff is granted.
4. [ ] **planned** — Diagnose the copied executable's failure on macOS M2 and verify a
   compatible build configuration.
5. [ ] **planned** — Run applicable compiler and sample verification and obtain signoff.

## Problem

Five findings concern ordinary MML source patterns, the console A* sample, and portability
of the animated executable to macOS M2.
The [animated sample](../../mml/samples/astar3_animated.mml) uses plain global integer
constants and explicit grid initialization. It retains the conditional-argument workaround
for finding 2. Finding 3 restores ordinary caption returns with literal terminal messages.
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
not an acceptable source requirement. The animated sample defines its named codes and scalar
constants with plain global integer bindings.

Acceptance: inferred and explicitly typed integer constants compile and execute correctly
without arithmetic or record wrappers. Cover references before and after their declaration.

The reduced failure depends on declaration order and also affects Boolean, string, and
float literals. `TypeResolver` resolves the initializer's literal type but leaves the
binding's cached type unresolved. `TypeChecker` treats a nonempty cached type as ready,
so an earlier reference copies that unresolved type before the binding is checked.
An arithmetic initializer has no cached type and therefore creates an ordering dependency.
The affected resolution and ordering logic predates the Int32 change in `a201c4bc`.

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
elif event.kind == event_kind_found then "Goal reached! " ++ "Reveal the final path.";
else "No path found. " ++ "The queue is empty. Close the window when done.";

// First branch of event_detail:
if event.kind == event_kind_exhausted then
  "All reachable candidates " ++ "have been processed.";
elif event.kind == event_kind_skip then
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

The failing caption version can be reconstructed from the workaround source at `d1f744f`
using the substitutions below. The linked animated sample includes these return shapes directly.

1. In `draw_scene`, replace the special handling of `found` and `exhausted` events with
   unconditional calls to `draw_label (event_caption event)` and
   `draw_label (event_detail event)`, keeping their existing coordinates and colors.
2. In `event_caption`, replace its final `else` branch with:

   ```mml
   elif event.kind == event_kind_finish then "Finished checking neighbors of " ++ pos ++ ".";
   elif event.kind == event_kind_found then "Goal reached! Reveal the final path.";
   else "No path found. The queue is empty. Close the window when done.";
   ```

3. In `event_detail`, prepend the following branch and change the existing first `if`
   for `event_kind_skip` to `elif`:

   ```mml
   if event.kind == event_kind_exhausted then "All reachable candidates have been processed.";
   ```

Compile with AddressSanitizer (`-s`) and exercise a full-height barrier with wall arguments
`25 0 10`, using a high fourth argument such as `100001` to reach the final caption quickly.
Raylib must initialize a window successfully for this graphical reproduction.

The graphical crash reported a write fault in AddressSanitizer's allocator deallocation,
followed by `free` and the generated `draw_scene` function. A
[headless reduction](#headless-confirmation-of-finding-3) confirms that the reconstructed
caption functions return literal storage which their caller passes to `__free_String`.

The workaround at `d1f744f` draws static terminal messages directly and keeps the dynamic caption
functions on allocating return paths. Its caption paths pass sanitizer checks in that form.

Acceptance: both static and allocated return branches remain valid after the callee returns;
caller cleanup never frees literal storage and releases owned storage exactly once. Preserve
the headless reduction as regression coverage without raylib or monitor dependencies.

#### Headless confirmation of finding 3

Evidence: 2026-10-06, repository compiler at `d1f744f`, macOS arm64, Homebrew LLVM 23.1.1.
The compiler source is unchanged. The following complete program reproduces the failure:

```mml
fn caption(allocated: Bool): String =
  let text = int_to_str 123;
  if allocated then text ++ "!";
  else "abc";
  ;
;

pub fn main(args: StringArray): Unit =
  println (caption (ar_str_len args > 0));
;
```

Save the embedded source as `caption_return.mml`. Compile it through the repository compiler:

```sh
sbtn "run -s -O0 caption_return.mml"
./build/target/captionreturn
./build/target/captionreturn allocated
```

Repeat compilation with `-O3`. Both builds pass `llvm-as` verification. With no program
arguments, the literal branch terminates with SIGABRT and an AddressSanitizer report in
`__free_String` at both optimization levels. With one argument, the allocating branch prints
`123!`, exits zero, and reports no sanitizer error at both levels.

The unoptimized IR constructs the literal result from the module's constant `abc` bytes and
returns it without a clone. The caller prints that result and unconditionally invokes the
String destructor on it. The runtime destructor calls `free` on the data pointer. This
establishes the incorrect literal cleanup independently of the graphical stack trace.

Additional return-shape probes at `-O0`, using runtime-selected inputs:

| Function body | Literal path | Allocating paths |
| --- | --- | --- |
| `if flag then int_to_str 123; else "abc";` | Pass | Pass |
| Embedded `caption` above | SIGABRT | Pass |
| `if kind == 0 then int_to_str 123; elif kind == 1 then int_to_str 456; else "abc";` | SIGABRT | Both pass |
| `if flag then "abc"; else let text = int_to_str 123; text ++ "!";` | SIGABRT | Pass |

Each probe returns `String`; its caller passes the result to `println`. Passing paths exit
zero with the expected text and no sanitizer diagnostic. Failing paths report a sanitizer
error during cleanup. These results show that a simple mixed return works, while local scopes
and nested branches expose gaps.

The sample connection was checked by extracting `Event`, its constants, `coordinates`,
`is_neighbor_event`, `reason_text`, and the reconstructed caption functions above. A headless
entry point constructs `Event kind 0 25 3 10 20 1 999999999` and passes either caption result
to a borrowing `show(text: String): Unit = println text` helper. At `-O0` with ASan:

- `event_caption`: kinds 1 and 3 pass; terminal kinds 8 and 9 fail during cleanup.
- `event_detail`: kinds 1, 3, and 8 pass; terminal kind 9 fails during cleanup.

The generated sample IR likewise returns the terminal literals without cloning and destroys
the results in the caller. Raylib, window initialization, and the search loop are unnecessary
to reproduce this defect.

The repair boundary is return ownership in
[OwnershipAnalyzer.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala).
At `d1f744f`, `promoteStaticBranchesInReturn` only handles an immediate conditional, without recursively
following nested return branches or local-binding wrappers. Its allocation queries must also
remain valid after ownership rewrites. A repair must align these decisions with caller cleanup
while preserving exactly-once evaluation and borrowed-return rejection. The broader consuming
transfer failures in [conditional ownership hardening](conditional-ownership-witnesses.md)
remain separate acceptance obligations; this reproduction does not establish their repair.

### 4. `astar3.mml` reads uninitialized wall-map cells

In [astar3.mml](../../mml/samples/astar3.mml), `demo` constructs `Grid` with
`ar_int_new size`. Allocation leaves the cells uninitialized; writing only the obstacle
cells is insufficient because search and printing read the other cells.

The `ar_int_new` implementation in
[mml_runtime.c](../../modules/mmlc-lib/src/main/resources/mml_runtime.c) allocates with
`malloc` and returns that storage without clearing it. Apparent success with fresh
zero-filled memory does not establish correctness. `demo` calls `ar_int_fill grid.walls 0`
before `build_vertical_wall` to initialize every open cell.

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

Approval: granted for the global literal repair, integer-array fill repair, and bounded
string-return ownership repair below. Implementation approval for findings 2 and 5 remains pending.

### String-return ownership repair

- [x] **complete** — Follow owned return values through local scopes and nested conditionals.
  Promote static result branches through the registered clone contract while preserving owned
  results, borrowed-return rejection, source evaluation order, and allocation facts across rewrites.
- [x] **complete** — Add semantic and headless native regressions for both branch orders,
  nested alternatives, local scopes, owned aliases, static/allocated controls, and effect counts.
  Verify LLVM and execute every selected branch with ASan at `-O0` and `-O3`.
- [x] **complete** — Restore the animated sample's ordinary caption calls and literal terminal
  returns; verify extracted caption functions without raylib or a monitor.
- [x] **complete** — Compiler handoff gates, memory checks, QA enforcement, and
  independent review pass; repair signoff is granted.
- Repair signoff and local commit authorization: granted.
- Local commit: `Fix nested mixed string returns`.

The repair covers result expressions through scopes and conditionals. Returns of named
mixed-ownership bindings and their aliases still require witness-aware escape/transfer handling
under [conditional ownership hardening](conditional-ownership-witnesses.md). That task's
consuming-transfer acceptance obligations remain open. Findings 2 and 5 are outside this repair.

#### Separate conditional return-lifetime gap

An additional probe allocates an owned local before a conditional and returns that local only
on one branch:

```mml
fn pick(flag: Bool): String =
  let text = int_to_str 123;
  let alias = text;
  if flag then alias;
  else "abc";
  ;
;
pub fn main(): Unit = println (pick false);;
```

With return promotion repaired, `pick false` leaks the four-byte allocation at both `-O0`
and `-O3` under ASan+LSan. The existing escape check exempts the local from cleanup when any
return path references it; it does not release that local on the other path. This is a
branch-dependent ownership transfer/cleanup obligation for conditional ownership hardening,
outside the caption repair. The bounded alias regression allocates inside the returning
branch and checks that its returned owned value is neither cloned nor freed in the callee.

### Global literal repair

- [x] **complete** — Resolve global literal types before forward references use them.
- [x] **complete** — Remove the animated sample's integer-constant workarounds.
- `TypeResolver` resolves existing binding and expression `typeSpec` values with
  `resolveTypeSpecWithMap`, preserving absent types, nominal aliases, diagnostics, and indexes.
- The resolver boundary and resolved reference identities are covered in
  [TypeResolverTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeResolverTests.scala).
  Cases include both declaration orders, `Int` and `Int32` binding annotations, a term annotation,
  Boolean/string/float literals, arithmetic initialization, and incompatible annotations.
- Both declaration orders pass LLVM assembly and sanitizer execution in
  [RuntimeTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/RuntimeTests.scala).
- The animated sample replaces `infinity` and `event_width` functions and the `EventKinds`,
  `RejectionReasons`, `CellStates`, and `Layout` constant records with 24 plain global
  integer bindings. Values, declaration placement, and group prefixes are preserved.
- Compiler handoff checks and independent review pass. Global literal repair and sample
  cleanup are complete and signed off. Local commit: `Fix forward references to global literals`.
  Findings 2, 3, and 5 remain open.

### Integer-array fill and wall-map repair

- [x] **complete** — Expose `ar_int_fill(arr: IntArray, value: Int): Unit` through the
  native runtime, compiler injection, and prelude. Fill the array's full stored length;
  borrow its storage and accept empty arrays without changing allocation behavior.
- [x] **complete** — Initialize `grid.walls` to zero before placing the console sample's wall.
- [x] **complete** — Verify empty, single-element, and multi-element arrays, nonzero values,
  Int32 limits, and repeat fills. Check every grid cell independently of allocator contents
  and exercise the default, empty, clipped, and full-barrier cases.
- [x] **complete** — Compiler handoff checks, QA enforcement, and independent review pass.
- Repair signoff: granted. Local commit: `Add runtime integer-array fill`.
  Findings 2, 3, and 5 remain open.

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
- Minimal headless reductions for the PHI findings remain pending. Finding 3 has a
  [confirmed headless reduction](#headless-confirmation-of-finding-3) at `d1f744f`.

String-return repair evidence: 2026-10-06, macOS arm64, Homebrew LLVM 23.1.1,
target `arm64-apple-darwin25.6.0`.

- The initial 27-check return matrix produced 17 failures and 10 passes before the compiler
  repair. Failures included missing clone insertion, invalid frees at `-O0` and `-O3`, and
  LLVM verification failure when an entire nested static conditional was cloned.
- [StringReturnTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/StringReturnTests.scala)
  passes all 27 tests with the bounded alias case described above. Eight semantic checks use
  resolved clone/destructor identities; eighteen native checks verify LLVM and execute three
  runtime-selected paths each under ASan+LSan at `-O0` and `-O3`. A negative test preserves
  borrowed-return rejection through nested scopes. Counter checks establish exactly-once
  predicate, initializer, and selected-value effects.
- [caption-returns.mml](../../tests/mem/caption-returns.mml) preserves the sample's `Event` and
  five caption/helper functions and the minimal reproduction. It checks 27 complete strings
  across every event kind, previous-cost alternative, and rejection reason through borrowing
  calls and caller cleanup. Both reduced branches execute on every run.
- `sbtn "run -s -O0 -o build/finding3-caption-o0 tests/mem/caption-returns.mml"` and its `-O3`
  counterpart compile successfully. Execute each with zero arguments and with `allocated`,
  setting `ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1`. All four runs exit
  zero, print 27 checked results, and report no sanitizer diagnostic.
- `sbtn scalafmtAll` and `sbtn scalafixAll` pass. `./tests/smoke/run.sh all` passes all 8 checks.
- `sbtn test` passes 908 compiler-library tests and 9 CLI tests; 37 existing ignores remain.
  `sbtn mmlcPublishLocal` succeeds. `make -C benchmark clean` and `make -C benchmark mml`
  rebuild all 12 benchmark executables; no performance measurement is claimed.
- The restored animated sample compiles with `mmlc -s`. Its `help` and zero-speed paths exit
  successfully without opening a window. Caption behavior is verified by the headless fixture;
  no graphical playback verification is claimed.
- `./tests/mem/run.sh all` passes all 46 fixtures with ASan+LSan, including `caption-returns`.
  QA enforcement and focused tracking consistency checks find no rule violations.
- A fresh independent review finds no actionable issues and reruns all 27 focused tests
  successfully, including 54 native executions under ASan+LSan. A separate fixture audit
  confirms that `Event` and all five caption/helper functions match the animated sample.
- ABI lowering, runtime layouts, and native signatures are unchanged, so Linux ABI gates do not
  apply. Native execution evidence covers macOS arm64. CLI JVM startup emits the existing
  Scala-library `sun.misc.Unsafe` deprecation warning; Scala compilation has no warnings.

Global literal repair evidence: 2026-10-06, macOS arm64, base `60651b1`.

- Before the resolver fix, the 20 new global-literal checks reported 9 failures and 11 passes:
  the resolver-boundary check, seven forward-reference semantic cases, and the forward
  runtime case failed. Backward references, arithmetic controls, and negative validation passed.
- After the fix, `sbtn "testOnly mml.mmlclib.semantic.TypeResolverTests
  mml.mmlclib.semantic.TypeCheckerTests mml.mmlclib.codegen.RuntimeTests"` passed all 84 tests.
  Runtime cases verify LLVM with `llvm-as` and execute with AddressSanitizer enabled.
- The published compiler executed the embedded minimal program with its constant after
  `main`, printed `30`, and exited successfully.
- `sbtn ";scalafmtAll;scalafixAll"` passed; no compiler warnings were reported.
- `./tests/smoke/run.sh all` passed all 8 checks, including native execution before publishing.
- `sbtn test` passed 880 compiler-library tests and 9 CLI tests. The existing 37 ignored
  tests remain ignored and are not counted as passing coverage.
- `sbtn mmlcPublishLocal` passed. `make -C benchmark clean` and `make -C benchmark mml`
  passed, rebuilding the 12 MML benchmark executables. No performance measurement is claimed.
- Memory-harness and Linux ABI gates do not apply: ownership, lambdas, runtime layouts,
  and ABI lowering are unchanged. The new native regressions execute with AddressSanitizer.
- QA enforcement and tracking consistency checks passed. A fresh independent reviewer found
  no actionable findings in the compiler repair and reran all 20 global-literal checks
  successfully. Native verification covers macOS arm64; no cross-target execution or
  performance measurement is claimed.

Animated sample cleanup verification: 2026-10-06, macOS arm64.

- The cleaned sample compiled with the published compiler and `-s`. Its help and zero-speed
  argument paths ran successfully without opening a window.
- Headless copies of the sample at `60651b1` and the cleaned source replaced only the call
  to `animate` in `demo` with search-result reporting. Both compiled with `-s`; all four
  cases below exited successfully with no sanitizer diagnostics and identical output.
- Each checksum starts at zero and folds `acc * 31 + value` with MML Int32 wrapping.
  The event checksum covers exactly `event_count * 8` integers; the path checksum covers
  exactly `path_length` cells. The normal search and event-recording code is unchanged.

| Wall arguments | Found | Events | Path length | Event checksum | Path checksum |
| --- | --- | --- | --- | --- | --- |
| Defaults (`25 3 4`) | yes | 1767 | 30 | 1713252100 | -1707470363 |
| No wall (`25 3 0`) | yes | 1767 | 30 | -709202489 | -1707470363 |
| Clipped wall (`25 -2 5`) | yes | 1767 | 30 | -709202489 | -1707470363 |
| Full barrier (`25 0 10`) | no | 4604 | 0 | 2137441252 | 0 |

Automated verification did not exercise window playback. Author verification confirms that
the cleaned animated sample works with the published compiler. The conditional-argument and
caption-return workarounds remain because their compiler repairs are separate findings.

Integer-array fill verification: 2026-10-06, macOS arm64.

- The focused `RuntimeTests` fill regression failed before the API existed with undefined
  `ar_int_fill` references. All five tests in that suite pass with the implementation.
- The versioned fill regression compiles through the normal frontend and native toolchain,
  verifies LLVM assembly, and executes under ASan. It checks empty arrays (including a
  negative size normalized by `ar_int_new`), one and 17 elements, repeat fills, zero,
  `123456789`, and both Int32 limits. Every multi-element result is read back; borrowing
  allows repeated writes and reads before ordinary scope cleanup.
- `sbtn "run run -s ..."` compiled and executed the same fill fixture successfully.
- A deterministic sample probe starts every grid cell at wall value `1`. To reproduce,
  replace only `demo`'s `(ar_int_new size)` with `(dirty_array size)`, append the helpers
  below, and insert this check immediately after `build_vertical_wall 0`:

  ```mml
  println ("wrong cells: " ++
    (int_to_str (wrong_cells grid wall_x wall_y wall_length 0 0)));
  ```

  ```mml
  fn dirty_array(size: Int): IntArray =
    let arr = ar_int_new size;
    init_array arr 1 size 0;
    arr;
  ;
  fn wrong_cells(grid: Grid, wx: Int, wy: Int, length: Int, i: Int, wrong: Int): Int =
    if i < grid.width * grid.height then
      let x = i % grid.width;
      let y = i / grid.width;
      let expected = if x == wx and y >= wy and y < wy + length then 1; else 0;;
      let mismatch = if ar_int_get grid.walls i == expected then 0; else 1;;
      wrong_cells grid wx wy length (i + 1) (wrong + mismatch);
    else
      wrong;
    ;
  ;
  ```

  Compile the probe with `sbtn "run -s <probe.mml>"`. Remove only
  `ar_int_fill grid.walls 0;` for the defective control. Compile the unchanged sample with
  `sbtn "run -s mml/samples/astar3.mml"`. Execute each binary with the arguments below.
  All 12 runs exited zero with no ASan diagnostics. The probe checks all 300 cells before
  search; successful unmodified sample runs also print ten 30-cell rows with exactly the
  expected obstacle cells and only `.`, `#`, and `*` characters.

| Wall arguments | Defective wrong cells | Fixed wrong cells | Fixed/sample result |
| --- | --- | --- | --- |
| Defaults (`25 3 4`) | 296 | 0 | path found |
| No wall (`25 3 0`) | 300 | 0 | path found |
| Clipped wall (`25 -2 5`) | 297 | 0 | path found |
| Full barrier (`25 0 10`) | 290 | 0 | no path |

The defective probe reports no path in all four cases. ASan alone does not establish
initialization correctness; the explicit cell-value oracle exposes the defect.

- `sbtn ";scalafmtAll;scalafixAll"` passed with no Scala compiler warnings.
- `./tests/smoke/run.sh all` passed 8/8.
- `sbtn test` passed 881 compiler-library and nine CLI tests (890 total); 37 existing ignores
  remain excluded from passing coverage.
- `sbtn mmlcPublishLocal` passed after successful compiler execution and smoke checks.
- `make -C benchmark clean` and `make -C benchmark mml` passed, building 12 MML
  executables. No performance measurements are claimed.
- Host toolchain: Homebrew Clang and LLVM 23.1.1, `arm64-apple-darwin25.6.0`.
  Verification covers macOS arm64. Aggregate layouts, calling-convention lowering,
  allocation, ownership analysis, and destruction are unchanged; Linux ABI and the memory
  harness gates do not apply. JVM startup emits existing Scala-library `sun.misc.Unsafe`
  deprecation warnings during CLI execution.
- QA enforcement and focused tracking consistency checks pass. A fresh independent reviewer
  found no actionable findings and independently reran the fill regression (one test) and
  all 12 sanitized sample cases, reproducing the recorded cell counts and map assertions.
  The review also traced the existing AArch64 aggregate adapter for the native fill call.
  Repair signoff is granted; the verified changes are locally committed as
  `Add runtime integer-array fill`.

## Risks / Notes

- Witness-based returns and branch-dependent transfer cleanup remain under
  [conditional ownership hardening](conditional-ownership-witnesses.md).
- A raylib window-initialization failure is a separate environmental condition, not evidence
  of the mixed-return bug. Require successful initialization before interpreting a GUI crash.
- LLVM PHI failures and runtime cleanup failures are distinct observations even when the
  triggering source involves the same caption functions.

## Signoff

- Global literal repair and animated sample cleanup signoff: granted.
- Integer-array fill and wall-map repair signoff: granted.
- String-return ownership repair signoff: granted.
- Remaining bug-repair signoff: pending.
- Tracked item completion: pending.
- Global literal repair and animated sample cleanup commit: complete
  (`Fix forward references to global literals`).
- Integer-array fill and wall-map repair commit: complete (`Add runtime integer-array fill`).
- String-return ownership repair commit: complete (`Fix nested mixed string returns`).
- Commit authorization: pending for findings 2 and 5.

## Task Working Memory

Global literal repair and animated sample cleanup are complete, signed off, and locally
committed as `Fix forward references to global literals`; verification evidence is recorded above.
Integer-array fill and wall-map initialization are complete, signed off, and locally
committed as `Add runtime integer-array fill`. Focused tests, smoke, the full suite, local
publication, benchmark builds, QA enforcement, and independent review pass.

Finding 3 is complete, signed off, and locally committed as `Fix nested mixed string returns`.
Direct results through local scopes and nested conditionals, sample caption restoration,
compiler verification, and independent review pass.
Named mixed-binding returns, consuming transfers, and the documented conditional return-lifetime
gap remain with conditional ownership hardening. Findings 2 and 5 still need reduction,
diagnosis, and implementation approval. Float and string array fill extensions are deferred.
