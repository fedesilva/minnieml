# MML Memory Model

MML uses deterministic, compile-time memory management. There is no garbage collector
and no ambient reference counting. The compiler tracks ownership of heap-allocated
values and inserts cleanup calls for values that still belong to the current scope.

The model is affine: a binding cannot be used after transferring ownership. While it owns
a value, it may lend that value repeatedly. There are no lifetime annotations or sigils for
borrowing. Parameters borrow by default; consuming parameters and move captures use `~`.

Restrictions that follow from ownership are stated with their reasons in the relevant sections.
[Implementation limitations](#implementation-limitations) lists compiler restrictions and gaps
that do not follow from those ownership rules.

---

## Design principles

The ownership model exists to keep the language mechanically sympathetic. Allocation,
movement, destruction, and duplication should have a clear cost model.

- **Borrow by default.** Passing a value to an ordinary parameter does not transfer cleanup
  responsibility and does not copy heap data.
- **Move explicitly.** Passing a value to a consuming `~` parameter, returning it, storing it
  in an owned aggregate, or moving it into an owned closure environment transfers ownership.
  A move is not a deep copy.
- **Drop exactly once.** The owner of a heap value is responsible for destroying it exactly
  once. When ownership moves, drop responsibility moves with it.
- **Do not hide cloning.** A clone is the paid duplication path. It should be visible in the
  source-level ownership model rather than inserted to paper over unclear ownership.
- **Reject unclear ownership.** If the compiler cannot prove that a borrow stays within the
  owner's lifetime, the program is rejected.

The compiler's implicit clone insertion is an exception to source-visible duplication;
see [Clone operations](#clone-operations).

---

## Core invariant

- Every heap value has exactly one owner at a time.
- Owned values are destroyed exactly once.
- Borrowed values are never destroyed by the borrower.
- Ownership may be transferred (moved) but not duplicated unless it goes through the clone path.

---

## Heap types

A type is a **heap type** if values of that type require destruction.

- **Native types** that allocate memory (e.g. `String`, `Buffer`, `IntArray`,
  `StringArray`, `FloatArray`).
- **User-defined structs** that contain a heap-typed or function-typed field (transitively).

Primitive types (`Int`, `Float`, `Bool`, `Unit`) and structs with only primitive fields
are value types and require no ownership tracking.

---

## Ownership states

Every binding tracked by the ownership analyzer is in one of these states:

| State        | Meaning |
|--------------|---------|
| **Owned**    | The binding is responsible for destroying the value. |
| **Moved**    | Ownership has been transferred; further use is invalid. |
| **Borrowed** | The binding refers to a value owned elsewhere and must not destroy it. |
| **Literal**  | A compile-time constant; never destroyed. |
| **Global**   | A module-level binding or subvalue with static lifetime; never destroyed locally. |

---

## Globals

Globals have static lifetime from the ownership model's point of view.

- A global heap value may be borrowed.
- A global heap value may be used at a consuming boundary.
- Consuming a global does not invalidate the global.
- Local cleanup never frees global-backed storage.

When a global-backed value is stored inside an owned aggregate, the aggregate is still an
ordinary owned local value, but its global-backed fields are not released by the aggregate's
destructor.

The compiler's incomplete handling of global-backed fields is described under
[Global-backed aggregates](#global-backed-aggregates).

---

## Non-consuming parameters (default)

Function parameters are non-consuming by default. The callee may use the value, but it
does not become responsible for destroying it.

```mml
fn greet(name: String): Unit =
  println ("Hello " ++ name);
;
```

`name` is borrowed. The caller retains ownership.

---

## Consuming parameters (`~`)

The `~` sigil declares that a parameter consumes its argument:

```mml
fn consume(~s: String): Unit =
  println s;
;
```

Calling `consume s` transfers ownership of `s`. After the call, `s` is `Moved` and cannot
be used again.

A value passed to a consuming parameter must:

1. Be owned.
2. Not be used along any later control-flow path.

Literals and globals have no local owner to transfer. The compiler's handling of fresh ownership
at these boundaries is described under [Clone operations](#clone-operations).

---

## Ownership acquisition

A binding becomes `Owned` when it is bound to the result of an allocating expression:

- Calls to allocating functions (`int_to_str`, `readline`, string concatenation, etc.)
- Struct construction where the struct has heap fields
- Conditionals where at least one branch allocates

```mml
let s = int_to_str 42;   // Owned
let u = User name role;  // Owned
let x = "hello";         // Literal
```

Not all heap-typed expressions produce new ownership. Borrowed inputs remain borrowed
unless explicitly moved or duplicated through the clone path.

---

## Struct move semantics

Binding a struct with heap fields transfers ownership rather than copying:

```mml
let a = User name role;
let b = a;   // a is Moved, b is Owned
```

This ensures a single owner for the struct and prevents double frees.

---

## Scope-end cleanup

At the end of a scope, the compiler inserts `free` calls for owned bindings that do not
escape. If the scope produces a result, that result is captured first.

```text
Source:
let s = int_to_str 42;
println s

Effect:
s is freed after println returns
```

---

## Escaping values

- Returning an owned value moves ownership to the caller.
- Values passed to consuming parameters move ownership to the callee.
- Values moved into owned aggregates or owned closure environments become the responsibility of
  that aggregate or environment.
- Moved values are no longer the responsibility of the original binding.

Returning a value is a move: local cleanup skips the
returned value because drop responsibility has moved to the caller.

---

## Temporary values

Intermediate allocating expressions are turned into temporaries and freed after use:

```text
Source:
println ((int_to_str n) ++ (int_to_str n))

Effect:
A = int_to_str n
B = int_to_str n
R = A ++ B
free A
free B
```

If a temporary is passed to a consuming parameter, ownership transfers and it is not
freed at the call site.

---

## Struct constructors

Constructors of structs with heap fields consume those fields:

```mml
struct User { name: String, role: String };
```

Struct construction is an ownership sink. Passing a value to a heap-typed struct
field transfers ownership to the constructed struct, so the argument must be owned.
Borrowed values cannot be assigned into owning struct fields.

You can think of that as:

```mml
fn User(~name: String, ~role: String): User
```

What happens to each argument depends on its state:

- **Owned** -> moved
- **Literal** -> cloned to create an owned field value (see [Clone operations](#clone-operations))
- **Global** -> accepted without invalidating the global; see
  [Global-backed aggregates](#global-backed-aggregates) for implementation limits
- **Borrowed** -> compile error

---

## Conditional ownership

When ownership depends on control flow, the compiler still makes sure destruction happens
exactly once.

### Both branches allocate

```mml
let s =
  if flag then
    int_to_str a;
  else
    int_to_str b;
  ;
;
```

`s` is always `Owned`.

### Mixed ownership

```mml
let s =
  if flag then
    int_to_str a;
  else
    "none";
  ;
;
```

`s` may or may not own a value. The compiler tracks that and frees it only when needed.

### Conditional consumption

```mml
let s = int_to_str 1;

if flag then
  consume s;
else
  println s;
;
```

In the non-consuming branch, the compiler inserts a `free` so the value is always
destroyed exactly once.

---

## Return values and borrow escape

Returning an owned heap value moves ownership to the caller.

Returning a borrowed value from a heap-returning function is invalid:

```mml
fn identity(s: String): String = s;   // error
```

Use a consuming parameter (`~s`) so the function receives ownership to return. A borrow does
not give the function ownership to transfer to its caller.

### Mixed return branches

If a function returns a heap type, every return path must produce ownership for the caller.
The compiler clones non-allocating literal/global branches when other branches allocate;
see [Clone operations](#clone-operations) for this implicit duplication boundary.

---

## Generated memory functions

For each struct with heap fields, the compiler generates:

- A destructor that frees each heap field
- A clone function that deep-copies heap fields, unless the struct contains a function field
  directly or transitively. Function environments cannot be cloned.

Native types implement their own free/clone in the runtime.

Generated clone functions are compiler-facing helpers. Source-level clone support is described
under [Clone operations](#clone-operations).

---

## Closures

A **non-capturing function value** is represented as the same fat pointer shape used for
closures: a function entry pointer plus a null environment pointer. The entry pointer uses the
closure-call ABI and accepts the environment pointer as its final argument, even though it ignores
that value.

A **capturing lambda** (closure) is represented as a fat pointer with a non-null environment
pointer. What matters is how that closure captures.

Direct calls to top-level functions use the plain direct-call ABI. When a named function is used as
a first-class value, codegen creates or reuses a closure-entry wrapper whose body calls the plain
direct symbol.

### Capture semantics

Captured bindings are written into the environment when the lambda is created.

For value types (`Int`, `Float`, `Bool`, etc.), capture is just a copy. The original
binding stays usable.

```mml
fn runWith(a: Int, x: Int): Int =
  let addA = { y: Int -> y + a };   // a is copied into the closure env
  addA x;
;
```

Heap captures split into two cases.

### Borrow closures (default)

Borrow is the default:

- lambda literals use `{ ... }`
- local inner functions use `fn name(...) = ...`

Borrow closures keep their environment on the stack.

- Capturing an **owned** heap binding borrows it; the outer binding stays `Owned`.
- Capturing a **borrowed**, **literal**, or **global** heap binding is also allowed.
- Inside the lambda body, captured heap fields are treated as borrowed.
- Multiple borrow closures may share the same enclosing heap binding.

That stack lifetime is the key restriction: a borrow-capturing closure cannot escape its
defining scope. Returning one is rejected.

```mml
fn withPrefix(prefix: String) =
  let printPrefixed = {
    println (prefix ++ "!");
  };
  printPrefixed ();
;
```

### Move closures (`~`)

Prefix the closure with `~` when it should own its heap captures:

- lambda literals use `~{ ... }`
- local inner functions use `fn ~name(...) = ...`

Move closures allocate their environment on the heap, and the closure value owns it.

- Capturing an **owned** heap binding moves it into the environment.
- The original outer binding becomes `Moved`.
- Capturing a **borrowed** heap binding is rejected.
- Heap literals are cloned into the environment when an owned capture is needed; see
  [Clone operations](#clone-operations).
- Inside the lambda body, captured heap fields are treated as borrowed from the
  environment.

```mml
fn makeGreeter(~name: String): Unit -> Unit =
  ~{
    println ("Hello, " ++ name);
  };
;
```

### Closure ownership

A move-capturing closure is an owned heap value. The binding that receives it is
responsible for freeing it, just like any other owned heap object. It can also be passed
to consuming parameters or returned to move ownership to the caller.

Owning captures does not make the closure call-once. Its body can borrow those captures
repeatedly while the environment retains ownership. Transferring a captured value out gives
up that ownership, so the same value cannot be transferred again.

```mml
fn makeAdder(a: Int): Int -> Int =
  ~{ x: Int -> x + a };
;

fn main() =
  let add5 = makeAdder 5;    // add5 owns the move closure
  let r = apply add5 37;     // add5 is borrowed by apply
  println (int_to_str r)
;                             // add5 is freed here
```

### Environment destruction

When a capturing closure goes out of scope, its environment is destroyed automatically.

For a borrow closure, that just ends the lifetime of the stack environment.

For a move closure, the heap environment is released when the closure goes out of scope.
If it holds heap captures, those are destroyed first.

### Capture style

Closures capture referenced bindings implicitly from the body. The capture mode is
chosen for the whole closure:

- default borrow capture with `{ ... }` / `fn name(...)`
- explicit move capture with `~{ ... }` / `fn ~name(...)`

---

## Partial applications

A partial application is a function value that stores the arguments already supplied and waits
for the remaining arguments. If a stored payload has a heap type, the PAP environment must know
whether that payload is borrowed or owned.

Supplied expressions run once, in source order, when the PAP is created. Calling the PAP
reuses those stored values. This includes `Unit` expressions: their effects occur at creation
even though `Unit` occupies no payload field.

### Ownership rules

The same ownership rules apply to PAP payloads as to ordinary values:

- An already-applied non-consuming parameter stores a borrow.
- An already-applied consuming `~` parameter stores ownership only through an explicit move.
- Borrowed heap payloads may be stored only when the PAP cannot outlive the owner.
- An escaping PAP must not contain borrowed heap payloads.
- Owned heap payloads are destroyed by the PAP environment if the PAP is dropped before it is
  fully applied.
- When a PAP with owned payloads is fully applied, those payloads are forwarded to the consuming
  callee and later PAP cleanup frees only the raw environment.
- A full call consumes a PAP that transfers an owned payload. A second call or use through a
  moved alias is rejected because the first call gives up ownership of the payload. The callee
  may destroy it, return it, or transfer it elsewhere; the PAP cannot supply it again. Passing
  such a PAP to another function requires a consuming `~` parameter because invocation
  consumes the PAP. Borrowing PAPs remain reusable while their owners remain valid.

This rule applies equally to ordinary callables and direct-callable lowering. The source callable
may be direct and non-escaping while the generated PAP value itself escapes; ownership is checked
on the generated value that actually stores the payloads.

See [pap-ownership.mml](../mml/samples/pap-ownership.mml) for a commented example comparing
a reusable owning closure, a borrowing PAP, and a PAP that transfers its payload on invocation.

### Remaining consuming parameters

A remaining consuming parameter transfers its argument when that argument is supplied.
Creating the PAP does not transfer an argument that has not been supplied yet.

```mml
fn measure(extra: Int, ~text: String): Int = extra + text.length;;

fn example(): Int =
  let measure10 = measure 10;
  let text = int_to_str 123;
  let first = measure10 text;
  let second = measure10 (int_to_str 456);
  first + second;
;
```

The first invocation moves `text`. Each invocation supplies a fresh owned String, and
`measure10` retains its scalar capture, so it can be reused. Borrowed arguments and later
uses of moved arguments are rejected. Aliases, returned PAPs, and higher-order calls preserve
the consuming contract.

In staged application, supplying a consuming argument moves it into the next PAP if more
arguments remain. That PAP must either destroy the payload on drop or transfer it once on
invocation. A PAP with both stored and remaining consuming arguments still obeys this
call-once rule for its stored payloads.

### PAPs in struct fields

An owning struct can receive an owned PAP or move closure. Construction moves the function
value into the field, and the original binding becomes unavailable. This applies to scalar
PAPs even when a constant payload allows codegen to omit the environment allocation.
Non-capturing ordinary functions are also accepted; their null environments need no cleanup.
Borrowing PAPs and borrow-capturing closures cannot enter owning fields.

```mml
struct Holder { f: Int -> Int };
fn add(a: Int, b: Int): Int = a + b;;

fn example(): Int =
  let p = add 1;
  let holder = Holder p;
  let first = holder.f 2;
  holder.f first;
;
```

The holder owns the environment through moves, returns, and nesting in other structs. Its
destructor releases the environment once. Function-bearing structs have no generated clone
helper, including structs that contain them transitively.

Field calls preserve the callable's return ownership: allocated results need cleanup, while
static results do not. Callable alternatives reaching the same indirect call must agree on
result ownership and which remaining parameters consume their arguments. Mixing either
contract at that call is rejected.

A field access lends the function value. A local alias can call it while its owner is alive;
it cannot return the borrowed field or transfer it into another owning field or consuming
function parameter. Pass the whole holder to a consuming parameter to transfer its ownership.

Calling a PAP that transfers a stored payload requires ownership of the enclosing aggregate.
The call marks that field consumed; later calls through the field or its aliases are rejected.
Other fields remain usable, but the aggregate cannot be passed or returned as a whole after
one of its fields is consumed. At scope end its destructor releases the consumed field's raw
environment and destroys any remaining fields. Conditional calls retain this cleanup behavior.
Remaining consuming arguments still move at invocation and do not make a reusable PAP call-once.

### Nested consuming PAP calls

A consuming PAP can be called inside an allocating argument expression:

```mml
println (int_to_str (consuming 0));
```

This transfers the stored payload once, just as binding `consuming 0` to a local result
would. The returned `Int` needs no heap ownership. The String allocated by `int_to_str`
is borrowed by `println` and destroyed after the call.

Arguments are evaluated once, in source order. A move in an argument remains visible to
later arguments and statements; nesting does not permit another call to the consumed PAP.
See [pap-nested-consuming.mml](../mml/samples/pap-nested-consuming.mml) for both forms.

### Environment cleanup

For a transfer-bearing PAP, the generated entry changes its environment destructor to the raw
deallocator before forwarding the payload. The consuming caller dispatches destruction after
the call. A dropped, uncalled PAP retains its payload destructor. This keeps the same closure
representation for borrowing and consuming higher-order calls.

PAP environments with only scalar payloads can be released after their last use, before a
terminal call. Payload destructors retain their ordering relative to user effects.

---

## Implementation limitations

These restrictions describe compiler support. Ownership alone does not require them.
Use-after-move rejection and keeping borrows within their owners' lifetimes remain model rules.

### Clone operations

There is no user-accessible clone syntax or clone protocol. Generated clone functions are
compiler-facing helpers. The compiler inserts implicit clones at these boundaries:

- Literal and some global values passed to consuming parameters or owning struct fields.
- Heap literals captured by move closures.
- Non-allocating literal/global return branches when other branches allocate.

These insertions provide owned storage but conceal duplication in the source. They are
implementation exceptions to the model's source-visible clone operation.

### Global-backed aggregates

Global-origin ownership is only partially represented, and aggregate destruction does not
fully distinguish global-backed fields. Some consuming boundaries clone global values into
owned storage. This limits the model's ability to retain static backing without duplication;
it does not permit freeing global-backed storage during local cleanup.

---

## Compile-time errors

| Error                         | Cause                                        |
|-------------------------------|----------------------------------------------|
| Use after move                | Using a binding after ownership transfer     |
| Consuming param not last use  | Value used after being consumed              |
| Borrow escape via return      | Returning a borrowed value as owned          |
| Borrowed to consuming param   | Passing a borrowed value to a `~` parameter  |
