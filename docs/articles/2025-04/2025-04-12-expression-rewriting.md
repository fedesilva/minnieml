# MinnieML Expression Rewriting

This is an overview of the current implementation of expression rewriting in MinnieML, focusing on the unified handling of operators and function applications.

## Architecture Overview

The expression rewriting pipeline consists of two main phases:

1. **Reference Resolution**: Collects all possible interpretations for each reference
2. **Expression Rewriting**: Applies precedence climbing to structure expressions

This is a significant evolution from the approach described in the [custom operators article](2025-02-24-custom-operators.md), which had components with overlapping responsibilities.

## Reference Resolution

The `RefResolver` now focuses solely on collecting candidate definitions for each reference. It:

- Gathers all module members matching a reference name
- Stores these candidates in the reference node
- Defers contextual disambiguation to the expression rewriting phase

This is simpler than the previous approach where `RefResolver` performed context-aware resolution directly.
This module is now free of complexity that belongs and was duplicated from the next phase.

## Unified Expression Rewriting

The `ExpressionRewriter` now handles all expression structuring in a single pass, treating both operators and function applications with the same mechanism:

- Uses precedence climbing for both operators and applications
- Treats function application as an implicit high-precedence operator (juxtaposition)
- Sets function application precedence to 100 (highest in the system)
- Handles mixed expressions with both operators and function calls

## Contextual Disambiguation: The Heart of the Semantic Phase

A key feature of the expression rewriting system is its contextual disambiguation capability. This critical phase of the compiler occurs before type checking.

The `ExpressionRewriter` intelligently determines the correct interpretation of each reference based on its position and context within an expression. For example:

- The same symbol like `+` can be resolved as either a unary prefix operator or a binary operator
- In `+4`, the `+` is resolved as a unary prefix operator
- In `2 + 4`, the same `+` is resolved as a binary operator
- In `mult 2 2`, the `mult` is recognized as a function reference, with function application having the highest precedence

Without this contextual analysis, expressions like `+4! - 2!` would be ambiguous, as `+` could be interpreted multiple ways. The system uses the following logic:

- In operand position: Try to resolve to non-operator values first, falling back to prefix operators
- In operator position: Try to resolve to binary operators first, falling back to postfix operators
- Apply precedence rules to structure the expression correctly

This position-sensitive resolution is essential for handling custom operators and enables the language's flexible yet predictable syntax.

## Pipeline Example 1: Mixed Operators

Consider this expression with mixed unary and binary operators:

```mml
let a = +4! - 2!;
```

### Original Flat AST

```
Bnd a
    Expr
      Ref +
        candidates: []
      LiteralInt 4
      Ref !
        candidates: []
      Ref -
        candidates: []
      LiteralInt 2
      Ref !
        candidates: []
```

### After Reference Resolution

At this point he have collected candidates for each reference.
The expression remains flat.

```
Bnd a
    Expr
      Ref +
        candidates: [BinOpDef +, UnaryOpDef +]
      LiteralInt 4
      Ref !
        candidates: [UnaryOpDef !]
      Ref -
        candidates: [BinOpDef -, UnaryOpDef -]
      LiteralInt 2
      Ref !
        candidates: [UnaryOpDef !]
```

### After Expression Rewriting

The expression rewriting algorithm, using the information collected
during locals resolution, resolves references and uses that information
to rewrite the expression to express function application and operator
precedence and associativity.

```
Bnd a
    Expr
      Expr
        Ref +
          resolvedAs: UnaryOpDef +
          candidates: [BinOpDef +, UnaryOpDef +]
        Expr
          Expr
            LiteralInt 4
          Ref !
            resolvedAs: UnaryOpDef !
            candidates: [UnaryOpDef !]
      Ref -
        resolvedAs: BinOpDef -
        candidates: [BinOpDef -, UnaryOpDef -]
      Expr
        Expr
          LiteralInt 2
        Ref !
          resolvedAs: UnaryOpDef !
          candidates: [UnaryOpDef !]
```

