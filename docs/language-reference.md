# MinnieML: Language Reference

This document describes the MinnieML language: its syntax, type system, and behavioral
rules.

For source-formatting conventions, see the [MML style guide](mml-style-guide.md).

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

### Visibility modifiers

Top-level declarations can be prefixed with a visibility modifier:

```mml
pub fn main(): Unit = ();;
prot let cache_size = 1024;
priv fn helper(x: Int): Int = x;;
```

Available modifiers:

- `pub` — public
- `prot` — protected
- `priv` — private

If omitted, visibility defaults to `prot`.

Most visibility rules are not enforced yet. One exception: when building an
executable, `main` must be declared `pub`.

### Let bindings

```text
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

```text
fn name(params): ReturnType = body;
```

Parameter types are required. The return type is optional and can be inferred. The body
is a single expression, possibly containing `let` bindings and sequenced expressions
separated by `;` (see [Expression sequencing](#expression-sequencing)).

```mml
fn greet(name: String): String = "Hello, " ++ name;;

fn factorial(n: Int): Int =
  if n == 0 then
    1;
  else
    n * factorial (n - 1);
  ;
;
```

**Nullary functions** (zero parameters) are declared with empty parentheses and must
be explicitly applied to `()` at call sites:

```mml
fn get_value(): Int = 42;;
let x = get_value ();
```

**Inner functions** use the same `fn` syntax inside expression bodies. They are scoped
to the surrounding expression and are equivalent to a local let-bound lambda with the
same name, parameters, and return type:

```mml
pub fn countdown(from: Int): Unit =
  fn loop(i: Int): Unit =
    if i > 0 then
      println (int_to_str i);
      loop (i - 1);
    ;
  ;

  loop from;
;
```

This is surface syntax for the same core form as a local `let` binding whose value is a
lambda. The inner function name is in scope inside its body, so recursion works the same
way as with let-bound lambdas.

**Inline hint:** Prefixing a function or operator declaration with `inline` requests that
the compiler inline it at call sites. This emits the LLVM `inlinehint` attribute; the
optimizer may still choose not to inline if the cost model disagrees.

```mml
inline fn dot(u: Vec3, v: Vec3): Float =
  (u.x * v.x) + (u.y * v.y) + (u.z * v.z);
;
```

### Lambda expressions

Lambda expressions create anonymous function values. The basic form is
`{ params -> body }`:

```mml
{ x: Int -> x + 1 }
```

**Parameter syntax** matches function parameters. Parameters can be annotated,
inferred, or marked as consuming with `~`:

```mml
fn apply(f: Int -> Int, x: Int): Int = f x;;

let result = apply { x -> x + 1 } 41;   // x: Int inferred from f's type
let consume = { ~s: String -> print s };
```

Type inference draws on two sources:

- top-down expected types from the surrounding context
- bottom-up anchors from the lambda body, such as operator use or calls to
  named functions

When both are available, the surrounding expected type wins. If the available
evidence does not point to one type, the compiler reports an inference error.
For example, `{ x -> x + 1.0; }` infers `x: Float` from the other operand.
`{ x, y -> x + y; }` needs annotations or an expected function type: `+` alone
does not choose between Int and Float.

**Multiple parameters** are separated by commas:

```mml
{ a: Int, b: Int -> a + b }
```

**Nullary lambdas** (thunks) omit the arrow:

```mml
{ println "hello" }
```

A nullary lambda has type `Unit -> R` and is called by applying it to `()`.
Here `()` is the value of type `Unit`, not a special empty-argument call form.
See [Literals](#literals).

```mml
let greet = { println "hello" };
let greet_result = greet ();
```

**Body expressions** follow the same rules as function bodies — `let` bindings
followed by a final expression:

```mml
let f = { x: Int ->
  let y = x + 1;
  y * 2;
};
```

**Return type ascription** can be written after the closing brace:

```mml
let loop = {
  println "tick";
  ()
}: Unit;
```

**Let-bound lambdas** can carry an explicit function type:

```mml
type ForeverFn = Unit -> Unit;

let loop: ForeverFn = {
  println "Type a number:";
}: Unit;
```

The `inline` hint is supported on top-level function and operator declarations,
not on let-bound lambdas.

**Closures**: Lambdas can reference bindings from enclosing scopes. Those
bindings are captured when the lambda is created:

```mml
fn runWith(a: Int, x: Int): Int =
  let addA = { y: Int -> y + a };
  addA x;
;
```

Here `a` is captured from the enclosing function's parameter.

Capture is borrow by default. Prefix a lambda with `~` to create a
move-capturing closure instead:

```mml
fn makeGreeter(~name: String): Unit -> Unit =
  ~{
    println ("Hello, " ++ name);
  };
;
```

Local inner functions follow the same rule: `fn name(...)` captures by borrow,
while `fn ~name(...)` captures by move. See [Memory management](#7-memory-management)
for the language-level summary and [the memory model](memory-model.md) for the full
ownership rules.

Module-level bindings are not captures; they are referenced directly like other
global declarations.

**Nested lambdas**: Inner lambdas can capture bindings from any enclosing
scope:

```mml
let outer = { x: Int ->
  let y = x + 1;
  apply { z: Int -> x + y + z } 100
};
```

If an inner lambda captures a binding from outside the enclosing lambda, the
capture propagates outward. The enclosing lambda must also carry it in its
environment.

### Operator declarations

Operator names can be symbolic (from `=!#$%^&*+<>?/\|.-`, e.g. `+`, `==`, `|>`)
or alphanumeric (same rules as binding names, e.g. `and`, `or`, `mod`).

