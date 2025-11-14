# Codegen Strategy for Closures

This document specifies the code generation strategy for implementing partially applied functions (closures) in the MinnieML compiler.

The core strategy is that a partial application creates a **closure**, which is a struct containing a function pointer and an environment. The function pointer always points to a specialized, executable **thunk**. The environment contains all arguments captured so far.

---

## 1. Closure Representation and ABI

### 1.1. The Closure Struct

A closure value at the LLVM level is a **`%Closure*`** (pointer to a closure struct):

```llvm
%Closure = type { i8*, i8* }
```

* `i8*` (field 1): type-erased pointer to the executable thunk function.
* `i8*` (field 2): type-erased pointer to the environment struct holding captured arguments.

### 1.2. Application Binary Interface (ABI)

An **environment-first ABI** is used for all closure calls.

1. **Thunk signature**:

   ```llvm
   define %Closure* @thunk_expecting_B(i8* %env, B %new_arg) { ... }
   ```

2. **Thunk implementation:** `%env` is immediately `bitcast` to the concrete environment.

3. **Call site:**

   ```llvm
   ; %p is a %Closure*
   %fn_slot  = getelementptr %Closure, %Closure* %p, i32 0, i32 0
   %env_slot = getelementptr %Closure, %Closure* %p, i32 0, i32 1

   %fn_i8  = load i8*,  i8**  %fn_slot
   %env_i8 = load i8*,  i8**  %env_slot

   %fn = bitcast i8* %fn_i8 to %Closure* (i8*, B)*
   %p2 = call %Closure* %fn(i8* %env_i8, B %arg)
   ```

---

## 2. Environment and Memory Management

### 2.1 Environment Layout

Environments contain all free variables of the function, including:

* arguments captured in previous partial applications
* lexically captured variables from outer scopes

The compiler uses **environment flattening**: every partial application allocates a new struct with all previous captures **plus** the new argument.

### 2.2 Memory Management

All environments are heap-allocated:

```llvm
declare i8* @mml_alloc(i64)
```

A thin implementation lives in the shared runtime (`mml_runtime.c`): `mml_alloc` simply forwards to `malloc` for now,
giving us a stable symbol to swap out once we add a proper region/GC allocator.

A full memory‑management strategy (RC, GC, or regions) will be added later.

---

## 3. Thunk Generation

For a function of arity **N**, the compiler generates **N–1 thunks**.

* `@thunk_1`: captures the first argument and returns a closure with `@thunk_2`.
* ...
* `@thunk_{N-1}`: receives the final argument and calls the fully saturated function.

---

## 4. Code Generation Walkthrough

Assume:

```llvm
R @func(A, B, C)
```

### 4.1 Step 1 — `let p1 = func c`

```llvm
%Closure = type { i8*, i8* }
%Env_1   = type { C }

; allocate env
%env_size_1 = ptrtoint %Env_1* getelementptr(%Env_1, %Env_1* null, i32 1) to i64
%env_raw_1  = call i8* @mml_alloc(i64 %env_size_1)
%env1       = bitcast i8* %env_raw_1 to %Env_1*

; store c
%c_slot = getelementptr %Env_1, %Env_1* %env1, i32 0, i32 0
store C %c_val, C* %c_slot

; allocate closure
%cl_size = ptrtoint %Closure* getelementptr(%Closure, %Closure* null, i32 1) to i64
%cl_raw  = call i8* @mml_alloc(i64 %cl_size)
%p1      = bitcast i8* %cl_raw to %Closure*

%fn_ptr_slot  = getelementptr %Closure, %Closure* %p1, i32 0, i32 0
%env_ptr_slot = getelementptr %Closure, %Closure* %p1, i32 0, i32 1

%thunk_ptr = bitcast %Closure* (i8*, B)* @func_thunk_1 to i8*
store i8* %thunk_ptr, i8** %fn_ptr_slot
store i8* %env_raw_1, i8** %env_ptr_slot
```

### 4.2 Step 2 — `let p2 = p1 d`

```llvm
%Env_2 = type { C, B }

define %Closure* @func_thunk_1(i8* %env, B %d_val) {
entry:
  ; unpack old env
  %env1 = bitcast i8* %env to %Env_1*
  %c_ptr = getelementptr %Env_1, %Env_1* %env1, i32 0, i32 0
  %c_val = load C, C* %c_ptr

  ; allocate Env_2
  %env_size_2 = ptrtoint %Env_2* getelementptr(%Env_2, %Env_2* null, i32 1) to i64
  %env_raw_2  = call i8* @mml_alloc(i64 %env_size_2)
  %env2       = bitcast i8* %env_raw_2 to %Env_2*

  ; store captures
  %c_slot_2 = getelementptr %Env_2, %Env_2* %env2, i32 0, i32 0
  store C %c_val, C* %c_slot_2
  %d_slot_2 = getelementptr %Env_2, %Env_2* %env2, i32 0, i32 1
  store B %d_val, B* %d_slot_2

  ; allocate p2
  %cl_size = ptrtoint %Closure* getelementptr(%Closure, %Closure* null, i32 1) to i64
  %cl_raw  = call i8* @mml_alloc(i64 %cl_size)
  %p2      = bitcast i8* %cl_raw to %Closure*

  %fn_slot_2  = getelementptr %Closure, %Closure* %p2, i32 0, i32 0
  %env_slot_2 = getelementptr %Closure, %Closure* %p2, i32 0, i32 1

  %thunk2_ptr = bitcast R (i8*, A)* @func_thunk_2 to i8*
  store i8* %thunk2_ptr, i8** %fn_slot_2
  store i8* %env_raw_2,  i8** %env_slot_2

  ret %Closure* %p2
}
```

### 4.3 Step 3 — `let result = p2 e`

```llvm
define R @func_thunk_2(i8* %env, A %e_val) {
entry:
  %env2 = bitcast i8* %env to %Env_2*

  %c_ptr = getelementptr %Env_2, %Env_2* %env2, i32 0, i32 0
  %c_val = load C, C* %c_ptr
  %d_ptr = getelementptr %Env_2, %Env_2* %env2, i32 0, i32 1
  %d_val = load B, B* %d_ptr

  %result = call R @func(A %e_val, B %d_val, C %c_val)
  ret R %result
}
```

---

## 5. Additional Call Scenarios

### 5.1 Direct saturated calls

If a call is fully saturated (`func a b c`), the closure mechanism is bypassed and a direct call is emitted.

### 5.2 Multi-argument application

Expressions like `func c d` desugar to `(func c) d`, and are lowered as a chain of unary applications.

Over-application is currently desugared the same way.
