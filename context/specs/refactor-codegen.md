# Codegen ABI Strategy Refactor

## Observation: Mixed ABI Concerns (current state)
- `emitter/abis/AbiLowering.scala` imports both `x86_64` and `aarch64` rules, keeps a single `rules` list, and pattern-matches on `state.targetAbi` for decisions like `needsSretReturn` (lines 3-147). Helpers such as `isPackableAarch64` and `shouldSplitX86_64` live together, so each edit risks touching the other ABI.
- `emitter/Module.scala` lowers native param/return types by calling the shared helpers; comments reference x86_64 even though behavior depends on `targetAbi` (lines ~236-249).
- `emitter/expression/Applications.scala` repeats the sret check with `needsSretReturn` and carries an x86_64-specific comment (lines ~384-425).
- `CodeGenState` stores only a `TargetAbi` enum; the lowering helpers re-inspect that enum repeatedly instead of using an ABI-local strategy object.
- Tests and TODOs for AArch64 large-struct/HFA behavior are forced to land in the shared helpers, increasing coupling.

**Extent:** The mixing is localized to ~3 call sites plus the shared `AbiLowering.scala` helper module; structs rules already live under per-ABI packages, but selection and utilities are centralized and branch on `TargetAbi`.

## Goals
- Choose ABI once per compilation and route all ABI-sensitive decisions through a concrete strategy (no scattered `if targetAbi == ...` checks).
- Make each ABI self-contained: rules, helpers, thresholds, and TODOs live in that ABI’s module.
- Reduce accidental regressions when touching one ABI; enable adding new ABIs without modifying shared code.
- Keep existing behavior (x86_64 split/indirect, aarch64 pack/indirect, sret rules) intact while reshaping structure.
- Fold the tracked AArch64 ABI TODOs into this effort:
  - Detect HFAs (≤4 floats/doubles) and keep them in registers (no indirect lowering).
  - Emit `ptr byval(%struct.T) align 8` for AArch64 large-struct params; adjust call sites to pass byval pointers.
  - Add `FunctionSignatureTest` coverage for HFA params/returns and the byval attribute, comparing against clang.

## Non-goals
- No new ABI semantics yet; parity with current x86_64 and AArch64 behavior is required.
- No build/CLI changes beyond selecting the strategy (existing `--target-abi` hint continues to work).

## Proposed Design
- Introduce `AbiStrategy` interface (new file, e.g., `emitter/abis/AbiStrategy.scala`):
  - `lowerParamTypes(paramTypes: List[String], state: CodeGenState): List[String]`
  - `lowerArgs(args: List[(String, String)], state: CodeGenState): (List[(String, String)], CodeGenState)`
  - `needsSret(returnType: String, state: CodeGenState): Boolean`
  - `lowerReturnType(returnType: String, state: CodeGenState): (String, Option[String])`
  - `emitSretCall(...)` helper (or delegate to shared implementation if identical across ABIs).
  - Optional: `name` for logging/tests.
- Provide concrete strategies:
  - `X86_64AbiStrategy` encapsulating `SplitSmallStructs`, `LargeStructByval`, sret rule `!shouldSplitX86_64`.
  - `AArch64AbiStrategy` encapsulating `PackTwoI64Structs`, `LargeStructIndirect`, sret rule `isLargeStructAarch64`.
  - `DefaultAbiStrategy` as no-op passthrough (current behavior of `TargetAbi.Default`).
- Bind strategy early:
  - In `emitModule`, map `TargetAbi` -> `AbiStrategy` and stash it in `CodeGenState` (add `abi: AbiStrategy` field so callers do not re-match enums).
  - Remove `rulesFor(state)` and target-specific matches in shared helpers; strategy owns its rules and helpers.
- Narrow shared surface:
  - Keep target-agnostic utilities (`getStructFieldTypes`, size helpers) in a small, separate object (e.g., `StructUtils`).
  - Move ABI-specific helpers (`isPackableAarch64`, thresholds) inside their strategy implementation to stop leaking between ABIs.
- Call-site simplification:
  - `Module.emitBndLambda` calls `state.abi.lowerParamTypes` / `lowerReturnType`.
  - `Applications.compileStandardCall` calls `state.abi.needsSret` (and `emitSretCall`) without commenting on a specific architecture.
  - Any future ABI-dependent logic (e.g., HFAs, byval attrs) extends only the relevant strategy.

## Migration Steps
1) Add `AbiStrategy` and implementations for x86_64, AArch64, Default; relocate existing rule objects under their strategy.
2) Extend `CodeGenState` with `abi: AbiStrategy`; instantiate it in `emitModule` when creating the initial state.
3) Replace uses of `lowerNativeParamTypes`, `lowerNativeArgs`, `lowerNativeReturnType`, `needsSretReturn` with calls to the strategy; delete the enum pattern matches in `AbiLowering.scala`.
4) Keep `StructLoweringRule` trait but scope rule registration per strategy (each strategy returns its rule list).
5) Update tests/fixtures that assert emitted signatures to ensure they cover both strategies (x86_64 + AArch64), and add a regression for existing AArch64 TODOs (HFA/byval) once implemented inside the AArch64 strategy.
6) Clean up comments/docs to remove architecture-specific notes from shared call sites.

## Risks / Open Questions
- State threading: `CodeGenState` currently holds `TargetAbi`; switching to a strategy field touches many constructor sites—verify no default `copy` calls break.
- AArch64 HFAs and byval attributes (from tracking) are still TODOs; decide whether to land them during the refactor or immediately after to avoid dual churn.
- Default ABI behavior: today `Default` effectively behaves like “no lowering.” Confirm desired semantics or map `Default` to host strategy for better safety.

## Validation
- Golden IR tests per ABI for: small struct split, large struct indirect/byval, sret returns, and native call sites.
- Round-trip interop tests against clang-produced IR on both ABIs (existing `FunctionSignatureTest` + new AArch64 HFA cases).
- Ensure no cross-ABI code path remains: grep for `TargetAbi.` in emitter after refactor should show only strategy selection at the boundary.
