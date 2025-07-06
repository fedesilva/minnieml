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