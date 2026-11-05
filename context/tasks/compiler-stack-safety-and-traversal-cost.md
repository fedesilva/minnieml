# Make long local sequences stack-safe and bound traversal cost

## Metadata

- **Owner:** The Author
- **Status:** deferred
- **Kind:** BUG
- **Priority:** MID
- **Impact:** VERY HIGH — intrusive changes across compiler phases, with potentially disruptive
  effects on scope, diagnostics, ownership, and emission.
- **Created:** 2026-11-04
- **Target Branch:** unassigned
- **External Reference:** none

## Execution Checklist

1. [ ] **deferred** — Establish shared functional collection and indexing tools.
2. [ ] **deferred** — Make parser sequencing independent of sequence length.
3. [ ] **deferred** — Repair early semantic traversal, one phase at a time.
4. [ ] **deferred** — Bound availability, diagnostic-evidence, and callable-value queries.
5. [ ] **deferred** — Repair closure generation and ownership traversal in separate slices.
6. [ ] **deferred** — Repair remaining late analyses and LLVM emission.
7. [ ] **deferred** — Verify the complete compiler and obtain final review and signoff.

## Tags

- correctness
- stack-safety
- compiler-performance

## Problem

Valid long functions crash the compiler with `StackOverflowError`. Local `let` and `fn`
bindings lower to immediate lambda applications, so a flat source sequence produces a deeply
nested expression tree. Several parsers, visitors, queries, and emitters retain JVM frames
while descending through that tree. Returning Cats `State`, or placing recursion inside a
collection fold, does not by itself make construction of the computation stack-safe.

There is also excessive repeated traversal. Ownership processing of 1,000 constant bindings
takes about 18 seconds in the measured build. Sampling identifies `ValueAvailability` as the
dominant work. This helper belongs to semantic error recovery: it decides whether expressions
remain usable after errors. Ownership and callable analysis invoke it on clean programs too.

MID priority reflects a tolerable short-term limitation. VERY HIGH impact describes the repair's
breadth and disruption risk, independently of scheduling priority. The task is deferred.

## Outcome

The existing [1,000-binding program](../../mml/samples/stack-depth-1000-lets.mml) compiles and
executes with ordinary JVM stack settings. Long mixed sequences of bindings, statements, and
local functions use bounded JVM stack in the compiler. Traversal cost on these sequences is
practical, with the measured repeated availability work addressed.

Evaluation order, lexical scope, binding identity, inferred types, diagnostic causes and order,
captures, ownership transfers, cleanup placement, and emitted behavior are preserved.

## Scope

- Parsing, shared AST collection/indexing, semantic analysis and recovery queries, closure and
  ownership processing, late callable analyses, and LLVM expression emission.
- Preserve the public lambda/application AST and language semantics.
- Include reference-linked initializers as well as constants; the former exercise value flow
  in addition to structural expression depth.
- Check structural equality and hashing where phase stabilization or callable maps depend on
  deep AST nodes. Change them when the scoped fixtures establish a failure or material cost.
- Keep unrelated alias semantics, generated-program call-stack use, AST printing layout, and
  a general guarantee for arbitrarily nested grammar constructs outside this task.
- Preserve the existing bug reproduction and research evidence below. Focused regression tests
  accompany each repair; a separate preliminary test-framework workstream is unnecessary.

## Plan (Approval Gate)

This is a draft high-level plan. Implementation approval is pending. Refine and approve a
bounded slice when the task resumes; obtain review and signoff before advancing to the next
slice. A stage may contain several independently reviewed compiler phases.

The recorded research is sufficient to begin an approved repair stage. Repeating the survey is
optional; decide whether a fresh measurement would help when selecting that stage.

### Functional approach and reuse at every stage

- Prefer tail calls and `@tailrec` wherever possible. Use immutable work lists and accumulators
  when tree traversal needs an explicit stack. Preserve state through values and returned copies.
- This task does not authorize `while`, `var`, mutable compiler stacks, or hidden mutable context.
- When rebuilding requires work after a child returns, represent that work explicitly with
  immutable frames or defer it through existing Cats facilities. Tail recursion cannot simply
  discard scope-exit, diagnostic, or cleanup work.
- Cats `State` uses `Eval`; Cats also provides `Defer` for the relevant state transformer.
  Defer recursive computation construction at the entry points covering each recursive cycle.
  Attaching `map` after an eager recursive call is too late.
- Every phase slice must inspect the tools established by earlier slices. Reuse or extend them
  when the operation and invariants match. Record why a phase-specific traversal remains needed
  when scope, ordering, or stopping rules differ.
- Extract common tools from concrete phase needs. Keep phase policy visible: a universal visitor
  that silently imposes one scope or evaluation order would be dangerous. The aim is a small
  shared vocabulary for collection, deferred descent, and reconstruction.
- At each handoff, state the shared tools established, the next consumers, and the preserved
  invariants. Add focused tests with the repair, using existing test support where it fits.

### 1. Shared collection and indexing

Make `TermTraversal.collect` tail-recursive using an immutable pending-node list and accumulator,
preserving root-first traversal and existing child order. Adapt `ResolvablesIndexer` to shared
structural traversal where possible, retaining parameter/environment definitions, order, and
the distinction between a definition list and a deduplicated index.

Recheck consumers before assigning bespoke repairs: index refresh and `BindingIdSupply.include`
run across many phases. This stage establishes the first reusable tools and includes focused
collection/indexing checks. It is not a prerequisite campaign to prove the known bug again.

### 2. Parser sequencing

Use parser-private pending frames for local bindings and statements, then lower them with a
tail-recursive loop or a stack-safe fold into the existing immediate-lambda AST. Cover `let`,
local `fn`, and ordinary statements together.

Factor binding prefix parsing from continuation construction in `expressions.scala`; adapt
expression-frame assembly in `bindingRecovery.scala`. Preserve local bindings appearing after
other terms in one expression, ordinary/member parsing policy, required versus optional frames,
annotations, source spans, recovery progress, and separate function terminators.

Sequence lookahead must inspect a bounded next segment rather than repeatedly parsing the entire
remaining sequence. Reuse existing lowering helpers. Check mixed sequences and parser work counts
alongside the existing recovery and source-span controls.

### 3. Early semantic phases

Work through reference resolution and expression rewriting as separate slices, using shared
deferred descent while retaining their `State` interfaces. Cover expression, callee, lambda,
and qualified-reference recursion cycles.

Then assess duplicate-name checking, ID assignment, type resolution, simplification, and capture
analysis against the shared tools and measured failures. Avoid rewriting a phase solely because
an older probe attributed a failure to it. Scope-aware collectors may require immutable scope
frames; capture discovery must preserve its distinct value-lambda stopping rules.

### 4. Semantic facts and repeated queries

Address `ValueAvailability`, `DiagnosticCauses`, and `CallableValues` in bounded slices, followed
by affected type-checker and partial-application paths. The availability hotspot is a measured
priority within this stage.

Determine which facts can be computed once and carried with checked results, instead of scanning
each remaining continuation again. Preserve complete versus incomplete diagnostic evidence,
invalid-value behavior, forwarding cycles, and unknown value-flow alternatives. Cause evidence
depends on binding context; AST-identity-only memoization is not a sufficient design.

Keep the solution shared among consumers rather than adding a local ownership-only bypass.
Measure the constant and reference-linked cases after each change. Set practical acceptance
bounds from those measurements; the present timing samples are not benchmark thresholds.

### 5. Closures and ownership

Repair closure collection and deferred tagging first, reusing collection/reconstruction tools
while preserving environment-name order, exact lambda association, and generated IDs.

