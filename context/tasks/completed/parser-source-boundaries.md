# Fix parser source boundaries

## Metadata

- **Owner:** Author
- **Status:** complete
- **Kind:** CORRECTNESS
- **Priority:** HIGH
- **Created:** 2026-10-25
- **Target Branch:** dev/parser-and-error-improvements

## Execution Checklist

1. [x] **complete** — Centralize exact token capture and document the source-boundary contract.
2. [x] **complete** — Migrate token, name, and delimiter boundaries throughout the parser.
3. [x] **complete** — Compose enclosing spans from owned syntax, carrying annotation endpoints.
4. [x] **complete** — Separate successful syntax endpoints from recovery scanner positions.
5. [x] **complete** — Add parser, CLI, and LSP regressions and update affected span assertions.
6. [x] **complete** — Compiler gates, measurements, QA, and independent correction review pass.
7. [x] **complete** — Code tour and verification evidence prepared for Author review.

## Tags

- parser
- source-spans
- diagnostics
- lsp

## Problem

Parser source spans can include trailing whitespace and line comments. A type mismatch on
`let b: Int = "2";` can therefore highlight the comment preceding the next declaration.
In the reported layout of [test-poisoning.mml](../../../mml/samples/test-poisoning.mml), the
mismatch covers `[5:5]-[8:1]`; its intended range is `[5:5]-[5:18]`, immediately after
the binding's semicolon.

Source inspection at `6d160888` identifies two causes:

- An enclosing FastParse `~` consumes trivia before `spNoWsP` captures the position.
  The helper's internal whitespace policy cannot undo that consumption.
- Some child parsers consume trivia before returning. Capturing a cursor after those
  children remains incorrect even with raw sequencing at the capture site.

The same patterns occur in names, types, expression frames, delimiters, native forms,
and link directives. CLI and LSP diagnostics use these parser spans; presentation-time
comment filtering would leave incorrect source information in the AST and editor queries.

## Outcome

Source spans end at the syntax they describe. Trailing spaces and comments do not extend
nodes or diagnostics. Comments between owned tokens remain inside composite spans.
Expression envelopes include consumed annotations even when a reference's display span
is narrower. Recovery retains exact malformed text and makes the same progress.

## Scope

- Parser-wide source-boundary capture and composition, with CLI and LSP regression coverage.
- End-exclusive spans, existing node coverage choices, and explicit recovery exceptions.
- Compiler design documentation and durable verification evidence.
- Preserve grammar, cuts, recovery anchors, diagnostic messages and suppression, semantic
  structure, and binding identities. Corrected locations and highlight ranges are intentional.
- No lexer replacement, public AST redesign, tree-rewrite framework, or uniform expansion of
  all declaration/reference spans. Preserve unrelated changes and sample edits.

This follows
[Error recovery and parser backtracking](error-recovery-and-parser-backtracking.md).
That task's verified implementation and semantic performance correction provide the starting
point. Both implementations and their documentation are accepted.

## Plan (Approval Gate)

Approval: granted for the complete source-boundary implementation and verification plan below.

### 1. Exact capture and ownership

Add parser-private `ParsedToken[A](value, location: SrcSpan)` and
`tokenP(info)(token: => P[A])`, using `Index ~~ token ~~ Index`. The argument remains by-name.
The caller supplies leading whitespace handling; the token parser must finish immediately
after its owned syntax. Capture before trailing trivia or whitespace-consuming validation.
Do not wrap arbitrary AST parsers, optional tails, repetitions, or recovery scanners.

Replace the misleading two position helpers with one documented raw cursor helper. Preserve
existing whitespace handling between grammar elements, cuts, alternatives, and repetitions.

The source contract is `[start, end)`. Preserve established coverage choices, including
name-only function/operator bindings and narrow reference spans. The module retains
whole-file coverage; attached doc comments and synthetic lowering retain their existing
ownership rules.

### 2. Tokens, delimiters, and validation

