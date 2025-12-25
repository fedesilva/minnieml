# Simple Memory Management Prototype

An early prototype for lifetime analysis and automatic memory management in MML.

## Design Principles

### Genericity Matters, Even in Prototypes

A prototype that ignores the existing architecture teaches nothing. A prototype that respects
the design's flexibility shows us what we actually need and how features integrate.

The current design is flexible and generic. Prototypes should leverage that, not work around it.

### Everything-is-Functions Makes Lifecycle Tracking Uniform

The CPS-like representation of multi-line functions (chained lambdas) makes inserting `free`
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
fn println(&s: String): Unit = @native;
fn readline(): String = @native[mem=alloc];
fn concat(&a: String, &b: String): String = @native[mem=alloc];
```

### Attribute Reference

| Attribute | Meaning |
|-----------|---------|
| `mem=alloc` | Returns newly allocated value (caller owns result) |
| `mem=pure` | No heap interaction (default for primitives) |

### Defaults (if no annotation)

- Takes heap params → **Consume** (default: ownership transfers to callee)
- Borrowing requires explicit syntax (e.g., `&` in signatures and `&expr` at call sites)
- `@native` functions that return heap types must declare `mem=alloc`; otherwise
  the return is treated as borrowed (no ownership, no frees inserted)

### Explicit Borrowing Syntax (`&`)

To make the system simpler and clearer, we default to "Move" semantics.
If you pass a value, you lose it. If you want to keep it, you must lend it (`&`).
Critically, this must be explicit at **both** the call site and the declaration:

- `fn foo(x: String)` -> Takes ownership.
  - Call: `foo x` (moves `x`)
- `fn bar(&x: String)` -> Borrows.
  - Call: `bar &x` (borrows `x`)

This enforces synchronization: if the signature expects a borrow, the caller *must* provide one.


---

## Ownership Model

### Linear Types Lite

For the prototype, assume linear ownership with **Move by Default**:
- Each heap value has exactly one owner
- Ownership **transfers** on function call (consume) by default
- Caller must explicitly lend (`&`) to retain ownership
- Values freed at scope end if still owned

### Ownership States

```
Owned    → value needs free at scope end
Moved    → ownership transferred, no free needed
Borrowed → lent to callee, strictly read-only (or interior mutability)
Literal  → static memory, never free
```

### Example: The Loop Function

```mml
fn loop(): Unit =
  println &"Type a number:";
  let s = readline();
  let n = str_to_int &s;
  println (concat &"Number is: " &(to_string n));
  loop ()
```

Ownership analysis:

| Expression | Result | State After |
|------------|--------|-------------|
| `"Type a number:"` | Literal | no tracking |
| `readline()` | `s` Owned | |
| `str_to_int &s` | borrows `s` | `s` still Owned |
| `to_string n` | tmp1 Owned | |
| `concat &"..." &tmp1` | borrows tmp1, tmp2 Owned | tmp1 still Owned |
| `println &tmp2` | borrows tmp2 | tmp2 still Owned |
| (after println) | | free tmp2 |
| (before tail call) | | free `s` |
| `loop ()` | tail call | |

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
call void @free(%String %13)                       ; free after last borrow
call void @free(%String %5)                        ; free before tail call
tail call void @loop()
```

---

## Representation: Synthetic Function Calls

The ownership phase inserts `App(Ref("free"), binding)` nodes into the AST.

- Uses existing `App`/`Ref` infrastructure
- Zero special-case codegen for the phase itself
- Visible in AST dumps for debugging
- Single `free` function in prelude, no `free_string`, `free_buffer`, etc.

### Codegen Handles Type-Specific Logic

Codegen sees `free(x)`, inspects the type structure, emits appropriate code:

```scala
resolveType(argType) match
  case NativeStruct(fields) =>
    // free pointer fields inside the struct
    for (name, fieldType) <- fields if isPointer(fieldType) do
      emitFreeField(arg, name)
  case NativePointer(_) =>
    // free the pointer itself
    emitFree(arg)
  case _ =>
    // primitive, no-op
```

Works for:
- `String` (struct with `data: CharPtr`) - frees the `data` pointer
- `Buffer` (`*i8`) - frees the pointer itself
- Any future native struct/pointer types

No hardcoded type names in codegen.

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

When emitting a function body:
1. After each `App` where result is borrowed and not used again → emit free
2. Before tail calls → emit frees for all remaining owned bindings
3. At function return → emit frees for all remaining owned bindings

---

## Open Questions

1. **What about conditionals?**
   ```mml
   let x = if cond then readline() else cached_value;
   ```
   Both branches must have same ownership semantics.

2. **Partial application with heap captures?**
   ```mml
   let greet = concat "Hello, ";  # captures a literal, ok
   let greet2 = concat (readline());  # captures heap value - problematic
   ```
   For prototype: error on heap captures in partial application.

3. **What frees a Buffer?**
   Need `free_buffer` or make `flush` consume the buffer.

4. **Nested structs?**
   If we add user structs containing Strings, ownership must be recursive.

5. **Error messages?**
   "Cannot use `s` after passing to `concat` - ownership was transferred"

6. **Linearity Ergonomics**
   Strict linearity might be rigid. How do we make "use-after-move" errors friendly without
   drowning everything in `&`?

7. **Static vs Heap Safety**
   Crucial to distinguish `LiteralString` (static) from allocated strings. Freeing a literal is fatal.

---

## Leak Testing Plan

### Prerequisites

1. Add `read_line_fd(fd: Int): String` to runtime (trivial - similar to `readline()`)
2. Expose `read_line_fd` in semantic prelude (`injectCommonFunctions`)

### Test Program

```mml
fn test_iteration(): Unit =
  let fd = open_file_read "test.txt";
  let line1 = read_line_fd fd;
  let line2 = read_line_fd fd;
  println line1;
  println line2;
  close_file fd;
;

fn run(n: Int): Unit =
  if n == 0 then ()
  else
    test_iteration ();
    run (n - 1)
;

fn main(): Unit =
  run 1000;
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
