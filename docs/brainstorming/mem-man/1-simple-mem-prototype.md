# Simple Memory Management Prototype

An early prototype for lifetime analysis and automatic memory management in MML.

## Design Principles

### Genericity Matters, Even in Prototypes

A prototype that ignores the existing architecture teaches nothing. A prototype that respects
the design's flexibility shows us what we actually need and how features integrate.

The current design is flexible and generic. Prototypes should leverage that, not work around it.

### Everything-is-Functions Makes Lifecycle Tracking Uniform

The CPS representation of multi-line functions (chained lambdas) makes inserting `free`
calls trivial - we just graft new `App` nodes into the existing chain. No special AST nodes,
no separate IR, just more function applications.

In a typical compiler, ownership analysis needs special cases for:
- Block scopes (when do locals die?)
- Expression temporaries (different lifetimes)
- Control flow (if/else branches need consistent cleanup)
- Loop bodies, early returns, etc.

With everything-as-functions, these all reduce to the same thing:
- `if-then-else` → function application
- Multi-statement body → chained lambdas
- Loop → recursive function call
- "Temporary" → lambda parameter

One recursive walk. At each `App`: check if arg is owned, check if callee consumes or borrows,
track accordingly. The tree shape *is* the scope structure. Lambda boundary *is* lifetime boundary.

This is a concrete payoff for the "everything becomes lambdas + apps" decision: ownership tracking
falls out of the existing representation without new control-flow machinery.

### The AST is the Information

No annotations. No side-channel maps. No metadata fields.

Walk the tree → insert `App(Ref("free"), x)` nodes → done.

The AST *is* the ownership information. After the phase runs, the frees are there, visible,
in the same representation as everything else. What you see is what runs.

## Goal

Enable writing more complex programs without manual memory management, while the compiler
teaches us about the actual memory model we need. This will evolve as we add real records
(non-native) and an effects system.

## The Problem

Given an AST that's fundamentally functions and applications, we need to:
1. Detect which values require heap allocation (String, Buffer, structs)
2. Track ownership through the program
3. Insert appropriate `malloc`/`free` calls

## What Needs Tracking

**Heap-allocated types:**
- `String` - struct with length and data pointer
- `Buffer` - pointer type
- Any `@native` struct

**No tracking needed:**
- `Int`, `Bool`, `Unit` - register/stack values
- String literals - static memory

## The Core Insight: We Need Effect Information

The type signature alone doesn't tell us enough:

```mml
fn readline(): String = @native;
```

Does `readline` return a fresh allocation the caller owns,
or a borrowed buffer we must not free?

Without knowing, we can't decide who frees.

---

## Proposed Solution: Extend @native With Attributes

Rather than a full effect system, extend `@native` with memory attributes:

```mml
fn println(s: String): Unit = @native
fn readline(): String = @native[mem=alloc]
fn concat(a: String, b: String): String = @native[mem=alloc]
```

### Attribute Reference

* `[mem=alloc]` — Function allocates new memory.
  Caller owns the return value and is responsible for freeing it.

* `[mem=static]` — Function returns a pointer to existing/static memory.
  Caller does not own the return value (do not insert free).

* *None* — Default assumption for "Pure" natives.
  Caller does not own the return (no tracking).

### Defaults (if no annotation)

- Takes heap params → **Borrow** (default: caller retains ownership)
- Moving ownership requires explicit syntax (`~` in signatures and `~expr` at call sites)
- **Parameters taking ownership (`~`) must consume**: Any function parameter that takes a heap type
  by move (`~`) *must* consume it (either free it internally or transfer it elsewhere). This keeps
  the "who frees" question unambiguous.
- `@native` functions that return heap types must declare `mem=alloc`; otherwise
  the return is treated as borrowed (no ownership, no frees inserted)

### Explicit Move Syntax (`~`)

To make the system simpler and clearer, we default to "Borrow" semantics.
If you pass a value, you keep it. If you want to transfer ownership, you must move it (`~`).
Critically, this must be explicit at **both** the call site and the declaration:

- `fn foo(x: String)` -> Borrows (caller retains ownership).
  - Call: `foo x` (borrows `x`, `x` remains valid)
- `fn bar(~x: String)` -> Takes ownership.
  - Call: `bar ~x` (moves `x`, `x` is now invalid)

This enforces synchronization: if the signature expects a move, the caller *must* provide one.

**Example:**

