# MML Memory Model

MML uses deterministic, compile-time memory management. There is no garbage collector
and no runtime reference counting. The compiler tracks ownership of heap-allocated
values and inserts `free` and `clone` calls automatically.

The model is affine: each owned value is used at most once. There are no lifetime
annotations, no borrow checker in the Rust sense, and no sigils for borrowing. Parameters
borrow by default. Ownership transfer requires an explicit `~`.

---

## Core invariant

- Every heap value has exactly one owner at a time.
- Owned values are destroyed exactly once.
- Borrowed values are never destroyed by the borrower.
- Ownership may be transferred (moved) but not duplicated unless explicitly cloned.

---

## Heap types

A type is a **heap type** if values of that type require destruction.

- **Native types** that allocate memory (e.g. `String`, `Buffer`, `IntArray`,
  `StringArray`, `FloatArray`).
- **User-defined structs** that contain at least one heap-typed field (transitively).

Primitive types (`Int`, `Float`, `Bool`, `Unit`) and structs with only primitive fields
are value types and require no ownership tracking.

---

## Ownership states

Every binding tracked by the ownership analyzer is in one of these states:

| State        | Meaning |
|--------------|---------|
| **Owned**    | The binding is responsible for destroying the value. |
| **Moved**    | Ownership has been transferred; further use is invalid. |
| **Borrowed** | The binding refers to a value owned elsewhere and must not destroy it. |
| **Literal**  | A compile-time constant; never destroyed. |
| **Global**   | A module-level binding with static lifetime; never destroyed locally. |

---

## Non-consuming parameters (default)

Function parameters are non-consuming by default. The callee may use the value, but it
does not become responsible for destroying it.

```mml
fn greet(name: String): Unit =
  println ("Hello " ++ name);
```

`name` is borrowed. The caller retains ownership.

---

## Consuming parameters (`~`)

The `~` sigil declares that a parameter consumes its argument:

```mml
fn consume(~s: String): Unit =
  println s;
```

Calling `consume s` transfers ownership of `s`. After the call, `s` is `Moved` and cannot
be used again.

A value passed to a consuming parameter must:

1. Be owned.
2. Not be used along any later control-flow path.

If the argument is a **literal** or **global**, the compiler inserts a clone so the callee
receives an owned value.

---

## Ownership acquisition

A binding becomes `Owned` when it is bound to the result of an allocating expression:

- Calls to allocating functions (`int_to_str`, `readline`, string concatenation, etc.)
- Struct construction where the struct has heap fields
- Conditionals where at least one branch allocates

```mml
let s = int_to_str 42;   // Owned
let u = User name role;  // Owned
let x = "hello";         // Literal
```

Not all heap-typed expressions produce new ownership. Borrowed inputs remain borrowed
unless explicitly moved or cloned.

---

## Struct move semantics

Binding a struct with heap fields transfers ownership rather than copying:

```mml
let a = User name role;
let b = a;   // a is Moved, b is Owned
```

This ensures a single owner for the struct and prevents double frees.

---

## Scope-end cleanup

At the end of a scope, the compiler inserts `free` calls for owned bindings that do not
escape. If the scope produces a result, that result is captured first.

```text
Source:
let s = int_to_str 42;
println s

Effect:
s is freed after println returns
```

---

## Escaping values

- Returned values transfer ownership to the caller and are not freed locally.
- Values passed to consuming parameters transfer ownership to the callee.
- Moved values are no longer the responsibility of the original binding.

---

## Temporary values

Intermediate allocating expressions are turned into temporaries and freed after use:

```text
Source:
println ((int_to_str n) ++ (int_to_str n))

Effect:
A = int_to_str n
B = int_to_str n
R = A ++ B
free A
free B
```

If a temporary is passed to a consuming parameter, ownership transfers and it is not
freed at the call site.

---

## Struct constructors

Constructors of structs with heap fields consume those fields:

```mml
struct User { name: String, role: String };
```

You can think of that as:

```mml
fn User(~name: String, ~role: String): User
```

What happens to each argument depends on its state:

- **Owned** → moved
- **Literal** → cloned
- **Global** → cloned
- **Borrowed** → compile error

---

## Conditional ownership

When ownership depends on control flow, the compiler still makes sure destruction happens
exactly once.

### Both branches allocate

```mml
let s =
  if flag then
    int_to_str a;
  else
    int_to_str b;
  ;;
```

`s` is always `Owned`.

### Mixed ownership

```mml
let s =
  if flag then
    int_to_str a;
  else
    "none";
  ;;
```

`s` may or may not own a value. The compiler tracks that and frees it only when needed.

### Conditional consumption

```mml
let s = int_to_str 1;

if flag then
  consume s;
else
  println s;
;
```

In the non-consuming branch, the compiler inserts a `free` so the value is always
destroyed exactly once.

---

## Return values and borrow escape

Returning a heap value transfers ownership to the caller.

Returning a borrowed value from a heap-returning function is invalid:

```mml
fn identity(s: String): String = s;   // error
```

Fix by consuming (`~s`) or cloning.

### Mixed return branches

If a function returns a heap type and only some branches allocate, the compiler clones
the non-allocating branches so the returned value is owned on every path.

---

## Generated memory functions

For each struct with heap fields, the compiler generates:

- A destructor that frees each heap field
- A clone function that deep-copies heap fields

Native types implement their own free/clone in the runtime.

---

## Closures

A **non-capturing lambda** is a plain function pointer. It is a value type and requires
no ownership tracking or deallocation.

A **capturing lambda** (closure) allocates an environment struct on the heap to store
captured values. The closure value itself — a fat pointer containing the function pointer
and a pointer to the environment — is a heap type.

### Capture semantics

Captured bindings are copied into the environment struct at the point the lambda is
created. For value types (Int, Float, Bool, etc.) this is a plain copy; the original
binding remains usable.

```mml
fn makeAdder(a: Int): Int -> Int =
  { x: Int -> x + a }   // a is copied into the closure env
;
```

### Closure ownership

A capturing lambda is `Owned` by the binding it is assigned to. Like any owned heap
value, it is freed when it goes out of scope, can be passed to consuming parameters,
and can be returned to transfer ownership to the caller.

```mml
fn main() =
  let add5 = makeAdder 5;    // add5 owns the closure
  let r = apply add5 37;     // add5 is borrowed by apply
  println (int_to_str r)
;                             // add5 is freed here
```

### Environment destruction

Each closure environment has a destructor embedded in the environment struct itself.
When the closure is freed, the runtime dispatches to the correct destructor
automatically. For closures that capture only value types, the destructor simply
releases the environment allocation.

### Current limitation

Closures cannot yet capture heap-typed values (String, structs with heap fields). Pass
heap values as explicit function parameters instead of capturing them.

---

## Compile-time errors

| Error | Cause |
|-------|-------|
| Use after move | Using a binding after ownership transfer |
| Consuming param not last use | Value used after being consumed |
| Borrow escape via return | Returning a borrowed value as owned |
| Borrowed to consuming param | Passing a borrowed value to a `~` parameter |
