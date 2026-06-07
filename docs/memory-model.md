# MML Memory Model

MML uses deterministic, compile-time memory management. There is no garbage collector
and no ambient reference counting. The compiler tracks ownership of heap-allocated
values and inserts cleanup calls for values that still belong to the current scope.

The model is affine: each owned value is used at most once. There are no lifetime
annotations, no borrow checker in the Rust sense, and no sigils for borrowing. Parameters
borrow by default. Ownership transfer requires an explicit `~`.

---

## Design principles

The ownership model exists to keep the language mechanically sympathetic. Allocation,
movement, destruction, and duplication should have a clear cost model.

- **Borrow by default.** Passing a value to an ordinary parameter does not transfer cleanup
  responsibility and does not copy heap data.
- **Move explicitly.** Passing a value to a consuming `~` parameter, returning it, storing it
  in an owned aggregate, or moving it into an owned closure environment transfers ownership.
  A move is not a deep copy.
- **Drop exactly once.** The owner of a heap value is responsible for destroying it exactly
  once. When ownership moves, drop responsibility moves with it.
- **Do not hide cloning.** A clone is the paid duplication path. It should be visible in the
  source-level ownership model rather than inserted to paper over unclear ownership.
- **Reject unclear ownership.** If the compiler cannot prove that a borrow stays within the
  owner's lifetime, the program is rejected.

Currently, user-accessible clone syntax and clone protocols are not implemented. The compiler
therefore inserts clones at a small number of boundaries where there is no source-level way to
ask for a clone yet. This is an implementation gap, not a design principle. The intended protocol
direction is sketched in [Memory Model Evolution](brainstorming/mem/mem-evolution.md).

---

## Core invariant

- Every heap value has exactly one owner at a time.
- Owned values are destroyed exactly once.
- Borrowed values are never destroyed by the borrower.
- Ownership may be transferred (moved) but not duplicated unless it goes through the clone path.

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
| **Global**   | A module-level binding or subvalue with static lifetime; never destroyed locally. |

---

## Globals

Globals have static lifetime from the ownership model's point of view.

- A global heap value may be borrowed.
- A global heap value may be used at a consuming boundary.
- Consuming a global does not invalidate the global.
- Local cleanup never frees global-backed storage.

When a global-backed value is stored inside an owned aggregate, the aggregate is still an
ordinary owned local value, but its global-backed fields are not released by the aggregate's
destructor.

Currently, global-origin ownership is only partially represented. The compiler still uses magic
clones for globals at some consuming boundaries because user-facing clone protocols and
global-aware aggregate destruction are not implemented yet. This is a gap in the implementation,
not the desired ownership model.

---

## Non-consuming parameters (default)

Function parameters are non-consuming by default. The callee may use the value, but it
does not become responsible for destroying it.

```mml
fn greet(name: String): Unit =
  println ("Hello " ++ name);
;
```

`name` is borrowed. The caller retains ownership.

---

## Consuming parameters (`~`)

The `~` sigil declares that a parameter consumes its argument:

```mml
fn consume(~s: String): Unit =
  println s;
;
```

Calling `consume s` transfers ownership of `s`. After the call, `s` is `Moved` and cannot
be used again.

A value passed to a consuming parameter must:

1. Be owned.
2. Not be used along any later control-flow path.

Literals and globals have no local owner to transfer. When a consuming boundary needs a fresh
owned value from one of them, the compiler currently inserts a clone because explicit user-level
clone syntax is not implemented yet.

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
unless explicitly moved or duplicated through the clone path.

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

- Returning an owned value moves ownership to the caller.
- Values passed to consuming parameters move ownership to the callee.
- Values moved into owned aggregates or owned closure environments become the responsibility of
  that aggregate or environment.
- Moved values are no longer the responsibility of the original binding.

Returning a value is a move: local cleanup skips the
returned value because drop responsibility has moved to the caller.

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

- **Owned** -> moved
- **Literal** -> currently cloned to create an owned field value
- **Global** -> accepted without invalidating the global; currently cloned at some boundaries
- **Borrowed** -> compile error

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
  ;
;
```

`s` is always `Owned`.

### Mixed ownership

```mml
let s =
  if flag then
    int_to_str a;
  else
    "none";
  ;
;
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

Returning an owned heap value moves ownership to the caller.

Returning a borrowed value from a heap-returning function is invalid:

```mml
fn identity(s: String): String = s;   // error
```

Fix by consuming (`~s`) so the function receives ownership, or by explicitly duplicating the
value once user-facing clone support exists.

### Mixed return branches

If a function returns a heap type, every return path must produce ownership for the caller.
Currently, when only some branches allocate, the compiler clones non-allocating literal/global
branches so the returned value is owned on every path. This is another temporary clone insertion
boundary until clone protocols are user-accessible.

---

## Generated memory functions

For each struct with heap fields, the compiler generates:

- A destructor that frees each heap field
- A clone function that deep-copies heap fields

