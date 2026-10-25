# Metal representation, foreign binding, and template annotations

## Metadata

- **Owner:** Author
- **Status:** complete
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-10-05
- **Completed:** 2026-10-24
- **Target Branch:** dev-2026-03-21-lambdas

## Execution Checklist

1. [x] **complete** — Separate representation types, foreign declarations, and templates in annotation parsing, diagnostics, and source output.
2. [x] **complete** — Update the standard library, samples, tests, documentation, and editor support.
3. [x] **complete** — Verify the rename and complete independent review.
4. [x] **complete** — Resolve annotation review findings and verify the corrections.

## Problem

Representation types, foreign declarations, and inline templates need distinct annotations.
Using `native` for foreign declarations conflicts with describing ordinary MML constructs
as native MML types or functions.

## Outcome

Three annotations distinguish the implementation boundaries:

- `@metal:repr` for representation types, including struct mirrors.
- `@metal:ffi` for foreign function bindings.
- `@metal:tpl["..."]` for inline LLVM templates.

Keep the applicable attributes, type rules, ownership behavior, and code generation.
The distinction makes native MML structs and foreign structs unambiguous.

```mml
type Color = @metal:repr { r: Int8, g: Int8, b: Int8, a: Int8 };
fn clear_background(color: Color): Unit = @metal:ffi[name="ClearBackground"];;
fn byte(n: Int): Int8 = @metal:tpl["trunc i32 %operand to i8"];;
```

## Scope

- In scope: annotation parsing and printing, diagnostics, editor syntax support,
  standard-library declarations, samples, test fixtures, and current documentation.
- Out of scope: changes to ABI lowering, linking, ownership, or template semantics;
  unrelated internal symbol renames; rewriting historical records.

## Plan (Approval Gate)

1. Update annotation parsing and display for the three metal forms, with a required
   first positional template string followed by optional named attributes.
2. Migrate maintained source files, fixtures, documentation, and editor support.
3. Verify that each annotation form retains its behavior under the appropriate spelling.

Approval: implementation, maintained-source and editor migration, regression coverage,
compiler verification, independent review, and task bookkeeping are approved.

## Verification

Required checks:

- Parser tests cover `@metal:ffi`, `@metal:ffi[...]`, foreign struct mirrors using `@metal:repr { ... }`,
  and inline templates using `@metal:tpl["..."]`.
- Inline templates belong to `@metal:tpl`; `@metal:ffi[tpl=...]` is rejected.
- The parser rejects `@native`, `@ffi`, and `@llvm` annotations.
- Native-call, template, ownership, and aggregate ABI regression tests pass.
- Compiler finish checks follow [coding-rules.md](../../coding-rules.md).

### Results

- `sbtn 'scalafmtAll; scalafixAll; testOnly mml.mmlclib.grammar.ImplementationAnnotationTests mml.mmlclib.grammar.FnTests mml.mmlclib.grammar.NativeTypeTests'`: 63/63 pass, including
  every malformed annotation's diagnostic location and following declaration, an unterminated
  template, a valid multiline template, and complete annotation printing.
- `bash tooling/zed/test.sh`: 11/11 corpus cases, six reference programs, and Zed queries pass.
  The global parser-directory warning does not prevent local grammar validation.
- `./tests/smoke/run.sh all`: 8/8 pass with Homebrew Clang/LLVM 23.1.1 on arm64 macOS.
- `sbtn testFull`: 1,522 library tests and 9 CLI tests pass; no compiler warnings.
- `sbtn mmlcPublishLocal`: passes after smoke and full-suite verification.
- `make -C benchmark clean` and `make -C benchmark mml`: pass after publishing.
  This checks compilation; performance was not measured.
- The annotation migration's ASan+LSan harness passed 52/52 in 113 seconds. Review corrections
  affect displays, parser spelling conventions, comments, and tests; ownership and lambda
  lowering are unchanged, so the memory harness was not repeated for these corrections.
- Migration checks also cover VS Code and actual Vim annotation highlighting, and all 269
  tracked MML sources: program and template contents are preserved under annotation migration.
  Interactive editor rendering is untested.
- The VS Code, Zed, and Neovim installation scripts pass. Installed files match their
  source or build output. A headless Neovim check confirms MML file detection and highlighting
  for all three metal annotations.
- No ABI lowering changes; Linux container execution is not required for this scope.
- QA enforcement, focused tracking consistency, and `git diff --check`: pass.
- Focused independent re-review of all twelve corrections passes with no actionable findings.
  Fresh primary, code-style/QA, test-quality, and readability reviews completed.
  No candidate findings required separate claim verification. The reviewers used the recorded
  build evidence; they did not rerun compiler checks.

### Review corrections

1. `NativeImpl.annotationName` supplies the annotation name to hover and AST printing.
2. Annotation tests use the shared `collectModuleTerms` traversal.
3. Implementation parsing uses Cats `none` and `some` constructors.
4. The language reference describes supported annotations without migration history.
5. The template section states its supported attributes without backend-planning commentary.
6. The foreign-declaration section specifies its named attributes.
7. The parser comment demonstrates a template instruction without a result assignment.
8. The Vim comment describes all metal annotations.
9. Rejection tests check a single member error, its source line and snippet, and preservation
   of the following declaration. Unterminated and valid multiline templates have dedicated cases.
10. Printing tests compare complete bare-foreign, attributed-foreign, and template annotations.
11. The editor corpus includes a representation struct without attributes.
12. The function parser test names its positional template and two named attributes accurately.

Correction verification and focused independent re-review pass.

## Signoff

- Workstream signoff: approved for the annotation implementation, review corrections,
  documentation, and editor support.
- Tracked item completion: approved.
- Local commit authorization: granted.
- Push authorization: not granted.

## Task Working Memory

The approved surface syntax is `@metal:repr` for representation types, `@metal:ffi`
for external functions, and `@metal:tpl["...", mem=alloc]` for templates. The template
string must come first; the optional supported attributes are `mem` and `name`.
AST structure and code generation behavior are preserved.

Parser, maintained sources, language reference, and editor support are migrated.
All samples and the standard prelude are audited; focused parser and editor checks pass.
Review corrections cover shared annotation naming and test traversal, parser style,
current-state documentation, valid examples, accurate labels, and stronger parser,
printing, and editor coverage. Verification and focused independent re-review pass.
All scoped implementation and verification are complete. The template external-declaration
fix remains in its [separate planned task](template-function-declarations.md).