Migrate names, references, literals, parameters, types, groups, tuples, lambdas, declarations,
native forms, and link directives. Successful member `let` uses its existing exact
semicolon endpoint. Delimited composites end immediately after their owned closer.

Capture native bracket endpoints before validation and carry them through its result.
Capture integer LLVM type fragments before their validating `flatMap`, preserving its
whitespace behavior. Link entries inherit their string literal's exact location.

### 3. Composite syntax and annotations

Undelimited composites end at their final child's syntax endpoint. Function bodies retain
separate body/declaration semicolons. Conditional clauses retain terminator endpoints:
the conditional ends at the explicit `else`, otherwise the last `elif`, otherwise the
initial clause. The implicit false branch remains zero-width at that endpoint.

Add parser-private `ParsedTerm(value: Term, end: SrcPoint)` at the term/ascription-to-frame
boundary. Change `withTypeAsc`, `termP`, `termMemberP`, and their expression-frame callbacks;
leaf parsers remain `P[Term]` and public AST types stay unchanged.

Compute the endpoint from the term's source and written ascriptions before applying
`withTypeAsc`; ignore computed `typeSpec`. This preserves consumed syntax when attachment
discards an annotation, as with the empty-tuple error in `( ): Int`. It also preserves
narrow reference spans without truncating their enclosing expressions.

### 4. Recovery positions

Expression frames distinguish three boundaries: successful syntax end, scanner start,
and recovery end. Capture scanner start together with the scanner call, after normal
inter-parser whitespace, for example:

```scala
termsP.rep ~ P(pointP(info) ~~ recoveryP(info, expressionRecovery))
```

Grouping the capture with recovery avoids a zero-width sequence rewind separating the
recorded start from the scanner's actual input. Recovery occurs only when the scanner
advances beyond scanner start. Its error span and failed text remain
`[scannerStart, recoveryEnd)`; the successful prefix ends at the last `ParsedTerm.end`.
A combined recovered expression ends at recovery end.

Preserve progress predicates, caller stop tokens, and zero-width missing-syntax errors.
Scanner-owned malformed text retains its exact whitespace. Delimiter-owned errors, such
as a closed malformed type group, end at the actual closing token. Do not trim source text
backward or derive recovery progress from the shortened syntax endpoint.

Audit all remaining cursor-based endpoints and document their ownership in the compiler
design. Each must represent an intentional recovery/missing-syntax boundary or an exact
owned-token capture.

## Verification

### Results — 2026-10-25

- Final `sbtn 'scalafmtAll; scalafixAll; testFull'`: exit 0; 1,946 library tests and nine CLI tests
  pass, zero failures/errors. No Scala compiler warnings. The build reports multiple test
  entrypoints, which are expected for the parser measurement utilities.
- `./tests/smoke/run.sh all`: 8/8 pass, repeated after the editor correction. Five fixtures
  execute through `sbtn run run`; the remaining three check compilation. This satisfies
  the valid-execution check before publication.
- `sbtn mmlcPublishLocal`: exit 0; the compiler is republished after the editor correction
  and smoke run.
- `mmlc mml/samples/test-poisoning.mml`: expected error exit 1; one visible type mismatch at
  `[5:5]-[5:18]`, highlighting only `b: Int = "2";`. The following comment is outside the range.
- `make -C benchmark clean`, then `make -C benchmark mml`: both exit 0; all 37 MML build
  commands succeed. This checks build compatibility, not execution performance.
- `./tests/mem/run.sh all`: exit 0; 52/52 fixtures pass ASan+LSan in 76 seconds, covering
  their executed paths on arm64 macOS with Clang 23.1.1.
- Three fresh-JVM parser measurement forks pass with zero recovered errors on all four inputs,
  equal instrumented/uninstrumented trees, and valid checksums/final trees in every batch.
  The shared fork setting is restored to false. The
  [measurement report](../../../benchmark/parser/source-boundaries.md) retains all 84 samples,
  corrected tree hashes, source fingerprints, counters, environment, and comparison limits.
