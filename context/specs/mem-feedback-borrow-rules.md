# Borrow Rules Feedback

> Draw a hard line for when a borrow is allowed to escape.
> Otherwise you'll recreate GC semantics on top of raw pointers and eventually hit
> use-after-free.

## A Coherent Rule Set

### 1. Values have one of two "modes"

- **Owned**: Has drop glue / must be freed exactly once
- **Borrowed**: Non-owning view; never freed; must not outlive the owner

### 2. Struct constructors are "sinks" (move-in)

note: this is a departure from out current setup.
        we actually copy values now which is not ideal for performance
        but is so simple.

`User name role` consumes its args (moves owned stuff into fields).

That's good: constructors are where ownership gets captured.

### 3. Normal functions are borrow-by-default

```mml
fn len(s: String): Int = ...
```

This means `len` takes a **borrow** of `s` by default, not ownership.

That implies:
- Calling `len x` never invalidates `x`
- `len` cannot free `x`
- `len` cannot stash `x` anywhere that outlives the call

#### The enforcement you need

Borrow-by-default only works if the compiler enforces:

1. You can't return a borrowed view that points into a local owned value
2. You can't store a borrow into an owned container / global / closure that escapes
3. You can't call a sink (like a constructor) with a borrow unless you explicitly
   clone or materialize an owned copy

**In other words: borrows are non-escaping unless proven safe.**

### 4. You still need an explicit "escape hatch" for ownership transfer

Because sometimes you do want to pass ownership through functions
(the `identity : String -> String` case).

Add an explicit marker:

```mml
fn identity(~s: String): String = s   // takes ownership, returns ownership
```

Without that, your language can't express "I'm consuming this and giving it back"
except via constructors.


note: we will build this next.

### 5. Containers and constructors compose naturally

If constructors are sinks, then containers built as structs automatically become
sinks too:

- `Array.push(a, x)` is a sink for `x`
- `Map.insert(m, k, v)` is sink for `k` and `v`
- `TreeNode(left, right, value)` sinks fields

And your "functional look-at-it-and-done" code mostly stays in borrow-land.

### 6. The two big gotchas to decide up front

#### A) Can you take borrows to temporaries?

Example: `len (concat "a" "b")`

If `concat` produces an owned temporary, borrow is fine only if you guarantee it
lives through the full call (sequence point).

That's doable: **"temporaries live to end of statement / let-binding / call"**.

#### B) Closures / lambdas

If closures can capture, borrow-by-default gets tricky: captures are an escape.

You likely want:
- Closures capture by borrow unless `move` is stated
- Borrow-captures can't outlive the scope



---

## Practical Implementation Path

1. Implement borrow-by-default with a strict rule: **borrows cannot escape a
   function** (no returning/storing/capturing borrows)

question: is this  not what we do already?

2. Make constructors and container insertion APIs be sinks (move)
    
note: stated above, we will probably do this.

3. Add explicit "owned param" for the handful of functions that actually
   transfer ownership

note: we already have this

4. Later, relax the "no escape" rule with real lifetime/region analysis if you want




