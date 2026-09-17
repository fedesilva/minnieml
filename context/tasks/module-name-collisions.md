# Prevent module symbol and output-name collisions

## Metadata

- **Owner:** unassigned
- **Status:** planned
- **Kind:** CORRECTNESS
- **Priority:** HIGH
- **Created:** 2026-09-16
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Define module identity and approve the naming policy.
2. [ ] **planned** — Implement consistent symbol and artifact naming.
3. [ ] **planned** — Verify collision cases, compatibility, and compiler checks.

## Problem

The LLVM mangler uses `lowercase(module) + "_" + member`. Distinct modules can emit
the same public symbol: `FooBar.run` and `Foobar.run` both become `foobar_run`.
Public functions have external linkage, so combining their object files can create
conflicting definitions. Private functions use internal linkage.

Default executable and library filenames also lowercase the module name. Builds into
the same target directory can therefore overwrite another module's output.

Information is lost before mangling too:

- `foo-bar.mml` and `foobar.mml` produce distinct module names (`FooBar` and `Foobar`)
  that collapse under lowercasing.
- `foo-bar.mml` and `foo_bar.mml` both sanitize to `FooBar`.
- Files with the same basename in different directories receive the same module name.

Removing `.toLowerCase` alone does not address all three cases.

## Outcome

Distinct supported module identities produce distinct exported symbols and build artifacts.
Ambiguous source identities receive a clear diagnostic where the naming policy rejects them.
Naming remains deterministic, and definitions, references, and entry points agree.

## Scope

- In scope: source-to-module identity, LLVM symbol mangling, entry-point naming,
  intermediate artifacts, and default executable/library output names.
- In scope: compatibility implications for exported symbols and native callers.
- Out of scope: a full import/package system and unrelated IR cleanup.

## Plan (Approval Gate)

- [ ] Decide which source distinctions define module identity and which are rejected.
  Specify how directory identity works without making symbols depend on checkout location.
- [ ] Choose an unambiguous symbol encoding and consistent artifact naming; assess ABI changes.
- [ ] Apply the approved policy across compiler and API paths, including entry-point lookup.
- [ ] Add regression coverage for case, separators, directory basenames, public linking,
  and output collisions. Verify matching references and definitions.

Approval: pending.

## Verification

Source inspection establishes the naming transformations and linkage rules:

- [CodeGenState.mangleName](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/package.scala)
  lowercases the module prefix.
- [ParserApi.sanitizeModuleName](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/api/ParserApi.scala)
  joins capitalized words split on hyphens, underscores, and spaces.
- [FileOperations.sanitizeFileName](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/FileOperations.scala)
  uses only the filename, without its directory.
- [Module emitter](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/Module.scala)
  gives public functions external linkage and other functions internal linkage.
- [PreCodegenValidator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/PreCodegenValidator.scala)
  separately constructs the lowercased main symbol.
- [LlvmToolchain](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
  lowercases default executable and library names.

No collision compilation or linker reproduction has been run. Those checks and the
applicable compiler verification gates remain pending.

## Risks / Notes

Changing exported names affects native callers and existing compiled objects. A case-only
fix also needs to account for case-insensitive filesystems. Explicitly requested output
paths need a defined overwrite policy separate from default-name generation.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Next action: reproduce the collision cases in isolated output directories, then propose
the module-identity and encoding policy for approval. Preserve the distinction between
source identity, linker symbols, and artifact filenames when defining the fix.
