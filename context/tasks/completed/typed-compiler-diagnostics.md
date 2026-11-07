# Typed compiler errors and shared diagnostic rendering

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** improvement
- **Priority:** unassigned
- **Created:** 2026-10-30
- **Completed:** 2026-11-05
- **Target Branch:** dev/typed-compiler-diagnostics
- **External Reference (optional):** None.

## Execution Checklist

1. [x] **complete** — Settle typed payloads, presentation mapping, and template arguments from the inventory.
2. [x] **complete** — Agree on the bounded migration order covering every inventoried family.
3. [x] **complete** — Migrate error producers, carriers, and presentation consumers across S1–S7.
4. [x] **complete** — Replace semantic string matching in tests and production control flow.
5. [x] **complete** — Verify the integrated result, finish review, and reconcile documentation.

## Tags

- diagnostics
- architecture
- testing
- cli
- lsp

## Problem

Several compiler errors identify distinct failures only through message strings. Tests inspect
`message` or `toString` to distinguish those failures, and some tests do the same even when a
specific error type already exists. Toolchain control flow also recognizes a missing tool by
searching an error message. Wording changes can therefore affect behavior and semantic assertions.

Error values and producers contain presentation text, while CLI and LSP consumers maintain
additional formatting matches. A dedicated type for one ownership error would leave this broader
model and presentation problem intact.

## Outcome

Compiler errors carry typed reasons and structured context. Shared presentation code maps those
values to messages and template substitutions for CLI, LSP, and plain API output. Semantic tests
and compiler decisions use error types and fields; focused rendering tests verify wording.

## Design

Keep useful outer categories such as `SemanticError.InvalidExpression` and `CodeGenError`, but
replace their arbitrary message strings with typed payloads. The existing
`ParsingIdErrorFound(error, phase)` and `ProtocolErrorFound(error, phase)` establish the carrier
pattern. For example: `InvalidExpression(expr, OwnershipError.GlobalTransfer, phase)`.
The payload identifies the failure and retains its structured context and source. Use closed
family enums and direct carriers. Introduce a small source-bearing trait only where consumers
need that shared contract; ordinary presentation functions do not need type-class instances.

Put the `error -> message` mapping near the printing side, shared by CLI, LSP, and plain API output.
Errors do not own human wording or require a `message` method. Move existing message generation
out of typed families such as `TypeError` and `ProtocolError` too. Keep shared message descriptions
separate from output-specific layout, highlighting, snippets, and LSP ranges.

Build messages with ordinary Scala string interpolation in shared presentation functions.
Derive substitutions from typed fields rather than storing a second representation in a map.
For a borrowed-argument error, the reference and consuming parameter supply the value and
parameter names. Derive ordered source labels separately from their source origins. Extract
individual message functions only when reuse or readability warrants them; no template engine
or generic substitution registry is required.

Keep `CompilationError` open, with a data-only target contract. Unknown extension errors remain
outside LSP conversion, as required by `DiagnosticsTests`. All built-in message mappings live in
presentation functions. Typed payloads contain no wording;
the shared contract and family models have no compatibility message methods.

Preserve diagnostic occurrence IDs, source provenance, ordering, recovery roots, and
`Primary`/`Consequence` relationships. Apply existing visibility filtering before presentation;
raw errors remain authoritative for compilation failure and code-generation gating.

Semantic assertions and production decisions match error variants and their fields. Keep text
assertions for rendering and opaque external output. A generic message-only fallback must not
replace modeling compiler-owned failure reasons.

## Scope

- Cover all inventoried parser, semantic, ownership, destruction, closure, entry-point, protocol,
  code-generation, and toolchain reasons; include tests that already have a typed alternative.
- Replace production branching on diagnostic wording, including missing-tool recognition.
- Adapt CLI printers, LSP diagnostics, source extraction, plain API output, and affected test helpers.
- Preserve structured errors across phase boundaries instead of flattening them to joined messages.
- Retain external process output and parser-library traces as data where their format is external.
- Preserve language semantics, recovery behavior, diagnostic visibility, and established output
  meaning. Localization, a general template engine, and unrelated toolchain cleanup are outside
  this task unless the design identifies a concrete requirement.

## Plan (Approval Gate)

Design, migration order, and implementation of S1–S7: approved as one workstream.
Verification and review apply to the integrated result. Each family migration includes producers,
carriers, presentation consumers, and semantic assertions together.

