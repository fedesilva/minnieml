# 133-type-checker-current-issues

## Current Status (2025-07-06)

### ✅ Fixed Issues

1. **RefResolver** - Now properly sets `resolvedAs` for single candidates
2. **TypeChecker** - Now handles FnParam references correctly  
3. **Operator Arity** - TypeChecker correctly uses resolved operator variant
4. **Injected Operators** - Now only set `typeAsc`, not `typeSpec`
5. **Pipeline Order** - TypeResolver now runs before RefResolver

### ❌ Remaining Issues

#### Issue 1: Operator Type Validation Logic (NEW)

The TypeChecker is comparing incompatible type categories when validating operators:

```scala
// In TypeChecker.scala, lines 75-78
checkedBody <- checkExpr(opDef.body, module, opDef.typeAsc)
_ <- (opDef.typeAsc, checkedBody.typeSpec) match
  case (Some(expected), Some(actual)) if areTypesCompatible(expected, actual, module) => Right(())
  case (Some(expected), Some(actual)) => Left(List(TypeError.TypeMismatch(opDef, expected, actual, phaseName)))
```

**Problem**: 
- `opDef.typeAsc` is a `TypeFn` (the complete function signature)
- Should be comparing just the return types
- Currently comparing `TypeFn(List(Int, Int), Int)` vs `Int`

**Fix**: Extract return type from the operator's TypeFn before comparison

#### Issue 2: Recursive Type Alias Resolution

TypeResolver only resolves one level of type aliases:
- `Int` → `Int64` ✓
- But `Int64` → `i64` ✗

**Required**: 
- Recursive resolution stopping at TypeDef
- Walk back assigning typeSpec from the TypeDef

## Test Case

```mml
op ++ (a: Int b: Int): Int = a + b;
op ++ (a: Int): Int = a + 1;
let a = 1 ++ 2;
let b = ++1;
```

Run: `sbt "mmlclib/testOnly mml.mmlclib.semantic.OpPrecedenceTests"`