# MML Tech Context

## Tools

- Scala 3
  - full new syntax - see below.
- FastParse
  - the parser combinator library
- Cats and Cats Effects
  - The external API are IO based.
  - We use EitherT and others
  - always use cats.syntax.all.\*
- Git Town
  - for git workflow management

## Development Workflow

- **Use the `metals` MCP server for compilation and testing.** It is significantly faster than using `sbt` directly. 
    The difference is a couple of seconds using the MCP vs. 40+ seconds for `sbt`.
  * you still will have to use sbt to run scalafix and scalafmt, but before handing over the completed task.

## Build and Test Commands
- Build project: `sbt compile`
- Build native image: `sbt "mmlcDistro"`
- Run from source: `sbt "run --help"`
- Run a single test: `sbt "mmlclib/testOnly mml.mmlclib.grammar.FnTests"`
- Run all tests: `sbt test`
- Format code: `sbt scalafmtAll`
- Run scalafix: `sbt "scalafixAll"`

- Always check that ALL TESTS PASS before completing a task.

## Code Style Guidelines

### Scala Language
- **Version**: Scala 3 with strict `-new-syntax` and significant indentation (no braces)
- **Max line length**: 100 characters
- **Control flow**: Use `if-then-else` syntax, not `if ()`
- **Functions**: Use top-level functions when possible; avoid unnecessary objects
- **Package objects**: Do not use deprecated package object syntax
- **Forward references**: Not needed unless there are cyclic references (which are code smells)

### Types and Enums
- **Return types**: Explicit return types required for public members
- **Enums**: Use `enum X derives CanEqual` for proper comparison support
- **Using clauses**: Avoid except for type-related functionality; no implicit parameters
- **Immutability**: Prefer `val` over `var`

### Imports and Dependencies
- **Import organization**: Use OrganizeImports with grouped imports; avoid unused imports
- **Cats imports**: Always use `import cats.syntax.all.*` and group cats imports


### Functional Programming
- **Error handling**: Use `Either[CompilationError, T]` with typed errors for functional error handling
- **Cats patterns**: Prefer `flatTraverse` over nested `flatMap` + `traverse`
- **Functional style**: Avoid mutation, use immutable data structures, prefer recursion over loops
- **Cats syntax**: Use `.some`/`.none` for Option, `.asLeft`/`.asRight` for Either
- **Effects**: External API should be IO-based with cats-effect

### Code Structure
- **Nesting**: Keep nesting levels shallow (3-4 max); extract methods for complex logic
- **Complexity**: Avoid deeply nested conditionals; refactor complex logic into separate functions
- **Readability**: Keep code simple and understandable for less experienced developers
- **Naming**: Follow existing naming conventions in the codebase

### Testing
- **Framework**: Use `munit.CatsEffectSuite` with `BaseEffFunSuite` helpers
- **Test methods**: Use `*Failed` or `*notFailed` helper methods
- **Test organization**: Follow existing patterns in the test suite
- **Always use existing tools** defined in the `BaseEffFunSuite` base class.
- **Read the docs*** this is how you filter tests with sbt and munit: `https://scalameta.org/munit/docs/filtering.html`

### Comments and Documentation
- **Purpose**: Comment complex logic, use doc-comments for public API
- **Content**: Comments should ONLY describe functionality, not implementation mechanics
- **Avoid**: Do not leave comments like "removed this, fixed that" or inane structural markers

### Code Quality
- **Formatting**: Follow `.scalafmt.conf` settings; run `sbt scalafmtAll` before finishing
- **Linting**: Run `sbt "scalafixAll"` and manually fix all issues that scalafix can't fix, see next.
- **Warnings**: Do not tolerate compiler warnings; fix them all.
- **Exhaustivity**: Fix exhaustivity errors; the compiler knows better than you.

## General rules and recommendations


- In scala 3 we use significant indentation.
- In scala 3 we dont use if (), we use if-then-else.
- In scala 3 we can and should use top level function, 
  no need to create objects just to contain them, 
  unless we want to avoid clashes.
- In scala 3 we do not use the deprecated package object syntax.
- In scala 3 functions do not need to be forward referenced, 
  if there are no cyclic references which are themselves a bad code smell.
- In scala 3, match and cases can be writen using significan indentation.
