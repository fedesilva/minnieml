# MML Task Tracking

## CRITICAL Rules

------------------------------------------------------------------------
/!\ /!\ Do not edit without explicit approval or direct command. /!\ /!\
/!\ /!\ Follow rules below strictly                              /!\ /!\
/!\ /!\ COMPLETING A TASK? -> ADD [COMPLETE] TAG. NEVER DELETE.  /!\ /!\
------------------------------------------------------------------------

* *Always read* `context/task-tracking-rules.md` 
  *before* working with this file - even if you read it before, 
  it might have changed in the meantime.

* *Always read* `context/coding-rules.md` 
  before working with this file - even if you read it before, 
  it might have changed in the meantime.

* Follow the rules in those documents faithfully.
* No exceptions

## Active Tasks

### Simple Memory Management Prototype

**Doc:** `docs/brainstorming/mem-man/1-simple-mem-prototype.md`

Linear ownership with borrow-by-default. Enables safe automatic memory management
and unlocks `noalias` parameter attributes for LLVM optimization.

**Key points:**
- Borrow by default, explicit move with `~` syntax
- Extend `@native` with `[mem=alloc]` / `[mem=static]` attributes
- New OwnershipAnalyzer phase inserts `__free_T` calls into AST
- `cap` field in String/Buffer for runtime ownership check (conditional merges)
- No codegen changes - just AST rewriting

**Phases:**
1. Hardcode native effects in compiler (no syntax changes)
2. Add `@native[...]` parsing
3. Implement OwnershipAnalyzer phase
4. Write programs, find edge cases, iterate

### [COMPLETE] Lsp cats warnings

* Added heartbeat fiber (50ms sleep + cede loop) - fixes idle warning during active use
* Still occurs after laptop sleep/wake - possibly clock skew related, low priority

2026-01-15T16:30:02.085Z [WARNING] Your app's responsiveness to a new asynchronous event (such as a new connection, an upstream response, or a timer) was in excess of 100 milliseconds. Your CPU is probably starving. Consider increasing the granularity of your delays or adding more cedes. This may also be a sign that you are unintentionally running blocking I/O operations (such as File or InetAddress) without the blocking combinator.

### [COMPLETE] Lsp Commands

* clean
* ast
* ir

all should work like the ones we have now.
in terms of messages, etc

### TARGET CPU

we are generating the target-cpu attribute and annotating with it all definitions.
BUT this will not work for cross compilation.

currently we have cpu and arch flags:

* remove the arch flag
  - stop passing both flags to opt and clang.
* change how emittig the target-cpu attribute  works
  - if present use that to generate the attribute
* if a target triple is passed we should not use the llvm-check-ok data
  since that most likely means we are cross compiling.

2. it should return useful information the editor can display


### Runtime: time functions

TBD


---

## Recent Changes

### 2026-01-15 (branch: 2026-01-14-dev)

- **LSP in-process compilation**: LSP no longer forks mmlc process for compile
  commands. Added `CompilerApi.compileBinaryQuiet` and `compileLibraryQuiet`
  methods that return error messages instead of printing. `LspHandler` now calls
  these directly.
- **CompilationError.message**: Added `def message: String` to `CompilationError`
  trait. Implemented for all error types: `ParserError`, `TypeError`,
  `SemanticError`, `LlvmCompilationError`, `CodeGenError`, `CompilerError`.
  Plain text messages without ANSI codes.
- **VSCode extension**: Updated to display error messages from LSP and log them
  to output channel.
- **IO.blocking for LSP**: Changed blocking operations to use `IO.blocking`:
  - `FrontEndApi.compile` - parsing/semantic analysis
  - `LlvmToolchain` - process execution, file I/O (collectLlvmToolVersions,
    queryAndCacheTriple, queryLocalTriple, extractRuntimeResource, executeCommand,
    checkLlvmTools, invalidateToolsMarker)
  - `CompilerApi.runPipelineQuiet` - codegen validation
- **LSP commands (clean, ast, ir)**: Added three new LSP commands:
  - `mml.server.clean` - cleans build directory
  - `mml.server.ast` - generates AST file
  - `mml.server.ir` - generates LLVM IR file
  Added `CompilerApi.cleanQuiet`, `processAstQuiet`, `processIrQuiet` methods.
  All use `CompilerConfig.default` (output to `build/` relative to CWD).
  VSCode extension updated with corresponding commands.
- **LSP heartbeat**: Added background heartbeat fiber in `LspServer` that runs
  `IO.sleep(50ms) *> IO.cede` in a loop. Prevents cats-effect CPU starvation
  warnings during active use. Warning still occurs after laptop sleep/wake
  (possibly clock skew) - low priority, revisit later.

### 2026-01-14 (branch: 2026-01-14-dev)

- **Benchmark infrastructure**: Added matmul and ackermann benchmarks,
  Makefile improvements, benchmark results and reports
- **Scoped TBAA**: Added scoped TBAA metadata for better alias analysis
- **Alias scope emitter**: New `AliasScopeEmitter` for alias scope metadata
  on function calls and memory operations
- **Host CPU attribute fix**: Fixed bug in `LlvmToolchain.readHostCpu` where
  `collectFirst { case line => ... }` matched all lines (total pattern),
  stopping at first line instead of finding `Host CPU:`. Changed to
  `.find(...).map(...)` pattern.

