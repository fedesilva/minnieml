# Lambda restart: test preservation map

The noncompiler transfer is complete on `dev-lambdas-migration`. All source-tip test declarations,
helpers, memory programs, samples, documentation, and independent benchmark/tooling changes are
present. The compiler implementation still matches parent `c7e9078`.

The current suite passes **430 tests**, with **62 ignored** and no failures or errors. Ignored tests
are unfinished compiler work, not passing coverage. Every ignore has its reason in a comment
immediately above the test. One preexisting nested TBAA declaration remains undiscovered.

## Resume from this checkpoint

The pushed documentation/helper checkpoint is `35c87bd9cb89da4f48b8fe2fa2cbae70129e3beb`
(`Start lambda migration with docs and test helpers`). This transfer checkpoint is titled
`Preserve lambda regressions and remaining source files`. Verify its commit/push state when resuming:

```sh
git status --short --branch
git log -3 --oneline
git rev-parse HEAD
git ls-remote --heads origin dev-lambdas-migration dev-lambdas-unify
git cat-file -t c16231a8753d214617a88a089341033fefe803d7
```

The Author waived confirmation requirements while moving material from the source branch. The
Author also authorized ignored tests and required a reason comment beside each one. **Normal
confirmation rules apply again before compiler changes.** No compiler implementation workstream
is approved by the transfer. Tracked-item statuses remain the preserved source history.

Read `AGENTS.md`, this document, and [unify-lambdas-salvage.md](unify-lambdas-salvage.md). Before
proposing compiler work, read `docs/memory-model.md`, `context/specs/pap-ownership-model.md`, and
the relevant compiler design/rules. Keep the source branch and immutable provenance recoverable.
No compiler was published; an installed `mmlc` must not be assumed to represent this branch.

To resume, the Author can use:

> Read AGENTS.md, context/lambda-test-migration.md, and context/unify-lambdas-salvage.md. Verify the
> migration branch and current tests. The noncompiler transfer is complete; ignored regressions
> have nearby reasons. Discuss a bounded compiler workstream before editing the compiler. Preserve
> the ownership contract and explain language behavior using MML examples.

## References and transferred scope

- Parent: `c7e90780897d202d857292b029baedc5b5f9b7e3`.
- Test/program source: `c16231a8753d214617a88a089341033fefe803d7`.
- Documentation source: `8e83506`, retained on local `dev-lambdas-unify`.
- Complete inventory: [lambda-test-migration.json](lambda-test-migration.json).

The complete `context/` and `docs/` trees and root instruction changes were transferred at the
first checkpoint, including additions, edits, renames, and deletions. Migration handoff updates
and these inventory files are intentional additions on the destination branch.

The subsequent transfer includes 67 changed sample entries, 12 added memory programs, all
remaining Scala test changes, `benchmark/Makefile`, both Rust matrix benchmarks, and
`tooling/vscode-llvm-ir/package-lock.json`. All 83 sample/memory/benchmark/tooling files match the
source bytes exactly. None of the source branch's 28 changed compiler implementation files were
transferred.

Three shared test helpers moved from `test/extractors/` to `test/ast/`:

| Parent file | Destination file |
| --- | --- |
| `TXAstExtractors.scala` | `AstExtractors.scala` |
| `TXLambdaHelpers.scala` | `LambdaTestQueries.scala` |
| `TXTermTraversal.scala` | `AstTraversal.scala` |

`AstTraversal` accesses the lambda body through typed `Lambda` matches instead of matching all
eight constructor fields. Its traversal behavior is unchanged. The other helpers match the source
blobs exactly. Existing caller imports and the four traversal helper names were updated.

## Test preservation and ignores

| Measure | Count |
| --- | ---: |
| Parent declarations retained | 411 |
| Source-tip declarations transferred | 477 |
| Current declarations, including retained parent variants | 493 |
| Passing tests | 430 |
| Ignored tests | 62 |
| Undiscovered nested declarations | 1 |
| Original file/name pairs across source history | 486 |
| Distinct declaration versions across source history | 507 |
| Corpus paths / distinct path-and-blob versions | 266 / 367 |

The 16 parent cases replaced or changed in source are retained alongside their successors. Eleven
same-name cases have a `[parent c7e9078]` suffix; five keep their original names. All 410 parent
cases that the runner discovered before migration still run and pass. Parent assertions remain
unchanged after helper-name normalization and test-header formatting.

The first transferred suite failed to compile with 14 errors in five suites. Eighteen tests
require absent materialization metadata, PAP/parser diagnostics, or the type-name comparison API.
Five test bodies are preserved inside comments next to explicit failing placeholders. Three
helper bodies likewise preserve their source assertions in comments and fail explicitly. Restore
those bodies against the agreed compiler API before enabling their dependent tests. Removing an
ignore alone cannot make these placeholders pass.

With those API-dependent tests ignored, the suite ran 430 passing and 44 failing tests. Those 44
source regressions are now ignored, with the observed gap explained directly above each test.
Their programs and assertions remain executable and unchanged. The inventory records each reason
and a short failure excerpt. The final suite has 430 passing and 62 ignored tests.

