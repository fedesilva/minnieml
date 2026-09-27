# Pipe rewrite

Status: Draft

---

## Rules

- `|>` is built-in syntax.
- The left side supplies the first argument of the next stage.
- Explicit arguments follow the piped argument, in source order.
- Stages associate left to right.
- No `_` placeholder is required.
- Lower to ordinary function applications. No runtime pipe operation.

```mml
x |> f          // f x
x |> f a        // f x a
x |> f a b      // f x a b
x |> f a |> g b // g (f x a) b
```

---

## Grouping

- A pipe separates stages; it has no ordinary numeric operator precedence.
- The complete preceding stage supplies the next stage's first argument.
- Insert that argument before applying the next stage's explicit arguments.
- A grouped callable receives the piped value as its next argument.
- Resolve ordinary operators within each stage after argument insertion.
- Pipes group within each semicolon-separated expression.
- Newlines and indentation do not change grouping.

```mml
g x |> f a      // f (g x) a
x |> f (g a)    // f x (g a)
x |> f a        // f x a
x |> (f a)      // (f a) x
h (x |> f a)    // h (f x a)
x + y |> f      // f (x + y)
x |> f a + b    // (f x a) + b
x |> f; y |> g  // f x; g y
```

---

## Application

- Insert the piped expression immediately after the stage's first term.
- References, lambdas, projections, and grouped expressions follow ordinary application rules.
- Non-callable heads produce ordinary type errors.
- `x |> f a` does not first evaluate `f a` as a partial application.
- After rewriting, ordinary typing and partial-application rules apply.
- A stage may leave parameters unapplied; its result is then a function value.
- Evaluation order and ownership follow the resulting ordinary application.
- The rewrite inserts the preceding expression once; it does not duplicate it.

---

## Example

```mml
fn a(s: String, i: Int): Int = s.length + i;;
fn b(i: Int): Int = i * 2;;

let x =
  "fede"
    |> a 1
    |> b
; // 10
```

Equivalent expression:

```mml
b (a "fede" 1)
```

---

## Lowering

- Preferred location: parser, alongside statement-sequence lowering.
- Apply in both ordinary and member expression parsing, before statement sequencing.
- Fold stages left to right.
- Preserve the accumulated expression as one argument of the next stage.
- Use existing `Expr` and `App` nodes.
- Leave ordinary operator resolution to `ExpressionRewriter`.
- No pipe-specific type checking, ownership handling, or code generation.

---

## Tokens

- Reserve exactly `|>` as syntax.
- Reject `op |>` declarations with a diagnostic identifying it as reserved syntax.
- Keep maximal matching for longer symbolic operators; `|>>` remains a single operator name.

---

## Diagnostics

- Retain the pipe location and the original piped expression's location through lowering.
- Preserve source locations of the stage head and explicit arguments.
- Argument errors can identify the inserted argument as "the piped value" and point to its source.

---

## Related

- [Placeholder partial application](placeholder-partial-application.md): proposed operator
  partial application with `_`, usable as a grouped pipe stage: `x |> (_ + 1)`.
