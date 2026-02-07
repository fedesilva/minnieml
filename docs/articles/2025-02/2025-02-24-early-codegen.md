# LLVM IR generation and optimization in MinnieML

MinnieML (MML) uses LLVM as its backend compiler.

This article shows how MML generates LLVM IR from source code and how LLVM's optimization pipeline transforms it into efficient executables.

## The code generation pipeline

The MML compiler processes source code through several stages:

1. Parsing into an abstract syntax tree (AST)
2. Reference resolution and type checking
3. Operator precedence climbing for nested expressions
4. LLVM IR code generation
5. Some cut and paste ...
6. LLVM optimization passes
7. Final code generation (machine code)

## Example: expression evaluation and global initialization

Consider this MML program with variable declarations and arithmetic operations:

```mml
let x = 3*3;
let y = 4*30;
let z = 2 * y * x;
let a = 1 * 2 + 3 / y * x;
```

This program defines four variables with increasingly complex initialization expressions.

## Generated LLVM IR at this stage

After parsing and semantic analysis, MML generates the following LLVM IR:

```llvm
; ModuleID = 'Anon'
target triple = "x86_64-unknown-unknown"

@x = global i32 9
@y = global i32 120
@z = global i32 0
define internal void @_init_global_z() {
entry:
  %0 = load i32, i32* @y
  %1 = mul i32 2, %0
  %2 = load i32, i32* @x
  %3 = mul i32 %1, %2
  store i32 %3, i32* @z
  ret void
}
@a = global i32 0
define internal void @_init_global_a() {
entry:
  %0 = load i32, i32* @y
  %1 = sdiv i32 3, %0
  %2 = load i32, i32* @x
  %3 = mul i32 %1, %2
  %4 = add i32 2, %3
  store i32 %4, i32* @a
  ret void
}
@llvm.global_ctors = appending global [2 x { i32, void ()*, i8* }] [
  { i32, void ()*, i8* } { i32 65535, void ()* @_init_global_z, i8* null },
  { i32, void ()*, i8* } { i32 65535, void ()* @_init_global_a, i8* null }
]
```

**Note**: At this development stage, the IR must be manually fed into the LLVM toolchain for further processing.

### Breaking down the generated IR

#### LLVM IR primer

LLVR IR is a low-level, typed assembly language that LLVM uses as an intermediate representation.
It is designed to be easy to analyze and transform, making it suitable for optimization.

In this example, the IR includes:

    - global variable declarations: `@x`, `@y`, `@z`, and `@a`
    - initialization functions for `@z` and `@a`
    - a global constructor array to manage initialization order
    - inside functions
        - `load` instructions to read variable values
        - `mul`, `sdiv`, and `add` instructions for arithmetic operations
        - `%N` registers to hold intermediate results
        - `store` instructions to write results back to global variables
        - `ret` instructions to return from functions
        - `@llvm.global_ctors` to manage initialization order

Let's analyze how each part of the MML code translates to LLVM IR:

1. **Simple constant expressions**
   ```mml
   let x = 3*3;
   let y = 4*30;
   ```
   These expressions are constant-folded by mml during compilation:
   ```llvm
   @x = global i32 9     ; 3*3 evaluated at compile time
   @y = global i32 120   ; 4*30 evaluated at compile time
   ```

   Nothing too complex, but laying the groundwork for more complex optimizations;
   we rely on LLVM to handle more complex cases, anyway.

   Eventually, we will perform more complex optimizations using language-specific knowledge. 

   For now, the code generator is simple and straightforward, and we can focus on continuously 
   building the language.

