# Simple Type Checker Implementation Specification (#133)

## Overview

This document specifies the implementation of the Simple Type Checker phase to unblock the codegen update (#156). The type checker will compute `typeSpec` for all `Typeable` nodes using simple forward type propagation and validate against user-provided `typeAsc` where present.

## Design Philosophy

The type checker follows a "leaves-up" propagation algorithm:
1. **Type Anchors**: Functions and operators provide fully-annotated type signatures
2. **Known Leaves**: Literals and resolved function references have known types
3. **Forward Propagation**: Types flow from leaves up through expressions
4. **Simple Inference**: Let bindings and expressions infer types from their components
5. **Validation**: Optional type ascriptions are validated against inferred types

## Key Design Decisions

### 1. Literals Use TypeRef Instead of LiteralType

**Problem**: Current literals use special `LiteralType` hierarchy, requiring special handling.

**Current State in `AstNode.scala`:**
```scala
case class LiteralInt(span: SrcSpan, value: Int) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(LiteralIntType(span))
  final val typeAsc:  Option[TypeSpec] = None

sealed trait LiteralType extends TypeSpec
case class LiteralIntType(span: SrcSpan) extends LiteralType
case class LiteralStringType(span: SrcSpan) extends LiteralType
// etc.
```

**Proposed Change in `AstNode.scala`:**
```scala
case class LiteralInt(span: SrcSpan, value: Int) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(TypeRef(span, "Int"))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralString(span: SrcSpan, value: String) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(TypeRef(span, "String"))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralBool(span: SrcSpan, value: Boolean) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(TypeRef(span, "Bool"))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralUnit(span: SrcSpan) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(TypeRef(span, "Unit"))
  final val typeAsc:  Option[TypeSpec] = None

case class LiteralFloat(span: SrcSpan, value: Float) extends LiteralValue:
  final val typeSpec: Option[TypeSpec] = Some(TypeRef(span, "Float"))
  final val typeAsc:  Option[TypeSpec] = None

// DELETE THESE - no longer needed:
// sealed trait LiteralType extends TypeSpec
// case class LiteralIntType(span: SrcSpan) extends LiteralType
// case class LiteralStringType(span: SrcSpan) extends LiteralType
// case class LiteralBoolType(span: SrcSpan) extends LiteralType
// case class LiteralUnitType(span: SrcSpan) extends LiteralType
// case class LiteralFloatType(span: SrcSpan) extends LiteralType
```

**Benefits:**
- Unified type system: everything uses `TypeRef`
- No special handling for literal types in TypeChecker
- Consistency with how all other types are represented

### 2. TypeChecker Runs After ExpressionRewriter

**Problem**: Need to handle both operators and function applications, and ensure that type information is available as it is computed.

**Proposed Pipeline:**
```scala
initialState
  |> DuplicateNameChecker.rewriteModule
  |> RefResolver.rewriteModule
  |> TypeResolver.rewriteModule
  |> ExpressionRewriter.rewriteModule  // Converts ops and fn calls to Apps, creates App chains
  |> TypeChecker.rewriteModule         // NEW PHASE HERE
  |> MemberErrorChecker.checkModule
  |> Simplifier.rewriteModule
```

**Implementation Note:** The `TypeChecker` must be implemented as a state-threading pass using `foldLeft` over the module members. This ensures that each member being checked has access to the updated, type-annotated versions of the members that came before it in the file, preventing stale reference issues.

**Benefits:**
- TypeChecker only sees unified function applications.
- No special handling for `BinOpDef`/`UnaryOpDef` needed.
- Operators are already rewritten as `App(App(Ref("+"), a), b)`.
- Type information is correctly propagated between members.

### 3. Mixed Mandatory/Optional Type Ascriptions

**Mandatory Ascriptions (Type Anchors):**
- All `FnDef` MUST have explicit return type: `fn foo(x: Int): String = ...`
- All `FnParam` MUST have explicit type: `fn foo(x: Int, y: Bool): String = ...`
- All `OpDef` MUST have explicit types: `op +(a: Int b: Int): Int = ...`

**Function Saturation Requirement:**
- All function calls must be fully saturated (exact argument count)
- No partial applications allowed (would create new functions with remaining parameters)
- No over-saturation allowed (too many arguments)

**Optional Ascriptions (With Inference):**
- `Bnd` (let bindings): `let x = 42` (inferred) vs `let x: Int = 42` (validated)
- Expressions and terms: Type ascriptions are validation, not requirements

**Error Cases:**
```rust
// Missing type annotations:
fn foo(x) = x;           // Missing param type
fn foo(x: Int) = x;      // Missing return type
op +(a b) = a + b;       // Missing param types AND return type

// Function saturation errors:
fn sum(a: Int b: Int): Int = a + b;
let x = sum 2;           // ERROR: under-saturated (1 of 2 args)
let y = sum 2 3 4;       // ERROR: over-saturated (3 args for 2 params)
let z = sum 2 3;         // OK: fully saturated (exactly 2 args)

// Type inference and validation:
let x = 42;              // x: Int (inferred from literal)
let y: Int = 42;         // y: Int (validated)
let z: String = 42;      // ERROR: type mismatch
```

## Design Decisions - Resolved

### Conditional Expressions
Both branches of `if cond then a else b` must have the same type. Simple type checking - no union types.

### Hole (`???`) Typing
- A `Hole` term (`???`) inherits its type from the surrounding context.
- If a `Hole` is found in a function body, its `typeSpec` will be set to the function's return type.
- If a `Hole` is found in a `let` binding without a type ascription, it is a `TypeError`.

### Type Alias Compatibility
Follow alias chains to find the concrete type:
- Could be several layers: `MyInt -> Int -> Int64 -> @native:i64`
- Write back the resolved `typeSpec` along the entire alias chain
- Compare concrete types for compatibility

### Native Functions
Validate like any other function. `fn print(s: String): () = @native` is checked against its signature - we just can't verify the body implementation.

### Unit Type Handling  
Keep existing `TypeUnit` nodes. There's no `TypeDef` for Unit to point a `TypeRef` to.

### Error Recovery Strategy
- Generate error and store it in state
- Leave `typeSpec` empty for failed nodes
- Continue processing if possible
- Stop processing dependent terms that need the failed type
- Accumulate as many errors as possible

## Type Inference Rules

### Known Types (Leaves)
```rust
// Literals: immediate known types
42          → typeSpec = TypeRef("Int")
"hello"     → typeSpec = TypeRef("String")
true        → typeSpec = TypeRef("Bool")
()          → typeSpec = TypeRef("Unit")

// Function references: from mandatory ascriptions
fn double(x: Int): Int = x * 2;
double      → typeSpec = TypeFn([TypeRef("Int")], TypeRef("Int"))

// Resolved references: from resolvedAs member
let x = 42;
x           → typeSpec = x.resolvedAs.typeSpec  // Int
```

### Type Propagation (Forward Flow)
```rust
// Function applications: use function's return type
double 42   → typeSpec = TypeRef("Int")  // from double's return type

// Expression chains: propagate through applications  
5 + 3       → typeSpec = TypeRef("Int")  // + returns Int
double (5 + 3) → typeSpec = TypeRef("Int")  // double returns Int

// Let bindings: infer from value
let result = double 42;  → result.typeSpec = TypeRef("Int")

// Expressions: from final term in application chain
Expr(List(double_ref, arg)) → typeSpec = double_ref.returnType
```

### Type Validation
```rust
// When typeAsc is present, must match inferred typeSpec
let x: Int = 42;         // OK: TypeRef("Int") = TypeRef("Int")
let y: String = 42;      // ERROR: TypeRef("String") ≠ TypeRef("Int")
let z = 42;              // OK: no ascription, use inferred TypeRef("Int")
```

## Implementation Plan

### Phase 1: Update AST Literals

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/AstNode.scala`

1. Change all literal case classes to use `TypeRef` instead of `LiteralType`
2. Remove the entire `LiteralType` hierarchy  
3. Search codebase for any pattern matching on `LiteralType` and update

**Impact Analysis:**
- Check `TypeResolver.scala` for `LiteralType` handling
- Check test files for `LiteralType` assertions
- Check pretty printing code

### Phase 2: Add TypeChecker Error Types

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala`

A new `TypeError` enum will be created to encapsulate all type-related errors. `SemanticError` will then have a case to wrap `TypeError`.

```scala
enum TypeError extends CompilationError:
  // Function and Operator Definition Errors
  case MissingParameterType(param: FnParam, fnDef: FnDef, phase: String)
  case MissingReturnType(fnDef: FnDef, phase: String)
  case MissingOperatorParameterType(param: FnParam, opDef: OpDef, phase: String)
  case MissingOperatorReturnType(opDef: OpDef, phase: String)

  // Expression and Application Errors
  case TypeMismatch(node: Typeable, expected: TypeSpec, actual: TypeSpec, phase: String)
  case UndersaturatedApplication(app: App, expectedArgs: Int, actualArgs: Int, phase: String)
  case OversaturatedApplication(app: App, expectedArgs: Int, actualArgs: Int, phase: String)
  case InvalidApplication(app: App, fnType: TypeSpec, argType: TypeSpec, phase: String)

  // Conditional Errors
  case ConditionalBranchTypeMismatch(cond: Cond, trueType: TypeSpec, falseType: TypeSpec, phase: String)
  case ConditionalBranchTypeUnknown(cond: Cond, phase: String)

  // General Type Errors
  case UnresolvableType(typeRef: TypeRef, node: Typeable, phase: String)
  case IncompatibleTypes(node: AstNode, type1: TypeSpec, type2: TypeSpec, context: String, phase: String)
  case UntypedHoleInBinding(bnd: Bnd, phase: String)

enum SemanticError extends CompilationError:
  // ... existing errors ...
  case TypeCheckingError(error: TypeError)
```

### Phase 3: Implement TypeChecker Core

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala`

```scala
object TypeChecker:
  private val phaseName = "mml.mmlclib.semantic.TypeChecker"

  /** Main entry point - process module and accumulate errors */
  def rewriteModule(state: SemanticPhaseState): SemanticPhaseState

  /** Validate and compute types for a member */
  private def checkMember(member: Member, module: Module): Either[List[SemanticError], Member]
  
  /** Validate mandatory ascriptions for functions/operators */
  private def validateMandatoryAscriptions(member: Member): List[SemanticError]
  
  /** Compute typeSpec for functions from their ascriptions */
  private def computeFunctionType(fnDef: FnDef): Either[List[SemanticError], TypeSpec]
  private def computeOperatorType(opDef: OpDef): Either[List[SemanticError], TypeSpec]
  
  /** Type check expressions using forward propagation */
  private def checkExpr(expr: Expr, module: Module): Either[List[SemanticError], Expr]
  
  /** Type check individual terms */
  private def checkTerm(term: Term, module: Module): Either[List[SemanticError], Term]
  
  /** Check function applications */
  private def checkApplication(app: App, module: Module): Either[List[SemanticError], App]
  
  /** Validate type ascription against computed type */
  private def validateTypeAscription(node: Typeable): List[SemanticError]
  
  /** Check type compatibility (handles aliases, etc.) */
  private def areTypesCompatible(t1: TypeSpec, t2: TypeSpec, module: Module): Boolean
  
  /** Follow alias chain to concrete type and update typeSpec along the way */
  private def resolveAliasChain(typeSpec: TypeSpec, module: Module): TypeSpec
  
  /** Check conditional expressions (both branches must match) */
  private def checkConditional(cond: Cond, module: Module): Either[List[SemanticError], Cond]
  
  /** Extract return type from function type */
  private def getReturnType(fnType: TypeSpec): Option[TypeSpec]
  
  /** Extract parameter types from function type */
  private def getParameterTypes(fnType: TypeSpec): List[TypeSpec]
```

### Phase 4: Update Pipeline

**File:** `modules/mmlc-lib/src/main/scala/mml/mmlclib/api/SemanticApi.scala`

Add `TypeChecker.rewriteModule` after `ExpressionRewriter.rewriteModule` in the pipeline.

### Phase 5: Add Comprehensive Tests

**File:** `modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/TypeCheckerTests.scala`

Test categories:
- **Mandatory ascriptions**: Missing types should error
- **Type inference**: Let bindings should infer from values
- **Type propagation**: Applications should use return types
- **Type validation**: Ascriptions should match inferred types
- **Error accumulation**: Multiple errors should be collected

## TypeChecker Algorithm Details

### 1. Validate Mandatory Ascriptions

```scala
def validateMandatoryAscriptions(member: Member): List[SemanticError] =
  member match
    case fnDef: FnDef =>
      val paramErrors = fnDef.params.collect {
        case param if param.typeAsc.isEmpty => 
          MissingParameterType(param, fnDef.name, phaseName)
      }
      val returnError = if fnDef.typeAsc.isEmpty then
        List(MissingReturnType(fnDef.name, phaseName))
      else Nil
      paramErrors ++ returnError
      
    case opDef: OpDef => // Similar validation for operators
    case _ => Nil
```

### 2. Compute Function Types

```scala
def computeFunctionType(fnDef: FnDef): Either[List[SemanticError], TypeSpec] =
  // Extract parameter types from ascriptions
  val paramTypes = fnDef.params.map(_.typeAsc.get) // Safe after validation
  val returnType = fnDef.typeAsc.get // Safe after validation
  
  Right(TypeFn(fnDef.span, paramTypes, returnType))
```

### 3. Type Check Expressions (Forward Propagation)

```scala
def checkExpr(expr: Expr, module: Module): Either[List[SemanticError], Expr] =
  expr.terms match
    case List(single) =>
      // Single term: inherit its type
      checkTerm(single, module).map { checkedTerm =>
        expr.copy(terms = List(checkedTerm), typeSpec = checkedTerm.typeSpec)
      }
      
    case multiple =>
      // Multiple terms: application chain
      checkApplicationChain(multiple, module).map { (checkedTerms, resultType) =>
        expr.copy(terms = checkedTerms, typeSpec = Some(resultType))
      }
```

### 4. Type Check Applications and Function Saturation

```scala
def checkApplication(app: App, module: Module): Either[List[SemanticError], App] =
  // First, determine if this is the root of an application chain
  val (rootFn, argCount) = countApplicationChain(app)
  
  for
    checkedChain <- checkApplicationChain(app, module)
    fnType <- getFunctionType(rootFn)
    paramCount <- getParameterCount(fnType)
    _ <- validateSaturation(paramCount, argCount, app)
    resultType <- getReturnType(fnType)
  yield checkedChain.copy(typeSpec = Some(resultType))

def validateSaturation(paramCount: Int, argCount: Int, app: App): Either[List[SemanticError], Unit] =
  if paramCount == argCount then Right(())
  else if argCount < paramCount then 
    Left(List(UndersaturatedApplication(paramCount, argCount, app, phaseName)))
  else 
    Left(List(OversaturatedApplication(paramCount, argCount, app, phaseName)))

def countApplicationChain(app: App): (Term, Int) =
  // Walk down the left side of App chain counting arguments
  def go(current: Term, count: Int): (Term, Int) = current match
    case App(fn, _) => go(fn, count + 1)
    case rootFn => (rootFn, count)
  go(app, 1)  // Start with 1 for the current App's argument
```

### 5. Type Check References

```scala
def checkReference(ref: Ref, module: Module): Either[List[SemanticError], Ref] =
  ref.resolvedAs match
    case Some(bnd: Bnd) => 
      // Use binding's typeSpec (computed or inferred)
      Right(ref.copy(typeSpec = bnd.typeSpec))
      
    case Some(fnDef: FnDef) =>
      // Use function's computed TypeFn
      Right(ref.copy(typeSpec = fnDef.typeSpec))
      
    case Some(opDef: OpDef) =>
      // Use operator's computed TypeFn  
      Right(ref.copy(typeSpec = opDef.typeSpec))
      
    case _ =>
      Left(List(UnresolvableReference(ref, phaseName)))
```

### 6. Type Check Conditionals

```scala
def checkConditional(cond: Cond, module: Module): Either[List[SemanticError], Cond] =
  for
    checkedCondExpr <- checkExpr(cond.cond, module)
    _ <- validateBooleanType(checkedCondExpr.typeSpec, cond)
    checkedIfTrue <- checkExpr(cond.ifTrue, module)
    checkedIfFalse <- checkExpr(cond.ifFalse, module)
    _ <- validateBranchTypes(checkedIfTrue.typeSpec, checkedIfFalse.typeSpec, cond)
    resultType = checkedIfTrue.typeSpec  // Both branches have same type
  yield cond.copy(
    cond = checkedCondExpr,
    ifTrue = checkedIfTrue, 
    ifFalse = checkedIfFalse,
    typeSpec = resultType
  )

def validateBranchTypes(trueType: Option[TypeSpec], falseType: Option[TypeSpec], cond: Cond): Either[List[SemanticError], Unit] =
  (trueType, falseType) match
    case (Some(t1), Some(t2)) if areTypesCompatible(t1, t2, module) => Right(())
    case (Some(t1), Some(t2)) => Left(List(ConditionalBranchTypeMismatch(t1, t2, cond, phaseName)))
    case _ => Left(List(ConditionalBranchTypeUnknown(cond, phaseName)))
```

### 7. Validate Type Ascriptions

```scala
def validateTypeAscription(node: Typeable): List[SemanticError] =
  (node.typeAsc, node.typeSpec) match
    case (Some(ascribed), Some(computed)) =>
      if areTypesCompatible(ascribed, computed, module) then Nil
      else List(TypeMismatch(ascribed, computed, node, phaseName))
    case _ => Nil
```

## Type Compatibility Rules

### Basic Compatibility
- `TypeRef("Int")` = `TypeRef("Int")` ✓
- `TypeRef("Int")` ≠ `TypeRef("String")` ✗

### Alias Compatibility  
- `TypeRef("MyInt")` = `TypeRef("Int")` if `MyInt` is alias to `Int` ✓
- Follow alias chains to resolve ultimate type

### Function Compatibility
- `TypeFn([Int], String)` = `TypeFn([Int], String)` ✓
- Parameter and return types must all match

## Error Handling Strategy

Following established `SemanticPhaseState` patterns:
- **Accumulate errors**: Continue processing to find all type errors
- **Early validation**: Check mandatory ascriptions first
- **Defensive programming**: Handle missing/invalid types gracefully
- **Clear error messages**: Include context and location information

## Testing Strategy

### Test Structure
Following patterns from `TypeResolverTests.scala`:
```scala
class TypeCheckerTests extends BaseEffFunSuite:
  test("infers let binding types from literals"):
    val code = """
      let x = 42;
      let y = "hello";
    """
    semNotFailed(code).map { module =>
      // Validate inferred types
    }
```

### Test Categories

**Mandatory Ascriptions:**
- Functions without param types → error
- Functions without return types → error
- Operators without complete signatures → error

**Type Inference:**
- Let bindings from literals
- Let bindings from function applications
- Expression type propagation

**Type Validation:**
- Matching ascriptions → success
- Mismatched ascriptions → error
- Optional ascriptions work correctly

**Conditional Expressions:**
- Both branches same type → success
- Different branch types → error
- Non-boolean condition → error

**Application Checking:**
- Correct argument types → success
- Wrong argument types → error
- Curried applications work correctly
- Function saturation enforced:
  - Under-saturated calls → error
  - Over-saturated calls → error
  - Exactly saturated calls → success

**Error Accumulation:**
- Multiple type errors collected
- Processing continues after errors

## Benefits for Codegen Update (#156)

After TypeChecker phase:
- **All nodes typed**: Every `Typeable` node has computed `typeSpec`
- **Type safety**: All types validated and consistent
- **Codegen ready**: `ExpressionCompiler` can trust AST type information
- **No hardcoding**: `getLlvmType` helper can use actual AST types
- **Foundation complete**: Ready to complete codegen update

## Success Criteria

1. **All literals**: Use `TypeRef` instead of `LiteralType`
2. **All functions**: Have computed `TypeFn` in `typeSpec`
3. **All expressions**: Have inferred types in `typeSpec`
4. **Type validation**: Ascriptions checked against computed types
5. **Error accumulation**: Multiple type errors collected and reported
6. **Pipeline integration**: Works seamlessly in semantic analysis chain
7. **Test coverage**: Comprehensive test suite covering all scenarios
8. **Codegen unblocked**: Provides foundation for completing update #156
