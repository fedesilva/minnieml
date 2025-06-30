# Type Ref and Resolver Design

## Overview

We need a TypeResolver phase similar to RefResolver but for types. 
This will resolve TypeRef nodes to their corresponding TypeDef or TypeAlias declarations.

## Current State

The type system is in flux. 
Current type-related AST nodes are drafts that need refinement as we implement more complex type resolution.

## Type Declarations

### TypeDef - Creates new type identity
- `@native` types (currently implemented but parsed as TypeAlias - needs fixing)
- `enum` types (future)
- `data` types (future)

Examples:
```
type Int = @native[i64]     # Should be TypeDef, not alias (done, validate)
enum Maybe 'T = One 'T | None   # Future
data Person { name: String }    # Future
```

### TypeAlias - Alternative name for existing type expression
- Can alias nominal types: `type Name = String`
- Can alias structural types: `type Named = { name: String }`
- Can alias constrained types: `type PosNum = Num & Positive`
- Can alias compound types: `type FullAccess = Readable & Writable`

Key point: TypeAlias is NOT structural typing - it's just a name for any type expression.

## TypeRef Resolution

TypeRefs appear everywhere and need resolution:
- Inside TypeAlias definitions
- In function parameters and return types
- In binding type annotations
- In expression type ascriptions
- Eventually in Union/Intersection (after refactoring)

## Implementation Plan

### Parser Changes (done)
- Split `type` keyword handling:
  - `type X = @native[...]` → creates TypeDef
  - `type X = <typespec>` → creates TypeAlias

### TypeResolver Implementation
- Follow RefResolver pattern:
  - Entry point: `rewriteModule`
  - Process all members looking for TypeSpecs
  - For each TypeRef found, lookup matching TypeDef/TypeAlias
  - Store candidates (for future overloading support)
  - Report `UndefinedTypeRef` errors

## What We're NOT Doing Yet

- No scoped type resolution (no type parameters, no imports)
- No complex type resolution (can't resolve `A & B` yet)
- No Union/Intersection implementation (just fixing signatures)
- No type checking or compatibility

## Key Insight

The resolver just connects names to declarations. Like RefResolver, it collects all candidates but doesn't disambiguate - that's for the type checker later.

## ResolvableType

`type ResolvableType = TypeAlias | TypeDef`

Only these can be targets of TypeRef resolution. TypeSpecs are shapes/constraints, not resolvable entities.
