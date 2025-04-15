# Error Accumulation in Semantic Analysis

## Current Implementation

The semantic analysis phase currently uses `Either[List[SemanticError], A]` for error handling, which fails fast on the first error encountered. While the parser already handles errors by creating marker AST nodes (`MemberError`), allowing compilation to continue, the semantic phase stops at the first error.

## Motivation for Change

Accumulating errors during semantic analysis provides several benefits:

- Better user experience by reporting multiple errors in a single compilation run
- Consistent error handling approach with the parsing phase
- More comprehensive feedback for users, reducing development iteration cycles

## Implementation Strategy: Phased Approach

### Phase 1: Member-Level Error Accumulation

Initial implementation focuses on accumulating errors across top-level members while minimizing changes to complex internal algorithms:

1. **Sequential Pipeline Preservation**

   - Maintain the existing pipeline structure in `SemanticApi.scala`
   - Keep phases running in their current order
   - Use `ValidatedNel[SemanticError, A]` at the module level

2. **Member-Level Error Collection**

   - Allow each semantic checker to process all members
   - Collect errors from each member while still allowing individual member analysis to fail fast
   - Return both accumulated errors and partially processed module

3. **Pipe Operator Extensions**
   - Extend the existing `|>` operator to work with `ValidatedNel`
   - Support seamless mixing of `Either` and `ValidatedNel` during transition

```scala
// Pipe extension for ValidatedNel
extension [E, A](self: ValidatedNel[E, A])
  /** Pipe for Validated */
  def |>[B](f: A => ValidatedNel[E, B]): ValidatedNel[E, B] = self.andThen(f)

  /** Pipe for integrating Either-based functions */
  def |>[B](f: A => Either[List[E], B]): ValidatedNel[E, B] =
    self.andThen(a => f(a).toValidatedNel)
```

### Phase 2: Deep Error Accumulation (Future Work)

The long-term plan involves more extensive refactoring of internal algorithms:

1. **ExpressionRewriter Enhancement**

   - Refine the precedence climbing algorithm to collect errors from different branches
   - Implement more sophisticated error recovery mechanisms

2. **Fallback Mechanisms**
   - Design fallback AST constructions for error cases
   - Enable continued processing even when expressions contain errors

## Technical Considerations

1. **Type Changes**

   - Move from `Either[List[SemanticError], A]` to `cats.data.ValidatedNel[SemanticError, A]`
   - Convert at API boundaries between internal and external representations

2. **Composition Patterns**

   - Use `andThen` for sequential operations that must follow previous steps
   - Use `mapN` for independent operations where possible

3. **Balancing Progress vs. Error Collection**
   - Accept that some critical errors must still terminate processing
   - Design around progress markers to maintain valid syntax trees with error nodes

## Benefits of This Approach

- **Immediate Value**: Users see more errors in a single compile run
- **Reduced Risk**: Minimal disruption to complex, well-tested code
- **Flexible Transition**: Gradual migration from Either to Validated
- **Maintainable Pipeline**: Clean syntax with pipe operators preserved
- **LSP**: when we get to the LSP, this will allow the compiler to provide info for
  correct members, even in the presence of errors.
