# Language Design: Ownership & Move Semantics

## Core Philosophy: "Clone by Default, Move by Request"
The language prioritizes ergonomics and ease of use by defaulting to copy semantics, while providing a specific syntax (sigil) to opt-in to performance optimizations (moves) or to handle unique resources.

## The Mechanism
When a value is passed to a function or constructor (via positional juxtaposition):

1.  **Explicit Move (`~`):** If the value is marked with `~`, ownership is transferred (moved) to the callee. The original variable becomes invalid.
2.  **Implicit Clone:** If no `~` is present, the compiler attempts to clone the value.
    * **If `Clonable`:** The value is copied.
    * **If NOT `Clonable`:** A compiler error occurs.

## The `Clonable` Protocol
The behavior relies entirely on whether a type implements the `Clonable` protocol.

| Type Category | Example | `Clonable`? | Default Behavior (No `~`) | With `~` Sigil |
| :--- | :--- | :--- | :--- | :--- |
| **Trivial** | `Int`, `Point` | ✅ Yes | **Copy** (Cheap). User notices nothing. | **Move** (Register copy/invalidation). |
| **Heap/Heavy** | `String`, `List` | ✅ Yes | **Copy** (Expensive). Safe, but potential hidden allocation. | **Move** (Fast). Pointer swap; ownership transfers. |
| **Unique** | `Socket`, `Mutex`| ❌ No | **Error**. Compiler demands explicit intent. | **Move**. Ownership transfers successfully. |

## Syntax Examples

### 1. The Call Site (User Choice)
The user decides whether to copy or move a heavy resource using standard functional application (juxtaposition).

```text
let data = "Big String..."
let age = 30

// Default: Implicit Clone
// Safe, keeps 'data' alive, but allocates memory.
let p1 = Person data age

// Explicit Move: Optimization
// Fast, zero-allocation, but 'data' is consumed.
let p2 = Person ~data age
```

### 2. The Definition Site (API Enforcement)
A library author can enforce ownership transfer in the struct definition.

```text
struct Session {
    id: Int,        // Standard copy (or move if `~` passed)
    ~socket: Socket // Enforced Move: Caller MUST surrender ownership
}

// Usage:
// Session 123 my_socket  <-- Error: Socket is not Clonable
// Session 123 ~my_socket <-- Valid: Ownership transferred
```

## Summary of Benefits
* **Low Cognitive Load:** Beginners don't fight a borrow checker; code "just works" for clonable types.
* **Safety:** Unique resources (Files, Sockets) cannot be accidentally duplicated (compiler error).
* **Performance:** Advanced users can eliminate allocations easily using `~`.
