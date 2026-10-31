# Typed compiler errors and shared diagnostic rendering

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** improvement
- **Priority:** unassigned
- **Created:** 2026-10-30
- **Target Branch:** unassigned
- **External Reference (optional):** None.

## Execution Checklist

1. [ ] **design** — Settle typed payloads, presentation mapping, and template arguments from the inventory.
2. [ ] **planned** — Approve bounded migrations covering every inventoried diagnostic family.
3. [ ] **planned** — Migrate error producers, carriers, and presentation consumers by family.
4. [ ] **planned** — Replace semantic string matching in tests and production control flow.
5. [ ] **planned** — Verify rendering, recovery, and diagnostic identity; update compiler documentation.

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
pattern. For example: `InvalidExpression(OwnershipError.GlobalTransfer(expr), phase)`.
The payload identifies the failure and retains its structured context and source. A shared trait
or type-class instance can support the carrier; the concrete interface remains a design choice.

Put the `error -> message` mapping near the printing side, shared by CLI, LSP, and plain API output.
Errors do not own human wording or require a `message` method. Move existing message generation
out of typed families such as `TypeError` and `ProtocolError` too. Keep shared message descriptions
separate from output-specific layout, highlighting, snippets, and LSP ranges.

Errors may carry a map from a thing to its message or substitution for an output template.
Settle whether entries describe named template arguments, source subjects with explanatory
labels, or both. Use structured keys and values, retaining the typed error independently of
the map. Message references and arguments can keep final wording at the presentation boundary;
deriving the map there from typed fields is another option. For a borrowed-argument error, the
reference and consuming parameter supply the template's value and parameter names and source labels.

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

Use the inventory to define payload families, carrier contracts, presentation ownership, and
template-map types. Compare a direct trait with a type-class boundary, then propose bounded
vertical migrations that update each family's producers, consumers, and tests together. Account
for the shared `CompilationError.message` contract and all consumers before removing it.

Approval: design direction and scheduling after protocol work are settled; concrete type shapes,
migration stages, and implementation approval are pending.

## Verification

The inventory records source inspection. Implementation verification is pending.

For each migration, verify the exact typed reason and payload, source locations, independent
occurrences, recovery roots, ordering, visibility, and failure gating. Test template coverage and
substitution contracts, then check CLI, LSP, and plain API rendering from the same error values.
Retain focused output tests and external-output checks. Search production and tests for remaining
message-based error discrimination and classify every retained occurrence. Run the applicable
compiler checks and independent review under the repository rules.

## Risks / Notes

Follow-up after [Implement protocols](protocols.md). The protocol foundation is complete;
[explicit Clone and global ownership transfers](explicit-clone.md) has separate scope and signoff.
This task does not reopen protocol completion or authorize compiler changes.

Coordinate source-field contracts with
[diagnostic contract documentation](compiler-diagnostic-contract-documentation.md) and ownership
construction with [diagnostic maintainability](ownership-diagnostic-maintainability.md).
[Toolchain error boundaries](qa-toolchain-error-boundaries.md) owns broader exception-boundary
cleanup; this task owns typed failure identity and presentation at those boundaries.

The inventory describes affected modules, not individual call sites. Revalidate each module
when planning its migration; rendering checks and external-output checks remain valid uses of text.

## Signoff

- Workstream signoff: concrete design and implementation plan pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The design separates typed error data from shared presentation mapping and supports template
substitutions or subject messages without using text as error identity. The exact carrier,
trait/type-class, and map representation need design. Next action: revalidate the inventory and
present the concrete data model, renderer boundary, and bounded migration plan.

## Affected Modules

Production paths below are relative to
`modules/mmlc-lib/src/main/scala/mml/mmlclib/`; corresponding test paths are under
`modules/mmlc-lib/src/test/scala/mml/mmlclib/`.

| Module | General description |
| --- | --- |
| `errors/` | Replace the shared message-bearing contract; preserve `DiagnosticStore` occurrence identity and causes. Include warning payloads in the presentation design. |
| `ast/` | Parser recovery nodes, invalid-member markers, and closure-environment errors carry string reasons. Preserve failed source text, subjects, and recovery evidence. |
| `parser/` | Binding, expression, conditional, type, member, identifier, link, and protocol failures need typed reasons. Protocol recovery joins and deduplicates message strings; retain structured reasons before aggregation. External parser traces remain data. |
| `compiler/` | Parsing-error lifting, compiler state, and code-generation failure boundaries must retain typed payloads and existing recovery, visibility, and gating behavior. |
| `semantic/package.scala` | Generic `InvalidExpression`, `DanglingTerms`, and `InvalidEntryPoint` carriers mix distinct failures. Existing `SemanticError` and `TypeError` message mappings move to presentation. |
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
