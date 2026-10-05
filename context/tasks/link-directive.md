# Module link directive

```mml
@link["one", static "two", shared "raylib"];
```

One optional directive before all members. One or more library names; semicolon required.
Plain names let the linker decide. `static` requires `.a`; `shared` requires `.so` or `.dylib`.
`.o` output accepts static libraries only; an explicit `shared` entry is a compilation error.

## Metadata

- **Owner:** Author
- **Status:** planned
- **Kind:** feature
- **Priority:** MID
- **Created:** 2026-10-04
- **Target Branch:** unassigned

## Execution Checklist

1. [x] **complete** — Research parsing, compiler state, native declarations, and linking.
2. [ ] **planned** — Implement directive parsing, diagnostics, and state collection.
3. [ ] **planned** — Add executable linking and static partial linking for object output.
4. [ ] **planned** — Verify behavior, document the directive, and obtain signoff.

## Problem

MML can declare external functions with `@native`, but source modules cannot specify the
libraries that supply those functions. The native build pipeline has no source-derived
library inputs.

## Outcome

A module can begin with `@link["one", "two"];`, with optional `static` or `shared` qualifiers
on individual names. The compiler collects the names and modes in compiler state and uses
them at the final native linking step.

## Rules

- A module accepts at most one `@link` directive. It is optional.
- The directive must precede every member and end with a semicolon.
- Its brackets contain one or more comma-separated entries: a nonempty string library name,
  optionally preceded by `static` or `shared`.
  Whitespace and ordinary line comments are allowed around the directive and its entries.
- A second directive, a directive after a member, or one inside an expression is an error.
- Entries are library names, not paths or arbitrary linker arguments.
- The default is linker selection: an unqualified `"foo"` supplies `-lfoo` for executable
  output, letting the linker choose static or shared according to its normal search rules.
- `static "foo"` requires `libfoo.a`. `shared "foo"` requires the target's shared library
  (`libfoo.so` or `libfoo.dylib`). Explicit modes never fall back to the other library kind.
- Preserve entry order, modes, and repeated names. Do not sort or deduplicate linker inputs.
- Library builds produce a relocatable `.o`, which permits only static inputs. Unqualified
  and `static` names resolve to `lib<name>.a` and are partial-linked with the generated object.
  An explicit `shared` entry is a source-located compilation error, not a warning, and prevents
  native tool execution. Missing static libraries are errors even if a shared library exists.
- Partial linking uses ordinary archive member selection. It does not force every archive
  member into the output or require every external symbol to be resolved. Remaining external
  references, including runtime dependencies, are resolved by the consumer's final link.
- AST and IR output retain the directive information without searching for native libraries
  or invoking a linker.
- `@native` keeps its existing signature and symbol-name behavior. The linker supplies the
  implementation; the directive does not generate declarations or bindings.

## Scope

In scope: syntax, source diagnostics, AST representation, compiler-state collection,
one native compilation entry point, executable link inputs, static partial linking, tests,
and language/compiler documentation.

Out of scope: MML imports or multi-module builds, shared-library output, dependency metadata
sidecars, package installation, raylib binding generation, raw linker flags, library paths
in the directive, and a new library-search-path configuration interface.

## Research

- [Module parsing](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/modules.scala)
  wraps one source file in a synthetic module and parses members until EOF. The directive
  belongs in a header before that member sequence.
- [Member parsing](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/members.scala)
  recovers unrecognized lines as `ParsingMemberError`. Directive errors must preserve later
  declarations and flow through the existing diagnostic pipeline.
- [Module](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/module.scala) and
  [CompilerState](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CompilerState.scala)
  have no link metadata. [IngestStage](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/IngestStage.scala)
  is the boundary for collecting parsed names into state. Check source placement before
  standard-library injection prepends generated members.
- [Native emission](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/Module.scala)
  already emits external declarations using `nativeSymbol.getOrElse(fnName)` and the target
  ABI. A new native declaration mechanism is unnecessary.
- [CodegenStage](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CodegenStage.scala)
  chooses between `compile` and `compileWithTimings`, which wrap the same `compileInternal`.
  Consolidate these into one toolchain entry point as part of the link-input integration.
- [LlvmToolchain](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/LlvmToolchain.scala)
  links executables through Clang with `-fuse-ld=lld`. Its library path uses `-c` to emit an
  object and copies the runtime object separately. Partial linking requires an additional
  step after assembling the module object. Preserve the separate runtime artifact.
- The toolchain already accepts argument sequences for process execution. Pass library
  arguments through that API without constructing a shell command string.
