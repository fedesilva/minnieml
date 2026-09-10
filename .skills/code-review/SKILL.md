---
name: code-review
description: Perform a strict, evidence-driven review of code and directly affected technical documentation for correctness, modularity, lean design, human readability, compiler invariants, tests, and target correctness. Use when the author asks to review, audit, or inspect code; wants a hard pre-commit critique; or needs changed behavior traced through the surrounding system. Produce findings only and never edit during the review.
---

# Code Review

Review code as a demanding maintainer who values proof, small systems, and
code that reads like what it intends to do.

Be hard to please about the code and hard to convince about a finding. Do not
trade rigor for noise.

## MML sources of truth

Read `AGENTS.md`, `context/coding-rules.md`,
`context/qa-rules-and-coding-style.md`, and `context/dev-tools.md` directly.
For compiler changes, include `docs/design/compiler-design.md`; for language
or ownership changes, also include `docs/language-reference.md` and
`docs/memory-model.md`. Load only directly relevant designs and task records.
These repository rules govern the review.

## Top-level orchestration

The top-level agent owns scope, builds the primary review packet, and dispatches
one new reviewer with no inherited conversation history. Spawn it with
`fork_turns: "none"`. Do not perform the primary review in the top-level agent
or reuse a reviewer from earlier work.

The primary reviewer must not read or invoke this skill. Give it a
self-contained, source-derived packet containing:

- the repository root and exact diff or commit range under review;
- the workstream goal, approved scope, and relevant task file when one exists;
- the paths to `AGENTS.md` and the applicable coding, QA, architecture, and
  language, memory-model, and target documentation;
- the exact verification commands run and their results;
- runtime observations, target/toolchain details, skipped checks, known limitations, and other
  technical evidence that is not available in the working tree;
- the full **Primary Reviewer Protocol** below.

Tell the reviewer to read every supplied rule and technical source directly.
Do not pass the parent conversation, the parent's conclusions, suspected
findings, intended fixes, or a suggested verdict. Allow inspection of code
directly connected to the change, but avoid unrelated task files and backlog
context.

If subagents are unavailable, stop before the review and ask the author whether
to continue with a parent-agent review. Proceed only with explicit approval,
execute the Primary Reviewer Protocol directly, and validate every candidate
with its evidence checks. Report that primary-review and per-claim independence
were skipped. The approval covers narrow re-reviews for the same review
workstream while subagents remain unavailable. Never substitute a parent review
silently.

### Re-review a fix narrowly

Dispatch each re-review to a new fresh-context primary reviewer. Build a new
self-contained packet containing the Primary Reviewer Protocol but limit its
scope to:

- the confirmed finding;
- the exact fix diff;
- the invariant the fix must restore;
- directly affected callers or callees;
- relevant regression tests and verification results.

Do not pass the earlier reviewer's broader reasoning, other candidates, the
parent's response, or a suggested verdict. Do not reopen unchanged parts of the
original review or search for unrelated findings. Broaden the scope only when
the fix itself changes a wider contract, and state why that wider scope is
necessary.

## Primary Reviewer Protocol

The primary reviewer executes this protocol from the review packet. It owns the
review and launches the independent claim verifiers. It must not read or invoke
the code-review skill.

## Establish the review

1. Read `AGENTS.md` and every instruction file that applies to the changed
   paths.
2. Use the scope named by the author. Otherwise review all current uncommitted
   changes, including staged, unstaged, and untracked files.
3. Read the approved task or design only when it is part of the current focus.
   Do not load unrelated backlog context.
4. Inspect the complete diff, then read enough callers, callees, types, tests,
   and compiler/runtime boundaries to understand the changed behavior.
5. Keep the review read-only. Do not edit, stage, commit, post comments, or
   change task state.
6. When a focused tracking-document review already passed, treat its internal
   consistency as established. Report a tracking issue only when code, tests,
   or runtime evidence under review directly contradicts a tracking claim.

## Follow the code

Do not review syntax in isolation. Reconstruct what the program does.

- Trace changed values and control flow from entry point to observable effect.
- Follow state transitions, ownership transfers, error paths, retries, and
  cleanup.
- Identify preconditions, postconditions, and invariants. Check that every
  branch preserves them.
- Examine callers and implementations when an interface alone cannot prove
  behavior.
- For concurrent tooling and runtime code, consider ordering, reentrancy,
  stale observations, atomicity, memory ordering, and every relevant
  interleaving.
