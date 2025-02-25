# MML Operator Precedence Documentation

This document explains how operator precedence and associativity work in MinnieML, with examples showing the expected AST structure for various expression patterns.

## Document status

This is a living document. 

We are still missing function application which
will be treated as a weird unary operator that consumes it's arity of arguments.

Once that is done, we might revisit the way we treat operators and might
rewrite them as application, too.

After that, we will be close enough to the TLIR (typed lambda intermediate representation)
which will be the core language of the compiler.

## A note on the architecture

The parser itself is based on parser combinators and there is no separate lexer.
As we parse the input, we immediately build an abstract syntax tree (ast) that represents 
the structure of the input.

Since we allow for custom operators, we parse expressions as flat structures consisting of 
terms which can be literals, or references to functions, bindings or operators.

After the ast is built, we run a pass to resolve the references to the actual definitions.
This pass uses context information to resolve the references to the correct definitions;
it can identify the correct operator based on the position and associativity of the operator.

Once the references are resolved, we run a pass to rewrite the flat expresions
into a tree structure that represents the precedence and associativity of the operators.
This is based on the Pratt operator climbing algorithm but it operates on ast nodes 
instead of tokens.

Finally, since the precedence climbing algorithm wraps nodes in expression that don't
actually need to be wrapped, we run a pass to unwrap the nodes that don't 
need to be wrapped - the simplifer pass.

## Operator Definitions

Users can define custom operators with different symbols, associativity, and precedence.

All examples assume the following standard operators are defined:

```mml
op ^ (a b) 90 right = ???;  // Exponentiation: right-associative, precedence 90
op * (a b) 80 left  = ???;  // Multiplication: left-associative, precedence 80
op / (a b) 80 left  = ???;  // Division: left-associative, precedence 80
op + (a b) 60 left  = ???;  // Addition: left-associative, precedence 60
op - (a b) 60 left  = ???;  // Subtraction: left-associative, precedence 60
op - (a)   95 right = ???;  // Unary minus: right-associative, precedence 95
op + (a)   95 right = ???;  // Unary plus: right-associative, precedence 95
op ! (a)   95 left  = ???;  // Factorial: left-associative (postfix), precedence 95
```

## Basic Binary Operations

The AST shown in the examples is a simplified representation of the actual AST structure. It omits stuff like types and source spans for brevity.

### Simple Binary Operation

```mml
let a = 1 + 1;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    LiteralInt(1),
    Ref("+", resolvedAs=BinOpDef),
    LiteralInt(1)
  ]
)
```

The expression is a flat list of three terms: left operand, operator, right operand.

### Multiple Binary Operations with Different Precedence

```mml
let a = 1 + 1 * 2;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    LiteralInt(1),
    Ref("+", resolvedAs=BinOpDef),
    Expr[
      LiteralInt(1),
      Ref("*", resolvedAs=BinOpDef),
      LiteralInt(2)
    ]
  ]
)
```

Since multiplication has higher precedence (80) than addition (60), the expression `1 * 2` becomes a subexpression within the addition operation.

### Chained Operations with Equal Precedence

```mml
let a = 1 - 2 - 3;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    Expr[
      LiteralInt(1),
      Ref("-", resolvedAs=BinOpDef),
      LiteralInt(2)
    ],
    Ref("-", resolvedAs=BinOpDef),
    LiteralInt(3)
  ]
)
```

For left-associative operators of equal precedence like `-`, the operations are grouped from left to right, effectively parsing as `(1 - 2) - 3`.

### Multiple Operators with Nested Precedence

```mml
let a = 1 + 1 * 2 / 3;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    LiteralInt(1),
    Ref("+", resolvedAs=BinOpDef),
    Expr[
      Expr[
        LiteralInt(1),
        Ref("*", resolvedAs=BinOpDef),
        LiteralInt(2)
      ],
      Ref("/", resolvedAs=BinOpDef),
      LiteralInt(3)
    ]
  ]
)
```

This parses as `1 + ((1 * 2) / 3)` because:
1. `*` and `/` have the same precedence (80)
2. Both are left-associative, so they group from left to right
3. Both have higher precedence than `+` (60)

### Mixed Associativity

```mml
let a = 1 + 1 * 2 ^ 3;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    LiteralInt(1),
    Ref("+", resolvedAs=BinOpDef),
    Expr[
      LiteralInt(1),
      Ref("*", resolvedAs=BinOpDef),
      Expr[
        LiteralInt(2),
        Ref("^", resolvedAs=BinOpDef),
        LiteralInt(3)
      ]
    ]
  ]
)
```

This parses as `1 + (1 * (2 ^ 3))` because:
1. `^` has the highest precedence (90) and is right-associative
2. `*` has the next highest precedence (80)
3. `+` has the lowest precedence (60)

### Right Associativity

```mml
let a = 2 ^ 3 ^ 2;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    LiteralInt(2),
    Ref("^", resolvedAs=BinOpDef),
    Expr[
      LiteralInt(3),
      Ref("^", resolvedAs=BinOpDef),
      LiteralInt(2)
    ]
  ]
)
```

Since `^` is right-associative, this expression parses as `2 ^ (3 ^ 2)` rather than `(2 ^ 3) ^ 2`.

## Unary Operators

### Postfix Unary Operator

```mml
let a = 4!;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    LiteralInt(4),
    Ref("!", resolvedAs=UnaryOpDef)
  ]
)
```

The postfix operator `!` is applied directly to the preceding value.

### Prefix Unary Operator

```mml
let a = -3;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    Ref("-", resolvedAs=UnaryOpDef),
    LiteralInt(3)
  ]
)
```

The prefix operator `-` is applied to the following value.

### Chained Unary Operators