```text
op name(params): ReturnType precedence associativity = body;
```

Every operator has a precedence (integer, higher binds tighter) and associativity
(`left` or `right`). A free declaration may omit either: precedence defaults to 50, and
associativity defaults to `right` for unary operators and `left` for binary operators.
An instance operator inherits omitted attributes from its protocol signature.
Operators can be unary (one parameter) or binary (two parameters).

```mml
op ++(a: String, b: String): String 61 right = concat a b;;  // string concatenation
op -(a: Int): Int 95 right = ???;;
```

See [Operator system](#5-operator-system) for details on precedence, fixity,
overloading, and desugaring.

### Protocols and instances

A protocol declares the signatures required for one type parameter. An instance supplies
their implementations for a concrete type:

```mml
protocol Show 'T =
  fn show(value: 'T): String;
;

instance Show Int =
  fn show(value) = int_to_str value;;
;

instance Show Bool =
  fn show(value) =
    if value then "true";
    else "false";
    ;
  ;
;

let number_text = show 42;
let flag_text = show true;
let show_int: Int -> String = show;
```

The argument or expected type selects the instance at compile time. A protocol describes a
constraint on its type parameter; it is not a value type that can annotate a variable.

Required signatures declare every parameter and result type and have no body. Instance members
may omit those types or repeat them exactly. Consuming parameter markers must match the signature.
There is one instance per protocol and concrete type; an alias of a type denotes the same instance.

Operators follow the same rules:

```mml
protocol Combine 'T =
  op <+>(left: 'T, right: 'T): 'T 60 left;
;

instance Combine Int =
  op <+>(left, right) = left + right;;
;

let total = 20 <+> 22;
let add_ten: Int -> Int = (<+>) 10;
```

The signature supplies omitted instance precedence and associativity. An explicit restatement
must match it, even if the written value is the usual free-operator default.

Function values and partial calls must select a concrete instance where they are bound.
Their expected function type can supply missing information, as in `show_int` and `add_ten`.
An unconstrained reference is an error even if only one instance happens to be declared.
Missing instances, incompatible operands, and multiple applicable signatures also produce errors.
Ordinary lexical bindings and parameters shadow protocol member names.

See the runnable [named-function sample](../mml/samples/protocol-functions.mml) and
[operator sample](../mml/samples/protocol-operators.mml). Default implementations, multiple type
parameters, protocol prerequisites, parameterized instances, and `where` clauses are not supported.
`where` is recognized in a signature header to report the unsupported constraint syntax;
it remains available as an ordinary binding name.

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

Top-level members can be: `let`, `fn`, `op`, `struct`, `type`. Each can be
prefixed by an optional visibility modifier (`pub`, `prot`, `priv`).
Module-level declarations are visible to all other declarations in the same
module regardless of declaration order.

### Native library linking

An optional `@link` directive before all module members supplies native libraries when
building an executable:

```mml
@link["z"];

fn zlibCompileFlags(): Int64 = @metal:ffi;;

pub fn main(): Unit =
  println (int64_to_str (zlibCompileFlags ()));
;
```

The directive requires at least one quoted library name and a trailing semicolon. Multiple
names are comma-separated: `@link["one", "two"];`. Whitespace and line comments are allowed.
Only one directive is permitted, before every declaration; it cannot appear in expressions.
Names start with an ASCII letter, digit, or underscore and may then contain letters, digits,
underscores, dots, plus signs, and hyphens. Paths, raw linker flags, and `static` / `shared`
qualifiers are not accepted.

Each name becomes a separate `-l<name>` argument after the program input. Names retain their
order and repetitions. The target linker discovers libraries and chooses static or shared
linkage using its normal rules. The compiler does not install libraries or configure their
runtime search paths. Foreign declarations use `@metal:ffi` and must match the library ABI.

Library mode produces a relocatable `.o` and discards these inputs, warning once per name:
`linking directives are discarded in library mode`. The consumer supplies the libraries at
its final link. AST output and an IR comment retain the directive without looking up libraries.
See [the zlib sample](../mml/samples/link-zlib.mml) for a complete executable.

### Semicolons

Semicolons are **terminators**, not separators. Every expression is terminated by `;`,
including each branch body and the enclosing `if` expression. Declarations that require
termination, such as `struct`, have their own `;`, independently of any expression they
contain. These rules apply at every nesting level.

Within an expression body, these terminators also delimit a
[scoped sequence](#expression-sequencing).

When a function or operator body is an expression, its terminator and the declaration's
terminator are distinct; in compact syntax they may appear as `;;`.

```mml
fn example(): Int =
  let x = 1;
  let y = 2;
  x + y;
;
```

The two `let` binding expressions each end with `;`. The `;` after `x + y` terminates
the final body expression, and the final standalone `;` terminates the function declaration.

For formatting conventions around semicolon placement, see the
[MML style guide](mml-style-guide.md#semicolons).

### Expression sequencing

Expression sequencing is scoped and right-associated: in `first; second; result;`,
`first` is evaluated before the remainder `second; result;`; the final expression supplies
the value and type of the sequence.

A local `let` evaluates its value, then makes the name available to the code that follows
in the same scope. An inner `fn` is also available after its declaration and can call
itself recursively. Bindings and expression statements can be mixed freely; they run
in source order.

Expressions can be sequenced directly with `;` for side effects;
'statement' expressions must have type `Unit` and intermediate
values are discarded.

```mml
fn side_effects(): Unit =
  println "first";
  println "second";
  println "third";
;
```

```mml
fn process(name: String): Unit =
  let greeting = "Hello, " ++ name;
  println greeting; // greeting is available here and below.
  let farewell = "Goodbye, " ++ name;
  println farewell; // farewell is available only after its binding.
;
```

### Literals

- **Integers**: `[0-9]+` (e.g. `42`, `0`, `1234`)
- **Floats**: `[0-9]+\.[0-9]+` or `\.[0-9]+` (e.g. `3.14`, `.5`, `0.001`)
- **Strings**: `"[^"]*"` — raw content, no escape processing during parsing. Multiline.
  Escape handling is performed at code generation time using LLVM hex escapes.
- **Booleans**: `true`, `false`
- **Unit**: `()`, the only value of type `Unit`

### Term-level type ascriptions

Any term can be ascribed with `: Type`:

```mml
let answer = 42: Int;
let label = "ok": String;
```

The ascribed type must be compatible with the term's computed type.

For lambda literals, a post-brace ascription constrains the lambda's **return
type**, not the full function value:

```mml
let loop = {
  println "tick";
  ()
}: Unit;
```

### Typed holes

`???` is a placeholder expression that marks unfinished code. It allows a program
to compile and type-check even when parts of the implementation are missing. At
runtime, reaching a hole prints the source location to stderr and terminates the
program with exit code 1.

A hole adopts the type expected by its context (return type, let binding type
annotation, etc.). If the compiler cannot determine the type, it reports an error
requesting a type annotation.

```text
fn todo(x: Int): Int = ???;;        // compiles, crashes at runtime if called
let y: String = ???;                // type inferred from annotation
let z = ???;                        // error: can't infer type
```

### Comments

- **Line comments**: `// comment text` — from `//` to end of line
- **Documentation comments**: `/* ... */` — attached to members, can nest

---

## 3. Control flow

### Conditionals

Conditionals are expressions. `if` has no `end`; each branch body ends with `;`, and the
enclosing expression frame then ends with its own `;`.

**If/else** (returns a value):

```mml
if condition then expr1; else expr2; ;
```

**If/elif/else** (multiple branches):

```mml
if cond1 then
  expr1;
elif cond2 then
  expr2;
else
  expr3;
;
```

**Single-branch if** (returns `Unit`):

```mml
if condition then
  expr;
;
```

The compiler inserts an implicit `else ()` for single-branch conditionals, so both
branches return `Unit`. Omit `else` when the false branch should do nothing;
use an explicit `else` for an alternative result or side effect.

**Rules**:

- The condition must have type `Bool`
- When `else` is present, both branches must have the same type
- The entire `if` expression has the type of its branches
- Every branch body must end with `;`

```mml
fn fizzbuzz(n: Int): Unit =
  if n % 15 == 0 then
    println "FizzBuzz";
  elif n % 3 == 0 then
    println "Fizz";
  elif n % 5 == 0 then
    println "Buzz";
  else
    println (int_to_str n);
  ;
;
```

### Recursion and tail calls

MML has no loop constructs (`while`, `for`, etc.). All iteration is done with
recursion. The compiler automatically optimizes eligible self-tail-recursion — no
annotation is needed. This is not a general tail-call guarantee: calls to other functions
are outside this optimization, and required cleanup after a recursive call can prevent it.

A call is in tail position when it is the last expression evaluated before a function
returns:

```mml
fn count_down(n: Int): Unit =
  if n > 0 then
    println (int_to_str n);
    count_down (n - 1);
  ;
;

fn sum_loop(i: Int, limit: Int, acc: Int): Int =
  if i == limit then
    acc;
  else
    sum_loop (i + 1) limit (acc + i);
  ;
;
```

Loops with state use the accumulator pattern:

```mml
fn count(arr: IntArray, i: Int, size: Int, acc: Int): Int =
  if i < size then
    let v = unsafe_ar_int_get arr i;
    count arr (i + 1) size (acc + v);
  else
    acc;
  ;
;
```

---

## 4. Type system

### Basic types

MML provides basic types that map directly to LLVM types:

```text
Int64, Int32, Int16, Int8      // Signed integers
Float, Double                  // Floating point
Bool                           // Boolean
Char                           // Character (i8)
Unit                           // Unit type (void)
String                         // Struct with length and data pointer
```

**Type aliases**:

```text
Int   → Int32   // Default integer type
Byte  → Int8
Word  → Int8
```

`Int` literals range from `-2147483648` to `2147483647`. Out-of-range literals
produce diagnostics. Integer addition, subtraction, and multiplication wrap at 32 bits.
Use `int_to_int64` and explicitly typed native arithmetic when a calculation needs 64 bits;
`Int64` remains a distinct type. `SizeT`, pointers, and allocation byte counts retain the
target's 64-bit width on supported targets.

Executable `main` may return `Unit`, `Int`/`Int32`, or `Int64`. Unit returns exit status 0;
Int32 returns its value directly, and Int64 is truncated to the C entry point's 32-bit status.

### Arrays

MML does not have polymorphic types yet, so arrays are provided as monomorphic
native types: `IntArray`, `StringArray`, and `FloatArray`. These are backed by C
runtime implementations with dedicated accessor functions (`ar_int_get`,
`ar_int_set`, `ar_float_get`, `ar_float_set`, etc.). Once the type checker
supports generics, these will be replaced by a single polymorphic array type.

String and array lengths and indexes use signed Int32. `IntArray` stores 32-bit elements.
Runtime lengths are checked before narrowing to Int32.

### Native type declarations

Native type declarations lift C/LLVM types into MML's type system without defining
the types themselves. Three forms exist:

**Primitive type**:

```mml
type SizeT = @metal:repr[t=i64];
```

**Opaque pointer**:

```mml
type CharPtr = @metal:repr[t=*i8];
```

**Struct mirror**:

```mml
type String = @metal:repr {
  length: Int32,
  data:   CharPtr
};
```

Each `@metal:repr` declaration creates a distinct type. Two declarations with the same
underlying representation are not interchangeable:

```mml
type Int64 = @metal:repr[t=i64];
type AnotherInt64 = @metal:repr[t=i64];
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

**Heap classification**: A struct is a heap type if any field is a heap type or function type.
Heap structs are tracked by the ownership system and freed automatically:

```mml
struct User { name: String, age: Int };  // Heap type (String is heap-allocated)
struct Point { x: Int, y: Int };         // Not a heap type
```

The compiler generates `__free_T` for heap structs and `__clone_T` when they contain no
function fields, directly or transitively. See [Memory management](#7-memory-management)
for ownership details.

Struct construction owns its fields. Passing a value to a heap-typed struct field is
an ownership transfer, so the argument must be owned. Borrowed values cannot be
assigned into owning struct fields.

Owned PAPs and move closures can move into function fields. The struct owns their environments
and destroys them at scope end, including through nested structs. Non-capturing function values
are accepted; borrow-capturing closures and borrowing PAPs are rejected. Field calls preserve
remaining consuming parameters. Calling a PAP that transfers a stored payload requires an owned
holder and prevents another call through that field or an alias. See
[PAPs in struct fields](memory-model.md#paps-in-struct-fields) for field borrowing and cleanup.

### Function types

Functions have types of the form `T1 -> T2 -> ... -> Tn -> R`:

```mml
fn add(a: Int, b: Int): Int = a + b;;
// Type: Int -> Int -> Int

fn doc_print(s: String): Unit = @metal:ffi;;
// Type: String -> Unit
```

Functions are curried — each arrow represents a function taking one argument.

Lambda expressions produce values with function types. A lambda can be used
anywhere a function type is expected:

```mml
fn apply(f: Int -> Int, x: Int): Int = f x;;

let inc = { x: Int -> x + 1; };    // inc: Int -> Int
let r = apply inc 10;              // r = 11
let r2 = apply { x -> x * 2 } 5;   // r2 = 10
```

When a lambda appears in a context where the expected type is known (function
parameter, let binding with type annotation, return position), the compiler
uses that context to infer parameter types. It can also infer parameter types
from the lambda body itself, for example from operator usage or calls to named
functions.

If body-derived evidence conflicts, inference fails. If both top-down context
and bottom-up evidence are present, the top-down expected type takes
precedence. Explicit annotations are needed only when the available evidence is
insufficient or ambiguous.

### Type compatibility

Two types are compatible if:

1. They denote the same type declaration
2. One is a type alias that resolves to the other

Each `@metal:repr` declaration creates a distinct type. The type checker does not consider
the underlying representation when comparing types.

### Compound types

**Tuples**: Tuple expressions can be parsed, but tuple-type syntax such as
`(T1, T2, ..., Tn)` and tuple code generation are not implemented yet.

**Function types**: `T1 -> T2 -> R`

```mml
let f: Int -> Int -> Int = add;
```

---

## 5. Operator system

### User-defined operators

All operators in MML are user-defined, including arithmetic and logical operators.
There are no built-in operators. The standard operators (`+`, `-`, `*`, `/`, `==`,
`and`, etc.) are provided by the standard library as ordinary operator
declarations.

```mml
op +(a: Int, b: Int): Int 60 left = @metal:tpl["add %type %operand1, %operand2"];;
op *(a: Int, b: Int): Int 80 left = @metal:tpl["mul %type %operand1, %operand2"];;
op %(a: Int, b: Int): Int 80 left = @metal:tpl["srem %type %operand1, %operand2"];;
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

Unary and binary operators can share a name. The compiler distinguishes their syntax by position
and argument count. Protocol signatures additionally support type-based selection: operand types
and the expected result or remaining function type identify a concrete instance.

Ordinary free declarations cannot overload the same name and arity by parameter type. Use a
protocol with concrete instances for that purpose.

```text
// Valid: unary and binary - coexist
op -(a: Int): Int 95 right = @metal:tpl["sub %type 0, %operand"];;
op -(a: Int, b: Int): Int 60 left = @metal:tpl["sub %type %operand1, %operand2"];;

// Invalid: duplicate binary operator
op +(a: Int, b: Int): Int 60 left = ...;;
op +(x: Float, y: Float): Float 60 left = ...;;  // error: duplicate name
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

All visible declarations of the same operator symbol and arity must agree on precedence and
associativity, including signatures from different protocols. The compiler checks this before
grouping expressions. Operand types and instance availability cannot resolve conflicting grouping
rules. Unary and binary forms are checked separately.

### Fixity

Fixity (prefix, infix, or postfix) is determined by the combination of arity and
associativity: binary operators are always infix, unary operators with `right`
associativity are prefix (e.g., `-x`), and unary operators with `left`
associativity are postfix (e.g., `x!`). There is no separate fixity annotation —
arity and associativity are sufficient to determine operator position.

### Operators as functions

Operators desugar to function calls:

```text
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
- Lambda bodies can reference bindings from enclosing scopes (closures). Captured
  bindings are resolved at the point the lambda is created, not when it is called.
- Inner functions can reference their own binding name for recursion.
- Inner binders shadow outer names, including names that would otherwise be
  captured.

### Visibility

Declarations carry a visibility flag written as `pub`, `prot`, or `priv`.
Most visibility rules are not enforced yet.

- **Public**: Importable and referenceable from any module.
- **Protected**: Visible to the defining module and related modules.
- **Private**: Confined to the defining module.

One rule is enforced today:

- In executable mode, the entry point `main` must be declared `pub`.

### Function application

Functions are curried: `f a b` desugars to `((f a) b)`.

**Partial application**:

```mml
fn add(a: Int, b: Int): Int = a + b;;

let add5 = add 5;       // Partial application: Int -> Int
let result = add5 10;   // Full application: 15
```

**Juxtaposition**: Function application is written as `f x` (apply `f` to `x`).
This applies uniformly: regular functions, operators, and struct constructors are
all called the same way.

### Nullary functions

Functions with zero parameters must be explicitly applied to `()`:

```mml
fn get_value(): Int = 42;;
let x = get_value ();  // Call: explicit application to unit
let f = get_value;     // Reference: no call
```

### Native declarations

Functions and operators can have `@metal:ffi` bodies, indicating external implementation:

```mml
fn doc_print(s: String): Unit = @metal:ffi;;
fn doc_str_to_int(s: String): Int = @metal:ffi;;
```

The codegen generates forward declarations and the linker resolves them. Unresolved
natives produce linker errors.

Use `name` to bind an MML declaration to a different external symbol:

```mml
fn external_count(): Int = @metal:ffi[name="_external_count"];;
```

Here MML code calls `external_count ()`, while the linker resolves `_external_count`.
The optional named attributes are `name` and `mem`, for example
`@metal:ffi[name="external_readline", mem=alloc]`.

### LLVM templates

Functions and operators can use `@metal:tpl["..."]` to emit inline LLVM IR:

```mml
fn ctpop(x: Int): Int = @metal:tpl["call i32 @llvm.ctpop.i32(i32 %operand)"];;
```

`@metal:tpl` requires the template string as its first positional argument. Optional
attributes follow it, separated by commas: `@metal:tpl["...", mem=alloc, name="helper"]`.
The supported attributes are `mem` and `name`; unknown attributes and flags are rejected.
`name` is metadata for templates; the template supplies the emitted instructions.

A `pub` template function also emits an exported function with its declared arguments and
return type, using the same public name and calling convention as an ordinary `pub` function.
Its body renders the template, and it remains available in native library output even without
internal callers. Direct calls expand the template at the call site. Function values use a
closure entry that renders the template and accepts the closure environment parameter; the
exported function has no environment parameter.

Templates do not produce external function declarations. External symbols called by a template
require their own declarations, such as an `@metal:ffi` binding.

**Template placeholders**:

For function templates, placeholders refer to runtime arguments. `Unit` arguments are omitted;
numbered placeholders count the remaining arguments from 1.

- `%operand` — single argument (unary functions)
- `%operand1`, `%operand2`, ... — multiple arguments (1-indexed)
- `%type` — LLVM type of first argument
- `%type1`, `%type2`, ... — LLVM type of the numbered function argument

### Memory effects on native functions

Native functions that allocate memory can be annotated with `[mem=alloc]`:

```mml
fn doc_readline(): String = @metal:ffi[mem=alloc];;
fn doc_concat(a: String, b: String): String = @metal:ffi[mem=alloc];;
fn doc_int_to_str(n: Int): String = @metal:ffi[mem=alloc];;
```

**Memory effect attributes**:

- `[mem=alloc]` — Function allocates new memory. Caller owns the return value.
- `[mem=static]` — Function returns static/existing memory. Caller does not own it.
- _None_ — Default. No memory effect, caller does not own the return.

These can be combined with templates: `@metal:tpl["...", mem=alloc]`

### Consuming parameters

Parameters can be marked as consuming with the `~` prefix:

```mml
fn take_ownership(~s: String): Unit = ();;
```

**Rules**:

- `~` appears only in the declaration, not at call sites
- A consuming parameter transfers ownership from caller to callee
- The caller must not use the value after passing it to a consuming parameter
- Partial application preserves consuming parameters: supplied consuming arguments move into the PAP;
  remaining consuming arguments move when supplied in a later application
- A PAP can receive fresh consuming arguments repeatedly while retaining its stored captures.
  If a call transfers a stored payload, that PAP can be called only once
- Consuming PAP calls can be nested inside argument expressions. Arguments run once in source
  order, and ownership moves remain visible to later arguments and statements
- A conditional argument to a consuming parameter transfers ownership on either branch.
  Borrowed branches are rejected; literal/global branches use the type's clone contract

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
type String = @metal:repr[mem=heap] { length: Int32, data: CharPtr };
type Buffer = @metal:repr[mem=heap, t=*i8];
```

User-defined structs are heap types if they contain any heap-typed or function-typed fields:

```mml
struct User { name: String, age: Int };  // Heap type (contains String)
struct Point { x: Int, y: Int };         // Not a heap type
```

### Custom deallocation functions

By default, heap-typed `@metal:repr` types use a `__free_<TypeName>` naming convention for
their deallocation function. The `free=<name>` attribute overrides this:

```mml
type Handle = @metal:repr[t=*i8, mem=heap, free=close_handle];
type MyStr = @metal:repr[mem=heap, free=destroy_str] { length: Int32, data: CharPtr };
```

When `free=` is provided, the ownership system calls the specified function instead of
`__free_Handle` or `__free_MyStr`. When omitted, the convention applies.

This only affects `@metal:repr` types. User-defined `struct` types always use automatically
generated `__free_<Name>` functions.

### Struct construction

Struct construction owns its fields. Passing a value to a heap-typed field is an
ownership transfer, so the argument must be owned. Borrowed values cannot be assigned
into owning struct fields.

```mml
fn example(): Unit =
  let name = readline();       // name is owned
  let user = User name 25;     // Constructor moves name into user
  // println name;             // error: use after move
  println user.name;           // OK: user owns the field
;
```

### Ownership examples

```mml
fn example(): Unit =
  let s = readline();    // s is owned (readline allocates)
  println s;             // println borrows s
  println s;             // s can be used again (still owned)
;                        // s is automatically freed

fn transfer_example(): Unit =
  let s = readline();
  consume_string s;      // If consume_string takes ~s, ownership transfers
  // println s;          // error: use after move
;
```

### Closures and captures

A **non-capturing lambda** is just a function value.

A **capturing lambda** carries an environment with references to enclosing
bindings. Captures borrow by default. Prefix the closure with `~` to request
move capture for the whole closure.

Borrow-capturing closures stay local to the scope that created them.
Move-capturing closures may escape and transfer ownership like other owned
values.

A move closure can consume an owned capture directly or move it into a local binding.
Doing so makes the outer closure call-once; later invocations are use-after-move errors.
Passing such a closure to another function requires a consuming parameter. A move closure
that only borrows its captures remains reusable.
Returning an owned PAP capture consumes the outer closure and preserves the returned
PAP's call-once contract.

See the [memory model](memory-model.md) for the precise ownership, escape, and
destruction rules.

### Return value ownership

Returning an owned value transfers ownership to the caller. A heap return type alone
does not imply ownership: static results remain unowned and need no caller cleanup.
For example, concatenation allocates an owned result:

```mml
fn make_greeting(name: String): String =
  "Hello, " ++ name;
;

fn main(): Unit =
  let greeting = make_greeting "World";
  println greeting;
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
- Calling a partial application after it has transferred a stored payload is an error
  (use after move).
- Conditional branches must produce compatible ownership states.

---

## 9. Standard library

The standard library provides a set of types, operators, and functions available
in every module without explicit imports. For implementation details, see
[Standard library injection](design/compiler-design.md#9-standard-library-injection) in
the compiler design document.

### Types

#### Primitive types

| Type     | LLVM     | Description               |
| -------- | -------- | ------------------------- |
| `Int64`  | `i64`    | 64-bit signed integer     |
| `Int32`  | `i32`    | 32-bit signed integer     |
| `Int16`  | `i16`    | 16-bit signed integer     |
| `Int8`   | `i8`     | 8-bit signed integer      |
| `Float`  | `float`  | 32-bit IEEE 754           |
| `Double` | `double` | 64-bit IEEE 754           |
| `Bool`   | `i1`     | Boolean                   |
| `Char`   | `i8`     | Character (same as Int8)  |
| `SizeT`  | `i64`    | Size type (same as Int64) |
| `Unit`   | `void`   | Unit type                 |

#### Type aliases

| Alias  | Target  |
| ------ | ------- |
| `Int`  | `Int32` |
| `Byte` | `Int8`  |
| `Word` | `Int8`  |

#### Composite types

| Type          | Description                                                   |
| ------------- | ------------------------------------------------------------- |
| `CString`     | Owned NUL-terminated C string pointer. Freed automatically.   |
| `String`      | Struct: `{ length: Int32, data: CharPtr }`. Heap-allocated.   |
| `Buffer`      | Opaque pointer to a buffered I/O writer. Heap-allocated.      |
| `Rng`         | Opaque pointer to a random number generator. Heap-allocated.  |
| `IntArray`    | Struct: `{ length: Int32, data: Int32Ptr }`. Heap-allocated.  |
| `StringArray` | Struct: `{ length: Int32, data: StringPtr }`. Heap-allocated. |
| `FloatArray`  | Struct: `{ length: Int32, data: FloatPtr }`. Heap-allocated.  |

### Operators

The provisional `Num` protocol supplies arithmetic, comparison, and unary negation for
`Int` and `Float`. Each operator has one precedence and associativity shared by both
instances. Integer remainder, shifts, and unary plus remain separate Int-only operators.

#### Integer arithmetic

| Operator | Type                | Prec | Assoc | Description            |
| -------- | ------------------- | ---- | ----- | ---------------------- |
| `a + b`  | `Int -> Int -> Int` | 60   | left  | Addition               |
| `a - b`  | `Int -> Int -> Int` | 60   | left  | Subtraction            |
| `a * b`  | `Int -> Int -> Int` | 80   | left  | Multiplication         |
| `a / b`  | `Int -> Int -> Int` | 80   | left  | Integer division       |
| `a % b`  | `Int -> Int -> Int` | 80   | left  | Remainder              |
| `a << b` | `Int -> Int -> Int` | 55   | left  | Left shift             |
| `a >> b` | `Int -> Int -> Int` | 55   | left  | Arithmetic right shift |
| `+a`     | `Int -> Int`        | 95   | right | Unary plus             |
| `-a`     | `Int -> Int`        | 95   | right | Unary negation         |

#### Integer comparison

| Operator | Type                 | Prec | Assoc |
| -------- | -------------------- | ---- | ----- |
| `a == b` | `Int -> Int -> Bool` | 50   | left  |
| `a != b` | `Int -> Int -> Bool` | 50   | left  |
| `a < b`  | `Int -> Int -> Bool` | 50   | left  |
| `a > b`  | `Int -> Int -> Bool` | 50   | left  |
| `a <= b` | `Int -> Int -> Bool` | 50   | left  |
| `a >= b` | `Int -> Int -> Bool` | 50   | left  |

#### Logical

| Operator  | Type                   | Prec | Assoc |
| --------- | ---------------------- | ---- | ----- |
| `a and b` | `Bool -> Bool -> Bool` | 40   | left  |
| `a or b`  | `Bool -> Bool -> Bool` | 30   | left  |
| `not a`   | `Bool -> Bool`         | 95   | right |

#### Float arithmetic

Float and Int use the same operator spellings, supplied by their `Num` instances.
The argument types select the instance. Operands must have the same type; use
`int_to_float` explicitly when mixing integer and floating values.

For example, `2 + 3` has type `Int`, and `2.0 + 3.0` has type `Float`.
An operator value needs enough type information: `let add: Float -> Float -> Float = (+);`.
The dotted spellings are not built in; programs may declare their own operators with those names.

| Operator | Type                      | Prec | Assoc |
| -------- | ------------------------- | ---- | ----- |
| `a + b` | `Float -> Float -> Float` | 60   | left  |
| `a - b` | `Float -> Float -> Float` | 60   | left  |
| `a * b` | `Float -> Float -> Float` | 80   | left  |
| `a / b` | `Float -> Float -> Float` | 80   | left  |
| `-a`    | `Float -> Float`          | 95   | right |

#### Float comparison

| Operator  | Type                     | Prec | Assoc |
| --------- | ------------------------ | ---- | ----- |
| `a < b`  | `Float -> Float -> Bool` | 50   | left  |
| `a > b`  | `Float -> Float -> Bool` | 50   | left  |
| `a <= b` | `Float -> Float -> Bool` | 50   | left  |
| `a >= b` | `Float -> Float -> Bool` | 50   | left  |
| `a == b` | `Float -> Float -> Bool` | 50   | left  |
| `a != b` | `Float -> Float -> Bool` | 50   | left  |

Float `!=` is true when either operand is NaN; the other comparisons are false for NaN.
Unary Float negation computes `0.0 - value`, including its signed-zero behavior.

#### String

| Operator | Type                         | Prec | Assoc | Description                    |
| -------- | ---------------------------- | ---- | ----- | ------------------------------ |
| `a ++ b` | `String -> String -> String` | 61   | right | Concatenation (calls `concat`) |

### Functions

#### I/O

| Function          | Type             | Description                      |
| ----------------- | ---------------- | -------------------------------- |
| `print(s)`        | `String -> Unit` | Print string to stdout           |
| `println(s)`      | `String -> Unit` | Print string with newline        |
| `readline()`      | `Unit -> String` | Read line from stdin. Allocates. |
| `mml_sys_flush()` | `Unit -> Unit`   | Flush stdout                     |

#### String operations

| Function          | Type                         | Description                                                |
| ----------------- | ---------------------------- | ---------------------------------------------------------- |
| `concat(a, b)`    | `String -> String -> String` | Concatenate two strings. Allocates.                        |
| `int_to_str(n)`   | `Int -> String`              | Integer to string. Allocates.                              |
| `float_to_str(f)` | `Float -> String`            | Float to string. Allocates.                                |
| `str_to_int(s)`   | `String -> Int`              | Parse signed Int32; panic on invalid or out-of-range input |
| `to_cstr(s)`      | `String -> CString`          | Copy into an owned NUL-terminated C string.                |

`to_cstr` borrows its input and allocates an independent `CString`. Native bindings can
accept `CString` for C string parameters; ordinary parameters borrow it for the call.
The compiler inserts `free_cstr` at the end of the owning scope. C code must not retain
the pointer beyond that lifetime. Embedded NUL bytes remain in the copy and terminate
ordinary C string operations at the first NUL.

`CString` uses the injected consuming destructor `free_cstr(~s: CString): Unit`, bound
to C's `free`. Explicit early release is possible, but ordinary code needs no manual free.

#### Type conversion

| Function          | Type              | Description                    |
| ----------------- | ----------------- | ------------------------------ |
| `int_to_int64(n)` | `Int -> Int64`    | Sign-extend a 32-bit integer   |
| `int64_to_str(n)` | `Int64 -> String` | Format a signed 64-bit integer |
| `int_to_float(n)` | `Int -> Float`    | Convert integer to float       |
| `float_to_int(f)` | `Float -> Int`    | Truncate float to integer      |

#### Float math

| Function  | Type             | Description                     |
| --------- | ---------------- | ------------------------------- |
| `sqrt(x)` | `Float -> Float` | Square root (LLVM intrinsic)    |
| `fabs(x)` | `Float -> Float` | Absolute value (LLVM intrinsic) |

#### Random number generation

| Function                     | Type                       | Description                                                           |
| ---------------------------- | -------------------------- | --------------------------------------------------------------------- |
| `rng_new(seed)`              | `Int -> Rng`               | Create a deterministic RNG. Allocates.                                |
| `rng_new_random()`           | `Unit -> Rng`              | Create an RNG with a runtime seed. Allocates.                         |
| `rng_next(rng)`              | `Rng -> Int`               | Advance the 64-bit RNG state and return a nonnegative 31-bit integer. |
| `rng_between(rng, min, max)` | `Rng -> Int -> Int -> Int` | Return an integer in `[min, max)`.                                    |

#### Buffered I/O

Buffers provide efficient batched output. Write operations accumulate in memory and
are flushed explicitly or when the buffer is freed.

| Function                     | Type                       | Description                                    |
| ---------------------------- | -------------------------- | ---------------------------------------------- |
| `mkBuffer()`                 | `Unit -> Buffer`           | Create buffer writing to stdout. Allocates.    |
| `mkBufferWithFd(fd)`         | `Int -> Buffer`            | Create buffer for file descriptor. Allocates.  |
| `mkBufferWithSize(size)`     | `Int -> Buffer`            | Create buffer with custom capacity. Allocates. |
| `flush(b)`                   | `Buffer -> Unit`           | Flush buffer contents                          |
| `buffer_write(b, s)`         | `Buffer -> String -> Unit` | Write string                                   |
| `buffer_writeln(b, s)`       | `Buffer -> String -> Unit` | Write string with newline                      |
| `buffer_write_byte(b, n)`    | `Buffer -> Int -> Unit`    | Write raw low 8 bits of integer                |
| `buffer_write_int(b, n)`     | `Buffer -> Int -> Unit`    | Write integer                                  |
| `buffer_writeln_int(b, n)`   | `Buffer -> Int -> Unit`    | Write integer with newline                     |
| `buffer_write_float(b, f)`   | `Buffer -> Float -> Unit`  | Write float                                    |
| `buffer_writeln_float(b, f)` | `Buffer -> Float -> Unit`  | Write float with newline                       |

#### File I/O

| Function                 | Type            | Description                         |
| ------------------------ | --------------- | ----------------------------------- |
| `open_file_read(path)`   | `String -> Int` | Open file for reading, returns fd   |
| `open_file_write(path)`  | `String -> Int` | Open file for writing, returns fd   |
| `open_file_append(path)` | `String -> Int` | Open file for appending, returns fd |
| `close_file(fd)`         | `Int -> Unit`   | Close file descriptor               |
| `read_line_fd(fd)`       | `Int -> String` | Read line from fd. Allocates.       |

#### Array operations

The array types (`IntArray`, `StringArray`, `FloatArray`) provide creation, element
access, and length operations. The table below uses `IntArray` / `Int` as the example;
family-specific operations and ownership rules follow it.

| Function                       | Type                             | Description        |
| ------------------------------ | -------------------------------- | ------------------ |
| `ar_int_new(size)`             | `Int -> IntArray`                | Create. Allocates. |
| `ar_int_set(arr, i, v)`        | `IntArray -> Int -> Int -> Unit` | Bounds-checked set |
| `ar_int_get(arr, i)`           | `IntArray -> Int -> Int`         | Bounds-checked get |
| `unsafe_ar_int_set(arr, i, v)` | `IntArray -> Int -> Int -> Unit` | Unchecked set      |
| `unsafe_ar_int_get(arr, i)`    | `IntArray -> Int -> Int`         | Unchecked get      |
| `ar_int_len(arr)`              | `IntArray -> Int`                | Array length       |

`ar_int_fill(arr, value): IntArray -> Int -> Unit` fills every element of an
integer array with the given value. It borrows the array, allocates no storage,
and accepts empty arrays. This operation is specific to `IntArray`.

The `StringArray` family uses `ar_str_*` and the `FloatArray` family uses
`ar_float_*`. The `StringArray` family does not have `unsafe_ar_str_set` or
`unsafe_ar_str_get` variants.

`ar_str_set(arr: StringArray, i: Int, ~value: String): Unit` borrows the array and
consumes the string value. After storing an owned string, the caller must not reuse it;
the array owns that value.

---

## 10. Current limitations

**No generics**: The type checker does not support parametric polymorphism yet.
Monomorphic workarounds (e.g., `IntArray`, `StringArray`, `FloatArray`) are used
in the meantime.

**Overloading**: Operators can be overloaded by arity — a unary and a binary
operator can share a name — but two binary operators with the same name are
rejected as free declarations even if their parameter types differ. Ordinary functions cannot
be overloaded by repeated declarations. Protocol signatures support type-based selection for
both functions and operators, with concrete instances selected before execution.

**Protocols**: One type parameter and required signatures are supported. Defaults, prerequisites,
member-local polymorphism, parameterized instances, and `where` clauses are deferred.

---

## Appendix: Reserved words

- `let`
- `fn`
- `op`
- `type`
- `struct`
- `protocol`
- `instance`
- `module`
- `if`
- `then`
- `elif`
- `else`
- `inline`
- `->`
- `@metal:repr`
- `@metal:ffi`
- `@metal:tpl`
- `???`
- `_`
- `~`
- `pub`
- `prot`
- `priv`
