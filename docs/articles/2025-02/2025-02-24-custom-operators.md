# MinnieML Operator Precedence and Associativity

This article explains how operator precedence and associativity work in MinnieML, 
with examples showing the expected ast structure for various expression patterns.

## Compiler status

We still need to implement function application, which we plan to treat as a prefix n-ary operator
with the highest precedence and left associativity.

Once that is done, we will revisit the way we treat operators and rewrite them as application, too.

After that, we will be close enough to the TLIR (typed lambda intermediate representation)
which will be the core language of the compiler.

## Pipeline Overview

### Parser

The parser itself is based on parser combinators so there is no separate lexer.
As we parse the input, we immediately build an abstract syntax tree (ast) that represents 
the structure of the input.

Since we allow for custom operators, we parse expressions as flat structures consisting of 
terms which can be literals, or references to functions, bindings or operators as we don't
know yet what the references point to and what the attributes of the operators are.

At this point, the parsing is done and we move to the first semantic phases.

### Semantic Analysis Passes

The semantic pipeline, so far, consists of three passes that transform the initial flat 
expressions into a referentially resolved and structured representation.

These phases work on the ast. The phases are functions that take a module
and return a module, modified per the rules of the phase.

#### Reference Resolution

The `RefResolver` is responsible for resolving operator references in the ast. 
Its key features include:

1. **Context-Aware Reference Resolution**
   * Tracks whether the compiler is expecting an operand or an operator
   * In operand position:
      * First tries to resolve to non-operator values (literals, bindings, functions)
      * Falls back to prefix unary operators (right-associative)
   * In operator position:
      * Tries to resolve to binary operators
      * Falls back to postfix unary operators (left-associative)

2. **Smart Disambiguation**
   * Handles complex scenarios like chained prefix operators
   * Identifies the correct type of reference based on position
   * Produces semantic errors for unresolvable references

The information collected during this phase is used by the next phase to build 
a structured expression tree.

#### Precedence Climbing

The `PrecedenceClimbing` component implements the actual operator precedence and associativity rules:

1. **Rewriting Strategy**
   * Recursively walks the ast to re write it as a structured expression tree
   * Handles prefix, infix, and postfix operators using the reference information 
     collected by the `RefResolver`
   * Groups terms based on operator precedence and associativity using 
     a precedence climbing algorithm.

3. **Advanced Features**
   * Correctly handles nested expressions
   * Manages different operator types (unary, binary) even with the same symbol 
     in the same expression.
   * Supports arbitrary operator precedence levels
   * Supports left and right associativity


##### Pratt Parsing 

MinnieML uses a Pratt-style approach to convert flat expressions into a structured tree. 
In Pratt parsing, expressions are grouped according to each operator’s precedence and associativity, 
ensuring the correct evaluation order without relying on a rigid grammar hierarchy.

Unlike a typical token-based Pratt parser, MinnieML operates on the ast, 
where the operators in an expression are references to the operator definitions. 
Each reference node already knows its operator’s precedence and associativity, 
eliminating the need for dynamic operator detection. 

This allows MinnieML to handle custom operators seamlessly, 
regardless of type, position, arity, or precedence.


#### Simplification Pass

The `Simplifier` serves as the final pass in the expression rewriting pipeline, 
responsible for cleaning up the ast:

1. **Purpose**
   * Removes unnecessary expression wrappers
   * Eliminates redundant ast nodes
   * Creates a compact, semantically precise representation
   * Each expression has enough operands to be evaluated, but no more.

2. **Simplification Strategies**
   * Recursively traverses module members
   * Unwraps single-term expressions
   * Dissolves group term artifacts

The combination of `RefResolver`, `PrecedenceClimbing`, and `Simplifier` allows MinnieML to:
* Resolve references contextually
* Parse complex expressions with custom operators
* Maintain precise control over operator behavior
* Create a clean, minimal ast representation

This approach yields a flexible syntax that supports full control over operator definitions,
with custom precedence and associativity.


## Future work and problems

When we introduce support for protocols and polymorphism, we will have to revisit the way
we resolve references. 

We will lose the ability to resolve references to a single definition like we do now, in favor of 
multiple potential candidates, and we will have to limit the resolution so that it fails
if different associativities or precedences are found for the same name and type of operator.
If we failed to do that, we would have to create multiple variations of the same 
expression, each rewritten differently depending on the attributes of the resolved operators.

In general for common operators, the Prelude will setup a reasonable set of operators,
which will serve as a framework for the rest of the program..

Once there is enough type information we will be able to drop invalid candidates.

## Operator Definitions

All examples assume the following standard operators are defined, here defined using 
the actual mml syntax.

```mml
    op ^ (a b) 90 right = ???;  # Exponentiation: right-associative, precedence 90
    op * (a b) 80 left  = ???;  # Multiplication: left-associative, precedence 80
    op / (a b) 80 left  = ???;  # Division: left-associative, precedence 80
    op + (a b) 60 left  = ???;  # Addition: left-associative, precedence 60
    op - (a b) 60 left  = ???;  # Subtraction: left-associative, precedence 60
    op - (a)   95 right = ???;  # Unary minus: right-associative, precedence 95
    op + (a)   95 right = ???;  # Unary plus: right-associative, precedence 95
    op ! (a)   95 left  = ???;  # Factorial: left-associative (postfix), precedence 95
```

## Basic Binary Operations

The ast shown in the examples is a simplified representation of the actual ast structure. It omits stuff like types and source spans for brevity.

### Simple Binary Operation

```mml
let a = 1 + 1;
```

**Expected ast:**
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

As you can see, the expression has enough operands to be evaluated.

### Multiple Binary Operations with Different Precedence

```mml
let a = 1 + 1 * 2;
```

**Expected ast:**
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

**Expected ast:**
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

For left-associative operators of equal precedence like `-`, the operations are grouped from left to right,
effectively being rewritten as `(1 - 2) - 3`.

Here, too, you can see, that each expression has two operands, since both operators are binary operators.

### Multiple Operators with Nested Precedence

```mml
let a = 1 + 1 * 2 / 3;
```

**Expected ast:**
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

**Expected ast:**
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

**Expected ast:**
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

**Expected ast:**
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

**Expected ast:**
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

**Expected ast:**
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

**Expected ast:**
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

**Expected ast:**
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

The parser correctly disambiguates between unary and binary uses of the same operator name `-` based on context. This parses as `(-3) - (-2)`.

### Complex Mixed Operators

```mml
let a = +4! - 2!;
```

**Expected ast:**
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

**Expected ast:**
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

**Expected ast:**
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

**Expected ast:**
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

**Expected ast:**
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

This is invalid because postfix operators cannot be applied consecutively without parentheses. 
The parser correctly identifes and reports this as an error.

### Unresolvable reference

```mml    
    let a = -x ^ 2;
```

**Result: Semantic Error**

The compiler cannot resolve `x` to a valid reference, resulting in an error.
Same thing would happen if there was a reference to an operator that is not defined.


