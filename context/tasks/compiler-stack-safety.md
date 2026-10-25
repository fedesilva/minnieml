# Prevent compiler stack overflows on long expression sequences

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-22
- **Target Branch:** unassigned
- **External Reference (optional):** none

## Execution Checklist

1. [ ] **planned** — Establish durable depth regressions and approve a bounded implementation plan.
2. [ ] **planned** — Make parsing and shared AST collection/indexing safe for long sequences.
3. [ ] **planned** — Address remaining semantic and pre-emission traversal failures.
4. [ ] **planned** — Verify compilation, semantic preservation, compiler gates, and independent review.

## Tags

- improvement
- correctness
- reliability

## Problem

A valid function containing 1,000 sequential local `let` bindings crashes the compiler
with `java.lang.StackOverflowError` during parsing. Local bindings form nested lambda
applications, and parsing each continuation retains several JVM frames per binding.
Independent phase probes also expose stack overflows beyond the parser.

The exposure is broader than one parser rule. Several analyses recurse through expression,
application, and lambda nodes, retaining work after each descent. Collection `map`,
`flatMap`, and `foldLeft` calls do not make recursion inside their callbacks stack-safe.
Tail-recursive fixed-point loops likewise do not protect the tree walks inside each iteration.

## Outcome

The 1,000-binding reproducer compiles with ordinary JVM stack settings. Depth regressions
exercise later phases independently so an early failure cannot conceal another unsafe walk.
Evaluation order, lexical scope, inferred types, captures, and ownership behavior are preserved.

## Scope

- Cover parsing, shared AST queries/indexing, semantic analyses, and pre-emission analyses.
- Retain the existing lambda-based AST representation and language semantics.
- Check deferred computation construction as well as execution in Cats `State`-based passes.
- Keep unrelated alias handling, AST printing layout, and generated-program stack usage outside
  this task.

## Plan (Approval Gate)

- [ ] Define bounded source and phase-level depth cases with explicit JVM stack settings.
- [ ] Choose stack-safe traversal strategies for parsing, collection, and rewriting while
  preserving traversal order and accumulated diagnostics.
- [ ] Split the remaining phase work into reviewable stages based on confirmed failures.
- [ ] Approve the implementation and verification plan before compiler changes.

Approval: implementation pending.

## Verification

### Published compiler reproduction

Confirmed on 2026-10-22 with published build
`0.0.4-SNAPSHOT (2026-10-23T02:10:08.220357Z-ef9fdc3)` on macOS arm64.
Ordinary compilation of a 1,000-binding function exits with status 1 and
`java.lang.StackOverflowError` in the parser. The JVM default thread stack is 2 MiB.
Representative frames repeat `letExprP`, `exprP`, `exprNoSeqP`, `exprFromTermsP`, and `termP`.

Reproduce from the repository root with the
[1,000-binding sample](../../mml/samples/stack-depth-1000-lets.mml):

```sh
mmlc mml/samples/stack-depth-1000-lets.mml
```

The program forwards zero through all bindings and returns zero. Successful compilation
and execution are the intended behavior; a JVM stack overflow is not a language diagnostic.

### Isolated phase observations

Published-build probes ran each phase on a fresh thread with `-Xss2m`. A failed prerequisite
was retried on a 128 MiB stack solely to prepare valid input for subsequent phases.
Fixtures used sequential bindings with either previous-binding references or constant-zero
initializers. Observed failures include:

| Binding count | Phase reporting `StackOverflowError` |
|---:|---|
| 500 | Parser |
| 1,000 | TypeResolver, RefResolver, ClosureMemoryFnGenerator, LocalCallableAnalysis |
| 2,000 | ParsingErrorChecker, ResolvablesIndexer, Simplifier, TypeChecker, PartialApplicationElaborator, OwnershipAnalyzer |

These are observed sizes, not exact failure thresholds; JVM compilation state and stack size
affect the limit. The 2,000-binding ownership retry exceeded a 45-second bound, so subsequent
phases were not measured at that depth. Passing smaller probes does not establish stack safety.
Durable automated phase regressions and post-repair verification remain pending.

## Risks / Notes

- [Parser expressions](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala):
  `letExprP` parses the continuation recursively before constructing the scoped binding.
- [TermTraversal](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TermTraversal.scala):
  `collect` recursively calls itself inside `flatMap`, then combines results. Its consumers
  include parsing-error checks, simplification checks, callable/ownership helpers, closure
  destruction, destruction validation, and closure layout analysis.
- [ResolvablesIndexer](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ResolvablesIndexer.scala):
  parameter collection recurses through lambda bodies and concatenates lists. Index refresh
  and `BindingIdSupply.include` expose this path across multiple phases.
- Duplicate-name checks, capture analysis, type checking, ownership rewriting, tail-recursion
  detection, and callable analyses contain ordinary recursive visitors. Inspection establishes
  exposure; the table above distinguishes phases with reproduced failures.
- IdAssigner, ExpressionRewriter, and PartialApplicationElaborator defer lambda descent through
  Cats `State`, but still invoke unsafe helpers. In
  [ClosureMemoryFnGenerator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ClosureMemoryFnGenerator.scala),
  closure tagging calls `rewriteExpr(lambda.body)` before attaching `flatMap`, so a `State`
  return type alone does not protect construction of the recursive computation.
- Long generated functions are a plausible trigger. This is deferred compiler robustness work;
  increasing the JVM stack only moves the failure threshold.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The parser crash and the phase failures above are confirmed. Implementation approval is
pending. Next: establish durable depth regressions, distinguish remaining traversal failures
from runtime-cost limits, and prepare bounded implementation stages. The task remains deferred
at MID priority.