This AST represents the following:

```mml
let a = ((+4)!) - (2!);
```

## Pipeline Example 2: Function Application with Operators

```mml
fn mult (a b) = ???;
let a = 2 * 4! - mult 2 2;
```

### After Expression Rewriting (Final Simplified AST)

```
Bnd a
    Expr
      Expr
        LiteralInt 2
        Ref *
          resolvedAs: BinOpDef *
          candidates: [BinOpDef *]
        Expr
          LiteralInt 4
          Ref !
            resolvedAs: UnaryOpDef !
            candidates: [UnaryOpDef !]
      Ref -
        resolvedAs: BinOpDef -
        candidates: [BinOpDef -, UnaryOpDef -]
      App
        fn:
          App
            fn:
              Ref mult
                resolvedAs: FnDef mult
                candidates: [FnDef mult]
            arg:
              Expr
                LiteralInt 2
        arg:
          Expr
            LiteralInt 2
```

Like if you wrote the following, which again is what you would expect:

```mml
let a = ((2 * (4!)) - ((mult 2) 2));
```

Note how function application is structured as nested `App` nodes with the highest precedence,
while still respecting operator precedence rules. The function application `mult 2 2` is interpreted as `(mult 2) 2`, illustrating the left-associative nature of function application.

## Pipeline Example 3: Boolean Operators

Boolean operations are a perfect example of how the unified expression rewriting system elegantly
handles complex expressions without special parsing cases.

The system uses the specified operator precedence (see later for definitions) to create the expected
behavior of boolean logic.

```mml
let flag1 = false;
let flag2 = true;

let result =
  if not flag1 and flag2 or not flag1 and not flag2 then
    "NADA"
  else
    "ALGO"
;
```

### Original Flat AST

```
Bnd result
    Expr
      Cond
        cond:
          Expr
            Ref not
              candidates: []
            Ref flag1
              candidates: []
            Ref and
              candidates: []
            Ref flag2
              candidates: []
            Ref or
              candidates: []
            Ref not
              candidates: []
            Ref flag1
              candidates: []
            Ref and
              candidates: []
            Ref not
              candidates: []
            Ref flag2
              candidates: []
        ifTrue:
          Expr
            LiteralString "NADA"
        ifFalse:
          Expr
            LiteralString "ALGO"
```

### After Reference Resolution

```
Bnd result
    Expr
      Cond
        cond:
          Expr
            Ref not
              candidates: [UnaryOpDef not]
            Ref flag1
              candidates: [Bnd flag1]
            Ref and
              candidates: [BinOpDef and]
            Ref flag2
              candidates: [Bnd flag2]
            Ref or
              candidates: [BinOpDef or]
            Ref not
              candidates: [UnaryOpDef not]
            Ref flag1
              candidates: [Bnd flag1]
            Ref and
              candidates: [BinOpDef and]
            Ref not
              candidates: [UnaryOpDef not]
            Ref flag2
              candidates: [Bnd flag2]
        ifTrue:
          Expr
            LiteralString "NADA"
        ifFalse:
          Expr
            LiteralString "ALGO"
```

### After Expression Rewriting (Final Simplified AST)

```
Bnd result
    Expr
      Cond
        cond:
          Expr
            Expr
              Expr
                Ref not
                  resolvedAs: UnaryOpDef not
                  candidates: [UnaryOpDef not]
                Ref flag1
                  candidates: [Bnd flag1]
              Ref and
                resolvedAs: BinOpDef and
                candidates: [BinOpDef and]
              Ref flag2
                candidates: [Bnd flag2]
            Ref or
              resolvedAs: BinOpDef or
              candidates: [BinOpDef or]
            Expr
              Expr
                Ref not
                  resolvedAs: UnaryOpDef not
                  candidates: [UnaryOpDef not]
                Ref flag1
                  candidates: [Bnd flag1]
              Ref and
                resolvedAs: BinOpDef and
                candidates: [BinOpDef and]
              Expr
                Ref not
                  resolvedAs: UnaryOpDef not
                  candidates: [UnaryOpDef not]
                Ref flag2
                  candidates: [Bnd flag2]
        ifTrue:
          Expr
            LiteralString "NADA"
        ifFalse:
          Expr
            LiteralString "ALGO"
```

