# Optional Moves with `~`

## Core idea

`~` is the universal ownership-transfer sigil. It means "I'm moving this value."
Without `~`, values are borrowed. With `~`, ownership transfers to the receiver.

## Three contexts

### 1. Parameter declarations (existing)

```mml
fn foo(~x: String): Unit = ...
```

`~x` declares a consuming parameter. The function takes ownership and is
responsible for freeing `x`.

### 2. Call-site move (new)

```mml
fn foo(x: String): Unit = ...

let s = readline();
foo (~s)
```

Even though `foo` doesn't declare `~x`, the caller can force a move with `~s`.
After the call, `s` is Moved in the caller's scope. This is useful when the
caller knows it won't use `s` again and wants to avoid keeping it alive.

### 3. Capture-site move (new)

```mml
let name = readline();
let f = { println (~name) };
```

`~name` in the lambda body moves `name` into the closure's env struct. The env
destructor frees it. The closure can escape (be returned, stored, passed around).

Without `~`, captures borrow from the enclosing scope:

```mml
let name = readline();
let f = { println (name) };
f ();
```

`name` is borrowed — the closure holds a pointer into the enclosing scope. The
closure cannot escape. If it does, the compiler errors.

## Rules

### Borrow captures (no `~`)

- Closure holds a reference to the outer binding's storage
- No ownership transfer — outer scope still owns and frees the value
- Env destructor does NOT free borrowed fields
- Closure must not escape (not returned, not stored, not passed to opaque consumers)
- Compiler enforces non-escape statically

### Move captures (`~`)

- Value moves into the env struct — outer binding becomes Moved
- Env destructor frees the moved-in value
- Closure can escape freely (it owns its captures)
- Use-after-move rules apply in the outer scope

### Value-type captures (Int, Float, Bool)

- Always copied — no ownership concern
- `~` not needed or meaningful (value semantics)

## Escape analysis

A closure "escapes" if:
- It is returned from a function
- It is stored in a struct field
- It is passed as an argument to a function (conservative: unless the param
  is known to be non-escaping)

A first-pass rule: if the closure is let-bound and the binding is only called
(applied with `()`) within the same scope, it is non-escaping. Borrowed
captures are safe.

More refined analysis can come later (non-escaping function parameters,
lifetime tracking).

## Interaction with protocols

Once protocols exist:
- `Clone` protocol provides `clone x` syntax for explicit deep copy
- `Drop` protocol provides the destructor
- `clone` and `~` are orthogonal — `clone` creates a new owned value,
  `~` transfers ownership. The user decides when each is needed.
  No automatic cloning, ever.

## Alignment with the language

`~` already means ownership transfer in MML. Extending it to call sites and
captures keeps the vocabulary small and the mental model consistent:

- No `~` → borrow (temporary access, no ownership change)
- `~` → move (permanent transfer, source invalidated)