- [GNU ld's relocatable output](https://sourceware.org/binutils/docs/ld/Options.html)
  supports partial linking through `-r`. Use a target-appropriate relocatable linker;
  executable-linker selection alone is not proof of partial-link support on every target.

## Plan (Approval Gate)

1. Add a source-located module header representation for the directive and its entries.
   Parse the optional single directive, validate its shape and placement, and report
   recoverable diagnostics for malformed, duplicate, or misplaced directives. Preserve
   ordinary member doc-comment attachment and display the directive in AST output.
2. Represent each entry with its name, source location, and mode (`Default`, `Static`, or
   `Shared`). Collect the ordered entries into immutable `CompilerState` during ingestion.
   Preserve them through semantic rewrites. Consolidate `compile` and `compileWithTimings`
   into one `compile` entry point returning the compilation result and timings. Control timing
   collection internally with `config.showTimings`; return an empty timing vector when disabled.
   Update callers and remove the timed/untimed branch in `CodegenStage`, passing library inputs
   once. Reject `Shared` entries for object output before native tool execution.
3. Append executable library arguments after the generated program input. Pass default entries
   as `-l<name>`; resolve explicit modes to matching static or shared library files. Keep these
   inputs out of runtime compilation, target probing, LLVM optimization, and assembly generation.
4. For library mode with requested libraries, assemble an intermediate module object, select
   static archives using target toolchain search paths, and perform a relocatable link into
   the final `.o`. Use static-only selection with no shared-library fallback. Use a linker
   supporting the target's relocatable output, including the Darwin system linker where
   needed. Preserve existing output naming, target selection, and runtime-object copying.
   With no directive, retain the existing object build path.
5. Add the acceptance coverage below and document syntax, library lookup, both output modes,
   and the consumer's responsibility for remaining dependencies. Run the applicable compiler
   checks and local post-chores before handing off compiler changes.

Rules approval: approved, including per-entry modes, default linker selection, and compilation
errors for explicit `shared` entries in `.o` output.

Plan scope includes consolidating the timed and untimed toolchain entry points.

Implementation approval: pending for compiler implementation and product documentation changes.

## Verification

Planning-document checks: local links, task/memory status consistency, and whitespace pass.
Tracking review has no actionable findings. Compiler verification remains pending.

Research evidence is the repository code linked above. No compiler tests have been run for
this feature. Required implementation coverage:

- Parsing: absent directive, one name, many names, mixed default/static/shared entries,
  whitespace/comments, and member doc comments.
- Diagnostics: empty list/name, non-string entry, invalid qualifier, malformed brackets/separators,
  missing semicolon, duplicate directive, misplaced directive, and expression use. Confirm
  recovery preserves following declarations.
- State: names, modes, order, repeats, and source spans survive ingestion and semantic rewriting;
  modules without a directive remain unaffected.
- Executables: call a native function from a fixture static library and from a fixture shared
  library. Verify default entries retain linker selection, explicit modes select the required
  kind when both are available, and missing requested kinds fail without fallback. Verify
  missing-symbol failures and argument order.
- Timing: the single compilation entry point receives identical library inputs and produces
  the same compilation result with timing enabled or disabled. Verify timing records are
  collected when enabled and the timing vector is empty when disabled.
- Objects: partial-link a fixture archive, then link a consumer with the resulting `.o`
  without supplying that archive again. Exercise archive dependencies and remaining external
  references. Reject a missing archive even when a same-named shared library is available.
  Verify explicit `shared` entries produce source-located errors and prevent native tool
  execution; plain and `static` entries both use archives.
- Modes/targets: AST and IR output do not require installed libraries. Verify relocatable
  output and static-only selection on supported native target families; report unavailable
  target verification accurately.
- Keep fixture sources and test instructions versioned. Build fixture artifacts during tests;
  do not depend on a developer's raylib installation.
- Run formatting, linting, full tests, smoke checks, and other applicable gates from
  [coding-rules.md](../coding-rules.md) before compiler handoff.

## Risks / Notes

Static archives may contain references to additional libraries. List required static
dependencies in order for the partial link; remaining external dependencies still belong
to the consumer's final link. Partial linking does not make the object self-contained.

Native libraries must match the target architecture and ABI. Library discovery uses the
configured toolchain environment; this feature does not install dependencies or create
runtime search paths for shared libraries.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: granted for the design, planning, and tracking records only.
  Implementation commits require separate authorization.

## Task Working Memory

Syntax and output behavior are settled in [Rules](#rules). Repository research identifies
the parser, ingestion, and native toolchain boundaries. The implementation plan is ready
for approval. Next action: approve the plan and assign an implementation branch.
