## Compile Prelude (Issue #238)

GitHub: `https://github.com/fedesilva/minnieml/issues/238`

Status: Draft scaffold

## Summary

Replace synthesized prelude injection with an MML-authored prelude file,
ingest it as part of compilation, and merge those members into the user module.

This will work like a header inclusion, not like a module system.

Critical dependency: native symbol aliasing must be implemented first; without it,
prelude clone/free declarations cannot be represented safely in MML.

Current source target:
- `modules/mmlc-lib/src/main/resources/prelude.mml`

## Problem Statement

Today, prelude behavior is provided through synthesized injection in
`semantic/package.scala` (~1000 lines of programmatic AST construction).
This makes the source of core definitions less explicit and harder to evolve in MML itself.

Issue #238 requests a shift to an MML-defined prelude and calls out required support for
native symbol naming needed by `__free_*` / `__clone_*` functions.

Current parser and naming rules make this dependency mandatory:
- binding names must start lowercase (`[a-z][a-zA-Z0-9_]*`);
- prelude currently declares `__clone_*` names directly;
- function `@native` parsing currently does not support a symbol-name override attribute.

## Current Synthetic Injection Architecture

### Injection site

`SemanticStage.rewrite()` calls three functions **before all other semantic phases**:

```
injectBasicTypes(module)       → 19 native types + 3 aliases
injectStandardOperators(...)   → ~27 operators as Bnd(Lambda(NativeImpl))
injectCommonFunctions(...)     → ~41 functions + 5 __free_* + 5 __clone_*
```

Each builds AST nodes with `SourceOrigin.Synth` and IDs using the `stdlib::` prefix.
Definitions are folded into `module.resolvables` and prepended to `module.members`.

### Stdlib ID scheme

All synthetic definitions use IDs of the form `stdlib::<segment>::<name>`:
- `stdlib::typedef::String`, `stdlib::typedef::Int64`, ...
- `stdlib::typealias::Int`, `stdlib::typealias::Byte`, ...
- `stdlib::bnd::concat`, `stdlib::bnd::__free_String`, ...

### Hardcoded stdlib ID consumers

Multiple files outside the injection site depend on these IDs by construction or pattern-match.
These must be preserved or migrated when the injection source changes.

| File | Reference | Purpose |
|------|-----------|---------|
| `semantic/OwnershipAnalyzer.scala` | `UnitTypeId = "stdlib::typedef::Unit"` (L108) | Skip free for Unit returns |
| | `BoolTypeId = "stdlib::typedef::Bool"` (L109) | Skip free for Bool returns |
| | `s"stdlib::bnd::$freeFn"` (L296) | Construct free function ID for lookup |
| | `s"stdlib::bnd::$cloneFn"` (L313) | Construct clone function ID for lookup |
| | `!id.startsWith("stdlib::")` (L315) | Distinguish user-defined vs stdlib functions |
| `semantic/MemoryFunctionGenerator.scala` | `"stdlib::typedef::Unit"` (L20) | Synthetic Unit TypeRef in generated frees |
| | `s"stdlib::bnd::$fnName"` (L39) | Construct stdlib free/clone IDs for resolution |
| `ast/TypeUtils.scala` | `s"stdlib::typedef::$typeName"` (L18) | Type ID lookup |
| | `s"stdlib::typealias::$typeName"` (L19) | Alias ID lookup |
| | `s"__free_$typeName"` / `s"__clone_$typeName"` (L50-62) | Free/clone name derivation by convention |
| `lsp/AstLookup.scala` | `id.exists(_.startsWith("stdlib::"))` (L1209) | Filter stdlib symbols from results |
| `codegen/PreCodegenValidator.scala` | `Set("Unit", "Int64", "Int")` (L11) | Allowed main return types |
| | `name == "StringArray"` (L79) | Main param type check |
| `codegen/emitter/Module.scala` | `"mml_sys_flush"` (L458+) | Hardcoded flush declaration/calls |
| | `name == "StringArray"` (L535) | Type check for entry-point param |
| `codegen/emitter/package.scala` | `"Bool"`/`"Int"`/`"Unit"` → LLVM types (L607-612) | MML-to-LLVM type name mapping |
| `codegen/emitter/FunctionEmitter.scala` | `case "Bool" => Right("i1")` (L650) | Bool-to-LLVM mapping |

