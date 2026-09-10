# Lambda restart: test preservation map

The migration branch is `dev-lambdas-migration`, based on `c7e9078`. The shared test helpers and
their seven existing callers are migrated. All 410 tests discovered on the parent still pass.
The remaining source regressions, memory programs, and sample changes are pending migration.

The complete inventory is [lambda-test-migration.json](lambda-test-migration.json). It records
each original test declaration, its destination when present, and recoverable source provenance.
This is a preservation inventory, not a claim that the restart implements the source branch.
Tracked-item statuses in `tracking.md` remain the preserved source history.

## Resume from this checkpoint

The Author signed off the documentation/helper workstream on 2026-09-09 and requested committing
and pushing `dev-lambdas-migration` before further migration. The checkpoint commit subject is
`Start lambda migration with docs and test helpers`; its push destination is
`origin/dev-lambdas-migration`. Establish the actual local and remote state when resuming:

```sh
git status --short --branch
git log -3 --oneline
git rev-parse HEAD
git ls-remote --heads origin dev-lambdas-migration dev-lambdas-unify
git cat-file -t c16231a8753d214617a88a089341033fefe803d7
```

Read `AGENTS.md`, this document, and the salvage handoff before choosing the next workstream.
Read `docs/memory-model.md` and `context/specs/pap-ownership-model.md` before proposing compiler
changes. Preserve intervening edits; the JSON describes this checkpoint, not a live status service.
The full migration is incomplete, and the next workstream still needs discussion and approval.

The completed work consists of the documentation/instruction transfer, the complete test/corpus
inventory, and the shared helper migration described below. Source programs and assertions in
the parent test suite are unchanged. Formatting, linting, and all 410 discovered tests passed.
One nested TBAA declaration is preserved but undiscovered. No compiler was published, so an
installed `mmlc` must not be assumed to represent this branch. Local JUnit reports may be absent
in a new checkout; rerun `sbtn test` to establish a fresh baseline there.

The next proposed workstream is to transfer the 12 added memory programs and 67 changed sample
entries from `c16231a`, preserving exact contents and intentional compilation failures. Read the
MML-specific rules first. Compare destination files before copying and update their inventory
locations and hashes. Establish which programs the parent accepts; transferred programs are not
automatically passing coverage. Keep expected failures out of the memory harness's run-all set.
Review this batch before starting compiler changes to satisfy its regressions.

Then migrate the remaining Scala regressions in small batches, retaining parent tests and the
historical replacements below. Borrowed-return and shadowing regressions are an initial candidate
because they exercise ownership through existing semantic test APIs. Discuss any necessary
compiler changes separately. Representation-dependent tests need an explicit adaptation that
preserves their source programs, diagnostics, and cleanup requirements.

The independent remaining files are `benchmark/Makefile`, `benchmark/matmul.rs`,
`benchmark/matmul-opt.rs`, and `tooling/vscode-llvm-ir/package-lock.json`. Preserve and review them
in their own scope. Reconcile historical documentation claims and tracked-item statuses in
separate reviewed work; this checkpoint does not mark lambda unification complete.

To resume, the Author can use:

> Read AGENTS.md, context/lambda-test-migration.md, and context/unify-lambdas-salvage.md. Continue
> the restart on dev-lambdas-migration from its pushed checkpoint. Verify the current state,
> then discuss the proposed memory/sample transfer. Preserve every source test and its history.
> Read the memory model and PAP ownership spec before compiler changes. Use MML examples.

## References and scope

- Parent: `c7e90780897d202d857292b029baedc5b5f9b7e3`.
- Test source: `c16231a8753d214617a88a089341033fefe803d7`.
- Retained source branch: `dev-lambdas-unify`, currently at `8e83506`.
- Documentation source: `8e83506`, including the handoff, both reviews, and parser design updates.
- Contract and recovery instructions: [unify-lambdas-salvage.md](unify-lambdas-salvage.md).

The inventory covers every Scala file under `modules/**/src/test/`, and every tracked file under
`tests/mem/` and `mml/samples/`, across the parent and all 72 commits through the test source.
Unchanged files are included. Helper renames, deleted paths, intermediate programs, and rewritten
test declarations retain their original blob references.

The complete `context/` and `docs/` source trees were transferred before this workstream. The two
test-migration inventory files are additions on the migration branch. Root instruction changes,
including the `GEMINI.md` deletion, are also present. The JSON records the verified worktree before
this checkpoint; its `destination_head` identifies the parent commit at that time.

## Inventory counts

| Measure | Count |
| --- | ---: |
| Parent test declarations | 411 |
| Source-tip test declarations | 477 |
| Migration-branch test declarations | 411 |
| Distinct file/name pairs across history | 486 |
| Distinct declaration versions across history | 507 |
| Corpus paths across history | 266 |
| Distinct path/blob versions across history | 367 |

Declaration counts include nested test registrations. They are not executed-test counts.
The source-tip declarations divide into 395 identical declaration texts, 71 absent names, and
11 changed declarations. Absent names include source replacements for parent test names; 71 is
not a count of net new behaviors. Matching text does not establish equivalent helper behavior or
compiler semantics. The inventory also records complete file versions for that review.

