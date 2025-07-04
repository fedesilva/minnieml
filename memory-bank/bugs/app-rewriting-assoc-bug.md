# Bug: ExpressionRewriter Incorrectly Associates Function Applications

## Problem Summary

The ExpressionRewriter is building incorrect AST structures for curried function applications. 
When parsing expressions like `println concat "Fede" "Silva"`, it produces `(((println concat) "Fede") "Silva")` 
instead of the correct `(println ((concat "Fede") "Silva"))`.

## Technical Analysis

Function application should be treated as a highest-precedence operator. 
In the code, it's already set to `AppPrecedence = 100`, but the issue is in how arguments are collected.

### Key Principles

1. **Function application is left-associative**: `f x y z` → `(((f x) y) z)`
2. **Nested functions need special handling**: When encountering another function in argument position, it should start its own application chain
3. **Parentheses create boundaries**: `f (g h) x` means `(g h)` is evaluated first and passed as an argument to `f`

## Root Cause

The current code in `ExpressionRewriter.rewriteAtom` greedily collects ALL non-operator terms:
```scala
val nonOpArgs = rest.takeWhile(term => !isOperator(term))
```

This misses that function references should also create boundaries (unless parenthesized).

## Examples of Incorrect Behavior

### Example 1: `println concat "Fede" "Silva"`

**Current (incorrect) AST:**
```
App
  fn: App
    fn: App
      fn: Ref(println)
      arg: Ref(concat)
    arg: LiteralString("Fede")
  arg: LiteralString("Silva")
```

**Expected (correct) AST:**
```
App
  fn: Ref(println)
  arg: App
    fn: App
      fn: Ref(concat)
      arg: LiteralString("Fede")
    arg: LiteralString("Silva")
```

### Example 2: `f g h x`

This should parse as `f (g (h x))` not `(((f g) h) x)`:

**Expected AST:**
```
App
  fn: Ref(f)
  arg: App
    fn: Ref(g)
    arg: App
      fn: Ref(h)
      arg: Ref(x)
```

### Example 3: Parenthesized Expressions

#### Case 3a: `f (+) x` - Single operator/function as argument
When a parenthesized group contains just a single operator or function, it's passed as an argument without application:

**Expected AST:**
```
App
  fn: App
    fn: Ref(f)
    arg: TermGroup
      inner: Ref(+)  // Just the operator reference
  arg: Ref(x)
```

#### Case 3b: `f (g h) x` - Parenthesized application
When a parenthesized group contains an expression, it's evaluated normally:

**Expected AST:**
```
App
  fn: App
    fn: Ref(f)
    arg: TermGroup
      inner: App
        fn: Ref(g)
        arg: Ref(h)
  arg: Ref(x)
```

#### Case 3c: `f (x + y) z` - Parenthesized operation
Regular expressions in parentheses are rewritten normally:

**Expected AST:**
```
App
  fn: App
    fn: Ref(f)
    arg: TermGroup
      inner: App
        fn: App
          fn: Ref(+)
          arg: Ref(x)
        arg: Ref(y)
  arg: Ref(z)
```

## Proposed Solution

The argument collection logic should be modified to process one argument at a time instead of greedily collecting all non-operator terms:

```scala
// Process one argument at a time and stop at:
// 1. An operator
// 2. Another function reference (unless parenthesized)

def collectOneArg(terms: List[Term]): (Option[Term], List[Term]) = {
  terms match {
    case Nil => (None, Nil)
    case (g: TermGroup) :: rest => 
      // Parenthesized expressions are complete arguments
      // Special case: if it's just a single op/fn ref, don't try to apply it
      g.inner.terms match {
        case List(single @ (IsFnRef(_, _) | IsOpRef(_, _, _, _))) =>
          // Single function or operator in parens - pass as-is
          (Some(g), rest)
        case _ =>
          // Other expressions - rewrite normally
          (Some(g), rest)
      }
    case IsFnRef(_, _) :: _ =>
      // Another function starts a new chain (unless we want to pass it as arg)
      (None, terms)
    case IsOpRef(_, _, _, _) :: _ =>
      // Operators end the current application chain
      (None, terms)
    case term :: rest =>
      // Regular terms (literals, variables) are arguments
      (Some(term), rest)
  }
}
```

## Algorithm Outline

1. When encountering a function `f`:
   - Look at the next term
   - If it's a parenthesized group, take it as an argument
   - If it's another function `g`, recursively process `g` first
   - If it's a regular term (literal, variable), take it as an argument
   - If it's an operator, stop
   
2. For nested functions `f g x y`:
   - Process `f`
   - See `g` (a function), so recursively process `g x y` → `((g x) y)`
   - Apply `f` to the result: `(f ((g x) y))`

## Edge Cases to Consider

1. **Empty applications**: `f ()` should work
2. **Operator as argument**: `f (+) x` where `(+)` is passed as a value to `f`
3. **Function as argument**: `map (f) list` where `(f)` is passed as a value
4. **Parenthesized expressions**: `f (x + y)` evaluates `x + y` first
5. **Complex nesting**: `f g h i j k` should correctly nest all applications
6. **Mixed with operators**: `f g x + h y` should parse as `((f (g x)) + (h y))`
7. **Higher-order functions**: `foldr (+) 0 list` where `(+)` is an argument

## Implementation Notes

- Function application already has highest precedence (100)
- The issue is not precedence but how arguments are collected
- Need to distinguish between "function in application position" vs "function as argument"
- Parentheses always create a complete, bounded expression

## Test Cases Required

Tests should verify correct handling of:
- Simple nested applications: `f g x` → `(f (g x))`
- Multi-argument functions: `f g x y` → `(f ((g x) y))`
- Mixed expressions: `f g x + h y` → `((f (g x)) + (h y))`
- Deeper nesting: `f g h x y z` → `(f (g (h ((x y) z))))`
- Parenthesized operators: `map (+) list` where `(+)` is passed as argument
- Parenthesized functions: `foldr (f) init list` where `(f)` is passed as argument

## Impact

This bug affects any code using curried functions with multiple arguments, 
which is fundamental to ML-style languages. 
It prevents correct compilation of basic functional patterns.
It hinders our current effort to rewrite the codegen, so it's a high priority task.