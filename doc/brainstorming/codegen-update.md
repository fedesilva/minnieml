# Codegen Update - Attributes Parsing for Native Implementations

## Ticket #156 Requirements

### 1. Add attributes parsing for native impls

**Requirement**: Add `op` attribute to functions/binary ops to specify LLVM intrinsic operations
- Example: `op * = @native[op=mult]` for multiplication
- Will be useful for protocols implementation

**Requirement**: Add `t` attribute to type defs to specify LLVM intrinsic types (or a struct)
- Example types:
   - `type Int = @native[t=i64]`
   - `type String = @native`

When rendering LLVM IR:

   * if a type has an attribute: 
            * t=XXX, XXX is a llvm native type (i32, i64, f64, etc)
        * if not, render as a forward reference to a struct 
            - think: 
                - could this be problematic?
                - is there any type that we could reference that is not a struct?
                - if we want primitives, we use the `t`
                - if not, it's either a struct defined in a header or ... what else?

**Current State**: 
- No attribute parsing exists for @native annotations
- Operators are hardcoded in `ExpressionCompiler.scala`

### 2. Type attribute `t` for LLVM type mapping

**Requirement**: Add `t` attribute to inform codegen that an MML type IS an LLVM type
- Example: `type Int = @native[t=i64]`

**Current State**:
- No type mapping mechanism exists
- Types are not declared with native mappings

### 3. Use types from AST (don't assume)

**Requirement**: Codegen should use types from the AST instead of making assumptions
- Currently hardcodes `i32` for integers, `%String` for strings
- Must fail if types are missing (not ascribed)

**Current State Confirmed**:
- `ExpressionCompiler.scala:111` - hardcodes `i32` for global references
- `ExpressionCompiler.scala:304,317,330,343` - hardcodes `i32` for binary ops
- `ExpressionCompiler.scala:385,396,407` - hardcodes `i32` for unary ops
- `ExpressionCompiler.scala:466,513` - assumes `i32` for function returns

### 4. Operators as function applications

**Requirement**: Operators are now function applications, need refactoring
- Binary operators like `+`, `-`, `*` should be curried functions
- Example: `a + b` becomes `(+)(a)(b)`

**Current State**:
- Operators are handled specially in `compileBinaryOp` and `compileUnaryOp`
- Not treated as regular function applications

## Implementation Plan

### Phase 1: Parser Updates

1. **Extend @native annotation parser**   
   - Support `op` attribute for operations
   - Support `t` attribute for type mappings
   - Location: Grammar parser files

2. **AST Updates**
   - Extend `NativeImpl` or annotation types to store attributes
   - Ensure attributes are preserved through parsing

### Phase 2: Type System Updates

1. **Create `injectBasicTypes` function**
   - Map basic types to native representations using TypeDef + TypeAlias pattern:
   (pseudo code, not valid mml)
     ```
     type Int64 = @native[t=i64]    // TypeDef with native mapping
     type Int = Int64               // TypeAlias pointing to native type
     type Float64 = @native[t=f64]  // TypeDef with native mapping (for future protocols)
     type Float = Float64           // TypeAlias pointing to native type (for future protocols)
     type Bool = @native[t=i1]      // Direct native mapping
     type String = @native          // No t attribute, points to struct ( see change of design below )
     ```
   - Should run before injectStandardOperators

2. **Update `injectStandardOperators`**
   - Add complete type specifications to operator definitions
   - Type both individual parameters AND overall function signatures
   - Add `op` attributes for LLVM intrinsics:
    (pseudo code, not valid mml)
     ```
     // Arithmetic operators (Int -> Int -> Int)
     op + (a: Int) (b: Int): Int -> Int -> Int = @native[op=add]
     op - (a: Int) (b: Int): Int -> Int -> Int = @native[op=sub]
     op * (a: Int) (b: Int): Int -> Int -> Int = @native[op=mul]
     op / (a: Int) (b: Int): Int -> Int -> Int = @native[op=sdiv]
     op ^ (a: Int) (b: Int): Int -> Int -> Int = @native[op=pow]
     
     // Comparison operators (Int -> Int -> Bool)
     op == (a: Int) (b: Int): Int -> Int -> Bool = @native[op=icmp_eq]
     op != (a: Int) (b: Int): Int -> Int -> Bool = @native[op=icmp_ne]
     op < (a: Int) (b: Int): Int -> Int -> Bool = @native[op=icmp_slt]
     op > (a: Int) (b: Int): Int -> Int -> Bool = @native[op=icmp_sgt]
     op <= (a: Int) (b: Int): Int -> Int -> Bool = @native[op=icmp_sle]
     op >= (a: Int) (b: Int): Int -> Int -> Bool = @native[op=icmp_sge]
     
     // Logical operators (Bool -> Bool -> Bool)
     op and (a: Bool) (b: Bool): Bool -> Bool -> Bool = @native[op=and]
     op or (a: Bool) (b: Bool): Bool -> Bool -> Bool = @native[op=or]
     
     // Unary arithmetic (Int -> Int)
     op - (a: Int): Int -> Int = @native[op=neg]
     op + (a: Int): Int -> Int = @native[op=nop]
     
     // Unary logical (Bool -> Bool)
     op not (a: Bool): Bool -> Bool = @native[op=not]
     ```
   - **Note**: Float operator types will be implemented with protocols system down the line.

