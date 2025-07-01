# Error Accumulation and Error Recovery Nodes

## Overview

This document describes the implementation of error accumulation in the semantic analysis pipeline and the introduction of error recovery AST nodes (`InvalidExpression` and `InvalidType`) to enable partial compilation for better LSP support.

## Current State

As of the implementation completed earlier:
- Semantic phases use `SemanticPhaseState` to accumulate errors while continuing processing
- All phases thread state through, collecting errors without stopping
- External API remains unchanged (`Either[List[SemanticError], Module]`)

## Proposed Enhancement: Error Recovery Nodes

### Motivation

When semantic analysis encounters errors (undefined references, undefined types), we currently:
1. Report the error
2. Keep the original, unmodified AST node

This approach has limitations for LSP:
- The AST contains nodes that are known to be invalid
- Later phases may fail when encountering these invalid nodes
- Partial type information is lost

### Solution: Invalid Node Hierarchy

#### 1. New AST Node Types

```scala
// In AstNode.scala
sealed trait InvalidNode extends AstNode

case class InvalidExpression(
  span: SrcSpan,
  originalExpr: Expr,
  typeSpec: Option[TypeSpec] = None,
  typeAsc: Option[TypeSpec] = None
) extends Term, InvalidNode

case class InvalidType(
  span: SrcSpan,
  originalType: TypeSpec
) extends TypeSpec, InvalidNode

case class DuplicateMember(
  span: SrcSpan,
  originalMember: Member,
  firstOccurrence: Member
) extends Member, InvalidNode

case class InvalidMember(
  span: SrcSpan,
  originalMember: Member,
  reason: String
) extends Member, InvalidNode
```

The `InvalidNode` trait allows:
- Easy filtering of error nodes from the AST
- Type-safe handling of invalid constructs
- Preservation of original nodes for debugging

#### 2. Phase Tracking in Errors

All `SemanticError` cases will include a phase field:

```scala
enum SemanticError:
  case UndefinedRef(ref: Ref, member: Member, phase: String)
  case UndefinedTypeRef(typeRef: TypeRef, member: Member, phase: String)
  case DuplicateName(name: String, duplicates: List[Resolvable], phase: String)
  case InvalidExpression(expr: Expr, message: String, phase: String)
  case DanglingTerms(terms: List[Term], message: String, phase: String)
  case MemberErrorFound(error: MemberError, phase: String)
  case InvalidExpressionFound(invalidExpr: InvalidExpression, phase: String)
```

Phase names use FQCN:
- `mml.mmlclib.semantic.DuplicateNameChecker`
- `mml.mmlclib.semantic.RefResolver`
- `mml.mmlclib.semantic.TypeResolver`
- `mml.mmlclib.semantic.ExpressionRewriter`
- `mml.mmlclib.semantic.MemberErrorChecker`
- `mml.mmlclib.semantic.Simplifier`

### Implementation Details

#### Critical Design Principle: Preserve Referenceable Members

**Important**: When a member (function, operator, type) has an invalid body or expression, we must:
1. Keep the member declaration intact with its name
2. Only replace the invalid part (body expression or type specification)
3. This ensures the member remains referenceable by other code

For example:
- A function with an invalid body still exists as a function that can be called
- A type alias with an invalid target type still exists as a named type
- An operator with an invalid implementation still exists as an operator

This is crucial for LSP functionality where:
- Go-to-definition should work even for members with invalid implementations
- Autocomplete should suggest these members
- Type checking can proceed partially even with invalid definitions

#### RefResolver Changes

When encountering undefined references:
```scala
case ref: Ref =>
  val candidates = lookupRefs(ref, member, module)
  if candidates.isEmpty then 
    // Create InvalidExpression wrapping the undefined ref
    val invalidExpr = InvalidExpression(
      ref.span,
      Expr(ref.span, List(ref)),
      ref.typeSpec,
      ref.typeAsc
    )
    invalidExpr.asRight
  else 
    ref.copy(candidates = candidates).asRight
```

The error is still reported via `SemanticPhaseState.addError`, but the AST continues with an `InvalidExpression`.

