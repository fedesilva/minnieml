# Deeper notes on MML: reactive data-driven compiler architecture

## 1. core philosophy: "mechanical sympathy"
* Goal: Build a compiler that works *with* the hardware and OS, not against it.
* Key Tactic: Use **"Out-of-Core"** algorithms.
    * RAM is a Cache: Never assume the whole project fits in the JVM Heap.
    * OS Managed: Use `mmap` and the OS Page Cache for memory management.
    * Zero-Copy: Use Apache Arrow IPC to share data between the compiler and LSP without serialization overhead.

## 2. the architectural split
The architecture separates the **Control Plane** (Coordination) from the **Data Plane** (Heavy Lifting).

### A. the control plane (SQLite)
* Role: Metadata, State, Dependency Graph, and Coordination.
* Why: Provides ACID compliance, easy concurrency (WAL mode), and a queryable graph for cycle detection.
* Location: `build/state.db`

### B. the data plane (Arrow & Parquet)
* Intermediate Cache (`.arrow`):
    * Format: Arrow IPC (Feather).
    * Characteristics: Uncompressed, memory-mappable, instant read/write.
    * Role: The "Hot" build cache used during active development and by the LSP.
* Final Artifact (`.mlp`):
    * Format: Parquet (inside Zip).
    * Characteristics: Heavily compressed, columnar.
    * Role: The "Cold" distribution format. Supports **Partial Reads** (loading types without loading bodies).

---

## 3. directory structure
All generated artifacts live in `build/` to keep the source clean and allow easy `clean` operations.

```text
my-project/
├── src/
│   ├── main.mml
│   └── user.mml
├── package.mml
└── build/                <-- Ephemeral build artifacts
    ├── state.db          <-- SQLite (Status, Deps, IOUs)
    ├── cache/            <-- The "Hot" Arrow Files (Memory dumps)
    │   ├── main.arrow
    │   └── user.arrow
    ├── bin/              <-- Native Binaries / Libraries
    │   └── my-app
    └── dist/             <-- Portable MML Packages
        └── my-pkg.mlp
```

---

## 4. the compilation pipeline: "the sieve strategy"
Instead of a binary choice between "Parallel" or "Global," we use a filtering approach that handles clean code fast and complex code reliably.

### Phase 1: the optimistic sprint (parallel)
* Action: Parse and resolve all modules in parallel.
* Inference Strategy: Local inference.
* Handling Dependencies:
    * If a dependency is explicit (e.g., `user.id: Int`): Resolve immediately.
    * If a dependency is unknown/inferred: **Do not block.**
    * Create an IOU: Record a "Wait" constraint in SQLite (`auth` waits for `user.id`).
    * Stall: Mark the module as `'stalled'` and move on.
* Result: Simple/Explicit code is finished. Complex/Circular code is queued.

### Phase 2: the reactive loop (constraint propagation)
* Mechanism: Event-driven resolution via SQLite (push, not poll).
* Loop:
    1.  Worker finishes inferring `user.mml`.
    2.  Worker updates `exports` table in SQLite.
    3.  Worker notifies coordinator: "module complete."
    4.  Coordinator queries `ious` table: "Who was waiting for `user`?"
    5.  Validate exports: Check if requested symbols exist in `user`'s exports. If not, fail immediately with error.
    6.  Dependent modules (`auth.mml`) with satisfied IOUs are woken up and re-queued.
* Termination: Continues until the queue is empty or no progress is made.

### Phase 3: the SCC fallback (global solver)
* Trigger: Queue is empty, but 'stalled' modules remain (True Cycles).
* Analysis: Query SQLite to find Strongly Connected Components (SCCs).
* Action:
    * Batch the cycle (e.g., `A <-> B`) into a single "Super-Module."
    * Run a global inference pass on the batch.
* Telemetry: Opens the possibility of emitting a **Performance Warning** to the user:
    > *"Cycle detected between A and B. Global inference required. Add explicit types to `A.foo` to restore parallel speed."*
    - if the problem becomse pathological they can address it, because we have the tool to pinpoint the issue and let them know.

---

## 5. data architecture details

