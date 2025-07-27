# Lambdify: Converting Functions to Lambda Bindings

## Overview

A semantic transformation that rewrites function and operator definitions to lambda bindings before the type checker.

## Motivation

The compiler currently handles three different constructs: `FnDef`, `OpDef`, and `Bnd`. By converting functions and operators to lambda bindings, we get:
- Unified internal representation (everything is bindings + lambdas)
- Simpler type checker (only handles bindings and lambdas)
- Closer to lambda calculus

## Pipeline Position

```
Parser → DuplicateNameChecker → RefResolver → TypeResolver → ExpressionRewriter → **Lambdify** → TypeChecker
```

## Transformation

```rust
# Surface syntax (unchanged)
fn sum(a: Int, b: Int): Int = a + b;
op +(a: Int, b: Int): Int = native_add a b;

# Internal representation after Lambdify
let sum = (a: Int, b: Int) -> a + b;
let + = (a: Int, b: Int) -> native_add a b;
```

## AST Changes

### Add Lambda Node
```scala
case class Lambda(
  span: SrcSpan,
  params: List[FnParam],
  body: Expr,
  typeSpec: Option[TypeSpec] = None,
  typeAsc: Option[TypeSpec] = None
) extends Term with Typeable
```

### Preserve Original Syntax
For error reporting and debugging, keep reference to original:
```scala
case class Bnd(
  // ... existing fields ...
  syntax: Option[Member] = None  // NEW: preserve FnDef/OpDef for errors
)
```

## Transformation Details

### Functions
```scala
// From:
FnDef(span, name, params, body, typeAsc, typeSpec)

// To:
Bnd(span, name, 
  value = Lambda(span, params, body),
  typeAsc = typeAsc,
  typeSpec = typeSpec,
  syntax = Some(originalFnDef)
)
```

### Operators
```scala
// From:
BinOpDef(span, name, params, body, typeAsc, typeSpec, precedence, associativity)

// To:
Bnd(span, name,
  value = Lambda(span, params, body),
  typeAsc = typeAsc,
  typeSpec = typeSpec,
  syntax = Some(originalOpDef)  // preserves precedence/associativity
)
```

### Native Functions
```rust
fn print(s: String): () = @native;
```

Transforms to:
```scala
Bnd("print", Lambda(params, NativeImpl(span)))
```

Native functions work exactly as before:
- Type annotations remain mandatory (can't infer external types)
- TypeChecker validates the declared types but treats body as opaque
- Codegen generates forward declarations from binding name + types
- Linker links to actual implementation in runtime library

## Benefits

1. **TypeChecker Simplification**: Only needs to handle Lambda and Bnd, not FnDef/OpDef
2. **Uniform Representation**: Functions are just values bound to names
3. **Future Features**: Enables partial application, higher-order functions, closures

## Implementation Notes


- Error messages should use `syntax` to show familiar function syntax
- Operator metadata (precedence/associativity) preserved in `syntax`

## Open Questions

1. Should we handle currying now or later?
2. Impact on code generation - lambdas vs direct functions?