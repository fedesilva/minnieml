# Diagnostics evidence collection optimization

## Metadata

- **Owner:** Author
- **Status:** design
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-10-25
- **Target Branch:** unassigned
- **External Reference:** none

## Execution Checklist

1. [ ] **design** — Define how checked results carry reusable cause and availability evidence.
2. [ ] **planned** — Approve and implement bounded type-checker evidence-flow changes.
3. [ ] **design** — Select a parser-error collection design that avoids rebuilding valid trees.
4. [ ] **planned** — Implement the selected parser-error collection design.
5. [ ] **planned** — Verify diagnostic contracts, allocation, phase timings, and compiler behavior.

## Tags

- diagnostics
- performance
- type-checking
- parser-recovery

## Problem

Valid programs pay for collecting diagnostic evidence even when no diagnostic is emitted.
Two costs have different causes:

- `ParsingErrorChecker` traverses and copies the parsed tree through Cats `State`, including
  expressions and type annotations, even when its output is structurally unchanged.
- `TypeChecker` repeatedly derives cause and availability evidence from already-checked
  subtrees. Reusing module summaries still requires traversing definitions to prove that
  their evidence is unchanged.

The error-recovery implementation delivered in `9d4f4740` removed a large whole-module
reconstruction regression. The measurements below identify
remaining costs; they do not indicate that diagnostic identity or recovery should be removed.

## Outcome

Preserve diagnostic behavior while reducing repeated traversal and allocation on valid input.
Checked results should carry enough evidence for their consumers to avoid rediscovering it.
Parser-error collection should avoid reconstructing a valid tree solely to establish that
there are no recovered errors.

Success requires lower measured phase cost and allocation, with unchanged diagnostics,
recovered structure, source spans, editor behavior, and the error gate before code generation.
No numerical speed target is approved.

## Scope

- Type-checker result evidence, cause queries, availability queries, and binding-summary updates.
- Parser-error discovery, registration, and attachment of diagnostic IDs after parsing.
- Focused measurement support and regressions needed to establish the affected contracts.
- Exclude grammar changes, blanket diagnostic suppression, a persistent compiler graph,
  general AST redesign, and unrelated ownership or alias-resolution repairs.
- Always-on parser instrumentation is a separate observation, not part of this optimization.

## Plan (Approval Gate)

### 1. Carry evidence with checked results

Extend the result contract around `Checked` so it distinguishes complete healthy evidence,
known failures, and incomplete evidence. An empty root vector alone must not mean both
"checked and healthy" and "query the subtree again".

Compute and combine evidence while checking children. Preserve the distinction between
attribution, proof that a diagnostic is consequential, and availability for later analysis.
Avoid replacing those meanings with one Boolean or treating missing types as healthy.

Use the resulting evidence to update binding summaries incrementally. Define what invalidates
an existing summary when types, bindings, scopes, or semantic indexes change. Preserve
conservative rediscovery where completeness cannot be established. Do not introduce a cache
keyed only by a binding ID across changing compiler states.

First investigate the immediate-lambda return path and per-binding summary updates identified
under Verification. Separate improvements to evidence flow from unrelated collection tuning.

### 2. Select a parser-error collection design

Compare these approaches before implementation:

- A lightweight discovery pass, followed by stamping only affected paths, preserving unchanged
  nodes and avoiding `State` reconstruction throughout a valid tree.
- Parser results that retain successfully recovered error occurrences, with diagnostic IDs
  assigned after parsing succeeds. Any retained occurrence collection must respect abandoned
  alternatives and backtracking; parser speculation must not allocate authoritative IDs.

A preliminary discovery pass adds another traversal on malformed input. Retaining occurrences
in parser results changes more parser interfaces and needs careful recovery semantics. Select
based on measured cost, contract simplicity, and maintenance burden.

An empty diagnostic store cannot justify skipping collection: this phase populates that store.
Preserve deterministic occurrence order, shared parsed-type occurrence identity, malformed
annotation coverage, raw error retention, and CLI/LSP presentation rules.

### 3. Verify the bounded changes

- Pin cause identities, complete/incomplete evidence, independent versus consequential errors,
  valid siblings, forward/recursive references, nested local bindings, and missing types.