### A. the SQLite schema (control plane)
```sql
-- Track build status and source hashes
CREATE TABLE modules (
    id INTEGER PRIMARY KEY,
    path TEXT UNIQUE,
    status TEXT,         -- 'pending', 'stalled', 'complete'
    parquet_path TEXT,
    src_hash TEXT
);

-- The Interface (What we have)
CREATE TABLE exports (
    module_id INTEGER,
    name TEXT,
    kind TEXT,           -- 'func', 'type', 'val'
    type_sig BLOB,       -- Known signature
    doc_md TEXT,         -- For LSP Hover
    UNIQUE(module_id, name)
);

-- The IOUs (What we want)
CREATE TABLE ious (
    waiter_module_id INTEGER,
    target_module_name TEXT,
    target_symbol_name TEXT
);

-- Symbol Definitions (For LSP 'Go to Definition')
CREATE TABLE definitions (
    id INTEGER PRIMARY KEY,
    module_id INTEGER,
    name TEXT,
    kind TEXT,           -- 'func', 'type', 'val'
    file TEXT,
    line INTEGER,
    col INTEGER
);
CREATE INDEX idx_def_name ON definitions(name);

-- Cross-Module References (For LSP 'Find References')
CREATE TABLE references (
    target_def_id INTEGER,
    source_module_id INTEGER,
    file TEXT,
    line INTEGER,
    col INTEGER
);
CREATE INDEX idx_ref_target ON references(target_def_id);
```

### B. the Arrow AST schema (data plane)
To map recursive ASTs to flat Arrow vectors, we use a **Structure of Arrays (SoA)** approach with integer IDs.

* Concept: `NodeID` is an `Int32` index, not a pointer.
* Storage: Distinct Vectors for distinct Node Types.

**Vector 1: Binary Expressions**
| Index | Op (Enum) | Left_ID (Int32) | Right_ID (Int32) |
| :--- | :--- | :--- | :--- |
| 10 | ADD | 2 (Lit) | 5 (Var) |

**Vector 2: Literals**
| Index | Value (Union/Dense) |
| :--- | :--- |
| 2 | 42 |

**Vector 3: Function Calls**
| Index | Func_ID | Args_List (Offset Buffer) |
| :--- | :--- | :--- |
| 99 | 10 | `[2, 5]` |

Usage Pattern:
* Persistence: Arrow serves as durable storage format (cache & distribution).
* Compilation: Workers load Arrow ASTs via `mmap`, work with in-memory structures (optionally deserialize to pointer-based AST for complex transformations).
* LSP: Rarely touches Arrow directly—SQLite indexes provide surface info (symbols, locations, types). Arrow loaded only for deep semantic operations.

### C. Arrow immutability & phase transitions

Critical Design Constraint: Arrow files are **immutable**—no in-place updates.

Compiler phases interact with Arrow storage through a **delta files** approach, where each phase produces incremental artifacts that reference parent phases rather than copying entire ASTs.

See: `multi-module-3.md` for the complete Data Plane Specification, including:
- Physical memory layout (AST to Arrow mapping)
- Delta file strategy and overlay reads
- Concrete schema definitions for all node types
- Access patterns and implementation details

---

## 6. tooling & UX benefits

### A. the "instant" LSP
* The Compiler and LSP share the **same brain** (SQLite + Memory-Mapped Arrow).
* Query Strategy: ~90% of LSP operations are **pure SQL queries** (no AST traversal):
    * Go to Definition: `SELECT file, line, col FROM definitions WHERE name = ?`
    * Find References: `SELECT file, line FROM references WHERE target_def_id = ?`
    * Hover: `SELECT type_sig, doc_md FROM exports WHERE name = ?`
    * Completions: Query `exports` table with module filters
* Deep Operations: Rare cases (refactoring, complex semantic queries) load Arrow ASTs via `mmap`.
* Result: Instant navigation upon editor startup. SQLite indexes eliminate AST scans.

### B. session persistence (incremental builds)
* Scenario: Developer closes the IDE, switches branches, or restarts the build server. `user.arrow` is safe on disk.
* Next Session:
    * Compiler checks `build/state.db`.
    * `user.mml` hash matches? -> `mmap` existing `build/cache/user.arrow`. **(Zero Parse Cost)**
    * `main.mml` dirty? -> Re-parse `main.mml`.

---

## 7. build targets & outputs
MML supports three distinct output modes, configured via `package.mml`.

### A. native binary (`target = "bin"`)
* Goal: A standalone executable for the host machine.
* Process:
    1.  Load typed ASTs (Arrow) for all reachable modules.
    2.  Perform monomorphization & tree-shaking.
    3.  Emit LLVM IR -> Object Files (`.o`).
    4.  Link system libraries (libc, libm).
* Output: `build/bin/my-app` (Executable).

### B. native library (`target = "lib"`)
* Goal: A static or shared library for embedding in C/Rust/C++ applications.
* Process:
    1.  Identify functions exported with `extern` or `public`.
    2.  Emit LLVM IR -> Object Files.
    3.  Archive into `.a` (Static) or link into `.so`/`.dylib`/`.dll` (Shared).
    4.  Generate C headers (optional).
* Output: `build/bin/libmy-pkg.so` + `include/my-pkg.h`.

### C. portable package (`target = "pkg"`)
* Goal: A reusable MML library for distribution (e.g., to a package registry).
* Process:
    1.  Take the fully typed Arrow ASTs.
    2.  Convert Arrow -> Parquet (Heavy Compression).
    3.  Bundle with `package.mml` metadata into a Zip archive.
