# Unify Unit parameter lowering

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG
- **Priority:** HIGH
- **Created:** 2026-10-23
- **Target Branch:** dev-2026-03-21-lambdas
- **External Reference:** none

## Execution Checklist

1. [x] **complete** — Audit Unit bindings across emission paths and approve a unified repair.
2. [x] **complete** — Preserve lexical Unit bindings while omitting runtime operands; add regressions.
3. [x] **complete** — Address all eleven external-review findings; repeat compiler gates and reviews.
4. [x] **complete** — Verified repair accepted; Author signoff granted.

## Problem

Passing a function with an explicit Unit parameter as a value creates a forwarding lambda.
Regular lambda emission removes Unit parameters from both the LLVM signature and the local
scope. Its body still references the generated parameter. The missing local falls through
to global loading, producing invalid LLVM such as `load void, ptr @$p0`.

## Outcome

Unit parameters remain valid lexical bindings in every function-emission path while occupying
no runtime operand. Unit argument expressions retain their effects and evaluation order.
A missing local binding cannot silently become a global reference.

## Scope

- Ordinary functions, non-capturing and capturing closures, generated forwarding lambdas,
  and tail-recursive functions.
- Shared parameter lowering and correct local/global reference classification.
- Direct calls, function values, explicit Unit parameters, nullary controls, and effectful
  Unit arguments in semantic, LLVM-validation, and native-execution regressions.
- Out of scope: returned-callee ownership classification and other callable-type repairs.

## Plan (Approval Gate)

Approval: shared parameter lowering, scope migration, guarded reference classification,
regressions, verification, and task bookkeeping are approved. Task closure and local commit are authorized.

- [x] Audit the five parameter scope builders and approve the repair boundary.
- [x] Introduce one parameter projection retaining source identity/index, LLVM type, and runtime
  position; validate counts and share declarations, scope construction, and tail-call indices.
- [x] Migrate global, direct-local, capturing, noncapturing, and loopified bodies; preserve
  capture/self/environment composition and remove discarded parameter setup.
- [x] Reject missing local/unresolved references instead of silently loading globals.
- [x] Preserve argument evaluation before Unit erasure and add binder-read, effect-order,
  identity, and diagnostic regressions with O0/O3 LLVM and native checks.
- [x] Run formatting/lint, smoke, full suite, host/Linux interoperability checks, publishing,
  benchmarks, memory checks, QA, independent code review, and tracking review.

## External review corrections

Approved scope: address all eleven external-review findings. The reviewer reported one medium
finding (captured Unit values emitted as physical `void` fields) and ten low findings:
commutative runtime test formulas; missing direct-entry and loopification assertions; missing
false-branch effect coverage; an overbroad declaration claim; redundant runtime indices;
repeated argument-scope register lists; redundant local-emitter nesting; nested regular-emitter
flatMaps; redundant lambda-helper arguments; and trailing blank lines.

The correction keeps Unit captures in lexical scope while omitting their physical fields,
strengthens the runtime and routing assertions, and simplifies the shared lowering and emitters.
The four O0/O3 capture regressions failed before the correction with `llvm-as` rejecting
`{ void, i32 }` and `{ ptr, void, i32 }` environment types. They pass after the correction,
including LLVM assembly and native execution with ASan/LSan. The affected parameter-lowering
and local-callable suites pass all 85 tests. Formatting/lint and smoke (8/8) pass.
The full suite passes 1,487 library and 9 CLI tests. Both Linux containers pass 187/187 affected
tests. The first emulated x86-64 run passed 182 and timed out on five tests; the identical retry
passed all 187. Publishing and the clean benchmark build pass; ASan+LSan passes 52/52.
QA, tracking consistency, and independent correction review pass. No actionable findings remain.

## Verification

### Correction verification

- Host: arm64 macOS, Homebrew Clang 23.1.1. `sbtn 'scalafmtAll; scalafixAll'` passes.
- `./tests/smoke/run.sh all`: 8/8 passes before full-suite and publishing gates.
- `sbtn testFull`: 1,487 library tests and 9 CLI tests pass.
- The six-suite container command below passes 187/187 in each builder, run sequentially.
  ARM64 executes in the native-architecture Linux VM; x86-64 executes under Docker emulation.
  Both use Ubuntu Clang 20.1.8. The first x86-64 run passes 182 and times out on five tests
  in `FunctionValueCodegenTests`, `FunctionSignatureTest`, `AggregateAbiTests`,
  `OwnedClosureTailRecursionTests`, and `LocalCallableCodegenTests`; the identical retry passes all 187.
- `sbtn mmlcPublishLocal`, `make -C benchmark clean`, and `make -C benchmark mml` pass in order.
  Benchmark binaries build successfully; no performance measurement is claimed.
- `./tests/mem/run.sh all`: ASan+LSan 52/52 passes after publishing, in 69 seconds.
- QA enforcement, tracking consistency, and `git diff --check` pass.
- Independent correction review: no actionable findings. A fresh primary reviewer and separate
  fresh style/QA, test-quality, and readability reviewers completed the correction review.
  No candidate claims required independent verification. No checks or independence requirements
  were skipped.

### Initial implementation verification

- Host: arm64 macOS, Homebrew Clang 23.1.1.
- `sbtn 'scalafmtAll; scalafixAll; testFull'`: formatting/lint passed; 1,481 library tests
  and 9 CLI tests passed, including O0/O3 factory execution with ASan/LSan.