- For queues, buffers, counters, timers, and fixed capacities, prove the bounds
  and inspect overflow, wraparound, saturation, loss, and backpressure.
- Check that errors, partial failures, dropped work, retries, and capacity
  failures remain observable enough to diagnose rather than appearing
  successful or disappearing silently.
- Compare tests and verification claims with the behavior they actually
  establish.

Think like a theorem solver. Treat each correctness claim as an obligation,
then try to construct a counterexample from inputs, state, timing, failure, or
execution order. Distinguish what the types prove, what the code proves, what a
test observes, and what only target execution or measurement can establish.

## Enforce the design taste

### Correctness first

Find bugs that compile. Look for wrong state, wrong ordering, partial updates,
lost work, stale data, invalid assumptions, mismatched units, off-by-one
boundaries, and failures that appear successful.

Do not excuse a correctness problem because the code is attractive. Do not
invent a bug because the code is unfamiliar.

### Modular, but never ceremonial

Require each module, trait, type, and abstraction to earn its existence through
clear ownership, a protected invariant, a real dependency boundary, reuse, or
a useful test seam.

Flag mixed responsibilities, leaking implementation details, reversed
dependencies, distant knowledge, and monoliths that hide several concepts in
one place. Also flag wrapper layers, pass-through traits, speculative extension
points, and module splits that add navigation without removing coupling.

Seek the smallest sufficient boundary. Neither a blob nor a layer cake.

### Lean and humane

Judge simplicity by the mental work required of the reader, not by line count.
Prefer code that an experienced, tired human can scan without decoding.

Favor:

- domain words and APIs that read like the intent;
- straight, visible control flow;
- well-named intermediate values that expose the reasoning;
- small conceptual units with obvious responsibilities;
- comments that explain invariants, constraints, and reasons.

A compiler phase should read like its transformation: resolve operands, check
constraints, construct the result. Keep traversal and reconstruction plumbing
behind meaningful names while leaving ordering and error flow visible.

Reject both compressed cleverness and reams of mechanical repetition. Look for
a small, fluent vocabulary that moves boilerplate behind one honest name while
leaving the call site readable. Remove repetition only when a real shared
concept exists; do not abstract incidental textual similarity.

Flag dense expression puzzles, gratuitous type machinery, nested conditions,
clever one-liners, repeated plumbing, copy-pasted state handling, and comments
that narrate obvious statements. Do not demand verbosity, arbitrary function
size limits, or abstraction for its own sake.

### Scala and functional design

Apply `context/qa-rules-and-coding-style.md` and `context/coding-rules.md`
as the source of truth. Preserve immutable state threading, explicit context,
Cats Option/Either conventions, exhaustive matching, and accumulated compiler
errors. Keep unavoidable mutation at documented boundaries.

A fold is not automatically readable because it is functional. Prefer named
records over positional tuples for meaningful state, and named transformation
steps over dense nested combinators. Reject speculative type machinery and
pass-through abstractions as readily as hidden mutation.

### Keep machinery out of the way

Keep LLVM text emission, native ABI packing, filesystem/process operations,
and LSP protocol details behind the smallest honest boundary. Semantic code
should describe language rules without depending on emitter naming conventions
or target representation details that belong elsewhere.

Check that each compiler phase has an identifiable responsibility and explicit
input/output invariants. Shared phase logic belongs in a shared module rather
than making one phase serve as several separately scheduled transformations.

### Preserve compiler and runtime contracts

Trace the affected contracts, including:

- type information, stable symbol IDs, lexical scope, shadowing, and index freshness;
- agreement between AST rewrites, traversal, validation, printing, and editor support;
- ownership witnesses, borrowing, moves, escape, aliasing, and destruction placement;
- single evaluation, observable effect order, branch/merge metadata, and recursion;
- target layouts, alignment, integer widths, calling conventions, and native/MML ABI calls;
- allocation lifetimes, initialization, cleanup on each path, and partial failures;
- diagnostics and error accumulation on malformed or unsupported programs.

Do not infer ownership from representation alone or invent implicit clones to
repair a transfer. Judge the implementation against the approved language and
memory contracts, not an assumed future architecture.

Treat compiler resource use and generated-program cost separately. Raise a
performance finding only with a concrete workload, complexity, resource bound,
or measured target effect. Respect the approved scope; a correctness review
is not permission to start an optimization workstream. Use IR, disassembly,
and target evidence when static reasoning cannot settle the question.

