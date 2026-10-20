# Unify call-stage interpretation and repair ownership

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** BUG, CORRECTNESS
- **Priority:** HIGH
- **Created:** 2026-10-19
- **Target Branch:** dev-2026-03-21-lambdas
- **Parent:** [Unify lambdas](unify-lambdas.md)

## Execution Checklist

1. [ ] **design** — Settle the shared call-stage contract using the [approved restart baseline](#restart-decision).
   - Next action: approve the bounded implementation plan.
2. [ ] **planned** — Implement one call-stage interpretation and migrate callable flow,
   capture transfers, and ownership consumers; cover findings F1–F6.
3. [ ] **planned** — Run compiler gates and independent review; obtain scoped signoff.
4. [ ] **planned** — Plan and repair [fully applied literal borrowing](#f7-borrowing-in-fully-applied-lambda-literals)
   as a separate bounded slice, then verify and obtain signoff.

## Problem

A chained application can invoke a function, obtain another function, and invoke that result.
Each invocation is a call stage. Flattening the chain into one callee and all its arguments
loses which function owns each parameter and result.

Callable flow, capture-transfer collection, and ownership analysis interpret those stages
separately. Their decisions disagree about applied parameters, invocation, consuming captures,
and result ownership. The consequences include false borrowed-value errors, accepted reuse of
call-once functions, lost cleanup, and an observed invalid free of a static String.

The attempted implementation ended with 97 passing focused tests and 8 failures. The restart
baseline is `581da71`. A single shared interpretation is proposed; implementation approval is pending.

## Outcome

Equivalent chained and explicitly bound calls preserve the same ownership contracts and
diagnostics. Each argument reaches its actual parameter, each invocation consumes the correct
callable, and the final invoked function determines result ownership. Evaluation order,
resolved identities, and exactly-once cleanup remain intact.

## Scope

- In scope: evaluated callee bodies and qualifiers; returned callables; partial applications;
  nullary calls; callable-argument flow; parameter agreement; final-result ownership;
  capture transfers and caller-scope cleanup. Findings F1–F6 define the primary repair.
- Separate bounded slice: F7, borrowing through fully applied lambda-literal lowering.
- Outside the primary repair: general chained-call emission, PAP elaboration inside callee
  bodies, tail-recursion cleanup, broad ignored-test restoration, and unrelated optimization.
  [Emitter limits](#emitter-limits) constrain evidence, not the semantic acceptance criteria.

## Findings and reproducers

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

The attempted repair accepts the original and bound forms semantically. General immediate
chaining remains subject to the emitter limit below; this example is not native evidence.

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
and sanitizer checks. The latest candidate String regressions pass in the focused run, but
the implementation as a whole still fails verification.

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
Source inspection strongly supports the common nullary mismatch as the cause of all eight
failures; a corrected implementation has not been tested.

Application arity and source parameter count must remain distinct. Synthetic Unit completes
the invocation but must not bind a nonexistent source parameter.

### F7: borrowing in fully applied lambda literals

**Existing independent bug; separate repair plan required.**

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
order, and cleanup. This finding is recorded here in full, but its implementation follows
the call-stage repair under a separate bounded approval.

### Emitter limits

General chained immediate-lambda applications have a separate emission restriction in
[Applications.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Applications.scala).
`compileLambdaApp` supports a single unary/nullary immediate application; arbitrary chains
are not established as executable by semantic acceptance. Named chains also blocked native
confirmation of F4 and F5. F3 does have an executed native ASan failure.

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

Compiler sources, test sources, and related design text match the committed baseline.
Findings and reproducers remain embedded in this task. Recreate acceptance tests within the
approved implementation plan; failing candidate tests are not part of the restart baseline.
Resetting source does not replace a locally installed compiler; development checks must use
repository source through `sbtn` until a verified build is published. A published attempted
repair is not a trustworthy baseline.

Rollback: complete. Revised implementation approval: pending.

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
5. Validate F1–F6 and controls, then run required gates and independent review before scoped
   signoff. Plan F7 separately after that checkpoint.

The design must settle dependency direction before implementation: stage structure must not
depend on ownership decisions, and callable-flow fixed-point propagation must not depend on
ownership-generated bindings. Centralization is intended to remove conflicting interpretation,
not to combine all analyses into one module.

## Verification

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
F3–F5 in that implementation. Those passing gates do not validate the latest candidate or
close the findings; benchmark builds are not performance measurements.

Required before handoff: focused acceptance cases, formatting/lint, full uncached tests,
smoke before publication, verified local publication, clean benchmark builds, memory ASan/LSan,
QA enforcement, tracking review, and fresh independent code review under repository rules.
Linux ABI verification applies if the approved implementation changes ABI or target layout.

## Risks / Notes

Three analyses currently duplicate call boundaries, and the attempt also changed result
ownership and cleanup. Repairing only the nullary predicate would address the latest visible
regression without establishing one consistent interpretation for the other findings.

The attempted source, test, and design changes are removed. The embedded findings define
regressions to cover during the approved repair; they do not establish an accepted solution.
No finding is closed by the extraction, rollback, or a passing intermediate checkpoint.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: granted for this task, parent links, and memory only; no implementation commit.
- Push authorization: not granted.

## Task Working Memory

- Primary scope: F1–F6; F7 remains the next separately planned borrowing repair.
- Restart baseline: `581da71`; compiler sources, test sources, and related design text are restored.
  Findings and reproducers are preserved here. Attempted implementation results are historical evidence.
- The shared call-stage abstraction is proposed and not implemented. Next action: settle and
  approve its bounded plan before restarting production work.
