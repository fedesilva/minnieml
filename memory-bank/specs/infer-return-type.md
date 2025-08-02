# Return Type Inference for Functions and Operators

## Problem Statement

Currently, MML requires explicit return type annotations for functions and operators, but not for let bindings:

```mml
# This works - type inferred from expression
let result = 5 + 3;

# This is required - explicit return type
fn add(a: Int, b: Int): Int = a + b;
op +(a: Int, b: Int): Int = native_add a b;
```

Since a function body is an expression (just like a let binding's value), and we can already infer let binding types from expressions, we should be able to infer function return types the same way.

## Current Architecture

The MML compiler uses a multi-phase semantic analysis pipeline:

1. **DuplicateNameChecker** - Check for duplicate definitions  
2. **RefResolver** - Collect candidate definitions for references
3. **TypeResolver** - Resolve type references to type definitions
4. **ExpressionRewriter** - Unified precedence-based expression restructuring  
5. **MemberErrorChecker** - Report parser errors
6. **TypeChecker** - Type checking and inference ← **Target for changes**
7. **Simplifier** - Final AST simplification

The **TypeChecker** already implements type inference for let bindings by:
- Checking the value expression to compute its type
- Setting the binding's `typeSpec` to the value's inferred type

This same mechanism should extend to functions and operators.

## Proposed Solution

### Phase 1: Parser Changes

**No changes needed** - the parser already handles optional type ascriptions through `typeAscP(source)` returning `Option[TypeSpec]`. The issue is that the TypeChecker currently rejects `None` values.

### Phase 2: TypeChecker Changes

Modify the **TypeChecker** to infer return types instead of requiring them.

**Current behavior** in `validateMandatoryAscriptions`:
```scala
case fnDef: FnDef =>
  val returnError = fnDef.typeAsc match
    case None => List(TypeError.MissingReturnType(fnDef, phaseName))  // ERROR!
    case Some(_) => Nil
```

**New behavior** - Apply the same inference logic used for let bindings:

```scala
case fnDef: FnDef =>
  fnDef.typeAsc match
    case Some(_) => 
      // Explicit return type provided - use existing validation logic
      validateExplicitReturnType(fnDef)
    case None =>
      // No return type - infer from body expression (like let bindings)
      inferReturnTypeFromBody(fnDef)
```

Do this for FnDef and OpDef (both binary and unary)

**Implementation approach:**

1. **Remove mandatory return type validation** for functions and operators
2. **Add inference logic** similar to let binding handling:
   - For functions with `typeAsc = None`: infer return type from body expression
   - For functions with explicit `typeAsc`: validate as before
3. **Handle inference failures** with clear error messages

### Phase 3: New Error Types

Add new error cases for inference failures:

```scala
enum TypeError:
  // ... existing cases ...
  case CannotInferReturnType(member: Member, reason: String, phaseName: String)
  case RecursiveReturnTypeInference(members: List[Member], phaseName: String)
```

## Implementation Details

### Parser Changes

**No changes required** - `typeAscP(source)` already returns `Option[TypeSpec]`, supporting optional return types.

### TypeChecker Changes

**1. Remove mandatory return type validation:**

```scala
private def validateMandatoryAscriptions(member: Member): List[TypeError] = member match
  case fnDef: FnDef =>
    val paramErrors = fnDef.params.collect {
      case param if param.typeAsc.isEmpty =>
        TypeError.MissingParameterType(param, fnDef, phaseName)
    }
    // Remove: returnError = fnDef.typeAsc match { case None => ... }
    paramErrors // Only validate parameters, not return type

  case opDef: BinOpDef =>
    // Similar changes - remove return type validation
    validateParameters(opDef) // Only validate parameters
    
  case opDef: UnaryOpDef =>
    // Similar changes - remove return type validation  
    validateParameters(opDef) // Only validate parameters
```

**2. Add return type inference in `lowerAscriptionsToSpecs`:**

```scala
case fnDef: FnDef =>
  val inferredReturnType = fnDef.typeAsc.orElse {
    // Infer from body expression - will be computed during type checking
    None  // Leave as None for now, infer during checkMember
  }
  val updatedParams = fnDef.params.map(p => p.copy(typeSpec = p.typeAsc))
  val updatedFn = fnDef.copy(typeSpec = inferredReturnType, params = updatedParams)
```

**3. Add inference logic in `checkMember`:**

```scala
case fnDef: FnDef =>
  val paramContext = fnDef.params.map(p => p.name -> p).toMap
  for
    checkedBody <- checkExprWithContext(fnDef.body, module, paramContext, fnDef.typeSpec)
    inferredType <- fnDef.typeSpec match
      case Some(explicitType) => 
        // Explicit return type - validate as before
        Right(explicitType)
      case None =>
        // Infer return type from body (like let bindings)
        checkedBody.typeSpec match
          case Some(bodyType) => Right(bodyType)
          case None => Left(List(TypeError.CannotInferReturnType(fnDef, "body has no type", phaseName)))
    // Validate inferred/explicit type matches body
    _ <- (Some(inferredType), checkedBody.typeSpec) match
      case (Some(expected), Some(actual)) if areTypesCompatible(expected, actual, module) =>
        Right(())
      case (Some(expected), Some(actual)) =>
        Left(List(TypeError.TypeMismatch(fnDef, expected, actual, phaseName)))
      case _ => Right(())
  yield fnDef.copy(body = checkedBody, typeSpec = Some(inferredType))
```

## Examples

### Before (Current - Explicit Required)
```mml
fn add(a: Int, b: Int): Int = a + b;
fn concat(a: String, b: String): String = a + b;  
op !(n: Int): Int = if n <= 1 then 1 else n * (n - 1)!;
```

### After (Proposed - Inference Supported)
```mml
# Inferred return types
fn add(a: Int, b: Int) = a + b;           # Inferred: Int
fn concat(a: String, b: String) = a + b;  # Inferred: String
op !(n: Int) = if n <= 1 then 1 else n * (n - 1)!;  # Inferred: Int

# Explicit return types still supported  
fn add(a: Int, b: Int): Int = a + b;      # Explicit: Int
fn divide(a: Int, b: Int): Float = to_float(a) / to_float(b);  # Explicit: Float
```

### Complex Examples
```mml
# Conditional expressions
fn max(a: Int, b: Int) = if a > b then a else b;  # Inferred: Int

# Function applications  
fn double(x: Int) = x * 2;
fn quadruple(x: Int) = double(double(x));         # Inferred: Int

# Native functions (explicit type still required)
fn println(s: String): () = @native;             # Cannot infer @native
```

## Edge Cases and Error Handling

### 1. Recursive Functions
```mml
# Simple recursion - works
fn factorial(n: Int) = if n <= 1 then 1 else n * factorial(n - 1);

# Mutual recursion - may need multiple passes or dependency analysis
fn isEven(n: Int) = if n == 0 then true else isOdd(n - 1);
fn isOdd(n: Int) = if n == 0 then false else isEven(n - 1);
```

**Handling:** The current TypeChecker processes members sequentially. For mutual recursion, we may need:
- Multiple inference passes
- Dependency graph analysis  
- Or require explicit types for mutually recursive functions

### 2. Native Functions
```mml
fn native_add(a: Int, b: Int) = @native;  # ERROR: Cannot infer @native body
```

**Handling:** Native functions (`@native` bodies) cannot be type-inferred and should require explicit return types.

### 3. Unresolvable Bodies
```mml
fn broken(x: Int) = some_undefined_function(x);  # ERROR: Cannot resolve body type
```

**Handling:** Generate clear error messages when body expressions cannot be typed.

### 4. Complex Expressions
```mml
fn complex(x: Int) = 
  let temp = x * 2;
  if temp > 10 
  then temp + 5
  else temp - 3;  # Inferred: Int (both branches are Int)
```

**Handling:** Leverage existing expression type checking - if the body expression can be typed, the return type can be inferred.

## Backward Compatibility

**Guaranteed:** All existing code with explicit return types continues to work unchanged.

```mml
# This continues to work exactly as before
fn add(a: Int, b: Int): Int = a + b;
op +(a: Int, b: Int): Int = native_add a b;
```

**New capability:** Functions can now omit return types and have them inferred.

```mml  
# This becomes possible
fn add(a: Int, b: Int) = a + b;
op +(a: Int, b: Int) = native_add a b;
```

## Testing Strategy

### 1. Basic Inference
- Simple arithmetic functions: `fn add(a: Int, b: Int) = a + b`
- String operations: `fn concat(s1: String, s2: String) = s1 + s2`  
- Boolean operations: `fn and(a: Bool, b: Bool) = a && b`

### 2. Mixed Explicit/Inferred
- Some functions with explicit types, others inferred
- Ensure no interaction issues

### 3. Complex Expressions
- Conditional expressions: `fn max(a: Int, b: Int) = if a > b then a else b`
- Function applications: `fn compose(x: Int) = f(g(x))`
- Let bindings within function bodies

### 4. Error Cases
- `@native` functions without explicit types → should error
- Unresolvable body expressions → should error with clear message
- Type mismatches between explicit and inferred → should error

### 5. Recursive Functions  
- Simple recursion: factorial, Fibonacci
- Mutual recursion: isEven/isOdd (may require explicit types initially)

### 6. Backward Compatibility
- All existing tests with explicit return types continue to pass
- No regressions in type checking behavior

## Implementation Plan

### Block 1: TypeChecker Core Changes
- Remove mandatory return type validation from `validateMandatoryAscriptions`
- Add inference logic to `checkMember` for functions and operators
- Add new error types for inference failures

### Block 2: Edge Case Handling
- Handle `@native` functions (require explicit types)
- Improve error messages for inference failures
- Test complex expression scenarios

### Block 3: Recursive Function Support
- Analyze current behavior with recursive functions
- Implement dependency analysis if needed
- Handle mutual recursion cases

### Block 4: Integration & Testing
- Comprehensive test suite covering all scenarios
- Verify backward compatibility (all existing tests pass)
- Performance testing with large codebases

## Benefits

1. **Consistency:** Functions and let bindings now have the same inference behavior
2. **Ergonomics:** Less boilerplate for simple functions  
3. **Type Safety:** Still maintains full type checking, just with inference
4. **Backward Compatible:** All existing code continues to work
5. **Gradual Adoption:** Teams can adopt inference incrementally

This change makes MML more ergonomic while preserving its type safety guarantees and maintaining complete backward compatibility with existing codebases.
