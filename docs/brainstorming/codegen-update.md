# Codegen Update: Declarative Native Types

## 1. Goal

This document outlines the plan to refactor the MML compiler's code generation phase.
The primary goal is to eliminate hardcoded types and special operator handling, replacing
them with a flexible system that derives type information directly from the AST. This will
be achieved by introducing a declarative syntax for mirroring external C types and structs
within MML.

## 2. Current State & Motivation

The current implementation in `ExpressionCompiler.scala` suffers from several issues:

*   **Hardcoded Types:** It assumes types like `i32`, `i64`, and `%String` for many
    operations, making the codegen brittle and not extensible.
*   **Special Operator Handling:** It has dedicated logic for binary and unary operators
    (`compileBinaryOp`, `compileUnaryOp`). This is now obsolete, as the `ExpressionRewriter`
    semantic pass converts all operators into standard function applications.
*   **Non-Scalable Native Types:** The old `@native` attribute syntax (`@native[t=...]`)
    is insufficient for describing complex data structures like the C `String` struct,
    forcing knowledge of its layout into the compiler.

This refactor is necessary to make the compiler more robust, type-aware, and prepared for
future features like user-defined data structures and expanded C interoperability.

## 3. The New Design: Struct Mirroring & Opaque Pointers

The new design centers on having the MML code provide a self-contained, explicit
description of native types, so the compiler does not have to rely on hardcoded information.
We will introduce a new syntax to allow MML to **mirror** the layout of external C data
structures.

It is critical to understand that MML is not *defining* these structs; it is declaring
their memory layout for the benefit of the MML compiler. This allows the codegen to
generate compatible LLVM IR, including correct `getelementptr` instructions for field
access. The actual definition of the type exists in the linked C code (e.g.,
`mml_runtime.c`). The LLVM linker will safely merge the MML-generated type definition
with the C-generated one, as long as they are structurally identical.

For this initial implementation, we will also treat pointers as **opaque handles**. MML will
know about pointer types but will not support dereferencing or arithmetic on them. They
can only be passed to and from native functions.

### 3.1. New `@native` Syntax

The `@native` keyword will now be used with a colon (`:`) and support three forms:

**1. Native Primitive Alias:** Maps an MML type to a primitive LLVM type.
```mml
type SizeT = @native:i64
```

**2. Opaque Pointer Alias:** Maps an MML type to an LLVM pointer type. The `*`
   signifies that this is an opaque pointer.
```mml
type CharPtr = @native:*i8
```

**3. Native Struct Mirror:** Describes the layout of an external struct using previously
   defined MML types.
```mml
type String = @native {
  length: SizeT,
  data:   CharPtr
}
```

## 4. Implementation Plan

The implementation is broken into four main blocks.

### Block 1: AST & Parser Changes

**AST (`AstNode.scala`):**
The `NativeType` node will be evolved into a sealed trait to represent the three new
forms of native type definitions.

```scala
sealed trait NativeType extends TypeSpec

// For @native:i64
case class NativePrimitive(span: SrcSpan, llvmType: String) extends NativeType

// For @native:*i8
case class NativePointer(span: SrcSpan, llvmType: String) extends NativeType

// For @native { ... }
case class NativeStruct(span: SrcSpan, fields: List[(String, TypeSpec)]) extends NativeType
```

**Parser (`Parser.scala`):**
The `nativeTypeP` parser will be rewritten to understand the new colon-based syntax and
create the appropriate `NativePrimitive`, `NativePointer`, or `NativeStruct` AST nodes.

### Block 2: Semantic Analysis Changes

The `TypeResolver` will be updated to handle `NativeStruct` definitions. When it
encounters one, it will recursively resolve the `TypeSpec` for each field in the struct.

### Block 3: Codegen - LLVM Type Emission

A new pass will be added to `LlvmIrEmitter` that runs before function compilation. This
pass will iterate over all `NativeStruct` definitions in the AST and generate the
corresponding LLVM `type` definitions (e.g., `%String = type { i64, i8* }`).

### Block 4: Codegen - Expression Compiler Refactoring

This is the final and largest block, where the hardcoding is removed.
1.  **Implement `getLlvmType` helper:** This function will take a resolved `TypeSpec`
    from the AST and return the correct LLVM type string (e.g., "i64", "%String", "i8*").
2.  **Refactor `compileExpr`:** The special cases for `BinOpDef` and `UnaryOpDef` will be
    removed. All expressions will be treated as function application chains.
3.  **Refactor `compileTerm` and `compileApp`:** All hardcoded types (`i32`, `%String`, etc.)
    will be replaced with calls to the `getLlvmType` helper, making the codegen fully
    dependent on the type information resolved in the semantic phase. The logic for
    `LiteralString` will be rewritten to use the mirrored `String` struct definition
    from the AST.
