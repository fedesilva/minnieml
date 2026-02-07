# MML Parser Optimization: The "Commit & Recover" Strategy

**Problem:**
The parser currently exhibits high backtrack counts (~66%) because it uses "Ordered Choice" (`|`) without "Cuts" (`~/`). For every simple identifier, the parser tentatively checks—and fails—against every keyword rule (`let`, `if`, `native`, etc.) before falling back to `refP`.

**Constraint:**
We cannot simply add Cuts (`~/`) because standard Fastparse cuts cause immediate termination on failure. The compiler must remain resilient, generating "Poison Nodes" for analysis in later phases rather than aborting the compilation.

**Solution: Irrefutable Rules**
To fix performance without sacrificing resilience, we use **Local Cuts** combined with **In-Rule Recovery**. Once a specific keyword matches (e.g., `let`), we cut to prevent backtracking to other rules, but we make the subsequent parsing *irrefutable* by capturing local failures as `Poison`.

### Implementation Pattern

```scala
// Helper to capture parse failures as Poison Nodes without failing the rule
// Consumes input until 'delimiter' if 'parser' fails.
def resilient[T](parser: P[T], delimiter: String, toPoison: String => T)(using P[Any]): P[T] =
  P(parser | (!delimiter ~ AnyChar).rep(1).!.map(toPoison))

// Example: Optimized 'Let' Binding
def letExprP(info: SourceInfo)(using P[Any]): P[Term] =
  P(
    "let" ~/  // <--- CUT: We commit to this being a Let Expr. No going back.
    (
      // Try parsing valid binding, OR consume garbage until '=' and return Poison
      resilient(bindingIdP, delimiter = "=", bad => PoisonNode(bad))
    ) ~
    "=" ~/    // <--- CUT: We expect assignment now.
    (
      // Try parsing valid expr, OR consume garbage until ';' and return Poison
      resilient(exprP, delimiter = ";", bad => PoisonNode(bad))
    )
  ).map { case (binding, expr) =>
    // Always returns a valid AST node, even if children are Poison
    Let(binding, expr)
  }
```

### Benefits
1.  **Zero Backtracking:** Once `"let"` is matched, the parser never rewinds to check if it was actually a `litBool` or `ref`. This eliminates thousands of wasted cycles.
2.  **Full Resilience:** The parser never crashes on syntax errors; it swallows the specific error into the AST and continues to the next statement.
