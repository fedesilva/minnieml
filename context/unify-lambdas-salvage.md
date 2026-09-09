# Lambda unification salvage and restart handoff

## Start here

The Author intends to restart lambda unification from the parent branch. Preserve the design work and all tests. The
failed implementation is useful evidence, but it is not the architecture to reproduce.

The Author explicitly requires:

- Bring `docs/memory-model.md` back into the parent. It is a primary design reference for the restart.
- Carry all agent-instruction, context-instruction, and other documentation changes into the new migration branch.
  This includes additions, edits, renames, and deletions, not just documents directly related to lambdas.
- Preserve all tests, including existing tests modified on this branch, new tests, memory programs, and their helpers.
- Prepare enough context for a new session after switching back to the parent.
- Explain language behavior with MML examples. Do not substitute Scala, C-like pseudocode, or LLVM for MML explanations.
- Keep explanations self-contained; do not require scrolling through earlier conversation.

This document records the salvage plan. Preparing it did not switch branches, merge files into the parent, commit, push,
or update tracked items. Documentation/instruction transfer and test migration still need to happen.

Read `AGENTS.md` first in the new session, then this document, then the branch-tip memory model and PAP ownership spec.
Read the relevant rules before changing files. Discuss and agree on each compiler workstream before implementing it.
Keep workstreams small and stop for review. This handoff does not authorize an unattended compiler rewrite.

## Recovery references

- Source branch: `dev-lambdas-unify`.
- Source tip: `c16231a8753d214617a88a089341033fefe803d7` (`c16231a`).
- Local remote-tracking ref `origin/dev-lambdas-unify` points to the same tip at handoff time; no remote fetch was run.
- Parent branch: `dev-2026-03-21-lambdas`.
- Parent tip and current merge base: `c7e90780897d202d857292b029baedc5b5f9b7e3` (`c7e9078`).
- Scope audited: `c7e9078..c16231a`, 72 commits and 153 changed-path entries with rename detection.
- Source memory-model blob: `f5134562e409d5dcb298bba30339dc209fc1dfbc`.

Use these hashes rather than moving branch names when recovering content. Some commits were rewritten: the plan and
changelog mention older hashes, such as `456a0c4`, `5da9c68`, and `0bf58f78`. The corresponding current-history commits
include `d5fc823`, `77ef42c`, and `2428773`. Locate historical work by subject and content if an old hash is
unavailable.

These local files are untracked at handoff and are not recoverable from `c16231a`:

- `context/unify-lambdas-salvage.md` (this handoff).
- `context/specs/lambda-unify-rescue.md` (architectural review of `c16231a`, reflowed to 120 columns).
- `context/qa-readability.md` (readability findings; advisory, not new project rules).

Keep these files when switching branches. They are absent from the parent tree and normally remain in the working
directory during a switch. They also need an explicit backup or eventual commit; the source branch does not save them.
Retain the source branch while salvage is underway. There is no reason to delete its history to restart elsewhere.

Useful read-only recovery commands, run from the repository root:

```sh
git show c16231a:docs/memory-model.md
git show c16231a:context/specs/pap-ownership-model.md
git show c16231a:context/tracking.md
git diff --name-status --find-renames c7e9078 c16231a
git log --reverse --format='%h %s' c7e9078..c16231a
git diff c7e9078 c16231a -- docs/memory-model.md
git log --reverse -p c7e9078..c16231a -- modules/mmlc-lib/src/test tests/mem
```

Do not merge the entire source branch to recover documentation or tests. Most implementation commits mix compiler
changes, tests, and tracking updates. Transfer the complete documentation/instruction changes and preserve all tests;
select compiler hunks separately. Before replacing a destination file, check for work made there since this handoff.

## First priority: preserve the ownership contract

Recover the complete `docs/memory-model.md` from `c16231a`, not just its formatting changes. The substantial rewrite is
`5bd03f4`; `bc41865` updates PAP behavior and removes a temporary implementation note; `0dcf661` adds the struct-sink
rule. The full tip version includes all three. Recover `context/specs/pap-ownership-model.md` alongside it.

The memory model must be read before ownership-related compiler work, including lambda lowering, generated functions,
partial application, aggregate construction, and tail-recursion optimization. Do not infer the language contract from
whatever the current emitter happens to support. The Author emphasized this because inconsistent ownership tracking
caused repeated leaks, dangling captures, and illegal transfers on this branch.

Carry forward these decisions:

- Ordinary parameters borrow. Consuming `~` parameters transfer ownership; moving does not deep-copy the value.
- An owned resource has one cleanup responsibility. Moves transfer that responsibility; borrowers do not acquire it.
- Returning owned values, constructing owning aggregates, and move capture are ownership-transfer boundaries.
- A struct is an ownership sink. Borrowed values cannot become owned merely by passing through a struct field.
- Apply the same rules through aliases, parameters, returned values, nested fields, captures, and generated PAPs.
- Eliding a closure allocation does not eliminate ownership of its captured resources.
- Cloning has a cost and must not silently repair an ownership mistake. Clone-per-PAP was explicitly rejected.
- Existing automatic clones at literal/global/return boundaries are documented implementation gaps. They do not
  authorize additional implicit cloning elsewhere. Source-level clone protocols are not implemented.
- Globals retain static lifetime and remain usable after consuming uses. The intended treatment of global-backed
  aggregate fields is documented separately from the incomplete implementation and its current clones.
- A PAP stores borrowed or owned payloads according to the already-applied parameters and captured operands.
- Borrowed heap PAP payloads must not outlive their owner. A non-escaping source function does not prove its PAP local.
- Already-applied consuming arguments move at PAP creation. Dropping an unused PAP must destroy its owned payloads;
  forwarding those payloads to the consuming callee transfers cleanup away from the PAP.
- Partial application with remaining consuming parameters stays rejected under the recorded contract.
- If lifetime or ownership cannot be proved safe, reject the program rather than inventing ownership or cloning.

Preserving the document does not mean claiming the parent implements every rule. During the documentation transfer,
separate accepted semantics, parent behavior, and unfinished design. Keep the original text available when correcting
examples or implementation descriptions; do not erase a decision merely because the parent does not implement it.

Specific inconsistencies to discuss while reconciling the docs:

- The memory-model introduction says every ownership transfer requires `~`, while returns and owning aggregate
  construction also transfer ownership without a call-site sigil. Explain the operation that authorizes the move.
- Its affine wording, "each owned value is used at most once", should distinguish consuming a value from borrowing it
  repeatedly. Do not turn this sentence into a blanket ban on repeated reads or calls.
- The `makeGreeter(name: String)` example move-captures a borrowed parameter. That conflicts with the stated rule;
  preserve the intended example but correct its ownership annotation with the Author's agreement.
- Statements that all borrow environments use the stack and all move environments use the heap describe a lowering
  strategy, not the semantic reason borrowing or ownership is valid. Direct lowering already breaks that equivalence.
- `unify-lambdas.md` retains older clone rules; `compiler-design.md` describes constructor cloning of borrowed inputs.
  Reconcile these with the later memory-model/PAP decisions instead of treating all prose as equally current.
- A PAP that forwards owned arguments raises a subsequent-call question. Record whether a second invocation is rejected
  and how callable consumption is represented. Changing a destructor pointer after the first call is not that proof.
- Preserve the intended global rules, but do not claim global-aware aggregate destruction or explicit clone protocols
  are available on the parent. Those are documented gaps.

The existing `docs/brainstorming/mem/mem-evolution.md` and `docs/brainstorming/mem/shared-refs.md` are already in the
parent. Read them when discussing clone/drop protocols or opt-in shared references. They are future design, not
authorization to add reference counting or new syntax as part of restarting unification.

## Ownership history that must not be repeated

These entries are supported by the branch's `context/tracking.md` changelog and git history. They explain why passing
one sample or fixing one emitter path was insufficient. Historical "complete" labels do not establish the invariant.

1. `af7e2c8`: classifying Direct move lambdas as not owning an environment while codegen still allocated one produced a
   leak. `tests/mem/direct-move-closure.mml` pinned the disagreement between analysis and emission. A simultaneous
   consuming-function test protected cleanup of real move closures. Ownership classification and lowering must agree.
2. `d2db85c`, later `6be566b`: borrowed returns escaped through nested local-binding wrappers. Aliases and shadowing
   defeated shallow walks. Preserve the nested-binding and shadowing regressions; use resolved binding identity.
3. `8bf747a`, `d493639`: Direct lowering initially lost operands when one local callable captured another callable. A
   nested function could pass its own argument in place of an outer capture. Preserve nested-callable tests.
4. `27c70fd`: literal heap captures needed correct operand threading, but the clone introduced at the binder had no
   cleanup site. The changelog explicitly records that leak despite a passing memory harness at the time.
