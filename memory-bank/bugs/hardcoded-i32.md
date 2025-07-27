# Type Hardcoding in Function Calls Bug

**Issue ID:** Related to Codegen Update #156, Block 4  
**Status:** Critical Bug - Compilation fails at LLVM IR level  
**Root Cause:** ExpressionCompiler hardcodes `i32` types instead of using actual parameter types  

## Problem Summary

The MinnieML compiler correctly parses, semantically analyzes, and generates function declarations with proper types, but when generating function calls, the ExpressionCompiler hardcodes `i32` types for all arguments and return values instead of using the actual types derived from the AST. This causes LLVM compilation to fail with type mismatch errors.

## MML Source Code

**File:** `mml/samples/concat_print_string.mml` (corrected with proper parentheses)

```rust
fn print (a: String): () = @native;
fn println (a: String): () = @native;

fn concat( a: String b: String): String = @native;

fn main(): () = 
  println (concat "Fede" "Silva")
;
```

**Expected Behavior:** Should print `FedeSilva` to stdout  
**Actual Behavior:** Compilation fails with LLVM type mismatch error

## LLVM Compilation Error

```
llvm-as: /path/to/ConcatPrintString.ll:33:30: error: '%5' defined with type '%String = type { i64, ptr }' but expected 'i32'
  %12 = call i32 @concat(i32 %5, i32 %11)
                              ^
Command failed with exit code 1: llvm-as
```

## LLVM IR Analysis

### Function Declarations (Correct)

```llvm
; External functions - THESE ARE CORRECT
declare void @print(%String)
declare void @println(%String)
declare %String @concat(%String, %String)
```

The function declarations correctly use `%String` types as derived from the MML AST type annotations.

### String Construction (Correct)

```llvm
; String construction for "Fede" - CORRECT
%0 = add i64 0, 4
%1 = getelementptr [4 x i8], [4 x i8]* @str.0, i64 0, i64 0
%2 = alloca %String
%3 = getelementptr %String, %String* %2, i32 0, i32 0
store i64 %0, i64* %3
%4 = getelementptr %String, %String* %2, i32 0, i32 1
store i8* %1, i8** %4
%5 = load %String, %String* %2    ; %5 is of type %String

; String construction for "Silva" - CORRECT  
%6 = add i64 0, 5
%7 = getelementptr [5 x i8], [5 x i8]* @str.1, i64 0, i64 0
%8 = alloca %String
%9 = getelementptr %String, %String* %8, i32 0, i32 0
store i64 %6, i64* %9
%10 = getelementptr %String, %String* %8, i32 0, i32 1
store i8* %7, i8** %10
%11 = load %String, %String* %8   ; %11 is of type %String
```

The string construction correctly creates `%String` values in `%5` and `%11`.

### Function Calls (Incorrect - Type Hardcoding Bug)

```llvm
; BUG: Hardcoded i32 types in function call
%12 = call i32 @concat(i32 %5, i32 %11)    ; ❌ Wrong!
call void @println(i32 %12)                ; ❌ Wrong!
```

**Issues:**
1. **Return Type:** `call i32 @concat(...)` expects `i32` return but `@concat` returns `%String`
2. **Parameter Types:** `i32 %5, i32 %11` treats String parameters as `i32` 
3. **Chained Call:** `call void @println(i32 %12)` passes `i32` to function expecting `%String`

### Required Fix (Correct LLVM IR)

```llvm
; ✅ CORRECT: Use actual types from function signatures
%12 = call %String @concat(%String %5, %String %11)
call void @println(%String %12)
```

## Root Cause Analysis

### Location
The bug is in **ExpressionCompiler** (`modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/ExpressionCompiler.scala`), specifically in the `compileApp` and possibly `compileTerm` methods.

### Issue Details
1. **Function Declarations Work:** The function signature derivation (completed in Block 4) correctly generates LLVM function declarations from AST type annotations
2. **Function Calls Broken:** The expression compilation hardcodes `i32` for all function call arguments and return values
3. **Type System Disconnect:** There's a disconnect between the type information available in the AST and what's used during expression compilation

### Missing Implementation
From Block 4 of Codegen Update #156, this exact issue was identified as remaining work:
- ❌ **Replace all hardcoded types in `compileTerm`/`compileApp` with `getLlvmType` helper**

The `getLlvmType` helper exists and works (evidenced by correct function declarations), but it's not being used in expression compilation.

## Code Locations to Investigate

### 1. ExpressionCompiler.compileApp
This method likely contains hardcoded `i32` types for:
- Function call return types
- Function call argument types

### 2. ExpressionCompiler.compileTerm  
This method may have hardcoded types for:
- Variable references
- Literal values
- Term compilation

### 3. Type Helper Usage
The codebase should use the `getLlvmType` helper consistently:
- ✅ **Working:** Function signature generation 
- ❌ **Broken:** Function call generation

## Solution Requirements

### 1. Fix Function Call Type Generation
Replace hardcoded `i32` types in function calls with actual types derived from:
- Function signature for return types
- Parameter expressions for argument types

### 2. Consistent Type Helper Usage
Ensure all codegen uses the `getLlvmType` helper instead of hardcoded types:
```scala
// Instead of hardcoded:
// callInstr = s"call i32 @$fnName($args)"

// Use actual types:
val returnType = getLlvmType(functionReturnType)
val paramTypes = paramExpressions.map(expr => getLlvmType(expr.typeInfo))
callInstr = s"call $returnType @$fnName($argList)"
```

### 3. Type Information Flow
Ensure type information flows from AST → Expression Compilation:
- Parameter expressions should carry type information
- Function references should provide return type information
- Expression compilation should use this information consistently

## Test Case

**Input MML:**
```rust
fn main(): () = println (concat "Hello" " World");
```

**Expected LLVM IR:**
```llvm
%result = call %String @concat(%String %hello, %String %world)
call void @println(%String %result)
```

**Current LLVM IR (Broken):**
```llvm
%result = call i32 @concat(i32 %hello, i32 %world)  ; ❌ Type mismatch
call void @println(i32 %result)                     ; ❌ Type mismatch
```

**Expected Output:**
```
Hello World
```

**Current Output:**
```
LLVM compilation fails
```

## Priority

**CRITICAL:** This blocks all function calls with non-primitive types. The type system works for declarations but fails for calls, making the compiler unusable for realistic programs.

## Next Steps

1. Examine `ExpressionCompiler.scala` `compileApp` and `compileTerm` methods
2. Identify all locations with hardcoded `i32` types
3. Replace with `getLlvmType` helper calls using AST type information
4. Test with `concat_print_string.mml` sample
5. Run full test suite to ensure no regressions