Native types implement their own free/clone in the runtime.

Generated clone functions are currently compiler-facing helpers. The intended direction is for
cloneability to become a protocol-level capability with a visible source-level clone operation.

---

## Closures

A **non-capturing function value** is represented as the same fat pointer shape used for
closures: a function entry pointer plus a null environment pointer. The entry pointer uses the
closure-call ABI and accepts the environment pointer as its final argument, even though it ignores
that value.

A **capturing lambda** (closure) is represented as a fat pointer with a non-null environment
pointer. What matters is how that closure captures.

Direct calls to top-level functions use the plain direct-call ABI. When a named function is used as
a first-class value, codegen creates or reuses a closure-entry wrapper whose body calls the plain
direct symbol.

### Capture semantics

Captured bindings are written into the environment when the lambda is created.

For value types (`Int`, `Float`, `Bool`, etc.), capture is just a copy. The original
binding stays usable.

```mml
fn runWith(a: Int, x: Int): Int =
  let addA = { y: Int -> y + a };   // a is copied into the closure env
  addA x;
;
```

Heap captures split into two cases.

### Borrow closures (default)

Borrow is the default:

- lambda literals use `{ ... }`
- local inner functions use `fn name(...) = ...`

Borrow closures keep their environment on the stack.

- Capturing an **owned** heap binding borrows it; the outer binding stays `Owned`.
- Capturing a **borrowed**, **literal**, or **global** heap binding is also allowed.
- Inside the lambda body, captured heap fields are treated as borrowed.
- Multiple borrow closures may share the same enclosing heap binding.

That stack lifetime is the key restriction: a borrow-capturing closure cannot escape its
defining scope. Returning one is rejected.

```mml
fn withPrefix(prefix: String) =
  let printPrefixed = {
    println (prefix ++ "!");
  };
  printPrefixed ();
;
```

### Move closures (`~`)

Prefix the closure with `~` when it should own its heap captures:

- lambda literals use `~{ ... }`
- local inner functions use `fn ~name(...) = ...`

Move closures allocate their environment on the heap, and the closure value owns it.

- Capturing an **owned** heap binding moves it into the environment.
- The original outer binding becomes `Moved`.
- Capturing a **borrowed** heap binding is rejected.
- Heap literals are currently cloned into the environment when an owned capture is needed.
- Inside the lambda body, captured heap fields are treated as borrowed from the
  environment.

```mml
fn makeGreeter(name: String): Unit -> Unit =
  ~{
    println ("Hello, " ++ name);
  };
;
```

### Closure ownership

A move-capturing closure is an owned heap value. The binding that receives it is
responsible for freeing it, just like any other owned heap object. It can also be passed
to consuming parameters or returned to move ownership to the caller.

```mml
fn makeAdder(a: Int): Int -> Int =
  ~{ x: Int -> x + a };
;

fn main() =
  let add5 = makeAdder 5;    // add5 owns the move closure
  let r = apply add5 37;     // add5 is borrowed by apply
  println (int_to_str r)
;                             // add5 is freed here
```

### Environment destruction

When a capturing closure goes out of scope, its environment is destroyed automatically.

For a borrow closure, that just ends the lifetime of the stack environment.

For a move closure, the heap environment is released when the closure goes out of scope.
If it holds heap captures, those are destroyed first.

### Capture style

Closures capture referenced bindings implicitly from the body. The capture mode is
chosen for the whole closure:

- default borrow capture with `{ ... }` / `fn name(...)`
- explicit move capture with `~{ ... }` / `fn ~name(...)`

---

## Partial applications

A partial application is a function value that stores the arguments already supplied and waits
for the remaining arguments. If a stored payload has a heap type, the PAP environment must know
whether that payload is borrowed or owned.

The same ownership rules apply to PAP payloads as to ordinary values:

- An already-applied non-consuming parameter stores a borrow.
- An already-applied consuming `~` parameter stores ownership only through an explicit move.
- Borrowed heap payloads may be stored only when the PAP cannot outlive the owner.
- An escaping PAP must not contain borrowed heap payloads.
- Owned heap payloads are destroyed by the PAP environment if the PAP is dropped before it is
  fully applied.
- Partial application is rejected when any remaining unapplied parameter is consuming.

This rule applies equally to ordinary callables and direct-callable lowering. The source callable
may be direct and non-escaping while the generated PAP value itself escapes; ownership is checked
on the generated value that actually stores the payloads.

Implementation note: PAP heap-payload ownership enforcement is currently being implemented. The
rules above are the model; this note exists while implementation catches up.

---

## Compile-time errors

| Error                         | Cause                                        |
|-------------------------------|----------------------------------------------|
| Use after move                | Using a binding after ownership transfer     |
| Consuming param not last use  | Value used after being consumed              |
| Borrow escape via return      | Returning a borrowed value as owned          |
| Borrowed to consuming param   | Passing a borrowed value to a `~` parameter  |
