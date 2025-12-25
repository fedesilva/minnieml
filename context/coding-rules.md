# MML Tech Context

### Scala Language
- **Version**: Scala 3 with strict `-new-syntax` and significant indentation (no braces)
- **Max line length**: 100 characters
- **Control flow**: Use `if-then-else` syntax, not `if ()`
- **Functions**: Use top-level functions when possible; avoid unnecessary objects
- **Package objects**: Do not use deprecated package object syntax

### Testing
- **Framework**: Use `munit.CatsEffectSuite` with `BaseEffFunSuite` helpers
- **Test methods**: Use `*Failed` or `*notFailed` helper methods defined in `BaseEffFunSuite`
- **Test organization**: Follow existing patterns in the test suite
- **Always use existing tools** defined in the `BaseEffFunSuite` base class.
- **Read the docs*** this is how you filter tests with sbt and munit: `https://scalameta.org/munit/docs/filtering.html`

### Comments and Documentation
- **Purpose**: Comment complex logic, use doc-comments for public API
- **Content**: Comments should ONLY describe functionality, not implementation mechanics
- **Avoid**: Do not leave comments like "removed this, fixed that" 

### Code Quality
- **Formatting**: Follow `.scalafmt.conf` settings; run `sbt scalafmtAll` before finishing
- **Linting**: Run `sbt "scalafixAll"` and manually fix all issues that scalafix can't fix, see next.
- **Warnings**: Do not tolerate compiler warnings; fix them all.
- **Exhaustivity**: Fix exhaustivity errors; the compiler knows better than you.

### Running the Compiler
- **Compile and run**: `sbt "run run <file>"` compiles AND runs the program in one step.
- **Compile only**: `sbt "run bin <file>"` compiles to binary without running.
- **After publishing**: `mmlc run <file>` or `mmlc bin <file>` from anywhere.

### Post-Task
- **Validate**: Run the *full* test suite.
- **Formatting**: Run `sbt scalafmtAll` before finishing.
- **Linting**: Run `sbt scalafixAll` and fix any issues.
- **Publish locally**: Run `sbt mmlcPublishLocal` to publish the compiler to its latest version.
- **Tip**: Chain commands to avoid sbt startup overhead: `sbt "scalafmtAll; scalafixAll; mmlcPublishLocal"`

## Git usage

- **Read Only** Freely use git to read history or fetching previous versions. No approval needed.
- **Commiting** Never commit without explicit approval. Ever. 
- **Never revert changes** without explicit approval.
- **Outside changes** If changes appear that you did not make, it was probably the Author, so ask before reverting.

## General rules and recommendations

- Always read relevant code and documentation before starting a new task or answering questions.

