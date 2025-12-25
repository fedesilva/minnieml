# Multi-Module Compilation Strategy

This document outlines a strategy for compiling projects composed of multiple modules and packages.

## The "Package" Concept

A **package** is the fundamental unit of compilation. It is a collection of one or more MML
modules that are compiled together.

- **Discovery**: The compiler accepts a directory or a single file.
    - **Directory**: Looks for an optional `package.mml`. If found, it configures the
        package. If not, it's an implicit package with default settings.
    - **File**: Treated as a single-file package.
- **Configuration (`package.mml`)**: An MML file with special metadata:
    - `name`: Package name.
    - `target`: `bin` (executable), `lib` (native object), or `package` (compiled
        artifact `.mlp`).
    - `dependencies`: List of external packages.
- **Output**:
    - `bin`/`lib`: Native code (linked).
    - `package`: A `.mlp` file (Zip archive) containing serialized, typed ASTs and
        metadata. This artifact is fully type-checked and ready for internalizing by other
        projects.

## Compilation Architecture: The "Reactive" Pipeline

The compiler uses a **data-driven, reactive architecture** coordinated by a persistent
**Language Database** (e.g., SQLite/libSQL).

### The Core Loop

**Note**: This document uses "stage" for large groupings (Shallow, Deep, Codegen) and "phase" for
individual algorithms within a stage. See Appendix for detailed definitions.

1.  **Parallel Parsing & Shallow Analysis** (Symbol Resolution):
    - All source files are parsed in parallel.
    - **Symbol Resolution**: Each module resolves references to local definitions and explicit
      imports. Types are symbols—type names are resolved like value names. No type checking or
      inference happens here, only name binding.
    - **Tracking Unresolved**: Symbols that can't be resolved yet (from wildcard imports or
      missing dependencies) are tracked as unresolved, not errors. Errors are generated later
      once all dependencies are available.
    - **DB Export**: The module exports its **symbol table** (names, kinds, operator metadata)
      and its **dependencies** (what imports it needs) to the Language Database.
    - **Waitlist**: The module enters a "Pending" state in the DB.

2.  **Reactive Scheduling (The "Wait List")**:
    - The compiler queries the DB: "Which pending modules have all their dependencies
        satisfied?"
    - **Dependencies Satisfied** means: The *interface* data (symbols, operator metadata) for
        all imported modules is present in the DB.
    - Modules that are ready are picked up by worker threads.

3.  **Deep Semantic Analysis** (Type Checking & Inference):
    - **Error Generation**: Unresolved symbols from shallow phase are converted to errors.
    - **Final Resolution**: Query DB to resolve remaining references and fetch operator metadata
        (precedence, associativity) from imported modules.
    - **Expression Rewriting**: With operators resolved and precedence known, expressions are
        rewritten into their correct structure.
    - **Type Checking & Inference**: The module is fully type-checked. Type inference happens
        late in this stage.
    - **Finalization**: The fully resolved/checked AST is serialized (e.g., to Parquet) and
        updated in the DB/Storage.
    - **Notification**: Completing a module triggers a check to see if it unblocked other
        waiting modules.

### Circular Dependencies

The architecture handles dependencies as follows:

- **Module-Level Cycles**: Supported. If Module A needs Module B's *types*, and Module B needs
    Module A's *types*, the "Shallow Analysis" phase extracts those types first. As long as the
    *interfaces* can be resolved, the bodies can be compiled.
- **Package-Level Cycles**: Disallowed to keep the package graph acyclic.

### Incremental Compilation

The Language Database *is* the incremental state.
- **Content Hashing**: Source files are hashed.
- **Cache Hit**: If a file's hash hasn't changed, its DB entries are valid. The scheduler skips
    it.
- **Cache Miss**: The module is marked "Dirty" and re-queued. Any module depending on it
    (transitively) is also checked to see if re-compilation is needed.

## External Packages (.mlp)

External dependencies are handled by **internalizing** them into the compilation space.

1.  **Ingestion**: When a project depends on `pkg-a.mlp`, the compiler "mounts" it.
2.  **Indexing**: The metadata (symbols, types, operators) from `pkg-a` is loaded into the
    Language Database.
3.  **Transparency**: To the rest of the pipeline, `pkg-a` looks just like local modules that
    have already finished their "Shallow Analysis" phase. Local modules can resolve against them
    immediately.
4.  **Optimization**: Since the `.mlp` contains the full AST, the final codegen phase can
    perform **whole-program optimization** (inlining, specialization) across package boundaries.

## Summary of Stages

1.  **Ingest**: Load external `.mlp` metadata into DB.
2.  **Shallow Stage**: Parse local files, resolve symbols, export to DB. (Parallel)
3.  **Deep Stage**:
    *   Pick ready module.
    *   Generate errors for unresolved symbols.
    *   Rewrite expressions (using DB operator metadata).
    *   Type check and infer.
    *   Serialize result & update DB.
    *   Repeat.
