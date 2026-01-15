# **MMLVM - Reference Guide**

## **1. Execution Model**

- **SSA-based (Single Static Assignment)**
- **Infinite Virtual Registers** (`%N` notation: `%0`, `%1`, `%2`, ...)
- **Stack-based function calls** (`call`, `ret`)
- **Heap for dynamic memory** (`alloc`, `free`, `load`, `store`)
- **Global Symbols** (`@name` for global variables and functions)
- **Labels** (`:label` for branching and control flow)
- **Textual Representation** for debuggability and analysis

---

## **2. Global Variables & Static Initialization**

### **Simple Globals**

MinnieML:

```mml
let a = 1;
let b = 2;
fn main() = a + b;
```

MMLVM:

```mml
global @a: Int = 1
global @b: Int = 2

fn @main(): Int
  %0 = load @a
  %1 = load @b
  %2 = add %0, %1
  ret %2
end
```

### **Globals Requiring Initialization**

MinnieML:

```mml
let a = 1;
let b = a * 2;
fn main() = b * 4;
```

MMLVM:

```mml
global @a: Int = 1
global @b: Int  # Uninitialized

fn @_init_b()
  %0 = load @a
  %1 = mul %0, 2
  store @b, %1
  ret
end

fn @main(): Int
  call @_init_b
  %0 = load @b
  %1 = mul %0, 4
  ret %1
end
```

- `_init_b()` initializes `@b` before use.
- `_` prefix marks it as **internal**.
- `call @_init_b` ensures `@b` is computed before use.

---

## **3. Function Calls & External Functions**

### **Arithmetic with Precedence**

MinnieML:

```mml
let a = 2;
let b = 2;
let c = 3;

fn main() = a * b + c;
```

MMLVM:

```mml
global @a: Int = 2
global @b: Int = 2
global @c: Int = 3

fn @main(): Int
  %0 = load @a
  %1 = load @b
  %2 = mul %0, %1  # a * b
  %3 = load @c
  %4 = add %2, %3  # (a * b) + c
  ret %4
end
```

### **Calling an External Function**

MinnieML:

```mml
fn print(s: String): Unit = ~[native];  # External function (builtin)

let s = "mmlvm!";

fn main() = print s;
```

MMLVM:

```mml
extern @print(String): Unit

global @s: String = "mmlvm!"

fn @main(): Unit
  %0 = load @s
  call @print, %0
  ret
end
```

- **`extern @print(String): Unit`** declares a function **not in this module**.
- `call @print, %0` calls the external function with argument `%0`.

---

## **4. Opcode Reference**

### **Arithmetic Operations**

```
add %rd, %ra, %rb   # rd = ra + rb
sub %rd, %ra, %rb   # rd = ra - rb
mul %rd, %ra, %rb   # rd = ra * rb
div %rd, %ra, %rb   # rd = ra / rb
rem %rd, %ra, %rb   # rd = ra % rb
neg %rd, %ra        # rd = -ra
```

### **Bitwise Operations**

```
and %rd, %ra, %rb   # rd = ra & rb
or  %rd, %ra, %rb   # rd = ra | rb
xor %rd, %ra, %rb   # rd = ra ^ rb
shl %rd, %ra, %rb   # rd = ra << rb
shr %rd, %ra, %rb   # rd = ra >> rb
```

### **Comparison & Branching**

```
eq   %rd, %ra, %rb   # rd = (ra == rb)
neq  %rd, %ra, %rb   # rd = (ra != rb)
lt   %rd, %ra, %rb   # rd = (ra < rb)
lte  %rd, %ra, %rb   # rd = (ra <= rb)
gt   %rd, %ra, %rb   # rd = (ra > rb)
gte  %rd, %ra, %rb   # rd = (ra >= rb)

br   %cond, :true_label, :false_label
jmp  :label
```

### **Stack Operations**

```
push %ra
pop  %rd
```

### **Memory Operations**

```
alloc  %rd, size
free   %ra
load   %rd, %addr
store  %addr, %ra
```

---