- A concurrent, subsequently aborted rebase interrupted an earlier measurement attempt before
  it produced samples. That attempt is excluded. After restoration,
  `sbtn 'set mmlclib / Test / fork := false; clean; testFull'` passes with 1,945 library and nine
  CLI tests, exit 0. The three retained measurement forks follow that clean run.
- Independent review covered the 19 parser, test, and technical-document files. A fresh primary
  reviewer and three separate fresh checks for style/QA, test quality, and human readability
  completed. The dedicated checks found no actionable issues. A separate fresh verifier
  confirmed one editor-query regression. The correction and regression pass the full suite.
- Fresh narrow re-review covered the editor correction, its regression, and directly affected
  design/article text. A new primary reviewer and three separate fresh style/QA, test-quality,
  and readability reviewers completed with no actionable findings. No candidates required
  further claim verification; no checks were skipped or replaced. The reviewer inspected the
  current four-test reference-suite report but did not run an independent test execution.
- Root QA enforcement and final whole-record tracking review pass. Local links, exact commit
  scope, parser fingerprints, and `git diff --check` are checked before handoff.

Benchmark builds and sanitizer fixtures cover the parser implementation before the editor-only
correction; the corrected editor traversal is covered by the repeated full suite and smoke run.
Parser and backend sources are unchanged by that correction.

The first focused run passed 394/400; six assertions expected whitespace after a closed group
or compared a conditional's terminator with its narrower body. The first full run passed
1,943/1,945 library tests; two assertions expected a space after an undefined identifier.
Only those intentional span expectations changed. The final full run above includes all
82 added tests and the corrected expectations; no tests are ignored by this change.

Coverage includes successful tokens and composites, annotations discarded by error nodes,
native validation and optional attributes, exact scanner-owned malformed text, LF/CRLF
comments, subsequent declarations, CLI highlights, LSP ranges, semantic tokens, and editor
lookup. Existing recovery, semantic-identity, diagnostic-order, runtime, and parser-deadline
tests pass in the full suite. Source-boundary changes do not alter ABI or target layout;
the additional Linux-container gate does not apply.

#### Review correction: annotation reference lookup

For `struct Box { value: Int }; let constructor = Box: Int -> Box 1;`,
`AstLookup.findReferencesAt(module, 1, 58, includeDeclaration = true)` targets the return-type
`Box`. In the review reproduction before the editor correction, the preceding parser returned
the declaration and annotation; the initial source-boundary implementation returned no result.
Both completed semantic analysis without errors. Narrowing only the preceding parser's raw
constructor-reference span to `[45,48)` also lost the query result. A query on the declaration
returned the declaration and written annotation, isolating the failure to target selection
rather than reference collection.

The independently verified probe passes its comparison assertions. Its first attempt used
an overly narrow test extractor and failed before lookup assertions; a corrected bare-constructor
case disproved that initial example. The fully applied fixture above establishes the introduced
regression. Shared build settings were restored after every attempt. Reference-target
traversal reaches a written annotation independently of its reference's name span through
a shared containment check at expression and callee traversal. Parser name spans and hover
behavior are unchanged. A full-semantic regression queries the annotation and declaration,
compares both declaration-inclusion modes, and checks exact spans plus negative controls
at punctuation, whitespace, and comments.

The bare form `let constructor = Box: Int -> Box;` also loses the annotation query with the
preceding parser because eta-expansion introduces an argument lookup that masks the callee.
That pre-existing editor limitation is outside this correction; it is not evidence of an
introduced source-boundary regression.