### Phase 3: Codegen Refactoring

1. **Add type resolution to codegen**
   - Create helper to extract LLVM type from AST type info
   - Use `t` attribute from type definitions
   - Replace all hardcoded type assumptions

2. **Refactor operator handling**
   - Remove special cases for operators in `compileExpr`
   - Treat operators as regular function applications
   - Use `op` attribute to determine LLVM instruction

3. **Update emission functions**
   - Modify `compileTerm`, `compileExpr`, `compileApp` to use resolved types
   - Pass type information through `CompileResult`

### Phase 4: Error Handling

1. **Add simple type sanity check**
   - Fail compilation if types are not ascribed or resolved.
   - Clear error messages for missing type information

2. **Validate native attributes**
   - Check that `op` values are valid LLVM operations
   - Check that `t` values are valid LLVM types

## Technical Details

### Attribute Syntax
```
op + ... = @native[op=add]
type Int = @native[t=i32]
```

**Note** there are two types of @native:
* native impl for the body of a `fn` or an `op`.
* native type, typedefs an mml type as an llvm type.

op and t are used in those contexts respectively.
op for functions and operators, t for typedefs.

### Type Resolution Flow
1. Parser creates AST with @native attributes
2. `injectBasicTypes` adds native type mappings
3. `injectStandardOperators` adds operator definitions with attributes
4. TypeResolver resolves all type references
5. Codegen reads attributes and uses them for LLVM emission

### Example Transformations

**Before (current hardcoded approach)**:
```scala
// In compileBinaryOp
val line = emitAdd(resultReg, "i32", leftOp, rightOp)  // hardcoded i32
```

**After (using AST types)**:
```scala
// Get type from AST
val llvmType = getLlvmType(expr.resolvedType)  // e.g., "i64" from @native[t=i64]
val op = getOperation(fnRef.nativeAttributes)   // e.g., "add" from @native[op=add]
val line = emitBinaryOp(resultReg, llvmType, op, leftOp, rightOp)
```

## Future Considerations

- Protocol support will build on this attribute system
- Custom operators can be defined with native implementations
- Type aliases can map to different LLVM representations
- Potential for platform-specific type mappings

### Proposed AST Changes (for review, approved!)

To support the declarative native struct syntax, the following change to `AstNode.scala` is required. This change evolves `NativeType` into a `sealed trait` to handle both simple aliases and struct definitions, without requiring any changes to the `TypeDef` node itself.

```diff
------- SEARCH
case class NativeType(
  span:       SrcSpan,
  nativeType: Option[String] = None
) extends TypeSpec
=======
sealed trait NativeType extends TypeSpec

/** A native type alias, ie: `type SizeT = @native[t=i64]` */
case class NativeAlias(
  span:       SrcSpan,
  nativeType: String
) extends NativeType

/** A native struct definition, ie: `type String = @native { length: SizeT }`
  */
case class NativeStruct(
  span:   SrcSpan,
  fields: List[(String, TypeSpec)]
) extends NativeType
+++++++ REPLACE
```

## Revised Plan: Declarative Native Structs with Opaque Pointers (2025-07-02)

During planning, a significant design improvement was proposed. 
The initial plan was flawed because it wasn't scalable. The new approach is to make MML code provide information to the codegen 
by allowing external C structs to be mirrored declaratively.

For the initial implementation, we will adopt a minimalist "Opaque Pointer" approach to handle pointers for C interop.

The key distinction is that the MML `type String = @native { ... }` definition provides a complete, 
non-opaque mirror of the C struct, which is necessary for the compiler to generate correct getelementptr instructions. 


### The "Struct Mirroring" and "Opaque Pointer" Concepts