### Migration stages

1. **S1 — Presentation foundation and entry-point pilot.** Introduce data-only reasons for no
   valid entry point, non-public `main`, and invalid return type. Retain the relevant source or
   binding. Route the semantic carrier, both CLI printers, LSP, and quiet output through shared
   message mapping. Preserve eligibility, source ranges, visibility, and failure gating. Replace
   entry-point semantic text assertions and verify output separately. Existing message access
   delegates to presentation until S7 removes the shared contract.
2. **S2 — Existing typed semantic families.** Move type, name-resolution, visibility, and protocol
   wording to presentation; type protocol detail strings and migrate semantic assertions.
3. **S3 — Parser recovery.** Type syntax reasons; retain ordered nested failures, failed source
   text, and recovery causes through ingest. Preserve diagnostic grouping during this migration.
4. **S4 — Expressions and ownership.** Cover rewriting, local bindings, integer ranges, Clone,
   ownership sinks, and callable ownership with typed payloads and assertions.
5. **S5 — Closures and destruction.** Preserve typed layout and destruction failures through
   semantic and code-generation boundaries.
6. **S6 — Code generation and toolchain.** Type emitter, target, resource, and process failures;
   retain nested errors and external output. Distinguish process-start failure from confirmed
   missing tools and unsuccessful process exits without broad exception-boundary cleanup.
7. **S7 — Finish the contract migration.** Remove compatibility message methods, cover warning
   presentation and AST/debug consumers, classify retained text assertions, and update compiler
   documentation. Preserve existing warning emission and LSP family eligibility.

## Verification

### S1 evidence

- `sbtn "scalafmtAll; scalafixAll; testOnly mml.mmlclib.semantic.PreCodegenValidatorSuite mml.mmlclib.util.error.print.EntryPointMessageTests mml.mmlclib.errors.DiagnosticStoreTests mml.mmlclib.semantic.ParsingErrorCheckerTests mml.mmlclib.semantic.OwnershipDiagnosticCauseTests mml.mmlclib.lsp.DiagnosticsTests"` passed: 61 tests, no failures.
- `./tests/smoke/run.sh all` passed all eight fixtures. The initial sandboxed invocation could
  not acquire the sbt boot lock; the rerun with local cache/server access passed.
- `sbtn testFull` passed: 2,604 library tests and 9 CLI tests; zero failures, errors, or skipped
  tests in the test reports. Host verification: 2026-11-05, Darwin arm64, GraalVM JDK 25.0.4.1.
- `sbtn mmlcPublishLocal` passed after smoke verification.
- `make -C benchmark clean` and `make -C benchmark mml` passed, building all 37 MML benchmark
  executables. These are compilation checks, not execution timings.
- ABI/container and memory-harness gates do not apply: S1 changes diagnostic payloads and
  presentation only. Ownership-test edits adapt existing diagnostic constructors.
- QA compliance and tracking consistency checks passed. Independent review completed all four
  dedicated checks and confirmed two findings: source-range tests derived their expectations
  from the production accessor, and `NotFound` needed a local explanation of unsupported
  parameters. The tests use independent fixture spans and the variant documents that condition.
- `sbtn "scalafmtAll; scalafixAll; testOnly mml.mmlclib.util.error.print.EntryPointMessageTests mml.mmlclib.semantic.PreCodegenValidatorSuite"` passed after those corrections: 19 tests, no failures.
  The corrections change test assertions and a comment, not production behavior. Narrow
  independent re-review completed its primary review and all four fresh dedicated checks
  (QA, test quality, readability, and code documentation) with no actionable findings.
- Entry-point selection assertions match typed payloads. Presentation tests cover all three
  reasons through both CLI printers, LSP, and quiet native compilation, including source spans,
  synthetic-source omission, and rejection before output creation.

### Integrated S1–S7 evidence

- `sbtn "scalafmtAll; scalafixAll; testFull"` passed: 2,613 library tests and 9 CLI tests.
  All 153 XML reports total 2,622 tests with zero failures, errors, or skipped tests.
  The first integrated test run reported 26 migration-assertion failures; corrections
  distinguish semantic facts from output formatting and use source evidence for duplicate
  declarations. The complete rerun passes. Scalafix removed unused imports; final compilation
  has no Scala warnings. sbt reports its existing multiple-main-classes discovery notice.