### Migration constraint

The `stdlib::` ID prefix acts as a namespace discriminator throughout the compiler.
The simplest migration path is to have the prelude ingest phase assign `stdlib::` IDs and
`SourceOrigin.Synth` to parsed definitions, preserving the existing contract. This avoids
touching every consumer above.

## Goals

- Use the MML prelude file instead of synthesized prelude definitions.
- Parse and ingest prelude definitions during compilation.
- Merge prelude members with the module being compiled.
- Support arbitrary native symbol names through `@native[name="..."]`.
- Bring `__free_*` and `__clone_*` into MML namespace via that native-name mechanism.

## Non-Goals (for first implementation pass)

- Broad redesign of module/import system semantics.
- Performance optimization beyond a minimal working implementation.
- Changes to runtime ABI behavior not required by prelude migration.
- Refactoring or removing hardcoded `stdlib::` ID references (preserve existing contract).

## Dependencies

- Attribute support:
  - `@native` on function/operator bodies needs a `name` argument for arbitrary native symbol
    binding.
  - Example shape from issue: `fn clone_string(s: String): String = @native[name="__clone_String", mem=alloc];`
  - This is required before prelude migration, otherwise clone/free bindings cannot map cleanly to
    runtime symbols.
- Clone mapping model:
  - `TypeUtils.cloneFnFor` currently derives `__clone_<Type>` by convention only.
  - We need an explicit mapping path so ownership and generated memory functions can resolve clone
    names from MML-visible bindings.
- Prelude content source:
  - `modules/mmlc-lib/src/main/resources/prelude.mml`.

## Prelude file status

`prelude.mml` (147 lines) covers all types, operators, and functions from the synthetic
injection. Two gaps remain:

1. **No clone functions** — the 5 `__clone_*` functions are not declared because there is no
   `@native[name="..."]` support yet to map MML-legal names to `__clone_*` C symbols.
2. **Free functions use MML-legal names** — `free_string` instead of `__free_String`. Same
   `@native[name="..."]` mechanism needed to map these to the C runtime symbols.

Once `@native[name="..."]` lands, prelude.mml will declare e.g.:
```
fn clone_string(s: String): String = @native[name="__clone_String", mem=alloc];
fn free_string(~s: String): Unit = @native[name="__free_String"];
```

## Open Questions

1. Where in the pipeline should prelude ingest/merge happen?
   - Current injection runs at the start of `SemanticStage.rewrite()`, before
     `DuplicateNameChecker`.
    - *Answer*: Prelude parse should happen in `IngestStage`.
   - Decision needed: parse once at startup vs. parse per compilation.
    - *Answer*: initial implementation parses per compilation.

2. Can prelude parsing/representation be cached?
   - Issue notes possible caching (e.g., Kryo).
   - Decision can be split into initial no-cache implementation + follow-up optimization.
    - *Answer*: Yes. See second answer to Q1.

3. How should clone mapping be configured for native heap types?
   - Option A: add clone override metadata to native type definitions.
   - Option B: keep clone naming convention and require matching MML-visible declarations.
   - Must preserve ownership analyzer and memory function generator behavior.

   * note sure what this means.

4. How should prelude IDs be assigned?
   - Recommended: prelude ingest assigns `stdlib::<segment>::<name>` IDs to match existing
     contract. All downstream consumers continue to work unchanged.
      - if we do this we need a special indexer, or we need to somehow parameterize
        exiting.
   

## Proposed Phases

### Phase 1 - Native symbol alias support (hard dependency)

- Extend function/operator `@native[...]` parsing to accept `name="..."` argument.
- Carry native symbol override through AST/semantic/codegen so emitted declarations/calls target
  the configured symbol (instead of always using binding name).
- Define clone/free mapping path used by ownership-related resolution (`__free_*` / `__clone_*`).
- Add focused regression tests for parser + resolver + codegen name mapping.

