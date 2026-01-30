# Initial Module System

An abridged implementation of the north star architecture described in
`docs/brainstorming/compiler/reactive-data-oriented-compiler/`.

This document outlines the preliminary work, compilation algorithm, and migration strategy
for introducing multi-module compilation to MML.

---

## Preliminary Work

Ideally independent tasks that can be tackled in separately.

### Nested Modules

We want nested modules working before proceeding with multi-file compilation.

**Syntax:** Using `module` keyword inside a file (which is itself a module):

```mml
module A =
  ...
;

module B =
  module C =
    ...
  ;
;
```

**Imports:**
- Could get away without them using fully-qualified names, but we want to reference operators
- Imagine writing `2 ModuleA.ModuleB.+ 2` — not sure this will even parse
  - we would like to use `use ModuleA.ModuleB.+` instead
- Imports will only be allowed at top level

**Selection:**
- `Module.member` or `Module.Module.member`
- Ref resolver will have to work with this
- We already have a form of selection for structs

**Semantic phase updates:**
- Ref resolver — walk through selections
- Type resolver — walk through selections
- TypeChecker — walk through selections
- Expression rewriter — possibly affected

### Package Concept

A Package is the top-level container: all modules of the program plus build metadata.

**Compiler input:**
- If file: package is implicit, use defaults or CLI flags (lib or exe for example)
- If folder: package is implicit unless there is a config file
  - No config file → use defaults or CLI flags
  - Config format TBD (probably TOML, YAML, or HOCON)

---

## Compilation Algorithm

### Parallel Initial Phase

- Find and parse all files
  - Each folder inside the package is a module, subfoders and files are submodules.
  - if single file, no folders are involved.
