# MML parser optimization: the "commit & recover" strategy

## And an upgrade of the error system

Status: diagnostic causes are implemented and verified. Parser recovery implementation is
authorized; its bounded plan and execution evidence are maintained in the recovery task.
Verification status is recorded in the recovery task; this design is not execution evidence.
Implementation stages and approvals belong to the
[recovery task](../../../context/tasks/completed/error-recovery-and-parser-backtracking.md).

Problem:
Earlier profiling attributed roughly 30% of compilation time to parsing and
reported about 66% backtracking. These are historical observations, not a baseline
for the completed lambda implementation. The counter counts rule entries behind
the furthest position reached, not distinct rewinds. Separate cheap initial keyword
failures from deep reparsing: cuts address the latter, not the former.

The parser uses ordered choice extensively, but it already has a cut after the
parameterized lambda arrow in `lambdaWithParamsP`. New commitment boundaries must
account for that cut, especially inside grouping/selection alternatives. The
[parenthesized-lambda parsing task](../../../context/tasks/parser-parenthesized-lambda-arguments.md)
records an overlapping valid-input failure; its repair retains its own scope.

Constraint:
We cannot simply add Cuts (`~/`) because failures after a cut can bypass
fallback recovery alternatives. The compiler must remain resilient,
generating error nodes for analysis in later phases rather than aborting
the compilation.

Solution: Irrefutable Rules
To fix performance without sacrificing resilience, we use local cuts
combined with in-rule recovery. Once a specific keyword matches
(e.g., `let`), we cut to prevent backtracking to other rules, but we
make the subsequent parsing *irrefutable* by capturing local failures
as `ParseTermError` nodes (the agreed rename of `TermError`).

---

## 1. synchronization sets

Instead of scanning for a single delimiter character (which risks
consuming the rest of the file if that character is missing), recovery
uses a **Synchronization Set** — tokens that unambiguously signal
"we are in a new context."

MML uses `;` for statement separators and explicit body terminators.
A literal lambda body can also end implicitly at `}`. Top-level and local `fn`
parsers require the body terminator followed by a declaration terminator.
Treat `;` as a context-dependent boundary, not an interchangeable end marker;
`)` and `}` close groups and lambdas respectively.

Two sync scopes:

- **Expression-level sync** (used inside a member when an inner term
  fails): `;`, `)`, `}` at the relevant nesting depth, with EOF as a
  final boundary. Missing delimiters must not cause unbounded scanning.
- **Member-level sync** (used when a whole top-level form fails): the
  top-level keywords that open a new member: `let`, `fn`, `struct`, `type`, `op`,
  plus visibility markers `pub` / `priv` / `prot` / `inline`.
  There is no surface `module` declaration. The source-level `link` header has its
  own valid-position and misplaced-header recovery rules; preserve those diagnostics.

```scala
def exprSyncSet(using P[Any]) =
  P(";" | ")" | "}")

def memberSyncSet(using P[Any]) =
  P("let" | "fn" | "struct" | "type" | "op"
    | "pub" | "priv" | "prot" | "inline")
```

These are anchor sketches, not raw character scanners: keyword anchors
need word boundaries, and recovery must respect strings, comments, and
nesting. Leave the anchor for the enclosing parser. At an anchor or EOF,
emit a missing-input error without consuming it; the caller must then
consume input or exit the construct, so repetition cannot stall.
Enforce progress at repetition boundaries. Share recovery machinery with
explicit nesting/context and source positions; anchor sets alone cannot
implement it, especially when delimiters are unmatched.

---

## 2. implementation pattern

The fallback pattern below attempts a parser and recovers to a context-specific boundary.
The implemented binding helpers recover names, parameter tails, punctuation, and expression
frames separately so a failed suffix does not discard a valid prefix. Expression frames
retain a nested `Expr` followed by terminal `ParseTermError` nodes, allowing early name
resolution to visit the retained children. `NoCut` keeps recovery reachable around attempted
terms; the enclosing boundary-checked `let` or `fn` commits to its construct.