The pending cases include borrowed returns through aliases, PAP argument moves and escapes,
struct ownership and destruction, heap type aliases, Direct call shapes, capture cleanup, and
named closure entries. Some code-generation assertions pin the source implementation's exact IR
shape. Their preservation does not authorize reproducing that architecture. Parent and source
representation expectations must be reconciled deliberately while preserving their semantic and
ownership coverage.

For example, these aliases must not manufacture ownership:

```mml
fn echo(s: String): String =
  let x = s;
  let y = x;
  y;
;
```

The parent accepts this borrowed return. Its source rejection regression is retained and ignored
with that reason. The shadowing and static-return companion regressions run and pass.

`TbaaEmissionTest.scala` still declares `loads and stores include alias scope metadata` inside
`TBAA field offsets honor alignment (String has ptr at offset 8, not 4)`. This preexisting nested
registration is recorded as `not-discovered`, not passing or ignored.

## Reading the inventory

Schema version 2 keeps every immutable source/history reference from the first checkpoint.
`scala_cases` records original file/name identities, source and parent declarations, their current
`destination`, and a separate `parent_destination` where applicable. `pending` records ignore
reasons and whether an unsupported body is commented. Execution is recorded independently from
preservation status.

| Case status | Meaning |
| --- | --- |
| `source-case-identical` | Current declaration text matches the source tip. |
| `source-case-format-adapted` | Only formatting or an adjacent migration comment differs. |
| `source-case-ignored` | Source program/assertions preserved; the test is explicitly pending. |
| `historical-name-present` | Superseded source name remains in the parent suite. |
| `historical-name-absent` | Intermediate name is retained through source history and successor mapping. |

`history` stores commit, blob, inclusive line range, declaration SHA-256, ignored state, and any
enclosing test. Declaration hashes cover text with trailing whitespace removed. File blob hashes
cover complete files. Recover a complete original with `git cat-file blob <blob>` or
`git show <commit>:<path>`.

`corpus_files` records all file versions and current hashes. `independent_files` records the four
benchmark/tooling paths outside the test/sample corpus. `verification` contains current JUnit
report hashes and outcomes; `previous_checkpoint_verification` retains the 410-test baseline.
Reports are local build outputs and may be absent in a new checkout. Rerun the suite there.
`destination_head` identifies HEAD when the working-tree snapshot was recorded.

Nine historical names have explicit successor mappings. Five remain active as parent tests; four
intermediate-only names remain recoverable in immutable source blobs. Three of the latter assert
rejected clone-per-PAP behavior. Their successors are preserved, but the rejected cloning contract
is not restored. The other intermediate replacement changes sibling-capture representation.

## Verification and limits

```sh
sbtn 'scalafmtAll;scalafixAll;test'
```

Formatting and linting pass without warnings. Final result: 430 passed, 62 ignored, zero failures
or errors. Checks verify every source declaration's preserved body, every parent declaration's
retained destination, all corpus file hashes, and unchanged compiler implementation.

Both Rust matrix benchmarks build and run with checksum `381460`. The transferred lockfile's
root dependencies match `package.json`; its source updates esbuild to `0.25.12`, Node types to
`20.19.41`, and VS Code types to `1.120.0`. Dependencies were not installed or refreshed.

All 79 transferred MML files were checked for IR generation using this freshly built compiler.
The CLI classpath came from `sbtn 'export mmlc / Compile / fullClasspath'`; each file was then
checked with `java -cp <classpath> mml.mmlc.Main ir -b <temporary-directory> <file>`. Results and
diagnostics are recorded in each corpus entry's `ir_check` and summarized in `program_verification`.
There were 67 generated-IR results and 12 rejections, including intentional negative examples.

Of the 12 memory programs, 11 generate IR. `tests/mem/escaping-paps.mml` and sample
`partial-fac2-escape.mml` fail codegen because the parent has no LLVM mapping for `TypeGroup` in
the grouped function-return type. `lambda-forms/direct-inline-lambda.mml` fails parsing.
`borrow-escape-test.mml` is rejected for its untyped hole, so it does not validate borrow escape.
The negative `lambda-forms/struct-field-borrow-fail.mml` sample generates IR: the parent wrongly
accepts that borrowed closure in an owning field, matching the ignored semantic regression.

IR generation does not establish runtime correctness or memory safety. No MML program was run
under sanitizers during this transfer. The memory harness cannot be called passing while
`escaping-paps.mml` fails to compile. Full ASan/LSan checks and MML benchmarks remain required
when implementing the relevant compiler changes.

## Next compiler workstream

Discuss and approve the next bounded slice before changing compiler code. The salvage review's
candidate is complete executable destructor ASTs; the preserved ownership regressions provide
additional concrete entry points. Establish the invariant and MML examples first, then identify
which ignored tests should become enabled for that slice. Do not silently clone values to repair
ownership, weaken assertions to obtain a green suite, or import the failed compiler wholesale.

Reconcile stale design-document claims and historical tracked-item statuses through their own
reviewed work. Neither this transfer nor the green suite with ignores completes lambda unification.