### Phase 2 - Complete prelude.mml

- Add clone function declarations using `@native[name="..."]`.
- Update free function declarations to use `@native[name="..."]` for `__free_*` mapping.
- Verify prelude.mml parses cleanly with the standard parser.

### Phase 3 - Prelude ingest and merge

- Load prelude source from resources.
- Parse prelude into compiler structures.
- Assign `stdlib::` IDs and `SourceOrigin.Synth` to parsed definitions.
- Merge prelude members into compilation module state (prepend to `module.members`,
  fold into `module.resolvables`).
- Replace `injectBasicTypes` / `injectStandardOperators` / `injectCommonFunctions` calls
  in `SemanticStage.rewrite()` with the new prelude ingest path.
- Preserve current user-facing behavior: same types, same operators, same functions,
  same IDs, same resolution order.

### Phase 4 - Pipeline hardening

- Add regression tests for prelude merge semantics and symbol resolution.
- Confirm diagnostics quality when prelude parse or merge fails.
- Verify all hardcoded stdlib ID consumers (see table above) still resolve correctly.
- Run full verification suite: `sbtn "test; scalafmtAll; scalafixAll; mmlcPublishLocal"`,
  benchmarks, memory harness.

### Phase 5 - Cleanup

- Remove synthetic injection code from `semantic/package.scala` (~1000 lines).
- Remove helpers: `injectBasicTypes`, `injectStandardOperators`, `injectCommonFunctions`,
  `mkFn`, `mkBinOp`, `mkUnaryOp`, `stdlibId`, `stdlibTypeId`, etc.

### Phase 6: Evaluation: Migrate some tipes to mml.

  - Investigate if migrating things like buffer and string to pure mml is possible at this stage

### Phase 6 - Caching evaluation (optional follow-up)

- Evaluate whether prelude cache provides meaningful wins.
- If adopted, define cache invalidation and correctness boundaries.




## Acceptance Criteria (Draft)

- Compiler uses `prelude.mml` definitions instead of synthetic prelude injection.
- Prelude definitions are available in normal name resolution paths.
- `@native[name="..."]` is supported for functions/operators and tested.
- Clone/free bindings required by ownership resolve correctly through prelude declarations and map
  to runtime symbols.
- All hardcoded `stdlib::` ID consumers (see table above) continue to resolve correctly
  without modification.
- Regression tests cover:
  - native symbol alias parsing and codegen emission/call behavior,
  - prelude ingest/merge success,
  - conflict behavior (user redefines a prelude name),
  - native-name bindings for clone/free,
  - representative ownership helper resolution,
  - stdlib ID stability (parsed prelude produces same IDs as synthetic injection).

## Risks

- Merge ordering may change name-resolution or shadowing behavior.
- Parser/attribute changes may impact unrelated `@native` handling.
- Ownership-related helper visibility (`__free_*` / `__clone_*`) could regress without targeted tests.
- Prelude parse producing different AST shapes than synthetic injection (e.g., different
  `BindingMeta`, missing `memEffect`, missing `consuming` flags) could cause silent regressions.
  Mitigation: compare parsed prelude AST against synthetic injection output for structural parity.

## Initial Work Checklist

- [ ] Implement `@native[name="..."]` support for function/operator native bodies.
- [ ] Thread native symbol override through semantic/codegen lookup and call/declaration emission.
- [ ] Define and implement clone mapping used by ownership-related code paths.
- [ ] Add clone declarations and fix free declarations in `prelude.mml`.
- [x] Confirm current synthetic prelude injection entry points in compiler pipeline.
- [x] Inventory hardcoded `stdlib::` ID consumers across the codebase.
- [ ] Confirm desired precedence/shadowing rules between user module and prelude members.
- [ ] Implement prelude load+parse+merge path with `stdlib::` ID assignment.
- [ ] Add regression coverage for prelude and native-name behavior.
- [ ] Verify all hardcoded stdlib ID consumers resolve correctly after migration.
- [ ] Remove synthetic injection code after prelude path is stable.
- [ ] Revisit caching after baseline behavior is stable.