Give ownership rewriting its own slice. Represent pending scope-exit work explicitly so that
initializer analysis, scope extension, continuation analysis, result transfer, cleanup placement,
and restoration of outer bindings retain their order. Include cleanup-rewriting helpers as well
as the main visitor. The measured `CallableValues` failure and availability cost do not prove
that the main ownership visitor is the only remaining problem.

Check shallow controls for captures, transfer witnesses, shadowing, escape diagnostics, branch
results, and observable cleanup order alongside long-sequence regressions.

### 6. Late analyses and LLVM emission

Assess tail-recursion detection, destruction validation, callable-target analysis, local-callable
analysis, and closure layout independently after earlier repairs. Reuse shared collection for
structural queries; carry lexical context explicitly for callable analyses.

Repair expression emission's immediate-lambda continuation path and ownership-result bindings.
Preserve initializer order, scope entries, and conditional exit-block propagation. Test multiple
deep callable bodies to exercise AST-key hashing/equality as well as traversal.

The 1,000-binding body-emitter overflow is reproduced. Full module emission, LLVM validation,
and native execution remain separate acceptance steps.

### 7. Complete verification and signoff

Run the 1,000-binding source through complete compilation and execution without larger-stack
fallbacks. Use focused deeper and mixed-sequence checks to cover repaired paths, with explicit
stack settings and bounded execution where useful. Check semantic preservation and malformed
source at shallow and deep positions; avoid recursive test assertions becoming the failure.

Run the applicable formatting, linting, smoke, full-suite, publication, benchmark, and memory
checks from [coding rules](../coding-rules.md). Follow local post-chores for compiler handoffs
and independent review. Apply target/ABI gates if the actual changes reach those boundaries.

## Impact Survey

Planning allowance: approximately 18–25 production files, several affected test suites, and
6–8 reviewable slices. This is a static scope estimate, not a commitment to rewrite every file.
Shared fixes and current phase evidence determine the final set.

| Area | Source evidence and expected impact |
|---|---|
| Parser | `letExprP` and `innerFnExprP` parse the continuation recursively; statement repetition alone does not remove that recursion. Primary files: [expressions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala), [bindingRecovery.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/bindingRecovery.scala). |
| Shared collection | [TermTraversal](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TermTraversal.scala) recurses inside `flatMap`. [ResolvablesIndexer](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ResolvablesIndexer.scala) has another recursive collector; [BindingIds](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/BindingIds.scala) invokes it when including existing definitions. |
| Early rewriting | [RefResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/RefResolver.scala) and [ExpressionRewriter](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ExpressionRewriter.scala) eagerly call body rewriting before attaching `map`. Returning `State` does not defer those calls. |
| Scope and captures | [DuplicateNameChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DuplicateNameChecker.scala) retains scope work after descent. [CaptureAnalyzer](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CaptureAnalyzer.scala) has distinct rewriting, direct-reference, and nested-capture walks. These are static exposures; the measured scalar cases pass. |
| Existing deferral | [IdAssigner](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/IdAssigner.scala) and [TypeResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeResolver.scala) defer let-body work behind state effects. [Simplifier](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/Simplifier.scala) returns lambdas unchanged. Shared-helper repairs may suffice for the scoped input. |
| Type checking and recovery | [TypeChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala) defers its main local-binding body check, but calls availability and cause queries on rebuilt continuations. [ValueAvailability](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ValueAvailability.scala) and [DiagnosticCauses](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/DiagnosticCauses.scala) descend through those continuations. Parameter inference and protocol-use collection also need consideration. |
| PAP stabilization | [PartialApplicationElaborator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/PartialApplicationElaborator.scala) defers body construction, but invokes callable/capture/index helpers and structural equality of rebuilt members. A tail-recursive fixed-point loop does not protect these operations. |
| Closure generation | [ClosureMemoryFnGenerator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ClosureMemoryFnGenerator.scala) has a recursive collector and eager `rewriteExpr(lambda.body).flatMap(...)` construction. Both need examination. |
| Ownership | [CallableValues](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CallableValues.scala) follows reference chains recursively. [OwnershipAnalyzer](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala) retains cleanup and scope-restoration work after body analysis; cleanup placement has further eager recursion. Highest semantic risk. |
| Late analyses | [TailRecursionDetector](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TailRecursionDetector.scala), [CallableTargetAnalysis](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/CallableTargetAnalysis.scala), and [LocalCallableAnalysis](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/LocalCallableAnalysis.scala) contain private recursive walks. Callable-target maps also use entire lambdas as keys. The current 1,000-binding scalar probes pass these analyses. |
| Shared late consumers | Closure destructor generation, destruction validation, and closure layout use shared term collection, so a shared fix may cover their expression-depth exposure. |
| LLVM emission | [Applications.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Applications.scala) calls body emission and then restores exit-block information. [OwnershipResults](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/OwnershipResults.scala) has a similar body path. |

### Invariants that prevent one universal traversal policy

| Consumer | Required behavior |
|---|---|
| Reference resolution | Resolve continuation before initializer; retain the local name's recursive initializer visibility. |
| Type checking | Check initializer, infer the parameter, then check continuation in the extended context. |
| Capture analysis | Initializer uses outer locals; continuation uses extended locals; value lambdas form capture boundaries. |
| ID allocation | Preserve allocation order, existing identities, and scope ownership paths. |
| Diagnostics | Preserve emission order, cause IDs, valid siblings, invalid-node payloads, and source spans. |
| Ownership | Preserve transfers, pending cleanup, scope restoration, and branch behavior. |
| Emission | Preserve single evaluation, callable scope, and conditional exit blocks. |

### Additional design risks

- `BindingOwner.scope` embeds the full enclosing scope path in each successive ID. Total ID text
  grows quadratically with chain depth. This is a source-derived scaling concern; the survey
  does not establish it as the dominant cost or require an identity redesign.
- Deep case-class equality, hashing, pretty-printing, and recursive test collectors can fail
  independently of the compiler visitor. The scalar probes do not settle multi-lambda map keys.
- The current `ParsingErrorChecker` has its own stateful stamping traversal; fixing
  `TermTraversal.collect` alone cannot be credited with fixing all ingest work.
- Preserving recovery evidence can require more than preserving known root IDs. Complete and
  incomplete evidence have distinct meanings.
- Stack thresholds depend on JVM compilation state and stack configuration. A passing bounded
  probe does not establish arbitrary-depth safety.

### Parser details to retain when refining stage 2

Pending frames must retain terms preceding a local binding in the same expression. A form such
as `f let x = 1; x` must keep the binding at its existing term position. A simple list of binding
headers alone is insufficient. Local functions retain their body and declaration terminators,
callable annotations, and malformed-header behavior.

Do not turn `let`/`fn` into global recovery stops: they are valid body terms, so a missing
terminator before one is ambiguous. A clean pause before a valid prefix must not manufacture
an empty-expression error. A terminal required empty frame must still report its error.
Member-position local bindings switch their continuation to ordinary expression parsing;
preserve that policy. Bound lookahead under `NoCut` without losing recoverable malformed starts.

Stage-local controls should cover consecutive and mixed lets/functions/statements, bindings
after application or operator terms, groups/tuples/conditions, typed and untyped local functions,
shadowing and recursion, missing punctuation, malformed names and annotations, exact failed
slices, source endpoints, and retained subsequent members. Paused recovery fragments and the
`withTypeAsc` finishing step remain design details to settle with a bounded implementation.

## Verification

