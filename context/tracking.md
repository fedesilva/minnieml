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

### LSP forks for commands

* this is dumb: lsp is mmlc, yet it forks out a new instance.
* extend the CompilerApi with a method that does not print errors, 
  returns them for lsp consumption (one for bin, one for lib) 

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

### Lsp command errors fail to propagate

In VScode console:

```
Compiling to binary... file:///Users/f/Workshop/mine/mml/mml/mml/samples/no-main.mml
[Error] Header must provide a Content-Length property.
{"\n\u001b[31mcompilation failed":"\u001b[0m\n\n\u001b[33mFile: mml/samples/no-main.mml\n\n\u001b[31mNo entry point 'main' found for binary compilation at [1:1]-[4:2]\u001b[0m\n\n\u001b[36m   1 |\u001b[0m \n\u001b[36m   2 |\u001b[0m \u001b[31m\u001b[1mfn this_is_not_main() =\u001b[0m\n       \u001b[32m\u001b[1m^^^^^^^^^^^^^^^^^^^^^^^\u001b[0m\n\u001b[36m   3 |\u001b[0m \u001b[31m\u001b[1m  println \"nopes\"\u001b[0m\n       \u001b[32m\u001b[1m^^^^^^^^^^^^^^^^^\u001b[0m\n\u001b[36m   4 |\u001b[0m \u001b[31m\u001b[1m;\u001b[0m\n       \u001b[32m\u001b[1m^\u001b[0m\u001b[0m\n\nContent-Length: 66"}
[Error - 9:08:15 PM] Client MinnieML Language Server: connection to server is erroring.
Header must provide a Content-Length property.
{"\n\u001b[31mcompilation failed":"\u001b[0m\n\n\u001b[33mFile: mml/samples/no-main.mml\n\n\u001b[31mNo entry point 'main' found for binary compilation at [1:1]-[4:2]\u001b[0m\n\n\u001b[36m   1 |\u001b[0m \n\u001b[36m   2 |\u001b[0m \u001b[31m\u001b[1mfn this_is_not_main() =\u001b[0m\n       \u001b[32m\u001b[1m^^^^^^^^^^^^^^^^^^^^^^^\u001b[0m\n\u001b[36m   3 |\u001b[0m \u001b[31m\u001b[1m  println \"nopes\"\u001b[0m\n       \u001b[32m\u001b[1m^^^^^^^^^^^^^^^^^\u001b[0m\n\u001b[36m   4 |\u001b[0m \u001b[31m\u001b[1m;\u001b[0m\n       \u001b[32m\u001b[1m^\u001b[0m\u001b[0m\n\nContent-Length: 66"}
```

1. it should not use just the error printer.
2. it should return useful information the editor can display

---

## Recent Changes

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

