# Test Suite Issues (Post Type Checker Implementation)

## Overview
After completing the Type Checker implementation (#133), the test suite revealed pre-existing issues unrelated to the type checker work. These issues need to be addressed to get all tests passing.

## Issues Identified

### 1. Function Parameter Syntax Error
**Problem**: Tests use incorrect comma-separated parameter syntax
- **Incorrect**: `fn compose (f: Int, g: Int, x: Int): Int`
- **Correct**: `fn compose (f: Int g: Int x: Int): Int`

**Affected Tests**:
- AppRewritingTests
- MemberErrorCheckerTests
- AlphaOpTests

### 2. Missing Unit Type Definition
**Problem**: The system doesn't define a `Unit` type, but tests use it
- `LiteralUnit` nodes have `typeSpec = Some(TypeRef(..., "Unit", ...))`
- TypeResolver fails with `UndefinedTypeRef` when it encounters Unit references

**Solution**: Add Unit type to `injectBasicTypes` in the semantic package

### 3. Missing Type Annotations in Tests
**Problem**: Many tests were written before type annotations became mandatory
- Functions missing return type annotations
- Functions missing parameter type annotations

**Examples**:
```mml
// Missing annotations:
fn identity(x) = x;              // Missing param and return types
fn double(x) = x * 2;            // Missing param and return types

// Should be:
fn identity(x: Int): Int = x;
fn double(x: Int): Int = x * 2;
```

## Proposed Solution

### Phase 1: Fix Critical Infrastructure
1. **Add Unit Type**: Update `semantic/package.scala` to inject Unit type
   ```scala
   val unitType = TypeDef(
     visibility = MemberVisibility.Protected,
     span = SrcSpan.synthetic,
     name = "Unit",
     typeSpec = None,  // Unit has no native representation
     typeAsc = None,
     docComment = None
   )
   ```

### Phase 2: Fix Test Syntax
1. **Update Parameter Syntax**: Change all comma-separated parameters to space-separated
2. **Add Type Annotations**: Add missing type annotations to all test functions

### Phase 3: Verify Changes
1. Run full test suite to ensure all tests pass
2. Document any additional issues that arise

## Impact
- **20 failing tests** in AppRewritingTests
- **Multiple failures** in TypeResolverTests, MemberErrorCheckerTests, AlphaOpTests
- All failures are test-related, not implementation bugs

## Priority
High - These issues prevent proper validation of the Type Checker implementation and block further development.