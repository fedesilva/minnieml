# Owned String Literals

## Why

The ownership analyzer puts `LiteralString` into a special `Literal` state, separate
from `Owned`. This exists because string literals are emitted in IR as
stack-initialized arrays rather than heap values, and the analyzer needs to suppress
`free` for them. The codegen detail surfaces as a semantic state, and that produces a
bug.

`mml/samples/unique-should-complain.mml`:

```mml
pub fn main() =
  let a = "String!";
  let b = a;       // a -> Literal, so this is not a move
  println a;       // accepted today; should be use-after-move
;
```

The same shape with an allocating RHS (`let a = int_to_str 42`) is rejected. Whether
the RHS happens to be a literal should not change the move rule.

`String` is one type. Affineness guards against the aliasing problems aggregates
carry: pointer identity, mutability, thread-sendability. A second ownership state for
the literal case weakens that guard for no semantic gain. Removing it restores the
rule to the value, not the syntax that produced it.

## The model

`LiteralString` is an allocating expression. It produces an `Owned String` at the
point of birth.

- `let a = "foo"` makes `a` `Owned`.
- `let b = a` moves `a`. `b` is `Owned`. `a` is `Moved`.
- `println a` after that move is rejected as `UseAfterMove`.
- `consume "foo"` allocates an owned value at the call site and moves it into the
  consuming parameter. No special path.

Heap aggregates no longer enter the `Literal` state. Value-type literals (`Int`,
`Bool`, `Unit`) keep it. They have no ownership concerns to track, and the state
is where they belong.

## Codegen

Producing a real heap `String` from a static byte pattern still has to happen.
That work stays in codegen. At the allocation site for a `LiteralString`, codegen
emits whatever runtime sequence yields an owned `String`. The analyzer sees an
`Owned` value at that point and treats subsequent flow normally.

The IR-level notion of static-bytes literals is retained. The `Literal` ownership
state is removed.

## Cloning

`argNeedsClone` in `OwnershipAnalyzer.scala` currently auto-clones in three cases.
After this change:

- Inline literal arg (`consume "foo"`): no auto-clone. The literal is born `Owned`
  and moves into the consuming param.
- `Ref` to a `Literal`-state local (`let x = "foo"; consume x`): the case stops
  existing. `x` is `Owned`; `consume x` moves it.
- `Ref` to a `Global` binding: unchanged. Still auto-cloned. Stays as a marked
  temporary until `docs/brainstorming/mem/mem-evolution.md` Layer 3 (the `Clone`
  protocol with `^` as surface syntax) replaces it.

After this, the only remaining auto-clone path is for globals.

## What this is not

- Not a change to how literals are stored in static memory or how IR initializes
  them.
- Not the removal of `Literal` for value-type primitives. They keep it.
- Not the Layer 3 `Clone` protocol work.
- Not a syntactic change. No new operator, no new keyword. The user-visible
  language is unchanged.

## Design pressure

`Literal` currently threads through more than `let`-bindings. The change has to
land at each of these sites, or the leak just moves:

- `analyzeLetBinding`: the `LiteralString` arm at `OwnershipAnalyzer.scala:958`
  collapses into the `Owned` / `isMoveOnRebind` path.
- `argNeedsClone`: the `LiteralString` arm at `:845` and the `Literal`-state `Ref`
  arm at `:852` collapse.
- Closures: `CapturedLiteral` (`:1439`-`:1444`) folds into `CapturedOwned` (move
  closure) or `CapturedBorrowed` (borrow closure).
- Mixed-conditional handling: the witness-binding `withLiteral` at `:932`, and the
  clone-the-non-allocating-return-branch tactic in `docs/memory-model.md`, no
  longer have a literal-vs-owned distinction to bridge.
- Returns: a function that returns a literal directly returns an owned heap value
  by the same path as any other allocating return.
- `OwnershipState.Literal` itself: retained for value-type primitives (`Int`,
  `Bool`, `Unit`). Heap-aggregate paths into this state go away.

The analyzer changes are coupled to codegen. The analyzer can only start treating
literals as `Owned` once codegen produces a real owned value at that point.

## Documentation

`docs/memory-model.md` needs updates in at least two places:

- "Ownership acquisition": drop `let x = "hello"` from the `Literal` example.
  Either add it to the allocating list, or note that literals are an allocating
  expression for ownership purposes.
- "Ownership states": clarify that the `Literal` row applies only to value-type
  primitives. Heap aggregates do not enter `Literal`.

## Acceptance criteria

- `mml/samples/unique-should-complain.mml` fails to compile with a `UseAfterMove`
  on `a` at the `println` line, against the move at `let b = a`.
- `let a = "foo"; let b = a` moves `a`. Reading `a` after is rejected.
- `consume "foo"` compiles. The lowered code is one allocation and one move. No
  clone insertion at the call site.
- `let a = "foo"; consume a` compiles. `a` is moved into the parameter. Later use
  of `a` is rejected.
- Existing tests that pass inline literals to consuming params compile with no
  behavioral change, just without the silent clone.
- Returns whose value happens to be a literal continue to deliver an owned value
  to the caller, without a special "clone literal branches" path.
- Global auto-clone behavior is unchanged. Only the literal paths are removed.
