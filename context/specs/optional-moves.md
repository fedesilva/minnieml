# Closures: Optional moves, borrows by default

## Motivation

MML's current ownership model is rigid regarding closure captures. Capturing a heap-typed value (like a `String` or `Buffer`) currently triggers an eager **move**.

This leads to "parameter threading" in programs that share state across multiple local functions. For example, in `mml/samples/raytracer3.mml`, sibling helpers cannot capture shared heap values; they must receive them as explicit parameters to avoid invalidating the source for other functions.

We are moving to a **borrowing-by-default** model for captures. The `~` sigil will act as an explicit "move" operator when a closure needs to outlive its scope.

## Borrowing by default

Ownership should only transfer when explicitly requested. By default, capturing a value in a closure is a **borrow**.

- **No `~`**: Borrow. The closure holds a reference to the outer binding.
- **With `~`**: Move. Ownership transfers to the closure's environment; the source is invalidated.

This change mainly affects how lambdas interact with their environment.

### Borrow captures (Default)

When a closure captures a binding without `~`, it borrows from the enclosing scope.

```mml
let buf = mkBufferWithSize 1024;

// write_hello borrows 'buf'. It does not own it.
fn write_hello() =
  buffer_write buf "hello";
;

write_hello ();
flush buf; // 'buf' remains available.
```

Multiple local functions can capture the same value without conflict, and the outer scope remains responsible for deallocation.

### Escape restrictions

A closure that borrows from its environment cannot escape the scope of the borrowed value. The compiler prevents these closures from being returned from functions or stored in structs.

Escape analysis needs to be extended, we already detect borrows escaping (being returned).


---

## Moving with `~`

The `~` sigil is used to opt-in to ownership transfer in three contexts.

### 1. Move closures and inner functions

If a closure must escape (e.g., being returned or stored), it must own its captures. Prefixing a lambda literal with `~` or an inner function name with `~` moves all captured values into the closure's environment.

**Move lambdas (`~{ ... }`):**
```mml
fn make_logger(name: String): Unit -> Unit =
  // ~{} moves 'name' into the closure environment.
  ~{ println ("Logger: " ++ name) }
;
```

**Move inner functions (`fn ~name`):**
```mml
fn outer() =
  let s = readline();
  fn ~inner() = println s;; // s is moved into 'inner'
  inner ();
;
```

*Note: The `fn ~name` syntax is only valid for inner functions. It is not allowed for top-level functions.*
*Note: `~` on the literal or the function name does not affect arguments. Arguments still need to be annotated with `~` (e.g., `~x: String`) if they consume ownership.*

When values are moved into a closure:
- The outer bindings become `Moved` and unusable.
- The closure's destructor frees the captured values.
- The closure can escape its creation scope.

### 2. Call-site move

A caller can force a move even if the function parameter is non-consuming. This is useful for early cleanup or when a value is no longer needed in the caller's scope.

```mml
let s = readline();
process_string (~s); // 's' is moved here
```

### 3. Consuming parameters

Function signatures use `~` to declare that a parameter consumes its argument.

```mml
fn consume(~x: String): Unit = ...
```

---

## Escape analysis

The compiler enforces that borrowing closures do not outlive their captures by tracing their usage from the call site. A closure is considered to "escape" if it is:
- Returned from a function.
- Stored in a struct field.
- Passed to a function parameter that takes ownership.
- In general, if its ownership is transferred to a scope other than the one that owns its borrowed values.

Because escape analysis traces through callees, passing a borrowing closure to a function that only borrows it (and does not let it escape further) is safe and permitted. This mirrors the existing rules for borrowed values.

---

## Value types

Primitive types (Int, Float, Bool) are always copied. While `~` is allowed for consistency, it is a no-op for these types and does not restrict closure escape.

## Protocols and `clone`

`clone` and `~` are distinct operations. `clone` (via the `Clone` protocol) creates a new owned value, while `~` transfers an existing one. Users decide which is appropriate based on whether they need to retain the original value.

---

## Mental model

- **Local helpers:** Use the default (borrow). It is efficient and requires no extra syntax.
- **Escaping closures:** Prefix the lambda with `~{}` or inner function with `fn ~name` to move heap state into the environment so the closure becomes self-contained.
- **Explicit cleanup:** Use `~` at call sites or when passing to consuming parameters to transfer ownership and invalidate the local binding.
