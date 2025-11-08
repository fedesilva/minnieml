# Spec: Improve Semantic Testing Tools (Option 1)

## Background

Grammar tests already have `parseFailedWithErrors`, which inspects parser-produced `Error` members even when parsing fails. Semantic tests lack an equivalent because `SemanticApi.rewriteModule` throws away the `SemanticPhaseState` whenever errors exist, returning `Left(CompilerError.SemanticErrors(errors))`. As a consequence:

- `CompilerApi.compileString` cannot yield the rewritten `Module` when semantic phases report errors.
- `BaseEffFunSuite` comments out `semFailedWithErrors` and re-implements an ad-hoc pipeline (`semWithState`) that omits phases such as `TypeChecker`, making it unsafe for asserting on real compiler output.

Option 1 fixes this by changing the core API to always return the final semantic state, letting callers decide whether errors should be treated as fatal.

## Goals

1. Preserve existing behavior for production callers (CLI, codegen, etc.): `CompilerApi.compileString` still fails fast when semantic errors exist.
2. Expose the full `SemanticPhaseState` (module + accumulated errors) after running the pipeline, even when errors occurred.
3. Enable test helpers (`BaseEffFunSuite`) to assert on semantic errors and inspect the rewritten module without reimplementing the pipeline.
4. Avoid duplicating the semantic pipeline; keep a single authority (`SemanticApi`).

## Proposed Changes

### 1. SemanticApi always returns the final state

- Change the signature of `SemanticApi.rewriteModule` from `CompilerEffect[Module]` to `CompilerEffect[SemanticPhaseState]`.
- Move the current `EitherT` logic into a helper so that the function always returns `Right(finalState)`; the `errors` field inside the state will carry semantic errors.
- Never convert `finalState.errors.nonEmpty` into a `Left` inside `SemanticApi`. This ensures the state is always available to callers.

```scala
def rewriteModule(module: Module): CompilerEffect[SemanticPhaseState]
```

### 2. CompilerApi enforces fatal vs. non-fatal semantics

- Update `CompilerApi.compileString` to call the new `SemanticApi.rewriteModule`.
- After getting the state, inspect `state.errors`.
  - If empty, return `Right(state.module)` (same as today).
  - If non-empty, return `Left(CompilerError.SemanticErrors(state.errors.toList))`.
- Add a new helper (e.g., `compileState`) that simply returns the state, mirroring `rewriteModule` but preserving public API symmetry.

### 3. Testing helpers gain real semantic introspection

- Replace the ad-hoc `semWithState` helper with a compile-state-backed helper (e.g., `semState`) that returns the rewritten module plus the collected `SemanticError`s.
  - The helper should fail fast if the semantic pipeline itself cannot complete (e.g., parser failure) but otherwise hand tests both the rewritten AST and the error list.
- Update existing semantic tests that currently use `semWithState` to rely on the new helper, then delete `semWithState` to avoid divergence.

### 4. Documentation and migration

- Document the new behavior in `docs/design-and-semantics.md` (semantic pipeline section) and `context/systemPatterns.md` if needed, emphasizing that semantic state now propagates even when errors occur.
- Note tests should rely on the compile-state helper (e.g., `semState`) rather than reimplementing the pipeline.

## Impacts

- **API**: `SemanticApi.rewriteModule` becomes a richer API returning state; `CompilerApi` preserves existing external behavior while offering an optional `compileState`.
- **Tests**: easier authoring of semantic tests since they can inspect real compiler output and error details.
- **Future work**: This foundation enables tooling (e.g., metals MCP) to surface semantic diagnostics without rerunning the pipeline.

## Open Questions

1. Should `SemanticApi` expose both `rewriteModule` (state) and a convenience `rewriteModuleResult` that returns `(Module, List[SemanticError])`? The spec currently keeps only the state-based version to avoid API bloat.
2. Should `CompilerApi.compileState` live in `api` or under `semantic` test utilities? The assumption is yes—public API—to keep parity with parsing APIs.
