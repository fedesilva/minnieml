# Memory Ownership: Globals Are Borrow-Only (No Move)

## Problem

Constructor parameters for heap fields are ownership sinks. For local values, this means
passing an owned heap value to a consuming constructor parameter moves it.

Top-level bindings are trickier:
- What does it mean to "move" a global value?
- If moved once, is the global invalid forever after?
- How should that interact with global initialization and later references?

Treating globals as movable introduces temporal global state and unclear semantics.

## Current Finding (2026-02-18)

The sample compiles today, but not because the compiler inserts a clone at the top-level
constructor call.

Root cause: during ownership analysis, top-level refs used from another top-level/member body
are untracked in that member-local scope, and the consuming-param check currently accepts
untracked refs.

Evidence is captured below in this document.

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

This program currently compiles, but the acceptance path is accidental (untracked fallback), not
an intentional global borrow-only rule.

## Evidence (Captured Excerpts)

These excerpts were captured from compiler outputs during this investigation and are stored here
to avoid relying on volatile build artifacts.

### AST: constructor call uses `name` directly

```text
Ref Person
  resolvedId: PersonStructBorrowGlobal::bnd::__mk_Person
arg:
  Expr
    Ref name
      resolvedId: PersonStructBorrowGlobal::bnd::name
```

There is no `__clone_String` in this call path.

### LLVM IR: global init loads `name` and calls constructor

```text
@personstructborrowglobal_name = global %struct.String { i64 4, ptr @str.0 }
@personstructborrowglobal_p = global %struct.Person zeroinitializer

define internal void @_init_global_personstructborrowglobal_p() {
entry:
  %0 = load %struct.String, %struct.String* @personstructborrowglobal_name
  %1 = call %struct.Person @personstructborrowglobal___mk_Person(%struct.String %0, i64 25)
  store %struct.Person %1, %struct.Person* @personstructborrowglobal_p
  ret void
}
```

### AST: clone exists inside generated `__clone_Person` (different path)

```text
prot Bnd __clone_Person [fn unary ...]
...
Ref __clone_String
  resolvedId: stdlib::bnd::__clone_String
arg:
  Ref name
    qualifier:
      Ref s
```

## Root Cause (Analyzer)

1. Untracked refs are accepted for consuming params.

Reference:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala:681`

In `handleConsumingParam`:
- `Owned` => move
- `Moved` => use-after-move
- `Borrowed` => error
- `case _` => allowed

2. Each member starts analysis with a fresh scope.

Reference:
- `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala:1323`

Effect:
- While analyzing top-level `p = Person name 25`, `name` from another top-level binding is not
  seeded into ownership state and can fall into the allowed untracked case.

## Rules

1. Top-level bindings are borrow-only by default.
2. Top-level bindings are not movable.
3. Passing a top-level heap binding to a consuming parameter does not invalidate the top-level name.
4. Constructor sink semantics still apply for locals:
   local owned heap values passed to consuming params are moved.
5. Literal promotion remains allowed when needed to satisfy owning fields.
6. If a true owned copy is required from a global, it must be represented explicitly in semantics
   (source-level explicit clone, or a documented compiler rewrite rule), never via implicit
   untracked fallback.

## Expected Outcome

- `let p = Person name 25` may remain valid under the global-borrow rule, but validation must come
  from explicit semantics (tracked global borrow behavior), not from missing ownership state.
- `println name` after that remains valid.
- The analyzer must still reject use-after-move for local bindings moved into constructors.

## Required Fix Direction

1. Model top-level bindings explicitly in ownership analysis (at least as borrow-only provenance).
2. Remove accidental acceptance of consuming args through the untracked fallback path.
3. Decide and document one explicit rule for globals passed to consuming params:
   - strict rejection unless explicitly cloned, or
   - compiler-inserted clone for globals under a documented rule.