5. `9af839e`: generated Direct PAP environments could escape even though the original callable was Direct. Stack-local
   PAP environments could dangle; `tests/mem/escaping-paps.mml` and escaped-PAP tests captured the failure.
6. `2428773`: cleanup was added for literal, owned-string, and owned-struct captures, including loop iterations. That
   fixed missing frees but exposed a second problem: an escaping PAP still held a pointer to a freed capture.
7. `b3ff71f`: a checkpoint contains a clone-per-PAP draft alongside the accepted spec rejecting that approach. Do not
   cherry-pick this implementation as a fix. Preserve the reproductions and the recorded rejection.
8. `bc41865`: the replacement tracks borrowed versus moved PAP payloads, rejects borrowed escape, and removes hidden PAP
   clones. Preserve the intended behavior and all regressions. The implementation still has split elaboration paths and
   backend-owned cleanup decisions identified in the rescue review.
9. `0dcf661`: equivalence work exposed borrowed closures being stored in structs and then passed to consuming
   parameters. The struct bypassed checks enforced for direct arguments and local aliases. This is tracked as #268.
10. `c16231a`: adds function-field ownership and nested-struct memory tests, but also regenerates processed destructors
    late in the pipeline. Review notes still flag aliases to function types and clone-required uses of function-bearing
    structs. Preserve the tests and fixes' intent; rebuild the implementation against a common ownership contract.

The parent already has substantial lambda infrastructure: `Bnd`/`Lambda`, captures, move syntax, closure values,
direct/closure ABI separation, and tail-recursion support. It is not a clean implementation of the intended model. In
particular, inspection of `FunctionEmitter.scala` at `c7e9078` confirmed both `emitEnvHeapFieldFrees` and
`emitUniversalClosureFreeBody`. The destructor-body problem predates this unification branch.

## All tests survive

This is an explicit Author requirement. Preserve the whole existing parent suite and every added or changed test on the
source branch, including tests outside the lambda suites. Also retain memory harness programs, manual regressions,
expected-failure samples, and the helper migrations needed to compile the tests.

The appendix enumerates every changed Scala test/helper entry, all 12 added memory programs, and all 67 sample entries.
Unchanged tests remain part of the baseline. File counts are inventory counts, not a test result or test-case count.

Migration rules:

- Recover the tip versions as the starting corpus, and inspect the history for tests renamed, replaced, un-ignored, or
  rewritten during the branch. A tip-only list of newly added tests is insufficient.
- Keep each original case and its source provenance while adapting its helpers or AST queries to the restart.
- Preserve the source program, intended result or diagnostic, ownership checks, and regression purpose.
- When an optimization changes, keep the semantic regression and adapt the representation assertion to the approved
  design. Do not weaken a failing check simply to obtain a green run.
- Exact symbol spelling, current environment layout, `isDirect`, or a dedicated materialization pass may disappear. That
  calls for an explicit test adaptation, not removal of the behavior being tested.
- Tests from the rejected clone-per-PAP draft remain historical evidence. Preserve their programs, and retain the later
  corrected acceptance/rejection and no-clone expectations from `bc41865`; do not reinstate the rejected contract.
- Record a mapping from original case to migrated case. Include unchanged, adapted, and temporarily unsupported cases so
  no test disappears through a rename or suite move. Do not bulk-ignore failures or call a partial port complete.
- Existing ignored tests also survive with their reasons. An ignored test or archived source is not passing coverage.

### Coverage to retain and extend

- Ownership of aliased native heap types and structs; resolution of their actual free/clone functions.
- Borrowed return rejection through one and multiple local bindings, conditionals, and shadowing.
- Named, inline, and let-bound non-capturing values passed to ordinary and consuming higher-order parameters.
- Move closures retaining their cleanup obligations through rebinding and consuming calls.
- Literal, owned-string, and owned-struct captures; ordinary bodies and loopified bodies; exactly-once destruction.
- Nested Direct callables forwarding captures, including a materialized callable capturing a Direct sibling.
- Mixed direct and first-class uses of the same named function and reusable closure-entry wrappers.
- Direct, recursive, partial, value-position, and nullary application cases in materialization analysis.
- PAPs that escape and stay local, with borrowed captures, borrowed arguments, aliases, and already-moved arguments.
- No implicit clones during PAP creation, dropped-before-call cleanup, and cleanup after forwarding owned arguments.
- Remaining-consuming-parameter rejection and use-after-move after PAP creation.
- Struct fields containing borrowed, moved, non-capturing, nested, and alias-typed function values.
- Function-bearing structs requiring a clone: reject unsupported uses instead of calling a missing clone function.
- Closure environment layout/TBAA, destructor-field offsets, zero-field structs, and PAP load/store metadata.
- Parser/grammar/typechecker/error-recovery cases and test-helper traversal behavior, including nested term errors.

