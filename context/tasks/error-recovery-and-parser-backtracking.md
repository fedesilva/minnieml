# Error recovery and parser backtracking

## Metadata

- **Owner:** Author
- **Status:** complete
- **Kind:** improvement
- **Priority:** HIGH
- **Created:** 2026-09-25
- **Target Branch:** dev/parser-and-error-improvements

## Execution Checklist

Implementation, compiler verification, and documentation are accepted. All twelve steps are
complete; the implementation is delivered to `main` in squash commit `9d4f4740`.

1. [x] **complete** — Rename `TermError` to `ParseTermError`, including its diagnostic
   wrapper, consumers, tests, and current documentation; preserve behavior.
2. [x] **complete** — [Define diagnostic causes](#step-2-diagnostic-cause-contract): retain
   the specific source error and location, support multiple causes, and classify ownership/PAP
   suppression versus unavailable-value checks.
3. [x] **complete** — Split `InvalidType` into `UndefinedTypeError` and `AmbiguousTypeError`;
   preserve the original reference, ambiguous candidates, and source diagnostic relationship.
4. [x] **complete** — Preserve local normalization results: retain successful sibling and
   continuation rewrites when another expression fails, with stable IDs, spans, and indexes.
5. [x] **complete** — Propagate causes through semantic analysis, bindings, and aliases;
   reconcile ownership/PAP suppression, suppress proven consequences in CLI/LSP presentation,
   and preserve independent errors and the codegen gate.
6. [x] **complete** — Add `ParseTypeError` and downstream handling, including ingest reporting
   from type positions; preserve the distinction between missing annotations and malformed ones.
7. [x] **complete** — Implement shared context-aware recovery and apply it to type ascriptions:
   preserve complete type syntax, enclosing delimiters, strings/comments, nesting, and progress.
8. [x] **complete** — Add local recovery and commitment to local/member `let` and `fn` forms;
   preserve bindings, lambda/application structure, and independent continuations.
9. [x] **complete** — Factor conditional prefixes and add recovery/commitment for `if`/`elif`/`else`
   in both expression contexts; preserve valid no-`else` forms and check lambda-cut interactions.
10. [x] **complete** — Integrated verification, parser performance comparisons, compiler
    gates, and final correction review pass. Semantic performance evidence is in step 11.
11. [x] **complete** — Correct the semantic performance regression while preserving
    diagnostic causes and unavailable-value behavior. Paired measurements, compiler gates,
    and independent correction review pass.
12. [x] **complete** — Explain the complete implementation in
    [Keeping useful compiler results after an error](../../docs/articles/2026-10/2026-10-25-error-recovery-and-diagnostic-causes.md),
    with Scala/MML walkthroughs, ASCII diagrams, and qualified performance evidence.

## Problem

Error recovery does not reliably preserve valid structure in complex expressions.
A single undefined reference can prevent normalization of an entire function and
cause misleading type and ownership diagnostics on otherwise valid expressions.
Parser backtracking also needs reduction without sacrificing local recovery.

## Outcome

Malformed programs retain their valid surrounding lambda/application structure.
Diagnostics identify primary failures, suppress their dependent consequences by
default, and continue reporting independent errors. Parser commitment reduces
unnecessary reparsing while recovery remains local and always makes progress.

## Scope

Design source: [Reduce backtracking: commit and recover](../../docs/brainstorming/parser/reduce-backtracking.md).

- Error-aware semantic phases, including `ExpressionRewriter`, type checking, and
  ownership analysis.
- Recovery from reference-resolution and other semantic failures as well as parser
  errors; cover `InvalidExpression` as well as `ParseTermError`.
- Preservation of valid sibling expressions and enclosing lambda/application structure.
- Cause information that survives bindings and later uses, without inventing valid
  type or ownership facts for erroneous input.
- Local parser cuts and recovery for `let`, `if`, `fn`, and type ascriptions, with
  nesting-aware synchronization and guaranteed progress.
- Focused correctness tests and uninstrumented performance comparisons alongside
  parser counters.

Struct mutation and intrinsic arrays are separate features. This task does not add
syntax to make invalid assignment examples compile.

## Dependencies and order

[Unify lambdas](unify-lambdas.md#task-working-memory) is complete and signed off.
Its recovery groundwork is available on `main`. Independent follow-ups do not require
clearing the entire bug backlog before this task.

The [parenthesized-lambda parser bug](parser-parenthesized-lambda-arguments.md) overlaps the
grouping/selection commitment boundaries. The approved parser plan includes its reproducer as
a commitment-containment control. The prerequisite cut containment fixes that shared failure;
its tracked item remains open, with acceptance evidence collected here.

### Parser recovery acceptance scenario for steps 7–8

The [parenthesized-lambda task](parser-parenthesized-lambda-arguments.md#verification)
records that the valid `mml/samples/errors-are-not-granular.mml` produces six member
parse errors, beginning at `pub fn main` and continuing over later statements and the
closing delimiter. The actual failing token is not identified.

Require the valid sample to compile with the prerequisite cut containment. Then use a
variant with malformed lambda syntax inside the function body to verify local recovery:
identify the failing token, retain the `main` binding and its independent statements,
and avoid a cascade of member errors. Establish the valid control before this recovery
case so that a grouping rejection cannot mask the recovery behavior. The parser plan below covers commitment containment and the malformed recovery fixture.

### Existing diagnostic groundwork

The [nested-parser diagnostic repair](unify-lambdas-ignored-tests.md#nested-parser-diagnostics)
in Unify lambdas is implemented and verified. `ParsingErrorChecker` collects recovered
`ParseTermError` nodes inside binding expressions while preserving recovered syntax.
The cause implementation adds metadata-only ingest stamping to that path.
`SemanticError.ParseTermErrorFound` retains their source, message, and failed code through CLI
and editor reporting. Tests cover nested errors, accumulation, recovery, CLI rendering,
and editor ranges.

Reuse this diagnostic path and its regressions during recovery implementation.
The cause implementation preserves valid semantic structure and connects dependent diagnostics
to their roots. The parser stages cover commitment, synchronization, termination, and performance.
Their verification is recorded below; the nested-diagnostic repair alone does not establish it.

## Design reconciliation

Source inspection at `87e899c` on 2026-10-25 establishes the following baseline;
no compiler execution or performance measurement is included in this reconciliation.

- [ExpressionRewriter](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ExpressionRewriter.scala)
  still rejects `InvalidExpression` and retains the original entire binding when rewriting
  returns errors. Recursive `EitherT` composition can discard successful sibling rewrites.
  Local preservation remains necessary; the historical diagnostic list below needs a fresh run.
- [ValueAvailability](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ValueAvailability.scala)
  already propagates unavailable results through resolved binding identities and aliases.
  It rejects `TermError`, `InvalidExpression`, and non-single-term expressions. This is a
  post-typing availability contract, not a general diagnostic-cause representation or an
  early normalization predicate.
- Type checking retains failed application arguments inside `InvalidExpression`.
  PAP elaboration continues through independent members and statement continuations.
  Ownership analysis preserves malformed expression payloads and checks their children for
  independent errors without treating a flat term list as a sequence. Reuse the assertions in
  [ElaborationRecoveryTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ElaborationRecoveryTests.scala)
  and [OwnershipExpressionTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipExpressionTests.scala).
- [SemanticStage](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/SemanticStage.scala)
  refreshes indexes across rewrites. Recovery must preserve binding IDs, source origins,
  `Callee = Ref | App | Lambda`, and the existing local-binding/statement application shape.
  Do not manufacture a callable or widen `Callee` merely to carry an error.
- `CompilationError` has no common cause identity. AST `Error` does not cover all semantic
  errors or `InvalidExpression`. Adding `cause` only to AST `Error` would leave semantic
  failures and later alias uses disconnected. Existing ownership suppression is specialized.
- The parser already cuts after a parameterized lambda's arrow. Expression and member-body
  conditionals each have separate full/no-`else` alternatives. Ascriptions parse an optional
  `:` followed by `typeSpecP`, including function and grouped types. Recovery must cover these
  actual paths; a nominal `typeRefP` fallback is insufficient.
- [CodegenStage](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CodegenStage.scala)
  already disables emission when errors exist. Suppression must affect diagnostic presentation,
  not remove the error state used by this gate.

## Agreed error model

These decisions define the implemented behavior. The bounded plans below specify the cause
and parser contracts. All execution steps and documentation are signed off; the source
baseline above retains its original names.

- Add `ParseTypeError` for missing or malformed type syntax. Rename `TermError` to
  `ParseTermError`, including diagnostic wrappers, consumers, tests, and documentation.
  Both parse-error nodes retain source location, message, and failed text.
- Replace the broad `InvalidType` wrapper with two distinct resolution errors:
  `UndefinedTypeError` when no type matches, and `AmbiguousTypeError` when several match.
  Both retain the original type reference and link to their diagnostic cause; the ambiguous
  error also retains the matching candidates. Neither represents a parsing failure or an
  ordinary type mismatch.
- A malformed annotation stays present as an error. It must not become an absent annotation
  that permits inference to silently accept the declaration. Preserve valid surrounding
  syntax and leave recovery delimiters for their owning parser. Extend ingest error
  collection to type positions and keep erroneous values unavailable to later analysis.
- Suppress dependent diagnostics at presentation time. Retain an explicit relationship to
  the specific source error, including its location, rather than only a secondary-error flag.
  Preserve primary and independent diagnostics and the error state that blocks codegen.
- Reconcile existing ownership and PAP-related suppression with this cause model. Distinguish
  filtering a proven consequence from declining analysis whose prerequisites are unavailable;
  do not manufacture ownership facts or diagnostics merely to suppress them later.

The approved implementation uses one diagnostic store, cause-bearing failed results, and
result-directed propagation. The execution checklist separates this work from later parser
recovery and commitment.

## Plan (Approval Gate)

The [Execution Checklist](#execution-checklist) is the delivery roadmap. Detailed plans are
prepared per stage, using the [agreed error model](#agreed-error-model) as the contract.

- **Rename:** change names without changing parser decisions, recovery, diagnostic content,
  or source spans. Use the existing nested-parser, CLI, and editor regressions. Preserve
  historical evidence under the names used by its recorded compiler revision.
- **Diagnostic contract and resolution errors:** settle storage and propagation before
  implementation. Check actual resolution paths for undefined and ambiguous references;
  preserve valid sibling types and original candidate evidence. Do not invent ambiguity
  merely to populate the new node. Keep unavailable-value analysis separate from filtering
  diagnostics that have known causes.
- **Semantic recovery:** reproduce the embedded undefined-reference case and a nested
  recovered parse error. Retain transformed subtrees alongside accumulated errors without
  inventing application structure for an unknown operator or callee. Verify normalization,
  independent diagnostics, and stable identities through the semantic pipeline. Then extend
  causal propagation and presentation-time suppression using the agreed contract; reuse
  elaboration and ownership recovery tests, including independent use-after-move.
- **Parse errors and recovery:** integrate `ParseTypeError` with type consumers and ingest
  before producing it from parser recovery. Capture a fresh parser baseline before changing
  parser decisions. Build recovery with explicit delimiter ownership and progress guarantees;
  exercise it first on complete type ascriptions, including arrows and grouped types.
  Apply the same machinery to binding/function forms and then conditionals. Check the
  existing lambda-arrow cut and the separately tracked grouping/selection reproducer.
- **Acceptance at each stage:** verify relevant behavior and run the required compiler checks
  for that change. Parser stages require missing-input, EOF, nesting, strings/comments,
  exact-span, preserved-structure, and termination cases. Final verification compares valid
  programs and uninstrumented parser timings alongside counters; counter reductions alone
  do not establish a speedup. Keep correctness and performance evidence separate.

Approval covers the diagnostic foundation, accumulating resolvers, failed-result handling,
propagation, presentation suppression, parser stages 6–10, and semantic performance correction.
Implementation, chunk reviews, compiler gates, and documentation review are complete.
Delivery to `main` is recorded under Signoff.

Step 1 preserves fields, constructors, inheritance, and absent type information. Use
the new names directly, without compatibility aliases. Preserve CLI/LSP messages,
severity, locations, snippets, diagnostic order, and counts; rename the developer AST
dump label. Add no new error types, cause tracking, parser cuts, or recovery changes.
Reuse the existing regressions without adding rename-only tests. A reproduced behavioral
regression warrants its own test. The roadmap remains intact; later stages retain their
bounded plans and whole-branch signoff.

### Step 2: diagnostic-cause contract

**Scope:** the complete compiler cause system, delivered in reviewed chunks across steps 2–5.
**Approval:** implementation authorized. Targeted invalid-result wrappers retain partial types
for editor queries while making failed values unavailable and preserving operand traversal.
Parser cuts and new syntax recovery are outside this implementation.

- [x] Inspect diagnostic storage, error-node creation, presentation, and existing suppression.
- [x] Settle diagnostic identity, original source retention, and multiple-root semantics.
- [x] Trace propagation through resolution, normalization, bindings, aliases, and generated
  syntax; choose the smallest carrier that survives those transformations.
- [x] Specify consequence proofs, deduplication, prerequisites, and the shared presentation view.
- [x] Agree acceptance scenarios and map implementation into the remaining roadmap stages.

#### Approved implementation contract

`DiagnosticStore` owns ordered records and compilation-local IDs. Each record retains its
original error payload and is primary or a consequence of known primary IDs. Raw errors
remain authoritative for codegen; CLI, quiet API, and LSP select the shared visible view.
Missing or invalid causal references cannot hide diagnostics.

Recovered parse errors are stamped after parsing. Accumulating resolvers allocate IDs with
failed results, preserve independent occurrences, and keep valid sibling types/references.
Parser-copied type annotations share their resolved occurrence through a traversal-local
identity memo; separate equal references remain independent. This reuses parser provenance,
not diagnostic deduplication by message, span, or structural equality.

Persistent causes live on failed expression/type nodes. Targeted `InvalidExpression`
wrappers preserve operands and partial editor types while making failed results unavailable.
`Callee` stays `Ref | App | Lambda`; failures cross that boundary through the enclosing
application. Normalization retains completed children and does not invent precedence for a
flat failed region. Existing printers retain their legacy diagnostic labels for bare errors.

Cause queries follow result dependencies and resolved binding IDs. An unused failed local
initializer does not taint an independent continuation. Known roots and unknown evidence
are separate; complete evidence is required to justify a consequence. The emitting rule
must still prove dependence: a failed predicate cannot explain an independent branch mismatch.
Availability remains separate from causes.

Ownership reports retain proven borrowed-return and move consequences, with canonicalization
of repeated observations kept separate. Immediate malformed payload shape errors use only
their own wrapper's known roots. PAP/ownership/callable guards remain prerequisites: invalid
results cannot supply callable or ownership facts, while their operands and independent
continuations remain analyzable. Invalid closure layouts keep their existing prerequisite guard.

The tests cover records, ingest identities, resolvers, normalization, aliases, shadowing,
failed-result propagation, independent ownership failures, and presentation. Full compiler
verification and independent review are required before signoff; the verification section
records actual completed checks and open failures.

#### Source findings and implementation boundaries

Historical source inspection at `931c6ff`; no runtime or performance claims:

- [CompilerState](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CompilerState.scala)
  stores raw errors without IDs or causes. Prefer one authoritative record collection over
  a parallel metadata table whose entries can drift after error-list updates.
- [RefResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/RefResolver.scala)
  uses fail-fast `Either.traverse` within a binding, then separately rewrites all ordinary
  unresolved term references as invalid expressions. Independent failures can therefore
  produce invalid nodes without corresponding primary diagnostics. Use one accumulating
  resolution result containing both rewritten syntax and diagnostics; matching the two
  passes afterward by span is unsound. Unresolved callees remain `Ref`, so recovery must
  preserve the failed region without manufacturing an application of an unknown callee.
- [TypeResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeResolver.scala)
  has the same fail-fast pattern. Its fallback receives a module with no members, and its
  lookup reads only members: every type reference visited by that fallback becomes
  `InvalidType`, including valid sibling annotations and cached literal types. Some type
  positions are omitted by the fallback, so this is not a claim about all positions.
  These two resolver defects are confirmed by static control flow; focused runtime
  regressions remain required. Existing tests do not assert complete primary-error
  accumulation or valid sibling-type retention.
- [TypeChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala)
  can report a branch-type mismatch independently of an erroneous predicate, then retain
  an ordinary `Cond`. An upstream root does not prove that mismatch is consequential.
  Failed applications retain their argument in `InvalidExpression`; several application
  errors can cause that wrapper. Its roots must resolve to retained records even when
  `filterDuplicateUnresolvable` removes a redundant report.
  Conditional checks also omit unresolvable-condition and unknown-branch reports when
  children already report errors. Classify each guard as prerequisite handling,
  canonicalization, or a proven consequence to retain for presentation-time filtering.
  Do not infer that every omitted diagnostic must be manufactured.
- [ParsingErrorChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/ParsingErrorChecker.scala)
  and its design contract promise no AST rewrites. Stamping cause IDs during ingest needs
  an explicit metadata-only rewrite contract, preserving recovered structure and locations.
- [OwnershipAnalyzer](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala)
  filters borrowed-return consequences and repeated moves before adding errors to state.
  `normalizeMoveErrors` also canonicalizes written/derived duplicates. Invalid payload
  traversal removes shape diagnostics without explicit source-error identity. Classify and
  migrate these operations separately; preserve distinct owners and distinct use sites.
- [ValueAvailability](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ValueAvailability.scala)
  and PAP/callable guards check prerequisites. They are not a general cause carrier, and
  unavailable input must not generate hypothetical ownership errors just to suppress them.
- [ClosureDestructorBodyGenerator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ClosureDestructorBodyGenerator.scala)
  avoids a missing-metadata consequence for an already-invalid environment layout. Preserve
  that prerequisite behavior while retaining the original environment diagnostic.

Acceptance coverage traces one root through aliases; two independent roots feeding
one failed result; copied/generated nodes sharing spans; and a failed initializer followed
by a valid continuation. Include unknown cause evidence, mixed rejected/independent callable
alternatives, independent ownership errors, and two owners reported at the same use site.
Add two undefined references within one binding, two undefined types beside valid types,
an undefined callee, and an invalid predicate with independently incompatible branch types.
Each trace must identify the original record, transferred references, visible diagnostics,
and the unchanged error gate. Reuse the existing ownership and elaboration recovery tests
as behavioral evidence; a design trace is not an executed test.

The Author approved implementing these boundaries together, with reviews between chunks:
foundation and type resolution (steps 2–3), reference resolution and local normalization
(step 4), and failed-result handling, propagation, ownership/PAP prerequisites, and shared
presentation (step 5). New parser error production and parser cuts remain outside this scope.

### Parser implementation plan: steps 6–10

1. Capture reproducible warmed timings through `Parser.parseModuleWithInfo`, with input hashes,
   sample distributions, toolchain details, and separately collected instrumentation counters.
   CLI ingestion always uses the instrumented parser, so CLI elapsed time is not this baseline.
2. Add `ParseTypeError` as a malformed-syntax carrier, distinct from type lookup failures.
   Extend ingest and consumers across all type slots. Mirrored parser annotations share a
   stamped occurrence; separate equal occurrences retain separate roots. Test constructed ASTs
   before recovery produces these nodes.
3. Add one pure context-aware recovery scanner that leaves synchronization anchors unconsumed,
   respects strings/comments/nesting, and permits context-exclusive stops after unmatched openers.
   Apply it to complete annotations, with separate lambda-parameter and declaration contexts.
   Contain speculative selection/group cuts before committing after a colon; validate dangling
   arrow tails without consuming a valid lambda separator or valid expression juxtaposition.
4. Recover local/member binding and function components before adding keyword cuts. Retain
   named bindings, failed initializers, lambda/application structure, and independent continuation
   expressions. Missing names do not create resolvable bindings. Each repetition must progress.
5. Factor shared conditional prefixes, retaining expression/member parser distinctions and
   synthesized Unit behavior for absent `else`. Recover conditions/branches at their own anchors;
   preserve nested conditionals and enclosing delimiters.
6. Test EOF, unmatched delimiters, comments/strings, exact spans, multiple independent errors,
   preserved valid structure, and bounded completion. Compare valid ASTs and the same timing
   corpus against baseline; treat counters separately from performance. Complete applicable
   compiler gates and independent reviews before the whole-branch handoff.

Existing MML syntax, operator precedence, valid-program semantics, and source spans remain the
contract. Parser-rule refactoring adds recovery and commitment without designing new syntax.

The parenthesized-lambda reproducer is a commitment-containment control. If the necessary
selection/group restructuring fixes that shared boundary, include its acceptance cases and
record the causal connection. Do not broaden the work to unrelated parser features.

#### Step 7: type-recovery chunk

- Give type parsing an explicit surrounding context: expression ascription, declaration result,
  ordinary parameter, lambda parameter, field, alias, or grouped type. Share recovery and type
  construction; keep each caller's delimiter ownership visible.
- Once `:` is present, retain a type result even when its text is missing or malformed. Preserve
  valid function-type components and grouped syntax where the AST can represent them faithfully.
  Failed fragments retain exact spans and text; missing fragments may have zero-width spans.
- Use the scanner to preserve enclosing punctuation, subsequent parameters/fields/declarations,
  and declaration doc comments. Strings, line comments, and nested block comments mask anchors.
  Operator result recovery must preserve precedence/associativity without stopping inside a name.
- Contain annotation commitment within speculative callers. A lambda parameter's final ungrouped
  arrow belongs to the lambda when no valid type atom follows it. Preserve the nullary-lambda
  alternative, unary/binary operator alternatives, and native representation declarations.
  Expression ascriptions still permit valid juxtaposed application terms after a complete type.
- Test real malformed source through parsing, ingest, semantic analysis, CLI, LSP, and the raw-error
  codegen gate. Cover missing/absent annotations, dangling arrows, nested groups, multiple independent
  errors, exact spans, sibling retention, boundary/EOF progress, and unchanged valid controls.
- Run formatting, lint, focused/full tests, applicable compiler gates, QA, and independent review.
  Commit the verified chunk locally; whole-branch signoff is recorded under Signoff.

#### Step 8: binding and function recovery chunk

- Share parsed names, parameter-list status, required-token recovery, and expression-frame
  recovery. Keep member modifiers, local move syntax, source-span policies, terminator ownership,
  and final member/local lowering explicit at their callers.
- Preserve valid expression prefixes as nested `Expr` values followed by terminal `ParseTermError`
  nodes. Resolution visits those children before normalization creates failed-result wrappers.
  Do not produce parser-origin `InvalidExpression`, whose early-phase payload is not traversed.
- A complete function header retains its lambda and real parameter scope despite a malformed body
  or missing `=`. An incomplete list may retain a nonempty successfully parsed parameter prefix
  in a lambda whose body ends in the header error. Omit complete binding metadata, computed types,
  and synthesized function ascriptions. With no established parameter prefix, keep a failed value
  without fabricating a nullary lambda. Preserve the named local binding's outer scope application.
- Prove partial function values and their aliases remain unavailable: typing, PAP elaboration,
  closure layout, and ownership must not promote prefix parameters to a usable callable signature.
  Normalization can structurally eta-expand a bare reference from a leading lambda before typing;
  test that its reference to the failed binding preserves the error and blocks callable use.
- Preserve local initializers and independent continuations, including missing names through the
  existing synthetic statement-chain shape. Nameless member errors retain source text and following
  members; the current member error carriers cannot hold analyzable initializer children.
- Recover within owned expression frames so a malformed later term does not erase valid prefixes
  or subsequent statements. Leave outer delimiters and conditional boundary keywords unconsumed.
  Functions retain distinct body and declaration semicolons. `let` and `fn` are valid local terms,
  so they cannot be unconditional function-body stops; missing `;;` before a bare declaration is
  ambiguous without adding a new heuristic. Preserve unambiguous member/doc-comment boundaries.
- Add boundary-checked keyword cuts only after each committed suffix has local recovery. Every
  repeated frame must consume a term, malformed fragment, or semicolon; zero-width errors are
  permitted only after an introducer already advanced input.
- Cover valid spans/lowering, malformed names/headers/bodies, lexical identities, partial-header
  unavailability, independent normalization, nested strings/comments/delimiters, exact diagnostic
  roots, the grouped-lambda recovery scenario, and bounded EOF completion. Run compiler gates,
  QA, independent review, and a local commit before conditional recovery.

#### Step 9: conditional recovery chunk

- Share the initial clause, repeated `elif` clauses, optional `else`, and lowering. Keep expression
  and member condition parsers explicit; both branch contexts use the ordinary expression parser.
- Preserve complete valid ASTs and whitespace spans. Without `else`, synthesize the final
  zero-width Unit expression; the outer conditional has Unit ascription and computed type,
  while nested `elif` conditionals have only the computed Unit type. Explicit `else` adds neither.
- After each consumed conditional keyword, return a recovered component even at an immediate
  boundary or EOF. Missing conditions, bodies, and semicolons retain their containing clauses.
  Missing `then` retains all parsed condition terms rather than guessing a condition/body split;
  use one missing-boundary error in the true branch and preserve following clauses.
- Preserve semicolon sequencing in expression conditions. Shared expression frames may recover
  a nonempty failed fragment up to a boundary-checked `if` and resume structural term parsing.
  A conditional parser that is total after its introducer then owns its nested keywords; do not
  add a second conditional grammar to the scanner or turn those keywords into global hard stops.
- Each repeated fragment must advance; zero-width missing-component errors belong only to an
  owner that consumed an introducer. An unmatched opener in an opaque failed fragment can hide
  ordinary keyword anchors until EOF or a mismatched closer. Keep that conservative boundary
  explicit rather than claiming preservation of clauses whose nesting cannot be established.
- Cover full/no-`else` lowering, missing components, nested keyword ownership, sequenced
  conditions, independent later diagnostics and normalization, strings/comments, enclosing
  delimiters, speculative lambda/group cuts, and bounded EOF completion. Run integration and
  compiler gates, QA, independent review, and a local commit before final measurements.

### Semantic performance correction

The correction preserves error payloads, root identities, completeness, partial editor types,
independent operand analysis, recursive/forward references, and presentation-only suppression.

- Cache explicit failed-type IDs and type-cause evidence against stable type declarations.
- Thread immutable cause and availability summaries through member checking. Reuse healthy,
  unchanged evidence; rebuild when failures or incomplete evidence require propagation.
- Avoid ownership scans whose results cannot be used by empty capture/return sets.
- Resolve synthetic conditional result types before cause analysis, including no-`else` and
  nested `elif` caches; preserve shared-reference diagnostic identity.
- Test type-cache refresh, recursive types, failures and incomplete evidence. Run all required
  compiler checks, then compare the same input and commands against `main` in fresh JVMs.
- Keep parser source-boundary work separate. Local review replaces Claude coordination.

## Verification

### Documentation handoff: verified

[Keeping useful compiler results after an error](../../docs/articles/2026-10/2026-10-25-error-recovery-and-diagnostic-causes.md)
explains the implemented cause model, parser recovery, and analysis reuse through Scala/MML
walkthroughs and progressive ASCII trees. The compiler-design reference links to the article.
The article distinguishes source-derived explanations, test assertions, native execution,
sanitizer coverage, and local performance measurements.

All 30 article links and anchors resolve. Its 20 ASCII diagrams fit within 100 columns,
four tables have aligned raw Markdown columns, fences are balanced, and whitespace checks
pass. Local links in the affected task records and tracker resolve; the existing anchor
inside the unrelated parenthesized-lambda task is excluded from content inspection.

Fresh independent primary review and separate fresh QA/style, test-quality, and readability
checks cover the complete article and design backlink. One independently confirmed finding
corrects evaluation-order test attribution: the semantic suite proves parameter identity and
argument association; `RuntimeTests` executes the printed-effect fixture and checks exact
output. A fresh narrow primary review and three new dedicated checks verify that correction
with no actionable findings. All required reports are complete; no fallback is used.
Reviewers inspect source, assertions, and recorded evidence without rerunning compiler gates.

No compiler source or test changes accompany this documentation handoff. The implementation
verification below applies unchanged. At this historical handoff checkpoint, tracking review
covered the accepted implementation and then-open documentation review and source-boundary
plan. `git diff --check` passed. Final acceptance is recorded under Signoff.

### Semantic performance baseline: regression confirmed

On arm64 macOS with GraalVM Java 25.0.4.1, `main` at
`87e899c16345376d6f2fd37a726771a8b65fb6cb` is built using `sbtn 'mmlc / assembly'`
in an isolated worktree. The branch compiler is the installed assembly from `81c2d302`;
its TypeChecker, OwnershipAnalyzer, and ValueAvailability sources match `6d160888`.
Both compile the unchanged `mml/samples/astar3_animated.mml` (SHA-256
`d6e824601ef8e4a82b23c7ac55af087b10244a6338ff925f268313ea5cc8e457`) with
`mmlc -mpPaI -b <separate-output-directory> <input>`. Each command starts a fresh JVM.
One priming compile per compiler is excluded; five measured compiles per compiler alternate
order by pair. All twelve compilations succeed. Medians in milliseconds:

| Measurement | Main | Branch |
| --- | ---: | ---: |
| Type checking | 38.66 | 1364.95 |
| Ownership | 433.75 | 708.75 |
| All semantic phases | 893.37 | 2453.91 |
| Whole command | 2041.80 | 3664.77 |

Type-checking samples are `37.99, 38.66, 55.19, 42.11, 38.07` on main and
`1333.06, 1363.27, 1364.95, 1400.17, 1461.19` on the branch. Ownership samples are
`432.82, 422.85, 433.75, 574.64, 475.94` and
`708.75, 846.88, 665.98, 706.17, 865.88`, respectively. These are local fresh-process
measurements, not steady-state JVM benchmarks. The type-checker phase includes indexing.
The earlier raw-parser measurements exclude semantic analysis and cannot establish its cost.

A separate JFR profile of the branch compiler identifies repeated type-evidence and invalid-type
walks. Source inspection confirms whole-module cause/availability reconstruction before each
member. Profile samples locate work; the before/after command comparison measures the correction.

### Semantic performance correction: verified

The correction reuses immutable named-type and healthy-binding summaries, retains full
rediscovery for failures or incomplete evidence, and avoids unused ascription/cause/ownership
scans. No-`else` conditionals carry a synthetic `Unit` in both cached and written type positions;
resolving only the written position leaves valid programs with incomplete cause evidence.
`TypeResolver` resolves both positions, sharing the existing occurrence identity cache.

The final measurement uses the same input hash, JVM, flags, priming, alternating order, and
five fresh-process samples as the baseline above. Main remains `87e899c1`; the correction is
an assembly built from `6d160888` plus the semantic correction recorded here. All twelve
compilations succeed. No competing builds or benchmarks run during measurement.

| Measurement (median ms) | Main | Corrected |
| --- | ---: | ---: |
| Type checking | 41.30 | 145.93 |
| Ownership | 447.04 | 340.56 |
| All semantic phases | 922.65 | 833.35 |
| Whole command | 2088.29 | 2048.92 |

| Pair | Main type check | Corrected type check | Main ownership | Corrected ownership | Main command | Corrected command |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 40.30 | 140.03 | 477.47 | 316.13 | 2159.56 | 1962.78 |
| 2 | 39.94 | 144.07 | 447.04 | 340.56 | 2015.31 | 2172.44 |
| 3 | 41.30 | 158.30 | 533.88 | 356.07 | 2227.30 | 2110.75 |
| 4 | 49.68 | 145.93 | 421.49 | 332.24 | 2088.29 | 2027.53 |
| 5 | 41.71 | 146.70 | 436.54 | 364.57 | 2086.50 | 2048.92 |

The observed whole-command median is back at main's level; this is a local observation, not
a general speed guarantee. Type checking remains about 105 ms above main, while ownership
and the complete semantic stage are faster in these samples. The correction removes the
large regression; it does not establish type-checker parity or warmed-JVM throughput.

Final formatting/lint and the five focused suites pass 90 tests, zero failures/errors:
`TypeResolverTests`, `TypeCheckerCauseTests`, `DiagnosticCausesTests`, `ParseTypeErrorTests`,
and `ValueAvailabilityTests`, all under `mml.mmlclib.semantic`.
They cover root propagation and replacement, recursive/forward dependencies, incomplete
proofs, type-cache refresh, discarded failures, and synthetic conditional type resolution.

Final compiler gates on arm64 macOS 26.6.2, GraalVM Java 25.0.4.1, and Homebrew Clang
23.1.1 run sequentially and pass:

- `sbtn scalafmtAll` and `sbtn scalafixAll`, followed by the five focused suites above.
- `sbtn testFull`: 1,864 library tests and nine CLI tests, zero failures/errors.
- `./tests/smoke/run.sh all`: 8/8 fixtures.
- `sbtn 'run run mml/samples/errors-are-not-granular.mml; mmlcPublishLocal'`: the program
  prints `6`, `6`, `4`, `3`; the corrected compiler is installed locally.
- `make -C benchmark clean` followed by `make -C benchmark mml`: 37 MML builds. These
  establish build compatibility; compilation-time evidence is the separate comparison above.
- `./tests/mem/run.sh all`: 52/52 ASan+LSan fixtures in 75 seconds. This covers executed
  host paths, not all ownership paths or other targets.

No ABI or layout changes require Docker checks; hosted validation is not run. The measured
and published compiler entries match except for generated build-information metadata.

Root QA enforcement finds no rule violations. Fresh-context independent primary review and
three separate fresh code-style/QA, test-quality, and human-readability checks complete with
no actionable findings. All four completed reports are audited; no candidates require claim
verification, no review coverage is incomplete, and no fallback is used. Reviewers inspect
the correction's six production files, four test files, affected design notes, and connected
callers. They do not rerun builds, native fixtures, sanitizers, or performance measurements.
Reviewed source identities remain unchanged through handoff.

Whole-record tracking review reconciles metadata, execution status, verification, signoff,
working memory, and tracker focus. Local link paths resolve and `git diff --check` passes.
At that verification checkpoint, parent-task and whole-branch signoff were still open;
final acceptance is recorded under Signoff.

### Step 1: term parse-error rename

Verified on 2026-10-25 on arm64 macOS with GraalVM Java 25.0.4.1 and Homebrew
Clang 23.1.1, target `arm64-apple-darwin25.6.0`.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed after the CLI-label correction;
  1,534 library tests and nine CLI tests, zero failures/errors/skips, and no compiler
  warnings reported.
  Existing `ParsingErrorCheckerTests`, `ParsingErrorPrinterTests`, and `DiagnosticsTests`
  retain their assertions for nested recovery, accumulation, preserved trees, source spans,
  failed text, and semantic/CLI/LSP messages.
- `./tests/smoke/run.sh all`: 8/8 passed on the final correction before local publication.
- `sbtn mmlcPublishLocal`: passed on the final correction after smoke verification.
- `make -C benchmark clean` and `make -C benchmark mml`: passed before the CLI-label
  correction; 37 benchmark binaries, including optimization/TCO variants. This verifies
  builds, not performance.
- `./tests/mem/run.sh all`: 52/52 passed ASan+LSan before the CLI-label correction.
  Benchmark and memory checks were not repeated for the diagnostic-label function;
  that correction changes only presentation and is covered by the final full suite.
- The first build attempt could not acquire the sbt cache lock under the sandbox;
  the authorized retry completed all checks above.
- Independent review confirmed that dangling-term diagnostics derive their CLI label from
  the runtime class name. The reproducer `let x = 1 let 123invalid = 5; 6;` changed
  `Term: TermError` to `Term: ParseTermError`. A focused regression failed on that exact
  difference before correction. Both CLI printers use a shared diagnostic-label function
  that preserves `TermError`; developer AST output uses `ParseTermError`.
- The correction adds one behavioral regression covering both printers. No compatibility
  type aliases are present. Old names remain only in the intentional CLI label/assertion,
  rename descriptions, and historical evidence. The compiler-design table-of-contents link
  is corrected to its existing heading.
- Codex's initial independent review and its separate QA, test-quality, and readability
  checks completed.
  Fresh narrow re-review of the correction and all three fresh dedicated checks completed
  with no actionable findings. Independent execution through the final published compiler
  confirmed `Term: TermError` at `[1:11]-[1:32]` for the reproducer above, with expected
  error exit status 1.
- Final QA enforcement, old-name search, `git diff --check`, and all 36 local links/anchors
  in the four affected documents passed. Tracking review reconciled the whole task and
  tracker at that checkpoint: step 1 complete and signed off; step 2 planning next.
- Claude's additional review of the complete diff completed under the repository code-review
  protocol: a fresh primary reviewer, three separate fresh QA/test-quality/readability
  checks, and independent candidate verification. No actionable findings remain.
  This supersedes its earlier direct parent-agent feedback outside the protocol. Claude
  ran no build or runtime checks; execution evidence remains the results recorded above.
- No ABI or target-layout changes; Linux ABI verification is not applicable.

### Steps 2–5: diagnostic cause implementation

Verified on arm64 macOS with GraalVM Java 25.0.4.1 and Homebrew Clang 23.1.1.

- `sbtn "scalafmtAll; scalafixAll; testFull"`: passed after review corrections;
  1,618 library tests and nine CLI tests, zero failures/errors, no compiler warnings.
  The final run retried an initial sandbox cache-lock denial with authorized filesystem access.
- `./tests/smoke/run.sh all`: 8/8 passed on the corrected source before local publication.
- Normal CLI check, `sbtn "run ast mml/samples/mem/struct-field-mutation.mml"`: expected
  error exit 1; only the original undefined `=` at `[8:14]-[8:16]` and its source snippet.
  No field-selection, unresolvable-type, or ownership-shape cascade appears. This was run
  before the final review corrections; the final suite covers shared presentation selection.
- Initial focused/full runs exposed repeated parser annotation reports, stale lambda types,
  mutual-recursion ordering, and invalid payload traversal creating PAP/ownership facts.
  Corrections are covered by the passing suite. Recursive structural type checks also have
  termination regressions. Existing presentation tests now distinguish raw records from
  displayed diagnostics; independent-error assertions are retained.
- Claude's foundation review completed with a fresh primary reviewer, three fresh dedicated
  checks, and independent per-candidate verification. Evidence was static, not executed
  mutation testing. It found no correctness defect within the foundation scope; it identified
  an independent call-argument mismatch hidden by failed-callee availability in the connected
  type checker. The correction checks usable parameter constraints independently and is
  covered by complete-call, partial-call, missing-annotation, and undefined-type controls.
- Review-driven foundation corrections remove unused partial restamping support, distinguish
  Complete/Incomplete evidence explicitly, document known versus proven roots, centralize
  relation validation, and add real-parser traversal and unknown-evidence cases. The small
  generic test projection remains; a shared tree transformer is outside this change.
- Local QA enforcement and its focused recheck completed with no remaining rule violations.
  `git diff --check` and all 49 local documentation links/anchors passed after corrections.
  Whole-record tracking review reconciles the current implementation and authorization.
- `sbtn mmlcPublishLocal`: passed after the final smoke run.
- `make -C benchmark clean` and `make -C benchmark mml`: passed; all 37 binaries built.
  These builds establish compatibility, not performance.
- Published CLI checks of `test-poisoning.mml`, `pap-borrowed-return-diagnostics.mml`, and
  `mem/struct-field-mutation.mml` report one type mismatch, two independent ownership
  errors, and one undefined `=` respectively, with no dependent diagnostic cascade.
  The type-mismatch source span includes trailing whitespace/comment text; parser spans
  are unchanged in this implementation. The poisoning sample comments describe the old
  cascade and need reconciliation when preparing the diagnostic showcase.
- `./tests/mem/run.sh all`: 52/52 passed ASan+LSan in 86 seconds before the final
  output-callback/test-only correction. Benchmark and memory checks were not repeated
  for that correction: it leaves generated code and ownership analysis unchanged.
  The full suite, smoke, and local publication passed again after the correction.
- Integrated independent review covered all 42 changed Scala files and both technical
  documents. A fresh primary and separate fresh QA, test-quality, and readability checks
  completed; two separate fresh verifiers confirmed test gaps. No compiler behavior defect
  was confirmed. The normal CLI test bypassed production diagnostic selection, and the
  ownership suite lacked one captured initializer with rejected and independent alternatives.
  The corrections exercise the production output path and require that mixed-origin capture
  to remain a visible primary. Both affected suites pass together: 16/16 tests.
- The first readability check was manually interrupted after its discovery command exposed
  the review skill body. Its evidence is excluded; a fresh replacement completed the check.
  This was an isolation correction, not an automated content block.
- Additional Claude narrow review was discontinued before completion. It supplies no final
  review verdict. Independent integrated coverage above remains complete.
- Fresh narrow re-review of the two corrections completed with no actionable findings.
  Three fresh dedicated QA, test-quality, and readability checks completed. Initial dedicated
  checks with uncertain context isolation were excluded and replaced. The primary reviewer
  encountered later verification references in a broad task search; accepted dedicated checks
  confirmed isolation from review history. No candidate required independent claim verification.
  This review inspected source and tests; it did not execute tests or mutations. Default stdout
  delivery and full ownership span equality are not directly asserted by the added tests.
- No parser recovery/cut changes or ABI/layout changes are included; Linux ABI container
  verification is not applicable to this scope.

### Parser foundation: verified

- The [uninstrumented baseline](../../benchmark/parser/baseline-a2c9832.md) records three fresh
  JVMs, four tracked valid sources, 500 warmup parses and seven batches of 100 parses per input.
  All raw/instrumented trees agree and all valid sources contain zero recovered errors. The
  separate grouped-lambda control has six recovered errors. All 84 samples, input/tree hashes,
  counters, environment, and limitations are retained. No speedup is claimed.
- Ten commitment regressions establish grouped lambda arguments and statement continuations,
  lexical identities, tuple alternatives, actual selections, annotations/spans, and malformed
  delimiter termination. Seven fail against the unchanged parser baseline; all ten pass after
  containing cuts in speculative selections/groups and the sequence probe. This fixes the shared
  boundary required before annotation cuts; no new valid syntax or precedence is introduced.
- The shared recovery scanner has 17 passing tests for nesting, hard stops, exact consumption,
  strings/comments, keyword boundaries, preserved doc openers, mismatched delimiters, zero-width
  boundaries/EOF, and 20,000-depth termination. It is not yet used for error production.
- The initial combined focused check passed 57/58 tests and exposed definition lookup falling
  back to the enclosing parameter on malformed type text. Declaration fallbacks are restricted
  to written names; valid names, type references, and value references retain their targets.
  Formatting, lint, and the subsequent carrier/ingest/printer/all-LSP check pass 57/57 tests,
  with no warnings.
- `sbtn 'scalafmtAll; scalafixAll; testFull'` passes 1,658 library tests and nine CLI tests,
  with zero failures or compiler warnings. The grouped/local lambda execution regression
  assembles its IR, compiles it with address instrumentation, and prints exactly `6` and `6`
  with exit 0 under leak detection.
- Smoke collection passes 8/8. `sbtn 'run run mml/samples/errors-are-not-granular.mml;
  mmlcPublishLocal'` executes the valid sample with output `6`, `6`, `4`, `3` and publishes
  successfully. Its header identifies the valid control; its body is unchanged. The JVM emits
  its existing `sun.misc.Unsafe` deprecation notice during `run`, not a compiler warning.
- `make -C benchmark clean` and `make -C benchmark mml` pass all 37 builds. These establish
  build compatibility, not performance. `./tests/mem/run.sh all` passes 52/52 ASan+LSan
  fixtures in 81 seconds. Host is arm64 macOS with GraalVM Java 25.0.4.1 and Clang 23.1.1;
  no ABI/layout changes require Linux container verification. QA and local link/diff checks
  pass.
- Independent foundation review completed over the full source/technical-document diff and
  untracked files. Fresh QA and readability checks found no actionable issues. The fresh
  test-quality check raised one P2 finding, confirmed by a separate fresh verifier: the
  commitment test evaluates `parseFailed` before attaching its timeout because `ParserApi`
  passes an eager parser result to `IO.pure`. Source and bytecode establish this ordering;
  they do not establish an actual hang. Cats Effect's ordinary timeout also waits for
  synchronous uncancelable work, affecting the scanner guard. A bounded child-process guard
  with forced cleanup and a readiness-confirmed timeout control covers both regressions.
  The focused command `sbtn "scalafmtAll; scalafixAll; testOnly mml.mmlclib.parser.ParserDeadlineTests
  mml.mmlclib.parser.RecoveryTests mml.mmlclib.grammar.ParserCommitmentTests"` passes 30 tests.
  Final `sbtn "scalafmtAll; scalafixAll; testFull"` passes 1,661 library and nine CLI tests,
  zero failures/errors/compiler warnings, exit 0 in 97 seconds. The helper checks actual child
  exit and dead/absent PID; cancellation uses Resource cleanup without a separate executed
  cancellation test. Java 25's Scala LazyVals runtime deprecation notice is retained in captured
  child output. The source correction is test-only; publication, benchmark, and memory evidence
  above still applies to the unchanged production source. Fresh narrow primary review and three
  fresh dedicated QA, test-quality, and readability checks completed with no actionable findings.
  No candidate required independent claim verification. The timeout control blocks with `IO.never`,
  rather than a CPU loop; the process boundary itself isolates synchronous work. Root audited
  completed review results and repeated the scoped QA and tracking consistency checks.
  No compiler behavior correction was requested. Review was read-only; gates were supplied
  evidence, not rerun by reviewers.

### Step 7: verified

- Contextual type recovery and real-source integration tests are implemented. The first focused
  command required two compile corrections (strict enum equality and FastParse lookahead syntax).
  Its first executed result was 49/52: two grammar oracles used the wrong alias field and group
  whitespace span, and one integration oracle read cause IDs from the original diagnostic payload
  instead of its record/stamped syntax. Corrected assertions preserve the intended contracts.
- `sbtn "scalafmtAll; scalafixAll; testOnly mml.mmlclib.parser.RecoveryTests
  mml.mmlclib.parser.TypeRecoveryTests mml.mmlclib.grammar.ParserCommitmentTests
  mml.mmlclib.semantic.TypeRecoveryIntegrationTests"` passes 52 tests, zero failures/errors/Scala
  warnings. sbt's multiple-main-class discovery notice refers to standalone test utilities.
- Balanced malformed fragments use a relaxed delimiter scan only when it reaches an actual
  caller/type boundary; unmatched openers fall back to hard punctuation stops. Closed malformed
  groups in expression annotations preserve following application terms. Regressions cover
  balanced and unmatched groups/brackets, following parameters/fields, body `=`, and independent
  application terms after groups containing commas, semicolons, or equals signs.
- The first full run passed 1,687/1,690 library tests and nine CLI tests. Three existing invalid
  native-marker annotation assertions expected discarded members rather than retained aliases.
  All nine invalid type-marker cases now assert exact rejected text/span, one primary type parse
  diagnostic/root, and a retained following binding. Implementation-position rejection checks
  remain unchanged. The revised five-suite command adds `ImplementationAnnotationTests` to the
  four suites above and passes 100/100 after formatting/lint, with zero failures/errors/Scala
  compiler warnings. Final `sbtn "scalafmtAll; scalafixAll; testFull"` passes 1,699 library
  and nine CLI tests, zero failures/errors/Scala compiler warnings, exit 0 in 100 seconds.
- A fresh-JVM `ParserBaseline` control with `1 1 1` over nqueens, mat-mul, astar2, and raytracer
  produces zero recovered errors and the exact source/raw-AST hashes in the
  [baseline](../../benchmark/parser/baseline-a2c9832.md#inputs-and-identity). Instrumented and
  raw trees agree. This is an AST compatibility control, not a performance measurement.
  The first invocation failed because `sbtn` joins separate command arguments; the documented
  recipes now place task separators inside one argument. The corrected invocation passed and
  restored `mmlclib / Test / fork := false`. Measured baseline data is unchanged.
- The inspectable [type-recovery sample](../../mml/samples/parser-type-recovery.mml) retains
  a malformed annotation, its dependent alias, an independent undefined reference, and later
  valid bindings. `sbtn "run ast mml/samples/parser-type-recovery.mml"` exits 1 as expected,
  reporting exactly `Type parser error at [5:13]-[5:19]: Invalid type syntax` and
  `Undefined reference 'missing' at [11:19]-[11:26]`. The dependent alias adds no visible error.
- Smoke collection passes 8/8. `sbtn "run run mml/samples/errors-are-not-granular.mml;
  mmlcPublishLocal"` prints `6`, `6`, `4`, `3` and publishes successfully. The runtime's Scala
  LazyVals deprecation notices are separate from Scala compiler warnings. `make -C benchmark
  clean` then `make -C benchmark mml` passes all 37 builds; this establishes build compatibility,
  not performance. `./tests/mem/run.sh all` passes 52/52 ASan+LSan fixtures in 80 seconds.
  Host is arm64 macOS 26.6.2 with GraalVM Java 25.0.4.1 and Clang 23.1.1. No ABI/layout change
  requires the Linux container gate.
- Full independent review and three fresh dedicated QA, test-quality, and readability checks
  completed. One P2 finding was confirmed by a separate fresh verifier: `fn outer(): Int =
  fn inner(arg): wrong = 1;; 2;;` loses its malformed result annotation when complete callable-type
  synthesis fails for an unannotated parameter. An isolated baseline/current parser comparison
  reports a member parse error at `4ed9c222` and no ingest diagnostic on the reviewed source;
  typed/nullary controls retain one type parse error. Empty and dangling-arrow results take the
  same path. The original fixture still has an independent uninferrable-parameter semantic error.
  Review was read-only; temporary test sources were removed and `Test / fork=false` confirmed.
- Eleven new local-result regressions fail before correction. They cover untyped, typed, and
  nullary parameters with malformed, empty, and dangling results, plus inferred parameters,
  dependent alias use, and an independent continuation. The semantic fixtures report only the
  independent undefined reference before correction. Retaining the malformed annotation directly
  on its lambda preserves valid ASTs and reuses the existing recursive failed-type query; mirrored
  annotations share their original root.
- Corrected formatting/lint and the five focused suites pass 111/111, exit 0 in 42 seconds.
  Corrected `sbtn "scalafmtAll; scalafixAll; testFull"` passes 1,710 library and nine CLI
  tests, exit 0 in 96 seconds, with zero failures/errors/Scala compiler warnings. The four-source
  fresh-JVM AST control again matches every recorded source/tree hash, with zero recovered errors
  and equal instrumented/raw trees; `Test / fork=false` is restored. Smoke passes 8/8, and the
  valid grouped-lambda sample prints `6`, `6`, `4`, `3` before successful local publication.
  Corrected `make -C benchmark clean` and `make -C benchmark mml` pass all 37 builds, exit 0;
  `./tests/mem/run.sh all` passes 52/52 ASan+LSan fixtures in 81 seconds, exit 0. These builds
  establish compatibility, not performance. Fresh narrow primary review and separate fresh QA, test-quality, and readability checks
  completed with no actionable findings. No candidate needed independent claim verification, and
  no fallback was used. Reviewers inspected source and saved verification evidence; they did not
  rerun gates. The eleven added tests observe dependent alias failure through the enclosing
  result; they do not directly assert that alias reference identity. Valid-AST controls cover
  four programs, and host sanitizer results establish only the executed paths. Root QA,
  54 local documentation links/anchors, and `git diff --check` pass.

### Step 8: verified

- Shared name, parameter-tail, required-token, and expression-frame recovery retains local/member
  bindings, real parameter scope, independent continuations, and nested valid expression prefixes.
  Binding-header type context separates a completed return type from a body when `=` is missing.
  Partial headers do not establish callable metadata; parser recovery creates no `InvalidExpression`.
- Initial test compilation exposed a duration-import name collision and strict source-point equality
  in three assertions. The first focused execution passed 71/81, with ten assertion failures and
  zero errors. Corrections preserve the shared statement-application shape and exact failed-member
  endpoint; existing subtype and whitespace-span expectations were reconciled. The next focused
  run passed 83/83, exit 0 in 47 seconds, with no Scala compiler warnings.
- Existing compatibility suites initially passed 39/53 with 14 assertion failures and zero errors.
  The first full run passed 1,732/1,758 library tests and all nine CLI tests, exit 1 in 108 seconds,
  with 26 assertion failures and zero errors. Invalid-name spelling excluded trailing whitespace
  after correction. Updated grammar and diagnostic assertions require retained syntax, exact token
  spans, original messages, primary roots, and independent failures; they do not equate a recovered
  module with valid input.
- That full run exposed an unterminated native-template regression: quoted-string masking consumed
  the following declaration. Binding frames opt into ordinary nesting/anchor recovery only when
  no later closing quote exists. The regression retains one exact parser root, the real function
  parameter, and the following declaration. Scanner controls preserve the default policy and closed
  multiline strings. A later quote can still conservatively extend the malformed fragment.
- A test-helper compilation error in the first correction check was corrected with `semVisibleState`.
  Final `sbtn "scalafmtAll; scalafixAll; testOnly ..."` passes all 112 tests across binding recovery,
  scanner recovery, module grammar, lambda grammar, implementation annotations, and parser printing,
  exit 0 in 43 seconds. Final `sbtn testFull` passes 1,761 library and nine CLI tests, exit 0 in
  73 seconds, with zero failures/errors and no Scala compiler warnings. The separate multiple-main-
  classes notice is build-tool output. The 17 binding integration tests establish retained identities,
  failed aliases/PAPs/captures, independent normalization/ownership diagnostics, exact CLI/LSP source
  roots, and the raw-error codegen gate.
- The four-source fresh-JVM `ParserBaseline` control (`1 1 1`) matches every recorded source and
  raw-tree hash in the [baseline](../../benchmark/parser/baseline-a2c9832.md), with zero recovered
  errors and equal instrumented/raw trees. `Test / fork=false` is restored. This control establishes
  AST compatibility, not performance. Host: arm64 macOS 26.6.2, GraalVM Java 25.0.4.1.
- `./tests/smoke/run.sh all` passes 8/8. `sbtn "run run mml/samples/errors-are-not-granular.mml;
  mmlcPublishLocal"` prints `6`, `6`, `4`, `3` and publishes successfully, exit 0 in six seconds.
  The JDK reports Scala-library `sun.misc.Unsafe` deprecation warnings during execution; there are
  no Scala compiler warnings. `make -C benchmark clean` and `make -C benchmark mml` pass all
  37 builds, exit 0. These builds establish compatibility, not performance.
- `./tests/mem/run.sh all` passes 52/52 ASan+LSan fixtures, exit 0 in 80 seconds. These results
  establish the exercised host paths, not portability or unexecuted paths. No ABI change requires
  the Linux-container gate. Root QA enforcement, 58 local documentation links/anchors, and
  `git diff --check` pass.
- Independent review completed with a confirmed P2: nameless local-let recovery drops its parsed
  annotation. `fn main(): Int = let : bad = 1; 3;;` reports only `Expected binding name`; ingest and
  LSP omit the independent malformed type error. The fresh verifier directly executed both APIs;
  CLI omission was traced statically. Invalid-name recovery discarded the whole binding at the
  baseline, so this is incomplete new component retention, not a previously reported error
  disappearing. The correction retains the annotation without inventing a resolvable name.
  Separate fresh QA/style, test-quality, and readability checks completed without actionable
  candidates. Root inspected their actual completed results and the atomic verifier result;
  no fallback was used. Reviewers inspected supplied gate evidence rather than rerunning it.
- Three added regressions fail on the missing declaration annotation before correction: missing
  name, invalid name, and separate malformed declaration/initializer annotations. The targeted
  run passes four controls and fails those three, exit 1 in eight seconds. An initial test filter
  matched only four existing nameless cases; it supplied no evidence about the new tests. The
  correction retains the written annotation on the existing synthetic statement argument, whose
  child still holds the original initializer and any annotation of its own. No resolvable source
  name or new lowering abstraction is introduced. Corrected formatting/lint and six focused suites
  pass 92/92, exit 0 in 46 seconds. Corrected `sbtn testFull` passes 1,764 library and nine CLI
  tests, exit 0 in 65 seconds, with zero failures/errors and no Scala compiler warnings.
  The four-source fresh-JVM control again matches all baseline hashes with zero recovered errors;
  `Test / fork=false` is restored. Corrected smoke passes 8/8. The valid grouped-lambda sample
  prints `6`, `6`, `4`, `3`, then local publication succeeds in four seconds. Corrected benchmark
  clean/build passes all 37 builds, and the memory harness passes 52/52 ASan+LSan fixtures in
  82 seconds; all commands exit 0. Root QA, whole-record tracking review, 58 local documentation
  links/anchors, and `git diff --check` pass. Fresh narrow primary review and separate fresh
  QA/style, test-quality, and readability checks completed without actionable findings. Root audited
  their completed results; no claim verifier was needed and no fallback was used. Reviewers traced
  the exact correction through ingest, typing, causes, availability, and LSP, and inspected supplied
  red/green and gate evidence without rerunning SBTN. The three correction fixtures directly exercise
  LSP; their shared CLI presentation path was inspected statically, not separately executed.

### Step 9: verified

- Shared conditional clauses replace the separate full/no-`else` implementations. Expression and
  member condition grammars remain explicit, while branch parsing and lowering are shared.
  Missing `then` retains all condition terms and one true-branch boundary error. Each recovered
  frame part advances; a following `if` resumes structural parsing and owns its nested clauses.
- The sixteen-declaration [conditional control](../../benchmark/parser/Readme.md#conditional-compatibility-control)
  was captured at `da7e9d19` before production edits. All four original corpus source/raw-tree hashes
  and the conditional-control hashes match after refactoring, with zero recovered errors and equal
  instrumented/raw trees. The `ParserBaseline 1 1 1` check is compatibility evidence, not performance;
  `Test / fork=false` is restored. The measurement utility's SHA-256 is unchanged.
- Production compilation passes. `sbtn "scalafmtAll; scalafixAll; testOnly
  mml.mmlclib.parser.ConditionalRecoveryTests mml.mmlclib.parser.BindingRecoveryTests"`
  passes 83/83 tests, followed by all five fresh-JVM controls, exit 0 in 52 seconds.
  A second formatting/lint run and `testOnly mml.mmlclib.semantic.ConditionalRecoveryIntegrationTests`
  pass all 13 integration tests. They check ingest payload/root identity, independent errors and
  normalization, actual parameter/reference IDs, failed aliases, exact CLI/LSP content and spans,
  quiet APIs, and the raw-error codegen gate. The same sequential command completes `testFull`:
  1,829 library tests and nine CLI tests pass, zero failures/errors, exit 0 in 108 seconds.
  No Scala compiler warnings occur; multiple-main-class notices and Scala-library/JDK Unsafe
  deprecation messages are separate tool/runtime output. Smoke passes 8/8. The grouped-lambda
  sample prints `6`, `6`, `4`, `3` through SBTN, followed by successful local publication in four
  seconds. Benchmark clean/build passes all 37 builds; the memory harness passes 52/52 ASan+LSan
  fixtures in 81 seconds. The sequential gate command exits 0. These builds establish compatibility,
  and sanitizers cover only exercised host paths; no ABI change requires Linux container checks.
- Root QA inspection finds no mutable compiler state, exceptions, ignored tests, brittle deep
  matches, or unused duplicate conditional helpers. Local links/anchors (56) and `git diff --check`
  pass. Independent primary review completed with three independently confirmed test findings:
  EOF exit/error-only checks do not distinguish lost clauses or missing component diagnostics (P2);
  two tuple regressions repeat shape/cardinality/sibling extraction (P3); the substantial nested
  owner match lacks paragraph spacing (P3). No production correctness defect was found.
  Separate fresh QA/style, test-quality, and readability checks completed, followed by one fresh
  verifier per claim. Root audited their actual completed results. Reviewers traced source and
  inspected supplied gate evidence; no SBTN or mutation experiment was run and no fallback was used.
  The test-only correction adds a typed child-process summary and compares all eleven EOF trees,
  component messages, zero-width spans, failed text, implicit Unit nodes, and diagnostic counts.
  It shares tuple extraction and separates substantial match alternatives. The sequential command
  `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.parser.ConditionalRecoveryTests mml.mmlclib.parser.ParserDeadlineTests mml.mmlclib.semantic.ConditionalRecoveryIntegrationTests; testFull'`
  passes 68 focused tests, 1,829 library tests and nine CLI tests, zero failures/errors, exit 0 in
  131 seconds. No compiler warnings occur. Production/runtime sources are unchanged, so the
  smoke, publication, benchmark-build, sanitizer, and AST-control evidence above applies without
  repeating those gates. Fresh narrow primary review and three separate fresh QA/style,
  test-quality, and readability checks completed with no actionable findings. Root audited each
  actual completed output. Reviewers inspected source and supplied gate results; they did not
  rerun tests or mutation experiments. No candidate required a verifier; no fallback was used.
  Root QA, whole-record tracking consistency, 57 local links/anchors, and diff checks pass.

### Step 10: measurements and integrated review verified

- The [final parser comparison](../../benchmark/parser/comparison-final.md) records compiler
  commit `81c2d3024634cc89edc210d59c67ecdbaf7924c4`, the unchanged measurement utility, exact
  commands, environment, identities, all 84 measured samples, and counter summaries. Three fresh
  JVMs each run five warmup batches and seven measured batches of 100 parses on the original
  four inputs in the original order. All commands exit 0; the shared fork setting is restored.
  All source/raw-AST hashes match baseline, all recovered-error counts are zero, and batch
  checksums/final-tree comparisons pass. No competing build or benchmark runs occur.
- Median-of-fork-median parse times are 245,288.75 ns for nqueens (−4.3%), 330,767.08 ns for
  matrix multiplication (−24.5%), 1,387,856.66 ns for astar2 (−25.5%), and 894,286.25 ns for
  raytracer (−25.5%) against the recorded baseline. These are local observations, not a general
  speed guarantee; continued warmup and only three forks limit inference. No measured input
  shows a median regression. The separate instrumented corpus counts are 324,939 invocations
  (−39.7%) and 159,080 backtrack-counter events (−50.4%); those reductions alone prove no timing
  improvement. Benchmark builds and native program timing are distinct from raw-parser timing.
- Comment-only cleanup in `mml/samples/test-poisoning.mml` describes its primary mismatch and
  suppressed dependent diagnostics. All non-comment source lines are unchanged. The command
  `sbtn 'run ast mml/samples/test-poisoning.mml'` returns the expected compilation-failure exit 1,
  with exactly one visible `Type mismatch at [5:5]-[8:1]: expected 'Int', got 'String'` diagnostic.
  The baseline report clarifies that JVM deprecation notices are separate from compiler warnings.
- At the measurement checkpoint, production source matches the step 9 compiler gates above.
  The subsequent semantic normalization correction has separate final verification below. Parser
  and measurement-utility sources remain unchanged from `81c2d302`; those timings remain evidence
  for the raw parser, not the later full compiler. No ABI/layout change requires Linux containers;
  there is no cross-platform execution claim.
- An initial integrated primary and two dedicated checks read historical reviewer conclusions
  embedded in the task record. Their independence is incomplete and those results are excluded
  from the required review. A replacement primary and three fresh checks use only the approved
  contract and direct source evidence; prior review material is excluded. The replacement primary,
  three separate fresh QA/style, test-quality, and readability checks, and independent atomic
  verification are complete. Root audited actual completed outputs; no fallback was used. The
  test-quality checker completed an initially partial inspection of all 37 test/support diffs.
  Reviewers inspected source and supplied gates; the atomic verifier executed the published
  compiler reproducer without SBTN. Compiler/test source, measurement data, and technical design
  documents stayed frozen during review; root updated excluded task/tracker bookkeeping.
- Independent atomic verification of the step 9 compiler confirms a newly exposed, preexisting
  normalization defect:
  `let result = ({ x: Int, y: Int -> x + y; }) 1 2;` fails with `InvalidApplication` after parsing,
  while the same literal without its surrounding parentheses produces an `Int` result. The
  published compiler reproduces exit 1 versus exit 0. `ExpressionRewriter` sends a grouped
  callee directly to ordinary application construction, bypassing the unary lowering used for
  direct multi-parameter lambdas. Immediate-lambda typing handles the first parameter and returns
  the body type, so the second argument is applied to `Int`. This split exists at `931c6ff4`;
  speculative parser cuts reject the exact grouped source there. The parser correction exposes
  the semantic defect rather than regressing a previously successful program. Shared lambda-head
  normalization and grouped/full/partial application regressions are verified below within the
  approved valid-grammar compatibility scope.
- A separate fresh atomic verifier confirms repeated temporary-source lifecycle code in four new
  diagnostic/recovery suites. Their setup, isolated output directory, and reverse-order cleanup
  policies are identical; only source text and names differ. The shared lifecycle correction below
  preserves fixture inputs and assertions. This is a maintenance finding, not evidence of a cleanup
  failure. The claim verifier used static inspection; execution evidence is recorded separately.

### Final review corrections: verified

- `ExpressionRewriter` dispatches grouped and bare call heads through the same helper. Lambda
  heads use existing unary lowering; ordinary application continuation remains separate, including
  nullary calls and calls returning another callable. Parameter IDs, source records, consuming
  flags, and existing partial/invocation metadata are retained. No parser or grammar changes are
  introduced by this correction.
- `GroupedLambdaApplicationTests` contains 20 semantic cases. Before the production correction,
  `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.semantic.GroupedLambdaApplicationTests'`
  returns exit 1: nine expected failures and eleven passing controls. Grouped and nested full,
  partial, overapplied, and mistyped calls fail, as does the grouped eta-expanded reference.
  Ungrouped, nullary, returned-lambda, and callable-parameter controls distinguish the defect.
- The first corrected-source batch runs the grouped suite, `AppRewritingTests`, four affected
  diagnostic/recovery suites, and `RuntimeTests`: 102/103 pass. Its sole failure is the new native
  test's expected returned-call trace. The compiler evaluates both arguments before executing
  the call stages, as required by the existing ownership/application contract. The corrected
  oracle expects `100`, `200`, `outer body`, `inner body`, `300`; production order is unchanged.
  The batch exits 1 before its queued full suite. No failed check is counted as passing.
- `BaseEffFunSuite.withSourceFixture` owns the shared temporary-source and isolated-output
  lifecycle used by ten tests in four suites. Fixture filenames, source text, callbacks, and
  assertions are unchanged. Bracketed reverse-order cleanup and stream closure are preserved.
- `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.codegen.RuntimeTests; testFull'`
  passes seven runtime tests, 1,850 library tests and nine CLI tests, zero failures/errors, exit 0
  in 116 seconds. The new native fixture validates LLVM IR, executes with ASan+LSan, and checks
  exact full/partial/returned-call output. No compiler warnings occur; the multiple-main-class
  and JVM deprecation notices are separate tooling notices.
- `./tests/smoke/run.sh all`: 8/8 pass. Sequential
  `sbtn 'run run mml/samples/errors-are-not-granular.mml; mmlcPublishLocal'` exits 0, printing
  `6`, `6`, `4`, `3` and installing the compiler locally. `make -C benchmark clean` followed by
  `make -C benchmark mml` exits 0 and builds all 37 binaries. These are build-compatibility checks,
  not performance measurements.
- `./tests/mem/run.sh all`: 52/52 ASan+LSan fixtures pass, exit 0, 80 seconds. All final gates
  run sequentially on arm64 macOS 26.6.2, GraalVM Java 25.0.4.1, and Clang 23.1.1. No ABI or
  target-layout change is made; Linux container execution is not required and is not claimed.
- Root QA finds no remaining rule violations. All 57 local links in the six affected records
  resolve; inspected anchors pass. One existing anchor in the unrelated parenthesized-lambda
  task is excluded from content inspection. `git diff --check` passes. Whole-record tracking
  review confirmed implemented corrections and completed gates and reviews; Author signoff
  was pending at that checkpoint and is recorded under Signoff.
- Fresh narrow primary review and three separate fresh QA/style, test-quality, and readability
  checks complete with no actionable findings. Root audited every actual completed report and
  confirmed the reviewed source identities stayed unchanged. No candidate required an atomic
  verifier, no claim is unresolved, and no fallback was used. Reviewers inspected source and
  command output; they did not independently rerun builds, native programs, or sanitizers.
  New grouped fixtures use nonconsuming scalar parameters; broader ownership evidence comes
  from the unchanged shared lowering, existing ownership tests, and the separate memory harness.
  Native checks cover executed arm64 macOS paths, not every ownership path or other targets.
  The tested and reviewed correction files are committed unchanged as `caee5d76` and `ebe47531`.
- Final whole-record tracking review reconciles metadata, all execution steps, verification,
  signoff, working memory, and the existing tracker entry at that checkpoint. Steps 2–10 were
  ready for whole-branch review; final acceptance is recorded under Signoff. Durable evidence
  is contained in this task and the versioned parser report, with no required temporary or machine-local files.

### Historical recovery example

Historical diagnostic recovery evidence, reproduced on 2026-09-25 (not rerun against
the completed lambda implementation):

```mml
struct Thing { name: String };

pub fn main(): Unit =
  let thing = Thing "Fede";
  let x = thing.name;
  println missing;
  println x;
;
```

Compilation through `sbtn "run run <source>.mml"` reports the primary undefined
reference `missing`, but also reports:

- `Ownership analysis requires a single-term expression` on `Thing "Fede"`.
- `'String' has no field 'name'` on `thing.name`.
- Additional inference and expression-shape diagnostics on later expressions.

Removing `println missing;` compiles and prints `Fede`.
`ExpressionRewriter.rewriteModule` retains the original binding when rewriting
returns errors, leaving valid expressions unnormalized. Ownership analysis then
reports the non-single-term shape. The
[struct mutation example](../../mml/samples/mem/struct-field-mutation.mml) produces
the same cascade with undefined `=` as the primary failure.

Required checks include both semantic and parse failures, multiple independent
errors, nested expressions, missing delimiters, precise spans, preserved valid
structure, and termination. Compare valid-program results and uninstrumented parse
timings; backtrack counters alone do not establish a performance improvement.

## Risks / Notes

Static inspection of `precedenceP` in `parser/members.scala` identifies an existing unchecked
`String.toInt` conversion: a precedence literal larger than `Int.MaxValue` throws instead of
becoming a parse diagnostic. This predates the parser foundation and is outside the bounded
type-recovery change; no execution or repair is claimed here.

Local function lowering also discards valid or unresolved written result annotations when any
parameter annotation is absent, because it cannot synthesize the complete callable ascription.
This predates contextual type recovery. Step 7 retains malformed result annotations on the
lambda but preserves existing valid ASTs; general result-annotation retention requires a separate
semantic/AST compatibility decision. No correction of that broader limitation is claimed.

The Author strongly favors centralization, simplification, and generalization in future work.
A shared typed AST transformation should be considered when it can simplify repeated compiler
logic; this cause implementation does not introduce one solely for a test helper.

Parser cuts alone do not repair semantic recovery. Blanket suppression can hide
independent errors, and retaining invalid subtrees must not create ordinary
ownership facts. Recovery must remain reachable after cuts and must not stall at
synchronization boundaries or EOF.

## Signoff

- Implementation handoff: accepted; implementation and verification finished.
- Step 1 signoff: accepted on 2026-10-25.
- Workstream and documentation signoff: accepted on 2026-10-25.
- Tracked item completion: accepted on 2026-10-25; all twelve execution steps complete.
- Delivery: squash commit `9d4f4740` on `main` contains the implementation and documentation.
- Closure bookkeeping: authorized as an amendment to the latest commit; no additional push.
- The following implementation commits identify historical development checkpoints; the
  delivered source and verification evidence are retained in `9d4f4740`.
- Step 1 commit: `931c6ff`
- Cause implementation commit: `a2c9832`
- Parser foundation commit: `4ed9c222`
- Type recovery commit: `0fe6f7cf`
- Binding recovery commit: `da7e9d19`
- Conditional recovery commit: `81c2d302`
- Shared diagnostic test fixtures commit: `caee5d76`
- Grouped-lambda normalization commit: `ebe47531`
- Semantic performance correction commit: `0daa7f21`

## Task Working Memory

Diagnostic causes, parser recovery, the semantic performance correction, and the explanatory
article are complete and accepted. Squash commit `9d4f4740` delivers them to `main` together
with the accepted [source-boundary correction](parser-source-boundaries.md).

The performance correction reuses immutable type and healthy-binding summaries, with
conservative recomputation for failed/incomplete evidence. Five paired fresh-JVM measurements
on `astar3_animated.mml` put the whole compile at 2,048.92 ms versus the measured main baseline's
2,088.29 ms, and type checking at 145.93 ms versus 41.30 ms. These local measurements are
not a general performance guarantee.

Recorded verification for this task passes 1,864 library and nine CLI tests, smoke 8/8,
37 benchmark builds, and memory 52/52 ASan+LSan. Independent correction and documentation
reviews report no actionable findings. The subsequent source-boundary task records the final
combined suite of 1,946 library and nine CLI tests and repeated smoke checks.

Closure changes task bookkeeping only; compiler gates are not rerun. All execution steps
and documentation have Author signoff. The numeric-literal example under compiler-design's
Expression parsing strategy has a separate known documentation issue: its `Ref` nodes should
be `LiteralInt`. That correction remains outside this completed task's scope.
