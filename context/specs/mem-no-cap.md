# Eliminating `__cap` and Hardcoded Heap Types

## Problem Statement

The current memory management prototype uses two mechanisms that limit genericity:

1. **`__cap` field**: A runtime discriminator baked into heap type structs (String, Buffer, IntArray, StringArray)
   - `__cap > 0` = heap allocated, safe to free
   - `__cap == -1` = static memory, don't free

2. **Hardcoded heap types**: `OwnershipAnalyzer.scala:187`
   ```scala
   val heapTypes: Set[String] = Set("String", "Buffer", "IntArray", "StringArray")
   ```

### Why This Is Problematic

1. **C library integration**: External structs (raylib, SDL, etc.) can't have fields added
2. **Not generic**: Adding a new heap type requires modifying the hardcoded list
3. **Couples runtime and compiler**: Memory management strategy is split between struct layout and analyzer logic

### Why `__cap` Exists

When two branches converge with different allocation origins:

```mml
fn get_string(use_heap: Bool, n: Int): String =
  if use_heap then to_string n else "static" end
```

The compiler inserts an unconditional `__free_String` call. At runtime, `__cap` lets the free function decide whether to actually deallocate.

---

## Proposed Solution

### 1. Type-Level Memory Attribute

Replace hardcoded list with explicit annotation on type declarations:

```mml
type String = @native[mem=heap];
type Buffer = @native[mem=heap];
```

The `mem=heap` attribute tells the compiler:
- Values of this type may be heap-allocated
- Ownership tracking applies
- Free calls may be needed

### 2. Heap Promotion for Returns

When a function returns a heap type, the caller always owns the result. If a branch returns a static value, the compiler inserts a clone to promote it to heap.

```mml
fn get_string(use_heap: Bool): String =
  if use_heap then to_string 42 else "static" end
  // "static" branch gets cloned - caller always owns result
```

### 3. Local Mixed Ownership Tracking (Sidecar Booleans)

For **local variables only**, when branches with different allocation origins converge, generate a companion boolean to track ownership.

```mml
// Source
let s = if cond then to_string n else "static" end

// Transformed at AST level by OwnershipAnalyzer
let __owns_s = if cond then true else false end;
let s = if cond then to_string n else "static" end;
// At scope end:
if __owns_s then __free_String s else ()
```

This avoids unnecessary cloning when the value is only used locally and never returned.

**LLVM IR mapping**: The sidecar boolean becomes a `phi i1` at the merge point. The conditional free compiles to:
```llvm
  br i1 %__owns_s, label %free, label %cont
free:
  call void @__free_String(...)
  br label %cont
cont:
  ; continue
```
LLVM optimizes this efficiently (often a conditional move or predicted branch).

**Key property**: The sidecar exists only at the IR level - it doesn't affect MML types or ABI. Codegen sees regular `Cond` and `App` nodes.

---

## Current State (Verified)

### Structs: Stack Allocated, Passed by Value

From `RecordsMem-x86_64-apple-macosx.ll`:
```llvm
define %struct.User @recordsmem___mk_User(%struct.String %0, %struct.String %1) {
  %2 = alloca %struct.User       ; stack allocation
  ; ... store fields ...
  ret %struct.User %5            ; return by VALUE
}
```

User-defined structs are value types. The struct itself doesn't need `__cap` - only heap-allocated fields within need tracking.

### Field Freeing: Works Independently

The OwnershipAnalyzer tracks String fields and frees them individually at scope end:
```llvm
  call void @__free_String(...)  ; name_str field
  call void @__free_String(...)  ; role_str field
```

No struct destructor is generated - fields are freed based on ownership tracking.

### Mixed Ownership: Status Quo with `__cap`

**MML source** (`mml/samples/mixed_ownership_test.mml`):
```mml
fn test_inline_conditional(use_heap: Bool, n: Int): Unit =
  let s = if use_heap then to_string n else "inline static" end;
  println s
```