- Check recovered term and type errors, mirrored annotations, discarded parser alternatives,
  source spans, and editor queries. Valid AST results must retain their intended structure.
- Repeat fresh-process CLI timings and isolated warmed measurements separately. Measure
  allocation as well as time; use representative valid and malformed programs beyond A*.
- Add a nested-binding scaling experiment to test the suspected repeated-subtree growth.
  The existing profile shows repeated traversal, not an empirical complexity bound.
- Run applicable compiler gates and independent review before handoff. No performance-only
  result substitutes for semantic, native, sanitizer, or recovery verification.

Approval: pending for design selection and implementation. Profiling evidence is research,
not implementation approval.

## Verification

### Measurement identities and method — 2026-10-25

- Baseline: `87e899c16345376d6f2fd37a726771a8b65fb6cb`.
- Measured checkout: `3ba9d214722633ec765d66405ed74804d702535c`; compiler sources match the
  delivered parser/diagnostic implementation in `9d4f4740`.
- Input: `mml/samples/astar3_animated.mml` taken from baseline revision `87e899c1`, not the
  edited working copy. SHA-256:
  `d6e824601ef8e4a82b23c7ac55af087b10244a6338ff925f268313ea5cc8e457`.
- Environment: arm64 macOS, GraalVM Community Java 25.0.4.1. Both assemblies were built with
  `sbtn 'mmlc / assembly'`. Isolated harness processes used `-Xmx1g` and
  `--sun-misc-unsafe-memory-access=allow`.
- Assembly SHA-256, baseline:
  `0026991f9a6e7aa0b00d3c4beeacdd527461a5f77dcc602dba3ec8a4de533816`.
- Assembly SHA-256, measured implementation:
  `f29ef3fff1d004c9c1d5e0fef02d8725764dd8f10d310b9b3f71d7e1b98cc43a`.

An external Java harness called the assemblies' existing APIs without changing compiler code.
For parser-error lifting it parsed once with `Parser.parseModuleWithInfo`, created an empty
`CompilerState`, and repeatedly called `ParsingErrorChecker.checkModule` on that same input.
The output had no errors and was structurally equal to the input module; it had 108 members.

For type checking it prepared the exact `SemanticStage` prefix through capture analysis:
parser-error collection, standard-type/operator/function injection, duplicate checking, ID
assignment, type resolution, constructor and memory-function generation, reference resolution,
expression rewriting, simplification, and capture analysis. Index rebuilding followed the
same phase boundaries as `SemanticStage`. The prepared input had 260 members and no errors.
The harness repeatedly called `TypeChecker.rewriteModule` on this input, consumed results
through a volatile reference, and checked that the final output had no errors. Index rebuilding
was measured separately on the prepared input; it is excluded from isolated type-checker time.

Each of three fresh JVMs per compiler/phase measured its first invocation, then ran 100 warmup
calls and seven batches of 100 calls. A fork's warmed value is the median of its last three
batch means; the summary is the median across forks. Allocated bytes come from the main
thread's `ThreadMXBean` allocation counter, not retained heap size or JFR allocation estimates.
The retained repeated measurements ran sequentially. Earlier exploratory runs are excluded
from these tables.

### Isolated results

| Phase | Baseline first call | Current first call | Baseline warmed | Current warmed | Baseline bytes/call | Current bytes/call |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Parser-error lifting | 4.204 ms | 67.943 ms | 0.019205 ms | 1.363605 ms | 37,557 | 13,519,409 |
| Type checking | 40.621 ms | 125.550 ms | 1.252433 ms | 9.771860 ms | 3,980,857 | 42,973,503 |
| Index rebuilding | 0.529 ms | 0.502 ms | 0.080948 ms | 0.084619 ms | 428,793 | 427,833 |

Per-fork warmed means aggregated as described above:

| Phase / compiler | Fork 1 ms | Fork 2 ms | Fork 3 ms |
| --- | ---: | ---: | ---: |
| Lifting / baseline | 0.023316 | 0.018311 | 0.019205 |
| Lifting / current | 1.363605 | 1.409092 | 1.361341 |
| Type checking / baseline | 1.248581 | 1.252433 | 1.259094 |
| Type checking / current | 10.015385 | 9.771860 | 9.743882 |
| Index / baseline | 0.075815 | 0.080948 | 0.082415 |
| Index / current | 0.076736 | 0.090892 | 0.084619 |

