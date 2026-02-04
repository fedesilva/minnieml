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
teaches us about the actual memory model we need. Records (user-defined structs) are now
implemented; this prototype focuses on ownership tracking and automatic deallocation.
The system will evolve as we add an effects system.

## The Problem

Given an AST that's fundamentally functions and applications, we need to:
1. Detect which values require heap allocation (String, Buffer, structs)
2. Track ownership through the program
3. Insert appropriate `malloc`/`free` calls

## What Needs Tracking

**Heap-allocated types:**
- `String` - struct with length, capacity, and data pointer
- `Buffer` - pointer type
- `IntArray` - struct with length, capacity, and data pointer
- `StringArray` - struct with length, capacity, and data pointer (elements are Strings)
- Any `@native` struct
- User-defined structs (`TypeStruct`) - may contain heap-allocated fields

**No tracking needed:**
- `Int`, `Bool`, `Unit` - register/stack values
- String literals - static memory

### Current Limitation: User Structs Are Value Types

The current codegen emits user-defined structs as **value types**. The generated constructor
(`__mk_<Name>`) uses `alloca` for temporary workspace, then loads and returns the struct by value:

```llvm
define %struct.Person @personstruct___mk_Person(%struct.String %0, i64 %1) {
  %2 = alloca %struct.Person        ; temporary stack space
  ; ... store fields ...
  %5 = load %struct.Person, ...     ; load entire struct
  ret %struct.Person %5             ; return by VALUE (copied)
}
```

This means:
- Local struct variables live on the stack
- Global struct variables live in the data segment
- Structs are passed and returned by copy, not by pointer

**Implications for ownership tracking:**
- The struct itself doesn't need `__cap` - its memory is managed by stack/data segment
- Only heap-allocated *fields* within the struct need tracking (e.g., a `String` field
  assigned from `readline()`)
- `DataDestructor` would free heap-allocated fields recursively, not the struct itself

**Open question:** Is this the intended semantics, or should structs be heap-allocated
and passed by pointer? Fixing this is out of scope for the ownership prototype - we work
with the current value-type semantics.

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
- Consuming params are marked **only in the declaration** with `~param`. Call sites stay bare.
- **Parameters taking ownership (`~`) must consume**: Any function parameter that takes a heap type
  by move (`~`) *must* consume it (either free it internally or transfer it elsewhere). This keeps
  the "who frees" question unambiguous.
- `@native` functions that return heap types must declare `mem=alloc`; otherwise
  the return is treated as borrowed (no ownership, no frees inserted)

### Native parameter consumption

- Native functions use the same per-parameter `~` sigil to mean “callee consumes”.
- Missing `~` means borrow-only (safe default that may leak but won’t double-free).
- Call sites never use `~`; consumption is checked via last-use analysis on arguments.

### Consuming Parameters (`~` in declarations only)

To make the system simpler and clearer, we default to "Borrow" semantics.
If you pass a value, you keep it unless the callee declares it consumes (`~param`).
Call sites never use `~`; the compiler checks that an argument passed to a consuming parameter
is at its **last use**. If not, it emits a diagnostic (suggest `clone` or `leak/forget`).

- `fn foo(x: String)` -> Borrows (caller retains ownership).
  - Call: `foo x` (borrows `x`, `x` remains valid)
- `fn bar(~x: String)` -> Takes ownership.
  - Call: `bar x` (call site unchanged). `x` must be last-use or cloned; afterwards `x` is invalid.

This enforces synchronization: if the signature expects a move, the caller *must* provide one.

**Example:**

```mml
let s = readline()
println s        -- Default: Borrow. 's' remains valid.
vector_push s    -- Param declared ~, so this consumes. 's' is now invalid.
println s        -- Compile Error: Use after move.
```

**Partial Application:** For this prototype, partial application with `~` arguments is **banned**.
All consuming (`~`) arguments must appear in a saturating call (one that fully applies the function).

