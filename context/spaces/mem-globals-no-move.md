# Memory Ownership: Globals Are Borrow-Only (No Move)

## Problem

Constructor parameters for heap fields are ownership sinks. For local values, this means
passing an owned heap value to a consuming constructor parameter moves it.

The ambiguity is top-level bindings (module globals):
- What does it mean to "move" a global?
- Is the global invalidated for all later uses?
- How does that interact with module initialization and cross-function references?

Treating globals as movable introduces temporal global state and unclear semantics.

## Concrete Example

Source file: `mml/samples/person-struct-borrow-global.mml`

```mml
struct Person {
  name: String,
  age: Int
};

let name = "fede";
let p = Person name 25;

fn main() =
  println name;
  println ("Name: " ++ p.name);
  println ("Age: " ++ (int_to_str p.age))
;
```

This program should compile under the global-borrow rule.

## Rules

1. Top-level bindings are borrow-only by default.
2. Top-level bindings are not movable.
3. Passing a top-level heap binding to a consuming parameter does not invalidate the top-level name.
4. Constructor sink semantics still apply for locals:
   local owned heap values passed to consuming params are moved.
5. Literal promotion remains allowed when needed to satisfy owning fields.
6. If a true owned copy is required from a global, it must be explicit (clone/promotion),
   not implicit move of the global binding itself.

## Expected Outcome

- `let p = Person name 25` is valid when `name` is top-level.
- `println name` after that remains valid.
- The analyzer must still reject use-after-move for local bindings moved into constructors.
