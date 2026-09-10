# MML Tech Context

## General Rules

### Scala Language
- **Version**: Scala 3 with strict `-new-syntax` and significant indentation (no braces)
- **Max line length**: 100 characters
- **Control flow**: Use `if-then-else` syntax, not `if ()`
- **Functions**: Use top-level functions when possible; avoid unnecessary objects
- **Package objects**: Do not use deprecated package object syntax

### Reading code

* No approval needed to read code/docs, research, or prepare a plan.

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
- **QA Rules**: Follow the guidelines in `context/qa-rules-and-coding-style.md`, no excuses.
  - do not handoff code for review BEFORE you go through them, and enforce them.

### Do not use sbt, use sbtn

  * sbtn has a faster startup time, since it keeps a hot instance in the background.
  * non negotiable
  * do not run multiple `sbtn` commands in parallel. The thin client/server socket is
    single-session in practice, and concurrent invocations collide or fail with connection
    errors.
  * if multiple sbt tasks are needed, batch them into one `sbtn "clean;compile;test"` command
    or run separate `sbtn` commands sequentially.
  * if sbtn gets stuck `sbtn shutdown`

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
    - **don't ask to do your job, just do your job**

- **Scope**: These steps apply only when code changes were made *to the compiler*.
  - not for context changes
  - not for documentation changes
  - not for mml samples.

- Enforce QA rules and style guidelines.
  - Non negotiable.

- **Fast sanity check first (mandatory)**:
  - Before publishing the compiler or running expensive verification (benchmarks, full memory harness),
    compile and run the following programs with `sbtn`:
    - run all `sbtn` commands sequentially or batch tasks into one invocation.
      Concurrent thin-client sessions collide, including after the compiler is warm.
    - `sbtn "run run mml/samples/hola.mml"`
    - `sbtn "run run mml/samples/quicksort.mml"`
    - `sbtn "run run mml/samples/astar2.mml"`
    - `sbtn "run run mml/samples/partial-fac1.mml"`
    - `sbtn "run mml/samples/style-guide.mml"`
    - `sbtn "run mml/samples/lambda-factorial.mml"`
    - `sbtn "run mml/samples/raytracer3_p6.mml"`

- **Validate**: Run the *full* test suite

- **Publish the compiler** with `sbtn mmlcPublishLocal` before running benchmarks or the test
    harness, since both use `mmlc`.

- **Run benchmarks**:
  - after publishing the compiler:
  - run `make -C benchmark clean`
  - run `make -C benchmark mml`

- **Run memory tests** (when changes touch memory management / ownership / lambdas):
  - `./tests/mem/run.sh all`
  - All tests must pass ASan+LSan checks

- **If a command session stalls during post-task verification**:
  - Kill the stalled session/process and rerun the same verification command once.
  - If the retry also fails or stalls, explicitly report it as a verification failure and ask the Author
    to run it locally.
  - Prefer killing and retrying over waiting indefinitely on a stuck shell interaction.

- Do a QA enforcement pass before handing over the task.
  Use local `post-chores`, which routes to `qa-enforcer` and the independent `code-review`.

## Workstream handoff

Use `.skills/post-chores/SKILL.md` to derive the applicable finish checklist.
The compiler gates above remain mandatory for compiler changes. Context-only and
documentation-only work needs focused consistency, link, and diff checks rather than
compiler builds, publishing, benchmarks, or memory runs. MML samples need verification
appropriate to their changed behavior; they do not trigger the full compiler checklist.
Review tracking documents with local `tracking-doc-review` in the current agent.
Review code, tooling, workflow rules, and affected technical documents with local
`code-review`; keep its fresh primary review and independent per-claim verification.
Do not claim a required check passed if it failed, was ignored, or could not run.

## Git usage

- **Read Only** Freely use git to read history or fetching previous versions. No approval needed.
- **Commits** are authorized by an explicit commit request or by `finish task`,
  `complete task`, or `finish errand`. Follow `context/task-tracking-rules.md`.
  Push only when requested; finishing does not authorize remote publication.
- Use Git Town for the branch lifecycle: `git town hack <name>` creates a feature branch,
  `git town sync` syncs/publishes, and `git town ship` merges. Do not use raw branch-creation
  or merge commands instead. Verify the intended parent before changing branch configuration.
- For authorized shipping in agent sessions, use
  `git town ship --non-interactive --message "<ship commit message>"` to avoid an editor wait.
  `--message-file <path>` is also available for a prepared message.
- Branch creation can also sync or publish with this repo's configuration. Check the intended
  parent and remote effects before running it. Task approval alone does not authorize a push,
  merge, or publication. Use the installed command's help to select appropriate options.
- **Never revert changes** without explicit approval.
- **Outside changes** If changes appear that you did not make, it was probably the Author, so ask before reverting.

## General rules and recommendations

- Always read relevant code and documentation before starting a new task or answering questions.
- Prefer running the full test suite, since it runs reasonably fast.
  - Use targeted testing only when working on a specific test