## Completed helper migration

All helper paths below are relative to `modules/mmlc-lib/src/test/scala/mml/mmlclib/test/`.

| Parent path | Destination |
| --- | --- |
| `extractors/TXAstExtractors.scala` | `ast/AstExtractors.scala` |
| `extractors/TXLambdaHelpers.scala` | `ast/LambdaTestQueries.scala` |
| `extractors/TXTermTraversal.scala` | `ast/AstTraversal.scala` |

The source-tip helper files supply the destination contents. `AstTraversal` uses typed `Lambda`
matches to access `body` instead of matching all eight constructor fields. Its traversal behavior
is unchanged. The other two helpers match their source-tip blobs exactly.

The seven callers are `LambdaLitTests`, `AlphaOpTests`, `AppRewritingTests`, `CaptureAnalyzerTests`,
`OpPrecedenceTests`, `OwnershipAnalyzerTests`, and `TypeCheckerTests`. Their imports use `test.ast`.
Ownership helper calls use `existsTerm` and `countTerms`. No test program or assertion changes in
this workstream. No compiler implementation changes are included.

## Reading the inventory

`scala_cases` contains one record per original file/name pair. `parent` and `source` identify the
declaration at the fixed commits above. `destination` records the current location and execution
result, or is null when that name is absent. `history` records each distinct declaration version
with its first observed commit, complete file blob, inclusive line range, SHA-256, ignored state,
and enclosing test when nested. Declaration hashes cover the original text with trailing
whitespace removed; file blob hashes cover the complete file.

| Case status | Meaning |
| --- | --- |
| `source-case-identical` | Declaration text matches the source tip. Execution is recorded separately. |
| `source-case-helper-adapted` | Only the four traversal helper identifiers differ. Currently no rows. |
| `pending-new` | The source file/name pair has no current destination. |
| `pending-changed` | The destination has that name, but its declaration differs from the source. |
| `historical-name-present` | A name superseded in source history remains in the parent suite. |
| `historical-name-absent` | A historical name is absent from both source tip and destination. |

`corpus_files` records whole-file versions, including shared helpers and all memory/sample files.
File statuses describe byte-level transfer, helper moves, or pending differences. They do not
claim successful execution. `verification` records the test command and hashes of the generated
JUnit reports; those reports are local build outputs, not committed artifacts.

For example, recover a recorded complete file with `git cat-file blob <blob>`, or inspect its
original location with `git show <commit>:<path>`. Preserve these immutable references when
adapting a test. A source-only record is recoverable history, not migrated passing coverage.

## Historical replacements

Every one of the nine names absent from the source tip has a `source_successor` mapping. These
links describe source history and do not authorize replacing a parent semantic regression.

- `61c7735` changes three closure-codegen expectations and one local function-call expectation
  from environment/wrapper emission to Direct entry emission.
- `1fc9f4e` changes the sibling-capture expectation to trailing parameters, and the local
  tail-recursive function expectation to eliding its closure-entry wrapper.
- `bc41865` replaces three clone-per-PAP expectations. The escaping heap-capture program becomes
  a rejection test. The ordinary and aliased heap-argument programs become local borrow tests
  with no clone and no payload destruction by the PAP. The consuming-argument test is a separate
  source case. The map follows the programs and ownership contract, not adjacent diff lines.

All three rejected clone expectations retain their source programs and assertions through their
blob/line provenance. Their successors remain pending. No hidden cloning is authorized by this
inventory. The move-closure cleanup test and nullary immediate-lambda test also retain their
ignored and enabled versions; the original surrounding comments are in the referenced blobs.

## Verification and discovered gap

Both the parent baseline and the helper migration passed 410 discovered tests, with zero failures
or errors. The migration verification command was:

```sh
sbtn 'scalafmtAll;scalafixAll;test'
```

Formatting and linting succeeded without compiler warnings. Every parent declaration remains
identical after normalizing the four helper renames. Whole-file comparisons verify that the
compiler, memory programs, and samples still match the parent.

`TbaaEmissionTest.scala:111` declares `loads and stores include alias scope metadata` inside the
`TBAA field offsets honor alignment (String has ptr at offset 8, not 4)` test. It is present in
both parent and source but absent from the runner's discovered cases. Its inventory execution
status is `not-discovered`, and its enclosing declaration is recorded. This explains the
411-declaration versus 410-executed-test counts. Fixing the registration is a separate change;
the test is preserved here without claiming it passes.

## Remaining migration

The 71 absent source names and 11 changed declarations require reviewed migrations. Their source
files and historical variants are all mapped; they have not been copied into the running suite.
The 12 added memory programs and 67 changed sample entries also remain pending, alongside the
benchmark and VS Code dependency changes listed in the salvage handoff.

Before changing representation assertions, retain the source program, intended result or
diagnostic, and ownership purpose. Preserve parent behaviors through renamed tests. Review the
memory model and PAP ownership spec before compiler changes, and agree each compiler workstream
with the Author. Neither a green parent suite nor this inventory closes the full migration.