**Note**: In `rewriteModule`, when updating members with invalid expressions:
```scala
// Preserve the member structure, only replace the body
case bnd: Bnd => 
  bnd.copy(value = Expr(invalidExpr.span, List(invalidExpr)))
case fn: FnDef => 
  fn.copy(body = Expr(invalidExpr.span, List(invalidExpr)))
// The member name, parameters, visibility, etc. remain intact
```

#### TypeResolver Changes

Similar approach for undefined types:
```scala
case typeRef: TypeRef =>
  val candidates = lookupTypeRefs(typeRef, module)
  candidates match
    case Nil => 
      // Return InvalidType instead of failing
      InvalidType(typeRef.span, typeRef).asRight
    case single :: Nil => 
      typeRef.copy(resolvedAs = Some(single)).asRight
    case multiple =>
      // Also use InvalidType for ambiguous references
      InvalidType(typeRef.span, typeRef).asRight
```

**Critical**: For type aliases with invalid targets:
```scala
case alias: TypeAlias =>
  resolveTypeSpec(alias.typeRef, member, module).map { resolvedType =>
    // Even if resolvedType is InvalidType, the alias remains valid as a named type
    alias.copy(typeRef = resolvedType)
  }
```

This ensures:
- `type MyType = UnknownType` creates a valid `MyType` that can be referenced
- Other code can use `MyType` even though its definition is invalid
- The type system can partially reason about `MyType`

#### DuplicateNameChecker Changes

When encountering duplicate members:
```scala
case duplicates if duplicates.length > 1 =>
  val (first :: rest) = duplicates
  // Keep the first occurrence as-is (referenceable)
  val validMembers = List(first)
  
  // Convert all subsequent duplicates to DuplicateMember nodes
  val invalidMembers = rest.map { duplicate =>
    DuplicateMember(
      duplicate.span,
      duplicate,
      first
    )
  }
  
  validMembers ++ invalidMembers
```

For duplicate parameter names within functions/operators:
```scala
case fn: FnDef if hasDuplicateParams(fn.params) =>
  // Convert the entire function to InvalidMember
  InvalidMember(
    fn.span,
    fn,
    s"Duplicate parameter names in function ${fn.name}"
  )
  
case op: OpDef if hasDuplicateParams(op.params) =>
  // Convert the entire operator to InvalidMember
  InvalidMember(
    op.span,
    op,
    s"Duplicate parameter names in operator ${op.name}"
  )
```

This approach ensures:
- The first occurrence of a duplicate member remains referenceable
- Subsequent duplicates are marked but not referenceable
- Functions/operators with duplicate parameters become entirely invalid

#### ExpressionRewriter Detection

ExpressionRewriter will detect and report invalid expressions from earlier phases:
```scala
case inv: InvalidExpression =>
  NEL.one(SemanticError.InvalidExpressionFound(
    inv, 
    "mml.mmlclib.semantic.ExpressionRewriter"
  )).asLeft
```

### Benefits

1. **LSP Support**: Partial ASTs remain available even with errors
2. **Error Isolation**: Invalid nodes are clearly marked and can be filtered
3. **Debugging**: Original failed nodes are preserved within Invalid wrappers
4. **Type Safety**: Invalid nodes properly extend Term/TypeSpec hierarchies
5. **Error Tracking**: Phase information helps debug error origins

### Trade-offs

1. **AST Complexity**: Additional node types to handle
2. **Pretty Printing**: Need to handle Invalid nodes in all printers
3. **Downstream Phases**: Must be aware of Invalid nodes

### Future Considerations

1. **Error Recovery**: Invalid nodes could attempt partial type inference
2. **IDE Features**: Use Invalid nodes to provide better error tooltips
3. **Incremental Compilation**: Invalid nodes mark boundaries for recompilation
4. **Error Nodes for Other Constructs**: Could extend to InvalidMember, InvalidPattern, etc.

## Implementation Status

- [x] Error accumulation via SemanticPhaseState
- [ ] InvalidNode trait and implementations
- [ ] DuplicateNameChecker error recovery
- [ ] RefResolver error recovery
- [ ] TypeResolver error recovery
- [ ] ExpressionRewriter detection
- [ ] Phase tracking in all errors
- [ ] Pretty printer updates
- [ ] Error printer updates

## Related Documents

- `error-accumulation.md`: Original error accumulation strategy
- `pipeline.md`: Semantic pipeline architecture