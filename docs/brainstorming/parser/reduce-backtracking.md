# MML parser optimization: the "commit & recover" strategy

## And an upgrade of the error system

Problem:
Parsing (ingest) is the largest single stage of the compile pipeline,
typically ~30% of total compile time on real samples — ahead of
semantic analysis, LLVM lowering, and codegen. The parser exhibits
high backtrack counts (~66%) with "Ordered Choice" (`|`) and no
"Cuts" (`~/`). The counter counts rule entries behind the furthest
position reached, not distinct rewinds. Separate cheap initial keyword
failures from deep reparsing: cuts address the latter, not the former.

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
as `TermError` nodes.

---

## 1. synchronization sets

Instead of scanning for a single delimiter character (which risks
consuming the rest of the file if that character is missing), recovery
uses a **Synchronization Set** — tokens that unambiguously signal
"we are in a new context."

MML's block structure is built on `;`: every statement-position
expression, every conditional branch, every lambda / function body
terminates with `;`. That makes `;` the universal expression-level
sync anchor; `)` and `}` close groups and lambdas respectively.

Two sync scopes:

- **Expression-level sync** (used inside a member when an inner term
  fails): `;`, `)`, `}` at the relevant nesting depth, with EOF as a
  final boundary. Missing delimiters must not cause unbounded scanning.
- **Member-level sync** (used when a whole top-level form fails): the
  top-level keywords that open a new member: `let`, `fn`, `struct`, `type`, `op`,
  `module`, plus visibility markers `pub` / `priv` / `prot` / `inline`.

```scala
def exprSyncSet(using P[Any]) =
  P(";" | ")" | "}")

def memberSyncSet(using P[Any]) =
  P("let" | "fn" | "struct" | "type" | "op" | "module"
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

We introduce a `resilient` helper that attempts a parser, and on
failure, recovers to a context-specific boundary and emits an error node.
Isolate cuts inside the attempted parser so recovery remains reachable.
`NoCut` preserves internal commitments but allows recovery at its boundary;
the enclosing keyword cut still commits to the selected construct.

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
      bad => TermError(span, "Invalid let binding", bad)
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

The generic `resilient` helper works for linear forms like `let`. For
multi-keyword block structures (`if` / `elif` / `else`), recovery uses
**context-specific anchors** — the keywords that delimit each
sub-block. Each branch is itself a `;`-terminated expression, so the
generic expression-sync set is the recovery target for branch bodies.

First factor the shared prefix of `ifExprP` and `ifSingleBranchExprP`.
The full form is tried first and fails at a missing `else`, reparsing a
valid no-`else` conditional through the second rule. A cut after `if`
in the full form would block that valid alternative; parse the shared
condition/branches once, then handle the optional `else`.

For `if x < . then`, recover the condition at `then` and continue with
the branch. Use the same cut-isolated recovery for branch bodies, adding
`elif` / `else` as anchors at the current conditional's depth. If `then`
is missing, stop at an enclosing boundary or EOF instead of scanning
indefinitely for it. Missing required keywords need recovery too.

## 3.5 type-ascription cut

`withTypeAsc` is the single hottest rule — roughly 28% of total parse
time. The collector attributes exclusive time, but this does not establish
that optional-colon checks cause the cost: profile uninstrumented runs
and distinguish combinator work, allocation, and instrumentation overhead.
The `:` token is unambiguous: it appears only in type
contexts (term ascription, `let x: T`, fn params, fn return, struct
fields). Once `:` is seen, the parser is committed to a type — there
is no alternative to backtrack into.

```scala
def typeAscP(using P[Any]): P[Type] =
  P(":" ~/ resilient(typeRefP, recoverType, bad => TypeError(bad)))

def withTypeAsc[T](term: P[T])(using P[Any]): P[T] =
  P(term ~ typeAscP.?).map { ... }
```

The cut prevents reconsidering an ascription after `:`; it does not
remove the initial optional-colon check. Recovery target inside the type is the
surrounding context's expected token (`=` for `let`, `,` or `)` for
params, `;` or `}` for terms).

---

## 4. downstream impact: error-aware phases

With this change, error nodes can appear **inside** otherwise valid
expressions and lambda/application trees. Later phases must preserve
the surrounding structure while recognizing the invalid subtree.

### Error classification

To prevent unhelpful cascades, semantic phases must distinguish
between the **root cause** and **consequences**.

1. Primary Error: The original parse error. (`cause = None`).
   Always reported.
2. Secondary Error: A downstream consequence (e.g., type checking
   failing on a `TermError`). (`cause = Some(primary)`). Suppressed
   by default unless verbose logging is on.

Continue reporting independent errors; only suppress diagnostics linked
to an existing cause. Merely visiting an error node need not emit another
diagnostic in every phase.

```scala
trait Error extends InvalidNode:
  def cause: Option[Error] = None // None → primary, Some → secondary
```

Cause identity must survive bindings and later uses, not just direct
visits to error nodes. Carry it through existing invalid types, binding
metadata, or diagnostic dependencies; avoid turning invalid input into
ordinary type/ownership facts. A single cause is sufficient for suppression
only if all independent primary errors remain reported; multiple causes
can retain fuller dependency information.

### Phase handling table

| Phase                  | Action on `case _: TermError`                                              |
| :--------------------- | :------------------------------------------------------------------------- |
| **TypeChecker**        | Preserve invalid status; link dependent failures to the primary error.     |
| **OwnershipAnalyzer**  | Preserve invalid status; do not infer ownership for the erroneous subtree. |
| **ExpressionRewriter** | Pass through; link dependent failures to the primary error.                |
| **Simplifier**         | Pass through.                                                              |
| **RefResolver**        | Pass through; link dependent failures to the primary error.                |
| **ResolvablesIndexer** | Already returns `Nil` — safe, no change needed.                            |
| **SemanticTokens**     | Already returns `Nil` — safe, no change needed.                            |
| **Codegen**            | **STOP.** Gate on any primary errors before entering codegen.              |

---

## 5. review notes & decisions

* Sequence: Implement after lambda salvage, against the settled AST and
  ownership behavior.
* Scope: We will apply this optimization to the recursive
  "Big 3" — `let`, `if`, and `fn` — plus `withTypeAsc` (the single
  hottest rule). Inner `fn` (local function) inherits the same shape
  as top-level `fn`. Leaf nodes (`refP`, `litP`) do not require
  optimization as they fail fast naturally.
* Node Types: We will reuse the existing `TermError`,
  `ParsingMemberError`, and `ParsingIdError` types. No new
  `PoisonNode` class is required.
* Ordering: The ordered choice in `termP` remains unchanged.
  Factor shared prefixes before adding cuts. Investigate keyword dispatch
  separately if profiling identifies initial alternative checks as costly.
* Validation: Compare representative valid and malformed programs, with
  uninstrumented timings alongside parser counters. Avoid exact assertions
  on the current backtrack counter. Test precise spans, preserved surrounding
  structure, independent diagnostics, and termination for missing `=`, empty
  values, truncated types, nested conditionals, unmatched delimiters, and
  synchronization characters inside strings/comments.