The regression initially expected `includeDeclaration = false` to omit the struct span.
That fails through unchanged reference-collection code: `ConstructorGenerator` gives the
synthetic constructor's return annotation the struct's source, and `collectReferencesInMember`
collects that annotation regardless of declaration inclusion. The test compares annotation-
and declaration-origin queries in both modes without requiring a separate collector repair.
The first post-correction full run therefore passed 1,945/1,946 library tests and all nine CLI
tests; the failing assertion was this additional, pre-existing collector behavior. The final
run passes 1,946/1,946 library tests and all nine CLI tests in 93 seconds. Before the fix, the
focused regression failed on the annotation target with three existing reference tests passing.
All 15 measured parser fingerprints are unchanged by the editor correction. The new regression
exercises application-callee traversal; direct expression-reference traversal, generated-reference
exclusion, and computed-type exclusion are checked by source review. No broader editor-query,
performance, or cross-target claim follows from that one fixture.

### Regression coverage

- Assert explicit offsets and source slices across representative parser families with spaces,
  tabs, blank lines, LF/CRLF, trailing line comments, adjacent punctuation, and EOF. Appending
  trailing trivia must not extend an existing node.
- Cover nested composites, annotations, native validation, link strings, optional tails,
  failed alternatives, and strings containing `//` or whitespace.
- Pin `item: Int // comment\n ;` as an expression with no recovery error and an endpoint
  after `Int`. For `item: Int // comment\n @ bad  ;`, recovery starts at `@` and retains
  `"@ bad  "` as failed text. Cover `( ): Int;`, missing punctuation, and subsequent declarations.
- Embed a poisoning fixture in CLI and LSP tests: one visible mismatch, unchanged message
  and cause relationships, a range ending after the binding semicolon, and no highlighted
  following comment. Check editor lookup and semantic-token boundaries using explicit
  source positions rather than expectations derived from the same AST span.
- Retain grammar, recovery/deadline, tree-shape, binding-identity, and instrumented versus
  uninstrumented parser controls. Update assertions only for intentional span corrections.

### Compiler handoff gates

- Run `sbtn scalafmtAll`, `sbtn scalafixAll`, and `sbtn testFull` sequentially.
- Run the smoke collection and a valid compiler execution through `sbtn`, then publish
  locally before benchmark builds and the memory harness. Follow the repository's compiler
  handoff rules; never run concurrent `sbtn` sessions.
- Repeat the established four-input parser measurement protocol from
  [the parser comparison](../../../benchmark/parser/comparison-final.md). Preserve historical
  reports and record corrected-span hashes and fresh measurements separately. Benchmark
  builds establish compatibility, not performance.
- Complete repository QA enforcement and independent code review. Address confirmed findings
  and rerun affected checks; record actual outcomes in versioned evidence.
- Code tour and verification evidence are accepted.

## Risks / Notes

- A generic location wrapper is unsound when its child has already consumed trailing trivia.
- Shortening the endpoint used for recovery progress can create false errors from whitespace.
- AST-only endpoint reconstruction loses annotations consumed but discarded by error nodes.
- Corrected spans intentionally change raw AST hashes. Historical comparisons remain evidence
  for their recorded revisions and must not be rewritten as results for this change.

## Signoff

- Workstream and documentation signoff: accepted on 2026-10-25.
- Tracked item completion: accepted on 2026-10-25; all seven execution steps complete.
- Delivery: squash commit `9d4f4740` on `main` includes the implementation and verification records.
- Closure bookkeeping: authorized as an amendment to the latest commit; no additional push.

## Task Working Memory

The source-boundary implementation is complete and delivered to `main` in `9d4f4740`.
Successful syntax endpoints, consumed annotations, and recovery scanner positions are separate.
The annotation reference-query regression is corrected. Recorded final verification passes
1,946 library and nine CLI tests, smoke 8/8, 37 benchmark builds, and 52/52 sanitizer fixtures;
three parser measurement forks and independent correction reviews pass. The compiler is
published locally. Coverage and platform limits remain documented under Verification.

Closure changes task bookkeeping only. Compiler sources and tests are unchanged from the
verified delivery; no compiler gates are rerun for closure. Author signoff covers all steps.
The related [error-recovery task](error-recovery-and-parser-backtracking.md) is also complete.
