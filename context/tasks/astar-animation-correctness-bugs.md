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

1. [ ] **in_progress** — Findings 1–4, 6, and 7 have signed-off repairs; task signoff remains pending.
2. [x] **complete** — [Global literal repair](#global-literal-repair),
   [string-return ownership repair](#string-return-ownership-repair), and
   [conditional IR repair](#conditional-ir-repair) are complete and signed off.
3. [x] **complete** — [Integer-array fill and wall-map repair](#integer-array-fill-and-wall-map-repair)
   passes compiler checks, sample verification, and independent review; repair signoff is granted.
4. [x] **complete** — [Finding 6: calls inside conditional field qualifiers fail type resolution](#6-calls-inside-conditional-field-qualifiers-fail-type-resolution).
5. [x] **complete** — [Finding 7: conditional field-alias lifetime](#7-conditional-field-aliases-can-outlive-their-temporary-owner).
6. [ ] **planned** — Run applicable compiler and sample verification and obtain signoff.

## Problem

Six findings concern ordinary MML source patterns and the console and animated A* samples.
The [animated sample](../../mml/samples/astar3_animated.mml) uses plain global integer
constants and explicit grid initialization. It uses an inline conditional array argument for finding 2 and ordinary caption returns
with literal terminal messages for finding 3.
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

At `c31058d`, `make_grid` in the linked animated sample used this workaround:

```mml
let value = if wall then 1; else 0;;
ar_int_set walls i value;
```

The ordinary inline form exposed the failure:

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
compiled successfully. Both the installed compiler and the repository compiler at `c31058d` reproduce the inline
argument failure. The bound form passes. The conditional IR repair below restores the inline form.

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
transfer failures in [conditional ownership hardening](mixed-ownership-transfers.md)
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

### 6. Calls inside conditional field qualifiers fail type resolution

- **Status:** complete.
- **Implementation approval:** granted for complete qualifier traversal in normal and error-recovery
  reference resolution, callee-qualifier dependencies, capture traversal, temporary-owner lifetime,
  focused regressions, and verification.
- **Repair signoff:** granted for the implemented scope, with the remaining
  [nested-conditional alias lifetime defect](#7-conditional-field-aliases-can-outlive-their-temporary-owner) explicitly deferred.
- **Local commit:** `Resolve field qualifiers and retain temporary owners`.

At `fd8a15c`, a conditional expression used to select a record fails when its predicate
contains function and operator calls. The `qualified` function in
[call-exit-blocks.mml](../../tests/mem/call-exit-blocks.mml) reproduces the failure with:

```mml
(if mark trace 2 1 == 1 then box; else box;).call
```

The recorded diagnostic is `UnresolvableType` for `mark` and `==`, before LLVM generation.
At that revision, `RefResolver.resolveTerm` and `rewriteTermWithInvalidExpressions` handle
references and expressions but skip `TermGroup`; names inside parenthesized field qualifiers
receive no resolved IDs or operator candidates. The type checker
can recover local parameter types by name, masking the gap for simple `flag` and `box` references.

Diagnosis at `fd8a15c` reproduces the original fixture failure and this smaller form:

```mml
struct Box { value: Int };
fn read(box: Box): Int = (if 1 == 1 then box; else box;).value;;
pub fn main(): Int = read (Box 7);;
```

Literal and parameter predicates emit LLVM IR. Function predicates, operator predicates,
branch calls, and `(make flag).value` fail; binding the result before selection emits IR.
An undefined name inside the qualifier produces a type-inference error, while the bound
form reports an undefined reference.

The same revision has a related dependency omission: `TypeChecker.collectMemberDeps.walkAppFn`
skips `Ref.qualifier`. Calling `box.call 7` before an inferred `let box = Ops identity`
fails to infer `box`; placing the binding first or binding `box.call` before calling it succeeds.

The repair moves complete per-term traversal into the resolver's term helpers and routes
expression traversal through them. Dependency collection uses the same reference walk in
value and callee positions. Lexical scope, field-name lookup, and evaluation order stay intact.

Regression coverage is in
[FieldQualifierResolutionTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/FieldQualifierResolutionTests.scala)
and [CallExitBlockTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/CallExitBlockTests.scala).
The semantic suite checks successful field resolution, local shadowing identities, inferred
forward dependencies, undefined-reference recovery, and invalid types and fields. Phase-level
checks require declaration IDs before type checking can recover local names.

The [runtime fixture](../../tests/mem/call-exit-blocks.mml) selects distinct callable branches
with results `7` and `8`. Its argument records digit `1` and its predicate records digit `2`;
trace `12` requires exactly one evaluation of each in the compiler's argument-before-callee
order. The capturing case returns `17` or `27` with traces `12` or `22`; a disabled enclosing
branch returns `3` without effects. The suite executes all three paths at `-O0` and `-O3`
with LLVM verification and ASan/LSan.

Verification including capture traversal and temporary-owner retention: 2026-10-07,
macOS arm64, Homebrew LLVM 23.1.1.

| Check | Result |
| --- | --- |
| `./tests/smoke/run.sh all` | Pass: 8/8. |
| `sbtn "scalafmtAll;scalafixAll;test"` | Pass: 1010 library and 9 CLI tests, 37 existing ignores, no failures or compiler warnings. Includes 15 qualifier-resolution tests, 54 qualifier-ownership tests, and 33 call-exit tests. |
| `sbtn mmlcPublishLocal` | Pass after smoke and development-compiler verification. |
| `make -C benchmark clean`, then `make -C benchmark mml` | Pass: 12 targets build; no timing measurements. |
| `./tests/mem/run.sh all` | Pass: 48/48 under ASan/LSan at `-O0`. |
| QA and tracking consistency | Pass. |
| Code review | Capture follow-up passes narrow independent re-review. Ownership review is complete through the explicitly approved implementing-agent fallback; primary and per-claim independence are waived. One confirmed P1 remains, explicitly deferred to [conditional field-alias lifetime](#7-conditional-field-aliases-can-outlive-their-temporary-owner). |

No ABI contract changes are included; Linux ABI checks are not applicable. CLI startup
retains the existing JVM `sun.misc.Unsafe` deprecation notice. Repair signoff and local
commit authorization are granted for finding 6 with the
documented deferral. Push authorization is pending.

#### Review follow-ups

The newly accepted expressions expose two downstream omissions. Both findings have independent
confirmation; their unresolved name references prevented code generation at `fd8a15c`.

1. **Capture traversal — repaired and signed off.** Missing qualifier traversal in
   `CaptureAnalyzer` leaves lambdas without captures. The regression below protects against
   LLVM assembly receiving an undefined `@flag` instead of an environment capture:

   ```mml
   struct Box { value: Int };
   fn invoke(f: Unit -> Bool): Bool = f ();;
   fn read(box: Box, flag: Bool): Int =
     (if invoke { flag } then box; else box;).value;
   ;
   pub fn main(): Unit = println (int_to_str (read (Box 42) true));;
   ```

   The repair traverses qualifier expressions in value and callee positions and propagates
   nested captures. Four semantic regressions require exact capture IDs and types in direct
   and nested closures. LLVM and native regressions exercise captured predicates selecting
   primitive and callable fields. Both paths pass at `-O0` and `-O3` under ASan/LSan. Narrow
   independent re-review reports no actionable findings. Nested getters have semantic coverage;
   native capture regressions exercise immediate borrow closures on macOS arm64.

2. **Temporary-owner lifetime — bounded repair signed off, conditional alias gap deferred.**
   The failing reduction selects a primitive field from a newly allocated owning record:

   ```mml
   struct Box { text: String, value: Int };
   fn make(): Box = Box (int_to_str 123) 0;;
   pub fn main(): Int = (make ()).value;;
   ```

   The pre-repair `-O0` IR calls `make`, extracts the field, and returns without a
   destructor call. An explicit `let box = make (); box.value;` control emits `__free_Box`.
   Native execution succeeds, but the allocated String has no remaining owner. Missing
   cleanup is established by LLVM IR; the review's leak detector could not access the
   process task port in its sandbox. The qualifier analysis visits effects
   without itself retaining ownership of the qualifier result. Cleanup placement must account
   for primitive projections, borrowed heap fields, and calls through function fields.
   Implementation approval is granted to retain temporary owners through field use, clean them
   up exactly once, and cover borrowed fields and callable fields.

   Qualifier preparation reuses the partial-application elaborator's local bindings. Binding
   lifetimes enclose field consumers and local alias bodies, with arguments evaluated before
   callable qualifiers. Ownership analysis retains borrowed alias dependencies, treats copied
   scalar fields as independent values, and rejects the covered borrows escaping a scope that
   destroys their owner. Mixed owned/borrowed qualifiers cannot authorize consuming field calls.
   Partial applications preserve borrowed field origins through aliases and capture the
   aggregate binding identity. Conditional branches remain scope boundaries; escaping projections
   require rejection. Nested conditional aliases have the remaining dependency gap linked below.

   Regressions are in
   [FieldQualifierOwnershipTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/FieldQualifierOwnershipTests.scala)
   and [field-qualifier-owners.mml](../../tests/mem/field-qualifier-owners.mml).
   The qualifier-ownership semantic cases check cleanup targets by resolved identity and reject invalid ownership
   escapes and later moves. Native ASan/LSan execution at `-O0` and `-O3` verifies both
   conditional branches, a disabled enclosing branch, and effect traces. Full compiler gates
   pass, including the two additional review fixes below.

Two additional review defects have focused regressions and repairs:

- A conditional branch can export a String through a nested aggregate alias after temporary
  root-owner cleanup. An independent verifier reported an `-O0` ASan failure when `println`
  reads the freed String. Borrow dependencies retain alias identities across local scope results,
  so the branch is rejected before code generation.
- `(let ignored = (); box).value` can duplicate cleanup of an existing owned `box`. An
  independent verifier reported two aggregate destructor calls and an `-O0` ASan double-free;
  the explicit scoped-result binding has the same defect. Owning initializers transfer their
  results through the consuming-result analysis, producing one cleanup and rejecting later use
  of the source owner.

The focused regressions reproduced four failures before these repairs and pass afterward.
Both primary review attempts and the claim verifiers were interrupted by the automated error
`This content was flagged for possible cybersecurity risk.` Their reported findings remain
preserved evidence. The explicitly approved
[code-review fallback](../../.skills/code-review/SKILL.md#automated-flags-and-interrupted-reviews)
completes the outstanding ownership review and fix checks in the implementing agent.
Primary-review and per-claim independence are waived; no clean independent ownership review
is claimed.

The fallback review confirms one remaining P1: conditional merging drops branch-local field-alias
dependencies, allowing a String to outlive temporary-record cleanup. Emitted IR and native ASan
execution at `-O0` establish destruction before `println` reads the String. The complete reproducer,
diagnosis, evidence, and repair plan are in
[conditional field-alias lifetime](#7-conditional-field-aliases-can-outlive-their-temporary-owner).
Deferral to finding 7 is approved, and finding 6's implemented scope is signed off
for a local checkpoint. Finding 7 records the completed, signed-off P1 repair separately.

Acceptance: valid calls and operators inside conditional field qualifiers resolve and type
check; the selected field can be called successfully. Verify branch selection and exactly-once
predicate evaluation, preserve diagnostics for invalid expressions, and cover LLVM validity
and native execution.

### 7. Conditional field aliases can outlive their temporary owner

- **Status:** complete.
- **Priority:** HIGH; confirmed P1.
- **Implementation approval:** granted for the bounded conditional dependency plan below.

At `cd73f28`, `OwnershipAnalyzer.analyzeCond` merges moves and consumed fields but drops
borrow dependencies introduced inside either branch. A returned field reference can still name
a branch-local alias, so an enclosing cleanup check cannot follow that alias back to its owner.
The compiler accepts a String borrow after its temporary record has been destroyed.

The affected path is exposed by the qualifier repair in
[A* finding 6](#6-calls-inside-conditional-field-qualifiers-fail-type-resolution).
The conditional dependency repair extends that retention to branch-local alias identities.
Local cleanup can follow conditional results back to their root owners.

#### Reproducer

```mml
struct Box { text: String, value: Int };
struct Outer { inner: Box };
fn make_outer(): Outer = Outer (Box (int_to_str 123) 7);;
fn example(flag: Bool): Unit =
  let text = if flag then
    let inner = (make_outer ()).inner;
    (if flag then let alias = inner; alias.text; else "static";);
  else "static";
  ;
  println text;
;
pub fn main(): Unit = example true;;
```

Expected: reject the result with an ownership diagnostic because the String cannot outlive
the temporary `Outer` created in the outer conditional's true branch.

Observed at the qualifier-repair checkpoint: compilation succeeds. The generated code destroys
`Outer` after the inner conditional merges, then passes the selected String to `println` after
the outer merge.
Native execution reads freed String storage.

#### Outcome

Conditional results retain the owner dependencies of branch-local aliases. Local cleanup
rejects escaping field borrows, while valid borrows used entirely within their owner's
lifetime remain accepted. Branch selection and exactly-once evaluation remain unchanged.

#### Scope

- In scope: conditional dependency merging in
  [OwnershipAnalyzer.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala),
  directly affected escape and use-after-move checks, and focused semantic/runtime regressions.
- Out of scope: new lifetime syntax, implicit cloning, and the separate
  [conditional ownership witness work](mixed-ownership-transfers.md).

#### Plan (Approval Gate)

- [x] Add a semantic regression for the embedded program that requires an ownership error.
- [x] Preserve branch result dependencies by resolved binding identity when merging scopes.
  Retain the transitive path from aliases to root owners without exporting local ownership.
- [x] Cover both branches, nested conditions, shadowed aliases, and live-owner controls.
- [x] Run applicable compiler gates and review the changed dependency contract: host gates and
  independent review pass; Linux sanitizer verification is explicitly deferred below.

Approval: implementation, repair signoff, and scoped local commit authorization are granted.
Completion is approved using the passing host checks and independent review, with Linux
sanitizer verification explicitly deferred to the separate BUG task.

#### Verification

Evidence: 2026-10-07, macOS arm64, Homebrew LLVM 23.1.1, qualifier-repair sources on
`dev-2026-03-21-lambdas` based on `fd8a15c`.

- Compile the embedded program with `mmlc -s -O0`. Compilation succeeds; linking emits
  the environment warning `directory not found for option -L/usr/local/lib`.
- Execute with `ASAN_OPTIONS=detect_leaks=0:halt_on_error=1:abort_on_error=1`.
  The process terminates with SIGABRT (subprocess return code `-6`). ASan reports
  `heap-use-after-free`, a read of size 3 in `println`, and deallocation through `__free_String`.
  Symbolizer warnings accompany the report; the memory error and named runtime frames are present.
- The emitted `example` function selects `alias.text` at the inner merge, calls the aggregate
  destructor before leaving the outer true branch, and invokes the `println` wrapper after
  the outer merge. This establishes destruction before use independently of symbolization.
- Primary review and per-claim independence were explicitly waived. The implementing agent
  confirmed the failure through source tracing, emitted IR, and native execution.

Replacing only `example` with this control prints `123` and exits zero under ASan at `-O0`:

```mml
fn example(flag: Bool): Unit =
  let text = (let inner = (make_outer ()).inner;
    if flag then let alias = inner; alias.text; else "static";);
  println text;
;
```

This pre-repair reproduction confirms a P1 finding. Leak detection was disabled for this
focused read-lifetime check; it does not establish `-O3` or cross-target behavior.

Repair evidence: 2026-10-07, macOS arm64, Homebrew LLVM 23.1.1.

The conditional merge combines both branches' borrow dependencies by resolved binding ID
and deduplicates their owner references. Branch-local ownership bindings stay local.
`FieldQualifierOwnershipTests` covers the original nested escape, all true/false branch
placements, transitive aliases, shadowing, use after moving an owner, and valid live-owner
borrows. The original regression fails at `cd73f28` because no ownership error is emitted;
all 64 qualifier-ownership tests pass with the repair.

`CallExitBlockTests` runs the extended `tests/mem/field-qualifier-owners.mml` fixture with
zero through three arguments at `-O0` and `-O3`. The fixture reads the selected String's
bytes, checks all nested branch choices and exactly-once effects, and reads the live owner
again. LLVM assembly verification and ASan/LSan execution pass. Both focused suites pass
97 tests in total.

| Check | Result |
| --- | --- |
| `./tests/smoke/run.sh all` | Pass: 8/8 after granting filesystem access for sbt's lock. The sandbox-only attempt could not start sbt. |
| `sbtn 'scalafmtAll;scalafixAll;test'` | Pass: 1020 library and 9 CLI tests, 37 existing ignores, no failures or compiler warnings. |
| `sbtn mmlcPublishLocal` | Pass after development-compiler execution and smoke verification. |
| `make -C benchmark clean`, then `make -C benchmark mml` | Pass: 12 programs build; no timing measurements. |
| `./tests/mem/run.sh all` | Pass: 48/48 under ASan/LSan at `-O0`. |
| QA enforcement | Pass. |
| Independent code review | Pass: no actionable findings. Fresh reviewer reran both focused suites, 97/97 passing. |

Host verification covers macOS arm64. Repair signoff and scoped local commit authorization
are granted. Completion is approved with the Linux sanitizer failures deferred below; no
passing Linux native sanitizer result is claimed.

##### Linux verification blocker

Linux native sanitizer verification remains blocked by
[Fix Linux ASan assembly failure after internalization](linux-asan-internalization.md).
That task holds the container results, standalone C reproducer, and passing controls.
Deferral approval: granted for completing finding 7 using the passing host checks and
independent review. The separate BUG task owns the assembly/link repair and Linux reruns;
its failures remain recorded and are not counted as passing verification.

#### Risks / Notes

Dependencies use stable binding identities. Merging them must retain shadowing distinctions
and must not transfer ownership or authorize consumption of borrowed fields.

## Outcome

Ordinary global constants, conditional expressions, and mixed string return paths compile
and execute safely. The console A* grid is deterministic and fully initialized. Every
finding has a focused regression or sample check that fails for the defective behavior.

## Scope

- In scope: the six findings, their reductions, directly affected compiler paths, regression
  coverage, and wall-map initialization in `astar3.mml`.
- Out of scope: animation controls, new language features, raylib changes, and unrelated cleanup.

## Plan (Approval Gate)

- [x] Reduce findings 1–4 and establish bounded repairs; conditional IR verification records
  the caption PHI occurrence that was not reproduced.
- [x] Diagnose conditional-argument PHIs and bound the string-return repair. No common cause
  with the historical caption PHI failure is established; named mixed-binding returns and
  conditional escape cleanup belong to [conditional ownership hardening](mixed-ownership-transfers.md).
- [x] Reduce and diagnose finding 6's frontend qualifier-resolution failure; bounded repair approved.
- [x] Complete finding 6's approved qualifier repairs and verification, with
  [nested-conditional alias lifetime](#7-conditional-field-aliases-can-outlive-their-temporary-owner) deferred to finding 7.
- [x] Complete finding 7's bounded conditional dependency repair and host verification,
  with Linux sanitizer verification deferred to the [Linux ASan BUG](linux-asan-internalization.md).

Approval: granted for the global literal repair, integer-array fill repair, and bounded
string-return ownership repair below, conditional IR repair, and finding 6 qualifier resolution,
capture traversal, and temporary-owner lifetime, plus finding 7's bounded conditional
dependency repair.

### Conditional IR repair

- **Status:** complete.
- **Implementation approval:** granted for exit-block propagation through calls and operators,
  focused regressions, sample restoration, verification, and directly affected documentation.
- [x] Preserve the last evaluated exit block through argument lists, including Unit arguments,
  direct/native/local/proven/indirect calls, evaluated callees, and unary/binary operators.
- [x] Verify LLVM predecessors, loopification, runtime branches, and exactly-once effect order.
  Restore the inline conditional array argument in the animated sample.
- [x] Retain literal and allocating caption fixtures; run ASan/LSan at `-O0` and `-O3`.
- [x] Run compiler gates, QA enforcement, tracking review, and independent code review.
- Repair signoff: granted. Local commit: `Preserve exit blocks through call evaluation`.
- Push authorization: pending.

At `c31058d`, LLVM 23.1.1 on macOS arm64 rejects the grid-loop inline conditional argument,
ordinary/local/indirect calls, operators, conditional Unit arguments, and conditional qualified
callees inside enclosing branches. Argument and callee evaluation discard `CompileResult.exitBlock`,
so enclosing PHIs name an earlier block. The bound grid argument and disabled-TCO control pass.
The allocating-caption substitutions pass LLVM verification and ASan/LSan at `-O0` and `-O3`;
the historical caption PHI failure is not reproduced, and no common root cause is established.

The contract stays local to emitted expression results: `None` means no new exit block;
a later nonempty exit replaces an earlier exit. Evaluation order and ownership semantics stay
unchanged. Conditional ownership hardening remains separate work.

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
under [conditional ownership hardening](mixed-ownership-transfers.md). That task's
consuming-transfer acceptance obligations remain open. Findings 2 and 5 are outside this repair.

#### Separate conditional return-lifetime gap

The standalone [mixed-ownership task](mixed-ownership-transfers.md) owns this repair.
Its [conditional return-lifetime evidence](mixed-ownership-transfers-evidence.md#conditional-return-lifetime-evidence-from-a)
contains the reproducer and sanitizer results. Returning a local on only one branch leaves
an allocation to clean up on the other branch; this obligation is outside the completed
caption repair.

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

## Verification

### Conditional IR verification

Host: macOS arm64, LLVM/Clang 23.1.1, GraalVM Java 25; baseline `c31058d`.

[CallExitBlockTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/CallExitBlockTests.scala)
contains 26 regressions: 18 call/operator/qualified-callee cases, two loopification controls,
two native loop/effect-order runs, and four literal/allocating-caption runs.
Before the emitter repair, the ordinary-call and loop tests fail the PHI predecessor assertion;
the disabled-TCO control passes. The repaired suite passes structural predecessor checks and
`llvm-as` verification. Tests compare actual control-flow edges rather than generated label numbers.

[call-exit-blocks.mml](../../tests/mem/call-exit-blocks.mml) checks selected branch values,
argument effect order, one evaluation per selected argument, qualified calls with known and
unknown targets, capturing-environment cleanup, and a 10,000-iteration loop. Native tests run
with zero, one, and two arguments to select both inner branches and the skipped outer branch.
The unchanged [caption fixture](../../tests/mem/caption-returns.mml) supplies expected text for
all event kinds and rejection reasons. Its allocating variant makes exactly the three terminal
caption substitutions recorded in finding 2; both variants pass at `-O0` and `-O3` with
`ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1`.

| Check | Result |
| --- | --- |
| `sbtn 'scalafmtAll;scalafixAll;test'` | Pass: 934 library tests, nine CLI tests, 37 existing ignores; no Scala compiler warnings. |
| `./tests/smoke/run.sh all` | Pass: 8/8. |
| `sbtn 'run run -s -O0 -b build/call-exit-check tests/mem/call-exit-blocks.mml'` | Pass: native fixture exits zero. |
| `sbtn 'run -s -O0 -b build/astar-phi-check mml/samples/astar3_animated.mml'` | Pass: restored graphical sample compiles and links; no window execution. |
| `sbtn mmlcPublishLocal` | Pass: local compiler installed after smoke and development-compiler execution. |
| `make -C benchmark clean`, then `make -C benchmark mml` | Pass: 12 MML targets build; no timing measurements. |
| `./tests/mem/run.sh all` | Pass: 48/48 under ASan/LSan at `-O0`. |
| QA and tracking consistency | Pass. |
| Independent code review | No actionable findings; independent rerun passes all 26 focused tests and `git diff --check`. |

Verification commands run sequentially where they use `sbtn`. JVM startup emits existing
`sun.misc.Unsafe` deprecation notices during CLI execution. No ABI changes are made; Linux
container ABI checks are not applicable.

[Finding 6](#6-calls-inside-conditional-field-qualifiers-fail-type-resolution) records the
completed, signed-off frontend repair for calls inside conditional field qualifiers;
the conditional IR repair has a separate scope.


Evidence for findings 1–4: 2026-10-06, macOS arm64, repository base `8143f27`.

- The minimal global-literal failure and conditional-argument PHI failure were observed with
  installed compiler `1629660`; the full sample's global-constant failure was also observed
  through `sbtn` at the repository base.
- The caption-return rewrite failed LLVM verification through the repository compiler.
- The mixed-return no-path caption crashed in an AddressSanitizer build after successful
  raylib window initialization. The diagnostic stack and reconstruction are embedded above.
- The uninitialized grid follows directly from the linked sample and runtime source.
- [Conditional IR verification](#conditional-ir-verification) records headless reductions
  for the conditional-argument PHI failure; the historical caption PHI failure was not
  reproduced. Finding 3 has a
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

- The [conditional field-alias lifetime repair](#7-conditional-field-aliases-can-outlive-their-temporary-owner)
  passes host verification and independent review. Linux sanitizer verification is blocked
  by the [Linux ASan assembly failure](linux-asan-internalization.md), with explicit approval
  to defer that verification from finding 7 completion.
- Witness-based returns and branch-dependent transfer cleanup remain under
  [conditional ownership hardening](mixed-ownership-transfers.md).
- A raylib window-initialization failure is a separate environmental condition, not evidence
  of the mixed-return bug. Require successful initialization before interpreting a GUI crash.
- LLVM PHI failures and runtime cleanup failures are distinct observations even when the
  triggering source involves the same caption functions.

## Signoff

- Global literal repair and animated sample cleanup signoff: granted.
- Integer-array fill and wall-map repair signoff: granted.
- String-return ownership repair signoff: granted.
- Conditional IR repair signoff: granted.
- Finding 6 signoff: granted for the implemented qualifier repairs with the conditional alias
  lifetime defect deferred to finding 7.
- Finding 7 signoff: granted for the bounded conditional dependency repair, with Linux
  sanitizer verification explicitly deferred to the separate BUG task.
- Tracked item completion: pending.
- Global literal repair and animated sample cleanup commit: complete
  (`Fix forward references to global literals`).
- Integer-array fill and wall-map repair commit: complete (`Add runtime integer-array fill`).
- String-return ownership repair commit: complete (`Fix nested mixed string returns`).
- Conditional IR repair commit: complete (`Preserve exit blocks through call evaluation`).
- Finding 6 commit: `Resolve field qualifiers and retain temporary owners`.
- Finding 7 commit: `Preserve conditional field-alias dependencies`.
- Commit authorization: granted for findings 6 and 7.
  Push authorization: pending.

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
gap belong to the standalone [mixed-ownership task](mixed-ownership-transfers.md).
Finding 2's conditional IR repair is complete, signed off, and locally committed as
`Preserve exit blocks through call evaluation`.
Propagation, all compiler gates, QA enforcement, tracking checks, and independent code review pass.
Finding 7's bounded conditional dependency repair is complete and signed off. Conditional
dependency merging, all 97 focused semantic, LLVM, and native checks, smoke, full tests,
publication, benchmark builds, and all 48 memory
programs pass. Independent review found no actionable findings and reran all 97 focused checks.
Both Linux builders pass the 64 qualifier-ownership checks but fail 29 native tests at ASan
assembly/linking. The [Linux ASan task](linux-asan-internalization.md) holds the standalone
reproducer, pipeline diagnosis, and required Linux reruns. Completion of finding 7 is approved
using the passing host checks and independent review; Linux sanitizer verification is explicitly
deferred to that BUG task. The scoped local commit is `Preserve conditional field-alias dependencies`.
Finding 6 is complete and signed off:
it repairs reference traversal, callee-qualifier dependencies, capture traversal, and
temporary-owner lifetime. Smoke,
formatting, lint, 1010 library and 9 CLI tests, local publication, benchmark builds, and
all 48 memory programs pass. Capture traversal passes narrow independent re-review.
Finding 6 ownership review used the approved implementing-agent fallback, with primary
and per-claim independence waived. Finding 7 has a fresh independent review and its scoped
Linux verification deferral. No push is authorized.
Float and string array fill extensions are deferred.
