# MML Tech Context

## Cardinal Rule

### Keep the author involved 

**Before starting a CODE changing task:**
* Present a plan
    - Don't ask confirmation to plan or research.
* Stand by for review
* No further action until approval is granted. No exceptions.

### Design Reference

- Read before designing 
    - `docs/design/` directory - All files.

## General Rules

### Scala Language
- **Version**: Scala 3 with strict `-new-syntax` and significant indentation (no braces)
- **Max line length**: 100 characters
- **Control flow**: Use `if-then-else` syntax, not `if ()`
- **Functions**: Use top-level functions when possible; avoid unnecessary objects
- **Package objects**: Do not use deprecated package object syntax

### Reading code

* No approval needed to make plans and read code.

### Testing
- **Framework**: Use `munit.CatsEffectSuite` with `BaseEffFunSuite` helpers
- **Test methods**: Use `*Failed` or `*notFailed` helper methods defined in `BaseEffFunSuite`
- **Test organization**: Follow existing patterns in the test suite
- **Always use existing tools** defined in the `BaseEffFunSuite` base class.


### Comments and Documentation
- **Purpose**: Comment complex logic, use doc-comments for public API
- **Content**: Comments should ONLY describe functionality, not implementation mechanics
- **Avoid**: Do not leave comments like "removed this, fixed that" 

### Code Quality
- **Formatting**: Follow `.scalafmt.conf` settings; run `sbtn scalafmtAll` before finishing
- **Linting**: Run `sbtn "scalafixAll"` and manually fix all issues that scalafix can't fix, see next.
- **Warnings**: Do not tolerate compiler warnings; fix them all.
- **Exhaustivity**: Fix exhaustivity errors; the compiler knows better than you.

### Running the Compiler

- **Prefer running via sbtn while developing**
- **Compile and run**: `sbtn "run run <file>"` compiles AND runs the program in one step.
- **Compile only**: `sbtn "run <file>"` (or `mmlc <file>` after publishing).
- **Never deploy** the compiler (mmlcPublishLocal) without testing it works via sbtn.

### Publishing the Compiler Artifact

The compiler needs to be installed before it's used if changes were made.

- **Publish fat jar**: `sbtn mmlcPublishLocal`
- **After publishing**: `mmlc run <file>` or `mmlc <file>` from anywhere.

### Before finish - Post Task Chores

**Critical**: 
  - Go through all these steps
  - Do not wait for confirmation, or ask to run this, do it or the task is not done.

- **Scope**: These steps apply only when code changes were made (not for context-only updates).
- **Validate**: Run the *full* test suite.
- **IMPORTANT**: Chain commands to avoid sbtn startup overhead: `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"`

- **Run benchmarks**:
  - after publishing the compiler:
  - run `make -C benchmark clean`
  - run `make -C benchmark mml`

- **Run memory tests** (when changes touch memory management / ownership):
  - `./tests/mem/run.sh all`
  - All 13 tests must pass both ASan and leaks checks
  - The script handles `mmlc clean` between modes automatically

- **If a command session stalls during post-task verification**:
  - Kill the stalled session/process and rerun the same verification command once.
  - If the retry also fails or stalls, explicitly report it as a verification failure and ask the Author
    to run it locally.
  - Prefer killing and retrying over waiting indefinitely on a stuck shell interaction.

## Git usage

- **Read Only** Freely use git to read history or fetching previous versions. No approval needed.
- **Commiting** Never commit without explicit approval. Ever. 
- **Never revert changes** without explicit approval.
- **Outside changes** If changes appear that you did not make, it was probably the Author, so ask before reverting.

## General rules and recommendations

- Always read relevant code and documentation before starting a new task or answering questions.
