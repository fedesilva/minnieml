# Function Application in MML: Unified Operator Approach

## Core Concept

Function application through juxtaposition can be viewed as a special kind of operator:
- Has highest precedence (100 in our implementation)
- Left associative (`func 1 2` is parsed as `(func 1) 2`)
- N-ary (can take any number of arguments)
- Implicit (represented by juxtaposition of terms)

## The Fundamental Rule

After processing a complete expression (lhs), the next valid term must be an operator:
- Either an explicit operator like `+`, `-`, etc.
- Or the implicit juxtaposition operator for function application (when in a valid context)

The context determines which operators are valid next, based on:
1. The current position in the expression
2. The precedence of operators in the current context
3. The rules of the precedence climbing algorithm

## Examples

### Valid Expressions
```
func 1 2 3      // Valid: (((func 1) 2) 3) - chained application
1 + func 1 2    // Valid: 1 + ((func 1) 2) - operator followed by application chain
(func 1) + 2    // Valid: complete expression followed by operator
1 + (func 1 2)  // Valid: operator followed by complete expression
```

### Invalid Expressions
```
(func 1) 2      // Invalid: complete expression followed by term (not an operator)
1 + (func 1) 2  // Invalid: complete expression in operator context followed by term
let a = 2 (1)   // Invalid: number can't be applied (not a function)
```

## Implementation Approach

The unified approach treats precedence climbing as handling all operators:

1. **Operator precedence**: Function application has highest precedence (100)
2. **Context sensitivity**: Different contexts allow different operators
3. **Error reporting**: Terms in invalid positions should be reported as errors

## Simplification Opportunity

Our current implementation has complex logic for handling dangling terms. A cleaner approach would be:

1. Model function application explicitly as an operator in the precedence climbing algorithm
2. Apply standard precedence climbing rules to determine when juxtaposition is valid
3. Generate errors based on what operators are expected in the current context

This simplifies our implementation while maintaining correct ML-style semantics.

## Example Analysis

### `(func 1) 2`
1. `(func 1)` is a complete expression
2. `2` is not an operator, but a term
3. After a complete expression, we need an operator
4. Error: Expected operator, got term

### `func (1) 2`
1. `func` is a function reference
2. `(1)` is an argument (valid in application context)
3. `2` is another argument (valid in application context) 
4. Valid: Juxtaposition operator applies between each term

### `(func 1) func 1`
1. `(func 1)` is a complete expression
2. `func 1` is not an operator (it's another expression)
3. After a complete expression, we need an operator
4. Error: Expected operator, got expression