The existing equivalence suites cover six semantic and four codegen scenarios. Preserve all of them, but extend
coverage: one capture test includes an inline form without checking its capture IDs, and several codegen tests assert
the selected representation rather than executing both programs and comparing effects.

Add explicit comparisons for effectful supplied PAP arguments, `Unit` arguments with effects, aliases, nested fields,
repeated calls after ownership transfer, multi-parameter immediate lambdas, and optimization/tail-recursion settings.
These are known gaps, not permission to postpone preserving the existing suite.

The existing memory harness discovers `tests/mem/*.mml`; it uses the installed `mmlc` and runs its ASan mode at `-O 0`.
Keep expected-compilation-failure examples in their appropriate tests/sample locations, not among harness programs that
must compile and run. Broaden optimization coverage deliberately when checking lowering correctness.

## Documentation, workflow, samples, and tooling to salvage

All documentation and instruction changes must move to the new migration branch. The Author explicitly expanded the
preservation requirement to include every `AGENTS.md`, other agent entry files, context instructions, and all other
documents. The entries below explain their use; they are not an optional shortlist.

Carry the complete changes under `context/` and `docs/`, including historical plans, changelogs, QA notes, independent
brainstorming, and documentation snippets. Include the root agent files and any nested instruction files changed after
the audited source tip. Carry the three untracked handoff/review documents too. The appendix records the current delta;
recheck the final source tree if additional changes are made before migration.

Preserve source additions, modifications, renames, and deletions. In this snapshot that includes the `CLAUDE.md` update
and deletion of `GEMINI.md`. This migration is authorized; do not turn each document into a separate salvage decision.
Resolve actual destination conflicts without losing either side's work. Reconcile stale implementation claims and
historical statuses as a separate reviewed change after preserving the source documents.

### Required design and review material

- `docs/memory-model.md`: transfer first, with the source version preserved and implementation claims reconciled.
- `context/specs/pap-ownership-model.md`: accepted borrowing/moving rules and the explicit rejection of hidden cloning.
- `context/specs/unify-lambdas.md`: retain the semantic goal and Author decisions; revisit implementation prescriptions
  and stale clone rules. A lambda introduces scope; materialization is a lowering decision. Avoid inventing separate
  `ScopeLambda` and `CallableLambda` semantic categories to encode current special cases.
- `context/specs/unify-lambdas-plan.md`: preserve as implementation history, bug evidence, and test provenance. Its
  completed slices are not the new plan. In particular, moving `destructorKind` elsewhere does not fix hidden bodies.
- `context/specs/bug-lambda-lifter.md`: preserve the duplicate-capture report and its binding-identity diagnosis. The
  report's suggested pass names are not authoritative; mmlc has no separate lambda-lifting pass. The ownership
  consequences of duplicate move captures are not established as harmless.
- `context/specs/lambda-unify-rescue.md` and `context/qa-readability.md`: preserve the untracked reviews independently.
- `docs/language-reference.md`: retain corrected examples/style and ownership-sink clarification; check claims against
  the memory model and the parent. Do not overwrite current parent edits without reviewing the delta.
- `docs/design/compiler-design.md`: retain useful ABI/cleanup explanation as historical implementation context.
  Reconcile scoped-binding special treatment, clone claims, and allocation-dependent ownership before presenting it as
  the restart's design.

`context/specs/lambdas-work-review.md` is already in the parent and records earlier closure failures. Its line numbers,
fix suggestions, and status claims need rechecking. For example, a suggestion to throw conflicts with the QA rules.

### Tracking and rules

- Preserve `context/tracking.md` as history, including ownership bug explanations, the clone-per-PAP rejection, open
  typed-hole and function-arity issues, alias-resolution work, the lambda-harness proposal, and cache/tooling ideas.
- Transfer the tracking document and its changelog as historical records. Its completed slice checkboxes describe the
  source branch, not the fresh implementation. Copying that record is part of the authorized migration; changing task
  statuses or declaring new work complete is a separate action. Workstream signoff and tracked-item updates stay
  separate.
- Existing issue references: #255 unification, #268 struct ownership, and #265 magic `__stmt` recognition. These are
  recorded local references, not a fresh audit of live GitHub state. Do not create duplicates from this handoff.
