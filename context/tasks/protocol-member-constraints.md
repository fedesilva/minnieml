# Protocol constraints and member-local type variables

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** feature
- **Priority:** HIGH
- **Created:** 2026-11-15
- **Target Branch:** `dev/protocol-constraints`

## Execution Checklist

1. [x] **complete** — [Phase 1: Protocol-level constraints](#phase-1-protocol-level-constraints).
2. [x] **complete** — [Phase 2: Member-local type variables](#phase-2-member-local-type-variables).

## Tags

- protocols
- generics
- constraints

## Problem

Protocol prerequisites need declaration validation and implied requirements in generic checking,
building on ordinary generic functions' `where` constraints. Member signatures need their own type
variables for operations whose types vary independently at each call. The
[language reference](../../docs/language-reference.md#protocols-and-instances) documents the
supported language surface for protocol prerequisites and member-local type variables.

## Outcome

Allow a protocol to require other protocols for its type parameter. Implementing the protocol
must satisfy those prerequisites, and generic callers can use them through the protocol constraint.
Then support member-local type variables in a separate phase, reusing ordinary generic function
scope, constraint, inference, and specialization machinery wherever the semantics agree.

## Scope

- Phase 1: protocol-level `where` constraints, prerequisite resolution, instance conformance,
  inherited evidence in member bodies and generic callers, diagnostics, and editor support.
- Phase 2: member-local type variables and their constraints, implementation conformance,
  inference, specialization, diagnostics, and editor support.
- Preserve additive specialization, the existing symbol index, and callable ownership rules.
- Out of scope: multiple protocol parameters, parameterized instances, default implementations,
  generic data types, and higher-kinded variables.

## Plan (Approval Gate)

### Phase 1: Protocol-level constraints

Requirements on the protocol parameter belong to the protocol declaration:

```text
protocol Render 'T where 'T: Describe =
  fn render(value: 'T): String;
;
```

- Declaring `Render Widget` requires a matching `Describe Widget` instance.
- Member implementations can use the prerequisite's operations.
- A generic caller constrained by `'T: Render` also has evidence for `'T: Describe`.
- Prerequisites apply to the whole protocol, rather than making individual members conditionally
  available.

#### Approved prerequisite rules

1. **Transitive requirements:** if `Render` requires `Describe`, and `Describe` requires
   `Display`, then a `Render` constraint supplies both requirements.
2. **Cycles:** reject direct self-requirements and indirect prerequisite cycles.
3. **Duplicates:** allow one entry per type variable in a `where` clause. Reject a repeated
   variable entry even when its requirements differ; combine its requirements with `&`. This
   one-entry rule applies to protocols and top-level/local functions. Within a protocol prerequisite
   entry, reject repeated protocol requirements. Accept overlapping transitive paths and explicit
   requirements already implied by another requirement.
4. **Variable scope:** protocol-level `where` may constrain only the protocol's declared
   type variable; it does not introduce variables.
5. **Declaration order:** prerequisite instances may appear before or after the dependent
   instance. Validation must be independent of declaration order.
6. **Declaration validity:** reject an instance missing any prerequisite during declaration
   checking. Declaration validity never depends on usage; validation is not deferred until
   a call or specialization.
7. **Member ambiguity:** matching same-named members remain ambiguous. Prerequisite relationships
   introduce no overriding or preference rule; lexical shadowing is unchanged.

Diagnostics identify unknown protocols, invalid constraint targets, cycles, duplicate written
requirements, and missing or invalid prerequisite instances. Requirements remain compile-time
constraints: no runtime dictionaries or ABI changes.

Implementation approval: granted for S1–S5. Each stage requires review and signoff before the
next stage starts. Phase 2 has a separate approved plan below.

Publish the verified compiler at every intermediate stage before requesting review. Run the
smoke harness before `sbtn mmlcPublishLocal`, then verify the stage's sample with installed
`mmlc`. The installed compiler and language server must include the delivered stage.

#### S1: Represent, parse, and resolve prerequisites

- **Status:** complete.
- Add `Protocol.constraints: List[TypeVariableConstraints]`, defaulting to empty. Retain written
  order and source locations using the existing constraint and protocol-reference types.
- Share the constraint-clause parser between protocols and top-level/local functions. Preserve
  `&`, comma-separated entries, contextual `where`, and declaration-boundary recovery.
- Resolve constraints in the scope of the protocol's single parameter. Resolve prerequisite
  names against all protocol declarations, including later ones.
- Diagnose malformed clauses, unknown variables, and unknown protocols at written locations.
  Keep member-local variables, member constraints, and parameterized instances unsupported.
- Verify parser recovery, retained source locations, binder isolation, forward references,
  independent failures, and repeated resolution.

#### S2: Validate the prerequisite graph

- **Status:** complete.
- Introduce a focused semantic component using resolved protocol IDs and source references.
  Detect duplicate written requirements and cycles with immutable traversal state; cycle
  diagnostics show the dependency path.
- Compute prerequisite-first order and deduplicated transitive IDs once. Store validated
  implication information in `ProtocolRegistry`, retaining written constraints in the AST.
- Reject invalid protocols and their dependants while preserving original diagnostic causes.
  Invalid prerequisites cannot supply evidence or cause misleading internal registry errors.
- Provide one registry query for whether an accepted protocol implies another; a valid protocol
  implies itself.
- Include a runnable prerequisite sample with expected output and independent counterexamples.

#### S3: Enforce prerequisites on instance declarations

- **Status:** complete.
- Retain instance identity reservation, member-contract validation, and lowering. After all
  instances undergo those checks, validate direct prerequisites in prerequisite-first order
  before `ProtocolChecker` returns.
- Require accepted instances for the same canonical concrete type. Processing prerequisites
  first establishes transitive validity independently of source order and use.
- Distinguish missing from rejected prerequisites, identifying the dependent instance and required
  protocol and preserving original failure causes. Keep rejected identities reserved for duplicate
  detection while excluding their implementations from usable candidates.
- Preserve source order, binding identities, and one executable body per member. Member bodies
  continue through ordinary type checking, including when unused.

#### S4: Make implied requirements available to generic code

- **Status:** complete.
- Share registry implication queries between abstract member selection and generic-call checking.
  Match variable binder IDs and protocol IDs; requirements on one variable cannot justify another.
- Keep source constraints unchanged, without synthetic inherited clauses. Retain signature IDs
  and `Ref.protocolType` for the existing monomorphizer to select concrete implementations.
- Cover direct calls, operators, function values, partial applications, generic forwarding, and
  nested functions through shared selection. Preserve lexical shadowing and member ambiguity.
- Extend the runnable sample with transitive operations and forwarding to weaker constraints.

#### S5: Integrate editors, documentation, and verification

- **Status:** complete — implementation, compiler/editor verification, actual installations,
  independent review, source/test tours, and Phase 1 signoff are complete.
- Extend LSP hover, definition, references, implementation navigation, and semantic tokens to
  protocol constraint references. Preserve written locations through specialization and include
  constraints in AST pretty-printing.
- Share one Tree-sitter clause grammar across protocol and top-level/local function headers.
  Update Zed highlighting, corpus/recovery tests, and generated parser artifacts. This includes
  the existing function-constraint grammar gap.
- Support contextual clauses in VS Code and Neovim syntax, install all three editor extensions,
  and verify their installed syntax and the shared language server.
- Update the language reference and compiler design. Verify the runnable prerequisite sample
  alongside editor integration.
- Complete verification, review, and the source/test code tour before Phase 1 signoff.

### Phase 2: Member-local type variables

Functions and operators use the same rules. The protocol header binds its one type parameter.
Each member signature binds its other variables in first-appearance order through parameters and
result. Its `where` clause constrains only those local variables. An instance inherits the types
and constraints, may repeat annotations using the signature's variable names, and cannot write
a member `where` clause. The body can use the local variables when annotations are omitted.

1. **complete — Bind signatures and check instances.** Share binder collection with generic
   functions. Resolve an instance against its signature in either declaration order, substitute
   the protocol parameter, and check the generic implementation even when unused.
2. **complete — Select and specialize calls.** Infer the protocol parameter and member arguments
   from arguments, expected types, and local callable uses. Explicit type arguments supply all
   member-local variables in the signature's order. Preserve the signature, protocol type, and
   member arguments through generic forwarding. Select the instance binding and specialize it
   with the ordinary function machinery, including function values and partial calls.
3. **complete — Integrate editors and documentation.** AST printing, the language server,
   and editor grammars support written member constraints and variables. The language reference
   and compiler design document the shared rules. The runnable example and editor, benchmark,
   and memory checks passed. Independent review and the source and test tour are complete.

The Phase 2 design and all three stages are approved, with review and signoff between stages.

## Verification

Phase 1 coverage must include valid and transitive prerequisites, unknown protocols, invalid
constraint targets, missing prerequisite instances, and declaration-order independence. Cover
rejection of cycles and written duplicates, and acceptance of overlapping transitive paths.
Verify declaration errors independently of usage. Verify prerequisite operations in member bodies
and generic callers, including direct calls, callable values, and partial applications.

Phase 2 coverage must include variable scope, implementation/signature mismatches, missing local
requirements, inferred and explicit type arguments, callable values, partial applications, and
specialization according to the approved semantics. Preserve source locations and navigation.

Relevant existing suites include
[ProtocolParserTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/ProtocolParserTests.scala),
[ProtocolCheckerTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolCheckerTests.scala),
[ProtocolSelectionTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolSelectionTests.scala),
and [GenericTypeCheckingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeCheckingTests.scala).
Additional acceptance cases include rejection of repeated variable entries, parser recovery,
binder identity, invalid prerequisite protocols, unaffected siblings, aliases and distinct nominal types, duplicate
detection after rejection, repeated checking, nested scopes, lexical shadowing, member ambiguity,
concrete specialization targets, evaluation order, ownership, and editor source locations.

At each intermediate stage, run formatting and linting, `sbtn testFull`, the smoke harness,
publish the verified compiler, and run the stage's sample with installed `mmlc` at O0/O3.
Before each final phase handoff, run formatting and linting,
`sbtn testFull`, smoke tests, publish the verified compiler, benchmarks, the memory harness, and
`bash tooling/zed/test.sh`. Run `sbtn` operations sequentially. Complete local post-chores,
QA enforcement, and independent review under [coding rules](../coding-rules.md).

### S1 verification — 2026-11-15

- Focused parser and resolution suites passed: `ProtocolParserTests`,
  `GenericFunctionParserTests`, `ProtocolTypeResolverTests`, and `GenericTypeResolutionTests`
  through `sbtn testOnly` with their fully qualified class names. Result: 90 tests passed,
  zero failures or errors.
- `sbtn 'scalafmtAll;scalafixAll;testFull'` passed after adding the CLI/LSP diagnostic regression:
  2,872 compiler-library tests and 9 CLI tests passed, zero failures or errors. Formatting and
  linting passed. The build emitted its multiple-main-classes discovery notice; no Scala source
  warning was emitted.
- `git diff --check` passed. Focused tracking consistency review found no actionable findings.
- Parser tests cover retained prerequisite syntax and source locations, empty/malformed clauses,
  recovery with missing equals and following declarations, and unsupported member syntax.
  Resolution tests cover forward references, binder isolation, independent invalid-variable and
  unknown-protocol errors, stable repeated resolution, and CLI/LSP diagnostic source locations.
- S1 verification checkpoint: publication and smoke checks were not performed with this stage.
  Full Phase 1 post-chores, independent review, benchmark/memory harnesses, and editor integration
  checks remain for the compiler handoff.
  No prerequisite enforcement or implied-requirement runtime behavior is claimed by S1.

### S2 verification — 2026-11-15

- `ProtocolPrerequisites` validates resolved protocol IDs using immutable traversal state. Written
  duplicates and cycles reject their declarations; cycle diagnostics retain the closed dependency
  path and the closing reference's source location. Valid protocols receive a prerequisite-first
  order and deduplicated transitive requirements in `ProtocolRegistry`.
- `ProtocolRegistry.implies` accepts self and transitive requirements only for accepted protocols.
  Rejected protocols and signatures retain original diagnostic causes. Constraint resolution
  records its causes on written entries; invalid signature types also reject their protocol.
  Dependent declarations inherit rejection without internal missing-registry errors.
- The shared resolver rejects repeated variable entries in protocol, top-level function, and
  local function clauses, including entries with different requirements. The diagnostic points
  at the repeated variable and recommends `&`. Its cause rejects dependent protocols as well.
- Focused verification passed with 109 tests, zero failures or errors:

  ```sh
  sbtn 'testOnly mml.mmlclib.semantic.ProtocolPrerequisiteTests mml.mmlclib.semantic.GenericTypeResolutionTests mml.mmlclib.semantic.ProtocolTypeResolverTests mml.mmlclib.parser.ProtocolParserTests mml.mmlclib.parser.GenericFunctionParserTests'
  ```

- `sbtn 'scalafmtAll;scalafixAll;testFull'` passed: 2,892 compiler-library tests and 9 CLI tests,
  zero failures or errors. Formatting and linting passed. The build emitted its multiple-main-classes
  discovery notice; no Scala source warning was emitted.
- [ProtocolPrerequisiteTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolPrerequisiteTests.scala)
  covers declaration-order independence, diamond dependencies, explicit/transitive overlap,
  rejection of repeated variable entries and their dependants, self and indirect cycles,
  a cycle cross-edge, invalid target binders, invalid prerequisite signatures, transitive and
  combined failure causes, stable repeated checking,
  CLI/LSP cycle locations, and a valid same-named member alongside rejected protocols.
  [ProtocolRegistryTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolRegistryTests.scala)
  covers self implication and exclusion of rejected protocol/signature identities.
  [GenericTypeResolutionTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeResolutionTests.scala)
  covers adjacent and nonadjacent repeated variables, local functions using enclosing variables,
  and exact CLI/LSP diagnostic locations. Distinct variables and separate clauses remain valid.
- The runnable [protocol-constraints sample](../../mml/samples/protocol-constraints.mml) covers
  forward declarations, shared prerequisites, explicit/transitive overlap, direct calls, function
  values, partial application, and generic forwarding with the same `Render` constraint.
  `sbtn 'run run -O0 mml/samples/protocol-constraints.mml;run run -O3 mml/samples/protocol-constraints.mml'`
  passed with the documented output at both optimization levels. Its protocol header has one
  `'T: Describe & Display & ToString` entry.
- `./tests/smoke/run.sh all` passed all eight programs. The sandboxed invocation could not
  access sbt's boot lock; the complete rerun with the required filesystem access passed.
- `sbtn mmlcPublishLocal` installed the compiler and launcher. Installed `mmlc run -O0` and
  `mmlc run -O3` on the sample each produced exactly:

  ```text
  direct: number 42 [42]
  function value: number 7 [7]
  partial: number 9 [9]
  generic: number 11 [11]
  ```

- Each of the seven numbered sample counterexamples was uncommented and compiled independently
  with installed `mmlc`: a duplicate protocol requirement, a repeated variable entry with distinct
  requirements, self and indirect cycles, an unbound variable, an unknown protocol, and same-name
  member ambiguity all produced the intended diagnostic. The repeated entry reported
  `Duplicate where entry for 'T; combine its requirements with &`. The ambiguity reported both
  `BaseLabel.label` and `FancyLabel.label`. Additional installed-compiler checks rejected repeated
  entries in unused top-level and local functions with the same duplicate-variable diagnostic.
- `git diff --check` passed. The current-agent QA check found no actionable rule-compliance
  findings; focused tracking consistency review found no actionable findings.
- S2 is an intermediate artifact. Instance prerequisite enforcement belongs to S3 and generic
  use of implied requirements belongs to S4. Full Phase 1 post-chores, independent review,
  benchmark/memory harnesses, and editor integration checks remain for S5. Tree-sitter support
  for `where` clauses is still part of S5; publishing the compiler updates the LSP executable
  but does not update Zed's grammar.

### S3 verification — 2026-11-15

- `ProtocolChecker` validates direct instance prerequisites after all member checks and lowering,
  using the registry's prerequisite-first protocol order. Canonical type identity lets aliases
  share prerequisite instances while preserving distinct nominal types. Checks include unused
  instances and declarations with other member-contract failures.
- Missing prerequisites receive declaration errors. Rejected prerequisites carry their original
  diagnostic roots through dependent instances and member uses. `ProtocolRegistry.rejectInstance`
  combines member and prerequisite causes while preserving reserved identities. Instances with
  failed written member-type resolution cannot supply evidence. Lowered bodies remain in the
  module for ordinary type checking, including unused bodies and repeated checker execution.
- Focused verification passed with 135 tests, zero failures or errors:

  ```sh
  sbtn 'testOnly mml.mmlclib.semantic.ProtocolInstancePrerequisiteTests mml.mmlclib.semantic.ProtocolCheckerTests mml.mmlclib.semantic.ProtocolRegistryTests mml.mmlclib.semantic.ProtocolPrerequisiteTests'
  ```

- [ProtocolInstancePrerequisiteTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolInstancePrerequisiteTests.scala)
  adds 17 tests covering protocol and instance order, diamonds with explicit overlap, source order,
  binding/parameter identities, one executable body per member, and repeated checking. Negative
  cases cover unused instances, missing and malformed prerequisites, transitive and combined
  causes, unresolved member annotations, aliases, distinct nominal types, duplicate reservation,
  usable sibling instances, dependent member uses, and continued checking of unused bodies.
  CLI/LSP diagnostics identify the dependent instance's written protocol reference.
- `sbtn 'scalafmtAll;scalafixAll;testFull'` passed: 2,909 compiler-library tests and 9 CLI tests,
  zero failures or errors. Formatting and linting passed. The final 17-test suite rerun passed
  after tightening its diagnostic identity assertions. The build emitted its multiple-main-classes
  discovery notice; no Scala source warning was emitted.
- `sbtn 'run run -O0 mml/samples/protocol-constraints.mml;run run -O3 mml/samples/protocol-constraints.mml'`
  passed with the four documented output lines. `./tests/smoke/run.sh all` passed all eight programs.
- `sbtn 'scalafmtAll;mmlcPublishLocal'` published the verified compiler. Installed `mmlc run -O0`
  and `mmlc run -O3` on the sample each produced exactly its documented output. All ten numbered
  counterexamples were compiled independently with installed `mmlc` and failed with their intended
  diagnostics. New examples reject unused `Stamp Bool` without `Describe Bool`, malformed `Tag Int`
  required by `Tagged Int`, and the `Leaf Bool` → `Middle Bool` → absent `Root Bool` chain.
- `git diff --check` passed. Current-agent QA and focused tracking consistency checks found no
  actionable findings.
- S3 is an intermediate artifact. S4 supplies implied requirements to generic code. S5 retains
  editor/Tree-sitter integration and final Phase 1 benchmarks, memory harness, Zed checks,
  post-chores, QA enforcement, and independent review. No runtime dictionaries or ABI changes
  are introduced by instance prerequisite checking.

### S4 verification — 2026-11-15

- Abstract member selection and generic-call requirement checking share the registry's validated
  implication query. Requirements match the variable's binder ID; accepted protocols supply
  themselves and their transitive prerequisites. Unregistered and rejected protocols supply
  no abstract evidence. Written constraint lists and locations remain unchanged.
- Abstract selections retain the protocol signature ID, substituted callable type, and
  `Ref.protocolType`. The existing monomorphizer resolves those signatures to the concrete
  prerequisite implementation. Generic ambiguity diagnostics include the matching abstract
  signatures' protocol names and instantiated types, with no preference among members.
- [ProtocolImpliedRequirementTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolImpliedRequirementTests.scala)
  contains 24 tests covering direct and transitive operations, diamond dependencies, inferred
  and annotated callable values and partials, operators, callbacks, explicit/inferred generic
  forwarding, local functions, separate variable identities, missing evidence, lexical shadowing,
  and ambiguity. Tests assert unchanged written constraints, retained signature IDs and source
  locations, and concrete specialization targets with fresh parameter IDs.
  [ProtocolConstraintTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolConstraintTests.scala)
  also checks that unregistered and rejected protocols cannot justify operations or forwarding.
- [ProtocolPrerequisiteExecutionTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/ProtocolPrerequisiteExecutionTests.scala)
  passes at O0 and O3 with ASan and leak checking. Exact stdout verifies argument order and one
  execution per call through direct members, callable values, partials, weaker generic functions,
  nested functions, and operators. Invoked and dropped consuming partials retain correct ownership;
  a separate negative case rejects use after a consuming inherited call at the written location.
- Focused verification passed with 157 tests, zero failures or errors:

  ```sh
  sbtn 'scalafmtAll;testOnly mml.mmlclib.semantic.ProtocolConstraintTests mml.mmlclib.semantic.ProtocolImpliedRequirementTests mml.mmlclib.codegen.ProtocolPrerequisiteExecutionTests mml.mmlclib.semantic.GenericTypeCheckingTests mml.mmlclib.semantic.ProtocolSelectionTests mml.mmlclib.semantic.FnMonomorphizerTests'
  ```

- Final `sbtn 'scalafmtAll;scalafixAll;testFull;run run -O0 mml/samples/protocol-constraints.mml;run run -O3 mml/samples/protocol-constraints.mml'`
  passed: 2,937 compiler-library tests and 9 CLI tests, zero failures or errors. Formatting and
  linting passed. The build emitted its multiple-main-classes discovery notice and sample runs
  emitted the JVM's Scala LazyVals Unsafe warning; no Scala source warning was emitted.
- `./tests/smoke/run.sh all` passed all eight programs. `sbtn mmlcPublishLocal` installed the
  verified compiler and launcher. Installed `mmlc run -O0` and `mmlc run -O3` each produced
  exactly the sample's documented output:

  ```text
  direct: number 42 [42]
  function value: number 7 [7]
  partial: number 9 [9]
  generic: number 11 [11]
  implied: number 13 [13] (13)
  forwarded: 17
  nested: 19
  ```

- All thirteen numbered sample counterexamples were uncommented and compiled independently
  with installed `mmlc`. Each failed with its intended diagnostic. The generic cases report
  missing `ToString` evidence for `'T`, list both `GeneralLabel.label` and `FancyLabel.label`
  for ambiguity, and reject borrowing `'T`'s evidence for `'U`. The repeated-variable case
  continues to report `Duplicate where entry for 'T; combine its requirements with &`.
- `git diff --check` passed. Current-agent QA and focused tracking consistency checks found
  no actionable findings. S4 is an intermediate artifact; full language-reference and editor
  integration, Tree-sitter/Zed checks, benchmarks, the memory harness, post-chores, QA enforcement,
  and independent review remain in S5. Prerequisites introduce no runtime dictionaries or ABI changes.

### S5 verification — 2026-11-16

- Protocol and generic-function constraints share hover, definition, reference, implementation,
  token, and AST-printing support. Eight new regressions cover exact locations, navigation at
  name ends, forward and unresolved references, inherited members, nested specialization,
  token uniqueness, ordinary local-function annotations, and framed JSON-RPC responses.
- Local-function binding types have derived origins; their lambda's written annotations retain
  source locations. The companion annotation-navigation repair is tracked as
  [local-binding type navigation bug](local-binding-type-navigation.md).
- Final `sbtn 'scalafmtAll;scalafixAll;testOnly mml.mmlclib.lsp.SemanticTokensTests;testFull'`
  passed all 18 focused annotation tests, all 2,945 compiler-library tests, and
  all 9 CLI tests, with zero failures or errors. This includes all eight new S5 tests and the
  strengthened inherited-consumption regression. Formatting and linting passed, with no Scala
  source warnings. The build prints its multiple-main-classes discovery notice.
- The initial full run exposed 13 stale IR assertion failures in `FunctionSignatureTest`,
  `ClosureCodegenTest`, `TailRecursionLoopificationTest`, `LambdaEquivalenceCodegenTest`, and
  `LocalCallableCodegenTests`. The authorized companion repair aligns their 14 expectations with
  the separately committed Int template's `add nsw i32`, preserving the other assertions.
- `./tests/smoke/run.sh all` passed 8/8. `sbtn mmlcPublishLocal` installed the compiler and launcher.
  Installed `mmlc run -O0` and `mmlc run -O3` on `mml/samples/protocol-constraints.mml` each matched
  the seven-line output documented in S4. `mmlc ast` includes the written protocol prerequisites.
- `python3 tooling/test-protocol-navigation.py` passed the installed server contract: 13 named
  navigation responses, 9 operator responses, 9 local-binding responses, and five selection-end
  navigation chains. Neovim's native client test passes exact destinations, commands, mappings,
  and navigation after unsaved edits.
- `bash tooling/zed/test.sh` passed 21 corpus cases, ten reference programs, and all four Zed
  queries. The five new corpus cases cover shared clauses, contextual `where`, comments,
  multiple requirements/variables, and recovery into later declarations. Capture inspection
  confirms ordinary `where` identifiers retain variable/function roles while clause `where`
  receives keyword highlighting. Tree-sitter CLI version: 0.27.0.
- Actual editor installations succeeded with `tooling/install-zed-extension.sh`,
  `tooling/install-nvim-syntax.sh`, and VS Code's `npm run build`, `vsce package`, and
  `code --install-extension --force`. Eighteen installed files match their source/build bytes;
  VS Code's manifest matches apart from editor-added installation metadata.
- VS Code's six tests pass. Real TextMate and Neovim syntax engines each pass the shared
  25-line fixture with 31 keyword/identifier assertions, including installed-profile runs.
  TextMate also preserves declaration and numeric scopes after three malformed function headers.
  Existing editor windows need reload/restart. Zed and VS Code GUI behavior was not exercised.
- `make -C benchmark clean` and `make -C benchmark mml` rebuilt all 37 MML targets. This is
  compilation verification, not a timing comparison.
- `./tests/mem/run.sh all` passed all 52 fixtures with ASan+LSan at O0 (88 seconds).
- Independent Phase 1 review completed all four dedicated checks and six separate claim
  verifications. It confirmed one ownership-test oracle gap and five documentation findings;
  no production defect was established. Corrections place reuse inside the constrained generic
  body and assert both move and reuse locations, and clarify the optional parser, graph traversal,
  registry failure, member-lowering, and callable-alternative contracts. Fresh narrow review of
  these corrections and the companion assertion repair completed all four dedicated checks with
  no actionable findings or coverage gaps. The corrections change comments and tests only.
- Independent review of the editor completion and companion navigation repair completed all four
  dedicated checks and four separate claim verifications. The confirmed findings covered TextMate
  recovery after a missing parenthesis, Neovim comments before a constraint colon, and two missing
  explanations. All four corrections pass a fresh narrow review with four dedicated checks and
  no actionable findings or coverage gaps. Before-image tests establish that the new grammar
  regressions fail before the corrections and pass after them.
- Current-agent QA enforcement found no additional compliance misses. Focused tracking consistency
  and local-link checks pass. The S5 source/test, editor-completion, and correction tours are delivered.
- Host: Darwin arm64; Homebrew LLVM/Clang 23.1.1; GraalVM Community Java 25.0.4.1.
  Prerequisites introduce no ABI change, so the ABI-specific Linux container gate does not apply.
- Sandbox access to sbt's boot lock and Tree-sitter's WebAssembly-toolchain download was unavailable;
  reruns with the required access succeeded. No verification waiver is claimed.

### Phase 2 Stage 1 verification — 2026-11-18

- [ProtocolMemberVariableTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolMemberVariableTests.scala)
  passed all six cases. They cover independent function and operator binders, forward instance
  declarations, inherited constraints in unused bodies, result-only variables, binder order, and
  diagnostics for renamed variables, narrowed annotations, misplaced or missing requirements.
- `sbtn 'scalafmtAll;scalafixAll;testFull'` passed with 2,951 library tests and 9 CLI tests.
  `./tests/smoke/run.sh all` passed all eight programs. `sbtn mmlcPublishLocal` installed the
  verified compiler. The [stage sample](../../mml/samples/protocol-member-variables.mml) printed
  exactly `7` followed by a newline with installed `mmlc run` at O0 and O3. Its generic members
  are checked at declaration time. The executed call uses the same instance's monomorphic member.

### Phase 2 Stage 2 verification — 2026-11-18

- The 24 member-call cases cover explicit and inferred arguments, result-only variables,
  contextual callbacks, named and operator values, partial calls, generic forwarding,
  constraint failures, shadowing, ambiguity, and infix constructor operands. The specialization
  test verifies reuse for the same instance binding and local arguments and distinct generated
  bindings when either changes.
- Native O0 and O3 ASan/LSan tests check exact evaluation order. They invoke one partial
  application that captures an owned string and drop another without calling it.
- Formatting, linting, and `sbtn testFull` passed with 2,978 compiler-library tests and 9 CLI tests.
- The smoke harness passed all eight programs. After `sbtn mmlcPublishLocal`, installed
  `mmlc run -O0` and `mmlc run -O3` on `mml/samples/protocol-member-variables.mml` both
  produced exactly these six lines:

  ```text
  7: true
  7: 42
  8: ready
  9: false
  false
  7
  ```

### Phase 2 Stage 3 verification — 2026-11-20

- The navigation regressions distinguish sibling binders with the same spelling and trace
  explicit arguments in a body with omitted parameter annotations. They check references with
  and without the declaration, hover ranges, signature and implementation destinations after
  specialization, and requirements written on both kinds of member. Semantic-token tests
  check exact ranges and reject duplicate tokens from inherited constraints.
- Formatting and linting passed. The full suite passed all 3,004 compiler-library tests and
  all 9 CLI tests. The first full run found a change to hover on constructor annotations.
  The correction preserves the existing constructor span and hover behavior.
- All eight smoke programs passed before local publication. The installed compiler produced the
  Stage 2 sample's exact six lines at O0 and O3. Its AST lists `'U` on both member signatures
  and `where 'U: ToString` on `label`. The language reference's three application examples
  each produced `"7"` at O0 and O3. The final smoke run required one full retry after the
  sbt server exhausted its file descriptors while launching Git. All eight passed on a fresh server.
- The installed language server passed 41 navigation checks and five selection chains,
  including ten checks against the runnable member sample. Four additional checks on
  `keep 'A key value` distinguished the written name from the caller's explicit type variable.
  Neovim's native client passed its command, mapping, destination, and unsaved-edit checks.
- Tree-sitter passed 22 corpus cases and parsed all 11 reference programs with the four Zed
  queries. The operator signature query distinguished the parameter named `where` from the
  clause keyword. All six VS Code tests passed. The packaged VS Code grammar and installed
  Neovim syntax also passed the shared fixture in isolated profiles. The Zed grammar and
  adapter built and installed in an isolated profile. GUI behavior was not exercised.
- `make -C benchmark clean` and `make -C benchmark mml` rebuilt all 37 MML targets.
  `./tests/mem/run.sh all` passed all 52 ASan/LSan fixtures at O0. The full suite includes
  the named and operator evaluation-order and consuming-partial regressions at O0 and O3.
  Verification ran on Darwin arm64 with Homebrew Clang 23.1.1. The change uses ordinary
  function specialization and introduces no ABI change.
- Independent Phase 2 review completed separate code style, test quality, human readability,
  and code documentation checks. Fifteen claims received separate independent verification.
  Fourteen findings were confirmed. Four covered overload prefix lengths, generic callbacks on
  stored partials, explicit-argument navigation, and located receiver-conflict diagnostics.
  Two strengthened tests make operand order observable and assert the selected operator's
  implementation ID. The other eight cover shared callable substitution, source documentation,
  and Scala formatting.
- Regression tests exercise two viable explicit-prefix lengths, a genuinely ambiguous call,
  and named and operator partials receiving a generic callback. Native tests observe both
  operands and the callback, and cover use and disposal of a consuming partial at O0 and O3.
- Follow-up review reproduced three cases. A longer overload hid the constructor argument
  that determined an unannotated lambda parameter. Two stored members lost receiver diagnostics
  when a call applied a returned function or passed a generic callback. Lambda inference now
  uses the same candidate operands as final selection. Conflict reporting uses the generic
  solver to recover receiver requirements and their written sites. The tests check the visible
  initializer error, both receiver locations, and the missing instance at the second call.
  The named direct call also observes both operands at O0 and O3.
- Further diagnostic checks covered repeated receiver occurrences within one callback, a
  member-local type shared between two callbacks, and an overload rejected by a fixed
  parameter. Conflict recovery checks complete calls with the generic solver. It records
  receiver types at their written sites while enforcing the other constraints across uses.
  A missing-instance report covers the dependent callback inference message within that call.
  The tests verify the complete visible diagnostic list and both instantiated choices of an
  ambiguous constructor prefix.
- Call-order controls cover generic identity and a member that returns a callable receiver.
  An overload requiring a separate protocol for its member-local type contributes to the
  reported conflict only when its known local argument satisfies that requirement.
- A nested identity callback can make a shared member-local type depend on the receiver.
  Diagnostic inference keeps a separate receiver binder for each use and checks shared type
  equalities after resolving the receivers. Tests cover both operand orders, both invocation
  orders, and matching receiver controls. A callback requiring equal input and output types
  preserves that equality when a later operand fixes the receiver. Replacing the callback at
  the Float use can leave the shared local type consistent, in which case the missing instance
  belongs at that call.
- When one callback demands two receiver types, the diagnostic follows a callback type variable
  to its concrete type. The generic and concrete callback tests both locate the conflicting
  `Int` and `Float` demands on the callback argument and the missing `Probe Float` instance
  on the call. Their same-type controls compile.
- The final independent review completed the code style, test quality, readability, and
  documentation checks. Shortening the diagnostic range for `1.5` to its first character
  failed the corrected assertion for both callback shapes in both operand and call orders. All 28
  tests in `ProtocolConflictEvidenceTests` passed. No actionable review findings remain.

## Risks / Notes

- Builds on [Generic functions](generic-functions.md), including declared requirements and
  additive specialization.
- Instance lowering substitutes the protocol parameter. Member-local arguments belong to ordinary
  generic function specialization. A forwarded member call needs both pieces of information.

## Signoff

- Protocol-level requirement placement and phase order: approved.
- Phase 1 prerequisite rules: approved.
- Phase 1 implementation plan (S1–S5): approved, with review and signoff between stages.
- S1 stage signoff: granted.
- S2 stage signoff: granted.
- S3 stage signoff: granted.
- S4 stage signoff: granted.
- S5 stage and Phase 1 signoff: granted.
- Phase 2 design and implementation plan: approved. Stage 1 and Stage 2 signoff: granted.
- The Author signed off Phase 2 Stage 3 and the completed task on 2026-11-20.

## Task Working Memory

Both phases are complete. The [prerequisite sample](../../mml/samples/protocol-constraints.mml)
shows how generic callers use a protocol's prerequisites. The
[member-local sample](../../mml/samples/protocol-member-variables.mml) stores a partial
application of `label` and calls `==>` with inferred and explicit result types.
