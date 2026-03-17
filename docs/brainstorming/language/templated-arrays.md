# Brainstorming: templated arrays

Status: Draft
Goal: Implement a templating mechanism for arrays while we don't have a polymorphic typechecker.

---

## 1. motivation

Right now MML uses two predefined monomorphic arrays (`IntArray`, `StringArray`).

Problems:
- Records are coming soon, but there's no polymorphic typechecker yet
- Writing bespoke containers for each element type is painful
- Need `Array Float` for FFT and other numeric work
- Arrays (like strings and records) are core PL concepts that deserve first-class support

---

## 2. proposed syntax

Use higher-kinded syntax via juxtaposition (like function application):

```mml
let xs: Array Int = [1, 2, 3];
let ys: Array Float = [1.0, 2.0, 3.0];
let zs: Array MyRecord = [...];
```

---

## 3. implementation strategy

### 3.1 uniform runtime representation

Single native `Array` type at runtime:

```mml
type Array = @native { length: Int, data: Ptr };
```

The `Array T` syntax exists only so codegen knows element type.

### 3.2 generic runtime functions

```c
mkArray(len: Int, elemSize: Int, elemAlign: Int) -> Array
array_data(a: Array) -> Ptr
array_len(a: Array) -> Int
```

### 3.3 codegen-driven element access

When codegen sees `Array T`:
1. Compute `elemSize` and `elemAlign` from `T`
2. `ptr = array_data(a)`
3. `typedPtr = bitcast ptr -> T*`
4. GEP + load/store

This eliminates the current `unsafe_ar_int_get` / `ar_str_get` zoo.

### 3.4 monomorphization cache

When codegen encounters `Array T`:
- Check if we've already synthesized accessors for `T`
- If not, generate monomorphic versions and add to map
- Avoids duplicate generation

Cache stores layout info per `T`: size, align, trivial-copy flag.

---

## 4. literal optimization

For `[1, 2, 3] : Array Int`:
1. Emit constant blob `[3 x i64]`
2. Allocate `3 * 8` bytes (aligned 8)
3. `llvm.memcpy` blob into data
4. Set length

For records: same if trivially copyable, otherwise fall back to per-element stores.

---

## 5. benefits

1. Single implementation - no more per-type array boilerplate
2. Optimal codegen - since we know structure and sizes, can use intrinsics
3. Literal syntax - can render directly to LLVM array syntax
4. Future-proof - easy migration path when polymorphic typer arrives

---

## 6. open questions

- Syntax for array literals: `[1, 2, 3]` vs `Array [1, 2, 3]`?
- Bounds checking strategy?
- Mutable vs immutable?