- Resolve internal value references (what can't be resolved waits)
  - In theory everything will resolve to an import, but because of `*` imports,
    in practice we will not be able *most* of the time.
- Resolve internal type references (what can't be resolved waits)
  - same note as above.
- Export public symbols (values and types)
- Create dependency list from import declarations
- Put dependencies in a shared system list

### Parallel Middle Phase

- When modules you depend on save their exports, compiler wakes waiting modules
- Load external symbols (types and values)
- Rewrite expressions using local and external metadata
- Local type checking with current simple typechecker
  - Instead of failing on unknown types, create type variables (constrained if info is enough)

### Sequential Final Phase

Once all files are processed and all modules are compiled and rewritten:

- Run second typechecker (bidi, generalizing)
- This one fails if it can't work out the types

---

## Migration Strategy

The algorithm above needs to be woven into the current architecture using the same
stage-with-phases approach.

### Current Architecture

**`CompilerState`** (per-module):
- `module: Module` — the AST
- `sourceInfo: SourceInfo` — source code
- `config: CompilerConfig` — build configuration
- `errors`, `warnings`, `timings`, `counters`

**Stages:**
- `IngestStage.fromSource` → parse source into `CompilerState`
- `SemanticStage.rewrite` → phases run sequentially via `|>` pipe

### New Architecture

**Two levels of state:**

1. **`PackageState`** (package-level, new):
   ```scala
   case class PackageState(
     moduleTree: ModuleTree,
     moduleStates: Map[ModuleId, CompilerState],
     exportTable: Map[ModuleId, ExportList],
     signals: Map[ModuleId, Deferred[IO, ExportList]]
   )
   ```

2. **`CompilerState`** (per-module, extended):
   - Add `imports: List[Import]` — parsed import declarations
   - Add `exports: Option[ExportList]` — computed during LocalSemantic

**Export table:**
- Each module computes its `ExportList` locally during LocalSemantic
- Then publishes to `PackageState.exportTable` (shared)
- Other modules await via `Deferred` signals

### What Exports Are (and Are Not)

**Exports are symbols, not semantic types.**

An `ExportList` contains just enough information to complete *name resolution* — not type checking.
Type checking happens later and validates semantic correctness.

**Example:** If module `A` has:
```mml
struct Person { name: String, address: Address };
```

And `Address` is defined in module `B`, then:
- `B`'s export list says: "I have a type called `Address`"
- `A` can now resolve the name `Address` to `B.Address`
- Later, the type checker validates that usage is semantically correct

**Why this matters:**

The export list exists to allow a second resolution pass (SecondSemantic) that finishes
ref-resolution and type-resolution across module boundaries. This is *not* type checking.

Crucially, **expression rewriting needs operator metadata** (precedence, associativity, arity)
to rewrite expressions correctly. Without knowing that `B.+` is a binary operator with
precedence 60 and left associativity, we can't rewrite `1 B.+ 2 B.* 3` properly.

**What an export entry contains:**
- Name (e.g., `"Address"`, `"+"`)
- Kind (type, function, operator)
- For operators: precedence, associativity, arity

**What it does NOT contain:**
- Semantic type information
- Type compatibility rules
- Anything the type checker needs to validate correctness

### Stages Draft

**IngestStage (package-aware):**
- Walk folder tree, collect all file paths (cheap, just strings)
- Parse all files in parallel → `Map[ModuleId, CompilerState]`
- Build `ModuleTree` from parsed modules (use path info to reconstruct hierarchy)
- Create `Deferred[IO, ExportList]` signal for each module
- Return `PackageState`

**LocalSemantic (parallel per module):**
- inject-stdlib (same as now)
  - *Goes away once multi-file lands — stdlib becomes real `.mml` source, no longer injected AST*
- duplicate-names
- id-assigner
- type-resolver (local types only)
- **Compute `ExportList`** from public members (fn, op, types)
- **Complete** the module's `Deferred` signal

**SecondSemantic (parallel per module, with coordination):**
- **Await** `Deferred` for each import dependency
- Load external symbols into scope
- type-resolver (now aware of imported symbols)
- ref-resolver (now aware of imported symbols)
- expression-rewriter (purely local)
- simplifier
- type-checker (SimpTyper)
- resolvables-indexer
- tailrec-detector

**FinalSemantic (sequential, entire package):**
- Run second typechecker (bidi, generalizing)
- Fails if types can't be resolved

### Coordination Pattern

Using cats-effect `Deferred` for module synchronization:

```scala
// IngestStage creates signals
val signals: Map[ModuleId, Deferred[IO, ExportList]] =
  moduleIds.traverse(
    id => Deferred[IO, ExportList].map(id -> _)
  ).map(_.toMap)

// LocalSemantic completes its signal
def localSemantic(moduleId: ModuleId, state: CompilerState): IO[CompilerState] =
  for
    newState   <- runLocalPhases(state)
    exportList  = computeExports(newState.module)
    _          <- signals(moduleId).complete(exportList)
  yield newState.copy(exports = Some(exportList))

// SecondSemantic awaits dependencies
def secondSemantic(moduleId: ModuleId, state: CompilerState): IO[CompilerState] =
  for
    imports        <- state.imports.traverse(imp => signals(imp.moduleId).get)
    externalScope   = buildScope(imports)
    newState       <- runResolutionPhases(state, externalScope)
  yield newState
```

*Note: This section is in progress.*

---

## Appendix A: IngestStage Algorithm

Detailed description of the maximum-parallelism ingestion strategy.

### Overview

The goal is to saturate all cores during parsing while building an immutable `ModuleTree`.
We achieve this by separating discovery from parsing:

1. **Collect** — walk filesystem, gather paths (cheap, sequential)
2. **Parse** — parse all files in parallel (expensive, parallel)
3. **Assemble** — build tree from parsed modules (cheap, sequential) and folders.
  - remember, folders are modules, too.

### Phase 1: Collect Paths

Walk the package folder recursively, collecting `.mml` file paths.
Each path gets a `ModuleId` based on its location in the hierarchy.

```scala
case class ModuleEntry(
  id: ModuleId,
  path: Path,
  parentId: Option[ModuleId]
)

def collectPaths(root: Path): List[ModuleEntry] =
  // Walk folder tree
  // For each .mml file: create ModuleEntry with id derived from path
  // parentId links file to its containing folder module
```

**ModuleId derivation:**
- `src/Foo.mml` → `ModuleId("src::Foo")`
- `src/Bar/Baz.mml` → `ModuleId("src::Bar::Baz")`
- Folder `src/Bar/` → `ModuleId("src::Bar")` (implicit module)

### Phase 2: Parse in Parallel

Parse all collected files concurrently. Each worker loads one file on demand.

```scala
def parseAll(entries: List[ModuleEntry]): IO[Map[ModuleId, CompilerState]] =
  entries
    .parTraverse { entry =>
      parseFile(entry.path).map(state => entry.id -> state)
    }
    .map(_.toMap)
```

Memory is bounded by worker count, not file count — each worker holds one source at a time.

### Phase 3: Assemble Tree

Build `ModuleTree` from parsed modules using `parentId` relationships.

```scala
case class ModuleNode(
  id: ModuleId,
  children: List[ModuleNode]
)

def buildTree(
  entries: List[ModuleEntry],
  parsed: Map[ModuleId, CompilerState]
): ModuleTree =
  // Group entries by parentId
  // Build tree bottom-up: leaves first, then parents
  // Each node references its CompilerState via id
```

The tree is constructed once, immutably. No updates needed after initial build.

### Why This Works

- **No memory spike**: files loaded on-demand by workers, not all at once
- **Full parallelism**: all cores busy during parse phase
- **Clean data flow**: three distinct phases, easy to reason about
- **Immutable-friendly**: tree built in one shot from complete data