- `./tests/smoke/run.sh all`: 8/8 passed on retry. The initial run was 7/8 because the
  sbt client timed out during disconnect after `astar2` compiled and executed successfully.
- Linux ARM64 (`mml-linux-arm64`, aarch64, Ubuntu Clang 20.1.8): 181/181 selected tests
  passed with execution inside the container. Suites: `ParameterLoweringTests`,
  `LocalCallableCodegenTests`, `FunctionValueCodegenTests`, `OwnedClosureTailRecursionTests`,
  `AggregateAbiTests`, and `FunctionSignatureTest`, invoked through one root `sbtn testOnly`.
- Linux x86-64 (`mml-linux-amd64`, x86_64, Ubuntu Clang 20.1.8): the same 181/181 tests
  passed under Docker emulation on the ARM host. This establishes emulated execution, not
  native x86-64 hardware performance.
- `sbtn mmlcPublishLocal`: passed after smoke/full/container verification.
- `make -C benchmark clean` followed by `make -C benchmark mml`: passed. This is the
  required benchmark build gate; no performance measurements are claimed.
- `./tests/mem/run.sh all`: ASan+LSan 52/52 passed with the published compiler.
- QA enforcement and `git diff --check`: passed.
- Independent code review: no actionable findings. Separate fresh code-style/QA, test-quality,
  and readability checks completed with no findings. No candidate claims required verification.
- Tracking review: task checklist, verification, tracker focus, and pending signoff agree.

The exact container test command, run sequentially in each builder:

```sh
sbtn "testOnly mml.mmlclib.codegen.emitter.ParameterLoweringTests mml.mmlclib.codegen.LocalCallableCodegenTests mml.mmlclib.codegen.FunctionValueCodegenTests mml.mmlclib.codegen.OwnedClosureTailRecursionTests mml.mmlclib.codegen.AggregateAbiTests mml.mmlclib.codegen.FunctionSignatureTest"
```

### Binder-read regression baseline

On 2026-10-23, before the compiler repair, the four `Unit binder*` tests in
`LocalCallableCodegenTests` failed at O0/O3 with `llvm-as` reporting
`void type only allowed for function results` on `%0 = load void, ptr @u`.
Command: `sbtn 'testOnly mml.mmlclib.codegen.LocalCallableCodegenTests -- *Unit*binder*'`.
The same four tests pass after the shared-lowering repair, including LLVM assembly and
native execution. Broader verification is recorded above.

### Original reproduction

On 2026-10-23, the following fixture was compiled with `sbtn "run <fixture>.mml
-b <output-dir> -O0"` using the callable-type repair working tree based on `d4566a1`.
Compilation reached `llvm-as`, which rejected the generated wrapper:

```llvm
%1 = load void, ptr @$p0
%2 = call { ptr, ptr } @owned_factory()
```

Diagnostic: `void type only allowed for function results`.
Host: arm64 macOS, Homebrew Clang 23.1.1. No baseline execution or fix verification claimed.

```mml
type Callback = Int -> Int -> Int;

fn add3(a: Int, b: Int): Int = a + b;;

fn apply(factory: Unit -> Callback): Int = let q = (factory ()) 1; q 2;;

fn apply_inline(factory: Unit -> Callback): Int = (factory ()) 1 2;;

fn owned_factory(u: Unit): Callback =
  let text = int_to_str 12345;
  ~{ a: Int, b: Int -> text.length + a + b; };
;

fn static_factory(u: Unit): Callback = add3;;

pub fn main(): Unit =
  println (int_to_str (apply owned_factory));
  println (int_to_str (apply owned_factory));
  ();
;
```

## Risks / Notes

Unit has no runtime payload, but Unit expressions can have effects. Dropping their evaluation
would violate the language's evaluation order. All five body emitters use the same lexical
parameter policy; loopified bodies supply PHI registers for runtime parameters.

Selected after [Unify and simplify callable-type handling](typecheck-grouped-callable-results.md).

## Signoff

- Workstream signoff: granted.
- Tracked item completion: approved after verified repair and review.
- Commit authorization: granted for the bounded repair and its records.
- Push authorization: not granted.

## Task Working Memory

Shared parameter lowering supplies declarations, runtime positions, source indices, and lexical
scopes for global/local functions, capturing/noncapturing closures, and loopified bodies. Unit
bindings survive without registers; computed parameter types take precedence over annotations.
Scope entries retain names and resolved IDs. Missing local/unresolved references produce
codegen diagnostics, and discarded parameter setup is removed. Argument evaluation is unchanged.

Binder-read regressions demonstrate the original invalid LLVM and pass after the repair.
Coverage includes effect order, conditional arguments, partial application, shadowing,
recursion, nullary/global controls, count/type diagnostics, and separate owned/static factory
contracts at O0/O3. Initial compiler gates and independent review passed. External-review
corrections are implemented; all compiler gates pass, including both Linux containers.
Independent correction review and all three dedicated checks report no actionable findings.
Unit captures retain lexical identity without physical fields; tests distinguish operand order,
assert direct/loop routing, and execute both conditional branches.

All scoped implementation, verification, review, and signoff steps are complete.
No remaining task work. Local commit is authorized; push is not authorized.