```mml
-- ALLOWED: saturating call
consume s 42

-- BANNED: partial application captures owned value
let f = consume s    -- Compile Error: cannot move into partial application
f 42
```

*Rationale:* Closures are typically callable multiple times (`FnMany`), but a closure that
owns an affine resource must be `FnOnce`. Banning this sidesteps affine closure types for now.

---

## Ownership Model

### Affine Types Lite

For the prototype, assume affine ownership with **Borrow by Default**:
- Each heap value has exactly one owner
- Function calls **borrow** by default (caller retains ownership)
- Consuming parameters are marked with `~` in declarations; call sites stay bare
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
  println "Type a number:";
  let s = readline();
  let n = str_to_int s;
  println (concat "Number is: " (to_string n));
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
      else readline();  # heap alloc    
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

**Runtime-provided (native types):**

The C runtime provides deallocation functions for native resource types:
- `__free_String` - frees String data
- `__free_Buffer` - frees Buffer
- `__free_IntArray` - frees IntArray data
- `__free_StringArray` - frees StringArray (must free contained Strings first)

**Generated (user-defined structs):**

For user-defined structs (`TypeStruct`), the parser generates `__free_<StructName>` alongside
the constructor `__mk_<StructName>`. The destructor uses a `DataDestructor` marker node
(mirroring `DataConstructor`), which codegen expands to:
1. Recursively free any heap-allocated fields (Strings, arrays, nested structs)
2. Free the struct itself (if heap-allocated, not stack)

---

## Implementation Sketch

### AST Changes

```scala
case class NativeImpl(
  span: SrcSpan,
  memEffect: Option[MemEffect]    // new field
)

enum MemEffect:
  case Alloc    // returns newly allocated memory, caller owns
  case Static   // returns pointer to static/existing memory, caller doesn't own
  case NoAlloc  // no memory effect, default for pure natives

// Marker for generated struct destructors (mirrors DataConstructor)
case class DataDestructor(
  span:     SrcSpan,
  typeSpec: Option[Type] = None
) extends Term
```

### New Semantic Phase: OwnershipAnalyzer

Runs after `ResolvablesIndexer`, before codegen. The current semantic pipeline is:

```
inject-stdlib → duplicate-names → id-assigner → type-resolver →
ref-resolver → expression-rewriter → simplifier → type-checker →
resolvables-indexer → tailrec-detector → [OwnershipAnalyzer] → codegen
```

The phase needs complete resolution (soft references resolved, index built) to look up
callee declarations and check `memEffect`.

```scala
case class OwnershipInfo(
  owned: Set[String],                    // bindings currently owned
  freePoints: Map[AstNode, Set[String]]  // where to insert frees
)

def analyzeOwnership(expr: Expr, owned: Set[String]): OwnershipInfo =
  // Walk AST with liveness to find last uses
  // Track owned bindings and aliasing
  // At App nodes, check callee memEffect and consuming params (~ in decls)
  // If consuming param arg is not last-use -> emit diagnostic (clone or leak)
  // Mark free points after last use (post-dominator) or at scope end
  // Disallow consuming params in partial applications / multi-call closures
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
   A: For prototype: consuming (`~`-declared) parameters must be supplied in saturating calls only.

3. **What frees a Buffer?** ✓ *Resolved by `__cap` field*
   A: Buffer is a Resource Type with a `__cap` field like String. Therefore:
   - `free(buf)` — universal, checks `__cap`
   - `flush(buf)` — borrows, no ownership change
   - `close(~buf)` — consumes (decl-level), frees after closing; call sites stay `close buf`

4. **Nested structs?**
   A: If we add user structs containing Strings, ownership must be recursive.

5. **Error messages?**
   A: "Cannot use `s` after passing to `concat` - ownership was transferred"

6. **Affine Ergonomics**
   Strict affine typing might be rigid. How do we make "use-after-move" errors friendly?
   A: Borrow-by-default helps — consuming params are explicit in declarations, not at call sites.

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
sbtn "run build samples/leak_test.mml"

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