2. **Complex expression for `z`**
   ```mml
   # (2 * y) * x
   let z = 2 * y * x; 
   ```
   This requires runtime calculation because it references other variables:
   ```llvm
   @z = global i32 0     ; Initial value is 0
   define internal void @_init_global_z() {
     entry:
       %0 = load i32, i32* @y    ; Load the value of y into %0
       %1 = mul i32 2, %0        ; (2 * y) -> (2 * %0) -> %1
       %2 = load i32, i32* @x    ; Load the value of x into %2
       %3 = mul i32 %1, %2       ; (2 * y) * x -> (%1 * %2) -> %3
       store i32 %3, i32* @z     ; Store result in z - let z = %3
       ret void
   }
   ```
   
   Note how MML's operator precedence is respected: 
   the expression is evaluated as `(2 * y) * x` since `*` is left-associative, 
   which directly maps to the sequence of operations in the IR.

3. **Complex expression for `a`**
   ```mml
   # (1 * 2) + ((3 / y) * x)
   let a = 1 * 2 + 3 / y * x;
   ```
   This requires careful handling of operator precedence:
   ```llvm
   @a = global i32 0     ; Initial value is 0
   define internal void @_init_global_a() {
     entry:
       %0 = load i32, i32* @y    ; Load the value of y
       %1 = sdiv i32 3, %0       ; 
       %2 = load i32, i32* @x    ; Load the value of x
       %3 = mul i32 %1, %2       ; Multiply (3/y) by x
       %4 = add i32 2, %3        ; Add 2 to the result (1*2 simplified to 2)
       store i32 %4, i32* @a     ; Store result in a
       ret void
   }
   ```
   
   The expression `1 * 2 + 3 / y * x` is parsed according to operator precedence:
   - `*` and `/` have higher precedence than `+`
   - `*` and `/` are left-associative
   - The expression is grouped as `(1 * 2) + ((3 / y) * x)`
   - `1 * 2` is constant-folded to `2`
   - The remaining computation happens at runtime in the order: `3/y`, then `(3/y)*x`, then `2 + ((3/y)*x)`

4. **Global constructor array**
   ```llvm
   @llvm.global_ctors = appending global [2 x { i32, void ()*, i8* }] [
     { i32, void ()*, i8* } { i32 65535, void ()* @_init_global_z, i8* null },
     { i32, void ()*, i8* } { i32 65535, void ()* @_init_global_a, i8* null }
   ]
   ```
   
   This special array tells LLVM which functions to run at program startup:
   - It contains 2 elements, one for each initialization function
   - Each element has 3 fields: priority (65535 is lowest), function pointer, and an optional data pointer
   - When the program starts, LLVM will call `@_init_global_z` and `@_init_global_a` to initialize the variables

The IR follows a pattern that reflects how MML's compiler works:
1. The AST captures expressions with proper operator precedence
2. The code generator traverses the AST and emits appropriate LLVM instructions
3. For each non-constant binding, it creates an initialization function
4. All initialization functions are collected in the global constructor array

## After LLVM optimization

When this IR passes through LLVM's optimizer, the result is substantially more efficient:

```llvm
; ModuleID = 'out/two-inits.bc'
source_filename = "two-inits.ll"
target datalayout = "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"
target triple = "x86_64-unknown-unknown"
@x = local_unnamed_addr global i32 9
@y = local_unnamed_addr global i32 120
@z = local_unnamed_addr global i32 2160
@a = local_unnamed_addr global i32 2
@llvm.global_ctors = appending global [0 x { i32, ptr, ptr }] zeroinitializer
```

The optimizer performs several transformations:

1. **Constant propagation**: LLVM calculates the values of `z` and `a` at compile time:
   - `z = 2 * 120 * 9 = 2160`
   - `a = 2 + (3 / 120) * 9 = 2` (the division `3/120` evaluates to 0 due to integer division)

2. **Dead code elimination**: The initializer functions are completely removed.

3. **Global constructor elimination**: The `@llvm.global_ctors` array is now empty.

4. **Address space optimization**: Added `local_unnamed_addr` to the globals for more efficient memory layout.

## Why this matters

The compiler generates straightforward, readable IR and lets LLVM handle the heavy optimization. Expressions are reduced to constants where possible, dead code is eliminated, and we get all of this without writing any optimization logic ourselves.

This separation lets us focus on language features and semantics without constantly revisiting code generation.