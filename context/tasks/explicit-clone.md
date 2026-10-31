# Explicit Clone and global ownership transfers

## Metadata

- **Owner:** The Author
- **Status:** complete
- **Kind:** feature
- **Priority:** unassigned
- **Created:** 2026-10-28
- **Target Branch:** dev/explicit-clone
- **External Reference (optional):** None.

## Execution Checklist

1. [x] **complete** — [P7: Integrate explicit Clone and generated instances](#p7-explicit-clone).
2. [x] **complete** — [P8: Require explicit cloning for global ownership transfers](#p8-global-ownership-transfers).
3. [x] **complete** — Complete documentation, compiler verification, and final review.

## Tags

- protocols
- ownership

## Problem

Implicit cloning at global ownership transfers hides duplication. Explicit cloning requires
a compiler-recognized protocol and concrete native and generated instances.

## Outcome

`^` borrows its input and produces an independent owned copy. Ownership sinks require an
explicit copy of global-backed values while ordinary borrowing and scalar copies remain valid.

## Scope

- After Num verifies the protocol system, inject `Clone`, declaring unary `^`. Adapt the
  existing memory-functions phase to generate Clone instances for eligible concrete types,
  reusing its cloning machinery. This does not require a general derivation facility.
  `^value` borrows its input and returns a separate owned value, leaving the input valid.
  Follow the contract in [Memory evolution](../../docs/brainstorming/mem/mem-evolution.md#layer-2-clone-protocol).
  Include the native clone implementations needed by generated instances.
- Remove implicit cloning of globals at ownership sinks. Reject transfers of global-backed
  values into owned storage; the programmer must explicitly use `^` to produce an owned copy.
  This applies to transfers requiring ownership, not ordinary scalar value copies.

General derivation, Unique integration, and broader literal-cloning policy are out of scope.
Follow [memory evolution](../../docs/brainstorming/mem/mem-evolution.md#layer-2-clone-protocol).

## Plan (Approval Gate)

Approval: P7 and P8 implementation scope and delivery order approved. Execute after
[Implement protocols](protocols.md), preserving review and signoff between bounded stages.

### P7: Explicit Clone

- Inject the compiler-recognized Clone protocol with borrowing unary `^`; propose precedence
  95 and right associativity, matching existing prefix operators.
- Register explicit native Clone instances using the existing runtime implementations for
  String, Buffer, Rng, IntArray, StringArray, and FloatArray. Check their allocating-result
  metadata and borrow-input contracts; add missing native support only where required by
  the approved eligible types. Heap representation alone must not imply Clone support.
  Declare ordinary MML helpers as `clone_String`, `clone_Buffer`, `clone_Rng`,
  `clone_IntArray`, `clone_StringArray`, and `clone_FloatArray`. Each helper explicitly names
  its corresponding `__clone_*` C symbol with `@metal:ffi[name="...", mem=alloc]`.
  Native Clone instance bodies call those helpers, for example
  `op ^(value) = clone_String value;;`. Neither the helper's spelling nor the native type's
  representation establishes Clone support. Keep injected declarations and `prelude.mml`
  consistent. Remove name-derived clone selection; generated bodies and internal cloning
  resolve the registered Clone member by identity.
- Adapt memory-function generation to register eligible concrete struct Clone instances and
  their ordinary member bindings. Register signatures before generating bodies so
  nested struct dependencies resolve by identity rather than declaration order.
- Copy scalar fields and invoke registered Clone members for non-scalar fields. Preserve
  rejection of unsupported function-bearing structs and types without cloneable members.
  Include scalar-only struct copies without introducing general protocol derivation or Unique.
- Use the shared instance/member lookup API for explicit Clone calls and the existing internal
  clone machinery. Retain internal helpers where needed for literal promotion; do not remove mixed
  witnesses, conditional cleanup, or unrelated destruction generation.
- Ensure generated and user-supplied Clone instances satisfy ordinary ownership checking
  and return a separate owned result for ownership-tracked types. Native bodies remain trusted
  implementations with runtime verification. Merely returning the borrowed input is invalid.

Exit: explicit cloning of native and nested struct values executes correctly; the source stays
valid and the copy has independent owned storage. Missing support and duplicate explicit/generated
instances fail clearly. Test Clone calls as ordinary callable values and partial applications,
plus cleanup of both source and result under sanitizers.

### P8: Global ownership transfers

- Trace global origin through local aliases, aggregate field projections, conditional results,
  and statically proven helper returns. Do not relabel a global-backed value as owned merely
  because it passes through a local binding or aggregate.
- Reject global-backed alternatives where a consuming parameter, owning field, owning return,
  move capture, or partial-application payload requires ownership. Diagnose the transfer site
  and suggest an explicit `^` copy. Continue accepting ordinary borrowing and scalar copies.
- Remove global-specific implicit clone insertion at those boundaries, including mixed
  alternatives. Preserve literal-only promotion and witness-based moves and cleanup.
- Verify that `^` produces independent ownership and ends the cloned result's global provenance.
  Migrate affected executable examples and fixtures to explicit cloning before transfer.

Exit: paired rejection/acceptance tests cover each boundary, including aliases and nested fields.
Mixed owned/literal cases keep their existing behavior; global alternatives require explicit
cloning only when ownership is demanded. Runtime tests establish that local cleanup never frees
original global storage and explicit copies are cleaned up exactly once.

### Documentation and final verification

Update the language reference, compiler design, memory model, and executable examples for
Clone and explicit global transfers. Follow [coding rules](../coding-rules.md) and
[development tools](../dev-tools.md), including local post-chores, smoke tests, full tests,
publishing, benchmark compilation, memory checks, and independent review. Apply required
Linux checks if ABI/C interoperability changes; offer optional cloud verification when applicable.
Record actual results and resolve scoped findings before signoff.

## Verification

- Compile and execute `^` through generated Clone instances and their native dependencies;
  verify the returned value's ownership and the original value's continued validity.
- Verify native helper symbol mappings and dispatch through declared instances. A native type
  with a conventionally named clone helper but no Clone instance must not gain clone support.
- Reject global-backed values at ownership sinks without implicit cloning; accept explicit
  `^` copies at those sinks, including cases reached through aliases and aggregate fields.

P7 verification (2026-10-29):

- `sbtn 'scalafmtAll; scalafixAll; testFull'`: passed formatting and lint; 2,374 compiler
  tests and nine CLI tests passed, zero failures.
- `./tests/smoke/run.sh all`: eight of eight passed.
- In both Linux containers, `sbtn 'testOnly *AggregateAbiTests *CloneExecutionTests
  *MixedOwnershipCodegenTests *CloneContractTests'`: 29 of 29 passed per architecture. Executed inside
  `mml-linux-arm64` (native aarch64) and `mml-linux-amd64` (emulated x86_64), with
  Ubuntu Clang 20.1.8 and Oracle GraalVM Java 25.0.4.1.1 / GraalVM 25.4.4.1.1.
  These runs predate the root-eligibility and regression-test corrections; the corrections
  do not change ABI lowering, and the container suites were not repeated.
- Clone execution tests cover O0/O3 sanitizer execution, native storage independence,
  nested structs declared out of dependency order, callable values, and partial applications.
  Buffer tests observe both copied contents and independent writes. Semantic regressions cover
  explicit struct instances used by generated outer instances in both declaration orders.
  Capture assertion controls inject premature native-adapter frees and require rejection.
- `sbtn mmlcPublishLocal`: passed; installed the verified compiler locally.
- `make -C benchmark clean` and `make -C benchmark mml`: passed compilation of all benchmark
  configurations. No execution-time performance measurements were taken.
- `./tests/mem/run.sh all`: 52 of 52 ASan/LSan fixtures passed.
- Independent review completed QA/style, test quality, readability, and code documentation checks.
  Six confirmed findings are addressed: root derivation eligibility, premature-free assertion
  coverage, Buffer content observations, and three inaccurate or incomplete comments. Host
  verification passes on the corrections. Fresh narrow re-review completed all four dedicated
  checks with no actionable findings or unresolved claims.
- Optional hosted validation was not run for P7. These results cover P7 only.

P8 verification (2026-10-30):

- `sbtn 'scalafmtAll; scalafixAll; testFull'`: formatting and lint passed; 2,492 compiler
  tests and nine CLI tests passed. Two legacy diagnostic expectations were migrated from
  unavailable-clone errors to global-transfer rejection before the passing full run.
- `GlobalOwnershipTests` covers nine source forms across five owning boundaries with paired
  rejection and explicit-copy cases, plus borrowing, scalar copies, top-level initialization,
  and stored global closure environments. Helper tests cover recursion and shadowing. Static
  global-field/literal returns cover direct, aliased, and alias-chain forms. Named and immediate
  borrowed-parameter controls require the parameter's resolved identity in the return diagnostic.
- `GlobalOwnershipExecutionTests` executes O0/O3 with ASan/LSan, checks output from each owning
  boundary, compares direct and aliased static returns on both branches, and reuses original
  global storage after local copies are destroyed.
- `./tests/smoke/run.sh all`: eight of eight passed.
- `sbtn mmlcPublishLocal`: installed the verified compiler locally.
- `make -C benchmark clean` and `make -C benchmark mml`: all configurations compiled.
  No execution-time performance measurements were taken.
- `./tests/mem/run.sh all`: 52 of 52 ASan/LSan fixtures passed.
- `mmlc run` passed for `mml/samples/person-struct-borrow-global.mml`,
  `mml/samples/person-struct-borrow-global-grouped.mml`, and
  `mml/samples/globals-stress-test.mml`; outputs were one, one, and three `fede` lines.
- No ABI lowering or C interoperability implementation changed; Linux container checks were
  not repeated. Optional hosted validation was not run.
- Independent review completed all four dedicated checks and confirmed two findings: mixed
  global-field/literal helper returns were rejected, and the escape-check exemption needed a
  local explanation. The correction accepts only proven non-owning global/literal alternatives,
  retains owning-return enforcement, and adds semantic and O0/O3 runtime regressions.
  The correction passed full tests, smoke, publishing, benchmark compilation, and memory reruns.
  Fresh narrow review confirmed both fixes and identified a weak negative assertion. That test
  requires the borrowed parameter's resolved diagnostic identity. Fresh narrow re-review completed
  all four dedicated checks with no actionable findings or unresolved claims.
- Before correcting alias origin reads, `sbtn 'testOnly *GlobalOwnershipTests'` passed 109 of
  111 tests; aliased and alias-chain static returns failed while the direct and borrowed-parameter
  controls passed. With exact alias origins and explicit immediate-parameter borrow origins,
  `sbtn 'testOnly *GlobalOwnershipTests *GlobalOwnershipExecutionTests'` passed all 113 tests.
  Formatting, lint, full tests, smoke, local publishing, benchmark compilation, and all 52 memory
  fixtures also passed on the alias correction. Fresh narrow independent review completed primary,
  QA/style, test-quality, readability, and code-documentation checks with no actionable findings
  or unresolved claims. Alias chains have semantic coverage; direct and single-alias returns have
  O0/O3 runtime coverage. Immediate-parameter controls use literal arguments and check rejection
  behavior without isolating origin metadata from dependency-based rejection.
- The global-transfer diagnostic reads `A global value cannot transfer ownership; use an
  explicit ^ copy`. Formatting, lint, and the three affected semantic suites passed all 224
  tests after the wording change; smoke passed eight of eight and local publishing passed.
  The installed CLI emitted the revised wording.
- Closure verification corrected leading package indentation in `GlobalOwnershipTests`, then
  `sbtn 'scalafmtAll; scalafixAll; testFull'` passed formatting, lint, all 2,492 compiler tests,
  and nine CLI tests. Fresh independent review of the wording change completed primary,
  QA/style, test-quality, readability, and code-documentation checks with no actionable findings
  or unresolved claims. This narrow review covers the diagnostic literal and seven assertion
  literals; the ownership implementation has the broader review coverage recorded above.

## Risks / Notes

Global provenance must survive aliases and aggregates. Heap representation alone does not
establish Clone support. Preserve literal promotion, mixed ownership witnesses, and cleanup.
General derivation and [implicit cloning policy](implicit-cloning.md) remain separate tasks.

## Signoff

- Workstream signoff: P7/P8 implementation and final documentation/verification signed off.
- Tracked item completion: complete.
- Commit authorization: granted for task closure; push is not authorized.

## Task Working Memory

P7 and P8 are complete on `dev/explicit-clone`. Explicit Clone uses registered native and generated
instances. Global provenance survives aliases, projections, conditionals, and proven helpers;
owning boundaries require explicit copies. Static returns retain their precise alternatives,
while borrowed parameters remain rejected. Samples and documentation describe these contracts.
Verification and independent review coverage are recorded above, including target limits.

Message-based diagnostic assertions are follow-up work in
[Typed compiler diagnostics](typed-compiler-diagnostics.md). The source/sink fixture matrix in
`modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GlobalOwnershipTests.scala` retains its
`NOTE:QA` about assembling source fragments mentally; fixture readability remains deferred QA
feedback. The note is preserved in the source. These follow-ups do not change P7/P8 semantics.

### Separate runtime finding: StringArray replacement

`ar_str_set` in `modules/mmlc-lib/src/main/resources/mml_runtime.c` assigns the incoming String
without releasing the occupied slot. Replacing element zero of a cloned array containing
`concat "old" " value"` with `concat "new" " value"` leaked the original ten-byte allocation
in the O0 and O3 ASan/LSan fixtures. The allocation stack led through `__clone_StringArray`.
The Clone fixture instead verifies the copied array after its source is destroyed. Repairing
occupied-slot replacement is outside P7 and remains pending.
