# Platform-Specific Language Design for CUDA (or others) Integration in MML

## Overview

This document summarizes the design considerations for MML around GPU/CPU/platform genericity.

## Key Design Decisions

### Compilation Strategy

**Single Binary, Dual Backends:**

```
     .mml source
         |
       MMLC
      /     \
     /       \
LLVM IR     PTX
   |           |
  CPU         GPU
   |           |
    \         /
     \       /
   Link together
```

* One compilation process generates both CPU code (via LLVM IR) and GPU code (via PTX)
* No runtime code generation - all compilation happens at build time
* Clean toolchain integration:

```bash
mml → LLVM IR → llc → .o
mml → PTX → ptxas → .o
linker → final executable
```

### Platform Abstraction

**Platform as a specialization parameter**

* Everything is platform agnostic, unless annotated with  \<platform>.
* Functions, protocol instances or effect handlers can be annotated.
* Same source code compiles to different execution models based on platform instantiation

  \\

Within any function, handler, or protocol instance that selects a platform, that choice applies to the entire body and its transitive callees. Every effect, function, and protocol used must be platform‑agnostic or have an instance for that platform; otherwise compilation fails.

**Example:**

```mml
fn process_array <cuda> (data: Array Float, size: Int) =
  let idx = thread_index  # tid.x on GPU, loop counter on CPU
  # ...
  ???
;
```

### Effect System Integration

### Run Effect and Platform-Aware Execution

To support device execution (e.g. CUDA), MML provides a `Run` effect:

```mml
effect Run =
  fn exec 'A 'B ( f: ('A -> 'B) ): 'B
;
```

* The compiler follows the function passed to `exec`, typechecks it for the target backend, 
  and emits platform-specific code.
* Handlers are responsible for launching on the chosen backend; on CUDA this lowers to:

```llvm
call i32 @cudaLaunchKernel(i8* %kernel_func, ...)
```

**Native annotation example**

The `@native` annotation can be used to speficy a platform specific - potentially 
hardware implemented - operations like we allow right now for llvm based platforms.

```mml
fn exp <cuda> (f: Float): Float = @native(op="__expf")
```

This functionality extends to platform specific effect handlers or protocol instances.
One can imagine a cuda specific implementation of the `Num` protocol, where

**Platform-Specific Effect Handlers:**

* Effect handlers can be specialized to a platform.
* The same effects (Memory, Parallel) can have different implementations per platform.
* Handlers may also be declared **without** `<>` for a host‑default implementation.

**Memory Effect Example:**

```mml
effect Memory =
  fn alloc 'T (size): Ptr 'T
  fn free 'T (Ptr 'T): Unit
;

handler Memory =
  fn alloc 'T (size) = malloc (size * sizeof 'T)
  fn free  'T (ptr) = free ptr
;

handler Memory <cuda> =
  fn alloc 'T (size) = cudaMalloc &ptr (size * sizeof 'T)
  free 'T (ptr) = cudaFree ptr
;
```

## Technical Implementation

### Code Generation Paths

1. **CPU Path:** MML → LLVM IR → Object File
2. **GPU Path:** MML → PTX Assembly → Object File (via ptxas)
3. **Linking:** Standard linker combines both object files with CUDA runtime

### Platform Polymorphism

* **Monomorphization:** The compiler creates concrete instances for each platform.
* **Effect Resolution:** Platform-specific handlers selected at compile time.
* **Memory Models:** Different memory semantics abstracted through platform traits.

### Pure Functional Core

The computation logic remains platform-agnostic:

```mml
let computation =
  let data = Memory.alloc 1000;
  let processed = Parallel.map (x => x * 2) data;
  let result = Parallel.reduce (+) processed;
  let _ Memory.free data;
  result
```

Platform selection happens at the handler level, not in the computation itself.

## Benefits

1. **Write Once, Run Anywhere:** Same algorithm works on CPU and GPU
2. **No Runtime Overhead:** All platform decisions made at compile time
3. **Clean Separation:** Pure computation separate from platform concerns
4. **Type Safety:** Platform constraints enforced by type system
5. **Optimal Code:** Each platform gets native, optimized code generation

## Architecture Summary

This design achieves **portable parallelism** through:

* Platform-parametric generics (like type generics but for execution targets)
* Effect handlers as hardware abstraction layer
* Dual backend compilation (LLVM IR + PTX)
* Pure functional core with effectful platform-specific boundaries

The result is a language where the same source code can be efficiently compiled to both sequential CPU execution and parallel GPU execution, with all platform-specific concerns handled through the effect system and resolved at compile time.
