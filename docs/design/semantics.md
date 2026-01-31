# MinnieML: Language Semantics

This document describes the semantics of the MinnieML language: its syntax, type system, and behavioral rules.

## Table of Contents
1. [Lexical Rules](#1-lexical-rules)
2. [Type System](#2-type-system)
3. [Operator System](#3-operator-system)
4. [Semantic Rules](#4-semantic-rules)
5. [Error Categories](#5-error-categories)

---

## 1. Lexical Rules

### Identifiers

MML has distinct lexical rules for different kinds of identifiers:

#### Binding Identifiers (Variables, Functions)
- **Pattern**: `[a-z][a-zA-Z0-9_]*`
- **Must start with**: Lowercase letter `a-z`
- **Can contain**: Letters, digits, underscores
- **Examples**: `x`, `myValue`, `calculate_sum`

#### Type Identifiers
- **Pattern**: `[A-Z][a-zA-Z0-9]*`
- **Must start with**: Uppercase letter `A-Z`
- **Can contain**: Letters and digits (no underscores)
- **Examples**: `Int`, `String`, `MyType`

#### Operator Identifiers
Operators can be **either symbolic OR alphanumeric**:

**Symbolic operators**:
- **Allowed characters**: `=!#$%^&*+<>?/\|~-`
- **Examples**: `+`, `-`, `==`, `|>`, `>>`

**Alphanumeric operators**:
- **Pattern**: Same as binding identifiers: `[a-z][a-zA-Z0-9_]*`
- **Examples**: `and`, `or`, `mod`, `div`

### Keywords

Reserved words that cannot be used as identifiers:

```
let, fn, op, type, module, if, then, else, @native, ??? (hole), _ (placeholder)
```

### Literals

#### Numeric Literals
- **Integers**: `[0-9]+` → `LiteralInt`
  - Examples: `42`, `0`, `1234`
- **Floats**: `[0-9]+\.[0-9]+` or `\.[0-9]+` → `LiteralFloat`
  - Examples: `3.14`, `.5`, `0.001`

#### String Literals
- **Pattern**: `"[^"]*"` (raw content, no escape processing during parsing)
- **Multiline support**: Yes, newlines and special characters are preserved
- **Escape handling**: Performed at code generation time using LLVM hex escapes
- **Examples**: `"hello"`, `"world"`, `"line 1\nline 2"` (literal newline in source)

#### Boolean Literals
- **Values**: `true`, `false`

#### Unit Literal
- **Syntax**: `()`
- **Type**: `Unit` (the only inhabitant of the Unit type)

### Comments

#### Line Comments
- **Syntax**: `// comment text`
- **Scope**: From `//` to end of line
- **Note**: `///` is also a comment (not an operator)

#### Documentation Comments
- **Syntax**: `/* ... */`
- **Purpose**: Attached to members for documentation
- **Can nest**: `/* outer /* inner */ */`

---

## 2. Type System

### Basic Types

MML provides a set of basic types that map directly to LLVM types:

```scala
Int64, Int32, Int16, Int8      // Signed integers
Float, Double                  // Floating point
Bool                           // Boolean (i1)
Char                           // Character (i8)
Unit                           // Unit type (void)
String                         // Struct with length and data pointer
```

**Type Aliases**:
```mml
Int   → Int64   // Default integer type
Byte  → Int8
Word  → Int8
```

### Native Type Declarations

MML allows declaring types that mirror native C/LLVM types. These declarations **lift native types into MML's type system** without defining the types themselves.

**Three forms of native types**:

```rust
// Primitive type with native representation
type SizeT = @native[t=i64];

// Opaque pointer
type CharPtr = @native[t=*i8];

// Struct mirror
type String = @native {
  length: SizeT,
  data:   CharPtr
};
```

**Primitive Types**: Each `@native[t=<repr>]` declaration defines a **distinct fundamental type**. In other languages, primitives like `int` or `float` are hardcoded into the compiler. MML gives users the ability to define their own primitives via `@native`.

```rust
type Int64 = @native[t=i64];
type AnotherInt64 = @native[t=i64];

// These are DIFFERENT types - two distinct primitives
// Even though both wrap i64, they are separate fundamental types
// A function expecting Int64 won't accept AnotherInt64
```

**Native Structs**: Native struct type declarations are **mirrors**, not definitions. The actual type definition exists in the linked C runtime (`mml_runtime.c`). MML uses these declarations to understand memory layout and generate correct IR.



### Function Types

Functions have types of the form `T1 → T2 → ... → Tn → R`, where:
- `T1, T2, ..., Tn` are parameter types
- `R` is the return type
- Functions are curried (each arrow represents a function taking one argument)

**Examples**:
```rust
fn add(a: Int, b: Int): Int = a + b;
// Type: Int → Int → Int

fn print(s: String): Unit = @native;
// Type: String → Unit
```

### Type Compatibility

Two types are compatible if:
1. They are the same type (same name)
2. One is a type alias that resolves to the other

**Important for Native Types**: Each `@native` declaration creates a distinct fundamental type. The type checker treats these as opaque primitives and does not look at their underlying representation:
- `Int64` and `AnotherInt64` (both `@native[t=i64]`) are **incompatible** - they are different primitives
- The type checker cannot see that both wrap `i64`; it treats each `@native` declaration as a separate fundamental type

### Compound Types

**Tuples**: `(T1, T2, ..., Tn)`
```mml
let pair: (Int, String) = (42, "hello");
```

**Function types**: `T1 → T2 → R`
```mml
let f: Int → Int → Int = add;
```

**Type variables**: `'T`, `'R`, etc. (for future polymorphism support)

---

## 3. Operator System

### User-Defined Operators

**All operators in MML are user-defined**, including fundamental arithmetic and logical operators. There are no built-in operators in the language itself.

The standard operators like `+`, `-`, `*`, `/`, `==`, `and`, etc. are **injected by the compiler** into every module (see `semantic/package.scala`). From the language's perspective, these are ordinary user-defined operators with no special status.

**Example operator definition**:
```mml
op +(a: Int, b: Int): Int 60 left = @native[tpl="add %type %operand1, %operand2"];
op *(a: Int, b: Int): Int 80 left = @native[tpl="mul %type %operand1, %operand2"];
op %(a: Int, b: Int): Int 80 left = @native[tpl="srem %type %operand1, %operand2"];
```

### Operator Kinds

MML supports two kinds of operators:

1. **Binary operators**: Take two operands
   - Syntax: `op NAME(param1, param2): ReturnType N ASSOC = body;`
   - Example: `op +(a: Int, b: Int): Int 60 left = ...;`
   - Represented as `Bnd` with `BindingMeta(arity=Binary)`

2. **Unary operators**: Take one operand
   - Syntax: `op NAME(param): ReturnType N ASSOC = body;`
   - Example: `op -(a: Int): Int 95 right = ...;`
   - Represented as `Bnd` with `BindingMeta(arity=Unary)`

### Operator Overloading

**Operators can be overloaded by arity**:
- A binary operator and a unary operator **can share the same name**
- Example: Binary `-` (subtraction) and unary `-` (negation) coexist

**Functions cannot be overloaded**:
- Each function name must be unique within a scope.
- Functions and operators with the same name are **not allowed**

**Overloading rules**:
```rust
// Valid: unary and binary - coexist
op -(a: Int): Int 95 right = ???;        // Unary negation
op -(a: Int, b: Int): Int 60 left = ???; // Binary subtraction

// Invalid: duplicate binary operator
op +(a: Int, b: Int): Int = ???;
op +(x: Float, y: Float): Float = ???;  // ERROR: duplicate name

// Invalid: function and operator with same name
fn foo(x: Int): Int = x;
op foo(a: Int, b: Int): Int = a + b;  // ERROR: name conflict
```

### Precedence and Associativity

Every operator has:
- **Precedence**: Integer value (higher binds tighter)
- **Associativity**: `left` or `right`

**Standard precedence levels**:
```
95: Unary operators (prefix: +, -, not)
80: Multiplicative (*, /)
60: Additive (+, -)
50: Comparison (==, !=, <, >, <=, >=)
40: Logical and
30: Logical or
```

These are **conventional values** used by the injected standard operators, not language-enforced rules. User-defined operators can use any precedence value.

### Operators as Functions

Operators are syntactic sugar for function calls:
```mml
1 + 2      // Desugars to: + 1 2
-5         // Desugars to: - 5
a * b + c  // Desugars to: + (* a b) c
```

---

## 4. Semantic Rules

### Visibility (not enforced yet)

Declarations carry a visibility flag for future access control, but the compiler does not enforce it
yet.

- **Public**: Importable and referenceable from any module.
- **Protected**: Visible to the defining module and related modules (siblings/nested) when imported
  or fully qualified.
- **Private**: Confined to the defining module.

### Function Application

**Currying**: Functions are curried, meaning multi-parameter functions desugar to nested single-parameter applications: `f a b` is really `((f a) b)`.

```mml
fn add(a: Int, b: Int): Int = a + b;

let add5 = add 5;       // Partial application: Int → Int
let result = add5 10;   // Full application: 15
```

**Juxtaposition**: Function application is written as juxtaposition: `f x` means "apply f to x".

### Nullary Functions

Functions with zero parameters require special handling:

**In call position**: Explicitly applied to unit
```mml
fn get_value(): Int = 42;
let x = get_value ();  // Explicit application to unit literal
```

**In value position**: Function reference (no implicit call)
```mml
let f = get_value;    // Reference to the nullary function
```
To evaluate a nullary function, apply it explicitly with `()`.

### Conditional Expressions

```mml
if condition then expr1 else expr2;
```

**Rules**:
- `condition` must have type `Bool`
- `expr1` and `expr2` must have the same type
- The entire expression has the type of the branches

### Native Declarations

Functions and operators can have `@native` bodies, indicating external implementation:

```mml
fn print(s: String): Unit = @native;
fn mml_sys_flush(): Unit = @native;
fn str_to_int(s: String): Int = @native;
op +(a: Int, b: Int): Int 60 left = @native[tpl="add %type %operand1, %operand2"];
```

**Purpose**: Lift native (C/LLVM) functions into MML's type system by declaring their signatures. The type annotations define the interface contract.
             The codegen will generate forward declarations in each module using these functions and the linker will resolve them.
             If they are not found a linker error will occur.

### Native Templates for Functions

Functions can use `@native[tpl="..."]` to emit inline LLVM IR, useful for LLVM intrinsics:

```mml
fn ctpop(x: Int): Int = @native[tpl="call i64 @llvm.ctpop.i64(i64 %operand)"];
fn pow(x: Float, y: Float): Float = @native[tpl="call float @llvm.pow.f32(float %operand1, float %operand2)"];
```

**Template placeholders**:
- `%operand` - single argument (unary functions)
- `%operand1`, `%operand2`, ... - multiple arguments (1-indexed)
- `%type` - LLVM type of first argument

The codegen prepends `%result =` to the template automatically.

---

## 5. Error Categories

### Semantic Errors

Errors related to names and references:
- **`UndefinedRef`**: Reference to undefined variable, function, or parameter
- **`UndefinedTypeRef`**: Reference to undefined type
- **`DuplicateName`**: Multiple declarations with the same name in a module

Expression structure errors:
- **`InvalidExpression`**: Malformed expression that cannot be processed
- **`DanglingTerms`**: Terms that appear in invalid positions

### Type Errors

Parameter and return type errors:
- **`MissingParameterType`**: Function parameter lacks required type annotation
- **`MissingReturnType`**: Function lacks return type (when inference is not possible)

Application errors:
- **`TypeMismatch`**: Expected type T1, got type T2
- **`InvalidApplication`**: Attempting to call a non-function
- **`UndersaturatedApplication`**: Too few arguments provided
- **`OversaturatedApplication`**: Too many arguments provided

Conditional errors:
- **`ConditionalBranchTypeMismatch`**: `if` branches have different types
- **`ConditionalGuardTypeMismatch`**: Condition is not `Bool`
