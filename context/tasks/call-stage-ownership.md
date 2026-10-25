# Unify call-stage interpretation and repair ownership

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG, CORRECTNESS
- **Priority:** HIGH
- **Created:** 2026-10-19
- **Completed:** 2026-10-19
- **Target Branch:** dev-2026-03-21-lambdas
- **Parent:** [Unify lambdas](unify-lambdas.md)

## Execution Checklist

1. [x] **complete** — Shared call-stage contract and bounded F1–F6 implementation plan approved.
2. [x] **complete** — Implement one call-stage interpretation and migrate callable flow,
   capture transfers, and ownership consumers; cover findings F1–F6.
3. [x] **complete** — Verify the primary repair with compiler gates, QA, tracking review,
   and independent review.
4. [x] **complete** — Fully applied literal borrowing is repaired; combined compiler gates,
   QA, tracking review, and independent review pass. Both repairs are signed off.

## Problem

A chained application can invoke a function, obtain another function, and invoke that result.
Each invocation is a call stage. Flattening the chain into one callee and all its arguments
loses which function owns each parameter and result.

Independent interpretations in callable flow, capture-transfer collection, and ownership
analysis disagree about applied parameters, invocation, consuming captures, and result
ownership. The failures include false borrowed-value errors, accepted reuse of call-once
functions, lost cleanup, and an observed invalid free of a static String. The repair shares
one typed-stage interpretation across these analyses.

## Outcome

Equivalent chained and explicitly bound calls preserve the same ownership contracts and
diagnostics. Each argument reaches its actual parameter, each invocation consumes the correct
callable, and the final invoked function determines result ownership. Evaluation order,
resolved identities, and exactly-once cleanup remain intact.

## Scope

- In scope: evaluated callee bodies and qualifiers; returned callables; partial applications;
  nullary calls; callable-argument flow; parameter agreement; final-result ownership;
  capture transfers and caller-scope cleanup. Findings F1–F6 define the primary repair.
