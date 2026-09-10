# Partial-Application Ownership Model

This note records the ownership rules for generated partial-application values.
It is the design reference for S8.6.1b in `context/specs/unify-lambdas-plan.md`.

## Problem

A partial application (PAP) stores already-applied arguments and, for Direct callables,
any trailing operands needed to call the original entry later. Heap-typed payloads are
not scalars: storing them in a PAP env creates a lifetime question.

Implicit cloning is not an acceptable answer. A PAP must not silently manufacture
ownership. If a PAP owns a heap payload, that ownership must come from an explicit move
in the source-level ownership model.

## Core Rule

Each heap payload stored in a PAP env is either borrowed or owned.

- Borrowed payloads may be stored only when the PAP value cannot outlive the owner.
- Owned payloads may be stored only after an explicit ownership transfer.
- The compiler must reject cases it cannot prove safe.
- No implicit clone may be inserted to make an escaping PAP safe.

## Parameter Ownership

PAP payload ownership follows the original function parameter.

- A non-consuming parameter borrows its argument.
- A consuming `~` parameter moves its argument.
- Partial application must preserve that distinction.

For an already-applied argument:

```mml
fn f(s: String, n: Int): Unit = ...;;
let p = f s;
```

`s` is borrowed because `f` borrows `s`. The PAP may use that borrow only while `s`
is alive.

```mml
fn f(~s: String, n: Int): Unit = ...;;
let p = f s;
```

`s` is moved because `f` consumes `s`. If partial application over this shape is
accepted, the PAP env owns `s` and its destructor is responsible for freeing `s` if
the PAP is dropped before the remaining arguments are supplied.

## Escaping Borrowed PAPs

An escaping PAP must not contain borrowed heap payloads.

Rejected shape:

```mml
fn make(): Int -> Unit =
  let s = int_to_str 1;
  f s;
;
```

If `f` borrows `s`, then `f s` is a PAP containing a borrow of `s`. Returning that PAP
would let the borrow escape the scope that owns `s`.

Accepted shape:

```mml
pub fn main(): Unit =
  let s = int_to_str 1;
  let p = f s;
  p 0;
;
```

This is valid when the compiler can prove `p` does not escape the scope where `s` is
alive.

## Consuming Parameters

The current rule that rejects partial application when any remaining parameter is
consuming stays in place.

```mml
fn consume(a: Int, ~s: String): Unit = ...;;
let p = consume 1; // rejected: remaining parameter is consuming
```

This is separate from already-applied consuming arguments. An already-applied consuming
argument moves into the generated PAP environment:

```mml
fn consume_first(~s: String, n: Int): Unit = ...;;
let p = consume_first s; // explicit move into the PAP
```

The source binding is moved at PAP creation. If the PAP is dropped before full application,
the PAP env destructor frees the moved payload field. If the PAP is fully applied, the moved
payload is forwarded to the consuming callee and later PAP cleanup frees only the raw env.

## Direct Callables

This model is not lambda-capture-specific. For Direct PAPs, the generated env stores
call payloads for a later entry call. The same payload rules apply:

- applied borrowed heap arguments may not escape their owner;
- applied moved heap arguments are owned by the PAP env;
- Direct trailing operands that are borrowed heap values may not escape their owner;
- Direct trailing operands that are explicitly owned by the PAP env must be freed by
  the PAP env destructor.

The fact that the source lambda or function itself does not escape is not sufficient.
The generated PAP value may still escape.

## Implementation Task

S8.6.1b should replace clone-per-PAP with explicit ownership/lifetime enforcement.

Required work:

- Remove implicit clone insertion from Direct PAP creation.
- Classify each PAP env heap payload as borrowed or owned.
- Reject escaping PAPs that contain borrowed heap payloads.
- Keep non-escaping borrowed heap PAPs valid when the lifetime proof is available.
- Preserve the existing rejection for partial application with remaining consuming
  parameters.
- Move already-applied consuming heap arguments into the PAP and make the PAP env
  destructor free those owned payload fields exactly once.

Initial conservative implementation may reject all heap-borrow PAP payloads until a
non-escape proof is wired in. That is preferable to hidden cloning.

## Tests

Pin these cases:

- non-escaping PAP over borrowed heap argument is accepted when proven local;
- escaping PAP over borrowed heap argument is rejected;
- escaping Direct PAP over borrowed heap trailing payload is rejected;
- partial application with remaining consuming parameter remains rejected;
- moved already-applied heap arguments mark the source binding moved and the PAP env
  destructor frees the moved payload exactly once;
- no generated IR for PAP creation contains implicit heap clone calls.
