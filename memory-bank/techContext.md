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

## Build/Test Commands

- Full build: `sbt compile`
- Run all tests: `sbt test`
- Run tests: `sbt test`
- Run single test: `sbt testOnly mml.mmlclib.grammar.OpTests`
- Run specific test case: `sbt "testOnly mml.mmlclib.grammar.OpTests -- -t \"let with simple binop\"`
- Format code: `sbt scalafmt`
- Format and fix code: `sbt scalafix`
- Test code changes without rebuilding native image: `sbt "run <mmlc args>"` (e.g., `sbt "run lib mml/samples/some_file.mml"`)

## Distribution and Packaging

The project includes comprehensive packaging capabilities to create distributable versions of the MinnieML compiler:

- `sbt mmlcPublishLocal`: Builds a native image and installs it to `~/bin` for local use

  - Generates the platform-specific native binary
  - Copies the shell script wrapper (`packaging/mmlc`) to `~/bin` alongside the binary
  - The script provides a stable command name while being able to locate the appropriate native binary

- `sbt mmlcDistroAssembly`: Creates a distribution package directory containing:

  - The compiled native binary with OS-arch naming
  - The shell script wrapper (`mmlc`)
  - Sample MML programs in a `samples` directory

- `sbt mmlcDistro`: Creates a zip file of the complete distribution package

The distribution package follows a consistent naming convention that includes the OS, architecture, and git SHA for traceability. This allows for reliable deployment across different platforms.

### Cross-Platform Building

For building on Linux platforms, the project includes Docker-based tooling:

- `packaging/docker/Dockerfile`: Container definition for Linux builds
- `docker-compose.yml`: Configuration for containerized compilation
- Helper scripts in `packaging/docker/`:
  - `linux-builder-distro.sh`: Creates Linux distribution packages
  - `linux-builder-shell.sh`: Provides an interactive shell in the build container

These tools enable cross-platform compilation without requiring a dedicated Linux machine.

## Development Tools

- **VSCode Extension**: The project includes a syntax highlighting extension for Visual Studio Code
  - Located in `tooling/vscode/`
  - Provides syntax highlighting for `.mml` files
  - Installation script available at `tooling/install-vscode-extension.sh`

## Coding Style

- **Scala Version**: 3 with strict `-new-syntax`
- **Imports**: Group and order cats imports, use `cats.syntax.all.*`
- **Enums**: Use `enum X derives CanEqual` for proper comparison support
- **Cats Patterns**: Prefer `flatTraverse` over nested `flatMap` + `traverse`, use `.asLeft`/`.asRight` for `Either`
- **Cats Syntax**: Use `import cats.syntax.all.*` for common syntax extensions, use .some/.none for Option
- **Error Handling**: Use `Either[CompilationError, T]` with typed errors for functional error handling
- **Functional Style**: Avoid mutation, use immutable data structures, prefer recursion over loops
- **Nesting**: Keep nesting levels shallow (3-4 max), extract methods for complex logic
- **Testing**: Use `munit.CatsEffectSuite` with `BaseEffFunSuite` helpers for tests, using the *Failed or *notFailed methods.
- **Comments**: Comment complex logic, use doc-comments for public API

Follow the `.scalafmt.conf` settings for formatting. Keep code clean and modular.

## General rules and recommendations

- When you finish making changes, before your job is done, you are required to run scalafmt and scalafix. Think deeply about this every time. We want clean code.

- If there are exhaustivity errors, fix them, you are wrong, the compiler knows better.

- In scala 3 we dont use if (), we use if-then-else.
- In scala 3 we can and should use top level function, no need to create objects just to contain them, unless we want to avoid clashes.
- In scala 3 we do not use the deprecated package object syntax.
- In scala 3 functions do not need to be forward referenced, if there are no cyclic references which are themselves a bad code smell.

- When running sbt commands DO NOT append the subproject, the top level project is configured to aggregate the children's commands.

- DO NOT tolerate compiler warning. fix them all. if running scalafix
  does not fix them, you need to edit the code and run all the tools again.

- DO NOT ever consider a task finished if you did not follow this instructions (code quality tools, review compiler output)

Do not ever leave comments like "removed this, fixed that."
Comments should only describe the code.
