# Design: Path-Dependent Types for Zero-Cost Array Safety

## 1. The Core Problem
In high-performance computing (e.g., sieves, matrix multiplication, FFT), array bounds checks are a significant bottleneck.
* **Standard Approach (Java/Go/Rust):** Check bounds at *every* access site.
    * *Pros:* Safe.
    * *Cons:* Performance overhead; reliance on complex optimizer passes (LICM) to remove redundant checks.
* **Unsafe Approach (C/C++):** Trust the programmer.
    * *Pros:* Maximum performance.
    * *Cons:* Memory corruption, segmentation faults, security vulnerabilities.

**Goal:** Achieve C-level performance with Type-Level safety guarantees, without requiring a heavy theorem prover.

## 2. The Solution: Provenance over Arithmetic
Instead of proving that an integer `i` satisfies the arithmetic condition `0 <= i < size` at every use site (which is hard/undecidable for complex calculations), we use **Path-Dependent Types** to prove **Provenance**.

We treat an array index not as a generic `Int`, but as a "Token" issued by a specific array instance.

### The Mechanism
1.  **The Instance (Path):** Every array `arr` defines a unique type `arr.Index`.
2.  **The Validator (Smart Constructor):** The *only* way to obtain an `arr.Index` is to pass a runtime bounds check against `arr`.
3.  **The Accessor (Trusted Core):** The array provides an `unchecked_get` method that *requires* an `arr.Index`.

### Conceptual Code (Scala/MML)

```scala
// Abstract definition (Concept)
class Array {
  // A generic integer type specific to THIS array instance
  type Index <: Int 
  
  // The only way to get an 'Index' is to pass the runtime check
  def check(i: Int): Option[this.Index] = 
    if (i >= 0 && i < this.length) Some(i.asInstanceOf[this.Index]) else None

  // This is UNCHECKED, but safe because you can only pass a valid Index
  def get(i: this.Index): Int = unsafe_read(i) 
}

// Usage
val arr1 = new Array(100)
val arr2 = new Array(100)

// You call the smart constructor (check)
val safeIdx = arr1.check(50).get

// WORKS:
arr1.get(safeIdx) 

// FAILS COMPILE TIME:
arr2.get(safeIdx) // Error: Expected arr2.Index, got arr1.Index
```



## 3. Benefits & Architecture

### A. Separation of Concerns (Validator vs. Worker)
This pattern naturally separates code into two zones:
* **The Safe Shell (Validator):** Handles I/O, user input, and setup. Checks constraints. High safety, low frequency.
* **The Fast Core (Worker):** Receives validated `Index` tokens. Executes hot loops. Zero overhead, high frequency.

### B. "Chain of Trust"
Once a token `idx` is created for `arr1`, it remains valid indefinitely (assuming immutable size).
* You can pass `idx` to other functions.
* You can compose functions (map/filter) that preserve the index validity.
* The compiler prevents "Cross-Pollination" errors (using an index validated for `A` to access `B`).

```scala
val idx1 = arr1.check(5).get
val idx2 = arr2.check(5).get

arr1.get(idx1) // OK
arr1.get(idx2) // Compile Error: Expected arr1.Index, got arr2.Index
```

### C. Decidability vs. Fully Dependent Types
* **Full Dependent Types:** Require the compiler to solve `i < N`. If `i` is the result of a complex function, the compiler may time out or fail.
* **Path-Dependent Types:** Require the compiler to check nominal equality (`arr1` == `arr1`). This is $O(1)$ and always decidable.

## 4. Compilation & LLVM Lowering

### Type Erasure
This abstraction is strictly a **compile-time** construct.
* **Frontend:** Tracks `arr1.Index` vs `arr2.Index`.
* **Backend (Codegen):** Erases both types to the native integer width (e.g., `i64`).

**Result:** The generated LLVM IR contains raw integer arithmetic and direct memory pointers. There are no structs, no wrappers, and no runtime overhead.

### Aliasing & Optimization
While path-dependent types prove *safety*, they do not automatically prove *non-aliasing* to LLVM (since `arr1` and `arr2` could point to the same memory).

To maximize vectorization:
1.  **Safety:** Handled by Path-Dependent Types (removes branching/checks).
2.  **Performance:** Handled by **Metadata Injection** (`!noalias`, `!alias.scope`) in the backend, allowing LLVM to reorder and vectorize loads/stores aggressively.

## 5. Summary
Using Path-Dependent types allows MML to implement **Loop Invariant Code Motion (LICM)** manually at the type level.

* **Standard Compiler:** *Hopes* it can prove the check is inside the loop and redundant.
* **MML + Path Types:** *Forces* the check outside the loop via the type signature, guaranteeing the inner loop is essentially raw C code.


## Appendixes


### Syntax draft


```mml
# Drafting syntax

## 1. Opaque Structure
# 'opaque' visibility means:
#   * Creation restricted to the companion module.
#   * Field access restricted to the companion module.
data opaque Array 'T {
  data: *'T,
  
  # Stored as the opaque type. 
  # Inside the module, this is physically an Int.  
  length: Array::Index 
}

## 2. Companion Module
module Array =

  # 1. The Opaque Token
  # The compiler treats this as a unique type per instance path.
  # Inside the module, it is transparently an Int.
  type opaque Index = Int;

  # 2. The Validator (Smart Constructor)
  #
  # 'Self' Rule: In this signature, 'Self' refers to the specific instance path (self: Array 'T).
  # Return Type: 'Maybe self::Index' resolves to 'Maybe arr1::Index' at call site.
  def check(self: Self, i: Int): Maybe self::Index = 
    # Inside module: We can read 'length' (it's an Int here)
    if (i >= 0 && i < self.length) 
    # Inside module: We can cast Int -> Self::Index
    then Some (i: self::Index)
    else None 
    end
  ;

  # 3. The Accessor (Fast Core)
  #
  # Input Type: 'self::Index' enforces that 'i' must have come from 'self'.
  # Performance: Zero runtime checks. 
  def get(self: Self, i: self::Index): 'T = 
    intrinsic_unsafe_read(self.data, i)
  ;

  # 4. Binary Function (Copy)
  #
  # 'src' and 'dest' are distinct paths.
  # 'src::Index' ensures 'i' matches 'src'.
  # 'dest::Index' ensures 'j' matches 'dest'.
  def copy(dest: Self, src: Self, i: src::Index, j: dest::Index): Unit =
    let val = get src i;
    intrinsic_unsafe_write(dest.data, j, val);
  ;

;

## 3. Usage & Safety Proof

let arr1 = mkArray 100;
let arr2 = mkArray 100;

# 1. Validation
# 'check' binds Self -> arr1. 
# Returns: Maybe arr1::Index
val safe_idx = check arr1 10; 

# 2. Valid Access
# 'get' binds Self -> arr1. Expects arr1::Index.
# Matches 'safe_idx'. OK.
# Warning, pseudo code ignores Maybe 
get arr1 safe_idx;

# 3. Invalid Access (The Trap)
# 'get' binds Self -> arr2. Expects arr2::Index.
# You passed 'safe_idx' (arr1::Index).
# COMPILE ERROR: Type Mismatch (arr2::Index != arr1::Index).
get arr2 safe_idx;
```