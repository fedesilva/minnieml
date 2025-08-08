# AGENTS.md - MinnieML Development Guide

## Build/Test Commands
- **Build**: `sbt compile` 
- **Test all**: `sbt test`
- **Single test**: `sbt "testOnly mml.mmlclib.grammar.FnTests"`
- **Format**: `sbt scalafmtAll` 
- **Lint**: `sbt "scalafixAll"`
- **Run compiler**: `sbt "run bin mml/samples/simple_string.mml"`
- **Use metals MCP for fast compilation/testing** (seconds vs 40+ seconds 
for sbt)
- **Metals runs one test at a time** run `sbt test` to run the full suite.

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

## Critical Rules
- **ALWAYS read memory-bank files first** - they contain essential context
- **Present detailed plans** before making changes, ask for approval
- **Fix ALL compiler warnings** and exhaustivity errors
- **Never add emoji or Claude references** to commits
- **Use metals MCP** for compiler feedback, type inspection, find usages