# Unique Resource Types

## Status: Early Discussion

## Problem

Some types represent exclusive resources that must not be duplicated: file handles,
sockets, mutexes, database connections. The current ownership model doesn't distinguish
these from ordinary heap types -- nothing prevents a `Socket` from being borrowed
multiple times or cloned.

## Proposal

Add a `[mem=unique]` annotation for `@native` type declarations:

```mml
type Socket = @native[mem=unique, t=i32];
type FileHandle = @native[mem=unique] { fd: Int, mode: Int };
```

### Semantics

- A unique type **cannot be cloned**. No `__clone_T` is generated or expected.
- A unique type **cannot be borrowed** by default function parameters.
- Passing a unique value requires an explicit move via `~`:

```mml
fn close_socket(~sock: Socket): Unit = @native;

let s = open_socket 8080
close_socket ~s    // ownership transferred
// s is invalid here
```

- Attempting to pass a unique value without `~` is a compiler error:

```text
error: type Socket is unique and cannot be borrowed
  --> example.mml:5:14
  |
5 | some_function s
  |               ^ Socket requires explicit move (~s)
```

### Interaction with existing model

| Aspect | Heap types (current) | Unique types (proposed) |
|--------|---------------------|------------------------|
| Default passing | Borrow (zero-cost) | Error |
| Explicit `~` | Move (ownership transfer) | Move (ownership transfer) |
| Clone function | Generated/provided | None (forbidden) |
| Scope-end free | Yes, if owned | Yes, if owned |
| Use-after-move | Error | Error |

Unique types slot into the existing ownership analyzer with one additional check:
when a `Ref` to a unique-typed binding appears as a function argument, verify the
parameter is consuming (`~`). If not, emit an error.

### What this does NOT change

- Default semantics for heap types remain borrow-by-default.
- No new protocol/typeclass system required.
- No call-site `~` for non-unique types (caller doesn't choose clone vs move).
- `~` on declarations remains the mechanism for consuming parameters.

## Open Questions

1. **Struct fields with unique types.** Should `struct Session { sock: Socket }` be
   allowed? If so, `Session` itself becomes non-clonable (unique by propagation).
   The constructor would need `~` on the socket field. This has implications for
   `MemoryFunctionGenerator` -- it would skip `__clone_T` for such structs.

2. **Returning unique values.** A function returning a unique type transfers ownership
   to the caller, same as `[mem=alloc]` today. No special handling needed, but worth
   confirming the analyzer handles it.

3. **Unique in conditionals.** `let x = if cond then open_socket 80 else open_socket 90`
   -- both branches produce owned unique values. The witness boolean mechanism already
   handles mixed ownership; unique types should work the same way.

4. **Borrowing with guarantees.** Sometimes you want to *use* a socket without consuming
   it (e.g., `send socket data`). A future refinement could allow borrowing unique types
   in limited contexts (single use, no aliasing). For now, the simplest model is: unique
   values can only be moved or used in-place by the owner.
