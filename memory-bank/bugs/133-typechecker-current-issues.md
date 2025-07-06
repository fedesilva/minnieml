# TypeChecker Current Issues

## Overview
The TypeChecker has been significantly refactored to properly handle type checking without higher-order functions.

## Current Status
Most of the TypeChecker functionality is working correctly:
- Reference resolution works for all types of references (FnParam, Decl, etc.)
- Operator precedence and expression rewriting work correctly
- The semantic pipeline order is correct (TypeResolver → RefResolver → ExpressionRewriter → TypeChecker)
- Type ascription to spec lowering is properly implemented
- Parameter context is correctly threaded through function/operator body checking

## Fixed Issues

### Issue 1: Operator Type Validation Logic (FIXED)
**Problem**: Injected operators had `typeAsc` set to a full TypeFn instead of just the return type.

**Root Cause**: 
- In `injectStandardOperators`, operators were created with `typeAsc = Some(TypeFn(...))`
- This is wrong because `typeAsc` on an operator/function should be the return type only

**Fix Applied**: 
- Changed injected operators to have `typeAsc = Some(intType)` or `Some(boolType)` as appropriate
- Test "Test operators with the same symbol but different arity" now passes

### Issue 2: TypeFn Assignment (FIXED)
**Problem**: TypeChecker was creating and assigning TypeFn to function/operator `typeSpec`.

**Root Cause**:
- Without higher-order functions, no value should ever have TypeFn as its type
- TypeFn should never be assigned to any node's `typeSpec`

**Fix Applied**:
- Removed all TypeFn creation from TypeChecker
- First pass now lowers type ascriptions to specs:
  - Function/operator `typeSpec` = their return type ascription
  - Parameter `typeSpec` = their type ascription
- Applications get parameter types directly from the declaration's parameters
- Clean separation: ascriptions are input, specs are what type checker uses

### Issue 3: Parameter References in Operator Bodies (FIXED)
**Problem**: References to parameters in operator bodies weren't getting their typeSpec set.

**Root Cause**:
- RefResolver sets `resolvedAs` to point to the OLD parameters (before TypeChecker lowering)
- When checking operator body, parameter refs had no typeSpec

**Fix Applied**:
- Added parameter context threading through expression checking
- When checking function/operator bodies, pass parameters with lowered typeSpecs as context
- Parameter references look up in context first before using `resolvedAs`

## Remaining Issues

### Issue 4: Complex Expression Type Checking
Some complex expressions (e.g., `(1 + 2) * 3`) are failing with UnresolvableType.

**Symptom**: The entire expression gets UnresolvableType error

**Possible Cause**: 
- Expression checking might not be properly handling all terms in complex expressions
- Need to investigate how ExpressionRewriter structures these expressions

see `memory-bank/bugs/133-typechecker-current-issues.txt`


-----

#### Analysis

# 🔬 Compiler Pipeline Analysis - Type Inference Failure Root Cause

> **🎯 BREAKTHROUGH**  
> The compiler dump reveals the exact point where type inference fails! The issue is in the TypeChecker phase when processing parenthesized expressions that contain correctly resolved operator applications.

## Source Code with Error Location

```mml
let a = (1 + 2) * (3 - 4) / 5;
        ~~~~~~~ <- Error location: SrcPoint(2,9,9) to SrcPoint(2,17,17)
```

## 📊 Compiler Pipeline Analysis

### 1. Original Module (Parsing) ✅

Successfully parses `(1 + 2)` as `GroupTerm` containing `Expr` with `LiteralInt 1`, `Ref +`, `LiteralInt 2`

```scala
GroupTerm
  Expr
    LiteralInt 1
    Ref +
      candidates: []
    LiteralInt 2
```

### 2. Types Resolved Module ✅

Operators are properly defined and typed, `candidates: []` initially (normal for this phase)

### 3. Resolved Module ✅

Symbol resolution works: `Ref +` shows `candidates: [BinOpDef +, UnaryOpDef +]`

```scala
Ref +
  candidates: [BinOpDef +, UnaryOpDef +]
```

### 4. Unified Rewriting ✅

Perfect! Converts `GroupTerm` to proper `App` structure:

```scala
App
  fn:
    App
      fn:
        Ref +
          resolvedAs: BinOpDef +
          candidates: [BinOpDef +, UnaryOpDef +]
      arg:
        Expr
          LiteralInt 1
  arg:
    Expr
      LiteralInt 2
```

### 5. Type Checked ❌

**FAILURE POINT:** All components show `typeSpec: None, typeAsc: None`

```scala
App
  typeSpec: None     // ❌ Should be computed
  typeAsc: None      // ❌ Should be inferred
  fn:
    App
      typeSpec: None
      typeAsc: None
      fn:
        Ref +
          typeSpec: None
          typeAsc: None
          resolvedAs: BinOpDef +
      arg:
        Expr
          typeSpec: None
          typeAsc: None
          LiteralInt 1   // ✅ This has proper type
```

## 🔍 Root Cause Analysis

### Critical Discovery: Type Inference Gap in TypeChecker