4.  **Codegen**:
    *   **Binary**: Load full ASTs (local + external), perform AST-level optimizations
        (monomorphization, pruning, region inference), emit LLVM IR per-module (parallel),
        optionally perform LLVM LTO on resulting `.o` files.
    *   **Package**: Package the serialized ASTs and metadata into a `.mlp` zip (Parquet
        format for compact storage and partial reads).


## Appendix: Pipeline Details

This section expands on the compilation pipeline with more implementation-oriented details.
Note: This is still brainstorming—a proper spec will emerge during implementation.

### Terminology

- **Stage**: Large grouping of work (Shallow Stage, Deep Stage, Codegen)
- **Phase**: Individual algorithm within a stage (parsing phase, symbol resolution phase, type
  checking phase, inference phase, etc.)

### Shallow Stage: Symbol Resolution

The shallow stage is pure **name binding**—no type checking or inference. It builds the symbol
table and tracks dependencies.

**Step 1: Local Resolution** (can run immediately for each module):
1. Parse source file
2. Run `RefResolver`: resolve value/function references to local definitions and explicit imports
3. Run `TypeResolver`: resolve type references to local type definitions and explicit imports
4. Record explicit imports (`import A.{foo, Tree}`)
5. Export local symbol table to DB:
   - Symbol names and kinds (function, type, value, operator)
   - Operator metadata (precedence, associativity, fixity)
   - NOT full type information (just symbol existence)
6. Track unresolved symbols (from wildcard imports or missing deps) as a list, not errors
7. Enter wait list in DB

**Step 2: Validate Imports** (once dependencies finish their shallow stage):
1. Query DB for dependencies' exported symbol tables
2. Validate explicit imports: does `import A.{foo}` refer to an actual symbol in A?
3. Generate errors for invalid explicit imports

**Step 3: Resolve Free Symbols**:
1. Walk AST for symbols still marked as unresolved
2. Look up in dependencies' exported symbol tables (handles wildcard imports)
3. Mark as resolved or keep in unresolved list
4. Result: module with maximally resolved symbols, plus list of truly unresolved symbols

**Shallow Stage Output**:
- Module AST with resolved symbol references
- Symbol table export (for dependents to use)
- List of unresolved symbols (to be handled in deep stage)

### Deep Semantic Stage: Type Checking & Inference

The deep stage performs semantic analysis with full type information.

**Phase 1: Error Generation**
- Take list of unresolved symbols from shallow stage
- Generate `SemanticError.UndefinedRef` and `SemanticError.UndefinedTypeRef`
- If errors exist, skip remaining phases (can't type-check with undefined symbols)

**Phase 2: Expression Rewriting**
- Query DB for operator metadata from all resolved imports
- Rewrite expression ASTs according to operator precedence and associativity
- This determines the actual structure of expressions

**Phase 3: Type Checking**
- Validate type correctness throughout the AST
- Check function applications, operator usage, etc.
- No inference yet—only check what's explicitly typed

**Phase 4: Type Inference**
- Infer types for expressions without explicit type ascriptions
- Happens late because it requires full type information from all resolved imports

**Phase 5: Finalization**
- Serialize fully resolved and type-checked AST
- Store in DB/persistent storage (Parquet format)
- Notify scheduler: dependents may now be ready

### Codegen Stage: Optimization & Emission

**Binary Target** (`bin`/`lib`):
1. **AST-Level Optimization**:
   - Load full AST graph (local modules + all external `.mlp` packages)
   - Perform reachability analysis from entry point (or public interfaces for `lib`)
   - Prune unreachable code
   - Monomorphize generic code based on actual usage
   - Region inference and other high-level optimizations
   - This is whole-program optimization with full semantic information

2. **Parallel LLVM IR Emission**:
   - Generate LLVM IR for each reachable module (parallel)
   - Emit `.o` files per module

3. **LLVM LTO** (optional):
   - Link `.o` files
   - LLVM performs its own link-time optimization
   - Produces final binary

**Package Target** (`.mlp`):
- Package serialized ASTs and metadata into zip archive
- Use Parquet format for AST storage (compact, supports partial reads)
- Include symbol tables and operator metadata
- Result is a fully type-checked, reusable compilation artifact

### Cross-Module Mutual Recursion

Within a package (module cycles allowed):
- **Types**: Can be mutually recursive across modules (shallow stage extracts type symbols first)
- **Functions**: Can be mutually recursive across modules (shallow stage extracts function
  signatures)
- **Values**: Circular value dependencies are uncomputable and should be detected/rejected (can be
  caught during type checking or as a separate validation phase)

### Notes

- The existing `RefResolver` and `TypeResolver` implementations perform the core symbol resolution
  logic needed for shallow stage—they just need to be extended to query the DB for imported
  symbols instead of only looking at local `module.members`.
- Error handling strategy: collect unresolved symbols during shallow stage, generate errors in deep
  stage once all resolution attempts are exhausted. This allows the reactive loop to make progress
  even when some symbols aren't immediately resolvable.

## Open Topics

    * interface/symbol hashing 
        - besides hashing files to check if they changed, we will need to hash
            the exported symbols, to have more granular incremental compilation
    * deadlock detection
        - we will support circular dependencies, but this could get us into a 
            deadlock or infinite loop. need to think about this.

--f