- Retain the changes to `AGENTS.md`, `context/dev-tools.md`, `context/coding-rules.md`,
  `context/qa-rules-and-coding-style.md`, and `context/task-tracking-rules.md` after comparing the parent versions. They
  include context loading, sequential `sbtn` use, smoke programs, comment rules, and tracking workflow.
- Carry `CLAUDE.md`'s change to `@AGENTS.md` and the deletion of `GEMINI.md` as part of the required instruction
  changes.
- Preserve `context/qa-misses.md`, but re-evaluate statuses against the parent. A QA issue fixed only on this branch
  becomes relevant again after the restart. Advisory readability findings do not override the existing coding rules.

### Independent material

- Keep `docs/mml-style-guide.md`, `mml/samples/style-guide.mml`, and the source-formatting corrections across samples.
  History includes `f69ffd0`, `7f64f17`, and `9e7a383`. Preserve intentional failing examples as failing examples.
- Keep all added samples, including `bubblesort.mml`, `borrow-escape-test.mml`, `closure-free-shapes.mml`,
  `partial-fac1.mml`, `partial-fac2-escape.mml`, and `mml/samples/lambda-forms/`.
- Preserve the changes to intrinsic arrays, parser backtracking, refinement types, and `snippets/lib.mml.txt` under
  `docs/brainstorming/`. They are independent design work; preserve their draft status.
- Keep `docs/brainstorming/compiler/Actionable-codegen-issues.md` as reported optimization observations, not proven
  fixes. Recheck arithmetic flags, aliasing assumptions, and TBAA claims before implementation.
- Keep `benchmark/Makefile`, `benchmark/matmul.rs`, and `benchmark/matmul-opt.rs`. Commits `dbf46e0`, `6421bda`, and
  `aa746ad` cover the optimization defaults/override and Rust comparisons. The Makefile also changes run counts.
- Preserve and review `tooling/vscode-llvm-ir/package-lock.json` from `4ad8da5`. A plugin dependency change should be
  tested in its own scope; do not reinstall or update dependencies just to write this handoff.

## Compiler code: review candidates and rebuild boundaries

No compiler patch is certified for direct cherry-picking by this audit. The file inventory is complete; the audit is not
a fresh line-by-line correctness proof of all compiler changes. Use the following commits to find bounded pieces.

Potentially reusable after separate review:

- `7430fed`: replaces explicit compiler throws with typed error handling and expands nested term-error checking.
  Includes parser, toolchain, conditionals, diagnostics, and a regression. Preserve the complete error behavior and
  required callers; this does not mean all exception boundaries or QA issues are resolved.
- `9eb60a3`: test helper renaming and traversal cleanup. Port together with all affected test imports. The manifest
  records the two renames and deleted traversal helper; preserve equivalent traversal coverage.
- `d2db85c` / `6be566b`: return-escape and shadowing fixes. The tests are mandatory; assess the walkers against the new
  identity-based contract rather than copying name-based logic unquestioned.
- `b3ff71f`: heap alias resolution helpers and their `TypeUtilsTests` may be separable from the rejected PAP draft.
  Inspect only the intended hunks and dependencies; the whole commit contains unwanted ownership implementation.
- `3b710e5`: nullary immediate-lambda typechecking. Preserve the case, but verify emission too: the plan explicitly
  records that accepting it in the frontend did not establish end-to-end codegen support.
- `6cd4158`: zero-field TBAA correction and regression. Re-evaluate against the parent layouts.
- `d9d60e2`: named-function wrapper reuse. Keep the performance requirement and tests; port only if compatible with the
  chosen callable representation.
- `bbd447c`: optimizer visibility of universal closure cleanup. Keep as a later optimization option, after destructor
  semantics are expressed explicitly.

There is unrelated AST work inside lambda commits: `TypeVariable` acquires a `constraints` field, with corresponding
LSP, semantic, and pretty-printer pattern changes. Its history spans `d9d60e2`, `13c600a`, and `1fc9f4e`. Decide its
disposition separately. A commit titled "Deduplicate named function closure thunks" is not limited to wrapper caching.

Rebuild or substantially redesign these areas before porting implementation:

- `MaterializationAnalyzer` / `isDirect` and allocation classifiers: preserve demand-analysis test scenarios; decide
  where lowering facts belong without making them the definition of ownership.
- `LoweredCaptureLayout` and codegen capture expansion: preserve semantic capture IDs and compute one reusable mapping
  for signatures, actual arguments, environment fields, loads, and destruction.
