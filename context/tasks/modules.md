# Modules, package root, and source inclusion

## Metadata

- **Owner:** unassigned
- **Status:** design
- **Kind:** feature
- **Priority:** MID
- **Created:** 2026-10-05
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Settle remaining module and inclusion semantics; approve a bounded plan.
2. [ ] **planned** — Introduce Package and working nested modules in a single source file.
3. [ ] **planned** — Add file-aware source tracking and top-level `include` package assembly.
4. [ ] **planned** — Verify executable and library packages, diagnostics, and existing programs.

## Problem

Compilation state centers on one source module. Programs need nested namespaces and a way
to share source modules, including native bindings, while retaining the existing sequential
compiler pipeline. Package-wide concerns need a container above individual modules.

## Outcome

`Package` is the compilation root. It contains the module hierarchy, compiler pragmas, and
the identity of the entry-point module. It subsumes the relevant package-wide fields of
`CompilerState`; the exact field allocation belongs in the implementation design.

Every source file is a module. Every folder from the package root is also a module.
Explicit inner modules remain nested within their enclosing module.

```text
Package
├── RaylibApp (entry-point module)
│   └── InnerModule
├── StdLib
│   ├── String
│   ├── Array
│   └── IO
└── OtherModule
```

The tree illustrates the module structure; it does not require a standard-library rewrite.

`mmlc` receives the module containing the entry point, or the public-interface module for
a library, and builds its package. Top-level `include` directives bring additional file
modules into that package. Including `StdLib` makes the `StdLib` module available for use.
Includes are tracked as a set: including the same module more than once is valid and adds
it to the package only once, including when it is reached through multiple include chains.

Each file is parsed independently. The parsed modules are assembled in memory under
`Package` before semantic processing. The existing sequential pipeline processes the
assembled package, with resolvers, type checking, and downstream phases adapted to the
module hierarchy. Module boundaries survive assembly.

Source tracking carries each source file's path, name, and positions through compilation
so diagnostics can identify the correct file and source text.

## Scope

- In scope: Package, nested modules, module-aware resolution, visibility and type checking, and the
  pipeline changes needed to compile the resulting hierarchy.
- In scope: top-level `include`, independent file parsing, folder/file module assembly,
  source identity, package pragmas, and executable/library entry-module selection.
- In scope: preserving native declarations and `@link` requirements across source inclusion.
- Out of scope: parallel compilation, separately compiled module artifacts, dependency
  scheduling, package distribution, and a general package manager.

## Plan (Approval Gate)

### 1. Settle semantics and implementation boundaries

- Define nested-module syntax, qualification, visibility, and name lookup, including types
  and operators. Coordinate identity and emitted names with
  [module naming](module-name-collisions.md).
- Define the package root and how folder/file paths establish module identity.
- Define the Package representation and which CompilerState fields it owns.
- Define include operand syntax and lookup, source identity for set membership, cycles,
  missing files, and conflicting module identities. The `include` keyword, top-level
  placement, inclusion as modules, and harmless repeated inclusion are settled.
- Define package pragma collection, including `@link` ordering and library-mode behavior.
- Define source identity propagation and executable/library entry-module validation.

### 2. Package and nested modules in one file

- Implement the package root and nested modules before multi-file inclusion.
- Adapt resolution, typing, indices, and downstream traversals to preserve module identity.
- Compile and execute single-file fixtures with inner modules and cross-module references.
- Stop for review and signoff before advancing to source inclusion.

### 3. Source inclusion

- Extend source tracking to retain file identity alongside positions and source text.
- Parse included files independently and assemble their modules into the package.
- Reuse the sequential pipeline for the assembled package.
- Exercise shared FFI declarations in one file and a consuming entry module in another.
- Stop for review and signoff before final task completion.

### 4. Verification and documentation

- Verify the accepted semantics and existing single-file compatibility.
- Update compiler and language documentation to describe the implemented behavior.
- Run applicable compiler checks and complete the required review before signoff.

Approval: architectural direction established above; detailed implementation plan pending.

## Verification

Pending implementation:

- Nested and sibling module references resolve to the correct values, types, and operators.
- Visibility, ambiguous names, and unresolved references follow the approved rules.
- File and folder modules retain distinct identities; emitted references match definitions.
- Included-file parser and semantic diagnostics show the correct path, name, and position.
- Repeated includes, including shared transitive dependencies, add each module only once.
- Missing sources and inclusion cycles follow the approved policy.
- Package pragmas retain their intended meaning, including native linking requirements.
- Executable entry selection and library public-interface selection use the designated module.
- A program uses FFI declarations from an included module and links successfully.
- Existing single-file programs pass the applicable regression and compiler checks.

No implementation or runtime verification is recorded for this task.

## Risks / Notes

### Future library and name-import ideas

These ideas are exploratory and do not expand the implementation scope or settle `use`
semantics:

- Compiler-distributed `Stdlib.mml` could organize prelude definitions into modules and be
  automatically included once per package. Runtime implementations could migrate from C
  to MML progressively, using native bindings and intrinsics where needed.
- A possible `use` construct could introduce convenient local names. One approach expands
  it into existing type aliases and local bindings. An operator wrapper could retain the
  original name and metadata and forward to the original operator.
- Another approach introduces a value-alias node, tentatively `Using` or `ValueAlias`,
  analogous to a type alias. Representation, evaluation, ownership, operator handling,
  and the relation between inclusion and unqualified scope remain design questions.

### Related work and implementation context

- [Module naming](module-name-collisions.md) owns symbol and output-name collision policy.
  This task needs compatible identities for nested, file, and folder modules.
- [Initial module-system notes](../../docs/brainstorming/compiler/initial-module-sys.md)
  discuss a broader parallel architecture. Parallel scheduling and additional typechecker
  stages are outside this task's sequential compilation scope.
- [Compile prelude](compile-prelude.md) describes declaration merging into a user module.
  Any later prelude migration using Package must reconcile that approach with file modules.
- [CompilerState](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/CompilerState.scala)
  holds a single module and SourceInfo, plus entry-point and link metadata.
  [Module](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/module.scala) already carries
  members, visibility, source-path metadata, and a link directive. Their exact migration
  requires implementation planning.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Delivery order is nested modules in one file, followed by independent parsing of included
files and package assembly. Both use the sequential pipeline. Package ownership of pragmas,
module hierarchy, and entry-module identity is settled; source tracking must retain file
identity. The directive is `include`, and includes have set semantics: repeated inclusion
is valid and contributes one module. Remaining decisions are listed in plan step 1.

Next action: inspect the affected parser, resolver, typechecker, and pipeline boundaries,
then present the remaining semantic choices and a bounded first-stage implementation plan.