## Demand useful tests

Check behavior, not just coverage.

- Require boundary, negative, capacity, transition, and failure cases where
  they can change the result.
- Check that mocks stop at external boundaries and do not reimplement the logic
  under test. Use the existing `BaseEffFunSuite` helpers for compiler tests.
- Prefer resolved IDs and type-aware assertions over generated names when
  semantic identity is the contract being tested.
- Challenge tests that merely repeat the implementation, cannot fail for the
  suspected bug, or assert an incidental representation.
- Separate semantic checks, emitted IR, LLVM verification, native execution,
  sanitizer results, and benchmark measurements. Ignored tests are not passing coverage.
- Never claim that valid IR proves ownership safety or that one target proves portability.
- Read `context/dev-tools.md` before running verification. Use `sbtn`, never
  concurrent `sbtn` sessions; coordinate build access across reviewers. Do not run
  mutating formatter/linter fixes, publish a compiler, or clean shared outputs
  during a read-only review. Use isolated scratch outputs for minimal experiments.

## Review affected documentation

Audit comments, technical documentation, and TODOs touched by or directly
affected by the change.

- Reject claims stronger than the available code, test, build, or runtime
  evidence.
- Keep affected documents mutually consistent and synchronized with the
  implemented behavior.
- Report comments or documentation made stale by the change.
- Report TODOs that are outdated or already resolved.

## Validate findings

Before reporting an issue:

1. Re-read the relevant code and applicable rule.
2. Trace a concrete failure path or explain the specific cognitive,
   architectural, or resource cost.
3. Check whether surrounding code, types, tests, or target constraints
   already prevent it.
4. For initialization and lifetime claims, trace storage, construction syntax,
   language initialization rules, destruction, reuse, and first use. A missing
   member assignment alone does not prove uninitialized state.
5. Separate introduced problems from pre-existing ones. Report a pre-existing
   issue only when the change relies on it, exposes it, or makes it worse.
6. Give the smallest credible correction direction without redesigning the
   project casually.

Use focused read-only investigation or verification commands when they can
confirm or disprove a finding. Do not report speculation as fact.

## Verify every claim independently

Treat the reviewer's findings as candidates, not results. Before reporting
them:

1. Split each candidate into one atomic claim that can be confirmed or
   rejected on its own.
2. Spawn one new subagent for each claim with no inherited conversation
   history. Use `fork_turns: "none"`. Never give one verifier several claims.
3. Run verifiers in parallel when capacity permits and in waves otherwise.
   Preserve the one-agent-per-claim rule.
4. Give each verifier the repository root, exact review scope, the single
   claim, changed lines, and paths to the applicable rules and technical
   sources. Require it to read those sources directly.
5. Give the verifier all verification instructions directly in the packet.
   The verifier must not read or invoke the code-review skill.
6. Do not pass other findings, the reviewer's reasoning process, a confidence
   score, a desired verdict, or a proposed fix. Ask the verifier to try to
   disprove the claim before accepting it.
7. Keep verification read-only. Allow focused commands, tests, disassembly, or
   minimal experiments when they can settle the claim without changing the
   reviewed work.
8. Require a verdict of `confirmed`, `rejected`, or `unresolved`, with the
   execution trace or evidence behind it.

Return a finding only when its verifier confirms it. Drop rejected claims and
withhold unresolved claims entirely. The report may say that verification was
inconclusive, but must not disclose the unverified claim. Outside the explicitly
approved unavailable-subagent fallback, report the review as blocked if an
independent verifier cannot be spawned for every claim.

## Report

Put findings first, ordered by severity. For each finding include:

- severity and confidence;
- file and line;
- the violated invariant, concrete failure, or exact source of unnecessary
  cognitive or machine cost;
- the execution trace or evidence that establishes it;
- a concise correction direction.

Outside the approved unavailable-subagent fallback, state that each reported
finding was independently verified and base confidence on the verifier's
evidence rather than the original reviewer's conviction. In fallback mode,
state instead that independence was skipped and base confidence on the direct
evidence checks.

Label proven bugs separately from design, readability, performance, and test
findings. Do not dilute material issues with taste-only nitpicks.

If no actionable findings remain, say so explicitly. Then state residual risks,
verification gaps, and which conclusions still depend on target execution or
measurement.
