# MinnieML: Language Reference

This document describes the MinnieML language: its syntax, type system, and behavioral
rules.

## Table of contents

1. [Declarations](#1-declarations)
2. [Program structure](#2-program-structure)
3. [Control flow](#3-control-flow)
4. [Type system](#4-type-system)
5. [Operator system](#5-operator-system)
6. [Semantic rules](#6-semantic-rules)
7. [Memory management](#7-memory-management)
8. [Errors](#8-errors)
9. [Standard library](#9-standard-library)
10. [Current limitations](#10-current-limitations)
- [Appendix: Reserved words](#appendix-reserved-words)

---

## 1. Declarations

Every declaration in MML is terminated by a semicolon (`;`).

Binding names (variables, functions) start with a lowercase letter and may contain
letters, digits, and underscores: `[a-z][a-zA-Z0-9_]*`.

Identifiers cannot be [reserved words](#appendix-reserved-words).

### Let bindings

```mml
let name = expr;
let name: Type = expr;
```

Let bindings are immutable. Once bound, a name cannot be reassigned. The type can be
explicitly ascribed or inferred from the expression.

```mml
let x = 42;
let greeting: String = "hello";
let sum = add 1 2;
```

### Function declarations

```mml
fn name(params): ReturnType = body;
```

Parameter types are required. The return type is optional and can be inferred. The body
is a single expression, possibly containing `let` bindings and sequenced expressions
separated by `;` (see [Expression sequencing](#expression-sequencing)).

```mml
fn greet(name: String): String = "Hello, " ++ name;

fn factorial(n: Int): Int =
  if n == 0 then 1
  else n * factorial (n - 1)
  end
;
```

**Nullary functions** (zero parameters) are declared with empty parentheses and must
be explicitly applied to `()` at call sites:

```mml
fn get_value(): Int = 42;
let x = get_value ();
```

**Inline hint:** Prefixing a function or operator declaration with `inline` requests that
the compiler inline it at call sites. This emits the LLVM `inlinehint` attribute; the
optimizer may still choose not to inline if the cost model disagrees.

```mml
inline fn dot(u: Vec3, v: Vec3): Float =
  (u.x *. v.x) +. (u.y *. v.y) +. (u.z *. v.z)
;
```

### Operator declarations

Operator names can be symbolic (from `=!#$%^&*+<>?/\|~-`, e.g. `+`, `==`, `|>`)
or alphanumeric (same rules as binding names, e.g. `and`, `or`, `mod`).

```mml
op name(params): ReturnType precedence associativity = body;
```

Operators require a precedence (integer, higher binds tighter) and associativity
(`left` or `right`). They can be unary (one parameter) or binary (two parameters).

```mml
op ++(a: String, b: String): String 61 right = concat a b;  // string concatenation (standard library)
op -(a: Int): Int 95 right = @native[tpl="sub %type 0, %operand"];
```

See [Operator system](#5-operator-system) for details on precedence, fixity,
overloading, and desugaring.

### Struct declarations

Type names start with an uppercase letter and may contain letters and digits:
`[A-Z][a-zA-Z0-9]*`. This applies to structs, type aliases, and native type
declarations.

```mml
struct Name { field1: Type1, field2: Type2 };
```

Declares a composite type with named fields. The struct name becomes a constructor
function, applied to field values in declaration order by juxtaposition like any
other function call:

```mml
struct Point { x: Int, y: Int };

let p = Point 10 20;
let px = p.x;            // Field access via dot notation
```

See [Structs](#structs) for details on heap classification and memory behavior.

### Type definitions

**Type aliases** give a new name to an existing type:

```mml
type AnInt = Int64;
```

**Native type declarations** lift C/LLVM types into MML's type system.
See [Native type declarations](#native-type-declarations) for the full syntax.

---

## 2. Program structure

### Modules

A source file is a module. The compiler derives the module name from the file path.
There is no `module` keyword at file scope.

Top-level members can be: `let`, `fn`, `op`, `struct`, `type`. Module-level
declarations are visible to all other declarations in the same module regardless of
declaration order.

### Semicolons

Semicolons are **terminators**, not separators. Every top-level declaration and every
`let` binding within a function body ends with `;`. The final expression in a function
body is not terminated by `;` — the `;` that follows belongs to the enclosing
declaration.

```mml
fn example(): Int =
  let x = 1;
  let y = 2;
  x + y
;
```

The two `let` bindings end with `;`. The final expression `x + y` is the return
value. The trailing `;` terminates the `fn` declaration.

### Expression sequencing

Function bodies can contain a sequence of `let` bindings followed by a final
expression. The value of the body is the value of its last expression.

```mml
fn process(name: String): Unit =
  let greeting = "Hello, " ++ name;
  println greeting;
  println "done"
;
```

Expressions can be sequenced directly with `;` for side effects:

```mml
fn side_effects(): Unit =
  println "first";
  println "second";
  println "third"
;
```

### Literals

- **Integers**: `[0-9]+` (e.g. `42`, `0`, `1234`)
- **Floats**: `[0-9]+\.[0-9]+` or `\.[0-9]+` (e.g. `3.14`, `.5`, `0.001`)
- **Strings**: `"[^"]*"` — raw content, no escape processing during parsing. Multiline.
  Escape handling is performed at code generation time using LLVM hex escapes.
- **Booleans**: `true`, `false`
- **Unit**: `()`, the only value of type `Unit`

### Typed holes

`???` is a placeholder expression that marks unfinished code. It allows a program
to compile and type-check even when parts of the implementation are missing. At
runtime, reaching a hole prints the source location to stderr and terminates the
program with exit code 1.

A hole adopts the type expected by its context (return type, let binding type
annotation, etc.). If the compiler cannot determine the type, it reports an error
requesting a type annotation.

```mml
fn todo(x: Int): Int = ???;         // compiles, crashes at runtime if called
let y: String = ???;                // type inferred from annotation
let z = ???;                        // error: can't infer type
```

### Comments

- **Line comments**: `// comment text` — from `//` to end of line
- **Documentation comments**: `/* ... */` — attached to members, can nest

---

## 3. Control flow

### Conditionals

Conditionals are expressions. Every `if` block is terminated by `end`.

**If/else** (returns a value):

```mml
if condition then expr1 else expr2 end
```

**If/elif/else** (multiple branches):

```mml
if cond1 then
  expr1
elif cond2 then
  expr2
else
  expr3
end
```

**Single-branch if** (returns `Unit`):

```mml
if condition then
  expr
end
```

The compiler inserts an implicit `else ()` for single-branch conditionals, so both
branches return `Unit`. Only use `else` when the `if` needs to return a value.

**Rules**:
- The condition must have type `Bool`
- When `else` is present, both branches must have the same type
- The entire `if` expression has the type of its branches
- `end` is always required

```mml
fn fizzbuzz(n: Int): Unit =
  if n % 15 == 0 then println "FizzBuzz"
  elif n % 3 == 0 then println "Fizz"
  elif n % 5 == 0 then println "Buzz"
  else println (to_string n)
  end
;
```

### Recursion and tail calls

MML has no loop constructs (`while`, `for`, etc.). All iteration is done with
recursion. The compiler detects and optimizes tail calls automatically — no annotation
is needed.

A call is in tail position when it is the last expression evaluated before a function
returns:

```mml
fn count_down(n: Int): Unit =
  if n > 0 then
    println (to_string n);
    count_down (n - 1)
  end
;

fn sum_loop(i: Int, limit: Int, acc: Int): Int =
  if i == limit then acc
  else sum_loop (i + 1) limit (acc + i)
  end
;
```

Loops with state use the accumulator pattern:

```mml
fn count(arr: IntArray, i: Int, size: Int, acc: Int): Int =
  if i < size then
    let v = unsafe_ar_int_get arr i;
    count arr (i + 1) size (acc + v)
  else acc
  end
;
```

### No loops

MML has no `while`, `for`, or any loop construct. All iteration is expressed via
recursion. See [Recursion and tail calls](#recursion-and-tail-calls).

---

## 4. Type system

### Basic types

MML provides basic types that map directly to LLVM types:

```mml
Int64, Int32, Int16, Int8      // Signed integers
Float, Double                  // Floating point
Bool                           // Boolean
Char                           // Character (i8)
Unit                           // Unit type (void)
String                         // Struct with length and data pointer
```

**Type aliases**:
```mml
Int   → Int64   // Default integer type
Byte  → Int8
Word  → Int8
```

### Arrays

MML does not have polymorphic types yet, so arrays are provided as monomorphic
native types: `IntArray`, `StringArray`, and `FloatArray`. These are backed by C
runtime implementations with dedicated accessor functions (`ar_int_get`,
`ar_int_set`, `ar_float_get`, `ar_float_set`, etc.). Once the type checker
supports generics, these will be replaced by a single polymorphic array type.

### Native type declarations

Native type declarations lift C/LLVM types into MML's type system without defining
the types themselves. Three forms exist:

**Primitive type**:
```mml
type SizeT = @native[t=i64];
```

**Opaque pointer**:
```mml
type CharPtr = @native[t=*i8];
```

**Struct mirror**:
```mml
type String = @native {
  length: SizeT,
  data:   CharPtr
};
```

Each `@native` declaration creates a distinct type. Two declarations with the same
underlying representation are not interchangeable:

```mml
type Int64 = @native[t=i64];
type AnotherInt64 = @native[t=i64];
// Int64 and AnotherInt64 are different types
```

Native struct declarations are mirrors of types defined in the linked C runtime. MML
uses these declarations to understand memory layout and generate correct IR.

### Structs

Structs are user-defined composite types with named fields.

**Declaration**:
```mml
struct User { name: String, role: String };
struct Point { x: Int, y: Int };
```

**Construction**: The struct name is a constructor function. It takes fields in
declaration order by juxtaposition, like any other function:

```mml
let p = Point 10 20;
let u = User "Alice" "admin";
```

**Field access** via dot notation:

```mml
let name = u.name;
let px = p.x;
```

**Heap classification**: A struct is a heap type if any of its fields are heap types.
Heap structs are tracked by the ownership system and freed automatically:

```mml
struct User { name: String, age: Int };  // Heap type (String is heap-allocated)
struct Point { x: Int, y: Int };         // Not a heap type
```

The compiler generates `__free_T` and `__clone_T` functions for heap structs
automatically. See [Memory management](#7-memory-management) for ownership details.

### Function types

Functions have types of the form `T1 → T2 → ... → Tn → R`:

```mml
fn add(a: Int, b: Int): Int = a + b;
// Type: Int → Int → Int

fn print(s: String): Unit = @native;
// Type: String → Unit
```

Functions are curried — each arrow represents a function taking one argument.

### Type compatibility

Two types are compatible if:
1. They are the same type (same name)
2. One is a type alias that resolves to the other

Each `@native` declaration creates a distinct type. The type checker does not consider
the underlying representation when comparing types.

### Compound types

**Tuples**: `(T1, T2, ..., Tn)`
```mml
let pair: (Int, String) = (42, "hello");
```

**Function types**: `T1 → T2 → R`
```mml
let f: Int → Int → Int = add;
```

---

## 5. Operator system

### User-defined operators

All operators in MML are user-defined, including arithmetic and logical operators.
There are no built-in operators. The standard operators (`+`, `-`, `*`, `/`, `==`,
`and`, etc.) are provided by the standard library as ordinary operator
declarations.

```mml
op +(a: Int, b: Int): Int 60 left = @native[tpl="add %type %operand1, %operand2"];
op *(a: Int, b: Int): Int 80 left = @native[tpl="mul %type %operand1, %operand2"];
op %(a: Int, b: Int): Int 80 left = @native[tpl="srem %type %operand1, %operand2"];
```

### Operator kinds

1. **Binary operators**: Two operands.
   `op name(a: T1, b: T2): R prec assoc = body;`

2. **Unary operators**: One operand.
   `op name(a: T): R prec assoc = body;`

### Operators are functions

An operator is just a function with metadata (precedence, associativity, arity)
that tells the expression rewriter how to desugar it. After rewriting, operator
calls become ordinary function applications. There is no separate calling
convention or special treatment at runtime.

Because operator names can be alphabetic (e.g., `not`, `and`, `mod`), they share
the same namespace as functions. A function and an operator with the same name
conflict — the compiler rejects the duplicate.

### Operator overloading

Currently, the only form of overloading in MML is by arity: a unary and a binary operator
can share the same name (e.g., unary `-` for negation and binary `-` for
subtraction). The compiler distinguishes them during expression rewriting based
on position and argument count.

See [Current limitations](#10-current-limitations) for the current scope of
overloading.

```mml
// Valid: unary and binary - coexist
op -(a: Int): Int 95 right = @native[tpl="sub %type 0, %operand"];
op -(a: Int, b: Int): Int 60 left = @native[tpl="sub %type %operand1, %operand2"];

// Invalid: duplicate binary operator
op +(a: Int, b: Int): Int 60 left = ...;
op +(x: Float, y: Float): Float 60 left = ...;  // error: duplicate name
```

### Precedence and associativity

Every operator has a precedence (integer, higher binds tighter) and associativity
(`left` or `right`).

**Standard precedence levels** (used by the standard library operators):
```
95: Unary operators (prefix: +, -, not)
80: Multiplicative (*, /)
60: Additive (+, -)
50: Comparison (==, !=, <, >, <=, >=)
40: Logical and
30: Logical or
```

These are conventional values, not language-enforced. User-defined operators can use
any precedence value.

### Fixity

Fixity (prefix, infix, or postfix) is determined by the combination of arity and
associativity: binary operators are always infix, unary operators with `right`
associativity are prefix (e.g., `-x`), and unary operators with `left`
associativity are postfix (e.g., `x!`). There is no separate fixity annotation —
arity and associativity are sufficient to determine operator position.

### Operators as functions

Operators desugar to function calls:
```mml
1 + 2      // desugars to: + 1 2
-5         // desugars to: - 5
a * b + c  // desugars to: + (* a b) c
```

---

## 6. Semantic rules

### Scoping

- Bindings are visible from the point of declaration to the end of the enclosing scope.
- Function parameters are visible within the function body.
- Module-level declarations are visible to all members in the module, regardless of
  declaration order (no forward-declaration needed).
- Nested functions and closures are not yet supported.

### Visibility (not enforced yet)

Declarations carry a visibility flag for future access control, but the compiler does
not enforce it yet.

- **Public**: Importable and referenceable from any module.
- **Protected**: Visible to the defining module and related modules.
- **Private**: Confined to the defining module.

### Function application

Functions are curried: `f a b` desugars to `((f a) b)`.

**Partial application**:
```mml
fn add(a: Int, b: Int): Int = a + b;

let add5 = add 5;       // Partial application: Int → Int
let result = add5 10;   // Full application: 15
```

**Juxtaposition**: Function application is written as `f x` (apply `f` to `x`).
This applies uniformly: regular functions, operators, and struct constructors are
all called the same way.

### Nullary functions

Functions with zero parameters must be explicitly applied to `()`:

```mml
fn get_value(): Int = 42;
let x = get_value ();  // Call: explicit application to unit
let f = get_value;     // Reference: no call
```

### Native declarations

Functions and operators can have `@native` bodies, indicating external implementation:

```mml
fn print(s: String): Unit = @native;
fn str_to_int(s: String): Int = @native;
op +(a: Int, b: Int): Int 60 left = @native[tpl="add %type %operand1, %operand2"];
```

The codegen generates forward declarations and the linker resolves them. Unresolved
natives produce linker errors.

### Native templates

Functions can use `@native[tpl="..."]` to emit inline LLVM IR:

```mml
fn ctpop(x: Int): Int = @native[tpl="call i64 @llvm.ctpop.i64(i64 %operand)"];
```

**Template placeholders**:
- `%operand` — single argument (unary functions)
- `%operand1`, `%operand2`, ... — multiple arguments (1-indexed)
- `%type` — LLVM type of first argument

### Memory effects on native functions

Native functions that allocate memory can be annotated with `[mem=alloc]`:

```mml
fn readline(): String = @native[mem=alloc];
fn concat(a: String, b: String): String = @native[mem=alloc];
fn to_string(n: Int): String = @native[mem=alloc];
```

**Memory effect attributes**:
- `[mem=alloc]` — Function allocates new memory. Caller owns the return value.
- `[mem=static]` — Function returns static/existing memory. Caller does not own it.
- *None* — Default. No memory effect, caller does not own the return.

These can be combined with templates: `@native[mem=alloc, tpl="..."]`

### Consuming parameters

Parameters can be marked as consuming with the `~` prefix:

```mml
fn take_ownership(~s: String): Unit = ...;
```

**Rules**:
- `~` appears only in the declaration, not at call sites
- A consuming parameter transfers ownership from caller to callee
- The caller must not use the value after passing it to a consuming parameter
- Consuming parameters in partial applications are not allowed

---

## 7. Memory management

MML uses affine ownership with borrow-by-default semantics for automatic memory
management.

### Ownership model

Each heap-allocated value has exactly one owner. By default, function calls borrow
values (caller retains ownership). Ownership can be transferred using consuming
parameters.

**Ownership rules**:
- Values returned by allocating functions (`[mem=alloc]`) are owned by the caller
- Passing a value to a regular parameter borrows it; the caller retains ownership
- Passing a value to a consuming parameter (`~`) transfers ownership to the callee
- Using a value after its ownership was transferred is an error
- Owned values are automatically freed when they go out of scope

### Heap types

A type is heap-allocated if declared with `[mem=heap]`:

```mml
type String = @native[mem=heap] { length: Int64, data: CharPtr };
type Buffer = @native[mem=heap, t=*i8];
```

User-defined structs are heap types if they contain any heap-typed fields:

```mml
struct User { name: String, age: Int };  // Heap type (contains String)
struct Point { x: Int, y: Int };         // Not a heap type
```

### Struct construction and cloning

Struct constructors clone their arguments. For heap-typed fields, the constructor
calls `__clone_T` to deep-copy the value. The caller retains ownership of the original.

```mml
fn example(): Unit =
  let name = readline();       // name is owned
  let user = User name 25;     // Constructor clones name
  println name;                // OK: caller still owns name
  println user.name            // OK: user owns its own copy
;
```

### Ownership examples

```mml
fn example(): Unit =
  let s = readline();    // s is owned (readline allocates)
  println s;             // println borrows s
  println s              // s can be used again (still owned)
;                        // s is automatically freed

fn transfer_example(): Unit =
  let s = readline();
  consume_string s       // If consume_string takes ~s, ownership transfers
  // println s;          // error: use after move
;
```

### Return value ownership

Functions returning heap types transfer ownership to the caller:

```mml
fn make_greeting(name: String): String =
  "Hello, " ++ name
;

fn main(): Unit =
  let greeting = make_greeting "World";
  println greeting
;                        // greeting is freed here
```

---

## 8. Errors

### Name and reference errors

- Referencing an undefined variable, function, or parameter is an error.
- Referencing an undefined type is an error.
- Declaring multiple bindings with the same name in a module is an error.

### Expression errors

- Malformed expressions that cannot be processed are errors.
- Terms appearing in invalid positions are errors.

### Type errors

- Function parameters must have explicit type annotations.
- When type inference cannot determine a return type, an explicit annotation is required.
- Applying a value to a non-function is an error.
- Passing too many arguments to a function is an error.
- `if` branches must have the same type.
- `if` conditions must have type `Bool`.

### Ownership errors

- Using a binding after its ownership was transferred is an error (use after move).
- A consuming parameter requires the argument to be its last use.
- Partially applying a function with consuming parameters is not allowed.
- Conditional branches must produce compatible ownership states.

---

## 9. Standard library

The standard library provides a set of types, operators, and functions available
in every module without explicit imports. For implementation details, see
[Standard library injection](compiler-design.md#9-standard-library-injection) in
the compiler design document.

### Types

#### Primitive types

| Type     | LLVM     | Description           |
|----------|----------|-----------------------|
| `Int64`  | `i64`    | 64-bit signed integer |
| `Int32`  | `i32`    | 32-bit signed integer |
| `Int16`  | `i16`    | 16-bit signed integer |
| `Int8`   | `i8`     | 8-bit signed integer  |
| `Float`  | `float`  | 32-bit IEEE 754       |
| `Double` | `double` | 64-bit IEEE 754       |
| `Bool`   | `i1`     | Boolean               |
| `Char`   | `i8`     | Character (same as Int8)  |
| `SizeT`  | `i64`    | Size type (same as Int64) |
| `Unit`   | `void`   | Unit type             |

#### Type aliases

| Alias  | Target |
|--------|--------|
| `Int`  | `Int64` |
| `Byte` | `Int8`  |
| `Word` | `Int8`  |

#### Composite types

| Type          | Description                                                  |
|---------------|--------------------------------------------------------------|
| `String`      | Struct: `{ length: Int64, data: CharPtr }`. Heap-allocated.  |
| `Buffer`      | Opaque pointer to a buffered I/O writer. Heap-allocated.     |
| `IntArray`    | Struct: `{ length: Int64, data: Int64Ptr }`. Heap-allocated. |
| `StringArray` | Struct: `{ length: Int64, data: StringPtr }`. Heap-allocated.|
| `FloatArray`  | Struct: `{ length: Int64, data: FloatPtr }`. Heap-allocated. |

### Operators

#### Integer arithmetic

| Operator  | Type                | Prec | Assoc | Description             |
|-----------|---------------------|------|-------|-------------------------|
| `a + b`   | `Int -> Int -> Int` | 60   | left  | Addition                |
| `a - b`   | `Int -> Int -> Int` | 60   | left  | Subtraction             |
| `a * b`   | `Int -> Int -> Int` | 80   | left  | Multiplication          |
| `a / b`   | `Int -> Int -> Int` | 80   | left  | Integer division        |
| `a % b`   | `Int -> Int -> Int` | 80   | left  | Remainder               |
| `a << b`  | `Int -> Int -> Int` | 55   | left  | Left shift              |
| `a >> b`  | `Int -> Int -> Int` | 55   | left  | Arithmetic right shift  |
| `+a`      | `Int -> Int`        | 95   | right | Unary plus              |
| `-a`      | `Int -> Int`        | 95   | right | Unary negation          |

#### Integer comparison

| Operator  | Type                 | Prec | Assoc |
|-----------|----------------------|------|-------|
| `a == b`  | `Int -> Int -> Bool` | 50   | left  |
| `a != b`  | `Int -> Int -> Bool` | 50   | left  |
| `a < b`   | `Int -> Int -> Bool` | 50   | left  |
| `a > b`   | `Int -> Int -> Bool` | 50   | left  |
| `a <= b`  | `Int -> Int -> Bool` | 50   | left  |
| `a >= b`  | `Int -> Int -> Bool` | 50   | left  |

#### Logical

| Operator   | Type                   | Prec | Assoc |
|------------|------------------------|------|-------|
| `a and b`  | `Bool -> Bool -> Bool` | 40   | left  |
| `a or b`   | `Bool -> Bool -> Bool` | 30   | left  |
| `not a`    | `Bool -> Bool`         | 95   | right |

#### Float arithmetic

Float operators use a `.` suffix to distinguish them from integer operators.

| Operator  | Type                        | Prec | Assoc |
|-----------|-----------------------------|------|-------|
| `a +. b`  | `Float -> Float -> Float`   | 60   | left  |
| `a -. b`  | `Float -> Float -> Float`   | 60   | left  |
| `a *. b`  | `Float -> Float -> Float`   | 80   | left  |
| `a /. b`  | `Float -> Float -> Float`   | 80   | left  |
| `-.a`     | `Float -> Float`            | 95   | right |

#### Float comparison

| Operator   | Type                      | Prec | Assoc |
|------------|---------------------------|------|-------|
| `a <. b`   | `Float -> Float -> Bool`  | 50   | left  |
| `a >. b`   | `Float -> Float -> Bool`  | 50   | left  |
| `a <=. b`  | `Float -> Float -> Bool`  | 50   | left  |
| `a >=. b`  | `Float -> Float -> Bool`  | 50   | left  |
| `a ==. b`  | `Float -> Float -> Bool`  | 50   | left  |
| `a !=. b`  | `Float -> Float -> Bool`  | 50   | left  |

#### String

| Operator  | Type                         | Prec | Assoc | Description                    |
|-----------|------------------------------|------|-------|--------------------------------|
| `a ++ b`  | `String -> String -> String` | 61   | right | Concatenation (calls `concat`) |

### Functions

#### I/O

| Function         | Type              | Description                        |
|------------------|-------------------|------------------------------------|
| `print(s)`       | `String -> Unit`  | Print string to stdout             |
| `println(s)`     | `String -> Unit`  | Print string with newline          |
| `readline()`     | `() -> String`    | Read line from stdin. Allocates.   |
| `mml_sys_flush()`| `() -> Unit`      | Flush stdout                       |

#### String operations

| Function         | Type                         | Description                          |
|------------------|------------------------------|--------------------------------------|
| `concat(a, b)`   | `String -> String -> String` | Concatenate two strings. Allocates.  |
| `int_to_str(n)`  | `Int -> String`              | Integer to string. Allocates.        |
| `float_to_str(f)`| `Float -> String`            | Float to string. Allocates.          |
| `str_to_int(s)`  | `String -> Int`              | Parse integer from string            |

#### Type conversion

| Function          | Type             | Description                |
|-------------------|------------------|----------------------------|
| `int_to_float(n)` | `Int -> Float`  | Convert integer to float   |
| `float_to_int(f)` | `Float -> Int`  | Truncate float to integer  |

#### Float math

| Function  | Type             | Description                     |
|-----------|------------------|---------------------------------|
| `sqrt(x)` | `Float -> Float` | Square root (LLVM intrinsic)   |
| `fabs(x)` | `Float -> Float` | Absolute value (LLVM intrinsic)|

#### Buffered I/O

Buffers provide efficient batched output. Write operations accumulate in memory and
are flushed explicitly or when the buffer is freed.

| Function                   | Type                        | Description                                  |
|----------------------------|-----------------------------|----------------------------------------------|
| `mkBuffer()`               | `() -> Buffer`              | Create buffer writing to stdout. Allocates.  |
| `mkBufferWithFd(fd)`       | `Int -> Buffer`             | Create buffer for file descriptor. Allocates.|
| `mkBufferWithSize(size)`   | `Int -> Buffer`             | Create buffer with custom capacity. Allocates.|
| `flush(b)`                 | `Buffer -> Unit`            | Flush buffer contents                        |
| `buffer_write(b, s)`       | `Buffer -> String -> Unit`  | Write string                                 |
| `buffer_writeln(b, s)`     | `Buffer -> String -> Unit`  | Write string with newline                    |
| `buffer_write_int(b, n)`   | `Buffer -> Int -> Unit`     | Write integer                                |
| `buffer_writeln_int(b, n)` | `Buffer -> Int -> Unit`     | Write integer with newline                   |
| `buffer_write_float(b, f)` | `Buffer -> Float -> Unit`   | Write float                                  |
| `buffer_writeln_float(b, f)`| `Buffer -> Float -> Unit`  | Write float with newline                     |

#### File I/O

| Function              | Type             | Description                        |
|-----------------------|------------------|------------------------------------|
| `open_file_read(path)` | `String -> Int` | Open file for reading, returns fd  |
| `open_file_write(path)`| `String -> Int` | Open file for writing, returns fd  |
| `open_file_append(path)`| `String -> Int`| Open file for appending, returns fd|
| `close_file(fd)`       | `Int -> Unit`   | Close file descriptor              |
| `read_line_fd(fd)`     | `Int -> String` | Read line from fd. Allocates.      |

#### Array operations

Each array type (`IntArray`, `StringArray`, `FloatArray`) has the same set of
operations. The table below uses `IntArray` / `Int` as the example; substitute
the appropriate types for the other families.

| Function                    | Type                            | Description        |
|-----------------------------|---------------------------------|--------------------|
| `ar_int_new(size)`          | `Int -> IntArray`               | Create. Allocates. |
| `ar_int_set(arr, i, v)`    | `IntArray -> Int -> Int -> Unit` | Bounds-checked set |
| `ar_int_get(arr, i)`       | `IntArray -> Int -> Int`        | Bounds-checked get |
| `unsafe_ar_int_set(arr, i, v)` | `IntArray -> Int -> Int -> Unit` | Unchecked set  |
| `unsafe_ar_int_get(arr, i)` | `IntArray -> Int -> Int`       | Unchecked get      |
| `ar_int_len(arr)`           | `IntArray -> Int`              | Array length       |

The `StringArray` family uses `ar_str_*` and the `FloatArray` family uses
`ar_float_*`. The `StringArray` family does not have `unsafe_ar_str_set` or
`unsafe_ar_str_get` variants.

---

## 10. Current limitations

**No nested functions**: Functions cannot be defined inside other functions. MML does
not support closures yet. To access values from an outer scope, pass them as explicit
parameters and lift the function to the top level.

**No generics**: The type checker does not support parametric polymorphism yet.
Monomorphic workarounds (e.g., `IntArray`, `StringArray`, `FloatArray`) are used
in the meantime.

**Overloading**: Operators can be overloaded by arity — a unary and a binary
operator can share a name — but two binary operators with the same name are
rejected even if their parameter types differ. Functions cannot be overloaded.
Type-based dispatch requires protocols (ad-hoc polymorphism) which are not yet
implemented. These restrictions follow from the current state of the type system
rather than from a language design decision.

---

## Appendix: Reserved words

- `let`
- `fn`
- `op`
- `type`
- `struct`
- `module`
- `if`
- `then`
- `elif`
- `else`
- `end`
- `inline`
- `@native`
- `???`
- `_`
- `~`