- `./tests/smoke/run.sh all` passed all eight fixtures.
- `sbtn mmlcPublishLocal` passed after smoke verification.
- Host: Darwin arm64, GraalVM JDK 25.0.4.1, sbt 2.0.10, Homebrew LLVM/Clang 23.1.1.
- `make -C benchmark clean` and `make -C benchmark mml` passed: all 37 benchmark
  executables built. These are compilation checks, not performance measurements.
- `./tests/mem/run.sh all` passed all 52 fixtures under ASan+LSan in 85 seconds.
- No ABI or target-lowering algorithms changed. Linux ABI/container verification is not
  applicable; host execution does not establish cross-target portability.
- Local QA audit covers the full diff and new files: no introduced mutable compiler state,
  exception-based diagnostic propagation, compatibility message methods, or semantic text
  classification remains. Independent review completed its primary pass and four fresh
  dedicated checks; each of three findings had a separate fresh verifier. The corrections
  restore struct names in unknown-field wording, retain expected-token payloads across the
  EOF parser test subprocess, and document closure mismatch counts as layout fields.
- `sbtn "scalafmtAll; scalafixAll; testOnly mml.mmlclib.parser.ConditionalRecoveryTests mml.mmlclib.util.error.print.SharedDiagnosticMessageTests mml.mmlclib.util.error.print.TypeErrorPrinterTests mml.mmlclib.lsp.DiagnosticsTests mml.mmlclib.semantic.ClosureEnvironmentTests"`
  passed after review corrections: 77 tests, zero failures. This includes the new unknown-field
  rendering regression and all 11 EOF/truncation payload comparisons. Narrow re-review completed
  its primary pass and all four fresh dedicated checks. Its only additional finding was an
  independently confirmed missing blank line between identity and rendering assertions; that
  correction passed a fresh narrow primary review and all four dedicated checks. No actionable
  findings remain. The whitespace-only correction required no additional runtime verification.
- Post-correction smoke verification passed 8/8 on retry. The first recheck reported 7/8:
  `hola` printed its expected output and sbt reported success, then the thin client raised
  `TimeoutException: Unable to join thread ... sbt-serverconnection-0 ... after 5 seconds`.
  The full harness retry passed. The corrected compiler was installed locally successfully.

For each migration, verify the exact typed reason and payload, source locations, independent
occurrences, recovery roots, ordering, visibility, and failure gating. Test template coverage and
substitution contracts, then check CLI, LSP, and plain API rendering from the same error values.
Retain focused output tests and external-output checks. Search production and tests for remaining
message-based error discrimination and classify every retained occurrence. Run the applicable
compiler checks and independent review under the repository rules.

## Risks / Notes

Follow-up after [Implement protocols](protocols.md). The protocol foundation is complete;
[explicit Clone and global ownership transfers](explicit-clone.md) has separate scope and signoff.
Protocol completion remains independent of this migration.

Coordinate source-field contracts with
[diagnostic contract documentation](../compiler-diagnostic-contract-documentation.md) and ownership
construction with [diagnostic maintainability](../ownership-diagnostic-maintainability.md).
[Toolchain error boundaries](../qa-toolchain-error-boundaries.md) owns broader exception-boundary
cleanup; this task owns typed failure identity and presentation at those boundaries.

The inventory below records the migration scope. Rendering checks and external-output checks
remain valid uses of text.

## Signoff

- Design and migration order: approved.
- Integrated S1–S7 implementation, verification, and independent review: complete.
- Whole-workstream signoff: approved for S1–S7.
- Tracked item completion: complete.
- Local commit and squash delivery to `main`: authorized.

## Task Working Memory

All S1–S7 producers and consumers use typed payloads and shared presentation functions.
The open `CompilationError` contract is data-only. Parser groups, closure layout failures,
and codegen field errors retain nested structured values. Tool execution distinguishes missing
executables from process-start and exit failures; only missing tools invalidate the marker.
Semantic tests inspect reasons and source evidence independently of rendering. Compiler design
records the complete contract.

Formatting/lint, 2,622 tests, eight smoke fixtures, local publishing, 37 benchmark builds, and
52 ASan+LSan memory fixtures pass. The three initial review corrections pass 77 affected tests.
Independent review and narrow re-reviews are complete with no actionable findings; the final
correction separates two test paragraphs. The local compiler includes all production corrections.
S1–S7 are signed off and complete. Implementation, verification, and review have no remaining work.

### Retained text checks