```scala
// Sketch: recovery includes missing input at an anchor or EOF.
def resilient[T](
  parser: => P[T], recovery: => P[String], toError: String => T
)(using P[Any]): P[T] =
  P(NoCut(parser) | recovery.map(toError))

// Sketch: recover the whole tail, including a missing '='.
def letExprP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    letKw ~/ resilient(
      letTailP(info),
      recoverExpr,
      bad => ParseTermError(span, "Invalid let binding", Some(bad))
    )
  )
```

`letTailP` retains the existing lambda/application lowering. Expected
identifier, separator, and value errors must recover locally to preserve
valid structure and precise errors. The outer fallback is a last resort:
an unrecovered failure there loses the tail's structure. Recovery must
capture start/end positions explicitly for diagnostic spans.
See [Fastparse cut isolation](https://com-lihaoyi.github.io/fastparse/#IsolatingCuts).

---

## 3. context-specific anchors (block structures)

Multi-keyword block structures (`if` / `elif` / `else`) also need explicit ownership
of their recovered components. Recovery uses **context-specific anchors**: the keywords
that delimit each sub-block. Each branch is itself a `;`-terminated expression, so the
generic expression-sync set is the recovery target for branch bodies.

Expression and member conditionals share one initial clause, repeated `elif` clauses, an optional
`else`, and one lowering function. Their condition parsers remain explicit: expression conditions
permit statement sequencing, while member conditions exclude it. The parser consumes the shared
prefix once and commits after each conditional keyword; local recovery makes its suffix total
at boundaries and EOF. Valid no-`else` forms retain their synthesized Unit branch and annotations.

For `if x < . then`, condition recovery stops at `then` and retains the branch. Missing `then`
keeps all parsed condition terms and adds one boundary error in the true branch, rather than
guessing a split. Branch recovery preserves `elif`/`else`, enclosing delimiters, and declaration
boundaries. A nonempty failed expression fragment can resume structural parsing at `if`, so the
nested parser owns its clauses. The scanner does not duplicate the conditional grammar; opaque
unmatched delimiters can conservatively hide ordinary keyword anchors.

## 3.5 type-ascription cut

Earlier profiling attributed roughly 28% of parse time to `withTypeAsc`.
Re-establish its cost on the current parser before treating it as the hottest rule.
The collector attributes exclusive time, but this does not establish
that optional-colon checks cause the cost: profile uninstrumented runs
and distinguish combinator work, allocation, and instrumentation overhead.
The `:` token is unambiguous: it appears only in type
contexts (term ascription, `let x: T`, fn params, fn return, struct
fields). Once `:` is seen, the parser is committed to a type — there
is no alternative to backtrack into.

`typeAscP` returns `P[Option[Type]]` with an explicit surrounding context. The absent-colon
path remains optional. After a colon, locally committed recovery returns a complete type
result, including function arrows and grouped types. `NoCut` contains that commitment at
the annotation boundary, preserving speculative caller alternatives. Parsing only nominal
references would narrow the grammar.

The cut prevents reconsidering an ascription after `:`; it does not remove the
initial optional-colon check. Recovery boundaries depend on the caller: `=` for malformed
binding or function-return types, `,` / `)` for parameters, and enclosing expression or field
delimiters elsewhere.
Binding headers can retain a completed type before a missing `=`; the binding parser owns
that punctuation error and preserves the following body. Type grouping tracks its own nesting.

Use `ParseTypeError` for missing or malformed type syntax, retaining its source
location, message, and failed text. An annotation containing this error remains
present; it must not become an absent annotation and silently fall back to inference.
Extend ingest error collection to type positions. Preserve surrounding syntax and
keep the erroneous type unavailable to later analysis.

Type resolution failures have separate representations: `UndefinedTypeError` for
no matching type and `AmbiguousTypeError` for multiple matches. These replace
`InvalidType`, retaining the original reference and its diagnostic cause; the
ambiguous error also retains matching candidates. A parsed but unresolvable type
reference is distinct from malformed syntax and from an ordinary type mismatch.
Resolution-error nodes and malformed-type ingest/consumer support are implemented.
Context-aware parser rules produce malformed-type errors during the type-recovery stage.

---

## 4. downstream impact: error-aware phases

Error nodes already appear **inside** otherwise valid expressions and
lambda/application trees. Local parser recovery will introduce more such cases.
Later phases must preserve the surrounding structure while recognizing the invalid subtree.

### Diagnostic-cause contract

The contract separates diagnostic occurrences, evidence about failed results, and proof
that one diagnostic is only a consequence. A root set supplies evidence; its presence does
not by itself prove that another error should be hidden.

#### Records, identity, and source evidence

Use one authoritative, ordered collection of diagnostic records in `CompilerState`:

| Component | Contract |
| :--- | :--- |
| `DiagnosticId` | Opaque identity allocated from an explicitly threaded immutable supply within one compilation. No cross-edit or cross-compilation stability promise. |
| Diagnostic record | Its ID, original `CompilationError` payload, and classification. Retain original source evidence together with the compilation's source context. |
| `Primary` | An original failure, or a diagnostic whose dependence has not been established. Visible by default. |
| `Consequence` | A diagnostic proven to add no independent failure, with a nonempty set of primary IDs, deduplicated and ordered by record insertion. |

Every cause reference resolves directly to a primary record. Propagating a consequence uses
its roots, never its own ID. This flat model identifies all original failures without a
second graph of intermediate explanations. The full error payload remains authoritative;
rewrites do not replace it with a message or a reconstructed source span.

A type-checking wrapper and its inner error form one occurrence. CLI grouping does not
create new occurrences; one occurrence may expand into several LSP diagnostics. Independent
failures at the same span receive different IDs. Messages, spans, AST equality, reference
object identity, and binding IDs are not diagnostic identities.

Allocate semantic IDs where a failure and its invalid result are produced together. Register
unmigrated errors as primary records. Expose raw error payloads as a projection of the records,
not a second independently writable collection. Preserve deterministic emission order.

Stamp recovered term and type parse errors with their primary IDs during deterministic ingest,
after parsing completes. This changes ingest's contract to a metadata-only rewrite: node structure,
locations, failed text, and diagnostic payloads remain intact. Do not allocate IDs during
parser backtracking. Ingest stamps fresh parser output once; re-ingesting an annotated tree is not an idempotence contract.

#### Failed-result carriers and availability

Keep persistent cause references on invalid expression/type nodes and recovered parse-error
nodes. Each migrated producer creates its primary record and cause-bearing result together.
Unmigrated or unattributed nodes may have no known roots and remain unsuppressible. No
universal AST node ID or permanent side table keyed by expressions is required.

A result-level failure uses a targeted `InvalidExpression` wrapper. Preserve `originalExpr`,
source origin, and available partial type information for editor queries. The wrapper marks
the value unavailable even when that partial type exists. Continue visiting its payload to
resolve names, collect independent diagnostics, and serve editor queries. A partial type
cannot establish ownership, a callable contract, or a valid value.

Wrap the smallest region whose result failed. A conditional with incompatible branches is a
failed conditional result. A mismatched local initializer is a failed initializer, not an
independent continuation. Phase-local result records carry the newly created IDs to the
nearest `Term`/`Expr` construction boundary that can wrap the result; specialized helpers
returning `Cond` or `App` need not widen `Callee` or store a second persistent cause channel.
Copies of invalid nodes preserve their roots. Combining failed regions unions known roots;
a new independent failure contributes its own primary ID as well.

`Callee` remains `Ref | App | Lambda`. An unresolved callee in an existing application makes
the enclosing application result invalid while retaining the callee and operands as payload.
For unresolved flat juxtaposition or an unknown operator, preserve the unnormalized failed
region instead of inventing application or precedence facts. Successful siblings and nested
continuations still retain their valid rewrites.

Known-root propagation follows result dependencies, not every descendant. Use resolved
binding IDs and a monotone fixed point to propagate through aliases, local parameters, and
generated bindings. Preserve shadowing. An immediate lambda application follows its body's
result; its initializer contributes through uses of the bound parameter. `BindOwnership`
follows its body with the initializer associated with its bound values. Conditional results
include predicate and branch dependencies; tuple results include their elements. A returned
lambda depends on its required parameter types, captures, and body, while an unused local
lambda does not contaminate the enclosing continuation.

Keep `ValueAvailability` separate from known-root collection. Empty roots mean no known
cause, not a usable value or complete causal knowledge. Preserve structural, resolved-ID,
type, and callable-evidence prerequisites. A recursive or unknown callable alternative may
lack a usable ownership contract without having a diagnostic. Such uncertainty cannot prove
that a new diagnostic is a consequence. Share traversal helpers only where the dependencies
are demonstrably identical.

#### Presentation and existing filtering

`hasErrors` and the codegen gate read the raw records. One shared selection produces the
visible records before CLI grouping, quiet-API message extraction, and LSP expansion. Root
messages and locations remain those of the original errors. APIs receiving only a bare
error format it without causal suppression. If causal references are missing, unresolved,
or not primary, keep the diagnostic visible.

The emitting rule must establish that a consequence depends on specific failed prerequisites
and adds no independent failure. A bad predicate does not explain incompatible branch types.
Unknown or independent callable alternatives prevent an all-path consequence proof. Do not
emit a new diagnostic merely because an invalid node was visited.

The approved implementation applies these policies; verification is tracked in the task:

| Existing mechanism | Policy under the cause contract |
| :--- | :--- |
| `InvalidExpressionFound` in normalization | Retain a consequence linked to the invalid region's known roots when the report merely repeats its failure. An unattributed invalid node remains visible. |
| Rejected borrowed return followed by capture errors | Retain proven capture consequences with the rejected-return roots. Keep the existing all-source proof and exclusions for independent borrowed parameters; unknown alternatives stay visible. |
| Borrow dependency followed by repeated move errors | Retain the dependent move report as a consequence only when the matching owner/move origin supplies its primary record. Preserve unrelated owners and uses. |
| Written/derived copies of one move report | Canonicalize the same semantic occurrence, preferring the written reference. Keep one record; do not create a new root merely for a duplicate. Equal spans alone are insufficient. |
| Redundant expression-shape error inside an invalid payload | Retain a consequence only when the invalid region's roots explain that shape failure. Preserve independent payload errors; an unrooted shape failure remains visible. |
| Type-checker unresolvable condition/branch/argument/function guards | Retain a consequence when the missing result/type follows from retained child failures. Preserve the existing prerequisite for whether the check applies; if no causal proof exists, report the genuine failure as primary. |
| Ownership/PAP/callable availability guards | Keep prerequisite checks. Do not generate ownership facts or hypothetical diagnostics from unavailable inputs. Continue independent child and continuation analysis. |
| Invalid closure layout before destructor completion | Keep the prerequisite guard and retain its original environment diagnostic. An already-rejected layout supplies no valid metadata to complete; no hypothetical missing-metadata error is required. |

Duplicate canonicalization uses the existing semantic identity evidence for that diagnostic
family, before selecting a representative record. It is separate from suppressing a genuine
consequence and must not merge distinct errors that happen to share text or locations.

### Worked cause traces and acceptance cases

These are design traces, not executed tests. `P1`, `P2`, and `C1` denote diagnostic IDs;
`b1` and `b2` denote resolved binding IDs. In every failing case, raw records remain nonempty
and codegen stays blocked, including when the final expression has an available result.
Unmentioned independent diagnostics remain visible.

1. **One root through aliases and shadowing.** Resolving a missing name creates `P1` and
   its invalid region at the written reference span. Assigning that result to `b1`, then
   aliasing it as `b2`, propagates `{P1}` to uses of those IDs. A same-named binding in a
   different scope inherits nothing unless its own initializer depends on `b1` or `b2`.
   Normalization preserves the carrier while unwrapping grouping syntax. A redundant
   unavailable-result report is `C1 -> {P1}`; presentation shows the original `P1` once.
2. **Two failures in one binding.** Two undefined term references create separate primaries
   `P1` and `P2`, each retaining its own reference and span. An invalid enclosing result
   may carry `{P1, P2}`, but both primaries remain visible. The accumulating resolver
   returns valid rewritten siblings as well as both failed regions. It must not report
   only the first failure and create an unattributed invalid node for the second.
3. **Unknown types beside a valid type.** Two unknown type references create `P1` and `P2`
   and their respective `UndefinedTypeError` nodes. A valid sibling `Int` keeps its
   resolved ID and source; an ambiguous reference instead retains all actual candidates
   and its own primary. Resolve against the same environment throughout recovery.
   Type wrappers and later alias uses preserve these roots; only the actual failures show.
4. **Failed initializer, valid continuation.** A local initializer with root `P1` lowers
   to an immediate lambda application's argument. If the body returns an independent
   literal, the whole result has no dependency on `P1` and can remain available. The
   initializer still carries `{P1}` and `P1` remains visible. If the body uses the bound
   parameter, its result inherits `{P1}`. Apply the same rule after `BindOwnership`
   lowering and to an unused local lambda containing a failed body.
5. **Unknown callee.** A flat failed callee region keeps its original terms with `{P1}`;
   normalization creates no guessed application. An already-shaped `App` with that callee
   is retained inside an invalid enclosing result. Independent operand failures add their
   own primaries and visible source spans. Editor traversal still reaches the payload.
6. **Bad predicate and independent branch mismatch.** The predicate's failure is `P1`.
   Incompatible valid branch types create independent primary `P2`. The conditional's
   invalid wrapper carries `{P1, P2}` and retains its partial type, but is unavailable.
   Both diagnostics show. A later report solely repeating the failed result may be a
   consequence of both; the branch mismatch is never a consequence of `P1` alone.
7. **Failed application and independent operand ownership error.** Application mismatch
   `P1` supplies the failed-result carrier; additional application failures retain their
   own IDs. A genuine use-after-move found inside its preserved operand is independent
   `P2`. Both show, while the failed result supplies no PAP capture or return contract.
   Canonicalization or child-error guards cannot leave carriers pointing to discarded IDs.
8. **Copied/generated syntax and shared spans.** Copies preserve their existing roots.
   A written and lowered report of the same moved owner/use/origin selects one primary.
   Two owners at one use span, or distinct uses of one owner, retain separate primaries.
   Generated binding IDs inherit initializer causes explicitly; object identity is not used.
9. **Unknown cause or mixed callable alternatives.** An unavailable result with no known
   diagnostic stays unavailable, but cannot justify suppressing another report. A mixture
   of a known rejected return and an independent or unknown callable alternative does not
   establish a universal consequence. Keep genuine new errors visible. Missing root IDs
   also keep their consequence records visible rather than silently losing diagnostics.
10. **Recovered parse failure.** Deterministic ingest creates `P1`, preserving the parse
    node's message, failed text, and span while attaching its ID. Rewrites and aliases
    preserve `{P1}`. Existing CLI/LSP conversion still identifies that same source error;
    `ParseTypeError` uses the same mechanism without erasing a malformed annotation.

Use phase-boundary assertions for complete diagnostic sets, original references/spans,
valid sibling IDs, and refreshed indexes. Add presentation assertions for visible IDs,
exact root messages/ranges, deterministic ordering, and identical causal selection across
CLI, quiet API, and LSP. Assert raw error state and disabled codegen separately.

Existing evidence to retain includes
[ElaborationRecoveryTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ElaborationRecoveryTests.scala),
[OwnershipExpressionTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipExpressionTests.scala),
[OwnershipDiagnosticTests](../../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/OwnershipDiagnosticTests.scala),
and the parser diagnostic suites. Dedicated diagnostic-store, cause-query, resolver,
type-checker cause, ownership-cause, and presentation tests exercise the new contract.
The task records their actual execution results and outstanding checks.

### Settled contracts and remaining work

Local bindings and statement sequences are already immediate applications of
lambdas. `App.fn` uses `Callee = Ref | App | Lambda`; an error node is not a valid
callee. Preserve this invariant, binding identities, source origins, and refreshed
resolvables indexes when retaining partial trees. Unknown operators/callees need a
preserved invalid region, not invented application or precedence facts.

| Phase / facility | Current contract and remaining recovery work |
| :--- | :--- |
| **ParsingErrorChecker** | Stamps recovered term and type errors with primary IDs while preserving recovered syntax and source evidence. Mirrored annotation occurrences share their root. |
| **RefResolver / TypeResolver** | Accumulate diagnostics and rewritten results together. Preserve valid sibling resolution and distinguish missing from ambiguous types. |
| **ExpressionRewriter** | Retains successful children and valid continuations; failed flat regions stay intact without guessed precedence. |
| **Simplifier / CaptureAnalyzer** | Preserve lambda/application shape, invalid payloads, and binding identities. |
| **TypeChecker** | Wraps failed results, retains partial editor types, and links proven missing-result consequences without hiding independent mismatches. |
| **ValueAvailability / DiagnosticCauses** | Separate availability from result-directed known roots and incomplete evidence. Follow resolved bindings and aliases. |
| **PAP elaboration / closure helpers** | Traverse independent operands and continuations while requiring valid results before materializing callable facts or layouts. |
| **OwnershipAnalyzer** | Retains primary/consequence records and independent payload errors. Failed operations cannot establish ownership facts. |
| **ResolvablesIndexer / SemanticTokens** | Preserve retained definitions and editor traversal through invalid payloads. |
| **CodegenStage** | Raw errors disable emission independently of presentation filtering. |

The `ParseTermError` rename is complete. The Author approved the full cause implementation
across roadmap steps 2–5, with chunk reviews and integrated compiler gates. This includes
records, accumulating resolution, normalization preservation, failed-result wrappers,
propagation, ingest stamping, and presentation selection. The malformed-type foundation in
step 6 is implemented. Parser recovery and commitment across steps 7–10 are authorized under
the bounded task plan, with local commits and whole-branch Author review. The task remains
open until its applicable steps are signed off.

---

## 5. review notes & decisions

* Sequence: Lambda unification is complete. Follow the bounded stages in the
  recovery task: naming and error-model preparation, semantic recovery, then parser
  recovery and commitment.
* Scope: We will apply this optimization to the recursive
  "Big 3" — `let`, `if`, and `fn` — plus type ascriptions. Cover local binding and top-level member
  parser paths; both lower function values to lambdas, while local `fn` also
  constructs a scoped binding and continuation. Leaf nodes (`refP`, `litP`) do not require
  optimization as they fail fast naturally.
* Node Types: `ParseTermError` replaces `TermError`; `ParseTypeError` represents malformed
  type syntax. `UndefinedTypeError` and `AmbiguousTypeError` replace `InvalidType` for distinct
  resolution failures. Retain
  `ParsingMemberError`, `ParsingIdError`, and `InvalidExpression` where they fit;
  this design does not require a general `PoisonNode` class.
* Ordering: The ordered choice in `termP` remains unchanged.
  Factor shared prefixes before adding cuts. Investigate keyword dispatch
  separately if profiling identifies initial alternative checks as costly.
* Validation: Compare representative valid and malformed programs, with
  uninstrumented timings alongside parser counters. Avoid exact assertions
  on the current backtrack counter. Test precise spans, preserved surrounding
  structure, independent diagnostics, and termination for missing `=`, empty
  values, truncated types, nested conditionals, unmatched delimiters, and
  synchronization characters inside strings/comments.
