# Placeholder Partial Application

## Problem

Currently there's no way to partially apply operators:

```mml
op ++(a: Int, b: Int): Int = a + b;
let f = ???;  # how to get a function that adds 2 to something?
```

Eta-expansion handles undersaturated function calls (`concat "hello"`), but operators require infix syntax and have no prefix reference form like Haskell's `(+)`.

## Proposed Solution: Placeholder Syntax

Use `_` as a placeholder to create partial applications:

```mml
let addTwo = _ + 2;        # \(x) -> x + 2
let double = _ * 2;        # \(x) -> x * 2
let divBy = 10 / _;        # \(x) -> 10 / x
let both = _ + _;          # \(x, y) -> x + y
```

Works for functions too:

```mml
let greet = concat "Hello, " _;  # \(x) -> concat "Hello, " x
let greetFede = concat _ "Fede"; # \(x) -> concat x "Fede"
```

## Transformation Rules

At parse time (or early rewriting), transform expressions containing `_`:

1. **Single placeholder**: `expr with _` → `\($p0) -> expr with $p0`
2. **Multiple placeholders**: `_ op _` → `\($p0, $p1) -> $p0 op $p1`
3. **Nested**: Each `_` at the same expression level becomes a param, left-to-right

### Examples

```
2 + _           →  \($p0) -> 2 + $p0
_ + 2           →  \($p0) -> $p0 + 2
_ + _           →  \($p0, $p1) -> $p0 + $p1
concat "Hi" _   →  \($p0) -> concat "Hi" $p0
_ * _ + _       →  \($p0, $p1, $p2) -> $p0 * $p1 + $p2
```

## Implementation Options

### Option A: Parser-level transformation

Transform during parsing when `_` is encountered. The parser would:
1. Detect `_` tokens in expression position
2. Collect them and generate Lambda wrapper
3. Replace `_` with synthetic Refs to params

**Pros**: Clean separation, early transformation
**Cons**: Parser gets more complex, context needed

### Option B: AST placeholder node + rewriter

1. Parser emits `Placeholder` nodes for `_`
2. New rewriter pass collects placeholders and wraps in Lambda
3. Run before ExpressionRewriter

**Pros**: Parser stays simple, transformation is explicit
**Cons**: Extra pass, placeholder nodes in AST

**fede note** 2025-12-24: as of today I prefer this. But let's consider the parser, too.

### Option C: ExpressionRewriter extension

Extend existing ExpressionRewriter to detect and transform placeholder patterns.

**Pros**: Uses existing infrastructure
**Cons**: ExpressionRewriter already complex
          *fede note* yes. let's not.

## Open Questions

1. **Scope of placeholder**: Does `_` only work at expression level, or inside groups too?
   - `(2 + _) * 3` → `\(x) -> (2 + x) * 3`?
   - Or error because `_` is inside a group?

   * don't understand the question? Placeholder is a term, goes wherever a term can go.
   * just triggers partial application.
   * same as 
        ```
            fn sum(a, b) = ???;
            let x = sum 1;
            let y = sum 1 _;
            let z = 8 + _;
        ```
    * it is required by operators because 
        ```
            let a = 2 +;

2. **Interaction with existing partial application**: 
   - `concat _ "x"` vs `concat "x"` (eta-expanded)
   - Should be orthogonal - placeholder is explicit, eta is automatic

3. **Type inference**: Placeholder params need types from context
   - `2 + _` → param must be Int (from `+` signature)
   - May need type propagation from operator/function

4. **Precedence**: How does `_ + _ * _` parse?
   - Should follow normal operator precedence
   - `_ + (_ * _)` → `\(a, b, c) -> a + (b * c)`

## Related

- Scala's placeholder syntax: `_ + 1`, `list.map(_ * 2)`
- Haskell sections: `(+ 1)`, `(1 +)`
- Clojure's `#(+ % 1)`