```mml
let a = - - 3;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    Ref("-", resolvedAs=UnaryOpDef),
    Expr[
      Ref("-", resolvedAs=UnaryOpDef),
      LiteralInt(3)
    ]
  ]
)
```

Multiple prefix operators can be chained, with each applying to the result of the next.

### Mixed Unary Operators

```mml
let a = + - 3;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    Ref("+", resolvedAs=UnaryOpDef),
    Expr[
      Ref("-", resolvedAs=UnaryOpDef),
      LiteralInt(3)
    ]
  ]
)
```

Different prefix operators can be combined, with the leftmost operator applying to the result of the ones to its right.

## Complex Cases

### Unary and Binary Operators with Same Symbol

```mml
let a = -3 - -2;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    Expr[
      Ref("-", resolvedAs=UnaryOpDef),
      LiteralInt(3)
    ],
    Ref("-", resolvedAs=BinOpDef),
    Expr[
      Ref("-", resolvedAs=UnaryOpDef),
      LiteralInt(2)
    ]
  ]
)
```

The parser correctly disambiguates between unary and binary uses of the same operator `-` based on context. This parses as `(-3) - (-2)`.

### Complex Mixed Operators

```mml
let a = +4! - 2!;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    Expr[
      Ref("+", resolvedAs=UnaryOpDef),
      Expr[
        LiteralInt(4),
        Ref("!", resolvedAs=UnaryOpDef)
      ]
    ],
    Ref("-", resolvedAs=BinOpDef),
    Expr[
      LiteralInt(2),
      Ref("!", resolvedAs=UnaryOpDef)
    ]
  ]
)
```

This complex expression combines:
1. A prefix operator (`+`) applied to a number with a postfix operator (`4!`)
2. A binary operator (`-`)
3. Another number with a postfix operator (`2!`)

The expression parses as `((+4!) - (2!))`.

### Unary and Exponentiation Precedence

```mml
let a = -2 ^ 2;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    Expr[
      Ref("-", resolvedAs=UnaryOpDef),
      LiteralInt(2)
    ],
    Ref("^", resolvedAs=BinOpDef),
    LiteralInt(2)
  ]
)
```

Even though unary `-` has higher precedence (95) than `^` (90), the expression parses as `(-2) ^ 2` rather than `-(2 ^ 2)` because the operator precedence climbing algorithm properly identifies which terms go together.

## Grouping and Explicit Precedence

### Parenthesized Expressions

```mml
let a = (1 + 2) * 3;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    Expr[
      LiteralInt(1),
      Ref("+", resolvedAs=BinOpDef),
      LiteralInt(2)
    ],
    Ref("*", resolvedAs=BinOpDef),
    LiteralInt(3)
  ]
)
```

Parentheses override normal precedence rules. Here, `(1 + 2)` is evaluated as a group before being multiplied by 3.

### Complex Grouping

```mml
let a = (1 + 2) * (3 - 4) / 5;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    Expr[
      Expr[
        LiteralInt(1),
        Ref("+", resolvedAs=BinOpDef),
        LiteralInt(2)
      ],
      Ref("*", resolvedAs=BinOpDef),
      Expr[
        LiteralInt(3),
        Ref("-", resolvedAs=BinOpDef),
        LiteralInt(4)
      ]
    ],
    Ref("/", resolvedAs=BinOpDef),
    LiteralInt(5)
  ]
)
```

This complex expression demonstrates:
1. Parentheses forcing evaluation order
2. Left-associativity of operators with equal precedence (`*` and `/`)

The expression parses as `((1 + 2) * (3 - 4)) / 5`.

## Custom Operators

### Multi-Character Operators

```mml
op -- (a b) = ???;
let a = 3 -- -4;
```

**Expected AST:**
```
Bnd(a,
  Expr[
    LiteralInt(3),
    Ref("--", resolvedAs=BinOpDef),
    Expr[
      Ref("-", resolvedAs=UnaryOpDef),
      LiteralInt(4)
    ]
  ]
)
```

The parser correctly handles multi-character operators, even when they contain characters that are also used for other operators. This parses as `3 -- (-4)`.

## Error Cases

### Consecutive Postfix Operators

```mml
let a = 4!!;
```

**Result: Semantic Error**

This is invalid because postfix operators cannot be applied consecutively without parentheses. The parser should correctly identify and report this as an error.

## Additional Test Cases to Consider

The following test cases would further strengthen the test suite:

1. **Mixed associativity without parentheses**
   ```mml
   let a = 1 + 2 ^ 3 + 4;  // Should parse as: 1 + (2 ^ 3) + 4
   ```

2. **Operators with same symbol but different arity**
   ```mml
   op ++ (a) = a + 1;
   op ++ (a b) = a + b;
   let a = 1 ++ 2;  // Binary usage
   let b = ++1;     // Unary prefix usage
   ```

3. **Custom operators overriding standard precedence**
   ```mml
   op @* (a b) 100 left = a * b;  // Higher precedence than standard *
   let a = 1 + 2 @* 3;  // Should parse as: 1 + (2 @* 3)
   ```

4. **Operators with unusual symbols**
   ```mml
   op <$> (a b) 65 left = a + b;
   op <*> (a b) 70 left = a * b;
   let a = f <$> x <*> y;  // Should parse as: (f <$> x) <*> y
   ```

5. **Multiple identical operators with right associativity**
   ```mml
   let a = 2 ^ 2 ^ 2 ^ 2;  // Should parse as: 2 ^ (2 ^ (2 ^ 2))
   ```

6. **Unary operators with different precedence**
   ```mml
   op $ (a) 97 right = a;  // Higher precedence than -
   let a = -$3;  // Should parse as: -($ 3)
   ```

7. **Chained binary operators with same precedence**
   ```mml
   let a = 10 / 5 * 2;  // Should parse as: ((10 / 5) * 2)
   ```