The defect and traversal cost are established by the
[measurement appendix](#appendix-measurements-and-research-evidence). Repeating the survey is
optional. Repair verification remains pending and belongs with each implementation stage.

## Signoff

- Task definition and scheduling: BUG, MID priority, VERY HIGH impact, deferred.
- Implementation plan: draft; approval pending for each bounded stage.
- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The crash and repeated availability cost are established. Shared functional tools and phase-local
conversions are required; the task grants no permission for imperative traversal. Tests belong
with repairs. There is no separate upfront depth-test workstream.

When resumed, refine stage 1's shared collection/indexing design and select its first bounded
implementation slice, deciding whether any measurement needs repeating. Reuse review is mandatory
at each subsequent phase boundary. Preserve the distinction between measured failures, static
exposures, and historical observations below.

## Appendix: Measurements and research evidence

**Stack baseline:** `-Xss2m` (2 MiB per thread). The 128 MiB retries only prepared inputs for
later phases; they did not count as passing results.

The following evidence establishes the defect and its impact. It does not establish a repair.
All necessary results and probe source are embedded below; no machine-local files are required
to understand this record.

### Using these measurements later

Small standalone programs generated long functions and exercised the unchanged compiler one
phase at a time. Each phase received the output of its prerequisites. If a prerequisite overflowed,
it was retried with a larger stack so the next phase could be examined. Those retries allowed
the investigation to continue; they did not count as successful stack-safety checks.

The results establish the crashes and identify expensive work. They are enough to proceed with
the repair. Rerun a measurement if changes to the compiler or JVM make it useful, or to compare
the cost before and after a specific fix. There is no requirement to repeat the whole survey
before starting implementation.

The environment, inputs, stack settings, deadlines, and limitations are recorded below. The
[operation transcripts](#complete-operation-results) preserve the observed outcomes, and the
[embedded programs and commands](#research-programs-and-invocation) describe how to repeat them.

### Current-build environment and method

- Date: 2026-11-04; macOS arm64.
- Commit: `bbd3c23657a87bbcd95608bec72158c294fffb3c`; repository source unchanged during probes.
- Scala 3.10.0; GraalVM CE Java 25.0.4.1, runtime `25.3.4.1+1.1`.
- Build: `sbtn 'compile; show mmlclib / Compile / fullClasspath'` passed.
- Effective dependencies include Cats core/kernel 2.13.0 and FastParse 3.1.1.
- Phase programs ran as `java -Xss2m -Xmx2g -cp <probe-and-project-classpath> DepthProbe N FLAVOR`.
  Each operation used a fresh platform thread with stack-size argument zero, inheriting the
  JVM default requested by `-Xss2m`. On overflow only, the same input was retried on a thread
  requesting 128 MiB, solely to prepare valid input for following operations.
- Each attempt had a 20-second join deadline. Timeout terminated the child with exit 124.
  An outer 120-second process bound covered each unprofiled phase run.
- Fixtures: `pub fn main(): Int =` followed by N bindings and `x(N-1); ;`. `refs` initializes
  `x0 = 0`, then each `xi = x(i-1)`; `zero` initializes every binding to zero.
- Phase order and explicit index refreshes match `SemanticStage`. Shared collection was also
  probed independently on the parsed tree. The full source below specifies all operations.
- Timings include thread startup and JIT/GC effects; threads share a JVM. Stack requests are
  JVM-controlled. These are bounded observations, not exact thresholds or benchmark results.

### Current results

| Fixture | Result at requested 2 MiB stack |
|---|---|
| 100 refs | Every probed operation returned successfully; no semantic errors; body emission returned Right. |
| 500 refs | Parser overflow; all subsequent operations returned after larger-stack parser preparation. Ownership took 2,261 ms. |
| 1,000 refs | Parser, RefResolver, ExpressionRewriter, closure collection, ownership-stage CallableValues, and main-body emission overflowed. Ownership's larger-stack retry took 18,008 ms. |
| 1,000 zero | Parser, RefResolver, ExpressionRewriter, closure collection, and main-body emission overflowed. Ownership returned on the normal stack in 17,895 ms. |
| 2,000 zero | Parser, shared collection, RefResolver, ExpressionRewriter, TypeChecker, PAP elaboration, closure collection, and ownership processing overflowed. Ownership's larger-stack retry timed out at 20,005 ms; later operations were unmeasured. |
| 100 zero, validation control | All operations returned; pre-codegen result explicitly had zero errors and `canEmitCode=true`; body emission returned Right. |

TypeChecker and PAP failures at 2,000 include `TermTraversal.collect` frames. The 1,000-ref
ownership failure is in `CallableValues.resolveOrigins/resolve`; it does not isolate the main
ownership visitor. The 2,000-zero ownership failure includes `ValueAvailability.isAvailable`.
The emitter failure follows `compileExpr` → `compileApp` → `compileLambdaApp` → body `compileExpr`.

At 1,000, tail detection, callable-target analysis, local-callable analysis, and closure layouts
returned successfully for both initializer variants. At 2,000, parsing-error stamping, ID
assignment, type resolution, simplification, and explicit index refreshes reached before the
timeout returned successfully. Keep their static recursive exposure distinct from a reproduced
failure in this build.

### Parser shape controls

These ran in fresh JVMs with `-Xss2m -Xmx1g` and an outer 30-second deadline. Each function ends
with `0; ;`; the repeated source unit is shown below.

| Shape | Repeated unit | Count | Outcome |
|---|---|---:|---|
| Statements | `();` | 10 | Parsed; zero ingest diagnostics. |
| Local functions | `fn fi(): Int = 0;;` | 10 | Parsed; zero ingest diagnostics. |
| Mixed | `let xi = 0; (); fn fi(): Int = xi;; ();` | 10 | Parsed; zero ingest diagnostics. |
| Statements | `();` | 1,000 | Parsed; zero ingest diagnostics. |
| Local functions | `fn fi(): Int = 0;;` | 500 | Parser StackOverflowError. |
| Mixed | `let xi = 0; (); fn fi(): Int = xi;; ();` | 250 groups | Parser StackOverflowError. |

### JFR ownership sampling

Java Flight Recorder sampled a separate 1,000-zero run with `settings=profile` and
`dumponexit=true`. That instrumented ownership attempt reached its 20-second limit; use the
unprofiled run for the 17,895 ms observation. Of 1,792 total execution-sample events, 1,722
belonged to the thread named `ownership`; 1,690 had `ValueAvailability` as the top frame:
**98.1% of ownership-thread samples**. This is a sample proportion, not exact CPU accounting.

The dominant method is `isAvailable`, descending through the remaining scoped-lambda body.
Many stacks are truncated at 64 frames, so these data identify a hot helper without proving
complete call ancestry or a precise asymptotic complexity. Source inspection separately shows
repeated availability and cause queries on nested continuations. The constant fixture establishes
that reference forwarding alone does not explain the cost.

### Evidence limits

- Larger-stack retries are preparation, not stack-safety passes.
- The original phase runs ignored the returned pre-codegen validation state. Their `OK` means
  no exception. Only the separate 100-zero control explicitly checked its errors and emit flag.
- Body emission returned Right after preparation, with zero output instructions for these literal
  and alias chains. This does not establish the returned value, complete module IR validity,
  LLVM assembly, ABI behavior, or native execution.
- The survey did not run the full suite, smoke harness, benchmarks, or memory harness. They
  remain implementation/handoff gates, not research results.
- Each run emitted a JVM warning about `sun.misc.Unsafe.objectFieldOffset` from Scala
  `LazyVals`; repeated warning boilerplate is omitted from the operation transcripts below.
- The probes catch failures at a research process boundary. This is not a proposed compiler
  exception-handling design, production test framework, or relaxation of functional rules.


### Historical published-build observations

The following observations describe the published build identified below. They are historical
evidence, not current phase attribution. The current-build results above govern the draft scope.

#### Published compiler reproduction

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

#### Isolated phase observations

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

A frontend corpus scan also encountered a parser crash after 182 sources. A larger-stack retry
completed all 262 sources (224 clean, 38 with diagnostics). Its `-Xss16m` setting permitted that
corpus comparison; it does not establish stack safety.

### Complete operation results

Rows retain operation, requested stack mode, outcome, elapsed milliseconds, and captured compiler
frames. JVM warning boilerplate is described above. Process totals for the original sequence were
0.66 s (100 refs), 3.16 s (500 refs), 19.51 s (1,000 refs), 19.34 s (1,000 zero), and 23.30 s
(2,000 zero). The first four exited 0 after any preparation retries; the final run exited 124.

<details>
<summary>100 reference-linked bindings</summary>

```text
PROBE count=100 flavor=refs java=25.0.4.1 stack=2m timeoutPerAttempt=20s
parser	2m	OK	99
collect-parsed	2m	OK	3
parsing-errors	2m	OK	65
duplicate-names	2m	OK	12
id-assigner	2m	OK	22
type-resolver	2m	OK	13
index-after-type-resolver	2m	OK	0
ctor-gen	2m	OK	5
mem-fn-gen	2m	OK	1
clone-instances	2m	OK	2
protocol-checker	2m	OK	33
ref-resolver	2m	OK	14
index-after-ref-resolver	2m	OK	0
clone-bodies	2m	OK	1
op-coherence	2m	OK	11
expression-rewriter	2m	OK	10
index-after-expression-rewriter	2m	OK	0
simplifier	2m	OK	2
index-after-simplifier	2m	OK	0
capture-analyzer	2m	OK	1
index-after-capture-analyzer	2m	OK	0
type-checker	2m	OK	60
index-after-type-checker	2m	OK	0
pap-elaborator	2m	OK	27
closure-mem-gen	2m	OK	7
struct-destructor	2m	OK	2
index-after-struct-destructor	2m	OK	0
ownership	2m	OK	116
index-after-ownership	2m	OK	0
closure-destructor	2m	OK	3
index-after-closure-destructor	2m	OK	0
tailrec	2m	OK	0
index-after-tailrec	2m	OK	0
destruction-validation	2m	OK	1
pre-codegen	2m	OK	1
callable-targets	2m	OK	4
local-callables	2m	OK	2
closure-layouts	2m	OK	1
emit-main-body	2m	OK	4
DONE count=100 flavor=refs outputLines=0
```

</details>

<details>
<summary>500 reference-linked bindings</summary>

```text
PROBE count=500 flavor=refs java=25.0.4.1 stack=2m timeoutPerAttempt=20s
parser	2m	StackOverflowError	112	mml.mmlclib.parser.expressions$package$.letExprP:383 | mml.mmlclib.parser.expressions$package$.termP$$anonfun$1:210 | mml.mmlclib.parser.expressions$package$.withTypeAsc:188 | mml.mmlclib.parser.expressions$package$.termP:225 | mml.mmlclib.parser.expressions$package$.$anonfun$31:381 | mml.mmlclib.parser.bindingRecovery$package$.rec$3:137 | mml.mmlclib.parser.bindingRecovery$package$.bindingFramePartP:137 | mml.mmlclib.parser.bindingRecovery$package$.rec$2:119 | mml.mmlclib.parser.bindingRecovery$package$.bindingFrameP:119 | mml.mmlclib.parser.expressions$package$.letExprP:381
parser	prepare128m	OK	47
collect-parsed	2m	OK	15
parsing-errors	2m	OK	89
duplicate-names	2m	OK	13
id-assigner	2m	OK	34
type-resolver	2m	OK	29
index-after-type-resolver	2m	OK	2
ctor-gen	2m	OK	10
mem-fn-gen	2m	OK	1
clone-instances	2m	OK	5
protocol-checker	2m	OK	48
ref-resolver	2m	OK	18
index-after-ref-resolver	2m	OK	3
clone-bodies	2m	OK	2
op-coherence	2m	OK	13
expression-rewriter	2m	OK	17
index-after-expression-rewriter	2m	OK	1
simplifier	2m	OK	0
index-after-simplifier	2m	OK	2
capture-analyzer	2m	OK	2
index-after-capture-analyzer	2m	OK	1
type-checker	2m	OK	171
index-after-type-checker	2m	OK	0
pap-elaborator	2m	OK	64
closure-mem-gen	2m	OK	11
struct-destructor	2m	OK	2
index-after-struct-destructor	2m	OK	0
ownership	2m	OK	2261
index-after-ownership	2m	OK	0
closure-destructor	2m	OK	6
index-after-closure-destructor	2m	OK	0
tailrec	2m	OK	1
index-after-tailrec	2m	OK	0
destruction-validation	2m	OK	1
pre-codegen	2m	OK	1
callable-targets	2m	OK	4
local-callables	2m	OK	3
closure-layouts	2m	OK	2
emit-main-body	2m	OK	7
DONE count=500 flavor=refs outputLines=0
```

</details>

<details>
<summary>1,000 reference-linked bindings</summary>

```text
PROBE count=1000 flavor=refs java=25.0.4.1 stack=2m timeoutPerAttempt=20s
parser	2m	StackOverflowError	114	mml.mmlclib.parser.expressions$package$.innerFnExprP:420 | mml.mmlclib.parser.expressions$package$.termP$$anonfun$1:211 | mml.mmlclib.parser.expressions$package$.withTypeAsc:188 | mml.mmlclib.parser.expressions$package$.termP:225 | mml.mmlclib.parser.expressions$package$.$anonfun$31:381 | mml.mmlclib.parser.bindingRecovery$package$.rec$3:137 | mml.mmlclib.parser.bindingRecovery$package$.bindingFramePartP:137 | mml.mmlclib.parser.bindingRecovery$package$.rec$2:119 | mml.mmlclib.parser.bindingRecovery$package$.bindingFrameP:119 | mml.mmlclib.parser.expressions$package$.letExprP:381 | mml.mmlclib.parser.expressions$package$.termP$$anonfun$1:210
parser	prepare128m	OK	64
collect-parsed	2m	OK	37
parsing-errors	2m	OK	99
duplicate-names	2m	OK	19
id-assigner	2m	OK	45
type-resolver	2m	OK	29
index-after-type-resolver	2m	OK	4
ctor-gen	2m	OK	16
mem-fn-gen	2m	OK	1
clone-instances	2m	OK	15
protocol-checker	2m	OK	41
ref-resolver	2m	StackOverflowError	8	mml.mmlclib.semantic.RefResolver$.resolveExpr:59 | mml.mmlclib.semantic.RefResolver$.resolveLambda:129 | mml.mmlclib.semantic.RefResolver$.resolveCallee:148 | mml.mmlclib.semantic.RefResolver$.resolveApp:139 | mml.mmlclib.semantic.RefResolver$.resolveTerm:68 | mml.mmlclib.semantic.RefResolver$.resolveExpr$$anonfun$1:59
ref-resolver	prepare128m	OK	22
index-after-ref-resolver	2m	OK	6
clone-bodies	2m	OK	6
op-coherence	2m	OK	12
expression-rewriter	2m	StackOverflowError	7	mml.mmlclib.semantic.ExpressionRewriter$.rewriteTerm:529 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteExpr$$anonfun$1:121 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteExpr:121 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteLambda:585 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteAppFn:594 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteApp:577 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteTerm:530
expression-rewriter	prepare128m	OK	26
index-after-expression-rewriter	2m	OK	7
simplifier	2m	OK	1
index-after-simplifier	2m	OK	6
capture-analyzer	2m	OK	2
index-after-capture-analyzer	2m	OK	2
type-checker	2m	OK	344
index-after-type-checker	2m	OK	3
pap-elaborator	2m	OK	139
closure-mem-gen	2m	StackOverflowError	10	mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:45 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:53 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkExpr$1$$anonfun$1:41 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkExpr$1:40 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:51
closure-mem-gen	prepare128m	OK	20
struct-destructor	2m	OK	4
index-after-struct-destructor	2m	OK	2
ownership	2m	StackOverflowError	147	mml.mmlclib.semantic.CallableValues.resolve$1:86 | mml.mmlclib.semantic.CallableValues.resolve$1$$anonfun$2:93 | mml.mmlclib.semantic.CallableValues.resolve$1:93 | mml.mmlclib.semantic.CallableValues.$anonfun$6:135 | mml.mmlclib.semantic.CallableValues.$anonfun$4:135 | mml.mmlclib.semantic.CallableValues.resolve$1:114
ownership	prepare128m	OK	18008
index-after-ownership	2m	OK	2
closure-destructor	2m	OK	17
index-after-closure-destructor	2m	OK	2
tailrec	2m	OK	2
index-after-tailrec	2m	OK	2
destruction-validation	2m	OK	2
pre-codegen	2m	OK	1
callable-targets	2m	OK	5
local-callables	2m	OK	5
closure-layouts	2m	OK	7
emit-main-body	2m	StackOverflowError	10	mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileExpr:906 | mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileApp$$anonfun$1:998 | mml.mmlclib.codegen.emitter.expression.Applications$package$.compileRuntimeBinding:132 | mml.mmlclib.codegen.emitter.expression.Applications$package$.compileLocalBindingValue:86 | mml.mmlclib.codegen.emitter.expression.Applications$package$.compileLambdaApp:42 | mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileApp:998 | mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileTerm:134 | mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileExpr:907 | mml.mmlclib.codegen.emitter.expression.Applications$package$.compileLambdaApp$$anonfun$1:44
emit-main-body	prepare128m	OK	3
DONE count=1000 flavor=refs outputLines=0
```

</details>

<details>
<summary>1,000 constant bindings</summary>

```text
PROBE count=1000 flavor=zero java=25.0.4.1 stack=2m timeoutPerAttempt=20s
parser	2m	StackOverflowError	104	mml.mmlclib.parser.keywords$package$.keywords:141 | mml.mmlclib.parser.identifiers$package$.bindingIdP:8 | mml.mmlclib.parser.expressions$package$.$anonfun$48:553 | mml.mmlclib.parser.utils$package$.tokenP:18 | mml.mmlclib.parser.expressions$package$.refP:553 | mml.mmlclib.parser.expressions$package$.selectableTermP$$anonfun$1:278 | mml.mmlclib.parser.expressions$package$.selectableTermP:281 | mml.mmlclib.parser.expressions$package$.termP$$anonfun$1:218 | mml.mmlclib.parser.expressions$package$.withTypeAsc:188 | mml.mmlclib.parser.expressions$package$.termP:225 | mml.mmlclib.parser.expressions$package$.$anonfun$31:381 | mml.mmlclib.parser.bindingRecovery$package$.rec$3:137
parser	prepare128m	OK	61
collect-parsed	2m	OK	43
parsing-errors	2m	OK	101
duplicate-names	2m	OK	21
id-assigner	2m	OK	56
type-resolver	2m	OK	46
index-after-type-resolver	2m	OK	10
ctor-gen	2m	OK	13
mem-fn-gen	2m	OK	1
clone-instances	2m	OK	6
protocol-checker	2m	OK	41
ref-resolver	2m	StackOverflowError	8	mml.mmlclib.semantic.RefResolver$.resolveTerm:68 | mml.mmlclib.semantic.RefResolver$.resolveExpr$$anonfun$1:59 | mml.mmlclib.semantic.RefResolver$.resolveExpr:59 | mml.mmlclib.semantic.RefResolver$.resolveLambda:129 | mml.mmlclib.semantic.RefResolver$.resolveCallee:148 | mml.mmlclib.semantic.RefResolver$.resolveApp:139
ref-resolver	prepare128m	OK	13
index-after-ref-resolver	2m	OK	6
clone-bodies	2m	OK	6
op-coherence	2m	OK	13
expression-rewriter	2m	StackOverflowError	8	mml.mmlclib.semantic.ExpressionRewriter$.rewriteExpr:121 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteLambda:585 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteAppFn:594 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteApp:577 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteTerm:530 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteExpr$$anonfun$1:121
expression-rewriter	prepare128m	OK	26
index-after-expression-rewriter	2m	OK	7
simplifier	2m	OK	0
index-after-simplifier	2m	OK	6
capture-analyzer	2m	OK	2
index-after-capture-analyzer	2m	OK	2
type-checker	2m	OK	376
index-after-type-checker	2m	OK	2
pap-elaborator	2m	OK	153
closure-mem-gen	2m	StackOverflowError	15	mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:45 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:53 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkExpr$1$$anonfun$1:41 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkExpr$1:40 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:51
closure-mem-gen	prepare128m	OK	21
struct-destructor	2m	OK	5
index-after-struct-destructor	2m	OK	3
ownership	2m	OK	17895
index-after-ownership	2m	OK	2
closure-destructor	2m	OK	15
index-after-closure-destructor	2m	OK	2
tailrec	2m	OK	2
index-after-tailrec	2m	OK	2
destruction-validation	2m	OK	2
pre-codegen	2m	OK	1
callable-targets	2m	OK	5
local-callables	2m	OK	4
closure-layouts	2m	OK	7
emit-main-body	2m	StackOverflowError	8	mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileExpr:906 | mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileApp$$anonfun$1:998 | mml.mmlclib.codegen.emitter.expression.Applications$package$.compileRuntimeBinding:132 | mml.mmlclib.codegen.emitter.expression.Applications$package$.compileLocalBindingValue:86 | mml.mmlclib.codegen.emitter.expression.Applications$package$.compileLambdaApp:42 | mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileApp:998 | mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileTerm:134 | mml.mmlclib.codegen.emitter.ExpressionCompiler$package$.compileExpr:907 | mml.mmlclib.codegen.emitter.expression.Applications$package$.compileLambdaApp$$anonfun$1:44
emit-main-body	prepare128m	OK	4
DONE count=1000 flavor=zero outputLines=0
```

</details>

<details>
<summary>2,000 constant bindings</summary>

```text
PROBE count=2000 flavor=zero java=25.0.4.1 stack=2m timeoutPerAttempt=20s
parser	2m	StackOverflowError	102	mml.mmlclib.parser.keywords$package$.defAsKw:34 | mml.mmlclib.parser.keywords$package$.keywords:121 | mml.mmlclib.parser.identifiers$package$.bindingIdP:8 | mml.mmlclib.parser.expressions$package$.$anonfun$48:553 | mml.mmlclib.parser.utils$package$.tokenP:18 | mml.mmlclib.parser.expressions$package$.refP:553 | mml.mmlclib.parser.expressions$package$.selectableTermP$$anonfun$1:278 | mml.mmlclib.parser.expressions$package$.selectableTermP:281 | mml.mmlclib.parser.expressions$package$.termP$$anonfun$1:218 | mml.mmlclib.parser.expressions$package$.withTypeAsc:188 | mml.mmlclib.parser.expressions$package$.termP:225 | mml.mmlclib.parser.expressions$package$.$anonfun$31:381
parser	prepare128m	OK	95
collect-parsed	2m	StackOverflowError	3	mml.mmlclib.ast.TermTraversal$.collect:20 | mml.mmlclib.ast.TermTraversal$.collect$$anonfun$1:20
collect-parsed	prepare128m	OK	73
parsing-errors	2m	OK	129
duplicate-names	2m	OK	33
id-assigner	2m	OK	100
type-resolver	2m	OK	49
index-after-type-resolver	2m	OK	10
ctor-gen	2m	OK	29
mem-fn-gen	2m	OK	1
clone-instances	2m	OK	19
protocol-checker	2m	OK	55
ref-resolver	2m	StackOverflowError	8	mml.mmlclib.ast.queries.ValueScope.within:14 | mml.mmlclib.semantic.RefResolver$Context.within:35 | mml.mmlclib.semantic.RefResolver$.resolveLambda:129 | mml.mmlclib.semantic.RefResolver$.resolveCallee:148 | mml.mmlclib.semantic.RefResolver$.resolveApp:139 | mml.mmlclib.semantic.RefResolver$.resolveTerm:68 | mml.mmlclib.semantic.RefResolver$.resolveExpr$$anonfun$1:59 | mml.mmlclib.semantic.RefResolver$.resolveExpr:59
ref-resolver	prepare128m	OK	25
index-after-ref-resolver	2m	OK	15
clone-bodies	2m	OK	22
op-coherence	2m	OK	11
expression-rewriter	2m	StackOverflowError	10	mml.mmlclib.semantic.ExpressionRewriter$.rewriteExpr:121 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteLambda:585 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteAppFn:594 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteApp:577 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteTerm:530 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteExpr$$anonfun$1:121
expression-rewriter	prepare128m	OK	34
index-after-expression-rewriter	2m	OK	9
simplifier	2m	OK	1
index-after-simplifier	2m	OK	10
capture-analyzer	2m	OK	3
index-after-capture-analyzer	2m	OK	9
type-checker	2m	StackOverflowError	11	mml.mmlclib.ast.TermTraversal$.collect:20 | mml.mmlclib.ast.TermTraversal$.collect$$anonfun$1:20
type-checker	prepare128m	OK	1236
index-after-type-checker	2m	OK	9
pap-elaborator	2m	StackOverflowError	3	mml.mmlclib.semantic.ValueAvailability$$anon$4.applyOrElse:149 | mml.mmlclib.semantic.ValueAvailability$$anon$4.applyOrElse:142 | mml.mmlclib.ast.TermTraversal$.collect:20 | mml.mmlclib.ast.TermTraversal$.collect$$anonfun$1:20
pap-elaborator	prepare128m	OK	446
closure-mem-gen	2m	StackOverflowError	29	mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkExpr$1:40 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:51 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:53 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkExpr$1$$anonfun$1:41
closure-mem-gen	prepare128m	OK	53
struct-destructor	2m	OK	12
index-after-struct-destructor	2m	OK	8
ownership	2m	StackOverflowError	388	mml.mmlclib.semantic.ValueAvailability.isAvailable:70 | mml.mmlclib.semantic.ValueAvailability.isAvailable:74
ownership	prepare128m	TIMEOUT	20005
```

</details>

<details>
<summary>100 constant bindings with explicit validation</summary>

```text
PROBE count=100 flavor=zero java=25.0.4.1 stack=2m timeoutPerAttempt=20s
parser	2m	OK	89
collect-parsed	2m	OK	3
parsing-errors	2m	OK	63
duplicate-names	2m	OK	13
id-assigner	2m	OK	25
type-resolver	2m	OK	17
index-after-type-resolver	2m	OK	0
ctor-gen	2m	OK	5
mem-fn-gen	2m	OK	1
clone-instances	2m	OK	3
protocol-checker	2m	OK	36
ref-resolver	2m	OK	14
index-after-ref-resolver	2m	OK	0
clone-bodies	2m	OK	1
op-coherence	2m	OK	13
expression-rewriter	2m	OK	11
index-after-expression-rewriter	2m	OK	0
simplifier	2m	OK	0
index-after-simplifier	2m	OK	1
capture-analyzer	2m	OK	1
index-after-capture-analyzer	2m	OK	0
type-checker	2m	OK	61
index-after-type-checker	2m	OK	0
pap-elaborator	2m	OK	31
closure-mem-gen	2m	OK	6
struct-destructor	2m	OK	3
index-after-struct-destructor	2m	OK	0
ownership	2m	OK	136
index-after-ownership	2m	OK	0
closure-destructor	2m	OK	9
index-after-closure-destructor	2m	OK	0
tailrec	2m	OK	2
index-after-tailrec	2m	OK	0
destruction-validation	2m	OK	3
pre-codegen	2m	OK	2
VALIDATED errors=0 canEmit=true
callable-targets	2m	OK	7
local-callables	2m	OK	4
closure-layouts	2m	OK	2
emit-main-body	2m	OK	6
DONE count=100 flavor=zero outputLines=0
```

</details>

<details>
<summary>Profiled 1,000-constant run</summary>

```text
PROBE count=1000 flavor=zero java=25.0.4.1 stack=2m timeoutPerAttempt=20s
parser	2m	StackOverflowError	89	mml.mmlclib.parser.keywords$package$.moduleKw:73 | mml.mmlclib.parser.keywords$package$.keywords:119 | mml.mmlclib.parser.identifiers$package$.bindingIdP:8 | mml.mmlclib.parser.expressions$package$.$anonfun$48:553 | mml.mmlclib.parser.utils$package$.tokenP:18 | mml.mmlclib.parser.expressions$package$.refP:553 | mml.mmlclib.parser.expressions$package$.selectableTermP$$anonfun$1:278 | mml.mmlclib.parser.expressions$package$.selectableTermP:281 | mml.mmlclib.parser.expressions$package$.termP$$anonfun$1:218 | mml.mmlclib.parser.expressions$package$.withTypeAsc:188 | mml.mmlclib.parser.expressions$package$.termP:225 | mml.mmlclib.parser.expressions$package$.$anonfun$31:381
parser	prepare128m	OK	63
collect-parsed	2m	OK	33
parsing-errors	2m	OK	98
duplicate-names	2m	OK	22
id-assigner	2m	OK	37
type-resolver	2m	OK	34
index-after-type-resolver	2m	OK	3
ctor-gen	2m	OK	12
mem-fn-gen	2m	OK	1
clone-instances	2m	OK	8
protocol-checker	2m	OK	45
ref-resolver	2m	StackOverflowError	11	mml.mmlclib.ast.queries.ValueScope.within:14 | mml.mmlclib.semantic.RefResolver$Context.within:35 | mml.mmlclib.semantic.RefResolver$.resolveApp:135 | mml.mmlclib.semantic.RefResolver$.resolveTerm:68 | mml.mmlclib.semantic.RefResolver$.resolveExpr$$anonfun$1:59 | mml.mmlclib.semantic.RefResolver$.resolveExpr:59 | mml.mmlclib.semantic.RefResolver$.resolveLambda:129 | mml.mmlclib.semantic.RefResolver$.resolveCallee:148 | mml.mmlclib.semantic.RefResolver$.resolveApp:139
ref-resolver	prepare128m	OK	15
index-after-ref-resolver	2m	OK	3
clone-bodies	2m	OK	7
op-coherence	2m	OK	10
expression-rewriter	2m	StackOverflowError	7	mml.mmlclib.semantic.ExpressionRewriter$.rewriteExpr:121 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteLambda:585 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteAppFn:594 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteApp:577 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteTerm:530 | mml.mmlclib.semantic.ExpressionRewriter$.rewriteExpr$$anonfun$1:121
expression-rewriter	prepare128m	OK	29
index-after-expression-rewriter	2m	OK	6
simplifier	2m	OK	1
index-after-simplifier	2m	OK	7
capture-analyzer	2m	OK	3
index-after-capture-analyzer	2m	OK	17
type-checker	2m	OK	335
index-after-type-checker	2m	OK	4
pap-elaborator	2m	OK	157
closure-mem-gen	2m	StackOverflowError	12	mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkExpr$1:40 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:51 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkTerm$1:53 | mml.mmlclib.semantic.ClosureMemoryFnGenerator$.walkExpr$1$$anonfun$1:41
closure-mem-gen	prepare128m	OK	23
struct-destructor	2m	OK	4
index-after-struct-destructor	2m	OK	3
ownership	2m	TIMEOUT	20005
```

</details>

<details>
<summary>Parser shape controls</summary>

```text
statements count=10 PARSED
statements count=10 DIAGNOSTICS 0
functions count=10 PARSED
functions count=10 DIAGNOSTICS 0
mixed count=10 PARSED
mixed count=10 DIAGNOSTICS 0
statements count=1000 PARSED
statements count=1000 DIAGNOSTICS 0
functions count=500 StackOverflowError mml.mmlclib.parser.expressions$package$.letExprP(expressions.scala:383) | mml.mmlclib.parser.expressions$package$.termP$$anonfun$1(expressions.scala:210) | mml.mmlclib.parser.expressions$package$.withTypeAsc(expressions.scala:188) | mml.mmlclib.parser.expressions$package$.termP(expressions.scala:225)
mixed count=250 StackOverflowError mml.mmlclib.parser.utils$package$.tokenP(utils.scala:18) | mml.mmlclib.parser.expressions$package$.implementationP$1(expressions.scala:636) | mml.mmlclib.parser.expressions$package$.nativeImplP(expressions.scala:645) | mml.mmlclib.parser.expressions$package$.termP$$anonfun$1(expressions.scala:213)
```

</details>

### Complete ownership top-frame sample counts

<details>
<summary>All 1,722 ownership execution samples, grouped by top frame</summary>

```text
count  class.method:line
  588  mml.mmlclib.semantic.ValueAvailability.isAvailable:72
  495  mml.mmlclib.semantic.ValueAvailability.isAvailable:74
  429  mml.mmlclib.semantic.ValueAvailability.isAvailable:48
  124  mml.mmlclib.semantic.ValueAvailability.isAvailable:85
   48  mml.mmlclib.semantic.ValueAvailability.isAvailable:76
    3  mml.mmlclib.semantic.ValueAvailability$$Lambda.0x000001ff012e79d0.apply:-1
    3  scala.collection.immutable.BitmapIndexedMapNode.copyAndInsertValue:1028
    2  mml.mmlclib.semantic.OwnershipAnalyzer$.mml$mmlclib$semantic$OwnershipAnalyzer$ReturnOwnershipAnalysis$$$_$exprReturnsOwned$$anonfun$1:346
    2  mml.mmlclib.semantic.ValueAvailability.isAvailable:86
    2  mml.mmlclib.semantic.ValueAvailability.isAvailable:70
    2  scala.collection.immutable.BitmapIndexedMapNode.get:714
    2  mml.mmlclib.semantic.ValueAvailability$.check$1:109
    2  scala.Option.exists:466
    1  scala.collection.immutable.$colon$colon.next_$eq:652
    1  scala.collection.immutable.List.flatMap:292
    1  scala.Option.filter:368
    1  scala.runtime.BoxesRunTime.equals:119
    1  scala.collection.immutable.BitmapIndexedMapNode.mergeTwoKeyValPairs:964
    1  mml.mmlclib.semantic.ValueAvailability.isAvailable:60
    1  scala.collection.immutable.HashMap.concat:169
    1  scala.collection.immutable.BitmapIndexedMapNode.copyAndMigrateFromInlineToNode:1085
    1  scala.collection.mutable.Growable.addAll:66
    1  scala.collection.immutable.List.forall:384
    1  mml.mmlclib.semantic.CallStages$.signature$2:35
    1  scala.collection.immutable.BitmapIndexedMapNode.get:712
    1  mml.mmlclib.semantic.OwnershipAnalyzer$ReturnOwnershipAnalysis$.mml$mmlclib$semantic$OwnershipAnalyzer$ReturnOwnershipAnalysis$$$termReturnsOwned:297
    1  scala.collection.immutable.List.splitAt:218
    1  mml.mmlclib.semantic.CallStages$.apply:61
    1  mml.mmlclib.semantic.CallStages$.signature$2:36
    1  scala.collection.immutable.HashMap.concat:168
    1  mml.mmlclib.semantic.OwnershipAnalyzer$.mml$mmlclib$semantic$OwnershipAnalyzer$ReturnOwnershipAnalysis$$$_$termReturnsOwned$$anonfun$2:308
    1  scala.collection.immutable.List.zip:83
    1  mml.mmlclib.semantic.ValueAvailability.isAvailable:63
```

</details>

### Research programs and invocation

These programs preserve the research method. They are evidence, not implementation changes or
a new test-suite prerequisite. Compile them against the inspected compiler library and its
Compile dependency classpath using the matching Scala compiler. The build command above reports
that classpath; include the library resources and compiled probe classes when launching Java.
No probe source or dependency is required from an external local directory.

The research used the Scala 3.10.0 compiler entry point `dotty.tools.dotc.Main` to compile the
embedded programs against the freshly built library. In the following parameterized commands,
`probeCompilerClasspath` contains that Scala compiler and its dependencies, `probeLibraryClasspath`
contains the built library/resources and its dependency jars, `probeOutput` is the chosen probe
class-output directory, and `probeRuntimeClasspath` adds that directory to the library classpath.
Save the two embedded programs as `DepthProbe.scala` and `ParserShapes.scala` in a scratch working
directory. These paths are outputs of reproduction, not evidence required by this task.

```sh
java -cp "$probeCompilerClasspath" dotty.tools.dotc.Main \
  -classpath "$probeLibraryClasspath" -d "$probeOutput" DepthProbe.scala ParserShapes.scala
java -Xss2m -Xmx2g -cp "$probeRuntimeClasspath" DepthProbe 100 refs
java -Xss2m -Xmx2g -cp "$probeRuntimeClasspath" DepthProbe 500 refs
java -Xss2m -Xmx2g -cp "$probeRuntimeClasspath" DepthProbe 1000 refs
java -Xss2m -Xmx2g -cp "$probeRuntimeClasspath" DepthProbe 1000 zero
java -Xss2m -Xmx2g -cp "$probeRuntimeClasspath" DepthProbe 2000 zero
java -Xss2m -Xmx2g -cp "$probeRuntimeClasspath" DepthProbe 100 zero
java -Xss2m -Xmx1g -cp "$probeRuntimeClasspath" ParserShapes 10 statements
java -Xss2m -Xmx1g -cp "$probeRuntimeClasspath" ParserShapes 10 functions
java -Xss2m -Xmx1g -cp "$probeRuntimeClasspath" ParserShapes 10 mixed
java -Xss2m -Xmx1g -cp "$probeRuntimeClasspath" ParserShapes 1000 statements
java -Xss2m -Xmx1g -cp "$probeRuntimeClasspath" ParserShapes 500 functions
java -Xss2m -Xmx1g -cp "$probeRuntimeClasspath" ParserShapes 250 mixed
java -Xss2m -Xmx2g \
  -XX:StartFlightRecording=filename=ownership.jfr,settings=profile,dumponexit=true \
  -cp "$probeRuntimeClasspath" DepthProbe 1000 zero
jfr print --json --events jdk.ExecutionSample ownership.jfr > samples.json
```

The unprofiled operation matrix predates the explicit `VALIDATED` report in the embedded
program: those runs called validation and discarded its return value. The 100-zero control used
the displayed validation report. No other phase behavior changed. The profiling run used the
original variant and stopped in ownership before validation.

<details>
<summary>DepthProbe.scala</summary>

```scala
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.*
import mml.mmlclib.parser.Parser
import mml.mmlclib.semantic.*
import mml.mmlclib.codegen.emitter.*
import java.util.concurrent.atomic.AtomicReference

object DepthProbe:
  private def attempt[A](name: String, stackBytes: Long)(body: => A): Either[Throwable, A] =
    val result = new AtomicReference[Either[Throwable, A]]()
    val start = System.nanoTime()
    val worker = new Thread(null, () =>
      try result.set(Right(body))
      catch case problem: Throwable => result.set(Left(problem))
    , name, stackBytes, false)
    worker.setDaemon(true)
    worker.start()
    worker.join(20000)
    val elapsed = (System.nanoTime() - start) / 1000000
    val mode = if stackBytes == 0 then "2m" else "prepare128m"
    if worker.isAlive then
      println(s"$name\t$mode\tTIMEOUT\t$elapsed")
      System.out.flush()
      System.exit(124)
    val outcome = result.get()
    outcome match
      case Right(_) => println(s"$name\t$mode\tOK\t$elapsed")
      case Left(problem) =>
        val frames = problem.getStackTrace.iterator
          .filter(frame => frame.getClassName.startsWith("mml."))
          .map(frame => s"${frame.getClassName}.${frame.getMethodName}:${frame.getLineNumber}")
          .take(12).toList.distinct.mkString(" | ")
        println(s"$name\t$mode\t${problem.getClass.getSimpleName}\t$elapsed\t$frames")
    System.out.flush()
    outcome

  private def check[A](name: String)(body: => A): A =
    attempt(name, 0L)(body) match
      case Right(value) => value
      case Left(_: StackOverflowError) =>
        attempt(name, 128L * 1024 * 1024)(body) match
          case Right(value) => value
          case Left(problem) => throw problem
      case Left(problem) => throw problem

  def main(args: Array[String]): Unit =
    val count = args(0).toInt
    val flavor = args.lift(1).getOrElse("refs")
    val bindings = (0 until count).map { i =>
      val value = if flavor == "zero" || i == 0 then "0" else s"x${i - 1}"
      s"let x$i = $value;"
    }.mkString("\n")
    val source = s"pub fn main(): Int =\n$bindings\nx${count - 1};\n;\n"
    println(s"PROBE count=$count flavor=$flavor java=${System.getProperty("java.version")} stack=2m timeoutPerAttempt=20s")
    val (info, parsed) = check("parser")(Parser.parseModuleWithInfo(source, "DepthProbe"))
    val module = parsed.fold(error => throw new IllegalStateException(error.message), identity)
    val initial = CompilerState.empty(module, info, CompilerConfig.default)
    check("collect-parsed")(TermTraversal.collect(module.members.collectFirst { case b: Bnd => b.value }.get) { case p: Lambda => p.params.size }.sum)
    val stamped = check("parsing-errors")(ParsingErrorChecker.checkModule(initial))
    val injected = stamped.withModule(injectCommonFunctions(injectStandardOperators(injectBasicTypes(stamped.module))))
    val phases: List[(String, CompilerState => CompilerState, Boolean)] = List(
      ("duplicate-names", DuplicateNameChecker.rewriteModule, false),
      ("id-assigner", IdAssigner.rewriteModule, false),
      ("type-resolver", TypeResolver.rewriteModule, true),
      ("ctor-gen", ConstructorGenerator.rewriteModule, false),
      ("mem-fn-gen", MemoryFunctionGenerator.rewriteModule, false),
      ("clone-instances", StructCloneGenerator.register, false),
      ("protocol-checker", ProtocolChecker.rewriteModule, false),
      ("ref-resolver", RefResolver.rewriteModule, true),
      ("clone-bodies", StructCloneGenerator.generateBodies, false),
      ("op-coherence", OperatorCoherence.checkModule, false),
      ("expression-rewriter", ExpressionRewriter.rewriteModule, true),
      ("simplifier", Simplifier.rewriteModule, true),
      ("capture-analyzer", CaptureAnalyzer.rewriteModule, true),
      ("type-checker", TypeChecker.rewriteModule, true),
      ("pap-elaborator", PartialApplicationElaborator.rewriteModule, false),
      ("closure-mem-gen", ClosureMemoryFnGenerator.rewriteModule, false),
      ("struct-destructor", StructDestructorBodyGenerator.rewriteModule, true),
      ("ownership", OwnershipAnalyzer.rewriteModule, true),
      ("closure-destructor", ClosureDestructorBodyGenerator.rewriteModule, true),
      ("tailrec", TailRecursionDetector.rewriteModule, true),
      ("destruction-validation", DestructionValidator.rewriteModule, false)
    )
    val finalState = phases.foldLeft(injected) { case (state, (name, phase, indexed)) =>
      val rewritten = check(name)(phase(state))
      if rewritten.errors.nonEmpty then
        println(s"DIAGNOSTICS phase=$name count=${rewritten.errors.size}")
        System.exit(5)
      if indexed then check(s"index-after-$name")(ResolvablesIndexer.rewriteModule(rewritten))
      else rewritten
    }
    val validated = check("pre-codegen")(CodegenStage.validate(finalState))
    println(s"VALIDATED errors=${validated.errors.size} canEmit=${validated.canEmitCode}")
    val targets = check("callable-targets")(CallableTargetAnalysis.analyze(finalState.module))
    val locals = check("local-callables")(LocalCallableAnalysis.analyze(finalState.module, targets))
    val environments = check("closure-layouts")(ClosureEnvironmentLayout.analyze(finalState.module, targets))
      .fold(error => throw new IllegalStateException(error.message), identity)
    val main = finalState.module.members.collectFirst { case b: Bnd if b.name == "main" => b }.get
    val body = main.value.terms.collectFirst { case lambda: Lambda => lambda.body }.get
    val codegen = CodeGenState(
      moduleName = finalState.module.name,
      resolvables = finalState.module.resolvables,
      callableTargetAnalysis = targets,
      localCallableAnalysis = locals,
      closureEnvironments = environments
    )
    val emitted = check("emit-main-body")(compileExpr(body, codegen, Map.empty))
    emitted match
      case Left(error) => println(s"EMISSION_ERROR ${error.message}")
      case Right(result) => println(s"DONE count=$count flavor=$flavor outputLines=${result.state.output.size}")
```

</details>

<details>
<summary>ParserShapes.scala</summary>

```scala
import mml.mmlclib.ast.*
import mml.mmlclib.compiler.*
import mml.mmlclib.parser.Parser

object ParserShapes:
  def main(args: Array[String]): Unit =
    val count = args(0).toInt
    val shape = args(1)
    val pieces = (0 until count).map { i =>
      shape match
        case "statements" => "();"
        case "functions" => s"fn f$i(): Int = 0;;"
        case "mixed" => s"let x$i = 0; (); fn f$i(): Int = x$i;; ();"
    }.mkString("\n")
    val source = s"pub fn main(): Int =\n$pieces\n0;\n;"
    try
      val (info, result) = Parser.parseModuleWithInfo(source, "ParserShapes")
      result match
        case Left(error) => println(s"$shape count=$count PARSE_FAILURE ${error.message}")
        case Right(module) =>
          println(s"$shape count=$count PARSED")
          val state = ParsingErrorChecker.checkModule(CompilerState.empty(module, info, CompilerConfig.default))
          println(s"$shape count=$count DIAGNOSTICS ${state.errors.size}")
    catch case error: StackOverflowError =>
      val frames = error.getStackTrace.iterator.filter(_.getClassName.startsWith("mml.")).take(4).mkString(" | ")
      println(s"$shape count=$count StackOverflowError $frames")
```

</details>

The sample aggregation selects `jdk.ExecutionSample` events whose
`values.sampledThread.javaName` is `ownership`, then groups the first
`values.stackTrace.frames` entry by method class, method name, and line number. The 1,690 count
includes top frames whose class is exactly `mml/mmlclib/semantic/ValueAvailability`; its generated
lambda and companion-object frames are reported separately in the complete distribution.