```mml
let s = readline()
println s        -- Default: Borrow. 's' remains valid.
vector_push ~s   -- Explicit Move. 's' is now invalid.
println s        -- Compile Error: Use after move.
```

**Partial Application:** For this prototype, partial application with `~` arguments is **banned**.
All `~` arguments must appear in a saturating call (one that fully applies the function).

```mml
-- ALLOWED: saturating call
consume ~s 42

-- BANNED: partial application captures owned value
let f = consume ~s   -- Compile Error: cannot move into partial application
f 42
```

*Rationale:* Closures are typically callable multiple times (`FnMany`), but a closure that
owns a linear resource must be `FnOnce`. Banning this sidesteps linear closure types for now.

---

## Ownership Model

### Linear Types Lite

For the prototype, assume linear ownership with **Borrow by Default**:
- Each heap value has exactly one owner
- Function calls **borrow** by default (caller retains ownership)
- Caller must explicitly move (`~`) to transfer ownership
- Values freed at scope end if still owned

### Ownership States

```
Owned    → caller owns, must free at scope end
Moved    → ownership transferred to callee, caller must not free
Borrowed → lent to callee, caller retains ownership
Literal  → static memory, no owner, never free
```

### Example: The Loop Function

```mml
fn loop(): Unit =
  println "Type a number:"
  let s = readline()
  let n = str_to_int s
  println (concat "Number is: " (to_string n))
  loop ()
```

Ownership analysis (with borrow-by-default, no sigils needed for borrows):

* `"Type a number:"` — Literal, no owner, no tracking
* `readline()` — caller owns `s`
* `str_to_int s` — borrows `s`, caller still owns `s`
* `to_string n` — caller owns tmp1
* `concat "..." tmp1` — borrows both, caller owns tmp2, still owns tmp1
* `println tmp2` — borrows tmp2, caller still owns tmp2
* (after println) — caller frees tmp2
* (before tail call) — caller frees `s`
* `loop ()` — tail call

### Generated Code Pattern

What it looks like today (no frees):

```llvm
%5 = call %String @readline()
%6 = call i64 @str_to_int(%String %5)
%12 = call %String @to_string(i64 %6)
%13 = call %String @concat(%String %11, %String %12)
call void @println(%String %13)
call void @loop()
```

With ownership tracking, would insert frees:

```llvm
%5 = call %String @readline()                      ; %5 owned
%6 = call i64 @str_to_int(%String %5)              ; borrows %5
%12 = call %String @to_string(i64 %6)              ; %12 owned
%13 = call %String @concat(%String %11, %String %12)  ; borrows %12, %13 owned
call void @println(%String %13)                    ; borrows %13
call void @__free_String(%String %13)              ; free after last borrow
call void @__free_String(%String %5)               ; free before tail call
tail call void @loop()
```

---

## Runtime Capacity (The Safety Net)

We add a `__cap` field to the runtime struct layout for Resource Types (`String`, `Buffer`).
Its role is strictly to handle **Control Flow Variance** (merging caller-owned and static paths) safely.


### The Logic: Conditional Merge Handling

We use the `__cap` field to safely handle merges, but we optimize based on what we know statically:

1. **Both branches Static/Literal:**
   Treat as static. No free inserted.

2. **Both branches Alloc:**
   Treat as owned. Insert unconditional `free()`.

3. **Mixed (One Alloc, One Static):**
   Treat as owned, but insert a **conditional free**. The runtime checks `__cap > 0`:
   - If `__cap > 0` (Alloc path taken): `free()` proceeds.
   - If `__cap = -1` (Static path taken): `free()` is skipped.

This avoids unnecessary runtime checks in hot paths where ownership is statically known.

```
  fn main() =
    let s = 
      if strict_mode() 
        then "static_default" 
        else readline();  // heap alloc    
      println s;
      // Implicit free at end of scope
      // We don't know if `s` is static or heap allocated.
      // this is where __cap helps
```


### Refined Role

This allows the compiler to be **conservative** (over-approximate ownership) without requiring
complex static analysis to distinguish literals from heap pointers in every possible branch.

---

## Representation: Synthetic Function Calls

The ownership phase inserts `App(Ref("__free_T"), binding)` nodes into the AST, where `T` is the
type name (e.g., `__free_String`, `__free_Buffer`).

- Uses existing `App`/`Ref` infrastructure
- Zero special-case codegen — just emits a call like any other function
- Visible in AST dumps for debugging
- `__` prefix is unrepresentable in source, prevents clashes with user-defined names


### Definition of the `__free_*` Functions

