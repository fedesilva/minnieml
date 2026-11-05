# Bound parsing work for nested parenthesized expressions

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** BUG
- **Created:** 2026-10-27
- **Target Branch:** dev/parser-parentheses-performance

## Execution Checklist

1. [x] **complete** — Reproduce parser-time growth with bounded nested-expression fixtures;
   see [measurements and reproduction](parser-parenthesized-expression-performance-evidence.md).
2. [x] **complete** — Identify repeated parser work and approve the
   [focused repair plan](#focused-repair-plan).
3. [x] **complete** — Repair the repeated work while preserving parsing and recovery behavior.
4. [x] **complete** — Scaling, diagnostics, compiler gates, and independent review pass;
   Author review and signoff are complete.

## Problem

Valid right-nested expressions such as `1 + (2 + (3 + 4))` cause repeated parsing of the same
suffix. Bounded parser-only measurements confirm approximately doubled rule work per added
nesting level in ordinary and member-body expressions. Nested first-element tuples repeat
more work through both selection and group-versus-tuple alternatives.

This is distinct from repeated type checking of an erroneous expression, which belongs to the
immediate protocol repairs. It is also distinct from the known
[stack overflow on long sequences](compiler-stack-safety.md).

## Outcome and scope

Parsing a moderately nested valid arithmetic expression performs bounded work proportional to
its structure rather than repeatedly parsing the same nested suffix. Preserve precedence, grouping,
source spans, valid callable groups, and malformed-input recovery. Do not change language syntax.

## Evidence and reproduction

The [bounded reproduction](parser-parenthesized-expression-performance-evidence.md) records
the source revision, JVM, exact input generator and hashes, commands, rule counts, and timing
samples. All 15 inputs parse without recovered errors, with equal instrumented and ordinary
ASTs. Two warmed measurement forks agree on rule counts: 12 arithmetic terms use 521,228
callbacks, 14 use 2,084,876, and 16 use 8,339,468. Median parse times at 16 terms are 49.09 ms
and 49.50 ms. Tuple controls expose a second shared-prefix problem; selected groups provide
a linear control. [Repair measurements](parser-parenthesized-expression-performance-evidence.md#repair-measurements)
record 3,452 callbacks for the repaired 16-term case, with 420 added callbacks per two added
terms. All 15 measured inputs and 68 compatibility cases retain exact raw AST hashes and
diagnostic counts. Compiler checks, independent review, and Author signoff are complete.

### Compiler checks

Verification on macOS aarch64 with JDK 25.0.4.1:

- The initial four work regressions fail on the unchanged parser in both arithmetic paths
  and nested tuples; the selected-group control passes.
- Focused parser, span, recovery, and O0/O3 sanitized execution checks pass: 89 tests before
  the final annotation and 32-term deadline additions.
- `sbtn 'scalafmtAll; scalafixAll; testFull; mmlcPublishLocal'` exits 0. All 2,538 library
  tests and nine CLI tests pass, with zero skipped tests in the XML reports. This includes
  all nine parser regressions and both sanitized execution regressions. The 32-term fixture
  exits cleanly within its five-second child-process deadline.
- The first `./tests/smoke/run.sh all` run reports 7/8 because the thin client times out
  closing its connection after `nested-tco` prints its expected checksum and reports success.
  The complete retry exits 0 with 8/8 fixtures passing.
- `make -C benchmark clean` and `make -C benchmark mml` exit 0; all 37 binaries build.
  This establishes build compatibility, not benchmark execution timings.
- `./tests/mem/run.sh all` exits 0: 52/52 ASan+LSan fixtures pass in 86 seconds.
- ABI container checks do not apply: the change is confined to parser structure and tests,
  with no ABI, layout, or calling-convention change.
- Local QA enforcement and `git diff --check` pass. Independent primary review and all four
  dedicated checks are complete. They identify one independently confirmed documentation
  finding: `opRefP` also accepts lowercase operator names. The corrected comment is recorded
  as [QA-005](qa-misses.md#qa-005-describe-lowercase-operator-references-in-the-parser-contract).
  A fresh focused run passes all 92 parser/span/recovery/execution tests. Fresh narrow primary
  review and all four dedicated checks accept the one-sentence documentation correction with
  no actionable findings. No executable code changes follow the successful compiler checks.

### Historical review report

The source family is a function returning `1 + (2 + (... + N))`, with one matched parenthesis pair
per nested suffix. Use fixed literal fixtures for correctness tests and separate controlled workloads
for timing. Measure the parser independently of type checking, code generation, and process startup.

Review-reported parse totals, with the same observations in the baseline preserved by `be195134`:

| Terms | Parse time |
|---:|---:|
| 16 | 1.2 s |
| 20 | 13 s |
| 22 | 51 s |
| 24 | More than 120 s |

These are historical reported timings, not the warmed baseline above. Differences in workload
format and measurement conditions prevent direct latency comparison.

## Focused repair plan

Approval: granted for the focused repair, its regressions, documentation, and verification.

### 1. Preserve the parser contracts with focused regressions

Use literal fixtures for ordinary function bodies and module-level binding expressions. Cover
nested arithmetic, nested first-element tuples, nested selected groups, and combinations of
these forms. Use the existing parser instrumentation for work bounds proportional to source
size; allow headroom instead of pinning exact rule names or callback totals. Reuse
`ParserBaseline` for timing outside correctness assertions.

Retain and extend the directly relevant controls:

- [ParserCommitmentTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/grammar/ParserCommitmentTests.scala):
  grouped lambda applications, tuple alternatives, lexical parameter identities, selections,
  and missing delimiters.
- [ExpressionSourceSpanTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/ExpressionSourceSpanTests.scala):
  group and field spans, annotations and their expression endpoints, and the distinction
  between `()` and the empty-tuple error `( )`.
- [ConditionalRecoveryTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/ConditionalRecoveryTests.scala):
  recovered conditionals inside groups, tuples, and parameterized lambdas; preserve later members.

Characterize incomplete field suffixes, missing tuple elements, trailing commas, and missing
closers before changing commitment points. Assert retained children, source ranges, and
diagnostic roots, not only parser success. Use the existing process-deadline helper for
termination-sensitive malformed cases.

### 2. Factor shared prefixes in expression parsing

Keep the repair within
[expressions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala)
and its directly affected tests/documentation:

- Parse each parenthesized base once, then attach zero or more `.field` suffixes. With no suffix,
  return the original base; with suffixes, retain the existing nested qualified `Ref` structure.
  Preserve the plain-reference alternative order and its annotation ownership.
- Parse parenthesized contents once, distinguishing a group from a tuple using the following
  comma or closer. Preserve the empty form's existing result and error behavior.
- Share the parenthesis and selection structure between ordinary and member-body parsing,
  supplying their existing expression parsers so their sequencing rules stay distinct.
- Preserve qualifier and final-field annotations, delimiter ownership, successful source
  endpoints, and recovery progress. Keep nested cuts contained where the enclosing grammar
  still has a legitimate alternative.

An optional selection suffix alone leaves tuple re-parsing. Adding cuts without factoring
the shared prefixes risks rejecting grouped lambdas and tuples. A parser-wide memoization
layer is unnecessary for this bounded grammar repair. B16 stack safety and semantic expression
rewriting remain outside scope.

### 3. Verify the repaired behavior and hand off

Show the work-bound regressions fail on the unchanged parser and pass after the repair.
Repeat the recorded 15-input measurement with the same commands; then extend depths under
a process deadline to check scaling beyond the original safe bounds. Verify arithmetic
precedence and grouping through existing semantic/runtime helpers, including a 16-term sum
of 136. Check malformed-input diagnostics and the existing parser recovery suites.

Update the parser architecture's commitment/selection description. Run formatting, linting,
smoke tests, the full suite, local publication, and benchmark builds under the compiler rules.
Apply the memory harness if the final change affects lambda behavior; no ABI change is planned.
Complete the required QA and independent code review before implementation signoff. Stop for
review before closing the task or moving to B16.

## Plan and signoff

- Task creation: authorized.
- Bounded reproduction and repair planning: approved.
- Implementation plan and repair approval: granted for the focused repair plan above.
- Workstream signoff: granted.
- Tracked item completion: complete.
- Commit authorization: granted for the parser repair, regressions, documentation, and bookkeeping.
- Delivery authorization: granted for squash shipping into the configured parent branch.

## Task Working Memory

The parser shares parenthesized contents and optional selection suffixes while preserving
plain-reference alternative ordering and annotation ownership. Regression coverage includes
ordinary/member expressions, tuples, selected groups, duplicate annotations, a bounded 32-term
parse, and O0/O3 sanitized arithmetic execution. Source-span and recovery comparisons match
the baseline across all 68 compatibility cases; measured work grows linearly in the recorded
arithmetic and tuple families.

Compiler checks pass, including the full suite, smoke retry, publication, benchmark builds,
and all 52 memory fixtures. Primary review and all four dedicated checks are complete; the
confirmed operator-comment finding is corrected and its narrow review is clean. Author signoff
is complete. B23 has no remaining work; B16 is first in the open bug-note order.