* Output: `build/dist/my-pkg.mlp`.
* Consumer Usage: Other MML projects import this file. The compiler reads the Parquet type columns *without* loading the function bodies until Codegen time.

---

## 8. refinements & open topics

### A. export hashing (granular incremental compilation)

Problem: Source file hashing invalidates all dependents even if only implementation (not interface) changes.

Solution: Hash individual exports, not entire files.

Export Hash Formula:
```
export_hash = hash(name + kind + type_sig + operator_metadata)
```

Implementation:

```sql
-- Extend exports table
ALTER TABLE exports ADD COLUMN export_hash TEXT;

-- Compute per-export when module completes
INSERT INTO exports (module_id, name, kind, type_sig, doc_md, export_hash)
VALUES (?, ?, ?, ?, ?, hash(name || kind || type_sig || op_metadata));
```

Invalidation Logic:

1. Module B Changes: Recompile B, compute new export hashes
2. Compare Hashes: Query old vs new hashes for B's exports
3. Selective Wake: 
   ```sql
   -- Find modules that import changed exports
   SELECT DISTINCT waiter_module_id 
   FROM ious 
   WHERE target_module_name = 'B' 
     AND target_symbol_name IN (
       SELECT name FROM exports 
       WHERE module_id = B_id 
         AND export_hash != old_hash
     );
   ```
4. Recompile Only Affected: Wake modules that depend on changed exports

Example:
- Module A imports `B.foo`
- Developer changes `B.bar` implementation (different function, unrelated)
- `B.bar` hash changes, `B.foo` hash unchanged
- Result: A does not recompile ✓

**Refinement: Type Shape Hashing**

For composite types (records, variants), consider hashing the "shape" separately from full definition:

*Example:*
```
# Version 1
type User = { id: Int, name: String }

# Version 2 (adds optional field with default)
type User = { id: Int, name: String, email: Option String = None }
```

If a dependent module only accesses `user.id` and `user.name`, it doesn't need recompilation when `email` is added. The **type shape** (accessed fields) hasn't changed from the dependent's perspective.

Implementation:
- Hash per-field access patterns: `shape_hash = hash(accessed_fields)`
- Track field usage in `references` table: `field_name TEXT`
- On type change, compare: old shape vs new shape for each dependent's usage

This is an advanced optimization—start with full type signature hashing, refine later.

Benefits:
- Precise: Only signature changes trigger recompilation
- Fast: Avoids cascading recompiles for implementation-only changes
- Scalable: Critical for large projects with deep dependency chains
- Structural awareness: Type shape hashing reduces false invalidations for structural changes

### B. deadlock detection (handling true cycles)

Problem: Phase 2 (Reactive Loop) could stall forever if true cycles exist or dependencies are missing.

Detection Strategy:

1. Progress Tracking: Count modules resolved in each iteration of Phase 2
2. Stall Condition: If `resolved_count == 0` and `pending_modules > 0`, no progress is being made
3. Analysis: Query SQLite to diagnose:
   ```sql
   -- Find modules stuck in 'stalled' state
   SELECT id, path FROM modules WHERE status = 'stalled';
   
   -- Find their unsatisfied dependencies
   SELECT waiter_module_id, target_module_name, target_symbol_name
   FROM ious
   WHERE waiter_module_id IN (SELECT id FROM modules WHERE status = 'stalled');
   ```

4. Classify Issue:
   - True Cycle (SCC): Multiple stalled modules form a cycle → Phase 3 (SCC Fallback)
   - Missing Export: Module A waits for `B.foo`, but B only exports `B.bar` → **Non-recoverable Error**
   - Missing Module: Module A waits for module C, but C doesn't exist → **Non-recoverable Error**
   - Infinite Loop (Bug): Should not happen with proper IOU tracking → Log & Error

Important: Missing exports are detected **early** (Phase 2, when target module exports symbols), not during deadlock detection. As soon as module B completes its shallow phase and exports to the DB, any IOU waiting for a non-existent `B.foo` is immediately identified and reported. No need to wait for other symbols to resolve—the symbol name is either in the exports table or it isn't.

Missing modules are also detected early when dependencies are first queried. Deadlock detection (Phase 3 trigger) only catches **true cycles** and potential bugs in the IOU tracking system.

Distinguishing Errors:

*True Cycle:*
```
❌ Error: Circular dependency detected
  auth.mml:15 → user.mml:23 → auth.mml:42

  Add explicit type annotations to break the cycle.
```

*Missing Export (Unresolved Reference):*
```
❌ Error: Unresolved import in auth.mml:15
  Waiting for: user.validateToken
  Available in user.mml: authenticate, createUser, deleteUser

  Did you mean: user.authenticate?
```