The goal is to generate LLVM IR that is structurally compatible with types defined in external C code. If our generated LLVM IR defines a type with the same name and memory layout as the one compiled from C, the linker will safely merge them.

This is achieved with a two-step process in a prelude file (inject type function for now):

**1. Bridge Primitive and Opaque Pointer Types:** Define MML types that map to C/LLVM types. Any type defined with a `*` in its `@native[t=...]` attribute is treated as an **opaque pointer** from MML's perspective.

```mml
// prelude.mml
type SizeT   = @native[t=i64]
type Char    = @native[t=i8]
type CharPtr = @native[t=i8*] // This is an opaque pointer type in MML
```

**2. Mirror the C Struct in MML:** Use these MML types to define the C struct's layout.

```mml
// prelude.mml
type String = @native {
  length: SizeT,
  data:   CharPtr
}
```

**Implications for MML:**
- **Safe by Default:** MML cannot perform pointer arithmetic or dereferencing on opaque pointer types like `CharPtr`. They are simply handles that can be received from and passed to native C functions.
- **Sufficient for Interop:** This is sufficient for the immediate goal of interacting with the C runtime's `String` struct.
- **Incremental Design:** More advanced pointer features and array types can be added in future iterations without breaking this initial design.

## Revised Implementation Plan

The implementation will follow these blocks:

**Block 1: Parser & AST Changes**
- Update `Parser.scala` to parse the new `@native { field: Type, ... }` syntax.
- Update `AstNode.scala` with a new node (e.g., `NativeStructDef`) to represent this structure in the AST.
   - make `NativeType` a trait, 
      - abstract it's current functionality into a subclass `NativePrimitiveAlias`
      - `NativeStructDef` is also a subclass 

**Block 2: Semantic Analysis**
- Update `TypeResolver` to handle `NativeStructDef` and recursively resolve the types of its fields.

**Block 3: Codegen - LLVM Type Emission**
- Add a new pass to `LlvmIrEmitter` to iterate over all `NativeStructDef`s and generate the corresponding LLVM `type` definitions (e.g., `%String = type { i64, i8* }`) before any other code is generated.

**Block 4: Codegen - Refactor `ExpressionCompiler`**
- Implement a `getLlvmType` helper that can look up 
   - these new struct definitions.
   - and primitive references
- Refactor `compileTerm` (especially for `LiteralString`), `compileApp`, and remove the old operator logic. The refactoring will use the rich type information from the AST, treating pointer fields as opaque.

## Implementation Tasks

1. **Add @native attribute parsing for 'op' and 't' parameters** (high priority)
   - Extend parser to support specific keys (o and t), do not allow free form attributes.
   - Store attributes in AST nodes (both nodes NativeImpl and NativeTypeImpl have a map to store attrs)

2. **Create injectBasicTypes function to map MML types to LLVM types** (high priority)
   - Define Int → @native[t=i64]
   - Define Bool → @native[t=i1]
   - Define String → @native[t=%String]

3. **Update injectStandardOperators to add type annotations** (high priority)
   - Add type signatures to all operators
   - Add @native[op=X] attributes for LLVM operations

4. **Refactor codegen to use types from AST instead of hardcoded assumptions** (high priority)
   - Replace hardcoded "i32", "i64", "%String" with type lookups
   - Use 't' attribute from type definitions

5. **Refactor operators to be treated as function applications** (medium priority)
   - Remove special operator handling in compileExpr
   - Treat as regular curried function calls

6. **Add error handling for missing type information** (medium priority)
   - Fail compilation when types are not ascribed
   - Provide clear error messages

## Current Todo List

- [x] Create doc/brainstorming/codegen-update.md with implementation plan
- [ ] **(Block 1)** Update parser for `@native { ... }` syntax.
- [ ] **(Block 1)** Update AST with `NativeStructDef` node.
- [ ] **(Block 2)** Update `TypeResolver` for `NativeStructDef`.
- [ ] **(Block 3)** Implement LLVM `type` definition emission in `LlvmIrEmitter`.
- [ ] **(Block 4)** Refactor `ExpressionCompiler` to use new type info from the AST.
- [ ] **(Block 4)** Update `injectBasicTypes` to include `SizeT`, `Char`, `CharPtr`.
- [ ] **(Original Task)** Refactor codegen to use types from AST instead of hardcoded assumptions.
- [ ] **(Original Task)** Refactor operators to be treated as function applications.
- [ ] **(Original Task)** Add error handling for missing type information.
