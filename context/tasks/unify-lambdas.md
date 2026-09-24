# Unify lambdas

## Metadata

- **Owner:** The Author
- **Status:** in_progress
- **Created:** 2026-09-10
- **Target Branch:** dev-lambda-unify
- **External Reference (optional):** https://github.com/fedesilva/minnieml/issues/255

## Execution Checklist

Top-level delivery order; details and checkpoint evidence are linked below. Pending steps
remain subject to bounded plan approval. Only the active step exposes its current subitem.

1. [x] **complete** — [Preserve tests, samples, docs, and helpers](#lambda-restart-test-preservation-map) (`6e33ee4`).
2. [x] **complete** — [Reject borrowed returns through aliases](#signed-off-workstream-borrowed-returns-through-aliases) (`323b8ca`).
3. [x] **complete** — [Typed closure destruction](#signed-off-workstream-typed-closure-destruction) (`2efeb8c`).
4. [x] **complete** — [PAP creation and ownership semantics](#partial-application-ownership-contract) (`ea803e2`).
5. [x] **complete** — [Deferred consuming arguments](#bug-supply-a-consuming-argument-after-pap-creation) (`c66e309`).
6. [x] **complete** — [Owned PAPs in struct fields](#bug-store-an-owned-pap-in-a-struct-field) (`2b55e82`).
7. [x] **complete** — [Nested consuming-PAP calls and review repairs](#bug-nested-consuming-pap-calls) (`234b6e1`, `dc29582`).
8. [x] **complete** — [Binding identity and local construction](#establish-binding-identity-and-local-construction-invariants); signed off.
9. [x] **complete** — [BUG: consume an owned PAP captured by a move lambda](#bug-consume-an-owned-pap-captured-by-a-move-lambda); signed off.
10. [x] **complete** — [BUG: preserve valid LLVM and TCO for nested capturing recursion](#bug-preserve-valid-llvm-and-tco-for-nested-capturing-recursion); signed off.
11. [x] **complete** — [BUG: continue elaboration after independent errors](#bug-continue-elaboration-after-independent-errors); signed off.
12. [x] **complete** — [Counter and argument-expression preservation](#preserve-counters-and-argument-expressions-across-ownership-analysis); signed off.
13. [ ] **planned** — [Mixed-ownership transfer repair and conditional-ownership hardening](#bug-preserve-mixed-ownership-through-consuming-transfers).
14. [ ] **in_progress** — [Remaining lambda semantics and lowering](#remaining-lambda-implementation), including [restoration of all ignored regressions](#restore-ignored-regressions).
15. [ ] **planned** — [Integrate the PAP tutorial into the language reference](#later-stage-documentation).
16. [ ] **planned** — General branch audit (deferred) and final task signoff.

Linux sanitizer validation is deferred to the separate
[Linux verification task](linux-sanitizer-verification.md).

## Problem

Lambda semantics, ownership, and lowering need a common model on `dev-lambda-unify`.
The [Execution Checklist](#execution-checklist) is the current progress overview;
[Task Working Memory](#task-working-memory) holds the active handoff.

## Outcome

Complete the fresh lambda implementation on the migration branch using the approved ownership contracts and preserved regressions.

## Scope

- In scope: The problem and outcome above; detailed scope is settled in the approved plan.
- Out of scope: Unrelated work and changes not covered by an approved plan.

### Semantic goals

Retained goals and ownership constraints for the fresh implementation. Read
[the memory model](../../docs/memory-model.md) as the primary ownership reference.
The [source design](../history/unify-lambdas-design.md) preserves the remaining proposals
and decisions for review; it is not a completed implementation checklist.

#### Context

Tracked item: #255 Unify lambdas.

MML already has a lambda-shaped core. Top-level functions, local functions, lambda literals,
local `let`, statement sequencing, and direct lambda application are all represented with
`Lambda` somewhere in the pipeline.

The design goal is to make that regularity real across semantics and codegen:

- A lambda is a scope.
- Function-ness is not a separate semantic category.
- Closure materialization is a representation decision.
- Direct calls, closure values, stack environments, and heap environments are lowering choices
  derived from usage and lifetime.

This is the heart of the CPS-like core: `let`, sequencing, functions, and lambdas are all modeled
as lambda application.

#### Non-goals

Do not introduce a semantic split like `CallableLambda` vs `ScopeLambda`.

That split would encode the wrong abstraction. It would keep forcing later phases to ask whether a
lambda is "really" a closure or "just" scope machinery, when the intended model is that lambdas are
the scope machinery.

Also avoid making closure literals the center of the design. A closure is one possible
materialized representation of a lambda value, not the semantic identity of a lambda.

#### Core invariant

Every lambda introduces a scope with parameters and a body.

For every lambda, the compiler should be able to derive:

- the bindings introduced by the lambda parameters
- the free variables referenced by the lambda body
- the ownership relationship between the lambda and those free variables
- whether the lambda value must be materialized
- whether an environment object is needed
- whether that environment can stay stack-local or must be heap-backed
- whether a direct entry point can be used

Those facts may be optimized away for immediately applied lambdas, but they should not be modeled
as a different semantic kind of lambda.

#### Ownership compatibility constraints

Lambda unification must preserve the ownership rules currently implemented by the compiler. The
change may make those rules apply through a more regular lambda model, but it must not weaken or
skip them.

Current ownership states:

1. `Owned`: the binding owns a heap value and must be freed at scope end unless ownership escapes
   or moves away.
2. `Moved`: ownership has transferred away; later use of the binding reports `UseAfterMove`.
3. `Borrowed`: the binding is only borrowed; the current scope must not free it or move it into a
   consuming use.
4. `Literal`: the binding refers to static/literal data and is never freed as owned local state.
5. `Global`: the binding has module lifetime and is borrow-only in local ownership analysis.

##### Lambda values are ordinary unique values

A core idea of this unification: a lambda value participates in the same ownership rules as any
other value of its type class. Reasoning about closures stops being a parallel system.

- A **move-capturing lambda value** behaves as an owned heap value. Standard owned-heap rules
  govern transfer through `~` parameters, return-to-caller, struct-sink construction, rebind
  moves, and use-after-move.
- A **borrow-capturing lambda value** behaves as a borrowed value. Standard borrowed-value rules
  govern what it can satisfy: not a consuming parameter, not an owned return, not a struct-sink.
- A **non-capturing lambda value** has no owned environment. It is borrow-only at the ownership
  layer — there is nothing to free.
- A **captured borrowed value** remains owned by the enclosing scope.
- A **captured moved value** becomes unavailable in the enclosing scope.

How the lambda escapes (return, consuming-param transfer, struct sink, indirect store via HO
argument) is *not* a closure-specific concept. The ownership analyzer already discovers each of
those escape paths at the use site for any owned heap value. Lambda values use the same
machinery.

Struct construction is an ownership sink. Storing a function value into a struct field must apply
the same transfer rules as any other sink: a borrow-capturing closure cannot enter the struct,
a move-capturing closure transfers ownership into the struct, and a non-capturing/null-env function
value carries no owned environment to free.

Preserved regression from S9 inspection: a borrowed closure must not be laundered through a struct
field and later passed to a consuming function parameter. Direct arguments, aliases, return escape,
and struct construction must all reject the borrowed ownership transfer:

```mml
struct Holder { f: Int -> Int };

fn consume_fn(~f: Int -> Int, x: Int): Int =
  f x;
;

pub fn main(): Int =
  let seed = 10;
  let add_seed = { x: Int -> x + seed; };
  let holder = Holder add_seed;
  consume_fn holder.f 10;
;
```

The rules below remain authoritative. Items 17–25 specialize the generic rules in items 1–16
with closure-specific diagnostic phrasing; the underlying checks become instances of the generic
ownership pipeline, not parallel closure-only logic.

Rules to preserve:

1. Heap-typed owned bindings are freed at the end of the scope that owns them.
2. Free insertion remains CPS-shaped: bind the result, run the required frees, then return the
   preserved result.
3. Values returned from a function are not freed locally; ownership transfers to the caller.
4. Returning a borrowed value as an owned return type is rejected with `BorrowEscapeViaReturn`.
5. Allocating expressions are recognized through native `Alloc` effects, known allocating user
   functions, heap-field struct constructors, and move-capturing lambdas with non-empty captures.
6. Allocating call arguments that are not bound by the user are lifted into synthetic temporaries
   and freed after the call unless consumed by the callee.
7. Conditional expressions with mixed owned/non-owned branches keep a witness boolean and free only
   on the owned branch.
8. Conditional branches that move an outer owned value merge back to a moved outer state.
9. When a function returns a heap type and one conditional return branch is static/non-allocating,
   that branch is cloned so the caller consistently receives an owned value.
10. Consuming parameters move owned arguments into the callee.
11. Passing an already moved value to a consuming parameter remains a use-after-move error.
12. Passing a borrowed local binding to a consuming parameter is rejected with
    `BorrowedValuePassedToConsumingParam`.
13. A move caused by a consuming parameter must be the last use in the enclosing body; otherwise
    `ConsumingParamNotLastUse` is reported.
14. Partial application preserves consuming parameters. A supplied consuming argument moves
    at that application stage; remaining consuming arguments move when supplied later.
15. Constructor calls with consuming parameters auto-clone supported non-owned inputs such as
    string literals and globals; owned values move in without cloning.
16. Rebinding an owned heap value through local `let` moves ownership into the new binding when the
    source is an owned heap value without mixed-ownership witness state.
17. Borrow-capturing lambdas may borrow owned, borrowed, literal, or global bindings.
18. Borrow-capturing lambda values cannot be returned; escaping borrow closures remain rejected
    with `BorrowClosureEscapeViaReturn`.
19. Move-capturing lambdas move owned heap captures into their environment.
20. Capturing an already moved heap binding remains an error.
21. Capturing a borrowed heap binding into a move lambda remains an error.
22. Capturing a literal/static heap value into a move lambda uses the appropriate clone function and
    records a literal capture.
23. Borrow-lambda captures remain borrowed. Move lambdas can borrow their owned captures
    repeatedly or transfer them during a call-once invocation, with ownership and cleanup
    propagated to the outer callable.
24. Capturing function values must continue to honor the same ownership and escape restrictions as
    other owned values when a real environment is owned.
25. Non-capturing/null-environment function values must be handled explicitly so unification does
    not invent a heap ownership obligation where no environment exists.

Concrete ownership references:

- `tests/mem/borrow-capture.mml`: sibling local functions borrow the same heap binding and the
  outer owner remains responsible for cleanup.
- `tests/mem/closure-capture.mml`: repeated move-closure creation returns owned closure values
  whose environments must be freed.
- `tests/mem/closure-heap-capture.mml`: a move closure owns a captured `String` passed through a
  consuming parameter.
- `tests/mem/test_temporaries.mml`: anonymous allocating arguments and nested concat temporaries
  are lifted and freed after their consuming use.
- `tests/mem/mixed_ownership_test.mml`: conditional string results mix static and heap branches
  and require witness-based conditional cleanup.
- `tests/mem/cond-consume.mml`: one conditional branch consumes an owned value while the other
  borrows it; the outer ownership state and frees must remain correct.
- `mml/samples/mem/use-after-move.mml`: using a binding after it moves into a consuming parameter
  is rejected.
- `mml/samples/mem/consume-not-last.mml`: moving a binding into a consuming parameter before its
  last use is rejected.
- `mml/samples/mem/move-borrow-fails.mml`: borrowed local data cannot satisfy a consuming
  parameter.
- `mml/samples/mem/borrow-escape.mml`: returning borrowed heap data as an owned result is
  rejected.
- `mml/samples/mem/partial-consume.mml`: a PAP may be dropped with a consuming parameter
  still unapplied; no argument ownership has transferred for that parameter.


#### Semantic model

##### Lambda as scope

All of these are lambda scopes:

```mml
fn add(a: Int, b: Int): Int = a + b;
```

```mml
fn outer(a: Int): Int =
  fn inner(b: Int): Int = a + b;
  inner 1
;
```

```mml
let x = value;
rest
```

```mml
{ x -> x + 1 }
```

```mml
{ x -> x + a } 1
```

The parser and rewriters may keep using immediate lambda application to represent `let` and
sequencing. Later phases should preserve the conceptual model: a lambda scope applied to an
argument extends the current computation with a new binding.

##### Free variables and captures

Free-variable analysis should be phrased for lambda scopes, not closure literals.

For each lambda:

- parameters are local to the lambda
- references to bindings from an enclosing lambda scope are free variables
- references to module-level bindings are global references
- nested lambdas may require free variables to be propagated through enclosing lambdas

The output of this analysis should be usable by both ownership analysis and codegen. A lambda may
have free variables even when no runtime environment object is ultimately allocated.

##### Borrow and move

Borrow vs move is a relationship between a lambda scope and its free variables.

- Borrow capture means the lambda observes a binding owned by an enclosing scope.
- Move capture means the lambda receives ownership of the captured value.
- Non-heap values can still be copied or passed by value according to their normal type rules.

The move marker should affect capture ownership, escape legality, and environment construction. It
should not create a separate semantic class of lambda.

Top-level functions normally have no local free variables. That should fall out of resolution and
free-variable analysis, not from a special top-level function model.

### Partial-application ownership contract

Retained accepted PAP contract and regression obligations. This section specifies
required behavior; current implementation progress remains in Task Working Memory and
the migration handoff.

#### Problem

A partial application (PAP) stores already-applied arguments and, for Direct callables,
any trailing operands needed to call the original entry later. Heap-typed payloads are
not scalars: storing them in a PAP env creates a lifetime question.

Implicit cloning is not an acceptable answer. A PAP must not silently manufacture
ownership. If a PAP owns a heap payload, that ownership must come from an explicit move
in the source-level ownership model.

#### Core Rule

Each heap payload stored in a PAP env is either borrowed or owned.

- Borrowed payloads may be stored only when the PAP value cannot outlive the owner.
- Owned payloads may be stored only after an explicit ownership transfer.
- The compiler must reject cases it cannot prove safe.
- No implicit clone may be inserted to make an escaping PAP safe.

#### Parameter Ownership

PAP payload ownership follows the original function parameter.

- A non-consuming parameter borrows its argument.
- A consuming `~` parameter moves its argument.
- Partial application must preserve that distinction.

For an already-applied argument:

```mml
fn f(s: String, n: Int): Unit = ...;;
let p = f s;
```

`s` is borrowed because `f` borrows `s`. The PAP may use that borrow only while `s`
is alive.

```mml
fn f(~s: String, n: Int): Unit = ...;;
let p = f s;
```

`s` is moved because `f` consumes `s`. If partial application over this shape is
accepted, the PAP env owns `s` and its destructor is responsible for freeing `s` if
the PAP is dropped before the remaining arguments are supplied.

#### Escaping Borrowed PAPs

An escaping PAP must not contain borrowed heap payloads.

Rejected shape:

```mml
fn make(): Int -> Unit =
  let s = int_to_str 1;
  f s;
;
```

If `f` borrows `s`, then `f s` is a PAP containing a borrow of `s`. Returning that PAP
would let the borrow escape the scope that owns `s`.

Accepted shape:

```mml
pub fn main(): Unit =
  let s = int_to_str 1;
  let p = f s;
  p 0;
;
```

This is valid when the compiler can prove `p` does not escape the scope where `s` is
alive.

#### Consuming Parameters

The PAP creation slice preserves rejection when any remaining parameter is consuming.
The Author selected removing that restriction as a
[follow-up bug-fix step](#bug-supply-a-consuming-argument-after-pap-creation).

```mml
fn consume(a: Int, ~s: String): Unit = ...;;
let p = consume 1; // rejected: remaining parameter is consuming
```

This is separate from already-applied consuming arguments. An already-applied consuming
argument moves into the generated PAP environment:

```mml
fn consume_first(~s: String, n: Int): Unit = ...;;
let p = consume_first s; // explicit move into the PAP
```

The source binding is moved at PAP creation. If the PAP is dropped before full application,
the PAP env destructor frees the moved payload field. If the PAP is fully applied, the moved
payload is forwarded to the consuming callee and later PAP cleanup frees only the raw env.

#### Direct Callables

This model is not lambda-capture-specific. For Direct PAPs, the generated env stores
call payloads for a later entry call. The same payload rules apply:

- applied borrowed heap arguments may not escape their owner;
- applied moved heap arguments are owned by the PAP env;
- Direct trailing operands that are borrowed heap values may not escape their owner;
- Direct trailing operands that are explicitly owned by the PAP env must be freed by
  the PAP env destructor.

The fact that the source lambda or function itself does not escape is not sufficient.
The generated PAP value may still escape.

#### Implementation requirements

PAP creation must enforce ownership and lifetime without implicit cloning.

Required work:

- Remove implicit clone insertion from Direct PAP creation.
- Classify each PAP env heap payload as borrowed or owned.
- Reject escaping PAPs that contain borrowed heap payloads.
- Keep non-escaping borrowed heap PAPs valid when the lifetime proof is available.
- Preserve the existing rejection for partial application with remaining consuming
  parameters within the creation slice; the follow-up bug-fix step covers lifting it.
- Move already-applied consuming heap arguments into the PAP and make the PAP env
  destructor free those owned payload fields exactly once.

Initial conservative implementation may reject all heap-borrow PAP payloads until a
non-escape proof is wired in. That is preferable to hidden cloning.

#### Tests

Pin these cases:

- non-escaping PAP over borrowed heap argument is accepted when proven local;
- escaping PAP over borrowed heap argument is rejected;
- escaping Direct PAP over borrowed heap trailing payload is rejected;
- partial application with remaining consuming parameter remains rejected in the creation-slice
  checkpoint; replace this expectation with ownership coverage in the selected follow-up;
- moved already-applied heap arguments mark the source binding moved and the PAP env
  destructor frees the moved payload exactly once;
- no generated IR for PAP creation contains implicit heap clone calls.

## Plan (Approval Gate)

Preservation, borrowed returns, typed closure destruction, PAP creation, deferred consuming
arguments, owned PAP struct fields, and nested consuming-PAP repairs are complete.
Both stages of binding identity and local construction are complete and signed off.
Remaining work requires bounded approval before implementation.
The general branch audit is deferred.

## Implementation Checklist

The [Execution Checklist](#execution-checklist) owns the top-level sequence and statuses.
The sections below retain detailed scope, acceptance criteria, and internal implementation
steps. Checked items from the source branch remain historical evidence, not completion on
this branch.

### Intermediate construction step

#### Establish binding identity and local-construction invariants

- **Status:** complete; both stages verified, independently reviewed, and signed off.
- **Sequence:** construction prerequisite for further ownership changes is satisfied.
- **Problem and evidence:** binding identity, reference construction, wrapper typing, and
  index maintenance require a common contract across semantic rewrites. Focused regressions
  cover missing clone-helper parameter IDs, unresolved shadowed locals, and ownership wrappers
  carrying result types where function types are required.
- **Implementation landmarks:**
  [BindingIds.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/BindingIds.scala),
  [IdAssigner.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/IdAssigner.scala),
  and [LocalBindings.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/LocalBindings.scala).
- **Outcome:** one nested binding identity-generation policy and one coherent API for
  local construction, shared by initial ID assignment and subsequent rewrites as appropriate.
  Ordinary `FnParam`, `Ref`, `Lambda`, and `App` nodes remain the representation.
- **Invariants:** fresh bindings receive defined, distinct identities; references created
  for a binding resolve to that identity; rewrites preserving a binding preserve its identity;
  deliberately fresh bindings receive fresh identities and correctly rebound references.
  Construction preserves types and consuming contracts. Enclosing context is explicit,
  and responsibility for keeping the resolvables index consistent is documented.
- **Scope:** consolidate existing identity generation and local construction; migrate the
  affected consumers in PAP elaboration, expression rewriting, closure invocation, ownership
  temporaries/cleanup, and generated memory helpers. `BindingOwner` carries scope provenance;
  `BindingIdSupply` retains allocation history through the compilation.
- **Boundary:** preserve ordinary AST nodes and the existing scope interpretation. Mixed-ownership
  transfer failures involving allocated and literal branches require the separate ownership repair.
- **Acceptance:** verify shadowing, repeated generated names, reference resolution after
  rewriting/index refresh, preserved identities, and consuming-contract propagation through
  the affected paths. Run applicable compiler gates and independent review. Keep the known
  mixed-ownership failures explicit as separate baseline evidence.
- **Approved implementation:** readable scope-path IDs retained throughout one compilation;
  immutable allocation state shared by initial assignment and generated locals; phase-boundary
  index refresh; shared parameter/reference, binding, and cleanup construction. Cross-compilation
  ID stability is not required.
- **Delivery:** stage 1 covers identity allocation, allocating callers, and index consistency.
  Stage 2 covers binding/cleanup construction and type propagation.
- [x] **complete** — Stage 1: shared identity allocation, allocating callers, and index
  consistency (`dbd65d9`); signed off.
- [x] **complete** — Stage 2: shared binding/cleanup construction, type propagation,
  and remaining callers; verified and signed off.
- **Follow-up:** [Remove provenance heuristics from lambda scope analysis](lambda-scope-classification.md).
  That task addresses AST interpretation and reconciles the existing sequence-lambda item;
  it is separate from this construction step.

### Near-term bug-fix steps

These steps cover lambda lowering, PAP invocation/storage, and ownership-analysis bugs. Their
current statuses appear in the [Execution Checklist](#execution-checklist); each section
retains its reproduction, scope, and acceptance criteria.

#### Bug: supply a consuming argument after PAP creation

- **Status:** complete; signed off by the Author on 2026-09-10.
- **Original reproduction:** for `fn measure(extra: Int, ~text: String): Int = text.length + extra;;`,
  `let f = measure 10;` was rejected because `text` had not been supplied.
- **Expected behavior:** create `f`, then transfer an owned String when calling it. A PAP
  that retains all its stored captures can be called repeatedly with fresh consuming arguments.
  A PAP that transfers a stored payload still obeys the call-once rule.
- **Implementation scope:** remove the `ExpressionRewriter` and `PartialApplicationElaborator`
  rejection guards, preserve consuming metadata in generated remaining/eta-expanded parameters,
  and retain unapplied parameter contracts through `CallableValues` before staged elaboration.
  Existing argument ownership checks enforce those contracts.
- **Acceptance:** named and inline forms accept the deferred consuming argument; aliases,
  returned PAPs, and higher-order calls retain its consuming contract. The supplied String
  moves at invocation, borrowed inputs and later use of moved inputs are rejected, repeated
  calls with fresh arguments work when captures are retained, and payload cleanup occurs
  exactly once. Cover mixed stored/remaining consuming arguments and staged application.
  Replace the relevant rejection tests in `PapOwnershipTest` and other affected suites;
  add native sanitizer coverage and synchronize the language reference and memory model.
- **Reference:** [deferred consuming arguments](../../docs/memory-model.md#remaining-consuming-parameters).

#### Bug: store an owned PAP in a struct field

- **Status:** complete; implementation and review fixes committed at `2b55e82`, included
  in the recorded `origin/dev-lambda-unify` history. Broader review coverage remains limited
  as documented in [completed-slice evidence](#completed-slice-evidence).
- **Original reproduction:** with `struct Holder { f: Int -> Int };` and ordinary two-argument
  `add`, `let p = add 1; let holder = Holder p;` was rejected by the PAP constructor-argument guard.
- **Expected behavior:** construction transfers the owned function environment into `holder`;
  `holder.f 2` produces `3`, and destruction of the holder releases its owned environment.
  The original `p` binding is moved. Borrowing PAPs must not enter owning fields.
- **Scope and sizing:** larger than the parameter step; integrate with existing function-field
  ownership and destruction work, rather than adding a separate PAP storage mechanism.
  Inspect constructor ownership, function-bearing struct classification, field value flow,
  nested destructors, and consumption through field access. Removing the guard alone is unsafe.
- **Acceptance:** owned scalar and heap-payload PAPs survive transfer and return inside a holder;
  repeated calls work for reusable PAPs. A call that consumes a stored PAP prevents a second
  call and avoids duplicate environment/payload cleanup when the holder is destroyed.
  Preserve borrowed-field rejection and non-capturing function support; cover nested holders,
  unused holders, moves, aliases, and calls. Do not synthesize implicit clones of function
  environments. Restore the relevant ignored ownership/destruction regressions, add native
  sanitizer coverage, and update the documented limitation when support is verified.
- **Reference:** [field ownership contract](../../docs/memory-model.md#paps-in-struct-fields).

#### Bug: nested consuming-PAP calls

- **Status:** complete; review follow-up verified and signed off by the Author's finish
  request on 2026-09-12.
- **Original reproduction:** `println (int_to_str (consuming 0))` incorrectly required ownership
  even when `consuming` owned its payload and was called once. A separate result binding worked.
- **Approved scope:** analyze argument ownership once, preserve source evaluation order and
  cleanup, reject reuse and invalid borrowing, add semantic/native regressions, update docs,
  and run compiler gates plus independent review.
- **Acceptance:** both forms in `mml/samples/pap-nested-consuming.mml` print `3`; later calls
  and aliases remain rejected after consumption. Nested heap results, struct fields,
  conditional calls, argument order, and exactly-once cleanup pass sanitizer coverage.

#### Preserve counters and argument expressions across ownership analysis

- **Status:** complete; verified, reviewed, and signed off.
- **Approved slice:** counter-continuity regressions, counter threading through branches and
  lambda bodies, task reconciliation, required compiler verification, and independent review.
  The counter slice is complete and signed off; its local commit is authorized.
  Expression-shape implementation is approved: enforce the existing single-term contract,
  preserve malformed syntax and independent diagnostics, align ownership classification and
  value availability, add regressions, document the contract, and run compiler handoff gates.
  Expression-slice signoff, scoped commit, and push to `origin/dev-lambda-unify` are authorized.
- **Source:** [OwnershipAnalyzer.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala),
  `analyzeCond`, `analyzeLambda`, `analyzeArgument`, and `prepareConsumingArgument`.
- **Counter scope:** generated condition/temporary names must remain distinct through branches,
  lambda bodies, and subsequent arguments. The shared binding-ID supply and temporary-name
  counter both advance across these traversals; branch/body ownership states remain scoped.
- **Expression contract:** normalized expressions contain one term. Retained empty or multi-term
  syntax has no result ownership; argument rewrites must preserve it and report its unsupported
  shape while continuing independent diagnostics.
- **Expected behavior:** preserve generated-name counter progression through branches, nested
  lambdas, and subsequent arguments while keeping branch/body ownership state properly scoped.
  Argument analysis must honor an explicit expression-shape contract and must never silently
  drop terms or their effects.
- **Implementation plan:**
  - [x] Add focused regressions for counter continuity through both conditional branches,
    lambda bodies, and subsequent arguments; check distinct generated names and binding IDs.
  - [x] Reconcile the shared identity allocator with condition/temporary name progression;
    address any remaining gaps independently of ownership-state isolation and merging.
  - [x] Enforce the single-term contract in argument preparation, ownership classification,
    consumption, binding transfers, and result-origin queries. Keep malformed syntax unavailable
    through aliases and preserve its children for diagnostics.
  - [x] Add expression-shape and recovery coverage, plus native verification of valid argument
    statement order and exactly-once evaluation through both conditional branches.
  - [x] Reconcile the lambda-decomposition follow-up with
    [Eliminate unnecessary AST field decomposition](preserve-ast-nodes-in-helper-apis.md).
    That task is complete; the ownership helpers accept whole AST nodes and the two markers
    are absent. No open QA miss remains to add for those markers.
  - [x] Run applicable compiler gates and focused independent review for all changes in this
    subtask. Compiler gates, QA, tracking consistency, and independent review pass for both slices.
- **Counter evidence:** before the counter repair, the conditional-branch regression produced
  nine generated locals with five distinct names; the nested-lambda regression produced seven
  with four distinct names. Both retained distinct binding IDs. The existing scoped-argument
  regression passed. Log: `/tmp/mml-counter-before.log`. All three regressions pass with the
  repair. Formatting, lint, and the full suite pass: 736 compiler-library tests and nine CLI
  tests, with 51 existing ignores (`/tmp/mml-counter-gates.log`). Smoke checks pass 8/8
  (`/tmp/mml-counter-smoke.log`). Local installation passes (`/tmp/mml-counter-publish.log`);
  clean benchmark builds pass for all 12 MML programs (`/tmp/mml-counter-bench-clean.log`,
  `/tmp/mml-counter-bench.log`). ASan+LSan passes 43/43 fixtures at `-O0` on macOS arm64
  (`/tmp/mml-counter-memory.log`). No performance comparison or Linux validation was run.
  QA compliance, focused tracking checks, and fresh independent code review pass with no
  actionable findings. The reviewer inspected the diff, allocation/cleanup paths, regression
  failures, and passing reports; no candidate finding required a claim verifier. The new
  fixtures establish semantic counter continuity, not isolated native execution behavior.
- **Expression decision:** `ExpressionRewriter` normalizes successful source expressions and
  rejects dangling terms; the parser represents statement sequences as nested lambda
  applications. Ownership operations require a single term and never give flat lists sequencing
  semantics. Malformed expressions receive a phase-labelled diagnostic; existing invalid-node
  payloads retain independent diagnostics without another shape error. Source information and
  type annotations remain attached to preserved syntax. No source-level loss of effects has
  been reproduced.
- **Expression verification (2026-09-22, macOS arm64, Clang 23.1.1):** formatting, lint,
  and the full suite pass: 759 compiler-library tests and nine CLI tests, with 51 existing
  ignores (`/tmp/mml-expression-final-gates.log`). All 23 focused shape/recovery regressions
  pass, including complete-term and explicit-ascription preservation, qualifier traversal,
  unavailable aliases, genuine child/continuation diagnostics, and observational call-boundary
  validation (`OwnershipExpressionTests` in the full suite). The native
  `tests/mem/argument-expression-order.mml` fixture passes at `-O0` in the full-gates log.
  Smoke checks pass 8/8 (`/tmp/mml-expression-final-smoke.log`); local installation passes
  (`/tmp/mml-expression-final-publish.log`). Clean builds pass for all 12 MML benchmarks
  (`/tmp/mml-expression-final-bench-clean.log`, `/tmp/mml-expression-final-bench.log`).
  ASan+LSan passes 44/44 fixtures at `-O0` (`/tmp/mml-expression-final-memory.log`).
  No performance comparison or Linux validation was run. Scala compilation is warning-free;
  forked CLI runs emit the existing JDK `sun.misc.Unsafe` runtime deprecation warning.
  Independently confirmed review findings are resolved: qualifier traversal reaches retained
  malformed children even when an independent continuation supplies an available result, and
  call-boundary lifetime validation observes reference state without repeating qualifier effects.
  The latter regression proves typed phase-input behavior; equivalent grouped source syntax
  is not established. Fresh narrow re-review reports no actionable findings. All compiler gates,
  focused QA, tracking consistency, links, and diff checks pass. Expression-slice signoff is complete.
- **Boundary:** the allocated/static consuming-transfer and return failures belong to
  [conditional-ownership hardening](conditional-ownership-witnesses.md). This subtask addresses
  counter propagation, expression preservation, and the QA cross-reference.

#### Bug: preserve mixed ownership through consuming transfers

- **Status:** planned.
- **Implementation task:** [Make conditional ownership explicit in ownership operations](conditional-ownership-witnesses.md).
  Repair this bug together with the shared handling of witnesses across cleanup, consumption,
  and returns, as one ownership workstream within Unify lambdas.
- **Problem:** a scoped local that selects an allocated String or a literal can reach a
  consuming parameter with incorrect cleanup on both paths. The allocated value is freed
  before the consuming call, then freed again by the callee. The literal reaches the callee
  without owned storage and is incorrectly freed there.
- **Expected behavior:** preserve the selected branch's ownership through the scoped result
  and consuming boundary. Transfer allocated storage once and suppress the former owner's
  cleanup after transfer. Literal storage must use the existing consuming-boundary clone
  contract; borrowed heap storage must remain rejected.
- **Acceptance:** add semantic and native regressions for both branches of the reproducer
  below, named and inline consuming calls, and scoped aliases. Verify exactly-once evaluation,
  valid transfer, cleanup only by the final owner, and borrowed-source rejection. Both native
  branches must return exit code 0 and pass ASan+LSan without double-free, invalid free, or leak.
- **Relevant implementation:** the witness-based cleanup in
  [OwnershipAnalyzer.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala),
  particularly `analyzeLambdaApplication`, `prepareConsumingArgument`, and `handleConsumingParam`.

Reproducer preserved from the independent verifier's `true.mml`:

```mml
fn take(~text: String): Int = text.length;;
fn check(flag: Bool): Int =
  take (if true then let value = if flag then int_to_str 123; else "abc"; ; value; else "def";);
;
pub fn main(): Int = check true - 3;;
```

For the literal branch, change only `check true` to `check false` in `main`.
Compile each variant with `mmlc -s -O 0`, then run with
`ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1`.

Evidence collected on 2026-09-11, macOS arm64:

- **Allocated branch (`true`):** exit 134; ASan reports `attempting double-free`.
  The first free is in `check`; the second is `take -> __free_String -> free`.
  Emitted IR shows witness-guarded `__free_String` before the result reaches `take`.
- **Literal branch (`false`):** exit 134; ASan reports a BUS during deallocation through
  `take -> __free_String -> free`. IR passes the literal-backed String without a clone.
- A fresh independent verifier reproduced the failures; the root agent subsequently reran
  both saved binaries and confirmed the same ASan failures. Scratch sources, binaries, and
  IR remain under `/tmp/scoped-witness-verifier/`; the source and observed results above are
  the durable evidence and do not depend on retaining that temporary directory.
- The reviewer excluded this from the four-repair re-review because the witness-cleanup
  block already exists at commit `234b6e1`. This attribution is based on source comparison;
  the exact reproducer was not run against a rebuilt older compiler. The bug remains open.
  The passing 41-fixture harness does not include this reproducer.

#### Bug: consume an owned PAP captured by a move lambda

- **Status:** complete; signed off.
- **Approved scope:** infer consumption through direct calls and local moves of owned captures,
  preserve reusable borrowing, propagate the outer callable contract, and verify destruction,
  rejection cases, samples, and documentation under the compiler gates.
- **Regression:** rejecting invocation of an owned PAP capture with
  `Calling this function consumes its environment and requires ownership`. Treating every
  capture as borrowed inside a move-lambda body also prevents moving the capture into a local.
- **Sample:** [returned-lambda-pap.mml](../../mml/samples/returned-lambda-pap.mml), with the
  second-call rejection probe commented out, prints four
  `4`s: an ordinary function returns an owning PAP; move lambdas call a captured PAP directly
  and through a local move; a returned move lambda returns its PAP capture to the caller.
- **Implementation:** `CaptureTransfers` discovers capture ownership sinks by resolved ID.
  PAP elaboration stabilizes the call-once contract through source move lambdas and callable
  flow. Call-once invocations own all non-borrowed captures and clean up those they retain
  after disarming the environment destructor. Result ownership also follows immediately
  applied scopes, preserving owning PAPs returned by scoped factory expressions.
- **Expected behavior:** a move lambda can transfer its owned PAP capture into a local and
  consume it. Direct invocation must obey the same ownership rules. Consuming the capture
  makes the enclosing lambda call-once; merely owning and borrowing captures remains reusable.
- **Fix scope:** carry capture transfers through ownership analysis, callable consumption
  contracts, aliases, and environment destruction. Reconcile ownership constraint 23 above
  and the memory model's blanket borrowed-capture wording with consumption of owned move
  captures. Preserve borrowing semantics for borrow lambdas and reusable move lambdas.
- **Acceptance:** both direct and locally rebound calls compile and produce `4` after the
  factory returns. Returning the owned PAP also works and preserves its call-once contract
  through aliases and higher-order calls. Cover both an ordinary function returning its
  owning PAP and a returned move lambda returning its PAP capture, including a scoped
  factory expression. Reject reuse of the moved capture, its aliases, and the consumed
  outer lambda, including through higher-order calls. Destroy the String and both environments
  exactly once on invocation or when dropped uncalled, including conditional consumption;
  do not clone function environments. Add semantic and native sanitizer regressions.
  The [borrowing counterexample](../../mml/samples/returned-lambda-pap-borrow-fail.mml)
  must retain its `Cannot return borrow-capturing closure` rejection.
- **Verification (2026-09-14, macOS arm64):** formatting and lint pass; full suite
  **690 passed / 51 existing ignores** (681 library, 9 CLI). All seven required compiler
  smokes pass, and the compiler is published locally. All seven MML benchmarks build.
  The memory harness passes **42/42 ASan+LSan**, including 1,000 iterations of the new capture
  and return cases. The sample prints four `4`s under ASan; the borrowing sample retains
  its escape and invocation ownership errors. The 28 focused semantic regressions cover
  both return forms, call-once propagation, aliases, shadowing, reuse, and borrowing.
  Logs: `/tmp/mml-move-capture-review-fix-gates.log`, `/tmp/mml-move-capture-return-forms.log`,
  `/tmp/mml-move-capture-review-fix-memory.log`, `/tmp/mml-move-capture-review-fix-benchmarks.log`.
  Independent review identified and verified incorrect consumption of noncapturing function
  aliases. Transfer inference excludes those non-owning values; repeated-invocation and
  owning-scalar-PAP regressions pass. Fresh narrow re-review finds no actionable issues;
  it also independently runs the expanded native fixture at `-O0` with ASan and verifies
  both static-wrapper calls and later cleanup in IR. Final native gates pass.
  QA compliance and focused tracking checks pass.
  Linux sanitizer validation remains separate.
- **Coverage extension (2026-09-14, macOS arm64):**
  [MoveCaptureConsumptionTest](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/MoveCaptureConsumptionTest.scala)
  has **64 passing cases**. Six PAP-return forms cover consuming invocation, repeated-call
  rejection, alias/higher-order reuse rejection, and the consuming-parameter requirement:
  ordinary direct return, ordinary local return, local allocation, captured-PAP return,
  captured-PAP return through a local, and PAP construction inside the returned lambda.
  Borrowed inputs and a borrowing outer lambda have explicit rejection cases; each capture
  consumption path also checks the outer lambda's call-once contract.
  [The memory fixture](../../tests/mem/move-capture-consuming-pap.mml) exercises local PAP
  returns, allocation, invocation-time PAP construction, aliases, higher-order calls,
  reusable borrowing, and scalar-PAP transfers. It drops outer lambdas uncalled and returned
  PAPs unused, repeating the checks 1,000 times. Formatting, lint, and the full suite pass:
  **726 tests / 51 existing ignores**; memory harness **42/42 ASan+LSan**.
  Direct alias-call regressions test call-once preservation separately from the explicit
  ownership transfer through a consuming higher-order parameter.
  Independent review and narrow re-review pass; the re-review also runs all 15 alias tests.
  Logs: `/tmp/mml-pap-coverage-final-suite.log`, `/tmp/mml-pap-coverage-memory.log`.

#### Bug: preserve valid LLVM and TCO for nested capturing recursion

- **Status:** complete.
- **Approved repair plan:** use named registers for hoisted closure storage; preserve local
  binding identity and share recursive-lambda compilation between ordinary and loopified
  scopes; detect tail recursion throughout nested bodies and conditional branches. Add
  regressions for LLVM validity and loopification, then verify runtime results, closure
  lifetimes, performance, compiler gates, and independent review.
- **Smoke and benchmark integration:** add a nested TCO sample, copy all smoke samples to
  `tests/smoke/`, provide a sequential source-compiler harness, and include the nested matrix
  benchmarks in i-j-k and i-k-j order in the MML build and matrix timing targets. The benchmark
  default is `-O3` on all platforms; `BENCH_RUNS` overrides measured runs without changing
  each target's default count.
- **Reproducer:** [mat-mul-nested.mml](../../benchmark/mat-mul-nested.mml) nests
  `mat_mul_i(i)`, `mat_mul_j(j)`, and `mat_mul_k(k, acc)` inside `mat_mul`.
  The helpers capture matrices, dimensions, and enclosing loop indices.
  Build with TCO enabled:

  ```sh
  sbtn "run -I -b benchmark/build -O1 -o benchmark/bin/matmul-nested-mml benchmark/mat-mul-nested.mml"
  ```

- **Observed failure (2026-09-15, Darwin arm64):** both the installed compiler and the
  source build emit invalid numeric SSA ordering in `mat_mul_i`. Its entry block begins
  with `%11 = alloca %struct.__closure_env_1`, followed by
  `%2 = getelementptr %struct.__closure_env_0, ptr %1, i32 0, i32 0`.
  `llvm-as` rejects the latter with `instruction expected to be numbered '%12' or greater`.
  Moving the nested declarations outside the `if` branches still fails.
- **Diagnostic control:** `--no-tco -O1` compiles and runs with trace checksum `381460`,
  matching the original matrix benchmark. Disabling TCO is not an acceptable fix or
  benchmark configuration. The nested source participates in `make -C benchmark mml`,
  `bench-matmul`, and `bench-matmul-time` with the normal optimization flags and TCO enabled.
- **Confirmed causes:** closure-environment allocation hoisting emits later-numbered storage
  before capture loads. Loopified pre-statements discard the recursive binding parameter,
  producing an undefined `@mat_mul_j`; renaming numeric registers in a scratch IR copy exposes
  that second assembler failure. Tail-recursion detection does not traverse nested value-lambda
  bodies or conditional branches, leaving eligible inner helpers on ordinary recursion.
- **Investigation and fix:** reduce the reproducer with TCO enabled, trace closure allocation
  and tail-recursive emission, and fix the responsible boundary while preserving capture
  values and environment lifetimes. Check recursive binding resolution and loopification
  for every nested helper, not just the first LLVM rejection.
- **Acceptance:** add a regression that fails on the baseline compiler and assembles/verifies
  generated LLVM. Verify eligible nested tail recursion becomes loops with TCO enabled;
  successful execution alone does not establish this. Build and run the nested benchmark
  with normal flags, match checksum `381460`, validate closure lifetimes with sanitizers,
  and compare performance with the original. Complete required compiler checks and
  independent review before handoff.
- **Implementation:** named entry-storage registers preserve LLVM ordering; loopified
  statements retain `FnParam` identity and share recursive binding compilation with ordinary
  scopes. Detection traverses nested bodies and conditionals. Both capturing and non-capturing
  local loop entries retain a self closure for calls that remain ordinary recursion.
  Ordinary calls accept named iteration-local borrows; closures carried across a loop back
  edge remain rejected.
- **Verification (2026-09-15, Darwin arm64, LLVM 23.1.1):**
  - Six codegen regressions assemble with `llvm-as` and check loop structure, including
    nested captures, branch-local helpers, synchronous higher-order borrows, mixed tail/ordinary
    recursion, and `--no-tco`. A seventh case checks semantic rejection of a borrowed closure
    returned through a higher-order identity function; existing coverage checks direct back-edge
    rejection.
    Before the detector repair, two of three initial cases failed loopification checks;
    the native stress regression also fails LLVM assembly on the baseline installed compiler.
  - `scalafmtAll`, `scalafixAll`, the full test suite (**733 passed, 51 existing ignored**),
    and local publishing pass. The JVM emits its existing
    `sun.misc.Unsafe::objectFieldOffset` deprecation warning; Scala compilation is warning-free.
  - `./tests/smoke/run.sh all` passes **8/8** using the source compiler. Its copies match the
    seven original samples plus `nested-tco.mml`, which checks checksum `6048`. Shell syntax,
    failure propagation, continued execution after failure, run/compile modes, and invalid
    arguments are checked. The harness replaces the individual smoke commands in the coding
    rules and tool guide.
  - `make -C benchmark clean` and `make -C benchmark mml` pass. The nested benchmark builds
    with the normal Apple Silicon `-O1` flags and returns checksum `381460`. Its raw IR passes
    `llvm-as` and `opt -passes=verify`; all three nested helpers have back edges and no
    loop-body allocations.
    The Makefile includes the nested executable in the default MML build and both matrix
    comparison targets; `make -n` checks their command lists.
  - The full nested benchmark returns `381460` under `-s -O0` with ASan/LSan enabled.
    `./tests/mem/run.sh all` passes **43/43** at `-O0`, including the new nested-capture stress
    case, zero/one/many iteration results, mixed tail/ordinary recursion, and 10,000 synchronous
    higher-order borrows.
  - A 15-run `hyperfine` comparison after three warmups measures 25.1 ms mean for the original
    and 23.0 ms for the nested benchmark. The original has an outlier up to 50.3 ms; these
    measurements do not establish a speedup.
  - Final gate logs: `/tmp/mml-nested-tco-final-gates.log`,
    `/tmp/mml-nested-tco-final-smoke.log`, `/tmp/mml-nested-tco-final-publish.log`,
    `/tmp/mml-nested-tco-final-benchmarks.log`, and `/tmp/mml-nested-tco-final-memory.log`.
    Full matrix sanitizer and timing evidence: `/tmp/mml-nested-tco-benchmark-asan.log` and
    `/tmp/mml-nested-tco-performance.json`.
- **Review:** independent review confirmed rejection of valid synchronous higher-order borrows
  exposed by nested TCO detection. The validator accepts named borrowed call arguments, with
  positive LLVM/runtime coverage and negative escape coverage. Fresh narrow re-review reports
  no actionable findings and independently runs the original reproducer under `-s -O0`.
  Focused QA and tracking consistency checks pass.
- **Finish verification (2026-09-16, Darwin arm64, LLVM 23.1.1):** formatting and lint pass;
  the full suite passes **733 tests with 51 existing ignored**; source smoke checks pass
  **8/8**; local publishing and a clean MML benchmark build pass; ASan/LSan checks pass
  **43/43**. All six flat, nested, and checked matrix variants return checksum `381460`
  with the default `-O3` build. Smoke copies match their samples and shell syntax passes.
  The formatter also removes one extra blank line in `ExpressionRewriter.scala`.
  Logs: `/tmp/mml-nested-tco-finish-checks.log`, `/tmp/mml-nested-tco-finish-smoke.log`,
  `/tmp/mml-nested-tco-finish-publish.log`, `/tmp/mml-nested-tco-finish-benchmarks.log`, and
  `/tmp/mml-nested-tco-finish-memory.log`. Comparative timing evidence is in the
  [checked matrix report](../../benchmark/results/2026-09-16-safe/Readme.md).
- **Finish review:** fresh independent review reports no actionable findings. It compiles
  and runs the nested-capture stress test under `-s -O0` with ASan/LSan, assembles and verifies
  its raw LLVM, and checks nested back edges, entry storage, capture writes, smoke copies,
  shell syntax, and benchmark integration with `BENCH_RUNS=7`. Focused QA, tracking review,
  local links, and diff checks pass.
- **Signoff:** nested capturing-recursion repair, smoke harness, and benchmark integration
  are complete and signed off with local commit authorization.
  Target execution evidence is Darwin arm64 only.

#### Bug: continue elaboration after independent errors

- **Status:** complete; verified, reviewed, and signed off.
- **Approved plan:** add regressions for independent member and statement errors; replace
  module-wide elaboration skipping with local recovery boundaries; preserve independent
  diagnostics while downstream phases recognize unavailable contracts; verify CLI and LSP
  diagnostics and run compiler checks and reviews.
- **Priority:** urgent; follows the nested capturing-recursion TCO repair. Module-wide
  abandonment of elaboration violates the compiler's error-accumulating architecture.
- **Baseline reproducer:** in [returned-lambda-pap.mml](../../mml/samples/returned-lambda-pap.mml),
  uncommenting the second `plain_pap 1;` statement produced five errors: the legitimate
  `statement` expected `Unit`, got `Int` mismatch, three false borrowed-capture errors
  for `consuming`, and one false borrowed-return error. The expected call-once reuse
  diagnostic was absent. The probe is commented out in the runnable sample.
- **Controls (2026-09-14):** replacing that statement with `1;` produces the same four
  false ownership errors alongside the type mismatch. Replacing it with
  `let second = plain_pap 1;` produces only the correct use-after-move error.
  Logs: `/tmp/mml-returned-pap-reuse-errors.log`, `/tmp/mml-pap-unrelated-type-error.log`,
  `/tmp/mml-pap-reuse-bound.log`.
- **Baseline cause:** `PartialApplicationElaborator.stabilizeCaptures` returned the entire state
  unchanged when `state.hasErrors`. An unrelated type error prevented valid
  PAPs and move lambdas from acquiring their capture and callable contracts.
  `SemanticStage` ran `OwnershipAnalyzer` on the unelaborated tree.
  The demonstrated cascade concerns PAP/capture ownership; the module-wide recovery
  defect is broader than ownership-specific input errors.
- **Fix scope:** establish recovery boundaries so elaboration continues through valid
  independent members and expressions while preserving accumulated diagnostics. Represent
  failed or incomplete regions explicitly enough that downstream phases can recognize
  missing prerequisites and avoid inventing secondary errors. Preserve capture ownership,
  return ownership, and call-once contracts in successfully elaborated regions.
  Globally skipping subsequent phases would discard independent diagnostics; blindly
  removing the elaboration guard would leave invalid input handling unresolved.
- **Acceptance:** add regressions for an unrelated error in another member and within a
  statement chain, including the three variants above. Valid PAP regions must still be
  elaborated; independent genuine errors must accumulate; unavailable prerequisites must
  not cause false capture/return diagnostics. Verify CLI and LSP diagnostic paths. The
  bare `Int` statement remains a real `Unit` mismatch, and the locally bound second call
  remains a real use-after-move error. Investigate broader application-chain/typechecker
  symptoms separately unless a reproducer establishes the same recovery cause.
- **Implementation:** application mismatches retain typed operands in `InvalidExpression`.
  `ValueAvailability` propagates unavailable results through binding identities and aliases;
  callable flow, PAP elaboration, capture contracts, closure layouts, and ownership checks
  recognize those boundaries. Valid operands and statement continuations remain analyzable.
  Editor queries and semantic highlighting retain the preserved expressions.
- **Verification:** formatting and lint passed; full suite: 734 compiler tests and 9 additional
  tests passed, 51 existing ignores. Recovery tests cover all three reproducer variants,
  independent members, unavailable aliases, and independent argument/continuation diagnostics.
  LSP tests verify diagnostic messages, ranges, and tokens within a failed statement.
  Smoke: 8/8 passed. Local compiler installation and all 11 MML benchmark builds passed.
  CLI controls report only the expected statement mismatch or use-after-move diagnostic.
  ASan+LSan: 43/43 passed. The unmodified sample runs and prints `4` on four lines.
  QA enforcement, tracking consistency, and independent code review found no actionable issues.
  The reviewer additionally checked six isolated malformed-input CLI probes. Native checks
  cover this macOS host; Linux sanitizer validation remains separately deferred.
- **Signoff:** complete for the recovery repair. Local commit authorized; push is not authorized.

### Remaining lambda implementation

- **Status:** in_progress; ignored-test restoration is the active bounded slice.
- Complete the [semantic goals](#semantic-goals) and reconcile the remaining preserved
  regressions against the agreed model in bounded, reviewed slices.
- The [scope-analysis follow-up](lambda-scope-classification.md) addresses provenance
  heuristics separately from binding construction. The
  [salvage sequence](../history/unify-lambdas-salvage.md#suggested-restart-sequence) and
  [migration inventory](unify-lambdas-migration.json) provide evidence for selecting further
  slices; neither supplies implementation approval or current completion status.

#### Restore ignored regressions

- **Status:** in_progress; eleven cases enabled, 40 pending.
- **Approved bounded slice:** investigate the nine placeholder-based materialization cases;
  restore six supported cases as codegen regressions with unchanged source fixtures, and
  prepare compiler-repair plans for the other three. The separate nullary immediate-application
  rejection remains outside this slice. Compiler implementation changes are not approved.
- **Inventory:** [Ignored regression audit](unify-lambdas-ignored-tests.md) accounts for all
  51 runner-discovered ignored tests by suite and name. Reconcile this inventory after each
  bounded compiler slice; passing ignored cases must not wait for the final branch audit.
- **Audit (2026-09-23):** temporary suites execute the existing assertions unchanged:
  **four pass, 47 fail**. Four stale ignores are removed without assertion changes:
  alias-typed allocating-local cleanup; scalar-return ownership classification; consuming-use
  error equivalence; and loopified lambda-parameter shadowing. The borrowed-PAP escape case
  produced the intended rejection with an obsolete diagnostic-name assertion.
- **Plan:**
  - [x] Execute and account for every ignored case; distinguish stale ignores, placeholder
    helpers, diagnostic changes, representation assumptions, and observed compiler failures.
  - [x] Re-enable the four unchanged passing cases and remove their stale reason comments.
  - [x] Adapt the borrowed-PAP escape case to a typed `BorrowClosureEscapeViaReturn` assertion
    using existing semantic helpers, then re-enable it.
  - [x] Replace six materialization placeholders with emitted-IR assertions and unchanged fixtures.
  - [ ] Restore or replace the other eight placeholder-based cases, including the three
    [materialization repairs](unify-lambdas-ignored-tests.md#materialization-repair-plans);
    investigate the separate nullary immediate-application rejection.
  - [ ] Reconcile 29 failing IR/lowering assertions and two heap-alias cases with the agreed
    semantics. Assign each to a bounded repair or an explicitly approved replacement;
    historical Direct-entry names and representation choices are not implementation mandates.
  - [ ] Re-enable each case alongside its repair, preserve its semantic intent, run the affected
    suites and required gates, and update the inventory with execution and review evidence.
  - [ ] Before migration signoff, account for every original case as enabled and passing or
    explicitly approved for replacement/retirement with linked coverage and rationale.
    Any remaining deferral requires an explicit scope decision and tracked follow-up.
- **Restoration verification (2026-09-23):** formatting and lint pass; full suite passes
  763 library tests and nine CLI tests, with 47 ignored. All four restored cases execute and pass.
  Test assertions and compiler implementation are unchanged
  by this restoration. Independent review reports no actionable findings; focused QA and
  tracking checks pass. The four-case restoration is signed off; commit and push are authorized.
- **Borrowed-PAP restoration (2026-09-24):** the unchanged source program is enabled with a
  typed `BorrowClosureEscapeViaReturn` assertion through `semState`.
  `sbtn 'scalafmtAll;scalafixAll;test'` passes: 764 library tests, nine CLI tests,
  46 ignored, no warnings.
  QA and focused tracking checks pass; independent review reports no actionable findings.
  Smoke, publishing, benchmarks, and memory runs are not applicable to this test-only change;
  compiler implementation and the embedded source program are unchanged.
  Subtask complete and signed off. Local commit is authorized; push is not authorized.
- **Materialization restoration:** six cases have emitted-IR replacements with unchanged
  source fixtures. Formatting, lint, 770 library tests, and nine CLI tests pass, with 40 ignores
  and no warnings. QA, focused tracking checks, and independent review pass.
  [Evidence and repair plans](unify-lambdas-ignored-tests.md#materialization-restoration-evidence)
  record the three pending compiler repairs. The six-case slice is complete and signed off;
  local commit is authorized. Compiler implementation and push are not authorized.
- **Acceptance:** no unexplained or stale ignores; no placeholder counted as restored;
  no weakened assertion merely to obtain a pass. Track undiscovered declarations separately
  from the 51 audited cases. Further assertion changes and compiler fixes require their
  bounded plans; restoring eleven tests does not complete the remaining lambda implementation.

### Later-stage documentation

- **Status:** planned.
- [ ] Adapt the [PAP ownership tutorial](../../mml/samples/pap-ownership.mml) into the
  [language reference](../../docs/language-reference.md) at a later stage of the lambda work.
  Preserve its concrete, step-by-step explanation of owning, borrowing, and consuming PAPs,
  following values through creation, calls, and cleanup.

## Verification

The [elaboration recovery repair](#bug-continue-elaboration-after-independent-errors) has
passing formatting/lint, **734 compiler tests and 9 additional tests / 51 existing ignores**,
**8/8 smokes**, local compiler publication, **11 MML benchmark builds**, and **43/43 macOS
ASan+LSan cases**. QA and independent code review are complete; the repair is signed off.
These results do not resolve the separate mixed-ownership reproducer or establish Linux
sanitizer coverage. General branch audit, final task signoff, and Linux sanitizer validation
remain pending or separately deferred.

Completed slices retain their own results and review limits in
[completed-slice evidence](#completed-slice-evidence) and the
[migration evidence](#migration-evidence). Historical test counts describe those checkpoints.

## Risks / Notes

Migrated from the former tracker on 2026-09-10. Migration preserves pending scope;
it does not establish implementation progress, approval, or completion.

Preserve the [salvage record](../history/unify-lambdas-salvage.md), [migration JSON](unify-lambdas-migration.json), test evidence,
and source-branch history. Current design material is retained in this task; superseded plans and reviews
are in `context/history/` for Author review. No compiler slice is authorized by that port.

## Signoff

- Workstream signoff: complete for preservation, borrowed returns, typed closure destruction,
  PAP creation, deferred consuming arguments, owned PAP struct fields, nested consuming-PAP
  repairs, both binding identity and local-construction stages, the nested
  capturing-recursion TCO repair with its smoke and benchmark integration, elaboration
  error recovery, counter preservation through conditional branches and nested lambdas,
  argument-expression preservation, restoration of four unchanged passing regressions,
  the typed borrowed-PAP escape regression, and six materialization regressions.
- Tracked item completion: pending; open work remains in the
  [Execution Checklist](#execution-checklist).
- Commit and push authorization: expression preservation, the ignored-test inventory and plan,
  four restored regressions, and their completion records; push to `origin/dev-lambda-unify`.

## Task Working Memory

- **Branch:** `dev-lambda-unify`.
- **Implementation:** [Binding identity and local construction](#establish-binding-identity-and-local-construction-invariants)
  is complete and signed off. Stage 1 is committed at `dbd65d9`; stage 2 includes the shared
  construction helpers, caller migrations, regressions, and completion records.
- **Completed repair:** [Nested capturing-recursion TCO repair](#bug-preserve-valid-llvm-and-tco-for-nested-capturing-recursion)
  is complete and signed off with passing LLVM, runtime, sanitizer, benchmark-build, compiler,
  QA, and independent review gates.
- **Completed repair:** [Elaboration error recovery](#bug-continue-elaboration-after-independent-errors)
  is complete and signed off with passing verification and independent review.
- **Completed repair:** [counter preservation](#preserve-counters-and-argument-expressions-across-ownership-analysis)
  is complete and signed off. Counter threading, focused regressions, required compiler gates,
  QA, tracking checks, and independent review pass.
- **Completed repair:** argument-expression preservation implements the approved single-term
  contract. Shape/recovery regressions, full compiler tests, formatting, lint, native argument
  order, smoke checks, local installation, benchmark builds, and sanitizer checks pass, including
  the selection-qualifier recovery repair and observational call-boundary validation.
  QA, tracking checks, and independent review pass. The slice is signed off with commit and
  push authorization. Overall migration remains open.
- **Ignored-test restoration:** the [51-case audit](unify-lambdas-ignored-tests.md) identifies
  four unchanged passing tests, all enabled and signed off. The borrowed-PAP escape regression
  is enabled with a typed `BorrowClosureEscapeViaReturn` assertion through `semState`;
  formatting, lint, all tests, QA, tracking checks, and independent review pass.
  The subtask is complete and signed off; local commit is authorized and push is not authorized.
  [The explicit regression plan](#restore-ignored-regressions) accounts for the remaining
  40 cases. Six materialization cases have emitted-IR replacements; formatting, lint,
  770 library tests, and nine CLI tests pass, with 40 ignores and no warnings. QA and
  tracking checks pass; independent review reports no actionable findings. The six-case
  restoration is complete and signed off; local commit is authorized and push is not authorized. The three remaining selected
  cases have [bounded repair plans](unify-lambdas-ignored-tests.md#materialization-repair-plans);
  compiler implementation approval is pending.
- **Recovery evidence:** CLI controls preserve the statement type mismatch and bound-call
  use-after-move diagnostic without false ownership errors. Regression, smoke, benchmark-build,
  and sanitizer results are recorded in the recovery section.
- **Evidence:** [Nested TCO repair verification](#bug-preserve-valid-llvm-and-tco-for-nested-capturing-recursion)
  and [binding construction verification](#binding-construction-evidence).
- **Open limits:** the mixed-ownership reproducer remains unresolved; the general branch
  audit and separate Linux sanitizer validation are deferred. The broader counter/expression
  follow-up is complete and signed off. Remaining lambda implementation and 40 ignored cases
  require further bounded work.
  [Consuming an owned PAP captured by a move lambda](#bug-consume-an-owned-pap-captured-by-a-move-lambda)
  has passing semantic, smoke, benchmark-build, native sanitizer, and independent review
  gates and is complete and signed off.

## Checkpoint Evidence

Verification results and review limits below apply to their named checkpoints. Current status,
approvals, and next action belong to the [Execution Checklist](#execution-checklist),
[Signoff](#signoff), and [Task Working Memory](#task-working-memory).

### Binding construction evidence

- **Binding construction:** shared binding and cleanup construction in ownership analysis,
  PAP elaboration, direct-lambda lowering, and closure invocation, with typed-wrapper and
  consuming-contract regressions.
  Those callers use `LocalBindings.bind` and `LocalBindings.sequence`; generated struct
  destructors share the sequencing path through `LocalBindings.cleanup`. Scope signatures
  derive from parameter and continuation types. Sequencing preserves continuation source
  and annotations. Remaining direct lambda constructors create callable definitions or
  preserve existing lambda metadata rather than constructing local binding wrappers.
  Stage 2 verification (2026-09-12): formatting, lint, **636 passed / 51 existing ignores**,
  all seven compiler smokes, and local publishing pass in `/tmp/mml-stage2-final-gates.log`.
  Commands use the same compiler gate sequence recorded below for stage 1.
  `sbtn 'scalafmtCheckAll;testOnly mml.mmlclib.semantic.LocalBindingsTest'` also passes
  with **19 passed / 0 ignored** in `/tmp/mml-stage2-final-focused.log`.
  `make -C benchmark clean` and `make -C benchmark mml` pass all seven builds;
  logs: `/tmp/mml-stage2-benchmark-clean.log` and `/tmp/mml-stage2-benchmarks.log`.
  These are build checks, not timing comparisons. `./tests/mem/run.sh all` passes
  **41/41 ASan+LSan cases at -O 0 (49s)** in `/tmp/mml-stage2-memory.log`, on Darwin arm64,
  Clang 23.1.1, target `arm64-apple-darwin25.6.0`. Published and build jars share SHA-256
  `78e49fc07925555411fdc5ba5771ab907b9c99129e5c7b749f0da386e8980961`.
  Stage 2 QA enforcement, focused tracking review, and fresh independent code review pass.
  No actionable findings remain. The reviewer independently checked the gate logs and jar
  hashes and ran the nested consuming-PAP sample at `-O 0 -s` with expected output and no
  ASan diagnostic; the full gates were inspected, not rerun. No candidate findings required
  claim-verifier delegation. Runtime evidence is limited to Darwin arm64.
  Shared allocation preserves existing IDs and registers new
  declaration, field, and local IDs. The shared declaration formatter supplies matching definition,
  reference, and owner paths. Rewriting phases publish one fresh output index; PAP refreshes its
  index between iterations. Lexical resolution selects the nearest shadowing parameter, and
  duplicate-name checking rejects duplicate parameters in nested and inline lambdas.
  Generated scope provenance retains its purpose and ordinal; regression tests establish stable
  re-entry after relocation. Ownership temporaries retain unique names because ownership maps
  still use names. The clone-helper identity and ownership-wrapper function-type regressions
  pass; the latter is enabled by stage 2.
  Stage 1 verification (2026-09-12): formatting, lint, and **627 passed / 52 ignored** in
  `/tmp/mml-binding-fixes-gates.log`; that checkpoint includes the ownership-wrapper regression
  among its ignores. All seven required compiler smokes and local publishing pass in the same log.
  Commands: `sbtn 'scalafmtAll;scalafixAll;test;run run mml/samples/hola.mml;run run mml/samples/quicksort.mml;run run mml/samples/astar2.mml;run run mml/samples/partial-fac1.mml;run mml/samples/style-guide.mml;run mml/samples/lambda-factorial.mml;run mml/samples/raytracer3_p6.mml;mmlcPublishLocal'`.
  Published and build jars share SHA-256
  `9fcddf4fa69c66d4ae13bd7316b36d97af4106ad20c03f2eee21e1bcef2008b3`.
  `make -C benchmark clean` and `make -C benchmark mml` pass all seven builds;
  logs: `/tmp/mml-binding-fixes-benchmark-clean.log` and
  `/tmp/mml-binding-fixes-benchmarks.log`. These are build checks, not timing comparisons.
  `./tests/mem/run.sh all` passes **41/41 ASan+LSan tests (48s)** in
  `/tmp/mml-binding-fixes-memory.log` on Darwin arm64, Clang 23.1.1,
  target `arm64-apple-darwin25.6.0`. QA enforcement and focused tracking review pass;
  fresh independent review finds no actionable issues in the correction diff and directly affected
  compiler paths. The reviewer reran the duplicate-parameter semantic reproducer, confirmed both
  source parameters in the diagnostic, and checked jar hashes and verification logs. No candidate
  findings required claim-verifier delegation. Other gates were inspected, not rerun by the reviewer.
  Stage 1 signoff and local commit authorization are granted. Push is not authorized.
  The [mixed-ownership transfer repair](#bug-preserve-mixed-ownership-through-consuming-transfers)
  retains its separate failure evidence and remains open.

### Completed-slice evidence

<details>
<summary>Completed slice records and earlier verification checkpoints</summary>

These records preserve implementation, verification, and review evidence. References to
unfinished work, current changes, next actions, branch names, and authorization describe the
named checkpoint only; they do not carry forward as live task state.

- **Branch names (2026-09-12):** Git Town renamed `dev-lambdas-migration` to
  `dev-lambda-unify` and `dev-lambdas-unify` to `abandoned-dev-lambda-unify`, locally
  and on `origin`. Both retain `dev-2026-03-21-lambdas` as their parent. Historical
  checkpoints and the migration inventory retain the names used when recorded.

- **Planned ownership-analysis follow-up (2026-09-12):** Recorded
  [counter and argument-expression preservation](#preserve-counters-and-argument-expressions-across-ownership-analysis)
  at the Author's request. Tracking consistency, links, and whitespace checks pass; the
  Author signed off the tracking errand and authorized its commit. Implementation and
  focused regressions are pending.

- **Completed review follow-up (2026-09-12):** The Author authorized finishing and committing
  the remaining work. Nested consuming-PAP calls and their review repairs are complete;
  the parent task remains in progress. `analyzeLambdaApplication` names the direct lambda
  application path, preserves generated-local counters through scoped arguments, and carries
  QA markers for the planned AST-field cleanup. TypeUtils retains paragraph spacing and a
  `FIXME:QA` reference to the resolved-type-identity finding.
  Formatting, lint, **611 passed / 51 existing ignored**, seven smokes, and publishing pass
  in `/tmp/mml-finish-20260912/gates.log`. The final QA-comment normalization passes formatting
  and lint again in `final-style.log`; it changes no executable behavior. All seven clean
  benchmark builds pass (`bench-clean.log`, `benchmarks.log`), and **41/41 ASan+LSan** fixtures
  pass in 77 seconds (`memory.log`). Installed/build jar SHA256:
  `ce49a34bdc22017b804d8a3334c5ca5c12237e0d7fcc9018594324abb915ec9b`.
  Fresh independent review of the counter repair, regression, rename, and comments finds no
  actionable issues (`final-code.diff`, `final-code-review.md` in that directory). No candidate
  required a claim verifier. QA compliance, tracking links, and whitespace checks pass.
  Next: the selected binding identity and local-construction step. Conditional-ownership
  repair/hardening and AST-field cleanup remain planned. The branch-rename errand is pending.

- **PAP review follow-up (2026-09-12):** The Author approved repairing let-binding counter
  propagation and correcting the QA marker. `analyzeLambdaApplication` returns the counter reached
  after body analysis while preserving its existing outer-scope ownership propagation.
  The focused `PapOwnershipTest` case covers two scoped arguments with allocating predicates
  and bodies: before the fix eight generated bindings shared four names; after the fix the
  regression passes with distinct names and identities. Logs:
  `/tmp/mml-pap-counter-review/before.log` and `/tmp/mml-pap-counter-review/after.log`.
  `TODO:QA` is corrected to `FIXME:QA`; passing a whole `Lambda` remains the noted follow-up.
  Formatting, lint, the full suite (**611 passed, 51 existing ignored**), all seven required
  smoke checks, and local publishing pass (`/tmp/mml-pap-counter-review/gates.log`). Clean
  benchmark builds pass for all seven programs (`bench-clean.log`, `benchmarks.log` in that
  directory); no performance comparison was run. The memory harness passes **41/41 ASan+LSan**
  in 45 seconds (`memory.log`). Installed/build jar SHA256:
  `ba0e82bf5bb3dafdc7edf9a33ab748e44192c443fc36939549fea1c7da39d561`.
  Local QA, tracking consistency, and staged/unstaged whitespace checks pass.
  Fresh narrow review could not start: `agent thread limit reached`. The exact repair diff,
  pre-repair snapshots, and review packet are in `/tmp/mml-pap-counter-review/`. The Author
  approved parent-agent review; that narrow review completed with no actionable findings.
  It traced predicate/argument/body counter flow into the next call argument, checked that
  only the counter is added to the existing scope merge, and checked the regression's
  before/after evidence. Primary-review and per-claim independence were skipped under the
  approved fallback. No additional code changes or test reruns were needed.
  This records the counter-repair checkpoint; completion and final review evidence are above.

- **Construction planning (2026-09-11):** The Author requested the
  [intermediate construction item](#establish-binding-identity-and-local-construction-invariants)
  within this workstream and a linked [scope-analysis follow-up](lambda-scope-classification.md).
  Both are planned. The Author signed off this tracking errand with `finish errand`.
  Tracking review, link/anchor checks, and the scoped diff check pass. The finish request
  authorizes a local commit of this bookkeeping only; compiler implementation and repair
  signoff remain pending. Administrative task creation does not receive a product changelog entry.

- **Prior checkpoint (2026-09-11, resumed review repair):** The approved review
  repair passes compiler gates and fresh independent narrow re-review. Compiler, test, sample,
  and documentation changes remain uncommitted on `dev-lambdas-migration`; the parent task
  remains in progress. No commands or reviewers remain running. Next: Author review/signoff
  of the bounded repair and its implementation tour. No commit or push is authorized by
  this resume.

  Isolated the static-alias case in `/tmp/mml-static-alias.mml`. Before the fix, ASan
  exited 134 in `__free_String`; emitted IR cloned the direct literal branch but passed
  static storage from `let value = "def"; value` directly to the consuming callee.
  `argNeedsClone` now recognizes `Literal` bindings at consuming boundaries. Both branches
  now call the registered String clone helper. Three semantic regressions check clone
  identities/counts for named calls, inline calls, and a scoped conditional.
  Evidence: `/tmp/mml-static-alias-before.log`, `/tmp/mml-resume-native-after.log`, and
  `/tmp/mml-resume-focused.log` (49 focused tests passed).

  The expanded native fixture initially failed type inference in its positive inline
  closure controls: their function parameter `f` shadowed an earlier scalar local `f`.
  Controls use distinct `action`/`offset` parameters and both execute successfully.
  The negative closure test now asserts the ownership-sink diagnostic explicitly.
  The separate type-inference collision remains outside this repair: minimal probe
  `/tmp/mml-inline-name-collision.mml` and output `/tmp/mml-inline-name-collision.log`
  reproduce it with the fresh compiler. No TypeChecker changes were made.

  Current verification:
  - `sbtn 'scalafmtAll; scalafixAll; test; run run mml/samples/hola.mml;
    run run mml/samples/quicksort.mml; run run mml/samples/astar2.mml;
    run run mml/samples/partial-fac1.mml; run mml/samples/style-guide.mml;
    run mml/samples/lambda-factorial.mml; run mml/samples/raytracer3_p6.mml;
    mmlcPublishLocal'`: **610 passed, 51 existing ignored**, all seven smokes,
    formatting/lint, and publishing pass (`/tmp/mml-resume-gates.log`).
  - `make -C benchmark clean`, then `make -C benchmark mml`: all seven benchmark
    builds pass (`/tmp/mml-resume-benchmarks.log`); no performance measurements claimed.
  - `./tests/mem/run.sh all`: **41/41 ASan+LSan fixtures pass**, 47 seconds
    (`/tmp/mml-resume-memory.log`), including the expanded argument-ownership fixture.
  - Exact shadowing reproducer and expanded fixture pass via `sbtn run run -s -O 0`
    (`/tmp/mml-resume-controls.log`). Both affected tutorials run under ASan and print
    their expected values; named/nested consuming forms both print `3`
    (`/tmp/mml-resume-samples.log`).
  - Installed/build jar SHA256:
    `740ac010a437b9dd1fb60d335c83a839547d2919179d67b272ef5f456b8735ef`.
  - Local QA passes. Fresh independent reviewer `/root/repair_independent_review` completed
    the narrow re-review with no actionable findings in sink validation, scoped result
    consumption, binding-identity cleanup, and literal cloning. It read the full analyzer
    diff and affected tests/docs, independently ran the expanded fixture under ASan, and
    checked emitted IR for shadowed cleanup, scoped transfers, literal clones, and owned/static
    closure destruction. Packet: `/tmp/mml-independent-repair-review.md`; isolated fixture
    output: `/tmp/mml-repair-independent-fixture`. Compiler SHA256 matches the verified build.
  - The reviewer's only candidate received a fresh independent verifier and was excluded as
    pre-existing general branch-ownership behavior. Evidence is preserved under
    `/tmp/scoped-witness-verifier/`. The Author subsequently requested the
    [mixed-ownership transfer action item](#bug-preserve-mixed-ownership-through-consuming-transfers)
    above, which preserves the reproducer and observed failures. No implementation was added.
    The reviewer's optional `leaks --atExit` run could not acquire the process task port;
    its fresh runtime evidence is ASan only, separate from the parent-run 41/41 ASan+LSan
    harness above. It did not repeat broad gates or Linux validation.
  - The earlier local fallback review is superseded by this independent re-review. The Author
    asked why reviewers were not independent, then directed continuation; fresh reviewer
    creation succeeded. General branch audit and Linux sanitizer validation remain deferred.

  Implementation tour: `analyzeCall` threads argument ownership once in source order;
  `prepareConditionalArgument` saves branch decisions and cleanup witnesses;
  `analyzeArgument` propagates consuming contracts through conditionals/scoped expressions;
  `ownershipSinkErrors` rejects borrowed closure transfers; `sameBinding`/`bindingMoved`
  retain cleanup identity under shadowing; `argNeedsClone` supplies owned storage for
  literal bindings at consuming boundaries.

- **Previous stopping point (2026-09-11, recorded at the Author's request; resolved above):** Review repairs remain
  uncommitted and in progress. The latest work addresses three locally reproduced review
  cases: an inline consuming parameter accepted a borrowed closure; a parameter shadowing
  its caller's binding leaked its owned value; and a borrowed conditional result escaped
  consuming checks through a local alias. Implemented shared ownership-sink validation,
  binding-identity cleanup, and propagation of consuming-result checks through scoped
  expressions. The new semantic regressions pass: **607 passed, 51 existing ignored**
  (`/tmp/mml-review-local-fixes.log`). Native verification of these fixes is not complete.
  Primary-review evidence: `/tmp/mml-inline-sink-claim.md`,
  `/tmp/mml-shadow-cleanup-claim.md`, `/tmp/mml-scoped-branch-claim.md`; local reproductions:
  `/tmp/mml-local-claim.log`, `/tmp/mml-shadow-check.log`, `/tmp/mml-branch-check.log`.

  Current failure: `sbtn 'run run -s -O 0 tests/mem/pap-argument-ownership.mml'`
  compiles, then exits 134. ASan reports a BUS caused by a write inside allocator
  deallocation, with `loop -> take -> __free_String -> free`
  (`/tmp/mml-review-local-native.log`). Suspected trigger:
  `take (if flag then "abc"; else let value = "def"; value;)`.
  `argNeedsClone` clones a direct string literal but explicitly returns false for a
  reference whose ownership state is `Literal`. Theory: the scoped alias returns static
  string storage without a heap clone, and the consuming function frees it. The trace
  confirms the destructor failure; this exact branch has not yet been isolated as its cause.

  **Next:** isolate that static-alias case, verify the emitted ownership/clone path, fix
  the confirmed cause, and run the expanded native fixture plus the exact shadowing
  reproducer. The latter did not run because the batched command stopped at the first
  failure. Positive owned/static closure controls added to the fixture also await native
  execution. Then run the compiler gates, republish, run the full memory harness and
  benchmark builds, and finish the narrow local re-review and implementation tour.
  Installed `mmlc` is still the earlier 605-test build; use `sbtn` for current source.
  No new agents are needed: the Author approved local verification and narrow re-review.
  This checkpoint records unfinished work, not signoff or commit authorization.

- **Review repair (approved 2026-09-11):** The Author requested the relevant fixes and an
  implementation tour. A mixed conditional argument reproduces an ASan double-free
  (`/tmp/mml-review-mixed-before.log`). Two new tests also reproduce missing consuming-branch
  moves and acceptance of borrowed branches (`/tmp/mml-review-conditional-consuming-before.log`).
  Calls use named argument records, shared reference validation, parameter-based moves,
  consistent temporary/destructor types, and typed callee analysis after operands. Conditional
  arguments save branch decisions once and guard nested predicates. Consuming branches validate
  and transfer individually. Parentheses use the common allocation classifier, including closures.
  Source inline calls already lower to unary scoped bindings; new tests retain their move/reuse
  behavior. A further failing test found that those bindings accepted borrowed conditional
  branches at consuming parameters (`/tmp/mml-review-inline-conditional-before.log`). Scoped
  bindings now share consuming-branch checks and saved condition decisions with ordinary calls;
  let-binding predicates also run once. Staged PAP capture transfer already rejects source reuse,
  so the full-application guard is retained. Earlier checkpoint gates pass **605 tests (51 existing ignored)**,
  all seven smokes, formatting/lint, publishing, and all seven benchmark builds. The full native
  harness passes **41/41 ASan+LSan fixtures** in 45 seconds. Both tutorial forms print `3` under
  ASan. Logs: `/tmp/mml-review-complete-gates.log`, `/tmp/mml-review-complete-memory.log`,
  `/tmp/mml-review-complete-benchmarks.log`, `/tmp/mml-review-complete-sample.log`, and
  `/tmp/mml-review-complete-tutorial.log`. Installed/build jar SHA256:
  `56d8e3c75c3d43510bd0176eed58766b3bc3afc2305124137241c6fe50678935`.
  Fresh reviewer `/root/argument_ownership_review` completed the primary review. Its mandatory
  claim verifier could not start: both the reviewer and root dispatch received
  `agent thread limit reached`. The Author approved local verification and narrow re-review;
  subsequent findings, fixes, and the unresolved native crash are recorded above. The prior
  completion record below is a historical checkpoint, superseded by this review repair.

  Review-item disposition: consuming moves use resolved parameter records rather than a
  Ref-only callee lookup; mixed cleanup uses witnesses; allocation classification unwraps
  parentheses and includes owned closures; callee analysis follows argument evaluation;
  temporaries and destructor operands share one type; lifetime checks reuse `analyzeRef`;
  returned origins are computed once per argument; named records replace positional tuples;
  typed callee results remove the unreachable fallback/non-local return. The full-application
  guard stays because PAP creation already transfers captured callees. The three memory.md
  blank lines are pre-existing Author edits and remain untouched.

- **Completed slice — nested consuming-PAP calls (approved 2026-09-11):** Fix ownership
  propagation through nested allocating expressions while preserving call-once and
  use-after-move checks. Add semantic and sanitizer regressions, verify the unchanged
  `pap-nested-consuming.mml` reproducer, update affected docs, and run compiler gates and
  independent review. Starting compiler commit: `2b55e82`. At that baseline the sample failed at
  `nested 0` with the ownership-required diagnostic; the separate-binding control passed under ASan.
  The fix analyzes operands once with immutable scope threading, then constructs ordered
  bindings and cleanup without re-analysis. It also rejects an earlier borrowed argument whose
  owner moves in a later argument. Nine semantic regressions cover acceptance, reuse, field
  aliases, and borrow lifetimes. The native fixture exercises 1,000 iterations of scalar/heap
  results, fields, conditionals, and an asserted evaluation-order trace.
  Formatting, lint, **594 tests (51 existing ignored)**, all seven required smokes, the unchanged
  reproducer under ASan, and the new native fixture pass. Compiler publishing succeeds.
  Logs: `/tmp/mml-nested-pap-full-gates.log`, `/tmp/mml-nested-pap-smokes.log`, and
  `/tmp/mml-nested-pap-reproducer.log`. The baseline regression run had four expected failures
  (`/tmp/mml-nested-pap-before.log`). Early logs record a command invocation error and a fixture
  syntax error; both are corrected. Full memory verification passes **40/40 ASan+LSan** in
  44 seconds (`/tmp/mml-nested-pap-memory-all.log`); all seven benchmark programs build after
  clean (`/tmp/mml-nested-pap-benchmarks.log`). The final published sample prints both expected
  results under ASan (`/tmp/mml-nested-pap-final-sample.log`). Native evidence is macOS arm64;
  benchmark builds do not establish performance. Primary reviewer `/root/nested_pap_review` was
  interrupted by "This content was flagged for possible cybersecurity risk." It returned no
  findings or final verdict. Fresh reviewer `/root/nested_pap_review_retry` completed the scope
  and independently confirmed one cleanup gap: inline move closures passed to borrowing
  parameters alongside allocating arguments received no temporary destructor. Native evidence
  showed a leaked closure environment and captured String. No other actionable findings remained.
  The fix includes owned lambda values in argument allocation classification. Native regressions
  cover borrowed/consumed move closures and borrow-capturing controls; a semantic regression
  preserves the captured source's moved state. Final formatting, lint, **595 tests (51 existing
  ignored)**, the expanded native fixture, all seven smokes, and publishing pass in
  `/tmp/mml-nested-pap-final-gates.log`. Final memory verification passes **40/40 ASan+LSan**
  in 43 seconds (`/tmp/mml-nested-pap-final-memory.log`); all seven benchmark builds pass after
  clean (`/tmp/mml-nested-pap-final-benchmarks.log`). The exact review reproducer prints `5`
  and exits 0 at `-O 0` under ASan+LSan (`/tmp/mml-nested-pap-closure-fixed.log`). The final
  published sample also prints both expected `3` results under ASan. Build and installed jar
  SHA256: `110d7a997ee7b762afcfa7f3855a336dcf88e44ec6f7fbdbdb46cb2993698d38`.
  Fresh narrow review `/root/nested_pap_cleanup_review` found no actionable findings. It
  independently compiled and ran the expanded fixture at `-O 0` under ASan+LSan, checked the
  three closure cleanup boundaries in IR, and reran the exact reported reproducer. QA compliance,
  focused tracking review, and whitespace checks pass. This completes the approved bounded slice;
  broader lambda implementation, the general branch audit, and Linux validation remain outside
  this completion. All command sessions are terminal. Compiler and sample changes remain
  uncommitted; the Author requested a separate commit of the documentation.
  Existing workflow edits, the Author's separate context notes, and unrelated memory whitespace
  are outside this compiler slice. The parent lambda task remains in progress.

- **Previous slice — owned PAP struct fields:** The Author approved proceeding through
  implementation and verification, then waiting for review. Start: clean `0f4f586`.
  Plan: integrate function-bearing struct classification/destruction, preserve callable contracts
  through fields, enforce ownership at construction/invocation, restore regressions, add sanitizer
  coverage, update docs, and run compiler gates plus QA and independent review. The Author later
  requested direct fixes to the review findings and authorized commit and push. Parent-task
  completion is not included.
- Implementation: consuming constructors and typed field cleanup cover nested function-bearing
  structs, without clone helpers. Stable field identities and callable flow preserve ownership
  through aliases, returns, moves, and conditionals. Consumed fields retain holder cleanup;
  siblings remain usable. Field-call result ownership follows callable origins, including static
  results. Callable alternatives must agree on return ownership and consuming parameters.
  Captured field aliases borrow their owner. Borrowed aggregate origins cannot reach ownership
  sinks through conditionals or local aliases. Generated destructors retain internal field
  destruction authority. Two name-collision paths in last-use checks and indirect codegen are
  fixed. Three preserved tests are enabled; `PapStructOwnershipTest` has 43 passing cases.
- **Initial verification — 2026-09-11:** **576 tests pass, 51 existing ignored**, with formatting,
  lint, all seven smokes, and the expanded ASan field fixture passing. Formatting/focused log:
  `/tmp/mml-fields-conditional-focused2.log`; full gates/publish:
  `/tmp/mml-fields-conditional-gates.log`. Assembly-reported jar hash:
  `aaf8e4d4f0b09c2cd78cc578b797d3241f5835ac`; published file SHA1:
  `e00b465750d6498a5da2bfb70bc857516d0791e5`. The full memory harness passes **38/38 ASan+LSan**
  in 41 seconds (`/tmp/mml-fields-conditional-memory.log`). All seven benchmark programs build
  after clean (`/tmp/mml-fields-conditional-benchmark-clean.log`,
  `/tmp/mml-fields-conditional-benchmarks.log`). Builds do not establish performance.
  Native execution is macOS arm64; Linux validation remains deferred. All command sessions are
  terminal. QA compliance and whitespace checks pass.
- Review findings fixed: an independently verified remaining-parameter contract mismatch leaked
  four bytes when an indirect target borrowed an argument marked moved. The compiler now rejects
  mixed contracts; homogeneous borrow/consume controls pass ASan+LSan. A surviving verifier from
  the third review independently confirmed conditional nested-field borrowing caused duplicate
  ownership/destruction. That reproducer now receives the intended diagnostic; four negative
  tests and two positive controls cover the fix, with native conditional-owned construction in
  the memory fixture. Parent validation also corrected static field-result cleanup and borrowing
  through captured field aliases before final verification.
- Independent review coverage is incomplete: `/root/field_review`, `/root/field_review_recovery`,
  and `/root/field_review_final` each stopped with "This content was flagged for possible
  cybersecurity risk." Preserve the two independently confirmed findings above; partial coverage
  is not a clean review. Focused conditional-fix review `/root/conditional_fix_review` completed
  with no actionable findings. It independently rejected the original reproducer, ran the native
  field fixture with ASan, and checked whitespace; its conclusion is limited to that fix.
  A separate contract-fix reviewer could not start: "agent thread limit reached." These are
  historical review limits, not current permission gates. The Author subsequently requested
  direct fixes and explicitly stopped further delegation. The broader independent review is
  not claimed complete. Earlier failing logs are debugging evidence, not final verification.
- **Review fixes and final verification — 2026-09-11:** Guard recursive heap classification;
  preserve arguments supplied to staged constructors; reject unavailable clone operations;
  canonicalize grouped field cleanup and cloning; support grouped LLVM size/alignment; check
  qualifier borrow dependencies after owner moves. Return-ownership keys use lambda identity,
  argument dependency checks are reused, and destructor sequencing is shared. Documented field
  borrows remain non-transferable. Nine regression tests and a grouped-field memory program cover
  the fixes. No new ignores were added.
  Final formatting, lint, and full suite pass: **585 tests, 51 existing ignored**, no failures or
  warnings (`/tmp/mml-review-probes/final-verification.log`). All seven required smokes pass
  (`/tmp/mml-review-probes/verification.log`); the grouped-field ASan compile/run also passes
  (`/tmp/mml-review-probes/grouped-verification.log`). The final compiler is published locally.
  Benchmark builds pass after clean (`/tmp/mml-review-probes/benchmark.log`); no performance
  comparison is claimed. The final memory harness passes **39/39 ASan+LSan** in 39 seconds
  (`/tmp/mml-review-probes/memory-final.log`). QA and whitespace checks pass. All command sessions
  are terminal. The Author explicitly requested commit and push; the parent task stays in progress.

- **Documentation follow-up — 2026-09-10:** The Author requested the PAP ownership tutorial
  become part of the language reference later. Recorded above; tutorial integration is deferred.

- **Signed-off current slice — deferred consuming arguments:** The Author approved this slice
  on 2026-09-10. Preserve consuming contracts through PAP creation, aliases, returns, and
  higher-order calls; verify invocation-time moves, borrowed-input rejection, reuse with fresh
  arguments, mixed/staged application, and exactly-once cleanup. Update affected documentation
  and regression/native sanitizer coverage, then run compiler gates and independent review.
  Implementation starts from clean checkpoint `ea803e2`. The Author's finish request supplies
  slice signoff and local commit authorization on 2026-09-10.
  Struct-field support, nested-call rejection, and the general branch audit remain separate.
- Implementation: remaining and eta-expanded parameters preserve consuming flags; callable
  origins retain applied argument counts and propagate supplied callable arguments before
  saturation. Existing ownership checks enforce moves and borrows, without a separate PAP
  argument-ownership path. Two former rejection tests now assert valid behavior;
  `DeferredPapOwnershipTest` adds 26 acceptance/move/rejection regressions. The language reference,
  memory model, and compiler design describe the supported contract.
- Independent review confirmed one gap: a partial call such as `let builder = use measure`
  did not propagate its supplied callable before an inner `let p = f n` was elaborated. A fresh
  verifier reproduced the rejection and confirmed direct-call controls. The fix propagates
  supplied arguments through residual callable origins at every stage. Three regressions and
  the native fixture cover the combined staged/higher-order case. A fresh narrow independent
  re-review found no actionable findings and independently compiled/executed the updated native
  fixture with ASan at `-O 0`, exit 0 without diagnostics. The initial review found no other
  actionable issues. Review and Author signoff are complete for this slice.
- Final verification after that fix:
  - `sbtn 'scalafmtAll;testOnly mml.mmlclib.semantic.DeferredPapOwnershipTest;run run -s -O 0
    tests/mem/pap-deferred-consuming.mml'`: 26 focused tests and the native ASan fixture pass
    (`/tmp/mml-deferred-pap-review-fix.log`). The fixture checks 1,000 iterations of repeated
    calls, dropped PAPs, returned/aliased/higher-order PAPs, staged mixed transfers, returned
    String ownership, and consuming closure arguments via its result/exit status.
  - `sbtn 'scalafixAll;test'` followed in the same invocation by all seven required smoke commands
    and `mmlcPublishLocal`: **530 passed, 54 existing ignored**, all smokes and publishing pass,
    no warnings/errors (`/tmp/mml-deferred-pap-final-gates.log`). `partial-fac1` prints `120`;
    `astar2` finds its path. Installed jar: `9abd2611aec15b6fe0a9cc528baf299b1ceeee2f`.
  - `./tests/mem/run.sh all`: **37/37 ASan+LSan fixtures pass** in 63 seconds
    (`/tmp/mml-deferred-pap-final-memory.log`). Native evidence is macOS arm64;
    Linux sanitizer validation remains separately deferred.
  - `make -C benchmark clean`, then `make -C benchmark mml`: all seven programs build
    (`/tmp/mml-deferred-pap-final-benchmarks.log`). These are build checks, not performance
    measurements.
  - QA compliance, local links, whitespace checks, and focused tracking review pass.
    All verification processes have exited. The Author has signed off and authorized a local
    commit of all current changes, including README, sample-comment, and formatting edits.
    No push is authorized.
- Initial staged tests exposed missing residual signatures before elaboration; retaining
  applied argument counts resolved that gap. The initial sandboxed test attempt could not
  access the sbt lock; escalated runs succeeded. Earlier passing logs are superseded by the
  final verification above. The parent task remains open.

- **Signed-off prior slice — PAP creation semantics:** The Author approved the selected
  task and requested implementation through verification, then a pause for review.
  Supplied arguments must evaluate once at creation, in source order; borrowed payloads
  must remain within their owners' lifetimes; consuming payloads transfer explicitly and
  are destroyed exactly once, whether dropped or forwarded at full application. No
  implicit cloning. Preserve the remaining-consuming-parameter rejection and relevant
  migrated regressions. The finish request on 2026-09-10 supplies slice signoff and local
  commit authorization; it does not complete the parent lambda task.
- Implementation plan: pin creation-time effects and ownership regressions; elaborate
  PAPs through the existing lambda/local-binding representation; integrate capture
  ownership and cleanup; run the compiler gates and independent review before handoff.
  Implementation uses the stated affine-call assumption: invoking a PAP with transferred
  payloads consumes it. The Author reviewed the ownership explanation and sample and
  accepted the slice with the finish request.
- Current implementation: typed PAP elaboration, source-order argument bindings, borrowed
  dependency checks, explicit payload transfers, and disarmed cleanup after invocation.
  Callable-origin analysis carries ownership information through aliases, returns, and
  higher-order arguments. Regression coverage includes creation effects and consuming PAPs.
- Review corrections: call arguments' borrowed dependencies are checked again after later
  arguments evaluate, and inline PAPs retain their source lambda's enclosing borrow captures.
  Both findings were independently confirmed and fixed. A fresh narrow re-review found no
  actionable findings. The initial primary review's final report was interrupted by an
  automated content flag; its two independent verifiers completed. The fresh re-review
  covered those corrections and directly affected code, not the whole migration again.
- Final verification checkpoint: `sbtn 'scalafmtAll;test'` passed 504 tests, with 54 ignored;
  `PapOwnershipTest` has 24 passing tests. `scalafixAll`, all seven required compiler smoke
  checks, and `mmlcPublishLocal` passed without compiler warnings; `partial-fac1` prints `120`.
  Installed artifact: `897059e00cf1b1a0f3c4f93be3430b122ebb62f2`.
  `./tests/mem/run.sh all` passed all 36 ASan+LSan fixtures (39 seconds), and
  `make -C benchmark clean` followed by `make -C benchmark mml` built all seven programs.
  These are build checks, not performance measurements. The inline borrowing regression
  also ran under ASan, printing `123` twice and returning its expected result, `3`.
  Local QA compliance, focused tracking review, and `git diff --check` passed.
  Native execution evidence is macOS arm64; Linux sanitizer validation remains deferred.
- Lifetime coverage includes argument evaluation, aliases, chained/inline PAPs, owned results,
  and borrowed struct fields. Scalar-only PAP cleanup preserves the million-iteration
  `escaping-paps` loop. PAP storage in struct fields is explicitly rejected until the broader
  function-field ownership implementation is available; this slice does not complete that work.
- **Selected follow-ups:** the Author classifies remaining-consuming-argument rejection and
  PAP struct-field storage rejection as bugs, accepts holding them, and prefers near-term
  steps within this workstream. Both are recorded under [Near-term bug-fix steps](#near-term-bug-fix-steps)
  with reproduction, scope, acceptance criteria, and provisional sizing. This prior checkpoint
  predates their implementation; current deferred-consuming progress is recorded above.
- The commented `mml/samples/pap-ownership.mml` and memory model distinguish owning a value
  from transferring it out. Sample execution exposed a separate nested-call rejection:
  `println (int_to_str (consuming 0))` fails ownership analysis, while binding the `Int`
  result separately works. This remains a documented follow-up after slice signoff; the sample and
  [memory model](../../docs/memory-model.md#nested-consuming-pap-calls) record the workaround.

- Test, sample, documentation, and helper preservation: `6e33ee4`, complete.
- Borrowed returns through local aliases: `323b8ca`, implemented and signed off.
- Typed closure destruction: `2efeb8c`, implemented and signed off.
- **Finished slice — 2026-09-10:** The Author signed off PAP creation/ownership and authorized
  the local checkpoint commit, stating that review is sufficient and the general branch
  audit will follow later. The interrupted overall compiler review remains incomplete;
  completing that review is deferred by this explicit direction, not claimed as passed.
  The nested-call rejection and the two selected bug-fix steps remain open. The parent
  task stays `in_progress`; next work requires selection of a bounded follow-up.
- Finish verification rechecked the successful logs: `/tmp/mml-pap-review-fixes.log`,
  `/tmp/mml-pap-final-smokes.log`, `/tmp/mml-pap-final-memory.log`, and
  `/tmp/mml-pap-final-benchmark.log`. No compiler/test sources changed after that checkpoint.
  The sample and memory documentation passed their focused reviews; final cleanup removes
  trailing whitespace from the sample. Builds were not repeated for bookkeeping edits.

Read the [handoff](#migration-handoff) and
[salvage sequence](../history/unify-lambdas-salvage.md#suggested-restart-sequence) to resume.
Keep that handoff current at every migration checkpoint as its standing requirement directs.

</details>

### Migration handoff

Use the [Execution Checklist](#execution-checklist) and
[Task Working Memory](#task-working-memory) to resume. The migration evidence records the
transfer baseline and completed workstreams, with their original verification limits.

#### Standing handoff requirement: every migration phase

The Author requires this document to stay current throughout **all phases of the migration**,
including documentation, tests, compiler changes, verification, and follow-up work. This applies
to every workstream.

Update the Execution Checklist and Task Working Memory at each meaningful checkpoint,
before pausing or ending a workstream, and before handoff. Keep the checklist top-level,
with only the active subitem and at most its next action. Record detailed evidence below:

- Work completed and the files or commits containing it; distinguish committed, pushed, and
  uncommitted changes.
- Work in progress, unresolved findings, blockers, and the exact next action.
- Checks actually run and their results, including failures, ignores, and checks still pending.
  Keep prior baseline results distinct from verification of the current changes.
- Decisions and approvals already given, their scope, and decisions still needed from the Author.
- Any live command or process and how to resume checking it; do not infer completion from silence.

A session reset must not require reconstructing progress from the conversation. Start a resumed
session by reading the Execution Checklist and Task Working Memory, then verifying the
recorded state against the repository. Preserve existing approvals, avoid repeating completed
work, and update the note as the phase advances.
This requirement also applies to future phases that have not yet been planned.

#### Resume from this checkpoint

The active step is binding construction stage 2; its implementation and verification are
complete and signoff is pending. See [Task Working Memory](#task-working-memory) for the
handoff and [Signoff](#signoff) for current authorization.

### Migration evidence

<details>
<summary>Transfer baseline and completed migration workstreams</summary>

All counts, failures, pending-work statements, process states, and approvals below describe
historical checkpoints, not the current compiler or active work. The migration inventory is
also a historical snapshot; use the Execution Checklist for current completion status.

### Lambda restart: test preservation map

The noncompiler transfer is complete on `dev-lambdas-migration`. All source-tip test declarations,
helpers, memory programs, samples, documentation, and independent benchmark/tooling changes are
present. The compiler matched parent `c7e9078` at that checkpoint; current compiler work is
recorded in the workstream sections below.

The transfer baseline passed **430 tests**, with **62 ignored** and no failures or errors. Ignored tests
are unfinished compiler work, not passing coverage. Every ignore has its reason in a comment
immediately above the test. One preexisting nested TBAA declaration remains undiscovered.

##### Transfer checkpoint and recovery

The pushed documentation/helper checkpoint is `35c87bd9cb89da4f48b8fe2fa2cbae70129e3beb`
(`Start lambda migration with docs and test helpers`). The complete transfer is committed as
`6e33ee4` (`Preserve lambda regressions and remaining source files`). At the start of the compiler
workstream, it is one commit ahead of `origin/dev-lambdas-migration`. Verify this when resuming:

```sh
git status --short --branch
git log -3 --oneline
git rev-parse HEAD
git ls-remote --heads origin dev-lambda-unify abandoned-dev-lambda-unify
git cat-file -t c16231a8753d214617a88a089341033fefe803d7
```

The Author waived confirmation requirements while moving material from the source branch. The
Author also authorized ignored tests and required a reason comment beside each one. **Normal
confirmation rules apply again before compiler changes.** The Author subsequently approved the
borrowed-return workstream recorded below. That approval persists across session resets; do not
ask for it again within this scope. Tracked-item statuses remain the preserved source history.

Read `AGENTS.md`, this document, and [unify-lambdas-salvage.md](../history/unify-lambdas-salvage.md). Before
proposing compiler work, read `docs/memory-model.md`, [the PAP contract](../tasks/unify-lambdas.md#partial-application-ownership-contract), and
the relevant compiler design/rules. Keep the source branch and immutable provenance recoverable.
The transfer checkpoint did not publish a compiler. The borrowed-return workstream has since
published its compiler locally; see its verification record below.

To resume, the Author can use:

> Read AGENTS.md, context/tasks/unify-lambdas.md, and context/history/unify-lambdas-salvage.md. Verify the
> migration branch and current changes. The Author has signed off the borrowed-return workstream;
> read its results and remaining native failures below. Preserve existing approvals
> and the ownership contract. Discuss and approve the next bounded compiler slice before editing.

#### Signed-off workstream: typed closure destruction

##### Final review and local commit approval — 2026-09-10

The Author finished review, approved the compiler-design updates, and authorized a local commit.
The Author explicitly prohibited pushing. This checkpoint supersedes the pending-review and
pending-signoff statements in the historical verification notes below. No top-level Tracked Item
status change or GitHub synchronization is authorized; the broader lambda work remains open.

The final review identified alias comparisons in `DestructionValidator` as a correctness issue.
Fresh primary review completed, but the agent thread limit blocked an independent verifier.
The Author explicitly approved parent verification and a fix if confirmed. Both reported cases
reproduced: an `Int64 -> Int64` closure with an `Int` parameter and a native destructor returning
an alias of `Unit`. The validator follows resolved aliases and single-type groups while preserving
native declaration identity. Five regressions cover compatible aliases and incompatible native
types; the three positive regressions failed before the fix. Verification and the narrow re-review
used the approved parent fallback, without per-claim independence.

Final formatting, lint and the full suite pass: **470 passed, 58 ignored**, without warnings
(`/tmp/mml-alias-tests.log`). The alias closure runs under ASan and prints `3`; the aliased
destructor emits IR accepted by `llvm-as`. Required native checks and the typed closure ASan
sample pass (`/tmp/mml-alias-smokes-final.log`), except the known `partial-fac1` exit 139
(`/tmp/mml-alias-partial-fac1.log`). The compiler is installed in `~/bin`
(`/tmp/mml-alias-publish.log`), and all seven benchmark builds pass
(`/tmp/mml-alias-benchmarks.log`). The memory harness remains **29/34** with the same five PAP
failures (`/tmp/mml-alias-memory.log`). Linux sanitizer verification remains deferred.

The design reference documents alias-aware validation, `OwnedClosure`, and the full semantic
pipeline. Scoped QA and whitespace checks pass. All verification processes are finished.
##### Shared LLVM test assertions

The Author authorized sharing the destructor tests' LLVM inspection helpers on 2026-09-10.
`BaseEffFunSuite` exposes `functionBody`, `functionBodyMatching`, `phiCount`, and
`assertPhiPredecessors` through `test/llvm/LlvmAssertions.scala`. All five codegen suites share
function lookup, preserving literal-name lookup and regex signature/ABI constraints separately.
Missing or ambiguous matches fail explicitly.

Phi checking handles conditional and unconditional branches, loop backedges, and repeated edges.
The destructor regressions retain their explicit two-phi expectation; the existing tail-recursive
sum regression also checks predecessors. The helper supports explicit unquoted block labels and
`br`, `ret`, and `unreachable` terminators, rejecting unsupported control flow. It checks predecessor
edges, not all LLVM invariants. Destructor-specific AST construction remains local.

Formatting, lint, and **465 tests pass, 58 ignored**, without compiler warnings
(`/tmp/mml-llvm-helpers-tests.log`). Nine helper tests cover lookup boundaries and positive/negative
phi cases. LLVM 23.1.1 accepts the conditional, loop, and repeated-edge fixtures independently.
This follow-up changes test code only; native publishing, smokes, benchmarks and the memory harness
retain the preceding compiler verification. A fresh scoped review through the local skill found no
actionable issues and independently reran LLVM verification on all three positive fixtures. QA and
whitespace checks pass. Changes remain uncommitted; Author review/signoff is next.

##### Independent review fixes: missing cleanup and CPU cache identity

The Author authorized fixing both independently confirmed findings on 2026-09-10.
Final destruction validation requires a registered destructor and matching cleanup entry for
every native/struct heap field. Missing explicit and default destructor registrations accumulate
errors per capture. Regression tests also reject omitted cleanup with a registered target and
check valid cleanup by field and destructor identity. Scalar and borrowed function captures
retain their existing behavior.

Runtime bitcode and object cache filenames include a digest of the compilation flags and
optimization level, alongside the target triple. Distinct resolved/explicit/default CPU choices
use distinct entries; legacy triple-only cache artifacts are bypassed. Tests cover both artifact
kinds, repeat selection, x86 CPU flags, optimization, sanitizer and stack-check options.

Formatting, lint and **456 tests pass, 58 ignored**, without compiler warnings. The missing-target
and omitted-cleanup assertions fail before the fix. Logs: `/tmp/mml-review-fixes-red.log` and
`/tmp/mml-review-fixes-tests.log`. The six previously successful required smokes and the typed
closure ASan sample pass (`/tmp/mml-review-fixes-smokes.log`). `partial-fac1` retains runner
exit 139 (`/tmp/mml-review-fixes-partial-fac1.log`).

On ARM64 macOS with LLVM/Clang 23.1.1, the original reused-directory CPU reproduction passes:
host M5, explicit M1, repeat M1, and no-CPU selections compile and run successfully. Library-mode
checks also pass, with one runtime compilation per distinct selection and cache reuse on repeat.
Independent inspection confirms target attributes in cached bitcode/objects and verifies the
delivered library runtime matches the selected cache entry. Logs:
`/tmp/mml-review-fixes-cpu-transitions.log` and
`/tmp/mml-review-fixes-cpu-library-publish-final.log`. Local publishing succeeds in the latter log.
The first library command attempt used an invalid option; final checks use `--target-type lib`.

All seven benchmark builds pass after cleaning (`/tmp/mml-review-fixes-benchmarks.log`). The full
memory harness remains **29/34**, with the same five PAP failures and the typed closure regression
passing (`/tmp/mml-review-fixes-memory.log`). Linux sanitizer verification remains deferred.
Native x86 CPU-transition execution is not part of this follow-up.

Each fix received a fresh, narrowly scoped primary re-review through the local skill; neither
review produced actionable findings. QA enforcement and staged/unstaged whitespace checks pass.
All verification sessions are finished. Changes remain uncommitted, existing staging is preserved,
and Author review/signoff is next. Tracked-item status, commit and push approvals remain separate;
no GitHub changes.

##### Review follow-up: destruction operand exit blocks

The Author authorized regression tests and the fix for the review's lost `exitBlock` finding.
The new codegen tests reproduce wrong outer-phi predecessors for conditional operands in
`DestroyClosure` and `DestroyClosureEnvironment`; the dispatcher control case already passes.
Both straight-line emitters now preserve the operand's exit block. All seven destruction
codegen tests pass, covering AArch64 and x86-64. LLVM assembly verification also passes for all
six emitted regression modules. Logs: `/tmp/mml-destruction-exit-red.log` and
`/tmp/mml-destruction-exit-green-ir.log`; passing IR:
`/tmp/mml-destruction-exit-{closure,environment,dispatch}_{AArch64,X86_64}.ll`.
Reconstructing the failing function bodies from the red test log in those modules also makes
`llvm-as` reject both with `PHI node entries do not match predecessors!`
(`/tmp/mml-destruction-exit-{closure,environment}-reproduced-invalid.ll`).

Final verification: formatting, lint and **451 tests pass, 58 ignored**, with no compiler
warnings. The six previously successful required smokes and local compiler publishing pass
(`/tmp/mml-destruction-exit-full.log`). `partial-fac1` retains its known runner exit 139
(`/tmp/mml-destruction-exit-partial-fac1.log`). All seven benchmark builds pass after cleaning
(`/tmp/mml-destruction-exit-benchmarks.log`). The memory harness remains **29/34**, with the
same five PAP failures listed below and the typed closure destruction sample passing
(`/tmp/mml-destruction-exit-memory.log`). Linux verification remains deferred.

QA review of this follow-up found no further issues; staged and unstaged whitespace checks pass.
All verification commands have finished. Changes remain uncommitted, existing staging is preserved,
and the next step is Author review. Workstream signoff and tracked-item status are still pending.
No GitHub changes.

##### Implementation and previous verification

The Author approved implementation of [typed closure destruction](../history/typed-closure-destruction.md)
and its full verification in the fresh-context request. This approval supersedes the older
next-slice planning note below. No tracked-item completion, commit, or push is authorized.

Implementation checkpoint (uncommitted): intrinsic AST nodes, resolved field/target IDs,
helper registration and ownership-aware body completion, expression lowering, final-index
validation, traversal/editor/printing support, and removal of metadata/body/call overrides are
implemented. Ordinary struct destructors retain their bodies. Owning function captures carry
an explicit ownership witness in `Capture.OwnedClosure`; helper bodies are completed after
ownership analysis so borrowed functions cannot acquire destruction by type alone.

The first full run passed 434 tests with three old-helper assertion failures (58 ignored).
After helper updates and five new contract/ownership tests, 441 passed; one new alias test
incorrectly expected ownership transfer on function aliasing. Existing semantics retain the
original owner; its identity assertion is corrected without changing transfer decisions.
Logs: `/tmp/mml-destruction-tests.log`, `/tmp/mml-destruction-tests2.log`.

Verification checkpoint: 445 tests passed with 58 ignored before the final ABI regression test.
`hola`, `quicksort`, and the new nested-closure program compiled and ran successfully via sbtn
(`/tmp/mml-destruction-tests-smoke2.log`). QA caught native ABI lowering being applied to
MML struct destructors; the emitter now lowers native targets only and keeps generated struct
calls in the MML ABI. A new test exercises both AArch64 and x86-64. Validator checks include
actual helper parameter types; native implementation bodies are governed by their registered
signatures. Intermediate test-only expectation failures are recorded in
`/tmp/mml-destruction-final-tests-smokes{,2}.log` and corrected.

Final Scala verification passes: **446 passed, 58 ignored**, no failures or compiler warnings.
Formatting and lint pass, including the final operand-ID test helper review.
Logs: `/tmp/mml-destruction-final-tests-smokes3.log` and
`/tmp/mml-destruction-signoff-tests.log`. Required smokes passed except `astar2` (CSSC assembler
compatibility) and `partial-fac1` (exit 139). Local publishing succeeded in
`/tmp/mml-destruction-publish.log`. Benchmark builds passed except `nqueens` (same CSSC issue).

The full memory harness passed **29/34**, compared with the prior **27/33**. The added
`typed-closure-destruction` case and existing `direct-move-owned-struct-capture` pass.
The five remaining failures concern PAP ownership/codegen: `direct-pap-escaping-heap-alias-arg`,
`direct-pap-escaping-heap-arg`, `direct-pap-escaping-heap-capture`,
`direct-pap-inscope-heap-capture`, and `escaping-paps`. Logs and baseline IR comparisons:
`/tmp/mml-destruction-memory.log`, `/tmp/mml-destruction-diagnostics/results.json`, and
`/tmp/mml-destruction-diagnostics/baseline-comparisons.json`. Common function definitions in the
four emitted PAP programs match the borrowed-return baseline exactly. PartialFac1 and Astar2
also retain their common function definitions; added universal helpers/entry wrappers account
for the differences. PAP fixes remain outside this slice.

The Author additionally authorized fixing the CSSC toolchain compatibility drift and explicitly
rejected hardcoding the machine CPU. LLVM emits assembly for the resolved host CPU, but final
Clang assembly lacked that CPU selection. The portable change in `LlvmToolchain.scala` forwards
the resolved CPU consistently to Clang using `-march` for x86 and `-mcpu` for ARM. Explicit
cross targets without a CPU continue to omit host CPU flags. Runtime compilation uses the same
flags. No CPU model or operating system is hardcoded. The portable change passed formatting, lint, **446 tests (58 ignored)** and the six successful
required smokes, including `astar2`, in `/tmp/mml-cpu-portable-verification.log`. A fresh native
Astar2 build/run also passed, and local publishing succeeded in `/tmp/mml-cpu-publish-final.log`.
A full x86 macOS executable built with `--cpu x86-64` and ran successfully; verbose output confirms
`-march=x86-64` on runtime compilation and final assembly/linking
(`/tmp/mml-cpu-portable-publish.log`). Clang C-to-assembly-to-object checks also passed for x86-64
and AArch64 Linux in `/tmp/mml-cpu-linux-objects`; Linux runtime execution is untested.

The optional cross-target check without `--cpu` exposed an existing, independent LLVM rejection:
`attributes #0 = {}` in `codegen/emitter/Module.scala`. The same emission exists at HEAD; CPU flag
forwarding does not cause it. This check stopped the initial publish batch; publishing was rerun
successfully after a fresh native smoke. `partial-fac1` still exits 139
(`/tmp/mml-cpu-partial-fac1.log`). All benchmark builds now pass after `make -C benchmark clean` and `make -C benchmark mml`,
including `nqueens` (`/tmp/mml-cpu-benchmarks.log`). The repeated memory harness remains
**29/34 passing**, with exactly the same five PAP failures (`/tmp/mml-cpu-memory.log`).
All verification commands are terminal; no background work remains. `git diff --check` passes.
The next action is Author review and workstream signoff; tracked-item status and commit/push
approval remain separate.
QA reviewed the closure diff and portable CPU change against the coding rules. Native/MML ABI
and semantic identity findings are fixed. No new QA findings remain; the recorded PAP failures,
ignored coverage and Linux sanitizer assembly failures remain explicit limits; the Linux follow-up
below resolves the no-CPU cross-target rejection and verifies ordinary Linux execution.

The new memory case covers nested owned closures, strings, structs, consuming parameters,
alias use, recursion and resource-free function values. All changes remain uncommitted;
workstream signoff, tracked-item updates, commit and push remain separate approval steps.

#### Linux verification follow-up

The Author pointed out the existing Docker setup and directed use of its shell scripts.
Docker Desktop was stopped; it is now running. The Ubuntu image built successfully, and
`packaging/docker/linux-builder-shell.sh` started and entered the `mml` Compose service.
The container is ARM64 Linux with LLVM/Clang 20.1.8 and GraalVM 21.0.3.

The first runtime attempt exposed LLVM's `Host CPU: (unknown)` report being forwarded literally
as a CPU name. Every program failed compilation before runtime; preserve these results as
`/tmp/mml-linux-verification-initial.log`. This establishes a remaining portability gap in the
CPU fix, not a passing Linux check. The follow-up treats unknown CPU reports as absent and omits
the invalid empty attribute definition in default-CPU IR. No replacement CPU is hardcoded.
Two regression tests cover CPU discovery and default-CPU attributes. An initial test fixture
missed the function-body terminator; it was corrected. Final formatting, lint and full suite pass:
**448 passed, 58 ignored**, no warnings. The six successful native smokes, default-CPU x86 macOS
cross-compilation and local publishing pass in `/tmp/mml-linux-cpu-fallback-tests-final.log`.
The x86 executable also ran successfully. `partial-fac1` retains exit 139
(`/tmp/mml-linux-followup-partial-fac1.log`). Repeated macOS benchmark builds pass and the memory
harness remains **29/34** with the same five PAP failures
(`/tmp/mml-linux-followup-mac-{benchmarks,memory}.log`). QA review and `git diff --check` pass.

Final Docker results are saved in `/tmp/mml-linux-verification-final.log`. `hola`, `quicksort`,
`astar2`, and `nqueens` compile and run; `style-guide`, `lambda-factorial`, and `raytracer3_p6`
compile; all benchmark builds pass. Linux `partial-fac1` also exits 139. The memory harness
reports **0/34**, all compile failures, so it provides no Linux memory-safety result. A standalone
`arrays-mem` diagnostic shows LLVM 20's generated `asan_globals` assembly rejected by Clang 20
with `Linkage must be 'comdat'`. Its assembly remains at
`/tmp/mml-linux-asan-diagnostic/out/aarch64-unknown-linux-gnu/ArraysMem.s` inside the container.
Other individual compile failures have not been classified beyond the harness results.

ASan itself works in this container: a tiny C probe compiled with Clang's `-fsanitize=address`
and reported the deliberately introduced heap-use-after-free, with a source line and stack trace
(`/tmp/mml-linux-asan-probe.log`). The remaining blocker is MML's sanitizer assembly path;
it must not be described as lack of Linux ASan support or as detected MML runtime memory errors.
No sanitizer workaround or Docker/source-script changes were made.

All commands are terminal and the interactive shell is closed. Docker Desktop and the Compose
service remain running. Linux build outputs are isolated inside the container. The Author deferred Linux sanitizer work to the separate local item
[Revisit Linux sanitizer verification](../tasks/linux-sanitizer-verification.md).
The Author requires local-only tracking, with no GitHub synchronization for this item.
An issue-creation command already in flight created GitHub issue #271 before the interruption;
it has not been added to the project or modified further. The Author was informed. No further
GitHub changes are authorized. Workstream signoff, other tracked-item statuses, commit and push
remain separate approvals.

The Author additionally requested compiling/running `mml/samples/astar3.mml` and the MML
benchmarks in Linux. The existing Docker shell script reopened the service. `astar3` compiled and ran successfully,
printed its path map and `Path found!`, and exited 0. All seven MML benchmark executables ran
once and exited 0: sieve (`78498` primes), quicksort (median `-85`), matmul and matmul-opt
(trace `381460`), nqueens (`14200` solutions), euclidean-ext (`5010954496756`), and ackermann
(`8189`). Their Linux builds were already current from the successful full benchmark build;
`make mml` reconfirmed that state. These are successful execution checks, not a statistical
performance comparison. Log copied to the host: `/tmp/mml-linux-astar3-benchmarks.log`.
The shell is closed and no checks remain running. The local deferred Linux item is recorded;
the closure destruction and portable CPU work is ready for review/signoff with the documented
sanitizer and PAP limits.

#### Review follow-up: separate phase files

The Author requires each semantic phase to live in its own file. Body completion is extracted
from `ClosureMemoryFnGenerator.completeBodies` into
`semantic/ClosureDestructorBodyGenerator.scala`, exposing `rewriteModule`. The first generator
registers layouts/helpers after type checking; the new phase completes field cleanup after
ownership analysis. The pipeline and design reference name the two phases separately. This is
a structural extraction with the same phase order and cleanup behavior. The Author also requires
shared implementation to live outside the phase files. Shared intrinsic-body construction is
in `semantic/ClosureDestructorAst.scala`; both phase objects retain a single
`rewriteModule` entry point and no phase calls the other.

The Author accepted this organization and asked to finish. A common semantic-phase trait was
discussed and left for later; none is introduced. Formatting, lint and the full suite pass after
the final shared-helper extraction: **448 passed, 58 ignored**, no warnings. The six successful
required native smokes and local publishing pass in `/tmp/mml-destructor-phase-split-final.log`.
QA reviewed both separate phases, their shared helper and pipeline/docs references; no new issues
were found. Staged and unstaged whitespace checks pass; existing staging is left untouched.

Final benchmark builds pass (`/tmp/mml-phase-split-benchmarks.log`). The memory harness remains
**29/34** with the same five PAP failures (`/tmp/mml-phase-split-memory.log`); the new nested
closure regression passes. `partial-fac1` retains exit 139
(`/tmp/mml-phase-split-partial-fac1.log`). All verification processes are terminal, and the final
compiler is published locally. Linux sanitizer work remains deferred in the local tracking item.

The Author will read the final phase walkthrough and finish review tomorrow. Workstream signoff
is still pending; no tracked-item completion, commit or push has been performed. No further GitHub
changes were made. Resume from the two phase files and `SemanticStage.scala`, preserving the
current staging. The next action is Author review, not another implementation or test cycle.

#### Signed-off workstream: borrowed returns through aliases

Approved by the Author after the migration commit and discussion of the existing symbol indexes.
The scope is return-escape analysis through local aliases and conditional results, including
aliases of borrow-capturing closures. Preserve valid shadowing, static returns, and owned returns;
reject invalid escapes without adding implicit clones.

The checkpoint commit is titled `Reject borrowed returns through local aliases` and contains
`semantic/OwnershipAnalyzer.scala`, `semantic/OwnershipAnalyzerTests.scala`, and both handoff
documents. Four preserved regressions
are enabled. Three new checks cover parameter identity after shadowing, valid consuming returns
through aliases, and rejection of a borrowed branch alongside an allocating branch.

`sbtn 'scalafmtAll;scalafixAll;test'` passed **437 tests, 58 ignored**, with no failures or
errors. This includes all four enabled regressions and all three new checks. The log is
`/tmp/mml-borrow-return-final-tests.log`; its process is terminal. Earlier syntax and
unused-member build errors were fixed before this pass.

Verification checkpoint:

- Required smoke passes: `hola` and `quicksort` compile/run; `style-guide`, `lambda-factorial`,
  and `raytracer3_p6` compile. Logs: `/tmp/mml-borrow-return-smoke-hola.log`,
  `/tmp/mml-borrow-return-smokes.log`, and `/tmp/mml-borrow-return-smokes-compile.log`.
- `astar2` fails assembly because `umin`/`umax` require `cssc`; `partial-fac1` exits 139 at
  runtime (log `/tmp/mml-borrow-return-smokes-rest.log`). Their newly generated LLVM IR is
  byte-for-byte identical to the saved parent-compiler migration IR in
  `/tmp/mml-migration-ir/3/` and `/tmp/mml-migration-ir/42/`, respectively. The new copies are
  in `/tmp/mml-borrow-return-ir/`. These failures are not caused by changed IR in this slice.
- `sbtn mmlcPublishLocal` succeeded after smoke verification, installing this workstream's
  compiler into `~/bin`. Log: `/tmp/mml-borrow-return-publish.log`.
- Benchmark clean succeeded. `make -C benchmark mml` built sieve, quicksort, matmul, and
  matmul-opt, then failed assembling nqueens: `abs` requires `cssc`. The remaining targets
  were not built. Log: `/tmp/mml-borrow-return-benchmarks.log`.
- `./tests/mem/run.sh all` completed: **27/33 pass ASan/LSan, six fail**. Log:
  `/tmp/mml-borrow-return-memory.log`. All five failing programs that generate IR produce
  byte-for-byte identical IR to their saved parent-compiler migration baseline. The sixth,
  `escaping-paps`, retains its baseline `TypeGroup` codegen rejection. Diagnostic reruns
  and comparisons are in `/tmp/mml-borrow-return-memory-diagnostics/results.json` and the
  adjacent per-program logs. Failures are detailed below; the memory gate is not green.
- QA reviewed all changed hunks against coding and QA rules: immutable origin propagation,
  indexed identities, existing test helpers, unchanged migrated assertions, no new ignores,
  and no inserted clones. Formatting, lint, and `git diff --check` pass. No additional defect
  was found in this bounded change; native verification failures remain open.
- All verification commands are terminal. No command or session handle remains to resume.

| Memory program | Failure |
| --- | --- |
| `direct-move-owned-struct-capture` | LLVM rejects duplicate `__free_Pair` definition. |
| `direct-pap-escaping-heap-alias-arg` | LLVM sees a closure pair where `%struct.String` is expected. |
| `direct-pap-escaping-heap-arg` | LLVM sees a closure pair where `%struct.String` is expected. |
| `direct-pap-escaping-heap-capture` | ASan reports double-free in `__free_String`. |
| `direct-pap-inscope-heap-capture` | ASan reports a segmentation fault in the local `say` function. |
| `escaping-paps` | No LLVM type mapping for grouped function-return `TypeGroup`. |

Implementation and index findings:

- `Module.resolvables` supplies the existing stable-ID value/type index. `ResolvablesIndexer`
  includes nested lambda parameters and runs before and after ownership analysis.
- `returnedOrigins` follows the result of immediate lambda applications, groups, and conditional
  branches. Supplied argument origins are associated with parameter IDs. The existing index is
  used for reference lookup and updated with the current lambda parameters during traversal.
- Both borrowed-reference and borrowed-closure return checks use those origins. Reference checks
  use indexed parameter consumption and captured-value IDs, without name-based fallback.
- Return escape is checked on the typed body before cleanup and static-return promotion, so
  an inserted clone cannot conceal a borrowed return path.
- The prior separate closure walker, shallow borrowed-reference walker, name fallback, and
  special `__stmt` constant used by that walker are removed.
- General ownership-state maps and owned-value cleanup are unchanged by this slice. The work
  does not claim to resolve every name-based ownership operation, PAP, or struct-field gap.

Implementation and verification are complete for this bounded slice, with the failed native
gates explicitly recorded above. The Author signed off this workstream after reviewing those
results and approved the four-file checkpoint commit. Next action: discuss the next bounded
compiler slice. Do not mark a Tracked Item complete; this signoff covers only the borrowed-return
slice. The checkpoint follows `6e33ee4`; use `git log -2 --oneline` and `git status --short --branch`
to verify its commit and working-tree state when resuming. No push is included in this checkpoint;
the branch will be two commits ahead of the local origin tracking ref after the commit.
The transfer inventory JSON remains the immutable `6e33ee4` preservation snapshot; its test
results and destination hashes describe that checkpoint, not these new compiler changes.

These four preserved `OwnershipAnalyzerTests` cases are enabled without weakened assertions:

- `borrowed param returned through let-binding wrapper is rejected`
- `borrowed param returned through let-bound conditional is rejected`
- `borrowed param returned through two nested let-bindings is rejected`
- `borrow-capturing lambda returned through two nested let-bindings is rejected`

The shadowing and static-return companions remain enabled. The focused new cases cover resolved
identity, valid consuming returns without clone/free insertion, and a mixed borrowed/allocating
return. The known grouped-return codegen failure is not resolved by this approval.

Keep this checkpoint current so a new session can identify the changes, results, and remaining work.
Workstream review is complete; do not request the same signoff again.
Further compiler workstreams and tracked-item status changes require separate approval.

#### References and transferred scope

- Parent: `c7e90780897d202d857292b029baedc5b5f9b7e3`.
- Test/program source: `c16231a8753d214617a88a089341033fefe803d7`.
- Documentation source: `8e83506`, retained on `abandoned-dev-lambda-unify`.
- Complete inventory: [lambda-test-migration.json](unify-lambdas-migration.json).

The complete `context/` and `docs/` trees and root instruction changes were transferred at the
first checkpoint, including additions, edits, renames, and deletions. Migration handoff updates
and these inventory files are intentional additions on the destination branch.

The subsequent transfer includes 67 changed sample entries, 12 added memory programs, all
remaining Scala test changes, `benchmark/Makefile`, both Rust matrix benchmarks, and
`tooling/vscode-llvm-ir/package-lock.json`. All 83 sample/memory/benchmark/tooling files match the
source bytes exactly. None of the source branch's 28 changed compiler implementation files were
transferred.

Three shared test helpers moved from `test/extractors/` to `test/ast/`:

| Parent file | Destination file |
| --- | --- |
| `TXAstExtractors.scala` | `AstExtractors.scala` |
| `TXLambdaHelpers.scala` | `LambdaTestQueries.scala` |
| `TXTermTraversal.scala` | `AstTraversal.scala` |

`AstTraversal` accesses the lambda body through typed `Lambda` matches instead of matching all
eight constructor fields. Its traversal behavior is unchanged. The other helpers match the source
blobs exactly. Existing caller imports and the four traversal helper names were updated.

#### Test preservation and ignores

| Measure | Count |
| --- | ---: |
| Parent declarations retained | 411 |
| Source-tip declarations transferred | 477 |
| Current declarations, including retained parent variants | 493 |
| Passing tests | 430 |
| Ignored tests | 62 |
| Undiscovered nested declarations | 1 |
| Original file/name pairs across source history | 486 |
| Distinct declaration versions across source history | 507 |
| Corpus paths / distinct path-and-blob versions | 266 / 367 |

The 16 parent cases replaced or changed in source are retained alongside their successors. Eleven
same-name cases have a `[parent c7e9078]` suffix; five keep their original names. All 410 parent
cases that the runner discovered before migration still run and pass. Parent assertions remain
unchanged after helper-name normalization and test-header formatting.

The first transferred suite failed to compile with 14 errors in five suites. Eighteen tests
require absent materialization metadata, PAP/parser diagnostics, or the type-name comparison API.
Five test bodies are preserved inside comments next to explicit failing placeholders. Three
helper bodies likewise preserve their source assertions in comments and fail explicitly. Restore
those bodies against the agreed compiler API before enabling their dependent tests. Removing an
ignore alone cannot make these placeholders pass.

With those API-dependent tests ignored, the suite ran 430 passing and 44 failing tests. Those 44
source regressions are now ignored, with the observed gap explained directly above each test.
Their programs and assertions remain executable and unchanged. The inventory records each reason
and a short failure excerpt. The final suite has 430 passing and 62 ignored tests.

The pending cases include borrowed returns through aliases, PAP argument moves and escapes,
struct ownership and destruction, heap type aliases, Direct call shapes, capture cleanup, and
named closure entries. Some code-generation assertions pin the source implementation's exact IR
shape. Their preservation does not authorize reproducing that architecture. Parent and source
representation expectations must be reconciled deliberately while preserving their semantic and
ownership coverage.

For example, these aliases must not manufacture ownership:

```mml
fn echo(s: String): String =
  let x = s;
  let y = x;
  y;
;
```

The parent accepts this borrowed return. Its source rejection regression is retained and ignored
with that reason. The shadowing and static-return companion regressions run and pass.

`TbaaEmissionTest.scala` still declares `loads and stores include alias scope metadata` inside
`TBAA field offsets honor alignment (String has ptr at offset 8, not 4)`. This preexisting nested
registration is recorded as `not-discovered`, not passing or ignored.

#### Reading the inventory

Schema version 2 keeps every immutable source/history reference from the first checkpoint.
`scala_cases` records original file/name identities, source and parent declarations, their current
`destination`, and a separate `parent_destination` where applicable. `pending` records ignore
reasons and whether an unsupported body is commented. Execution is recorded independently from
preservation status.

| Case status | Meaning |
| --- | --- |
| `source-case-identical` | Current declaration text matches the source tip. |
| `source-case-format-adapted` | Only formatting or an adjacent migration comment differs. |
| `source-case-ignored` | Source program/assertions preserved; the test is explicitly pending. |
| `historical-name-present` | Superseded source name remains in the parent suite. |
| `historical-name-absent` | Intermediate name is retained through source history and successor mapping. |

`history` stores commit, blob, inclusive line range, declaration SHA-256, ignored state, and any
enclosing test. Declaration hashes cover text with trailing whitespace removed. File blob hashes
cover complete files. Recover a complete original with `git cat-file blob <blob>` or
`git show <commit>:<path>`.

`corpus_files` records all file versions and current hashes. `independent_files` records the four
benchmark/tooling paths outside the test/sample corpus. `verification` contains current JUnit
report hashes and outcomes; `previous_checkpoint_verification` retains the 410-test baseline.
Reports are local build outputs and may be absent in a new checkout. Rerun the suite there.
`destination_head` identifies HEAD when the working-tree snapshot was recorded.

Nine historical names have explicit successor mappings. Five remain active as parent tests; four
intermediate-only names remain recoverable in immutable source blobs. Three of the latter assert
rejected clone-per-PAP behavior. Their successors are preserved, but the rejected cloning contract
is not restored. The other intermediate replacement changes sibling-capture representation.

#### Verification and limits

```sh
sbtn 'scalafmtAll;scalafixAll;test'
```

Formatting and linting pass without warnings. Final result: 430 passed, 62 ignored, zero failures
or errors. Checks verify every source declaration's preserved body, every parent declaration's
retained destination, all corpus file hashes, and unchanged compiler implementation.

Both Rust matrix benchmarks build and run with checksum `381460`. The transferred lockfile's
root dependencies match `package.json`; its source updates esbuild to `0.25.12`, Node types to
`20.19.41`, and VS Code types to `1.120.0`. Dependencies were not installed or refreshed.

All 79 transferred MML files were checked for IR generation using this freshly built compiler.
The CLI classpath came from `sbtn 'export mmlc / Compile / fullClasspath'`; each file was then
checked with `java -cp <classpath> mml.mmlc.Main ir -b <temporary-directory> <file>`. Results and
diagnostics are recorded in each corpus entry's `ir_check` and summarized in `program_verification`.
There were 67 generated-IR results and 12 rejections, including intentional negative examples.

Of the 12 memory programs, 11 generate IR. `tests/mem/escaping-paps.mml` and sample
`partial-fac2-escape.mml` fail codegen because the parent has no LLVM mapping for `TypeGroup` in
the grouped function-return type. `lambda-forms/direct-inline-lambda.mml` fails parsing.
`borrow-escape-test.mml` is rejected for its untyped hole, so it does not validate borrow escape.
The negative `lambda-forms/struct-field-borrow-fail.mml` sample generates IR: the parent wrongly
accepts that borrowed closure in an owning field, matching the ignored semantic regression.

IR generation does not establish runtime correctness or memory safety. No MML program was run
under sanitizers during this transfer. The memory harness cannot be called passing while
`escaping-paps.mml` fails to compile. Full ASan/LSan checks and MML benchmarks remain required
when implementing the relevant compiler changes.

#### Later compiler workstreams

Current remaining work is listed in the [Execution Checklist](#execution-checklist) and
[remaining lambda implementation](#remaining-lambda-implementation). The evidence above
records the migration baseline and completed workstreams; it does not set the next action.

</details>
