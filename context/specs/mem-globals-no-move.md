# Memory Ownership: Globals Are Borrow-Only (No Move)

## 1. Problem Statement

In MinnieML's affine ownership model, "consuming" parameters (`~` syntax) are ownership sinks.
For local bindings, passing an owned heap value to a consuming parameter "moves" it,
transferring responsibility for its destruction to the callee and invalidating the local name.

Top-level (global) bindings cannot be "moved" or invalidated because they must remain
available throughout the program's execution. Treating globals as movable introduces
temporal state that is incompatible with global initialization and subsequent references.

Currently, the compiler accidentally accepts globals in consuming parameters because they
fall into an "untracked" fallback path in the `OwnershipAnalyzer`. This results in
missing `__clone_T` calls, which can lead to double-frees (if the callee frees the global)
or invalid IR.

## 2. Semantic Rules (Updated 2026-03-14)

### 2.1 Global Bindings
- **Borrow-Only**: All top-level heap-allocated bindings are strictly borrow-only.
- **Persistence**: A global binding can never transition to the `Moved` state.
- **Auto-Clone**: When a global heap binding is passed to a `consuming` (`~`) parameter
  (of a constructor OR a regular function), the compiler automatically inserts a
  `__clone_T` call. This provides the callee with a fresh owned copy while preserving
  the original global.

### 2.2 Local Bindings
- **Owned**: Passed to consuming parameters via **move** (no clone).
- **Borrowed**: Passing a borrowed local to a consuming parameter results in a
  **Compiler Error** (`ConsumingParamNotLastUse`), as the caller doesn't have ownership
  to transfer.

### 2.3 Literals
- **Auto-Clone**: Since literals are static and cannot be "moved," they are always
  cloned when passed to a consuming parameter.

## 3. Detailed Implementation Blueprint

### Phase 1: Infrastructure Updates
**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala`

1.  **OwnershipState Enum** (Line 9):
    Add `case Global // Top-level binding, borrow-only`
2.  **OwnershipScope Constructor** (Line 24):
    Ensure `bindings` can be seeded from `analyzeMember`.

### Phase 2: Seeding Global Context
**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala`

1.  **Global Discovery** (`rewriteModule`, Line 1435):
    ```scala
    val globals = module.members.collect {
      case bnd: Bnd if bnd.typeSpec.exists(t => TypeUtils.isHeapType(t, module.resolvables)) =>
        bnd.name -> BindingInfo(OwnershipState.Global, bnd.typeSpec, bnd.resolvedId)
    }.toMap
    ```
2.  **Scope Initialization** (`analyzeMember`, Line 1385):
    Pass the `globals` map into the `OwnershipScope` created at line 1403.

### Phase 3: Auto-Clone for Globals
**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala`

1.  **argNeedsClone** (Line 744):
    Update to return `true` for `Global` heap-allocated bindings:
    ```scala
    case Some(OwnershipState.Global) => true
    ```
2.  **analyzeRegularApp** (Line 948):
    Remove the `if isConstructorCall` guard (Line 970). Broaden the `processedArgs` map logic to apply to any consuming parameter if `argNeedsClone` is true.

### Phase 4: Handling Consuming Parameters
**File**: `modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala`

1.  **handleConsumingParam** (Line 677):
    Add a case for `Global` (around line 718) to accept the usage without invalidating the binding:
    ```scala
    case Some(OwnershipState.Global) => (scope, Nil) // Accepted; wrapper ensures clone
    ```

## 4. Expected Outcome & Verification

### Code Example: `mml/samples/person-struct-borrow-global.mml`
```mml
struct Person { name: String, age: Int };
let name = "fede"; // Global String
let p = Person name 25; // Analyzer inserts __clone_String(name)

fn main() =
  println name; // Valid: name was never moved
;
```

### Verification Tasks
- [ ] **AST Verification**: Inspect AST of `Person name 25` to ensure `wrapWithClone` was applied.
- [ ] **IR Verification**: Confirm `@__clone_String` is called in the generated LLVM IR.
- [ ] **Memory Test Suite**: Run `./tests/mem/run.sh all` (ASan/Leaks check).
- [ ] **Regression Tests**: Verify local `Borrowed` still fails and local `Owned` still moves correctly.

