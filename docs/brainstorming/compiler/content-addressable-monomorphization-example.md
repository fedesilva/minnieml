Content-Addressable Monomorphization: A Worked End-to-End Collapse

Here's a concrete end-to-end collapse where **(1) generic**, **(2) effect handler**, and **(3) newtype/alias** all disappear into **one** emitted symbol.

## Source

### Types (aliases / "zero-cost newtypes")
```mml
type UserId  = Int
type OrderId = Int
```

### Effect
```mml
effect Log {
  fn info(msg: String): Unit
}
```

### Two *different* handlers that are behaviorally identical
Both end up calling the same low-level primitive `System.writeLine`.

```mml
module SystemLog {
  fn info(msg: String): Unit =
    System.writeLine(msg)
  ;
}

module StdoutLog {
  fn info(msg: String): Unit =
    System.writeLine(msg)
  ;
}
```

### A generic function that uses the effect + a "newtype"
```mml
fn audit_inc<'Id>(x: 'Id): 'Id =
  Log.info(concat("inc: ", to_string(x)))
  x + 1
;
```

### Two call sites: different type + different handler
```mml
fn a(): UserId =
  handle Log with SystemLog in {
    audit_inc (41 : UserId)
  }
;

fn b(): OrderId =
  handle Log with StdoutLog in {
    audit_inc (41 : OrderId)
  }
;
```

---

## Specialization inputs

Your specializer is effectively:

`Specialize(TemplateAST(audit_inc), Context) -> ConcreteAST`

Two contexts:

- Ctx A: `{ 'Id -> Int, Log -> SystemLog }`
- Ctx B: `{ 'Id -> Int, Log -> StdoutLog }`

Why `'Id -> Int` in both?
- because `UserId = Int` and `OrderId = Int` erase (or normalize) to the same rep type for codegen.

---

## Step 1 — substitution

### After substituting **type**
`audit_inc<'Id>` becomes `audit_inc<Int>` in both cases.

### After substituting **effect**
The `Log.info(...)` call is rewritten to the chosen handler's `info`.

So you *appear* to get two different concrete ASTs:

**Concrete A (before deeper normalization):**
```mml
fn audit_inc_Int_SystemLog(x: Int): Int =
  SystemLog.info(concat("inc: ", to_string(x)))
  x + 1
;
```

Concrete B:
```mml
fn audit_inc_Int_StdoutLog(x: Int): Int =
  StdoutLog.info(concat("inc: ", to_string(x)))
  x + 1
;
```

---

## Step 2 — normalization that makes them equal

There are two key normalizations that matter for *this* collapse:

### 2.1 alpha-normalization (boring but necessary)
Rename locals/params to canonical names.

Both become (shape-wise):
```mml
fn (v0: Int): Int =
  <call>(concat("inc: ", to_string(v0)))
  v0 + 1
;
```

### 2.2 **handler inlining / resolution normalization** (the important one)
Both `SystemLog.info` and `StdoutLog.info` are tiny wrappers that compile to the same call:

- `SystemLog.info(msg)` → `System.writeLine(msg)`
- `StdoutLog.info(msg)` → `System.writeLine(msg)`

If your normalization pass canonicalizes trivial wrappers (or you normalize based on *callee body hash*, see Merkle section), then both concrete ASTs reduce to the same "post-normalized" structure:

```mml
fn (v0: Int): Int =
  System.writeLine(concat("inc: ", to_string(v0)))
  v0 + 1
;
```

At this point **the handler identity is gone**; only the *behavior* remains.

---

## Step 3 — content hash

Now you hash the normalized AST (plus the hashes of callees, Merkle-style).

Let:
- `H_writeLine = hash(System.writeLine specialization)` (probably just an extern/FFI node)
- `H_to_string = ...`
- `H_concat = ...`

Then:
```
H_audit_inc =
  hash(  fn(v0:Int)->Int {
          call System.writeLine( concat("inc: ", to_string(v0)) )
          add v0 1
        }
        + H_writeLine + H_concat + H_to_string
      )
```

Because the normalized AST is identical, you get:

- `H_audit_inc(Ctx A) == H_audit_inc(Ctx B)`

---

## Step 4 — cache + single emitted symbol

First time (from `a()`):
- cache miss → emit LLVM for that function
- name it by hash, e.g.
  - `@h$9f3c4a...` (pick your exact scheme)

Second time (from `b()`):
- cache hit → **reuse** the exact same symbol

So both `a()` and `b()` end up calling:

- `@h$9f3c4a...` (same address, same machine code)

---

## What actually collapsed (three-for-one)

1) **Generic**: `'Id` collapsed because `UserId` and `OrderId` erase to the same rep (`Int`).
2) **Newtype/alias**: `UserId` vs `OrderId` didn't create new code because they're representationally identical.
3) **Effect**: two distinct handlers collapsed because after normalization they both reduce to the same underlying call graph.

That's the exact kind of "content-addressable monomorphization" win you want: **semantic identity → single symbol → less bloat + better I-cache locality**.

If you want this to work *without* doing "inline wrapper bodies" as a normalization, the Merkle call-graph approach is the clean way: the call edge is keyed by the callee's own content hash, not by its human name. That makes "two different wrappers with identical bodies" collapse automatically.
