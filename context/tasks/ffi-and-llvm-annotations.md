# Replace `@native` with `@ffi` and `@llvm`

## Metadata

- **Owner:** Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-10-05
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Separate foreign declarations and LLVM templates in annotation parsing, diagnostics, and source output.
2. [ ] **planned** — Update the standard library, samples, tests, documentation, and editor support.
3. [ ] **planned** — Verify the rename and complete review.

## Problem

`@native` covers both foreign declarations and inline LLVM templates. The name also
conflicts with describing ordinary MML constructs as native MML types or functions.

## Outcome

Replace `@native` with two annotations:

- `@ffi` for foreign function bindings and type declarations, including struct mirrors.
- `@llvm[tpl="..."]` for inline LLVM templates.

Keep the applicable attributes, type rules, ownership behavior, and code generation.
The distinction makes native MML structs and foreign structs unambiguous.

```mml
type Color = @ffi { r: Int8, g: Int8, b: Int8, a: Int8 };
fn clear_background(color: Color): Unit = @ffi[name="ClearBackground"];;
fn byte(n: Int): Int8 = @llvm[tpl="trunc i32 %operand to i8"];;
```

## Scope

- In scope: annotation parsing and printing, diagnostics, editor syntax support,
  standard-library declarations, samples, test fixtures, and current documentation.
- Out of scope: changes to ABI lowering, linking, ownership, or template semantics;
  unrelated internal symbol renames; rewriting historical records.

## Plan (Approval Gate)

1. Locate every syntax consumer and producer of `@native`. Use `@ffi` for foreign
   declarations and `@llvm` for inline templates.
2. Migrate maintained source files, fixtures, documentation, and editor support.
3. Verify that each annotation form retains its behavior under the appropriate spelling.

Approval: annotation names and separation specified; implementation plan pending approval.

## Verification

Required checks:

- Parser tests cover `@ffi`, `@ffi[...]`, foreign struct mirrors using `@ffi { ... }`,
  and inline templates using `@llvm[tpl="..."]`.
- Inline templates belong to `@llvm`; `@ffi[tpl=...]` is rejected.
- The old `@native` spelling is rejected.
- Native-call, template, ownership, and aggregate ABI regression tests pass.
- Compiler finish checks follow [coding-rules.md](../coding-rules.md).

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Implementation commit authorization: not granted.

## Task Working Memory

Foreign declarations use `@ffi`; inline LLVM templates use `@llvm[tpl=...]`.
Existing semantics are preserved.
Next action: inspect syntax consumers and producers and prepare the implementation plan.