- CLI/LSP/plain rendering assertions: `DiagnosticPresentationTests`, `DiagnosticsTests`,
  `ProtocolDiagnosticTests`, `ProtocolAlternativeDiagnosticTests`, `EntryPointMessageTests`,
  `ParsingErrorPrinterTests`, and the output-specific sections of recovery integration,
  protocol parser/checker, type resolver, and identifier tests.
- Failure clues in `BaseEffFunSuite`, codegen tests, and semantic tests call `diagnosticMessage`
  only after typed failure selection or to fail a test unexpectedly; text does not select behavior.
- `FrontendCorpus` records rendered visible output for corpus comparisons. `ParserBaseline`
  consumes parser-failure text as a benchmark checksum. `ParserDeadlineProbe` serializes
  typed missing-syntax cases with token payloads for its structural comparison and renders only
  its external probe failure report.
- Toolchain probe stderr, sanitizer output, emitted LLVM IR, and CLI process output are external
  or presentation contracts. Their checks retain text assertions.
- Production `.message` fields belong to LSP/JSON-RPC protocol DTOs. Compiler control flow
  does not inspect diagnostic wording.

## Migration inventory (original scope)

Production paths below are relative to
`modules/mmlc-lib/src/main/scala/mml/mmlclib/`; corresponding test paths are under
`modules/mmlc-lib/src/test/scala/mml/mmlclib/`.

| Module | General description |
| --- | --- |
| `errors/` | Replace the shared message-bearing contract; preserve `DiagnosticStore` occurrence identity and causes. Include warning payloads in the presentation design. |
| `ast/` | Parser recovery nodes, invalid-member markers, and closure-environment errors carry string reasons. Preserve failed source text, subjects, and recovery evidence. |
| `parser/` | Binding, expression, conditional, type, member, identifier, link, and protocol failures need typed reasons. Protocol recovery joins and deduplicates message strings; retain structured reasons before aggregation. External parser traces remain data. |
| `compiler/` | Parsing-error lifting, compiler state, and code-generation failure boundaries must retain typed payloads and existing recovery, visibility, and gating behavior. |
| `semantic/package.scala` | `InvalidEntryPoint` carries the typed S1 payload. Generic `InvalidExpression` and `DanglingTerms` still mix distinct failures. Remaining `SemanticError` and `TypeError` message mappings move to presentation. |
| Semantic ownership and Clone modules | Ownership sinks, global transfers, callable alternatives, consumed environments, capture ownership, malformed expressions, and Clone contracts use generic expression errors. |
| Semantic expression, binding, and type modules | Expression rewriting, local binding identity, duplicate-name recovery, and integer-range checks use textual reasons. Some resolver tests inspect text despite existing typed errors. |
| Semantic protocol modules | `ProtocolError` already models many reasons, but signature/member detail strings remain. Move rendering out of the model and use typed payloads in selection, grouping, and checker tests. |
| Semantic destruction and closure modules | Destruction validation and memory/destructor generation collapse distinct contract failures into generic errors. Closure metadata failures lose their identity when forwarded to other phases. |
| `codegen/PreCodegenValidator.scala`, `LlvmToolchain.scala`, `ClangTarget.scala` | Model entry-point, target, process-launch, and tool-operation reasons. Replace missing-tool message matching; retain external stderr and exception details as payload. |
| `codegen/emitter/`, including `expression/` and `tbaa/` | Callable, closure, destruction, parameter, layout, module, expression, and type emission share string-based `CodeGenError`. Preserve nested failures and distinguish compiler invariant failures from source-code and target restrictions. |
| `util/error/print/` | Consolidate message mapping used by CLI printers and source extraction; preserve focused wording, location, and highlighting tests. |
| `lsp/` | Share message mapping and preserve ranges and diagnostic visibility. Tests that select diagnostics by rendered text need structured selection before conversion or an appropriate presentation identifier. |
| `api/` | Preserve typed frontend failures through internal boundaries; use the shared renderer for quiet/plain output and retain opaque exception details where appropriate. |
| Tests in `semantic/`, `parser/`, `grammar/`, `codegen/`, and `lsp/` | Migrate message-based reason selection, payload checks, broad phase selectors, and recovery comparisons. Some need new reason types; others can already match existing variants. |
| Test helpers, CLI configuration tests, and sanitizer/CI harnesses | Keep genuine rendering, external-tool output, probe-output, and test-helper failure-message checks distinct from compiler error classification. |
