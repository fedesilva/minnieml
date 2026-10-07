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
- **Spacing**: Use deliberate blank lines at block boundaries and between logical steps;
  follow [Vertical Spacing](qa-rules-and-coding-style.md#10-vertical-spacing).
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
    run `./tests/smoke/run.sh all` from the repository root.
  - The harness uses `sbtn` sequentially on the copies in `tests/smoke/`.
    It compiles and runs `hola`, `quicksort`, `astar2`, `partial-fac1`, and `nested-tco`;
    `style-guide`, `lambda-factorial`, and `raytracer3_p6` are compile-only checks.
    Do not run another `sbtn` command concurrently with the harness.

- **Validate**: Run the *full* test suite

- **ABI changes require Linux container verification**:
  - Run the affected ABI and C interoperability tests in both `mml-linux-arm64` and
    `mml-linux-amd64`, in addition to host verification. Execute fixtures inside each
    container; cross-compilation alone does not satisfy this gate.
  - Use the existing compiler APIs and toolchain command runner for fixtures. Do not add
    separate subprocess machinery for Clang.
  - Run the container checks sequentially, with no concurrent host or container `sbtn`
    commands. See [Linux builder instructions](../packaging/docker/Readme.md) for setup.
  - Record each container's architecture, toolchain, commands, and results. Distinguish
    native execution, emulated execution, and cross-compilation. Report an unavailable or
    failing container check as an unmet gate; do not silently omit it or claim it passed.

- **Publish the compiler** with `sbtn mmlcPublishLocal` before running benchmarks or the memory
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

Use `.skills/post-chores/SKILL.md` when finishing a tracked task or handing off compiler
changes, including compiler errands. Non-compiler errands, simple asks, and intermediate
artifacts do not trigger post-chores; perform only checks relevant to the change.
The compiler gates above remain mandatory for compiler changes. Context-only and
documentation-only work needs focused consistency, link, and diff checks rather than
compiler builds, publishing, benchmarks, or memory runs. MML samples need verification
appropriate to their changed behavior; they do not trigger the full compiler checklist.
Review tracking documents with local `tracking-doc-review` in the current agent.
When post-chores applies or the Author requests review, review code, tooling, workflow rules,
and affected technical documents with local `code-review`, following its independence rules.
Do not claim a required check passed if it failed, was ignored, or could not run.

### Required code tour

For a code handoff, include a code tour before requesting approval. Cover source and test
code only. Its purpose is to explain the changes so the Author can understand and assess
them without reconstructing the explanation from the linked files.

- Do not include documentation, task files, working memory, changelogs, signoff records,
  instructions, or commit bookkeeping in the code tour.
- Do not repeat a code tour already delivered for the same changes. A subsequent
  finish or commit request needs only a concise report of completion, the commit hash,
  verification status, working-tree status, push status, and any remaining work.
- If code changes follow the tour, explain only those new code changes.
- Non-code changes need a brief description of the result, not a file-by-file tour or
  copyable path blocks, unless the Author explicitly requests a walkthrough.

When a tour is required:

- Start with the problem being addressed and what the changes accomplish.
- Explain the old behavior, the new behavior, why the change is needed, and how the code
  produces the new behavior. Describe how the changed pieces work together and what the
  tests establish. Include relevant tradeoffs or limitations.
- Build the explanation in a logical order, with each part supplying context for the next.
  Give a matching code reading order, but do not substitute "look here, then here" for
  explaining the changes.
- Link to the relevant files with current line numbers as evidence for the explanation.
  Cover code changes, including moves and deletions; a file list or diff summary alone
  is not a tour.
- Alongside each clickable link, provide the repository-relative `path:line` in a fenced
  plain-text code block, with only the reference inside. It must copy directly without
  list numbers, bold markers, link markup, or explanatory text that the Author must remove.
- Write in plain language. Define technical terms, abbreviations, and referenced components
  within the tour before relying on them. Do not assume knowledge of the implementation,
  prior conversations, or unexplained references. Links supplement the explanation; they
  do not replace it.

When a tour is required, approval is denied if it is missing, too jargon-heavy, assumes
unstated knowledge, or introduces references that it does not explain within the tour itself.

## Git usage

- **Read Only** Freely use git to read history or fetching previous versions. No approval needed.
- **Commits** are authorized by an explicit commit request or by `finish task`,
  `complete task`, `finish subtask`, `complete subtask`, or `finish errand`. Follow
  `context/task-tracking-rules.md`.
  Push only when requested; finishing does not authorize remote publication.
- Use Git Town for the branch lifecycle: `git town hack <name>` creates a feature branch
  and `git town sync` syncs/publishes. Do not use raw branch-creation or merge commands
  instead. Verify the intended parent before changing branch configuration.
- Prefer `git town combine --non-interactive` when merging stacked feature branches.
  Preserve the existing commits unless the Author explicitly asks to squash into the parent.
- Use `git town ship` for explicitly authorized delivery to `main`, or with `--to-parent`
  when the Author explicitly requests a squash into a feature parent. Finishing a task
  does not authorize either operation.
- For authorized `ship` operations in agent sessions, use
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
