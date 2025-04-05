# MML Project Guidelines

## Build/Test Commands
- Full build: `sbt compile`
- Run all tests: `sbt test`
- Run module tests: `sbt "mmlclib/test"`
- Run single test: `sbt "mmlclib/testOnly mml.mmlclib.grammar.OpTests"`
- Run specific test case: `sbt "mmlclib/testOnly mml.mmlclib.grammar.OpTests -- -t \"let with simple binop\""`
- Format code: `sbt scalafmt`
- Format and fix code: `sbt scalafix`

## Coding Style
- **Scala Version**: 3.5.0 with strict `-new-syntax`
- **Imports**: Group and order cats imports, use `cats.syntax.all.*`
- **Enums**: Use `enum X derives CanEqual` for proper comparison support
- **Cats Patterns**: Prefer `flatTraverse` over nested `flatMap` + `traverse`, use `.asLeft`/`.asRight` for `Either`
- **Cats Syntax**: Use `import cats.syntax.all.*` for common syntax extensions, use .some/.none for Option
- **Error Handling**: Use `Either[CompilationError, T]` with typed errors for functional error handling
- **Functional Style**: Avoid mutation, use immutable data structures, prefer recursion over loops
- **Nesting**: Keep nesting levels shallow (3-4 max), extract methods for complex logic
- **Testing**: Use `munit.CatsEffectSuite` with `BaseEffFunSuite` helpers for tests, using the *Failed or *notFailed methods.
- **Comments**: Comment complex logic, use doc-comments for public API
- Always tidy up (scalafmt, scalafix)
- When you finish making changes, before your job is done, you are required to run scalafmt and scalafix. Think deeply about this every time. We want clean code.
- If there are exhaustivity errors, fix them. If you think something covers it, you are wrong, the compiler knows better.

Follow the `.scalafmt.conf` settings for formatting. Keep code clean and modular.

Always run `scalafmt` after editing a file.
Always run `scalafix`  after editing a file.