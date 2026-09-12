# Unify lambdas

## Metadata

- **Owner:** The Author
- **Status:** in_progress
- **Created:** 2026-09-10
- **Target Branch:** dev-lambdas-migration
- **External Reference (optional):** https://github.com/fedesilva/minnieml/issues/255

## Problem

* Status: In progress


- GitHub: `https://github.com/fedesilva/minnieml/issues/255`

Fresh start on `dev-lambdas-migration`. See
[Task Working Memory](#task-working-memory) and the
[migration handoff](#migration-handoff) for current progress and the next discussion.

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
23. Heap captures inside the lambda body are treated as borrowed from the environment.
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

- [x] Discuss and approve a bounded slice of common ownership and partial-application
  elaboration, using the salvage sequence and preserved regressions.
- [x] Establish evaluation-once semantics, borrowed payload lifetimes, and explicit
  ownership transfers for the PAP creation slice.
- [x] Implement and verify PAP creation semantics; Author signoff recorded on 2026-09-10.
- [x] Support deferred consuming arguments; Author signoff recorded on 2026-09-10.
- [ ] Complete and verify the remaining fresh implementation in reviewed stages.

Approval: Preservation, borrowed returns, and typed closure destruction are recorded below.
The Author signed off the bounded PAP creation-semantics slice on 2026-09-10 and authorized
its local commit. The Author also signed off the deferred-consuming-argument slice on
2026-09-10 and authorized its local commit, including the current review edits. The general
branch audit is deferred at the Author's request. Broader lambda work remains pending.

## Implementation Checklist

The branch-specific remaining work and evidence are in the
[migration handoff](#migration-handoff). Source-branch checked items are history,
not a checklist to carry into this implementation.

### Intermediate construction step

- [ ] [Establish binding identity and local-construction invariants](#establish-binding-identity-and-local-construction-invariants).

#### Establish binding identity and local-construction invariants

- **Status:** planned; selected for this workstream by the Author on 2026-09-11.
  The tracking errand authorizes recording this item; implementation has not started.
- **Sequence:** after Author review/signoff of the current nested consuming-PAP repair,
  before further ownership changes. Review this intermediate step independently before
  resuming the remaining lambda fixes.
- **Problem and evidence:** `IdAssigner.nestedId` and `SyntheticLocals.nestedId` duplicate
  the nested binding ID recipe. `SyntheticLocals.local` already constructs a `FnParam` and
  matching `Ref`, but binding identity, type propagation, and index maintenance remain
  responsibilities that callers and phases must coordinate. This is construction debt;
  the evidence does not establish an identity-related runtime failure.
- **Source landmarks (2026-09-11):**
  [IdAssigner.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/IdAssigner.scala),
  `nestedId`, lines 28–37;
  [SyntheticLocals.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/SyntheticLocals.scala),
  `SyntheticOwner`, line 7, `nestedId`, line 39, and `local`, line 76.
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
  temporaries/cleanup, and generated memory helpers. Assess whether `SyntheticOwner` remains
  useful after consolidation. Renaming helpers alone does not satisfy the outcome.
- **Boundary:** no compiler-wide AST phase redesign, new semantic lambda category, or scope
  classification change. This refactor does not itself fix mixed-ownership transfer failures
  involving allocated and literal branches; those require a separate ownership change.
- **Acceptance:** verify shadowing, repeated generated names, reference resolution after
  rewriting/index refresh, preserved identities, and consuming-contract propagation through
  the affected paths. Run applicable compiler gates and independent review. Keep the known
  mixed-ownership failures explicit as separate baseline evidence.
- **Next action:** inspect the affected constructors and phase/index contracts, then present
  the concrete API and bounded migration plan for approval before implementation.
- **Follow-up:** [Remove provenance heuristics from lambda scope analysis](lambda-scope-classification.md).
  That task addresses AST interpretation and reconciles the existing sequence-lambda item;
  it is separate from this construction step.

### Near-term bug-fix steps

The Author considers these restrictions bugs and selected them as steps in this workstream.
Holding them for follow-up is acceptable. Keep them separate from the PAP creation slice;
this records scope and priority, not implementation or signoff. Tackle the smaller parameter
case first. The struct-field step shares the function-field ownership work described above.

- [x] [Allow a consuming argument to be supplied after PAP creation](#bug-supply-a-consuming-argument-after-pap-creation).
- [ ] [Allow an owned PAP to move into a struct field](#bug-store-an-owned-pap-in-a-struct-field).
- [x] [Allow nested consuming-PAP calls](#bug-nested-consuming-pap-calls).

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

- **Status:** in_progress; implementation and review fixes verified; commit and push authorized.
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

- **Status:** complete; approved implementation, verification, and independent review complete
  on 2026-09-11.
- **Original reproduction:** `println (int_to_str (consuming 0))` incorrectly required ownership
  even when `consuming` owned its payload and was called once. A separate result binding worked.
- **Approved scope:** analyze argument ownership once, preserve source evaluation order and
  cleanup, reject reuse and invalid borrowing, add semantic/native regressions, update docs,
  and run compiler gates plus independent review.
- **Acceptance:** both forms in `mml/samples/pap-nested-consuming.mml` print `3`; later calls
  and aliases remain rejected after consumption. Nested heap results, struct fields,
  conditional calls, argument order, and exactly-once cleanup pass sanitizer coverage.

### Later-stage documentation

- [ ] Adapt the [PAP ownership tutorial](../../mml/samples/pap-ownership.mml) into the
  [language reference](../../docs/language-reference.md) at a later stage of the lambda work.
  Preserve its concrete, step-by-step explanation of owning, borrowing, and consuming PAPs,
  following values through creation, calls, and cleanup.

## Verification

The nested consuming-PAP slice passes **595 tests, 51 existing ignored**, all seven required
smokes, and **40/40 macOS ASan+LSan fixtures**. Formatting, lint, publishing, and all seven
benchmark builds pass. Independent review confirmed an inline-closure cleanup gap; its fix
passes the exact reproducer and expanded regressions. Fresh narrow re-review found no actionable
findings.
Commands, logs, and review status are in [Task Working Memory](#task-working-memory).

The deferred-consuming-argument slice passes **530 tests, 54 ignored**, all seven required
smokes, and all **37 macOS ASan+LSan fixtures**. Formatting, lint, local publishing, and all
seven benchmark builds pass. Independent review confirmed a staged higher-order propagation
gap; its fix and added regressions pass verification, and a fresh narrow re-review found no
actionable findings. The Author signed off this slice on 2026-09-10.
Commands and logs are recorded in [Task Working Memory](#task-working-memory).

The PAP creation checkpoint passes 504 tests with 54 ignored, all seven required smokes,
and all 36 macOS ASan+LSan fixtures. Formatting, lint, local publishing, and all seven
benchmark builds pass. See [Task Working Memory](#task-working-memory) for commands and
evidence. Linux sanitizer validation and the general branch audit remain deferred.

## Risks / Notes

Migrated from the former tracker on 2026-09-10. Migration preserves pending scope;
it does not establish implementation progress, approval, or completion.

Preserve the [salvage record](../history/unify-lambdas-salvage.md), [migration JSON](unify-lambdas-migration.json), test evidence,
and source-branch history. Current design material is retained in this task; superseded plans and reviews
are in `context/history/` for Author review. No compiler slice is authorized by that port.

## Signoff

- Workstream signoff: Preservation complete; borrowed returns and typed closure destruction signed off.
  PAP creation semantics signed off on 2026-09-10, including its documented limitations.
  The Author considers the review sufficient for this checkpoint and defers the general
  branch audit; the interrupted overall compiler review is not represented as complete.
  Deferred consuming arguments signed off on 2026-09-10 by the Author's finish request.
- Tracked item completion: Pending; the broader lambda implementation and follow-ups remain open.
- Commit authorization: Granted for both checkpoints by the Author's finish requests.
  The current commit includes all working-tree changes, as explicitly requested.
  No push authorized.

## Task Working Memory

**Branch:** `dev-lambdas-migration`.

- **Construction planning (2026-09-11):** The Author requested the
  [intermediate construction item](#establish-binding-identity-and-local-construction-invariants)
  within this workstream and a linked [scope-analysis follow-up](lambda-scope-classification.md).
  Both are planned. The Author signed off this tracking errand with `finish errand`.
  Tracking review, link/anchor checks, and the scoped diff check pass. The finish request
  authorizes a local commit of this bookkeeping only; compiler implementation and repair
  signoff remain pending. Administrative task creation does not receive a product changelog entry.

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

### Migration handoff

The detailed checkpoint log and standing handoff requirement are merged here. Dated
verification records describe their own checkpoints; use this task’s current working memory
for the next action and active approvals.

### Lambda restart: test preservation map

The noncompiler transfer is complete on `dev-lambdas-migration`. All source-tip test declarations,
helpers, memory programs, samples, documentation, and independent benchmark/tooling changes are
present. The compiler matched parent `c7e9078` at that checkpoint; current compiler work is
recorded in the workstream sections below.

The transfer baseline passed **430 tests**, with **62 ignored** and no failures or errors. Ignored tests
are unfinished compiler work, not passing coverage. Every ignore has its reason in a comment
immediately above the test. One preexisting nested TBAA declaration remains undiscovered.

#### Standing handoff requirement: every migration phase

The Author requires this document to stay current throughout **all phases of the migration**,
including documentation, tests, compiler changes, verification, and follow-up work. This applies
to every workstream, not only the currently approved borrowed-return work.

Update the handoff at each meaningful checkpoint, before pausing or ending a workstream, and
before handing work to a fresh session. Record:

- Work completed and the files or commits containing it; distinguish committed, pushed, and
  uncommitted changes.
- Work in progress, unresolved findings, blockers, and the exact next action.
- Checks actually run and their results, including failures, ignores, and checks still pending.
  Keep prior baseline results distinct from verification of the current changes.
- Decisions and approvals already given, their scope, and decisions still needed from the Author.
- Any live command or process and how to resume checking it; do not infer completion from silence.

A session reset must not require reconstructing progress from the conversation. Start a resumed
session by reading this handoff and verifying the recorded state against the repository. Preserve
existing approvals, avoid repeating completed work, and update the note as the phase advances.
This requirement also applies to future phases that have not yet been planned.

#### Resume from this checkpoint

##### Current focus — 2026-09-11

The nested consuming-PAP implementation is complete and uncommitted, with final gates and
independent review recorded in [Task Working Memory](#task-working-memory). Owned PAP struct-field support
is committed at `2b55e82`. The parent task stays in progress; the next bounded slice requires
Author selection. The general branch audit and separate Linux sanitizer validation remain deferred.

##### Transfer checkpoint and recovery

The pushed documentation/helper checkpoint is `35c87bd9cb89da4f48b8fe2fa2cbae70129e3beb`
(`Start lambda migration with docs and test helpers`). The complete transfer is committed as
`6e33ee4` (`Preserve lambda regressions and remaining source files`). At the start of the compiler
workstream, it is one commit ahead of `origin/dev-lambdas-migration`. Verify this when resuming:

```sh
git status --short --branch
git log -3 --oneline
git rev-parse HEAD
git ls-remote --heads origin dev-lambdas-migration dev-lambdas-unify
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
- Documentation source: `8e83506`, retained on local `dev-lambdas-unify`.
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

After the approved borrowed-return workstream, discuss and approve the next bounded slice. The salvage review's
candidate is complete executable destructor ASTs; the preserved ownership regressions provide
additional concrete entry points. Establish the invariant and MML examples first, then identify
which ignored tests should become enabled for that slice. Do not silently clone values to repair
ownership, weaken assertions to obtain a green suite, or import the failed compiler wholesale.

Reconcile stale design-document claims and historical tracked-item statuses through their own
reviewed work. Neither this transfer nor the green suite with ignores completes lambda unification.
