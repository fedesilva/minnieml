# Interim Containers: Monomorphic Arrays

This document outlines a plan for introducing basic array types in MML, focusing on a monomorphic approach to simplify initial implementation and avoid immediate blockers related to polymorphism in the type system. The goal is to provide functional `Int` and `String` arrays, leveraging the existing `String` struct design pattern.

## Design Principle: "Fat Pointer" Structure

Arrays will follow the same internal representation as the existing `String` type: a struct containing a length field and a pointer to the data.

*   `String` is represented as `{ i64, i8* }` (length: `SizeT`, data: `CharPtr`).
*   `Array[T]` will conceptually be `{ SizeT, T* }`.

## Monomorphic Array Types

To bypass current limitations in the type system regarding polymorphism, we will introduce specific, concrete array types for `Int` and `String`.

### Proposed Types to Inject into Semantic Prelude

These types will be injected into the semantic prelude, similar to how `String` and other basic types are handled.

```mml
// Represents an array of Int (Int64)
type IntArray = @native {
  length: SizeT,
  data:   *Int64 // Pointer to a contiguous block of Int64
}

// Represents an array of String
type StringArray = @native {
  length: SizeT,
  data:   *String // Pointer to a contiguous block of String structs
}
```
**Note**: `*String` here means a pointer to the `String` struct itself, not `i8*`. Each element in `StringArray` would therefore be a `String` struct (which itself contains a length and `i8*` data pointer).

### Memory Layout Implications

*   `IntArray` would map to an LLVM structure like `{ i64, i64* }`.
*   `StringArray` would map to an LLVM structure like `{ i64, %struct.String* }`.
    *   This requires defining `%struct.String` in LLVM IR if it's not already implicitly defined or if we need to explicitly type it for array elements.

## Core Operations

Operations will be implemented as native functions, specifically tailored for each monomorphic array type to avoid type-based overloading issues.

### Allocation Functions

These functions will create new arrays of a specified size, returning the appropriate array type.

*   `fn mk_int_array(size: Int): IntArray = @native;`
*   `fn mk_string_array(size: Int): StringArray = @native;`

### Element Access Functions (Get/Set)

Given the current `DuplicateNameChecker` and `RefResolver` limitations (allowing overloading by arity but not directly by type signature for functions), we will use distinct function names for element access.

*   `fn get_int_array_at(arr: IntArray, idx: Int): Int = @native;`
*   `fn set_int_array_at(arr: IntArray, idx: Int, value: Int): Unit = @native;`
*   `fn get_string_array_at(arr: StringArray, idx: Int): String = @native;`
*   `fn set_string_array_at(arr: StringArray, idx: Int, value: String): Unit = @native;`

### Length Function

A generic length function for the array structure.

*   `fn array_len(arr: IntArray): Int = @native;`
*   `fn array_len(arr: StringArray): Int = @native;`
    *   **Note on `array_len`**: This might still hit the "functions cannot be overloaded" rule if the type checker is strict. A safer initial approach might be `int_array_len` and `string_array_len` or, if feasible, an operator (`op .length(arr: IntArray): Int`). Given that operators can be overloaded by arity and name, but functions cannot, we will need to carefully consider how `array_len` is handled. For now, distinct names (`int_array_len`, `string_array_len`) are the safest bet to avoid blocking.

## Next Steps

1.  **Define Native Types**: Add `IntArray` and `StringArray` to the semantic injection mechanism.
2.  **Implement Native Runtime Functions**: Create the corresponding C functions in `mml_runtime.c` for allocation, get, set, and length.
3.  **Inject Native Functions**: Add the MML signatures for these native functions into the semantic prelude.
4.  **Test**: Write sample `.mml` files to test creation, access, modification, and length retrieval for both `IntArray` and `StringArray`.
