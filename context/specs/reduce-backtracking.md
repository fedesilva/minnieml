# MML Parser Optimization: The "Commit & Recover" Strategy

## And an upgrade of the error system

**Problem:**
The parser currently exhibits high backtrack counts (~66%) because it uses
"Ordered Choice" (`|`) without "Cuts" (`~/`). For every simple identifier,
the parser tentatively checks—and fails—against every keyword rule
(`let`, `if`, `native`, etc.) before falling back to `refP`.

**Constraint:**
We cannot simply add Cuts (`~/`) because standard Fastparse cuts cause
immediate termination on failure. The compiler must remain resilient,
generating error nodes for analysis in later phases rather than aborting
the compilation.

**Solution: Irrefutable Rules**
To fix performance without sacrificing resilience, we use **Local Cuts**
combined with **In-Rule Recovery**. Once a specific keyword matches
(e.g., `let`), we cut to prevent backtracking to other rules, but we
make the subsequent parsing *irrefutable* by capturing local failures
as `TermError` nodes.

---

## 1. Synchronization Sets

Instead of scanning for a single delimiter character (which risks
consuming the rest of the file if that character is missing), recovery
uses a **Synchronization Set** — tokens that unambiguously signal
"we are in a new context."

For MML, the sync set includes:
- **Statement terminators:** `;`
- **Block closers:** `end`, `)`
- **Top-level keywords:** `fn`, `struct`, `type`, `op`

```scala
def syncSet(using P[Any]) =
  P(";" | "end" | "fn" | "struct" | "type" | "op")

// Consume garbage until a sync token, DO NOT consume the sync token
def recover(using P[Any]) = (!syncSet ~ AnyChar).rep(1)
```

---

## 2. Implementation Pattern

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

## 3. Context-Specific Anchors (Block Structures)

The generic `resilient` helper works for linear forms like `let`. For
multi-keyword block structures (`if`/`elif`/`else`/`end`), recovery
uses **context-specific anchors** — the keywords that delimit each
sub-block.

```scala
def ifExprP(...) = P(
  "if" ~/
  (conditionP | recoverUntil("then")) ~
  "then" ~/
  (trueBranchP | recoverUntil("else", "end")) ~
  ("else" ~/ (falseBranchP | recoverUntil("end"))).? ~
  "end"
)
```

This ensures that if the user writes `if x < . then`, the parser
swallows the bad condition, resyncs at `then`, and correctly parses
the rest of the block without aborting.

---

## 4. Downstream Impact: Error-Aware Phases

With this change, `TermError` nodes will appear **inside** valid AST
nodes (e.g., `Let(TermError(...), ...)`). Previously, errors only
appeared at the member level.

### Error Classification

To prevent unhelpful cascades, semantic phases must distinguish
between the **root cause** and **consequences**.

1. **Primary Error:** The original parse error. (`cause = None`).
   Always reported.
2. **Secondary Error:** A downstream consequence (e.g., type checking
   failing on a `TermError`). (`cause = Some(primary)`). Suppressed
   by default unless verbose logging is on.

```scala
trait Error extends InvalidNode:
  def cause: Option[Error] = None // None → primary, Some → secondary
```

### Phase Handling Table

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

## 5. Review Notes & Decisions

* **Scope:** We will apply this optimization to the recursive
  "Big 3": `let`, `if`, and `fn`. Leaf nodes (`refP`, `litP`) do
  not require optimization as they fail fast naturally.
* **Node Types:** We will reuse the existing `TermError`,
  `ParsingMemberError`, and `ParsingIdError` types. No new
  `PoisonNode` class is required.
* **Ordering:** The ordered choice in `termP` remains unchanged.
  The performance gain comes from eliminating backtracking *after*
  a keyword match, not from reordering the initial checks.