*Missing Module:*
```
❌ Error: Module not found
  auth.mml:3 imports 'crypto'
  No module or package named 'crypto' found in project or dependencies.
```

**Phase 3 Trigger:**
```
IF progress == 0 AND stalled_count > 0:
    sccs = find_strongly_connected_components(stalled_modules)
    FOR EACH scc IN sccs:
        IF scc.size > 1:
            # True cycle - batch into super-module
            run_global_inference(scc)
        ELSE:
            # Single isolated module - likely missing dependency
            report_error(scc[0], "Unsatisfied dependencies")
```

User Feedback:
When Phase 3 is triggered, emit actionable telemetry:
```
⚠ Performance Warning: Cycle detected between user.mml and auth.mml
  Fallback to global inference (slower compilation)
  
  Suggestion: Add explicit type annotation to break cycle:
    - auth.mml:15  fn validate(user: ???) -> Result
    - user.mml:23  fn authenticate(token: ???) -> User
```

Implementation Notes:
- Use Tarjan's algorithm for SCC detection (linear time, single pass)
- Store cycle info in SQLite for LSP diagnostics (underline cyclic imports)
- Cycles across packages are still disallowed (checked during ingestion)

---


### C. region inference & pipeline placement

Question: How does region inference interact with the reactive compilation pipeline?

Answer: Region inference is a **post-type-checking, pre-codegen optimization**.

Pipeline Placement:

1. Shallow Stage: Symbol resolution, no region analysis
2. Deep Stage: Type checking, type inference (regions not yet assigned)
3. Pre-Codegen Analysis: 
   - Region Inference: Analyze escape patterns, determine region boundaries
   - Lifetime Analysis: Compute value lifetimes within inferred regions
   - Memory Layout: Decide lane layouts (SoA/AoS) per region
4. Codegen Stage: Emit LLVM IR with region-aware allocations

**Why This Order?**

- Region inference requires full type information: Need to know function signatures, return types, parameter lifetimes
- Escape analysis needs call graph: Must see all function calls to determine if values escape
- Per-module analysis insufficient: Region inference benefits from whole-program view (across modules)

**Implementation Strategy: Out-of-Core via Escape Summaries**

Problem: Loading all typed ASTs into memory violates the "RAM is a Cache" principle. For large projects (1M+ LOC), this would blow the heap.

Solution: Bottom-up streaming analysis using **Escape Summaries**.

Region inference doesn't need full ASTs—just escape behavior. Process functions in topological order (leaves → roots), computing and storing tiny summaries instead of loading the entire program.

Escape Summary Schema:
```sql
CREATE TABLE escape_summaries (
    function_id INTEGER PRIMARY KEY,
    returns_pointer BOOLEAN,
    captured_params TEXT,      -- JSON array: [0, 2] (which params are captured)
    allocates_in TEXT,          -- JSON array: ["r1", "r2"] (region names)
    escape_behavior TEXT        -- 'NoEscape' | 'EscapeViaReturn' | 'EscapeViaCapture'
);
```

**Process (Streaming, O(Single Function) memory):**

```
1. Build call graph from Deep Stage metadata (already in SQLite)
2. Topologically sort: leaves first, roots last
3. For each function in order:
   a. mmap function's AST from Arrow (single function only)
   b. Compute escape behavior:
      - Does it return a pointer?
      - Does it capture arguments? Which ones?
      - Does it allocate? In which regions (explicit or inferred)?
   c. Query callee summaries from SQLite (tiny structs, not ASTs)
   d. Infer this function's regions based on:
      - Callee summaries
      - Explicit region annotations (user-provided)
      - Escape patterns
   e. Emit RegionSummary to SQLite
   f. Unload AST (munmap)
4. Proceed to codegen (load ASTs on-demand, with region metadata from summaries)
```

Memory Usage: O(single function), not O(whole program). Adheres to out-of-core philosophy.

User Control:

While inference is the goal, users can provide explicit region annotations (as seen in the mechanical sympathy doc). The compiler:

- Respects explicit regions: User-defined boundaries are preserved
- Infers within boundaries: Optimizes allocations within user-specified regions
- Validates safety: Ensures no lifetime violations across boundaries

LSP Integration:

Escape summaries and region inference results available for LSP queries:
```sql
-- Escape summaries (already shown above)
CREATE TABLE escape_summaries (...);

-- Inferred region assignments
CREATE TABLE region_assignments (
    function_id INTEGER,
    value_id INTEGER,
    region_name TEXT,
    is_explicit BOOLEAN,  -- User-defined vs inferred
    lifetime_bound TEXT
);
```

LSP can show:
- Hover: Display inferred regions and escape behavior for functions
- Diagnostics: Highlight potential lifetime violations or suggest region annotations
- Inlay Hints: Show inferred region boundaries in code

---