- `OwnershipAnalyzer`: preserve transfer/escape behavior across all value forms and sinks. Remove dependence on special
  immediate-application shapes and declaration parameter copies as the only source of ownership facts.
- `ExpressionRewriter` and Direct PAP emission: establish one supplied-argument evaluation and ownership contract.
  Different declaration locations must not change when or how often an argument is evaluated.
- `ClosureMemoryFnGenerator`, `MemoryFunctionGenerator`, and destructor emission: generate actual executable bodies,
  make declarations available before their users, and validate generated fragments under the same AST invariants.
- Tail-recursion emission: preserve effects and cleanup ordering without reconstructing ownership from binder names and
  wrapper shapes. Use ordinary recursion where transformation safety is not established.

The remaining compiler-path entries in the appendix are consumers or support for these changes, including pipeline
wiring, function type/ownership helpers, diagnostics, and printers. Inspect dependencies before extracting any hunk.

## Architectural findings to carry into the restart

The detailed evidence is in `context/specs/lambda-unify-rescue.md`, reviewed at `c16231a`.

1. Generated environment destructors contain only raw free calls, while codegen adds field destruction. The universal
   destructor body is replaced by null checking and dynamic destructor dispatch. Both mechanisms exist in the parent.
   The AST must express the operations actually executed, with explicit intrinsics where necessary.
2. Struct closure destructors are regenerated after earlier semantic passes. Their closure-free calls have a raw-pointer
   type but receive function values; codegen repairs the mismatch by extracting environment pointers.
3. Tail-recursion lowering discards binder identity and reconstructs statements by names and AST shapes. It moves
   cleanup around recursive calls and maintains its own borrow-closure validation.
4. Tail-call lowering can omit evaluation of `Unit` arguments. Direct PAP saturation and payload/parameter alignment
   also disagree about source versus non-void arguments. Keep one mapping and preserve evaluation of every argument.
5. Immediate lambda application receives local-binding ownership semantics. Calling an ordinary borrowing function and
   immediately applying an apparently equivalent lambda can therefore differ in whether the argument is moved.
6. Named declaration typechecking can type the binding without typing the contained lambda. Immediate-lambda checking
   also assumes a single parameter. Establish a common post-typechecking invariant, including generated lambdas.
7. Named PAP eta-expansion leaves supplied expressions inside the eventual body; Direct PAP emission evaluates them at
   creation. Effectful arguments expose different evaluation times and counts. This must have one semantic answer.
8. Function ownership is spread across representation tests, name-keyed state, and copied declaration parameters. Owned
   function rebinding and function-valued fields do not consistently use ordinary ownership rules.
9. Semantic and backend capture-layout algorithms duplicate work, while lowered operands lose source binding IDs. The
   duplicate-capture report is one consequence; ownership correctness also depends on preserving identity.
10. Rewriters can swap or discard type fields, omit lambda traversal, and create cleanup lambdas without the ordinary
    typed/captured-lambda contract. Some of these faults predate this branch. Validate phase boundaries and generated
    ASTs.

Do not equate completed slices, an AST class named `Lambda`, or a green focused suite with semantic unification. The
reported 96 focused passing tests in the rescue review are historical evidence, not validation of the restart.

## Suggested restart sequence

1. Confirm the working branch and preserve this handoff plus the two untracked reviews. Keep `c16231a` recoverable.
2. Transfer all source agent instructions, context instructions, and other documentation into the migration branch,
   including the memory model, PAP spec, reviews, historical tracking, and independent design documents. Preserve their
   changes first; discuss the inconsistencies above separately without weakening the accepted ownership decisions.
3. Inventory and preserve the complete tests/helpers/samples corpus. Record original-to-migrated test mappings and
   establish the parent's actual baseline. Do not import compiler machinery solely to make representation tests compile.
4. Agree on a small semantic contract and its tests: consistent lambda typing/captures, binding identity, transfer at
   each sink, supplied-argument evaluation, and generated-AST validity. Resolve immediate binding versus borrowing
   calls.
5. Choose the first compiler workstream with the Author. Complete destructor ASTs are a proposed starting point, not an
   already-approved implementation plan. Preserve the existing cleanup regressions while changing the mechanism.
6. Build common ownership and PAP elaboration, then consolidate capture/argument lowering. Reintroduce Direct lowering
   and tail-recursion optimizations against those contracts. Review each workstream before starting the next.
7. Reconcile tracked-item and GitHub status separately after workstream signoff. Preserve the source history throughout.

