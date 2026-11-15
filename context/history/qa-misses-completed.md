# Completed QA misses

Resolution records for completed QA items. Open items are tracked in
[QA misses](../tasks/qa-misses.md). IDs remain reserved.

### QA-001: Preserve resolved type identity in type utilities

- **Status:** complete
- **Implementation and verification:** [Unify type-property alias resolution](../tasks/completed/type-property-alias-resolution.md).
- **Source:** [TypeQueries.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/TypeQueries.scala),
  [NativeTypeQueries.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/NativeTypeQueries.scala),
  [TypeMemoryQueries.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/TypeMemoryQueries.scala),
  and [MemoryFunctions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/queries/MemoryFunctions.scala).
- **Problem:** Reducing resolved types to short names permits stdlib-first or first-match lookup
  to select a different declaration. Name-based recursive guards can also conflate declarations.
- **Repair:** Property queries and callers preserve typed AST inputs and resolved IDs. Shared
  declaration resolution replaces name guessing, including native representation queries.
  Shared memory-function selection uses the resolved declaration's owning module; explicit native
  destructors may name stdlib bindings. Struct generation can reference its pending helpers.
- **Evidence:** 23 type-query tests and the full compiler suite pass. Collision tests use constructed
  resolved AST declarations; they do not establish multi-module source-language support.
- **Resolution:** Compiler gates, independent review, and Author signoff are complete.

### QA-003: Exercise normal CLI reporting in diagnostic agreement tests

- **Status:** complete
- **Implementation and verification:** [Moved-capture and visibility diagnostics](../tasks/completed/lsp-missing-semantic-diagnostics.md).
- **Source:** [DiagnosticsTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/DiagnosticsTests.scala),
  [CompilerApi.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/api/CompilerApi.scala),
  and [ErrorPrinter.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/util/error/print/ErrorPrinter.scala).
- **Problem:** CLI agreement assertions using `SemanticErrorPrinter` bypass normal command
  reporting through `ErrorPrinter`, whose moved-capture and visibility branches are separate.
  A defect in those normal CLI branches would not fail the secondary-printer assertions.
- **Expected outcome:** Source-driven checks capture normal CLI command output; constructed
  diagnostic payloads use the normal renderer and verify the offending source highlight.
- **Evidence:** Independent review confirmed the dispatch mismatch. The corrected tests use
  `CompilerApi.processAstOnly` for moved capture and `ErrorPrinter` for visibility; both focused
  diagnostic suites pass all 14 tests. Independent review accepts the corrected reporting path.
- **Resolution:** Compiler gates, independent review, and Author signoff are complete.

### QA-004: Isolate diagnostic-specific evidence in CLI assertions

- **Status:** complete
- **Implementation and verification:** [Moved-capture and visibility diagnostics](../tasks/completed/lsp-missing-semantic-diagnostics.md).
- **Source:** [DiagnosticsTests.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/lsp/DiagnosticsTests.scala).
- **Problem:** Searching combined CLI output for a moved site and highlighted reference lets
  a neighboring use-after-move diagnostic satisfy assertions intended for the moved-capture report.
- **Expected outcome:** Check the capture report's own evidence and verify that this complete
  report appears in normal CLI output.
- **Evidence:** Independent execution confirms that both diagnostics report the same reference
  and move site in the fixture. The correction isolates the typed capture error through
  `ErrorPrinter`; both focused diagnostic suites pass all 14 tests. Independent narrow review
  and its four dedicated checks have no actionable findings.
- **Resolution:** Compiler gates, independent review, and Author signoff are complete.

### QA-005: Describe lowercase operator references in the parser contract

- **Status:** complete
- **Implementation and verification:** [B23 parser repair](../tasks/completed/parser-parenthesized-expression-performance.md).
- **Source:** [expressions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala),
  `opRefP`, and [identifiers.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/identifiers.scala),
  `operatorIdP`.
- **Problem:** The `opRefP` comment describes symbolic operators only, although `operatorIdP`
  also accepts lowercase names. The plain-reference ordering depends on that behavior to
  preserve annotation ownership.
- **Expected outcome:** The declaration comment names both accepted operator forms.
- **Evidence:** Independent documentation review and a separate claim verifier confirm the
  mismatch. The corrected sentence describes symbolic or lowercase operator references.
  This is a documentation defect; no runtime defect is established.
- **Resolution:** The wording is corrected. Fresh narrow primary review and all four dedicated
  checks have no actionable findings. B23's workstream signoff is complete.

### QA-006: Give generic syntax concepts clear names and owners