**The Problem Identified:** The TypeChecker is failing to assign types to `App` and `Expr` nodes, even though all the components are properly resolved and individual literals are typed.

### ⚠️ Type Inference Logic Missing

The TypeChecker phase shows that **every** `App`, `Expr`, and `Ref` node has:
- `typeSpec: None`
- `typeAsc: None`

But `LiteralInt` nodes have proper types. This means the TypeChecker is missing logic to:

1. Compute types for operator applications
2. Propagate types up expression trees  
3. Assign computed types to expression nodes

### AST Fragment Analysis

**Working: Literal typing**
```scala
LiteralInt(
  SrcSpan(SrcPoint(2,10,10), SrcPoint(2,11,11)),
  1,
  Some(TypeRef(
    SrcSpan(SrcPoint(2,10,10), SrcPoint(2,11,11)),
    Int,
    Some(TypeAlias(...))
  ))
)  // ✅ Proper type
```

**Failing: Expression typing**
```scala
App(
  typeSpec: None,     // ❌ Should be computed
  typeAsc: None,      // ❌ Should be inferred
  fn: Ref(
    SrcSpan(...),
    +,
    None,
    None,
    Some(BinOpDef(...)),  // ✅ Properly resolved
    List(...)
  ),
  arg: Expr(..., LiteralInt(..., 1, Some(TypeRef(...)))),  // ✅ Properly typed
  arg: Expr(..., LiteralInt(..., 2, Some(TypeRef(...))))   // ✅ Properly typed
)
```

**The TypeChecker should compute:**
```scala
// App.typeAsc = BinOpDef.returnType = Int
```

### What Should Happen

1. TypeChecker sees `App` with `resolvedAs: BinOpDef +`
2. Extracts operator return type: `Int`
3. Validates argument types match operator parameter types
4. Assigns `typeAsc: Some(TypeRef(..., Int, ...))` to the `App`
5. Propagates type up to parent expressions

---

## Raw AST Fragments from Dump

### Working Literal (has type):
```scala
LiteralInt(
  SrcSpan(SrcPoint(2,10,10),SrcPoint(2,11,11)),
  1,
  Some(TypeRef(
    SrcSpan(SrcPoint(2,10,10),SrcPoint(2,11,11)),
    Int,
    Some(TypeAlias(
      Protected,
      SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),
      Int,
      TypeRef(
        SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),
        Int64,
        Some(TypeDef(...))
      ),
      Some(NativePrimitive(...))
    ))
  )),
  None
)
```

### Failing App (no type):
```scala
App(
  typeSpec: None,
  typeAsc: None,
  fn: App(
    typeSpec: None,
    typeAsc: None,
    fn: Ref(
      SrcSpan(SrcPoint(2,12,12),SrcPoint(2,14,14)),
      +,
      None,
      None,
      Some(BinOpDef(
        Public,
        SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),
        +,
        FnParam(...),
        FnParam(...),
        60,
        Left,
        Expr(...),
        None,
        Some(TypeRef(SrcSpan(SrcPoint(0,0,0),SrcPoint(0,0,0)),Int,Some(TypeAlias(...))))
      ))
    ),
    arg: Expr(typeSpec: None, typeAsc: None, LiteralInt(...))
  ),
  arg: Expr(typeSpec: None, typeAsc: None, LiteralInt(...))
)
```

### Error Structure:
```scala
TypeCheckingError(
  UnresolvableType(
    TypeRef(SrcSpan(SrcPoint(2,9,9),SrcPoint(2,17,17)), unknown, None),
    Expr(SrcSpan(SrcPoint(2,9,9),SrcPoint(2,17,17)), List(...)),
    mml.mmlclib.semantic.TypeChecker
  )
)
```

---

## 🎯 Precise Diagnosis & Solution

### 📍 Exact Problem Location
- **Phase:** TypeChecker (phase 5 in the pipeline)
- **Component:** Expression type inference logic  
- **Missing Logic:** Type computation and assignment for `App` nodes

### 🔧 What Needs Implementation
- **App Type Inference:** When processing `App` nodes, extract return type from resolved operators
- **Expression Type Propagation:** Propagate computed types up the expression tree
- **Type Assignment:** Properly set `typeAsc` fields on computed expressions

### 🚀 Current Status
- **✅ Parsing:** Perfect - handles parentheses and precedence
- **✅ Symbol Resolution:** Perfect - all operators resolve correctly  
- **✅ AST Rewriting:** Perfect - converts to proper application structure
- **❌ Type Inference:** Missing - needs implementation of expression type computation

### 📝 Solution
Implement type inference logic in the TypeChecker that computes and assigns types to `App` nodes based on their resolved operators and argument types.

### Key Implementation Points
1. When processing an `App` node, check if `fn.resolvedAs` contains an operator definition
2. Extract the operator's return type from the `BinOpDef`/`UnaryOpDef`
3. Validate argument types match operator parameter types
4. Assign the computed type to `App.typeAsc`
5. Ensure type propagation works up the expression tree

This is a **single, focused implementation gap** rather than fundamental architectural issues!