**Generated LLVM IR** (from `MixedOwnershipTest-x86_64-apple-macosx.ll`):
```llvm
then2:
  call void @to_string(ptr sret(%struct.String) align 8 %5, i64 %1)  ; heap alloc
  %6 = load %struct.String, ptr %5, align 8
  br label %merge4
else3:
  ; ... build static String with __cap = -1 ...
  store i64 -1, i64* %11, !tbaa !10   ; <-- __cap = -1 for static
  %12 = load %struct.String, %struct.String* %8
  br label %merge4
merge4:
  %13 = phi %struct.String [ %6, %then2 ], [ %12, %else3 ]  ; branches merge
  ; ... use s ...
  call void @__free_String(ptr byval(%struct.String) align 8 %15)  ; UNCONDITIONAL
```

**C runtime check** (`mml_runtime.c`):
```c
void __free_String(String s)
{
    if (s.__cap > 0 && s.data)   // <-- runtime decides whether to free
        free(s.data);
}
```

**How it works:**
1. Heap branch: `to_string` sets `__cap = buffer_size` (positive)
2. Static branch: Literal construction sets `__cap = -1`
3. At merge: Compiler doesn't know which branch was taken
4. Free call is unconditional - runtime checks `__cap` to decide

This works but requires `__cap` to be embedded in every heap-type struct.

---

## Findings Summary

| Aspect                   | Current State                          | Proposed                             |
|--------------------------|----------------------------------------|--------------------------------------|
| Heap type identification | Hardcoded `heapTypes` set              | `@native[mem=heap]` attribute        |
| Function returns         | `__cap` discriminates at runtime       | Heap promotion - caller always owns  |
| Local mixed ownership    | `__cap` discriminates at runtime       | Ownership tracking (locals only)     |
| Free call (returns)      | Unconditional, runtime checks `__cap`  | Unconditional, no check needed       |
| Free call (locals)       | Unconditional, runtime checks `__cap`  | Conditional on ownership flag        |
| Struct fields            | Fields freed independently             | Heap promotion, unconditional free   |
| External C types         | Can't add `__cap` field                | Works - no struct modification       |

---

## Memory Function Generation

A phase before the type checker generates `__free_T` and `__clone_T` functions for all heap types. This keeps the "everything is functions" principle and requires no special codegen.

### Native Heap Types

Declared in stdlib with C runtime implementations:
```mml
fn __free_String(s: String): Unit = @native;
fn __free_Buffer(b: Buffer): Unit = @native;
fn __free_IntArray(a: IntArray): Unit = @native;
fn __free_StringArray(a: StringArray): Unit = @native;

fn __clone_String(s: String): String = @native;      // strdup equivalent
fn __clone_Buffer(b: Buffer): Buffer = @native;      // malloc + memcpy
fn __clone_IntArray(a: IntArray): IntArray = @native;
fn __clone_StringArray(a: StringArray): StringArray = @native;
```

### MML Structs

Generated automatically by the phase:
```mml
struct User { name: String, role: String }

// Generated
fn __free_User(u: User): Unit =
  __free_String u.name;
  __free_String u.role

fn __clone_User(u: User): User =
  __mk_User (__clone_String u.name) (__clone_String u.role)
```

Nested structs work naturally via recursion:
```mml
struct Company { owner: User, address: String }

// Generated
fn __free_Company(c: Company): Unit =
  __free_User c.owner;
  __free_String c.address

fn __clone_Company(c: Company): Company =
  __mk_Company (__clone_User c.owner) (__clone_String c.address)
```

### Phase Responsibilities

1. Walk type declarations marked `@native[mem=heap]`
2. For native types: verify `__free_T` and `__clone_T` exist in stdlib
3. For MML structs: generate both functions, calling the appropriate `__free_*`/`__clone_*` on each heap-typed field

### Benefits