Cold execution magnifies both costs. These measurements do not separately attribute class
loading, interpreter execution, and JIT compilation. Warmed type checking remains substantially
more expensive, so JVM startup alone does not explain the regression.

### CPU and allocation profiles

Final profiles ran sequentially in separate JVMs after the harness warmup and measurement
batches. Each recorded 15 seconds with JFR execution sampling at 1 ms, stack depth 256,
allocation sampling at 1,000 events/second, and GC events. Only main-thread execution samples
are counted below. Profiling-loop throughput is not used as an unprofiled timing result.

- **Parser-error lifting:** 10,841 invocations; 11,554 execution samples. About 80.7% of
  sampled leaf locations were in Cats classes, particularly `Eval` and `AndThen` machinery.
  Allocation samples show evaluation wrappers, closures, tuples, and traversal objects in
  addition to AST copies. This supports a traversal/allocation cost, not error emission cost.
- **Type checking:** 1,503 invocations; 11,561 execution samples. `DiagnosticCauses` appeared
  in 5,935 stacks (51.3%); `ValueAvailability` in 1,410 (12.2%). These are inclusive stack
  counts, not independent additive phase timings or percentages of the full compiler.
- Cause queries originating at `checkImmediateApplication`'s body-root lookup accounted for
  2,147 samples (18.6% of the type-checker profile). The body has already been checked before
  this query recursively examines it again.
- Cause queries through `ModuleCheckingState.withChecked` accounted for 1,420 samples
  (12.3%). `DiagnosticCauses.updatedModule` enumerates old/new definitions and rechecks their
  evidence even when it can reuse the existing summary.

Relevant source:

- [ParsingErrorChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/ParsingErrorChecker.scala):
  `checkModule`, `stampTypes`, `stampExpr`, and `stampTerm` traverse and copy valid syntax.
- [TypeChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala):
  `checkImmediateApplication`, `resultRoots`, `validateApplication`, and `withChecked`.
- [DiagnosticCauses](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DiagnosticCauses.scala):
  `evidenceOf`, `annotations`, `definitions`, and `updatedModule`.
- [ValueAvailability](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ValueAvailability.scala):
  recursive `isAvailable` and per-binding `updatedModule`.

### Fresh-process CLI cross-check

Five alternating baseline/current pairs, with one excluded priming invocation each, compiled
that same input using `-mpPaI` and separate warmed output directories. All runs succeeded.
Median parser-error lifting rose from 4.46 to 72.18 ms; type checking from 36.88 to 131.74 ms.
Ownership fell from 427.56 to 328.03 ms. Actual process elapsed time was 2,003.23 versus
2,021.55 ms; summed compiler phase time was 1,604.90 versus 1,612.15 ms.

Other improvements offset the diagnostic-related costs on this input. The phase differences
are not a controlled measurement of diagnostic propagation alone. The CLI always calls the
instrumented parser; omitting `-P` suppresses counter printing, not collection.

## Risks / Notes

- Measurements cover one valid program on one host. They do not establish malformed-program
  performance, cross-platform behavior, or an asymptotic complexity bound.
- Nested local bindings produce overlapping bodies; repeated evidence queries suggest poor
  scaling, but a controlled scaling experiment is still required.
- Availability is not equivalent to having no known diagnostic roots. Unresolved, recursive,
  or partially typed values need conservative treatment.
- Preserve post-parse diagnostic ID assignment, occurrence identity, deterministic ordering,
  independent errors, and the distinction between raw and visible diagnostics.
- The embedded methods and numerical results are the durable evidence summary. Raw JFR
  recordings and the exploratory harness are not included; exact replay tooling must be
  added under an approved measurement plan before implementation comparisons.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Implementation commit authorization: not granted.

## Task Working Memory

Design selection is open. Profiling identifies repeated evidence derivation in type checking
and unconditional reconstruction in parser-error collection. Review the checked-result
contract first, then select the parser-error collection approach. Implementation approval
is pending; this task is not selected in the active tracker.