For compiler work, follow the actual checked-out `context/coding-rules.md` post-task requirements. Use `sbtn`, never
parallel `sbtn` sessions. Format/lint, fix warnings, run the full unit suite and required smoke programs, and complete
the required QA review. Memory-related changes require the full memory harness and its leak checks.

The memory harness and benchmarks use the installed `mmlc`. After switching branches, an installed compiler may still
come from the abandoned branch. Validate the checked-out compiler with `sbtn`, then publish it as required before using
those tools. Do not mistake a passing run against the old installed compiler for validation of the restart.

No compiler tests were rerun for this documentation-only handoff. Verification here checks the recovery hashes,
inventory completeness, cited local paths, and document formatting.

## Prompt for the next session

> Read AGENTS.md, then context/unify-lambdas-salvage.md. We are restarting lambda unification from
> dev-2026-03-21-lambdas at c7e9078. Recover the memory-model documentation and preserve every test from c16231a.
> Move all agent-instruction, context-instruction, and other documentation changes into the new migration branch,
> including additions, edits, renames, deletions, and the untracked handoff/review documents.
> Read the source memory model and PAP ownership spec before proposing compiler changes. No hidden cloning;
> ownership must remain consistent through aliases, captures, PAPs, and struct fields. Check what has already been
> transferred, propose the next small workstream, and discuss it with me. Use MML for language examples.

## Complete changed-file inventory

This manifest is generated from `git diff --name-status --find-renames c7e9078 c16231a`. It accounts for all 153
entries: 26 documentation/workflow/tooling entries, 67 samples, 28 compiler entries, 20 Scala test/helper entries, and
12 memory tests. `A` means added, `M` modified, `D` deleted, and `R` renamed. Renames include their original path on the
next line.

Preservation rules for each group are given above. The compiler group is a review inventory, not a bulk-port list. The
untracked handoff/reviews and unchanged parent tests are additional to this manifest.

### Documentation, workflow, benchmarks, and tooling (26 entries)

```text
M AGENTS.md
M CLAUDE.md
D GEMINI.md
M benchmark/Makefile
A benchmark/matmul-opt.rs
A benchmark/matmul.rs
M context/coding-rules.md
M context/dev-tools.md
M context/qa-misses.md
M context/qa-rules-and-coding-style.md
A context/specs/bug-lambda-lifter.md
A context/specs/pap-ownership-model.md
M context/specs/unify-lambdas-plan.md
M context/specs/unify-lambdas.md
M context/task-tracking-rules.md
M context/tracking.md
A docs/brainstorming/compiler/Actionable-codegen-issues.md
M docs/brainstorming/language/intrinsic-arrays.md
M docs/brainstorming/parser/reduce-backtracking.md
M docs/brainstorming/snippets/lib.mml.txt
M docs/brainstorming/typer/refinement-types.md
M docs/design/compiler-design.md
M docs/language-reference.md
M docs/memory-model.md
A docs/mml-style-guide.md
M tooling/vscode-llvm-ir/package-lock.json
```

### Scala tests and helpers (20 entries)

```text
M modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/ClosureCodegenTest.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/FunctionSignatureTest.scala
A modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/LambdaEquivalenceCodegenTest.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/TailRecursionLoopificationTest.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/TbaaEmissionTest.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/grammar/LambdaLitTests.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/AlphaOpTests.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/AppRewritingTests.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/CaptureAnalyzerTests.scala
A modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/LambdaEquivalenceSemanticTest.scala
A modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/MaterializationAnalyzerTests.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OpPrecedenceTests.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipAnalyzerTests.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ParsingErrorCheckerTests.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeCheckerTests.scala
M modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeUtilsTests.scala
R modules/mmlc-lib/src/test/scala/mml/mmlclib/test/ast/AstExtractors.scala
  from modules/mmlc-lib/src/test/scala/mml/mmlclib/test/extractors/TXAstExtractors.scala
A modules/mmlc-lib/src/test/scala/mml/mmlclib/test/ast/AstTraversal.scala
R modules/mmlc-lib/src/test/scala/mml/mmlclib/test/ast/LambdaTestQueries.scala
  from modules/mmlc-lib/src/test/scala/mml/mmlclib/test/extractors/TXLambdaHelpers.scala
D modules/mmlc-lib/src/test/scala/mml/mmlclib/test/extractors/TXTermTraversal.scala
```

### Memory harness programs (12 entries)

