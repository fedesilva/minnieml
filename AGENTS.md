# AGENTS.md - MinnieML Development Guide

## Build/Test Commands
- **Build**: `sbt compile` 
- **Test all**: `sbt test`
- **Format**: `sbt scalafmtAll` 
- **Lint**: `sbt "scalafixAll"`
- **Run compiler**: `sbt "run bin mml/samples/simple_string.mml"`
- **Use metals MCP for fast compilation/testing** (seconds vs 40+ seconds 
for sbt)
- **Metals runs one test at a time** run `sbt test` to run the full suite.
- **ONLY IF METALS IS NOT WORKING: single test**: `sbt "testOnly mml.mmlclib.grammar.FnTests"`

## Code Style
- **Scala 3** with `-new-syntax`, significant indentation (no braces)
- **Max line length**: 100 characters
- **Control flow**: `if-then-else` syntax, not `if ()`
- **Return types**: Explicit for public members
- **Imports**: Use `import cats.syntax.all.*`, group imports with OrganizeImports
- **Error handling**: `Either[CompilationError, T]` with typed errors
- **Immutability**: Prefer `val` over `var`, use immutable data structures
- **Using clauses**: Only for type-related functionality, no implicit parameters
- **Comments**: Only describe functionality, not implementation mechanics

## Testing
- **Framework**: `munit.CatsEffectSuite` with `BaseEffFunSuite` helpers
- **Test methods**: Use `parseNotFailed`, `semNotFailed`, `parseFailed`, `semFailed`
- **Debug tools**: Use `prettyPrintAst` and `yolo.inspect.rewrite` for failing tests
- **Always run ALL tests** before completing tasks
- **Always check existing errors before adding new** to avoid dupes and to pick up the patterns and in house tools.

## Critical Rules
- **ALWAYS read `context/` files first** - they contain essential information related to the project, current focus, tasks, technical descriptions and conventions.
- **Read `docs/design-and-semantics.md`** - contains a brief intro to the language
- **Present detailed plans** before making changes, ask for approval
- **Follow explicit instructions exactly** - If you suggest an alternative and it's not acknowledged, consider it denied. Present alternatives/questions AFTER the plan, clearly marked as OPTIONAL or QUESTION, not mixed into the plan itself.
- **Fix ALL compiler warnings** and exhaustivity errors
- **Never add emoji or references to yourself or your brand or your vendor ** to commits
- **Use metals MCP** for compiler feedback, type inspection, find usages
- **Read the Parser, AST definitions and the Semantic Phases** Before working on anything, get up to speed with the codebase, so you can make informed desicions.


## Context Management 

**When a task is finished follow these rules**
    - update the active context adding a note on the task acomplished
    - if the task was selected from one in the next steps section, mark that entry as completed or partially completed, etc, as appropriate
**When requested to review and update the context**
    - Compact long recent tasks lists, leave no more than 10
    - Read the code and verify the context in general is up to date,
        - if it is not, make a plan, ask for approval.


## Commands
    
  - when told to `load context` read all the context files as per above instructions and
        pay particular attention to current work specified in the active context document.
    - read any code related to the current task, acknowledge, summarize and wait for instructions.

  - when told to `reload context` read the context
    - even if you already did it, there might be updates.

  - when told to `update context` follow the instructions for this as described in `Context Management` above.