- **Status:** complete
- **Implementation and verification:** [Generic functions S1](../tasks/generic-functions.md#s1-ast-and-parsing).
- **Source:** [type AST](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/types.scala),
  [generic parser](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/generics.scala),
  and [type parser](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/types.scala).
- **Problem:** S1 placed variable constraints with protocols and used overlapping names for
  individual constraints and their variable-associated groups. Shared type parsing retained
  protocol-specific terminology. A parser-test name and a design sentence overstated their
  subjects' responsibilities.
- **Expected outcome:** Constraint declarations live with the type model; names distinguish
  the variable-associated group from each constraint. Shared parser names describe their
  grammar, and comments, tests, and design documentation describe their actual contracts.
- **Evidence:** Focused review under QA section 11 completed all four dedicated checks and
  independently verified each of the seven design/readability findings. No runtime defect
  was established. Corrected code passed formatting, linting, all 2,704 tests, eight smoke
  checks, 37 benchmark builds, and 52 ASan+LSan fixtures. Fresh narrow review completed all
  four dedicated checks with no actionable findings.
- **Resolution:** Corrections, compiler verification, independent review, and S1 Author signoff
  are complete.

### QA-007: Explain generic binder membership and resolution contracts

- **Status:** complete
- **Implementation and verification:** [Generic functions S2](../tasks/generic-functions.md#s2-resolution-and-substitution).
- **Source:** [Lambda metadata](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/terms.scala)
  and [TypeResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeResolver.scala).
- **Problem:** The binding-scope comment incorrectly restricted cached local-function annotations
  to enclosing binders. Lambda documentation omitted type-parameter membership and ordering;
  variable-resolution documentation omitted identity preservation and missing-binder recovery.
- **Expected outcome:** Declaration comments state that binding wrappers introduce no type
  parameters, function parameters exclude enclosing binders and follow signature encounter order,
  and resolution preserves existing IDs while diagnosing unresolved occurrences without binders.
- **Evidence:** Independent review completed all four dedicated checks and separately verified
  the three documentation findings. No runtime defect was established. The corrections change
  comments only; compiler verification is recorded with S2.
- **Resolution:** Comments are corrected and formatted. Fresh narrow review completed all four
  dedicated checks with no actionable findings. Author signoff is granted with S2.

### QA-008: Preserve generic use-site evidence and local requirements

- **Status:** complete
- **Implementation and verification:** [Generic functions S3](../tasks/generic-functions.md#s3-type-checking-and-inference).
- **Source:** [RefResolver](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/RefResolver.scala),
  [TypeChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala),
  [GenericInference](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/GenericInference.scala),
  and [ProtocolRegistry](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ProtocolRegistry.scala).
- **Problem:** Independent S3 review confirmed eight findings:
  1. Explicit-type classification discards value ascriptions, accepting invalid `identity (Person: Bool)`.
  2. Local functions whose requirements mention only enclosing type variables bypass use-site checks.
  3. Higher-order inference cannot use a solved callback input to infer its unannotated result.
  4. Concrete and abstract protocol candidate trials duplicate state and applicability handling.
  5. Compound requirements lack a negative regression for the second protocol.
  6. Argument consumption lacks an explanation of its callable-inference boundary.
  7. Instance lookup documentation does not identify its result as an instance declaration ID.
  8. Binder freshening documentation does not explain the identity mapping and scope separation.

  The correction review separately confirmed duplicated test helpers and local selectors,
  an overly broad failure assertion, ambiguous type-name prose, and the missing local-template
  field contract.
- **Expected outcome:** Preserve written annotations, enforce inherited requirements, complete
  callback inference from solved inputs, share candidate trials, and add focused regressions
  and declaration documentation.
- **Evidence:** The primary review completed all four dedicated checks. Eight separate fresh
  verifiers confirmed the findings. Corrections pass formatting, linting, 2,795 library tests,
  9 CLI tests, 8 smoke checks, 37 benchmark builds, and 52 ASan+LSan fixtures. Regression
  coverage is consolidated in [GenericTypeCheckingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeCheckingTests.scala)
  and [GenericInferenceTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericInferenceTests.scala).
- **Resolution:** Behavioral and test/QA/documentation corrections pass verification. Narrow
  reviews verified test preservation and the whole-prefix requirement for value alternatives.
  All four dedicated checks completed with no actionable findings on the final corrections.
  Signoff is granted with S3.

### QA-009: Explain how BindingIds.within chooses an allocation scope

- **Status:** complete
- **Source:** [BindingIds.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/BindingIds.scala),
  `within`.
- **Problem:** The comment discusses allocation anchors and relocation without explaining that
  the operation chooses the ID prefix for bindings generated inside a function body.
- **Expected outcome:** State that purpose first, then explain how an assigned parameter supplies
  the prefix and when the operation allocates a child scope.
- **Evidence:** The implementation uses the first assigned parameter ID without its last segment
  or allocates a child scope. The existing relocation regression in
  [LocalBindingsTest](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/LocalBindingsTest.scala)
  checks reuse of that prefix after moving the function.
- **Resolution:** The comment is corrected and the marker removed. Formatting and whitespace
  checks pass. Fresh independent review completed all four dedicated checks with no actionable
  findings. No executable behavior changes. Signoff is granted with S3.