This represents the expression as if it were written with explicit parentheses:

```mml
if ((not flag1) and flag2) or ((not flag1) and (not flag2)) then
  "NADA"
else
  "ALGO"
```

Without any special parsing rules, the user defined operator precedence naturally creates the correct logical structure:

1. The unary `not` operator has a precedence of 95 (highest among the boolean operators), so it binds tightly to its operands
2. The binary `and` operator has a precedence of 40 (higher than `or`)
3. The binary `or` operator has a precedence of 30 (lowest of the group)

This hierarchy ensures that:

- `not` operators are applied first to their respective operands
- `and` operations are evaluated before `or` operations
- The entire expression follows standard boolean logic evaluation order

The beauty of this approach is that we don't need parentheses or special syntax rules to represent boolean logic - the same unified expression rewriting mechanism handles boolean operations just like any other operators in the system. Alphabetic operators like `and`, `or`, and `not` are treated identically to symbolic operators, with their behavior defined by their precedence and associativity values.

Because the language will provide a well defined set of operators with standard definitions,
and because the compiler will reject overrides ( no duplicates allowed ), the user will have - unless they mess
with the prelude - a sane operator set, working as they expect.

## Implementation Details

1. **Precedence Levels**:

   - Function application: 100 (highest)
   - Unary operators: 95
   - Exponentiation: 90
   - Multiplication/Division: 80
   - Addition/Subtraction: 60
   - Comparisons: 50
   - Logical operators: 30-40

2. **Application Chains**:
   - Function applications are built as left-associative chains
   - Multiple arguments are interpreted as nested applications
   - For example, `f a b c` becomes `((f a) b) c` in the AST

## Standard Operators

The compiler automatically injects a set of standard operators into every module via the `injectStandardOperators` function. These include:

### Binary Operators

```mml
op ^ (a b) 90 right = ???;  # Exponentiation
op * (a b) 80 left  = ???;  # Multiplication
op / (a b) 80 left  = ???;  # Division
op + (a b) 60 left  = ???;  # Addition
op - (a b) 60 left  = ???;  # Subtraction
op == (a b) 50 left = ???;  # Equality
op != (a b) 50 left = ???;  # Inequality
op < (a b) 50 left  = ???;  # Less than
op > (a b) 50 left  = ???;  # Greater than
op <= (a b) 50 left = ???;  # Less than or equal
op >= (a b) 50 left = ???;  # Greater than or equal
op and (a b) 40 left = ???; # Logical AND
op or (a b) 30 left  = ???; # Logical OR
```

### Unary Operators

```mml
op - (a) 95 right = ???;  # Unary minus
op + (a) 95 right = ???;  # Unary plus
op ! (a) 95 left  = ???;  # Factorial (postfix)
op not (a) 95 right = ???; # Logical NOT (prefix)
```

Note that MinnieML supports alphabetic operators like `and`, `or`, and `not` alongside symbolic operators. The system treats all operators uniformly regardless of their naming style.

## Key Differences from Previous Implementation

1. **Decoupled Resolution**: Reference resolution and expression rewriting are more cleanly separated
2. **Implemented Application**: Function application is fully implemented (was marked as TODO)
3. **Unified Approach**: Both operators and function application use the same precedence climbing mechanism
4. **Component Renaming**: `PrecedenceClimbing` functionality is now in `ExpressionRewriter`
5. **Alphabetic Operators**: Full support for alphabetic operators like `and`, `or`, and `not`

This approach provides a consistent, extensible foundation for the compiler pipeline as we move toward the
Typed Lambda Intermediate Representation (TLIR).
