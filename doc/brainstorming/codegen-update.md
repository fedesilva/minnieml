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
   - Add support for attributes: `@native[key=value, ...]`
   - Support `op` attribute for operations
   - Support `t` attribute for type mappings
   - Location: Grammar parser files

2. **AST Updates**
   - Extend `NativeImpl` or annotation types to store attributes
   - Ensure attributes are preserved through parsing

### Phase 2: Type System Updates

1. **Create `injectBasicTypes` function**
   - Map basic types to native representations:
   (pseudo code, not valid mml)
     ```
     type Int = @native[t=i64]
     type Bool = @native[t=i1]
     type String = @native // no attributes, this will point to a struct
     ```
   - Should run when injectStandardOperators runs

2. **Update `injectStandardOperators`**
   - Add type annotations to operator definitions
   - Add `op` attributes for LLVM intrinsics:
    (pseudo code, not valid mml)
     ```
     op + : Int -> Int -> Int = @native[op=add]
     op - : Int -> Int -> Int = @native[op=sub]
     op * : Int -> Int -> Int = @native[op=mul]
     op / : Int -> Int -> Int = @native[op=sdiv]
     ```

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
   - Fail compilation if types are not ascribed.
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
- [ ] Add @native attribute parsing for 'op' and 't' parameters
- [ ] Create injectBasicTypes function to map MML types to LLVM types
- [ ] Update injectStandardOperators to add type annotations
- [ ] Refactor codegen to use types from AST instead of hardcoded assumptions
- [ ] Refactor operators to be treated as function applications
- [ ] Add error handling for missing type information