# MML Debugger Support: LLDB + VS Code DAP

## Goal

Enable source-level debugging for MML programs using:

- LLDB in terminal
- VS Code Debug Adapter Protocol (DAP) frontends (CodeLLDB / C++ adapters)

Target outcomes:

- Breakpoints on `.mml` lines bind reliably
- Step over/into/out follows MML source locations
- Backtraces show user-level function names
- Local variable inspection works for at least function params (phase 2)

## Short answers

- Do we need DWARF metadata?
  - Yes, for real source-level debugging. At minimum we need LLVM debug metadata that lowers to DWARF line tables.
- Do we need to update our VS Code plugin?
  - Not strictly required to debug (users can configure CodeLLDB manually).
  - Recommended for good UX (one command to build debug binary + launch debugger).

## Current state

- Compiler tracks source spans in AST (`SrcSpan`) and module `sourcePath`.
- IR emission currently sets module header/triple but not debug metadata.
- LLVM toolchain pipeline currently does not pass `-g` or debug-oriented flags.
- VS Code extension is LSP + compile commands only, no debugger integration.

## Design principles

- Keep release performance unchanged by default.
- Make debug behavior explicit and opt-in.
- Build in incremental phases so breakpoints/stepping arrive first, then richer variable info.
- Keep generated/synthesized compiler nodes debuggable but visually low-priority.

## Proposed compiler interface

Introduce build-profile and debug-symbol controls in compiler config/CLI:

- `--dev` and `--release` as profile flags
- `-D` / `--debug` to enable debug symbols
- `--no-debug` to explicitly disable debug symbols
- `--opt N` remains available and overrides profile optimization defaults

Defaults:

- `--dev` defaults to `--opt 0` and `--debug`
- `--release` defaults to `--opt 3` and `--no-debug`
- TCO remains enabled by language design in all profiles

Precedence:

1. Profile (`--dev`/`--release`) sets baseline defaults.
2. Explicit `--opt N` overrides profile optimization.
3. Explicit `-D`/`--debug`/`--no-debug` overrides profile debug default.

Flag compatibility notes:

- `-D` is reserved for debug (uppercase).
- Existing `info -d/--diagnostics` remains unchanged (lowercase `-d`).
- Existing `dev` subcommand is kept; this is intentionally separate from profile flags.

## IR-level metadata plan

### Phase 1: line tables (MVP)

Emit enough LLVM DI metadata for source mapping:

- `!llvm.dbg.cu`
- `DICompileUnit`
- `DIFile` (path from module source path)
- `DISubprogram` for each emitted function
- `DILocation` on emitted instructions

Behavior target:

- breakpoints and stepping work on `.mml` source
- call stack resolves to MML function locations

### Phase 2: variable visibility

Emit variable metadata:

- `DILocalVariable` for params and selected locals
- `dbg.declare` / `dbg.value`

Behavior target:

- inspect function params and key locals in LLDB / DAP variables pane

### Phase 3: synthesized nodes policy

Define location policy for compiler-generated symbols:

- constructors/destructors/clone/free helpers
- synthesized wrappers from lowering phases

Recommended policy:

- preserve stepping continuity but avoid noisy stop points
- optionally mark helper functions with internal linkage and minimized stepping priority

## Toolchain updates

When debug symbols are enabled:

- Ensure object/executable generation preserves debug info end-to-end
- Add debug flags at final `clang` steps
- Keep compatibility with current cross-target flow (`target triple`, optional `target cpu`)

Potential quality knobs:

- split DWARF (later)
- deterministic paths (`-fdebug-prefix-map`) for reproducible CI artifacts (later)

## VS Code integration options

### Option A: no plugin changes (fastest)

Document a manual `launch.json` using CodeLLDB:

- preLaunchTask: compile with `--dev -D` (or `--release -D`)
- launch built binary from `build/target/...`

Pros: minimal engineering
Cons: weaker UX, repetitive project setup

### Option B: plugin-assisted debug UX (recommended)

Extend `tooling/vscode` with:

- `mml.debugCurrentFile` command
- debug configuration provider for `mml` convenience configs
- optional task provider that invokes `mmlc build --dev -D ...`

Note: We still rely on existing debugger adapters (CodeLLDB/cpptools). We do not need to implement a full custom debug adapter initially.

## Execution plan

1. Add config + CLI plumbing for profile + debug flags (`--dev`/`--release`, `-D`/`--debug`).
2. Implement phase-1 DI emission and wire instruction locations.
3. Add an end-to-end debugger smoke test script (compile, breakpoint hit assertion via lldb batch script).
4. Document manual VS Code `launch.json` flow.
5. Add optional plugin command (`debugCurrentFile`) for one-click experience.
6. Implement phase-2 variable metadata.

## Risks

- Optimizations can collapse source locations and make stepping surprising.
- Inlined/synthesized functions can clutter call stacks.
- Cross-platform LLDB behavior differs (macOS/Linux), needs matrix validation.

## Acceptance criteria (phase 1)

- Breakpoint at a known `.mml` line resolves and stops in LLDB.
- Step-over moves along expected `.mml` lines for a sample program.
- Backtrace includes user function names and source files.
- Existing non-debug build output remains unchanged in default `--release --no-debug` mode.

## Open questions

- Should `mmlc run` in dev mode implicitly use `--debuginfo line`?
- Do we want a dedicated `mmlc debug` command alias?
- How should we map synthesized ownership/lowering nodes to source lines to avoid stepping noise?
- Should plugin changes be in same workstream as compiler DI, or follow-up once MVP is stable?
