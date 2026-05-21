# MML parser optimization: the "commit & recover" strategy

## And an upgrade of the error system

Problem:
Parsing (ingest) is the largest single stage of the compile pipeline,
typically ~30% of total compile time on real samples — ahead of
semantic analysis, LLVM lowering, and codegen. The parser exhibits
high backtrack counts (~66%) because it uses "Ordered Choice" (`|`)
without "Cuts" (`~/`). For every simple identifier, the parser
tentatively checks—and fails—against every keyword rule (`let`, `if`,
`native`, etc.) before falling back to `refP`.

Constraint:
We cannot simply add Cuts (`~/`) because standard Fastparse cuts cause
immediate termination on failure. The compiler must remain resilient,
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
  fails): `;`, `)`, `}`. Bounded by construction — a `;` always closes
  the current statement, so recovery never escapes the enclosing scope.
- **Member-level sync** (used when a whole top-level form fails): the
  top-level keywords that open a new member: `fn`, `struct`, `type`, `op`,
  `module`, plus visibility markers `pub` / `priv` / `prot` / `inline`.

```scala
def exprSyncSet(using P[Any]) =
  P(";" | ")" | "}")

def memberSyncSet(using P[Any]) =
  P("fn" | "struct" | "type" | "op" | "module"
    | "pub" | "priv" | "prot" | "inline")

// Consume garbage until a sync token, DO NOT consume the sync token
def recoverExpr(using P[Any])   = (!exprSyncSet   ~ AnyChar).rep(1)
def recoverMember(using P[Any]) = (!memberSyncSet ~ AnyChar).rep(1)
```

---

## 2. implementation pattern

We introduce a `resilient` helper that attempts a parser, and on
failure, consumes input until the sync set and emits an error node.

```scala
// Helper: try parser, on failure consume garbage until sync set
// and emit error node.
def resilient[T](
  parser: => P[T], toError: String => T
)(using P[Any]): P[T] =
  P(parser | recover.!.map(toError))

// Example: Optimized 'Let' Binding
def letExprP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    "let" ~/  // CUT: commit to Let. No backtracking past this.
    (
      // If bindingIdP fails, consume garbage until sync set
      resilient(
        bindingIdP,
        bad => TermError(span, "Expected binding identifier", bad)
      )
    ) ~
    "=" ~/    // CUT: expect assignment.
    (
      // If exprP fails, consume garbage until sync set
      resilient(
        exprP,
        bad => TermError(span, "Expected expression", bad)
      )
    )
  ).map { case (binding, expr) =>
    Let(binding, expr)
  }
```

---

## 3. context-specific anchors (block structures)

The generic `resilient` helper works for linear forms like `let`. For
multi-keyword block structures (`if` / `elif` / `else`), recovery uses
**context-specific anchors** — the keywords that delimit each
sub-block. Each branch is itself a `;`-terminated expression, so the
generic expression-sync set is the recovery target for branch bodies.

```scala
def ifExprP(...) = P(
  "if" ~/
  (conditionP | recoverUntil("then")) ~
  "then" ~/
  (trueBranchP | recoverUntilExprOr("elif", "else")) ~
  ("elif" ~/ (...) ).rep ~
  ("else" ~/ (falseBranchP | recoverExpr)).?
)
```

This ensures that if the user writes `if x < . then`, the parser
swallows the bad condition, resyncs at `then`, and correctly parses
the rest of the block without aborting. Branches close at `;`; the
enclosing statement's `;` bounds the whole `if`.

## 3.5 type-ascription cut

`withTypeAsc` is the single hottest rule — roughly 28% of total parse
time — because every term wrapped with `withTypeAsc` speculatively
tries to consume a `:` and a type, succeeding for a small fraction of
calls and failing for the rest. The `:` token is unambiguous: it
appears only in type
contexts (term ascription, `let x: T`, fn params, fn return, struct
fields). Once `:` is seen, the parser is committed to a type — there
is no alternative to backtrack into.

```scala
def typeAscP(using P[Any]): P[Type] =
  P(":" ~/ resilient(typeRefP, bad => TypeError(bad)))

def withTypeAsc[T](term: P[T])(using P[Any]): P[T] =
  P(term ~ typeAscP.?).map { ... }
```

Cost target: collapse the "try `:`, fail" backtracks into a no-cut
peek. Recovery target on failure inside the type is the
surrounding context's expected token (`=` for `let`, `,` or `)` for
params, `;` or `}` for terms).

---

## 4. downstream impact: error-aware phases

With this change, `TermError` nodes will appear **inside** valid AST
nodes (e.g., `Let(TermError(...), ...)`). Previously, errors only
appeared at the member level.

### Error classification

To prevent unhelpful cascades, semantic phases must distinguish
between the **root cause** and **consequences**.

1. Primary Error: The original parse error. (`cause = None`).
   Always reported.
2. Secondary Error: A downstream consequence (e.g., type checking
   failing on a `TermError`). (`cause = Some(primary)`). Suppressed
   by default unless verbose logging is on.

```scala
trait Error extends InvalidNode:
  def cause: Option[Error] = None // None → primary, Some → secondary
```

### Phase handling table

| Phase                  | Action on `case _: TermError`                      |
| :--------------------- | :------------------------------------------------- |
| **TypeChecker**        | Emit secondary: "Cannot type-check erroneous       |
|                        | expression". Link to primary.                      |
| **OwnershipAnalyzer**  | Emit secondary: "Cannot analyze ownership of       |
|                        | erroneous expression". Treat as `Borrowed`.        |
| **ExpressionRewriter** | Emit secondary: "Cannot rewrite erroneous          |
|                        | expression". Pass through.                         |
| **Simplifier**         | Emit secondary: "Cannot simplify erroneous         |
|                        | expression". Pass through.                         |
| **RefResolver**        | Emit secondary: "Cannot resolve references in      |
|                        | erroneous expression". Pass through.               |
| **ResolvablesIndexer** | Already returns `Nil` — safe, no change needed.    |
| **SemanticTokens**     | Already returns `Nil` — safe, no change needed.    |
| **Codegen**            | **STOP.** Pipeline must gate on *any* primary      |
|                        | errors before entering codegen.                    |

---

## 5. review notes & decisions

* Scope: We will apply this optimization to the recursive
  "Big 3" — `let`, `if`, and `fn` — plus `withTypeAsc` (the single
  hottest rule). Inner `fn` (local function) inherits the same shape
  as top-level `fn`. Leaf nodes (`refP`, `litP`) do not require
  optimization as they fail fast naturally.
* Node Types: We will reuse the existing `TermError`,
  `ParsingMemberError`, and `ParsingIdError` types. No new
  `PoisonNode` class is required.
* Ordering: The ordered choice in `termP` remains unchanged.
  The performance gain comes from eliminating backtracking *after*
  a keyword match, not from reordering the initial checks.