Since the only Resource types defined today are String and Buffer and they are defined in the c runtime, 
and any new one that we add in the short term will also be, we will provide deallocation
functions for each in the same runtime alongside the types they free.

Note: Since writing this we introduced specialized arrays, we need to think about them.

---

## Implementation Sketch

### AST Changes

```scala
case class NativeImpl(
  span: SrcSpan,
  memEffect: Option[MemEffect]    // new field
)

enum MemEffect:
  case Alloc
  case Pure
```

### New Semantic Phase: OwnershipAnalyzer

Runs after TypeChecker, before Codegen.

Note: Since writing this we have introduces soft references and
      the reindexing phase between typer and codegen.
      We need to think about this.

```scala
case class OwnershipInfo(
  owned: Set[String],                    // bindings currently owned
  freePoints: Map[AstNode, Set[String]]  // where to insert frees
)

def analyzeOwnership(expr: Expr, owned: Set[String]): OwnershipInfo =
  // Walk AST
  // Track owned bindings
  // At App nodes, check callee's mem effect
  // Mark free points after last borrow or at scope end
```



### Codegen Changes

None. The OwnershipRewriter inserts standard `App` nodes for `__free_*` calls, so Codegen
treats them as normal function applications. All complexity is contained in the AST
rewriting phase.

---

## Open Questions

1. **What about conditionals?**
   ```mml
   let x = if cond then readline() else cached_value;
   ```
   A: Both branches must have same ownership semantics.


2. **Partial application with heap captures?** ✓ *Resolved by banning `~` in partial application*
   ```mml
   let greet = concat "Hello, "      -- captures a literal, ok (borrows static)
   let greet2 = concat (readline())  -- banned: cannot move owned value into closure
   ```
   A: For prototype: `~` arguments must appear in saturating calls only.

3. **What frees a Buffer?** ✓ *Resolved by `__cap` field*
   A: Buffer is a Resource Type with a `__cap` field like String. Therefore:
   - `free(buf)` — universal, checks `__cap`
   - `flush(buf)` — borrows, no ownership change
   - `close(~buf)` — consumes, frees after closing

4. **Nested structs?**
   A: If we add user structs containing Strings, ownership must be recursive.

5. **Error messages?**
   A: "Cannot use `s` after passing to `concat` - ownership was transferred"

6. **Linearity Ergonomics**
   Strict linearity might be rigid. How do we make "use-after-move" errors friendly?
   A: Borrow-by-default helps — explicit `~` moves are the exception, not the rule.

7. **Static vs Heap Safety** ✓ *Resolved by Runtime Capacity (`__cap` field)*
   Crucial to distinguish `LiteralString` (static) from allocated strings. Freeing a literal is fatal.
   **Resolution:** The `__cap` field in resource types allows runtime distinction: `__cap > 0` for heap,
   `__cap = -1` for static. `free()` becomes a safe no-op for literals.

---

## Leak Testing Plan

### Prerequisites

1. ✓ Add `read_line_fd(fd: Int): String` to runtime
2. ✓ Expose `read_line_fd` in semantic prelude (`injectCommonFunctions`)

### Test Program

```mml
fn test_iteration(): Unit =
  let fd = open_file_read "test.txt";
  let line1 = read_line_fd fd;
  let line2 = read_line_fd fd;
  println line1;
  println line2;
  close_file fd
;

fn run(n: Int): Unit =
  if n == 0 then ()
  else
    test_iteration ();
    run (n - 1)
;

fn main(): Unit =
  run 1000
;
```

### Run

```bash
# Create test file
echo -e "hello\nworld" > test.txt

# Build
sbt "run bin samples/leak_test.mml"

# Check for leaks
leaks --atExit -- ./build/target/LeakTest-x86_64-apple-macosx
```

### Expected Results (Current State)

Without memory management, expect ~2000-4000 leaks:
- 2 Strings per iteration from `read_line_fd`
- 1000 iterations
- Plus any from `concat` if used

### Success Criteria (After Ownership Phase)

```
Process XXXXX: 0 leaks for 0 total leaked bytes.
```

---

## Next Steps

1. **Phase 0:** Hardcode native effects in compiler (no syntax changes)
2. **Phase 1:** Add `@native[...]` parsing
3. **Phase 2:** Implement OwnershipAnalyzer phase
4. **Phase 3:** Modify codegen to emit frees
5. **Phase 4:** Write programs, find edge cases, iterate

This prototype will teach us what we actually need, and evolve into the proper system
as we learn.