```text
A tests/mem/consume-closure.mml
A tests/mem/direct-move-closure.mml
A tests/mem/direct-move-literal-capture.mml
A tests/mem/direct-move-owned-string-capture.mml
A tests/mem/direct-move-owned-struct-capture.mml
A tests/mem/direct-pap-escaping-heap-alias-arg.mml
A tests/mem/direct-pap-escaping-heap-arg.mml
A tests/mem/direct-pap-escaping-heap-capture.mml
A tests/mem/direct-pap-inscope-heap-capture.mml
A tests/mem/escaping-paps.mml
A tests/mem/struct-field-move-closure.mml
A tests/mem/struct-field-nested-move-closure.mml
```

### MML samples (67 entries)

```text
M mml/samples/and-not-or.mml
M mml/samples/array_int_test.mml
M mml/samples/array_str_test.mml
M mml/samples/astar2.mml
M mml/samples/bad-line.mml
M mml/samples/big-struct-rand-input.mml
M mml/samples/big-struct-rand.mml
A mml/samples/borrow-escape-test.mml
A mml/samples/bubblesort.mml
M mml/samples/buffer_fd_size_test.mml
M mml/samples/buffer_test.mml
M mml/samples/captures.mml
A mml/samples/closure-free-shapes.mml
M mml/samples/concat-fn.mml
M mml/samples/concat_borked_partial.mml
M mml/samples/convoask.mml
M mml/samples/convoluted.mml
M mml/samples/custom-op.mml
M mml/samples/debug_to_string.mml
M mml/samples/double-and-sum.mml
M mml/samples/fd_test.mml
M mml/samples/fn-aliasing.mml
M mml/samples/function-lambda-combinations.mml
M mml/samples/hola.mml
M mml/samples/lambda-factorial.mml
A mml/samples/lambda-forms/borrow-capture-consuming-fail.mml
A mml/samples/lambda-forms/borrow-capture.mml
A mml/samples/lambda-forms/direct-inline-lambda.mml
A mml/samples/lambda-forms/direct-let-lambda.mml
A mml/samples/lambda-forms/direct-local-fn.mml
A mml/samples/lambda-forms/direct-top.mml
A mml/samples/lambda-forms/equivalence-ir-inspection.mml
A mml/samples/lambda-forms/first-class-non-capturing.mml
A mml/samples/lambda-forms/move-capture.mml
A mml/samples/lambda-forms/struct-field-borrow-fail.mml
M mml/samples/lambda_multi.mml
M mml/samples/no-main.mml
M mml/samples/nullary_app_fail.mml
M mml/samples/op-partial.mml
M mml/samples/parse-error-later.mml
M mml/samples/partial-app-inline.mml
M mml/samples/partial-app.mml
A mml/samples/partial-fac1.mml
A mml/samples/partial-fac2-escape.mml
M mml/samples/partial-inline-simple.mml
M mml/samples/person-struct-borrow-global.mml
M mml/samples/person-struct-input.mml
M mml/samples/person-struct-with-partial.mml
M mml/samples/person-struct.mml
M mml/samples/print_string.mml
M mml/samples/quicksort.mml
M mml/samples/readline-loop-final.mml
M mml/samples/shift_test.mml
M mml/samples/should_fail.mml
M mml/samples/sieve.mml
M mml/samples/simple_string.mml
M mml/samples/simple_temp.mml
M mml/samples/small.mml
M mml/samples/str_to_int.mml
A mml/samples/style-guide.mml
M mml/samples/test-poisoning.mml
M mml/samples/test_negative_add.mml
M mml/samples/test_print_add.mml
M mml/samples/test_read_file.mml
M mml/samples/test_to_string.mml
M mml/samples/test_to_string_extended.mml
M mml/samples/typefn-nonclosure-values.mml
```

### Compiler implementation (28 entries)

```text
M modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeUtils.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/types.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/ExpressionCompiler.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/FunctionEmitter.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/Module.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Applications.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Conditionals.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/package.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/package.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/ParsingErrorChecker.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/SemanticStage.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/AstLookup.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ClosureMemoryFnGenerator.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ExpressionRewriter.scala
A modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/LoweredCaptureLayout.scala
A modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/MaterializationAnalyzer.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/MemoryFunctionGenerator.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/ErrorPrinter.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/SemanticErrorPrinter.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/SourceCodeExtractor.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Term.scala
M modules/mmlc-lib/src/main/scala/mml/mmlclib/util/prettyprint/ast/Type.scala
```
