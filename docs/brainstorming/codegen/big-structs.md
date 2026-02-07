# Large struct return semantics

## Current behavior

Struct constructors return by value, regardless of size:

```llvm
define %struct.Big @__mk_Big(...) {
  %ptr = alloca %struct.Big          ; N bytes on stack
  ; ... store fields ...
  %val = load %struct.Big, %struct.Big* %ptr
  ret %struct.Big %val               ; return by value (copy)
}
```

For a 20-field `Int64` struct (160 bytes), this means:
- 160 bytes allocated on stack per constructor call
- Entire struct loaded and returned by value
- Caller receives a copy

The x86_64 and aarch64 ABIs handle large returns via hidden `sret` pointer (caller passes
destination address as first argument), so the codegen is correct. But there's still
copying involved at the language level.

## Implications

- Stack pressure: Large allocations per call
- Memory bandwidth: Copying N bytes on return
- Recursion/nesting: Costs multiply with call depth

For small-to-medium structs (≤64 bytes), this is fine. For larger structs, it may
become a performance concern.

## Alternatives

### 1. heap allocation (box semantics)

```llvm
define %struct.Big* @__mk_Big(...) {
  %ptr = call i8* @malloc(i64 160)
  ; store fields directly to ptr
  ret %struct.Big* %ptr   ; return 8-byte pointer
}
```

Pros:
- Only 8 bytes returned
- No stack pressure for large structs

Cons:
- Heap allocation cost (malloc/free)
- Requires ownership tracking for the struct itself
- Indirection on every field access

### 2. caller-provided buffer (out-parameter)

```llvm
define void @__mk_Big(%struct.Big* sret %out, ...) {
  ; store fields directly to %out
  ret void
}
```

Pros:
- No copy on return
- Caller controls memory placement (stack or heap)
- Matches what ABI already does under the hood

Cons:
- Changes calling convention at MML level
- Complicates the "constructor is a function" model

### 3. threshold-based hybrid

Use value semantics for small structs, switch to heap/sret for large ones:
- ≤16 bytes: return in registers (current, optimal)
- 17-64 bytes: return by value with sret (current, acceptable)
- >64 bytes: heap allocate or require explicit boxing