- Approved continuation: F7, borrowing through fully applied lambda-literal lowering.
- Outside the primary repair: general chained-call emission, PAP elaboration inside callee
  bodies, tail-recursion cleanup, broad ignored-test restoration, and unrelated optimization.
  [Emitter limits](#emitter-limits) constrain evidence, not the semantic acceptance criteria.

## Findings and reproducers

F1–F6 below describe the failing baseline and attempted repairs, not the verification result
of the approved implementation. Current evidence belongs in [Verification](#verification).

The examples and observations below are the durable evidence. Candidate tests and local build
artifacts are not required to understand the failures. A partial application (PAP) stores
supplied arguments in a callable awaiting the remaining arguments. A call-once callable owns
captures that its invocation consumes.

### F1: capture transfers through evaluated callees

**Existing bug; semantic correctness.** Capture-transfer collection visits arguments but omits
transfers performed while evaluating the callee. An enclosing move lambda then treats a
transferred capture as borrowed.

```mml
fn take(~s: String): Int -> Int =
  println s;
  { x: Int -> x; };
;
pub fn main(): Unit =
  let s = int_to_str 42;
  let outer = ~{ { u: Unit -> take s; } () 2; };
  println (int_to_str (outer ()));
;
```

The failing diagnostic is `BorrowedValuePassedToConsumingParam`. The equivalent control binds
`{ u: Unit -> take s; } ()` to a local before applying `2`; semantic compilation accepts it.
The repair must include applied lambda bodies and callee qualifiers, preserve transferred
capture IDs, and make a second call to the consuming outer lambda invalid. Constructing an
uncalled lambda must not execute its body. Saturated immediate bodies need caller-scope moves
and local cleanup; treating them as standalone borrowed closures is insufficient.

The attempted repair accepted the original and bound forms semantically. That result is not
native evidence; executable coverage has the limits described below.

### F2: reuse of a returned call-once callable

**P2; regression in the initial repair; independently verified semantically.**

```mml
fn take(~text: String, n: Int): Int = text.length + n;;
fn example(): Int =
  let text = int_to_str 42;
  let p = take text;
  let first = { u: Unit -> p; } () 1;
  p 2;
;
```

The initial repair accepts both calls. The control replaces the `first` binding with:

```mml
let f = { u: Unit -> p; } ();
let first = f 1;
```

The control correctly rejects the final `p 2` with `UseAfterMove`. The immediate body preserves
`p`, while consumption checks examine only the flattened base lambda and miss the invocation
of returned `p`. Source tracing of commit `581da71` shows a borrowed-return rejection before
that repair; this comparison was not a baseline build. No native double-free was demonstrated.

Acceptance must cover returned aliases, conditional callable results, reusable controls,
borrowed captures, enclosing move-lambda call-once metadata, and one destruction of each owned
environment. Selected and unselected conditional callable payloads both need valid cleanup.

### F3: final static result inherits an earlier owned-return contract

**P1; native safety regression exposed by the returned-callable correction.**

```mml
fn take(~text: String, n: Int): String = println text; "literal";;
fn make(~text: String): Int -> String = take text;;
pub fn main(): Unit =
  let result = make (int_to_str 42) 1;
  println result;
;
```

Control:

```mml
let p = make (int_to_str 42);
let result = p 1;
```

The reviewed correction accepts the chain and emits valid LLVM, but native ASan execution
aborts with exit 134 in `__free_String`. The control passes ASan and prints `42` and `literal`.
The final String inherits `make`'s owned-closure return contract, causing a free of the literal.
The initial repair before the returned-callable correction rejects the chain with
`A call-once function requires a consuming parameter`; that is an attempted-repair comparison,
not evidence about the committed base.

Final-stage allocation, return-contract analysis, and static-result provenance must agree.
Cover both a static String and an allocated `int_to_str n` result at O0 and O3, with runtime
and sanitizer checks.

### F4: later-stage callable arguments lose their call-once contract

**P1; semantic regression exposed by the returned-callable correction.**

```mml
fn take(~text: String, n: Int): Int = println text; n;;
fn twice(~f: Int -> Int, n: Int): Int =
  let first = f n;
  first + f n;
;
fn choose(): (Int -> Int) -> Int -> Int = twice;;
pub fn main(): Int =
  let p = take (int_to_str 42);
  choose () p 1;
;
```

Control: replace `choose () p 1` with `let run = choose (); run p 1;`.
The reviewed correction accepts the chain, while the control rejects the second call to `f`
with `UseAfterMove`. Parameter lookup accepts `p` at the consuming parameter, but callable flow
propagates arguments only into base `choose`, so `twice.f` receives no call-once contract.
The initial repair rejects the chained argument instead. This is an executed semantic
comparison between attempted implementations. Separate emission limitations prevent a claim
of an observed native double-free.

Each supplied argument must propagate to the actual stage's resolved parameter, including
partially supplied stages. Preserve the distinction between observed callable flow and a
complete lexical proof; observations at some call sites cannot prove all possible targets.

### F5: later-stage alternatives disagree about consuming a parameter

**P2; acceptance gap plus cleanup regression.**

```mml
fn consume(~text: String): Int = text.length;;
fn borrow(text: String): Int = text.length;;
fn choose(flag: Bool): String -> Int =
  if flag then consume; else borrow; ;
;
pub fn main(): Int =
  let text = int_to_str 42;
  choose false text;
;
```

Control: replace `choose false text` with `let run = choose false; run text;`.
Both the initial repair and its returned-callable correction accept the chain, while both
reject the control with `Callable alternatives must agree on consuming parameters`.
The initial repair inserts caller cleanup for `text`; the correction omits it. It unions
consuming flags of returned alternatives while checking agreement only on base `choose`.
The borrowing path consequently has no cleanup owner.

The acceptance gap predates that correction; the cleanup removal is a regression. These
comparisons do not establish untouched-base behavior. Native leakage through the intended
invocation was not established because of the emitter limitation. Every callable stage must
check parameter and result-contract agreement before authorizing moves or selecting cleanup.

### F6: nullary calls lose allocation and owned-result contracts

**Regression in the latest correction; eight focused test failures.** A function with zero
declared parameters retains an empty `Lambda.params`, but its callable type has a synthetic
`Unit` input. The candidate `invokedLambdas` and `provenInvokedLambdas` require exactly one
remaining source parameter, excluding nullary calls. Origin resolution and typed stage
splitting use different completion rules.

The native declarations exercised by the failing Handle tests are:

```mml
type Handle = @native[t=ptr, mem=heap, free=release];
fn allocate(): Handle = @native[mem=alloc];;
fn release(~p: Handle): Unit = @native;;
fn outstanding(): Int = @native;;
```

`allocate ()` loses its allocation contract. Consuming sinks report borrowed ownership and
move closures reject the resulting Handle captures. This also affects ordinary function
results, as shown by the failing semantic regression:

```mml
fn take(~text: String, n: Int): Int = text.length + n;;
fn example(): Unit -> Int =
  let p = take (int_to_str 42);
  ~{ { u: Unit -> p; } () 1; };
;
fn main(): Int =
  let outer = example ();
  let first = outer ();
  outer ();
;
```

Expected: `UseAfterMove` only at the second `outer ()`. Observed: owned-result information
from `example ()` is lost and both calls produce ownership-required errors instead.
Source inspection identified the common nullary mismatch as the likely cause of all eight
failures in that attempted correction.

Application arity and source parameter count must remain distinct. Synthetic Unit completes
the invocation but must not bind a nonexistent source parameter.

### F7: borrowing in fully applied lambda literals

**Existing independent bug; bounded repair approved.**

```mml
fn example(): Int =
  let text = int_to_str 42;
  let first = { value: String, n: Int -> value.length + n; } text 1;
  first + text.length;
;
```

Semantic compilation reports `UseAfterMove` at the final `text.length`, with the first `text`
argument as the move location. `ExpressionRewriter.lowerDirectLambda` lowers fully supplied
parameters through ordinary local bindings. Their `Rebind` ownership handling transfers the
input despite the source parameter being nonconsuming.

Preserve the parameter contract through lowering without weakening ordinary local moves or
consuming parameters. Accept this example and borrowed-input controls; reject reuse after a
corresponding `~value` call. Cover allocating temporaries, equivalent named calls, evaluation
order, and cleanup. Supplied prefixes of partially applied lambdas retain their existing
capture behavior; this repair concerns fully applied source parameters.

### Emitter limits

General chained immediate-lambda applications have a separate emission restriction in
[Applications.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Applications.scala).
`compileLambdaApp` supports a single unary/nullary immediate application; arbitrary chains
are not established as executable by semantic acceptance. Ownership materializes intermediate
results for the covered chains without extending that emitter. Named chains blocked native
confirmation of F4 and F5 in the attempted repair. F3 has an executed native ASan failure in
that repair and static/allocated O0/O3 regressions in the approved implementation.

PAP construction inside a chained immediate callee body remains excluded. A conditional
fixture constructing `take (allocate ())` there passed semantics and LLVM assembly but failed
ASan execution with a program counter of 1. `PartialApplicationElaborator` leaves that body's
PAP unelaborated. The executable conditional regression constructs its PAPs before the
immediate lambda, rebinds them to fresh local owners inside it, and selects between those
locals. Both alternatives require cleanup; this covers F2/F6 without claiming PAP-callee
traversal support.

Semantic regressions must run independently of emission. Do not turn a semantic pass into
a runtime claim or expand this repair to general emitter support without a separate plan.
The parent retains [PAP callee traversal](unify-lambdas.md#bug-elaborate-partial-applications-inside-callees)
and [tail-recursion cleanup](unify-lambdas.md#bug-preserve-tail-recursion-across-owned-closure-cleanup).

## Plan (Approval Gate)

### Restart decision

**Approved baseline:** restart the production implementation from committed base `581da71`
(`581da7165e2124e5c3a3b67fb0ccc3b58e7d3219`, `format`), preserving the findings and reproducers
in this task. This is a reproducible starting point, not a claim that the base fixes these bugs.

Completed rollback scope:

- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CallableValues.scala`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CaptureTransfers.scala`
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala`
- `docs/design/compiler-design.md`
- `modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/LocalCallableCodegenTests.scala`
- `modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/StringReturnTests.scala`
- Removed the untracked `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/CaptureTransfersTest.scala`.

The rollback restored compiler sources, test sources, and related design text to the committed
baseline. Findings and reproducers remain embedded in this task; the approved repair builds its
acceptance tests from that evidence.
Resetting source does not replace a locally installed compiler; development checks must use
repository source through `sbtn` until a verified build is published. A published attempted
repair is not a trustworthy baseline.

Rollback: complete. Shared-stage repair and fully applied literal-borrowing repair: approved.

### Shared contract and migration

1. Define a small immutable call-stage view that identifies the callee, supplied application
   nodes, actual source parameters, completion boundary, and returned callable for the next
   stage. Cover nullary, unary, multi-parameter, partial, and chained applications. Keep origin
   facts in callable flow and cleanup generation in ownership analysis.
2. Make callable-origin resolution and parameter propagation use that view. Preserve lexical
   substitutions, cycle guards, partial prefixes, unknown alternatives, and unavailable-value
   recovery. Keep complete proofs separate from observed flow.
3. Migrate capture-transfer collection and ownership consumers to the same boundaries:
   parameter agreement, call-once consumption, return ownership, allocation detection, and
   static-result provenance. Remove their independent stage-splitting logic. Check contracts
   on the actual invoked targets rather than unioning incompatible consuming flags.
4. Preserve caller-scope execution for evaluated immediate bodies, the boundary around uncalled
   lambda values, argument-before-callee order, single evaluation, resolved identities, and
   exactly-once environment/payload cleanup. Keep temporary construction in existing helpers.
5. Validate F1–F6 and controls, then run required gates and independent review.

Approved dependency direction: AST/types and availability → typed stages → callable flow →
capture and ownership consumers. `CallStages` retains typed boundaries without ownership state.
`CallableValues` owns target alternatives, lexical substitutions, and complete proofs. Ownership
materializes argument and intermediate-result locals through `LocalBindings`; their lexical
aliases extend existing evidence without rerunning callable-flow fixed-point propagation.
Nullary calls consume synthetic Unit without inventing source parameters. Parameter disagreement
does not authorize a unioned consuming contract.

### Fully applied literal borrowing

1. Mark source-parameter scopes during fully applied lambda lowering. Preserve borrowing for
   nonconsuming parameters while ordinary local bindings and consuming parameters still move.
2. Keep allocated arguments caller-owned while the source parameter borrows. Carry conditional
   ownership witnesses, destructor identities, and borrow dependencies into exactly-once cleanup.
3. Preserve the parameter boundary in return ownership, static-result, returned-origin, and
   capture-transfer analysis. Reject escaping borrows and borrowing across a later argument's move.
4. Verify unary/multiple parameters against named calls, consuming and ordinary-binding controls,
   allocated/static/mixed arguments, capture metadata, source diagnostics, and native O0/O3 runs.
   Run the full compiler gates, QA, tracking review, and fresh independent review.

## Verification

### Fully applied literal borrowing

`ExpressionRewriter` marks fully supplied source-parameter scopes in `LambdaMeta`. Ordinary
local bindings retain transfer semantics; marked nonconsuming parameters borrow. Ownership
analysis keeps allocating arguments caller-owned and carries their conditional ownership
witnesses and destructor identities into cleanup. Borrowed parameters cannot authorize
consuming calls or escape through heap returns. Return/static proofs and capture-transfer
analysis preserve the source parameter boundary, including enclosing move-lambda metadata.

Regression coverage:

- `LambdaLiteralBorrowingTests`: 20 semantic cases cover unary and multiple parameters,
  owned/borrowed inputs, named-call controls, consuming and ordinary-binding transfers,
  consuming sinks, escaping borrows, allocated/static/mixed arguments, reusable and call-once
  captures, partial-prefix transfer controls, a scoped argument transferring its owned result,
  and a later argument invalidating an earlier borrow.
  Negative cases assert diagnostic locations and resolved identities.
- `LocalCallableCodegenTests`: eight native cases (four fixtures at O0 and O3) cover owned and
  borrowed String reuse, conditional String allocation cleanup, native Handle allocation counts
  and destructor identity, effect trace `123`, and consuming-parameter cleanup. They use LLVM
  verification, native execution, and ASan/LSan through the existing compiler/toolchain helpers.
- The chained/bound allocating-argument semantic controls identify cleanup by the allocated
  String's resolved owner, allowing the caller to retain ownership while the parameter borrows.

Validation, 2026-10-19:

```sh
SBT_OPTS=-Dsbt.io.virtual=false sbtn 'scalafmtAll; scalafixAll; testFull'
SBT_OPTS=-Dsbt.io.virtual=false ./tests/smoke/run.sh all
```

Passed: **1,219 library tests and 9 CLI tests, zero failures/errors, 36 existing ignores**.
Formatting and lint passed without compiler warnings. Smoke passed **8/8**. The full suite
includes all 19 semantic and eight O0/O3 native borrowing regressions. The focused development
run first passed 147/155, then 154/155; corrections addressed diagnostic source/identity oracles,
a conditional fixture's syntax, and the allocating-argument cleanup owner assertion. The final
full run passes all focused cases.

The remaining compiler gates ran sequentially:

```sh
SBT_OPTS=-Dsbt.io.virtual=false sbtn mmlcPublishLocal
make -C benchmark clean
make -C benchmark mml
./tests/mem/run.sh all
```

Local publication succeeded after smoke verification. All **12 clean MML benchmark builds**
passed; no performance measurements were taken. Memory checks passed **51/51** under ASan/LSan
at O0 with `ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1` in 68 seconds.
Host: macOS arm64, Homebrew Clang 23.1.1 (`arm64-apple-darwin25.6.0`). No ABI or layout change;
the conditional Linux ABI gate does not apply. Native evidence is limited to this host and the
covered fixtures. QA compliance, focused tracking review, and `git diff --check` pass.
Independent review confirmed one lifetime-check regression, described below. Correction
verification and fresh narrow independent re-review pass. The primary-repair results below
establish the pre-continuation baseline.

#### Independent review: moved result of a scoped argument

```mml
fn example(): Int =
  let text = int_to_str 42;
  { value: String -> value.length; } (let moved = text; moved);
;
```

The first borrowing implementation reports `UseAfterMove(text)`, with the legal transfer as
both the move and alleged later use. A fresh verifier independently reproduced the error.
The argument transfers ownership into its result and has no remaining borrow of `text`, but
the lifetime check follows the original aliases back to that moved source. A consuming-parameter
control, a hoisted `moved` binding, and a fresh allocation inside the argument compile.

Lifetime validation uses the analyzed result's surviving borrow dependencies. Caller-owned
cleanup covers transferred argument storage; later argument/body moves still invalidate actual
borrows. Semantic coverage checks one cleanup of the resulting parameter slot; O0/O3 Handle
execution checks the payload and zero outstanding allocations after the call. The named-call
path has an independently observed analogous scoped-argument rejection outside this lowering
correction. The comparison with the pre-continuation literal behavior is source tracing, not
an executed baseline build.

The correction passes the same sequential verification commands listed above: formatting/lint,
**1,220 library tests and 9 CLI tests**, zero failures/errors, 36 existing ignores, and no
compiler warnings; smoke **8/8** on retry; verified local publication; **12** clean benchmark
builds; and ASan/LSan **51/51** in 67 seconds. The first correction full run passed 1,218 library
tests and failed two native fixtures because their scoped argument contained an extra semicolon;
the corrected fixtures pass at O0/O3. The first smoke run reported 7/8 after `astar2` printed
`Path found!` and sbt reported success, then its thin client timed out joining the server thread
during shutdown. One full harness retry passed 8/8. QA and focused tracking review pass.
Fresh narrow independent re-review finds no actionable issues in the correction, dependency
producers, semantic cleanup assertion, or O0/O3 native allocation-count checks. The full
borrowing review has no other actionable findings; all reported findings are corrected.

### F1–F6 implementation

`CallStages` preserves typed application boundaries, original operands, and completion evidence.
`CallableValues` uses those boundaries for fixed-point argument flow, target alternatives,
residual prefixes, lexical substitutions, and final-invocation queries. `CallableIdentity`
is shared independently of ownership analysis. Generated locals extend callable and
availability evidence without rerunning parameter propagation.

`CaptureTransfers` follows evaluated callees, qualifiers, and immediate bodies. Ownership
checks each stage's parameter/result agreements, moves returned call-once values by identity,
and derives allocation/static-result contracts from the final invocation. Argument locals
preserve source diagnostics and single evaluation; intermediate callable locals retain
ownership, destructor identity, dependencies, and cleanup through existing binding helpers.

Regression coverage:

- `CallStagesTests`: 15 structural/flow cases for nullary and explicit Unit calls, partial
  prefixes, multiple stages, aliases, constructor fields, lexical recursion guards, unavailable
  operands, unknown alternatives, and later-stage parameter identities.
- `CallStageOwnershipTests`: 55 semantic cases for F1–F6. Negative fixtures establish parsing
  separately and assert diagnostics, source locations, resolved identities, capture metadata,
  and cleanup without requiring emission.
- `StringReturnTests`: two semantic cleanup checks and eight native cases at O0/O3 cover
  static/allocated final results and exactly-once cleanup of allocated/mixed immediate arguments,
  with chained and bound controls.
- `LocalCallableCodegenTests`: five native cases cover argument-before-callee effect trace
  `132`, caller-scope Handle cleanup, and both conditional PAP payload selections in chained
  and bound forms, plus native Handle destructor identity and allocation counts for an
  immediate argument. Existing invoked/uninvoked PAP cleanup cases cover F6 allocation contracts.
- `AppRewritingTests`: the returned-lambda case follows resolved argument identities through
  materialized bindings, asserting that source parameters receive `1` and `2` respectively.

Host: macOS arm64, Homebrew Clang 23.1.1 (`arm64-apple-darwin25.6.0`). No ABI or target-layout
change; the conditional Linux ABI gate does not apply. These results cover the shared-stage
repair before the fully applied literal-borrowing continuation. General emitter/PAP traversal
remains excluded.

Validation, 2026-10-19:

```sh
SBT_OPTS=-Dsbt.io.virtual=false sbtn 'scalafmtAll; scalafixAll; testFull'
```

Passed: **1,192 library tests and 9 CLI tests, zero failures/errors, 36 existing ignores**.
Formatting and lint passed without compiler warnings. The full suite includes the semantic,
LLVM-validation, native execution, and sanitizer regressions listed above. Intermediate full
runs caught a stale AST-shape assertion and two diagnostic-fixture mistakes (additional valid
diagnostics and an assumed whitespace-free span); the final fixtures retain source identity
and precise diagnostic assertions.

The following gates passed sequentially after both review corrections and the full suite:

```sh
SBT_OPTS=-Dsbt.io.virtual=false ./tests/smoke/run.sh all
SBT_OPTS=-Dsbt.io.virtual=false sbtn mmlcPublishLocal
make -C benchmark clean
make -C benchmark mml
```

Smoke: **8/8 passed**, including five source-build executions and three compile-only checks.
The first run reported 7/8: `partial-fac1` printed `120` and sbt reported success, then the
thin client failed with `TimeoutException: Unable to join thread ... after 5 seconds` during
shutdown. One complete harness retry passed 8/8 before publication.
Local publication succeeded after smoke verification. All 12 clean MML benchmark builds
passed; no performance measurements were taken.

```sh
./tests/mem/run.sh all
```

Memory: **51/51 passed** with ASan instrumentation at O0 and
`ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1`. The harness used the verified
local publication and completed after the benchmark builds. QA/style compliance and
`git diff --check` passed. Focused tracking review found no actionable inconsistencies in this
task, its parent links, or active memory. Full independent review is complete. Both confirmed
findings below are corrected; fresh narrow review of the final parameter-contract correction
reports no actionable findings. The narrow review is source-based; the full-suite results
establish execution of its regressions and the repeated-wrapper compatibility controls.

Independent review reproduced a double-free at O0 and O3 under ASan:

```mml
pub fn main(): Int =
  { text: String -> println text; { n: Int -> n; }; } (int_to_str 42) 0;
;
```

The immediate parameter took the prepared argument's ownership and freed the String, but
the staged call also emitted the prepared cleanup. The equivalent bound call, a named borrowing
callee, a consuming immediate parameter, and a literal argument passed at O0. The correction
filters prepared cleanup through the existing resolved-identity move evidence after stage
analysis. Seven added regressions pass: two semantic cleanup/identity checks, four O0/O3
allocated/mixed String cases, and a native Handle destructor/count case. F7 parameter-borrowing
semantics are unchanged. Two full-scope reviewers were interrupted by an automated content
flag. A fresh reviewer completed the full scope and independently verified this correction:
the exact reproducer prints `42` and exits zero under ASan/LSan at both O0 and O3.

The completed full review independently confirmed an unresolved-alternative contract gap:

```mml
fn consume(~text: String): Int = text.length;;
fn choose(flag: Bool, ~unknown: String -> Int): String -> Int =
  if flag then consume; else unknown;;
;
pub fn check(~unknown: String -> Int): Int =
  choose false unknown (int_to_str 42);
;
```

Known-only parameter lookup classifies the final argument using `consume.text`, discarding
the unresolved alternative. The generated caller omits String cleanup. The lookup predates
the shared stages; stage-specific argument classification exposes it on this later invocation.
The committed analysis retains cleanup for the third argument beyond `choose`'s parameters;
this baseline comparison is source inspection, not an executed baseline regression.

Durable native reproduction: save the MML source as `unknown.mml` and this driver as `driver.ll`:

```llvm
target triple = "arm64-apple-macosx"
%struct.String = type { i32, ptr }
@format = private constant [13 x i8] c"returned=%d\0A\00"
declare i32 @unknown_check({ ptr, ptr })
declare i32 @printf(ptr, ...)
define internal i32 @borrow(%struct.String %text, ptr %env) {
entry:
  %length = extractvalue %struct.String %text, 0
  ret i32 %length
}
define i32 @main() {
entry:
  %value = call i32 @unknown_check({ ptr, ptr } { ptr @borrow, ptr null })
  %printed = call i32 (ptr, ...) @printf(ptr @format, i32 %value)
  %ok = icmp eq i32 %value, 2
  %status = select i1 %ok, i32 0, i32 1
  ret i32 %status
}
```

```sh
mmlc -x lib -O0 -s -a -b current unknown.mml
clang -fsanitize=address -O0 driver.ll current/target/unknown.o \
  current/target/mml_runtime-arm64-apple-macosx.o -o unknown-borrow
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1 ./unknown-borrow
```

Before correction, the independent verifier observed `returned=2`, followed by one 3-byte
LeakSanitizer leak from `int64_to_str → int_to_str`, exit 1. A consuming callback control,
linked to the same library object, adds `declare void @free(ptr)` and extracts field 1 from
`%text`, calls `free` on that pointer, then returns the length. It prints `returned=2` and
exits zero under ASan/LSan. Both results use the host/toolchain recorded above.

The correction retains origin completeness in parameter agreement. A known target beside
an unresolved alternative cannot authorize transfer; the existing incompatible-parameters
diagnostic rejects the call. Entirely unknown targets retain their default non-consuming
argument behavior. Eight added regressions cover structural contract queries and chained/bound
semantic rejection, with entirely unknown cleanup controls.

An intermediate full run passed 1,188 library tests and 9 CLI tests, with four library failures
and 36 ignores. The strict completeness check rejected repeated aggregate identity wrappers:
two `MixedOwnershipTests` controls and two `MixedOwnershipCodegenTests` O0/O3 cases. Observed
flow conservatively stops at repeated formal cycles; complete lexical substitutions prove the
actual targets. Parameter queries use that complete lexical proof when observed flow is
incomplete, preserving complete observed contracts. The nested-wrapper structural regression
also checks the recovered parameter identities. The final full run passes all four controls
and all eight new cases. Fresh narrow independent review finds no actionable issues in the
correction, directly affected consumers, tests, or design text. It also traces uncalled partial
applications to the original callee's agreement diagnostic; the known-plus-unresolved uncalled
combination has source-trace support rather than a dedicated regression. The full review has
no other actionable findings.

### Restored baseline, 2026-10-19

Compiler sources, test sources, and related design text match `581da71`; the untracked
`CaptureTransfersTest.scala` is absent. The findings and reproducers remain in this task.

```sh
SBT_OPTS=-Dsbt.io.virtual=false sbtn testFull
```

Result: **1,107 library tests and 9 CLI tests passed, zero failures, 36 existing ignores**.
The full uncached suite completed successfully. This verifies the rollback, not the proposed
call-stage repair.

### Attempted implementation result, 2026-10-19

```sh
SBT_OPTS=-Dsbt.io.virtual=false sbtn 'scalafmtAll; testOnly mml.mmlclib.semantic.CaptureTransfersTest mml.mmlclib.codegen.LocalCallableCodegenTests mml.mmlclib.codegen.StringReturnTests'
```

Result: **105 total, 97 passed, 8 failed, 0 errors**. The failures are:

- `CaptureTransfersTest`: enclosing move lambda's second invocation lacks `UseAfterMove` (F6).
- `LocalCallableCodegenTests`: consuming-parameter `noalias` coverage rejects allocated Handles.
- Two evaluated-callee native cases reject Handle captures: argument effects before returned-PAP
  invocation, and cleanup of selected/unselected conditional PAPs.
- Two known-static-PAP payload-cleanup cases fail, with invocation disabled/enabled.
- Two environment-only-PAP capturing-callee cleanup cases fail, with invocation disabled/enabled.

The attempted additions comprised 41 semantic cases, two native evaluated-callee cases, and
four String-result cases (static/allocated at O0/O3). They are removed from the restart baseline.
The embedded findings preserve the required behavior and evidence without those test files.

An intermediate attempted implementation passed 1,144 library tests plus 9 CLI tests, with
36 existing ignores; smoke 8/8; ASan/LSan memory checks 51/51; local publication and clean MML
benchmark builds. The host was macOS arm64 with Clang 23.1.1. Independent review then confirmed
F3–F5 in that implementation. Those passing gates do not validate the approved implementation or
close the findings; benchmark builds are not performance measurements.

Required before handoff: focused acceptance cases, formatting/lint, full uncached tests,
smoke before publication, verified local publication, clean benchmark builds, memory ASan/LSan,
QA enforcement, tracking review, and fresh independent code review under repository rules.
Linux ABI verification applies if the approved implementation changes ABI or target layout.

## Risks / Notes

Shared stage boundaries do not establish complete target knowledge. Observed callable flow
and lexical proofs remain separate; an unknown alternative cannot establish a static-result
proof. Native evidence is limited to the fixtures in Verification and Emitter limits.
Benchmark builds establish build compatibility, not performance.

## Signoff

- Workstream signoff: granted for shared-stage ownership and fully applied literal borrowing.
- Tracked item completion: complete.
- Commit authorization: granted for the implementation, tests, design, and completion records.
- Push authorization: not granted.

## Task Working Memory

- Scope: shared-stage ownership and fully applied source-lambda borrowing; complete and signed off.
- Restart baseline: `581da71`. Findings and reproducers are preserved here; attempted-repair
  results are historical evidence.
- Fully applied source parameters borrow unless consuming. Allocated inputs retain caller-owned
  cleanup; ordinary local bindings retain transfers. Lifetime checks use surviving borrow
  dependencies and accept ownership transferred through scoped arguments.
- Final verification: 1,220 library plus 9 CLI tests, zero failures/errors, 36 existing ignores;
  formatting/lint without compiler warnings; smoke 8/8 on retry; verified local publication;
  12 clean benchmark builds; ASan/LSan 51/51. QA and focused tracking review pass. Shared-stage
  and borrowing independent reviews are complete; all confirmed findings have corrected code,
  regression coverage, and clean fresh narrow re-review.
- No implementation work remains in the approved scope. General emitter/PAP traversal and
  tail-recursion work remain excluded. The scoped-argument named-call limitation is recorded
  in Verification. Push remains unauthorized.