1. **OwnershipAnalyzer stays simple**: Just inserts `App(Ref("__free_T"), ...)` or `App(Ref("__clone_T"), ...)` - no struct layout knowledge
2. **Codegen unchanged**: Sees regular function calls, no destructor special case
3. **Recursive handling explicit**: The AST shows exactly what gets freed/cloned
4. **Single source of truth**: One phase owns all generation logic

### When Clones Are Inserted

- Function returns a heap type and a branch contains a static value
- Struct field assignment from a static value (fields are always owned)

### When Clones Are NOT Inserted

- Local variables with mixed ownership (use sidecar instead)
- Values already heap-allocated

---

## References

- `docs/brainstorming/mem/1-simple-mem-prototype.md` - Original design
- `context/specs/mem-plan.md` - Implementation plan
- `context/tracking.md` - Phase 2.5 `__cap` implementation details

---

## Implementation Progress (2026-02-03)

### Completed

**Phase A+B: Type-Level Memory Attributes + Replace hardcoded heapTypes**
- Added `memEffect: Option[MemEffect]` field to `NativePrimitive`, `NativePointer`, `NativeStruct` in `ast/types.scala`
- Extended parser in `parser/types.scala` to handle `@native[mem=heap]` syntax
- Updated stdlib type definitions in `semantic/package.scala` with `memEffect = Some(MemEffect.Alloc)` for String, Buffer, IntArray, StringArray
- Replaced hardcoded `heapTypes` set with `isHeapType()` in `OwnershipAnalyzer.scala` that queries type attributes dynamically
- Added `findTypeByName()` helper to resolve types by name across different ID formats
- All 210 tests pass

**Phase C: Memory Function Generation**
- Added `__clone_String`, `__clone_Buffer`, `__clone_IntArray`, `__clone_StringArray` to C runtime (`mml_runtime.c`)
- Added clone function declarations to stdlib (`semantic/package.scala`)
- Created `MemoryFunctionGenerator.scala` - generates `__free_T` and `__clone_T` for user structs with heap fields
- Added `MemoryFunctionGenerator` to `SemanticStage.scala` pipeline (after TypeChecker, before resolvables-indexer)
- All 210 tests pass, all 7 benchmarks compile, 0 memory leaks

**Phase D: Clone Insertion for Returns (COMPLETED 2026-02-03)**
- Added `wrapWithClone()` helper function in `OwnershipAnalyzer.scala`
- Added `promoteStaticBranchesInReturn()` - detects mixed allocation in conditionals and wraps static branches with `__clone_T`
- Updated Lambda case in `analyzeTerm` to call clone insertion on function bodies
- Verified: All 210 tests pass, all 7 benchmarks compile, 0 memory leaks in mixed_ownership_test
- LLVM IR shows `__clone_String` being called in static branches of mixed-allocation returns

### Remaining

**Phase E: Sidecar Booleans for Local Mixed Ownership**
- Track ownership at compile time for local conditionals
- Generate `__owns_<varname>` sidecar booleans
- Conditional free at scope end based on sidecar

**Phase F: Remove `__cap` Infrastructure** (final cleanup after D+E proven)
- Remove `__cap` field from struct definitions in `semantic/package.scala`
- Update C runtime to remove `__cap` checks in free functions
- Remove `__cap = -1` from string literals in `codegen/emitter/Literals.scala` and `Module.scala`

### Key Files Modified

| File | Changes |
|------|---------|
| `ast/types.scala` | Added `memEffect` to NativeType case classes |
| `parser/types.scala` | Parse `[mem=heap]` in native type syntax |
| `semantic/package.scala` | Added `memEffect` to stdlib types, added clone function declarations |
| `semantic/OwnershipAnalyzer.scala` | Replaced `heapTypes` with `isHeapType()`, added clone insertion for returns |
| `semantic/MemoryFunctionGenerator.scala` | NEW: generates `__free_T`/`__clone_T` for user structs |
| `compiler/SemanticStage.scala` | Added MemoryFunctionGenerator to pipeline |
| `mml_runtime.c` | Added `__clone_*` C implementations |
