# Repair protocol error recovery and integration

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** CORRECTNESS
- **Priority:** HIGH
- **Created:** 2026-10-27
- **Target Branch:** dev/protocols
- **Parent:** [Implement protocols — Num checkpoint](protocols.md#p6-num-review-checkpoint)

## Execution Checklist

1. [x] **complete** — Keep failed protocols and instances from causing unrelated registry errors.
2. [x] **complete** — Prevent repeated checking of nested expressions containing a type error.
3. [x] **complete** — Repair entry-point selection, editor references, and declaration recovery.
4. [x] **complete** — Add integration coverage, correct the grouping diagnostic cascade, and consolidate identical small rules.
5. [x] **complete** — Repair lexical inference identity, native instance symbols, editor protocol syntax, and the expected-result description.
6. [x] **complete** — Pass compiler gates and independent review; obtain Author signoff.

## Problem

Protocol error recovery must distinguish rejected source declarations from broken internal lookup
state. Nested type errors must not cause exponentially repeated checking. Compiler and editor
consumers must distinguish signatures, lowered instance functions, and ordinary source functions.

## Outcome

Invalid source reports useful errors without hiding valid neighboring code or repeatedly
checking the same failed subtree. Protocol members behave correctly in entry-point validation,
editor reference search, recovery, and ownership checking.

## Repairs and acceptance criteria

### 1. Failed declarations and diagnostic recovery

An incomplete instance such as `instance Num Bool = op +(x, y) = x or y;; ;` lacks the other
Num members. Its missing-member diagnostic is expected. Internal registry errors on ordinary
Int and Float arithmetic elsewhere in the module are not.

`ProtocolChecker.lowerInstance` registers the instance key before checking members. A rejected
instance retains that key and its diagnostic causes, but `ProtocolRegistry.signatureCandidates`
excludes its incomplete bindings. This preserves duplicate detection and valid neighboring
candidates. Unmarked missing associations remain internal errors.

A protocol with two required functions named `f` is rejected. Its signature lookup retains the
original diagnostic causes, so reference resolution and grouping do not report missing internal
IDs. Valid declarations sharing the name remain usable. Uses of only rejected signatures carry
the declaration failure through ordinary invalid-expression recovery.

Tests must cover a valid neighboring instance, unused and used invalid signatures, repeated invalid
instances, aliases, and source declarations colliding with injected Num. Assert visible diagnostics
and their source ranges through the full semantic pipeline, not only the first rejecting phase.

### 2. Failed nested expressions

In `1.0 + 2 + 3.0 + 4.0`, the mixed first pair is invalid. Probing that prefix and checking it
again for each enclosing candidate doubles the work at each level. Independent operand errors
are retained once and rule out enclosing trials; expected types cannot repair the mixed pair.
The same rule applies to deeply nested right operands.

Reuse checked failures when further context cannot change their meaning. Preserve contextual
lambda and overload inference, original diagnostic causes, and independent candidate trials.
Prefer a shared checking rule to a separate cache or special numeric-expression path.
Use readable, literal regressions and bounded measurements that separate parsing from type checking.

### 3. Compiler and editor integration

- **Entry points:** a lowered protocol member named `main` is an ordinary implementation binding,
  not the program's entry point. With no user entry point, report that `main` is missing. Keep
  user-defined public `main` selection independent of declaration order.
- **Find references:** after type selection, calls carry instance binding IDs. Searching from the
  protocol signature must still find calls across its instances. Reuse the registry's associations
  instead of identifying implementations from generated-name spelling.
- **Recovery:** several parser rules copy lists of declaration-start keywords. They disagree about
  visibility modifiers, `protocol`, `instance`, `type`, and `struct`. Share the declaration boundary
  rule so a malformed header or link directive cannot swallow the next declaration or its visibility.

### 4. Missing tests and local simplification

- Execute a protocol instance over `String`: consuming parameters, an owned return, and a partial
  application retaining an owned argument. Run under address and leak sanitizers. Existing scalar
  and move-closure tests do not establish heap-type substitution and cleanup.
- Test conflicting precedence/associativity against injected Num and free standard operators.
  Include actual operator uses in both declaration orders, so grouping cannot silently select the
  first declaration's attributes after recording a conflict.
- Check visible messages, primary-error counts, and source ranges for missing instances,
  incompatible arguments, insufficient type information, ambiguous selection, and grouping conflicts.
- Cover duplicate source/injected instances and duplicate source/injected Num declarations.
- Use the existing shared sanitizer runner in `FunctionValueCodegenTests`; keep its program and
  expected output visible.
- Consolidate identical standard-type ID construction, signature ID construction, instance-member
  arity, parameter-count-to-arity conversion, operator defaults, and lowered-member ID collection.
  Preserve the established Int implementation IDs and explain why their spelling differs from Float.
- Replace brittle newly extended App patterns with named queries, separate substantial matches into
  readable paragraphs, and fix the overlong expression and detached doc-comment.
- Make frontend corpus outcomes typed so an unknown outcome cannot silently produce a successful
  process exit. Explain semantic-token keyword lengths consistently with neighboring cases.
- Reconcile the uncached suite count with the recorded baseline and added tests. An incremental
  count without the exact source/test selection is not proof of a missing test.

Broader representation and phase-boundary choices are in
[Protocol design discussions](protocols-design-discussions.md). They are not automatic refactors
within these repairs. Unrelated parser-time growth and missing editor diagnostics have separate tasks.

## Review cross-reference

The descriptions above are authoritative; these labels only support comparison with review notes.

| Repair area | Review labels |
|---|---|
| Failed declarations | C1, C2, T5 |
| Repeated failed checking | C3 |
| Entry point, references, recovery | C4b, C5, V6 |
| Heap execution, grouping, diagnostic coverage, shared test runner | T1–T4, T6 |
| Identical small rules and focused readability | V1, V3–V5, V8–V10, V13, V16–V17, V25, V27, V32, V34 |

## Error-recovery checkpoint verification

Pre-repair source inspection independently confirmed the two registration/lookup failure paths and
repeated checking of failed nested expressions. Entry-point execution and editor-reference
regressions cover the additional integration defects; their results are included below.

Reported type-checker measurements for an erroneous left-associated chain were 69 ms at 8 terms,
837 ms at 16, 2.8 s at 20, 7.8 s at 22, and 28.1 s at 24. The all-Float control was 42–58 ms.
These are review-reported observations, not measurements repeated for this repair. Do not infer
constant-time successful checking or immunity of all right-associated expressions from that control.

The parent records the preceding full-suite, smoke, publish, benchmark-compilation, and sanitizer
results. They are baseline evidence; the results below verify the repairs.

The first executable focused run passed 340 of 343 tests. Failed-declaration, entry-point, reference
search, parser recovery, and both 24-term failure regressions pass. All four new heap execution cases
pass ASan/LSan, including partial invocation and drop at O0/O3. Two failures are fixture mistakes:
the displayed canonical type is Int32, and an Int-returning consuming call must bind its result
instead of occupying a Unit statement position. The third exposed a second primary diagnostic
after a grouping conflict. The rewriter attributes that failed use to the original conflict;
independent-error controls cover the correction.
Initial test compilation also exposed result-type mismatches and an unused local; both are corrected.
The corrected focused run passes all 366 cases, including application rewriting and four controls
for grouping conflicts, unaffected unary syntax, and independent errors. Formatting and linting pass;
no Scala compiler warnings remain.

Compiler gates pass on macOS arm64 with Clang/LLVM 23.1.1:

- `sbtn testFull`: 2,277 library and 9 CLI tests pass. The repair diff adds exactly 47 test
  declarations and removes none, accounting for the increase from the 2,230-test full baseline.
  The reported 2,229 incremental count lacks an exact source/test-selection record; it does not
  identify a missing case in this uncached run.
- `./tests/smoke/run.sh all`: all eight fixtures pass.
- `sbtn mmlcPublishLocal`: the verified compiler is installed locally.
- `make -C benchmark clean` and `make -C benchmark mml`: all benchmark variants compile.
  No benchmark execution-performance claim is made.
- `./tests/mem/run.sh all`: all 52 fixtures pass ASan/LSan in 73 seconds.
- `git diff --check`, main-agent QA, and focused tracking consistency/local links pass.

No ABI layout or calling-convention changes are present; Linux-container and hosted ABI gates are
not applicable.

Independent review covers the 51-file repair delta and directly affected callers and callees.
The fresh primary correctness/documentation review and separate fresh QA/style, test-quality,
and human-readability checks all complete with no actionable findings. No claim verifiers are
needed because no atomic candidates remain. The review leaves the verified sources unchanged.
An initial primary stopped after its skill discovery read opening skill prose; the replacement
primary uses only the supplied review protocol and preserves the unaffected independent checks.
Review is static; the execution evidence above supplies the build, runtime, sanitizer, and bounded
timing results.

## Standalone protocol corrections

Lambda inference keys constraints and lexical aliases by resolved binding identity. A nested Float
binding cannot constrain an outer same-name Bool parameter; aliases retain their lexical scope.
The regressions cover Num and named members in both statement orders, alias shadowing, missing
outer evidence, and real conflicting constraints. Native instance lowering preserves the source
external symbol for bare `@metal:ffi`, retaining explicit names and template metadata. Direct calls
and function values exercise ordinary, implicit-instance, explicit-instance, and template cases.

The Tree-sitter grammar accepts protocol and instance declarations, bodyless signatures, and type
variables. Regenerated parser artifacts and Zed queries support their highlighting, outline, and
indentation. Five corpus cases cover the added syntax and missing-signature-type recovery; both
tracked protocol examples are reference inputs. A missing final protocol terminator can still absorb
a following instance into an error node; general incomplete-block recovery is outside this repair.
The protocol draft describes the implemented expected-result path and its nullary-selection test.

Verification on macOS arm64 with Clang/LLVM 23.1.1 and Tree-sitter 0.27.0:

- The inference regression run fails four of 85 cases before the correction. The corrected
  `sbtn 'testOnly mml.mmlclib.semantic.NumSelectionTests mml.mmlclib.semantic.ProtocolSelectionTests mml.mmlclib.semantic.TypeCheckerTests'`
  passes all 140 cases.
- The implicit native-instance case fails symbol assertions and executable linking before the
  correction; its three controls pass. The corrected
  `sbtn 'testOnly mml.mmlclib.semantic.ProtocolCheckerTests mml.mmlclib.codegen.ProtocolExecutionTests'`
  passes all 63 cases.
- `sbtn 'scalafmtAll; scalafixAll'`, all eight smoke fixtures, and local publishing pass.
- `sbtn testFull` passes 2,292 library and 9 CLI tests. Fifteen added test declarations account
  for the increase from the 2,277-library-test error-recovery checkpoint. New inference executions
  pass at O0/O3; native-symbol executions pass at O0, with address and leak sanitizer checks.
- `make -C benchmark clean` and `make -C benchmark mml` pass. No performance measurements are claimed.
- `./tests/mem/run.sh all` passes all 52 ASan/LSan fixtures in 77 seconds.
- `bash tooling/zed/test.sh` passes all 16 corpus cases, eight reference programs, and four queries.
  Outline, indentation, and named/unary operator highlight captures are inspected.
- Both Linux builders pass all 27 tests from
  `sbtn 'testOnly mml.mmlclib.codegen.ProtocolExecutionTests mml.mmlclib.codegen.NumExecutionTests'`,
  invoked sequentially with `docker compose exec -T <service> bash -lc`.
  `mml-linux-arm64` executes Linux aarch64 on native ARM hardware; `mml-linux-amd64` executes Linux
  x86-64 under Docker/Rosetta emulation. Both use Ubuntu Clang/LLVM 20.1.8 and GraalVM JDK 25.0.4.1.1.
  These are executed fixtures, not cross-compilation-only checks; emulated timings are not performance
  evidence. No ABI layout or calling-convention changes are present.
- Implementation QA and `git diff --check` pass. Fresh narrow primary review and separate fresh
  QA/style, test-quality, and human-readability checks complete with no actionable findings.
  No claim verifiers are needed because no candidates remain. Independent scratch regeneration
  matches all three generated Tree-sitter artifacts byte-for-byte; all 16 corpus cases and inspected
  outline/indentation captures pass. The review uses the compiler, sanitizer, and Linux execution
  evidence above without rerunning unchanged suites.
- Optional cloud verification is deferred; no hosted run is dispatched.
- Final `sbtn 'scalafmtAll; scalafixAll; compile'` passes after the private
  `ProtocolCheckState` rename and documentation pass. Executable tokens are unchanged apart
  from the rename; the compiler, sanitizer, grammar, and Linux results above cover the fixes.

## Approval and signoff

- Implementation: approved for immediately relevant protocol defects, regressions, and local cleanup.
- Design follow-ups and unrelated repairs: separate approval required.
- Workstream signoff: approved for the error-recovery repairs and four standalone corrections.
- Tracked item completion: complete.
- Commit authorization: granted for all repairs and related records. Push is not authorized.

## Task Working Memory

The error-recovery repairs and four standalone corrections are complete and signed off.
Compiler, sanitizer, grammar, required Linux checks, and independent review pass as recorded above.
Optional cloud verification is deferred. No implementation work remains in this task.
The parent Protocols task stays open: Num checkpoint signoff, local binding inference, Clone
integration, and separate design discussions remain outside this closure.
