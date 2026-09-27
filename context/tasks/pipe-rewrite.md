# Pipe rewrite

## Metadata

- **Owner:** Author
- **Status:** planned
- **Kind:** feature
- **Priority:** MID
- **Created:** 2026-09-27
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Prepare the parser-lowering and diagnostic-origin implementation plan.
2. [ ] **planned** — Implement pipe syntax, lowering, token reservation, and diagnostics.
3. [ ] **planned** — Verify grouping, application semantics, evaluation, and ownership.
4. [ ] **planned** — Update language documentation and obtain signoff.

## Problem

MML needs pipeline syntax that supplies the first argument of each call without a placeholder.

Design: [Pipe rewrite](../../docs/brainstorming/language/pipe-rewrite.md).

## Outcome

Built-in `|>` syntax lowers to ordinary applications:

```mml
x |> f a         // f x a
g x |> f a       // f (g x) a
x |> f a |> g b  // g (f x a) b
x |> (f a)       // (f a) x
x + y |> f       // f (x + y)
x |> f a + b     // (f x a) + b
```

Stages associate left to right within each semicolon-separated expression. The rewrite inserts
the preceding expression once. Typing, evaluation, and ownership follow ordinary application.

## Scope

- Parser lowering in ordinary and member expressions, including nested groups.
- References, lambdas, projections, and grouped callables as stage heads.
- Reserve exactly `|>`; reject its operator declaration and preserve longer symbolic names.
- Preserve pipe and operand locations so diagnostics can identify the piped value.
- No runtime pipe operation or pipe-specific ownership and code-generation rules.
- Placeholder elaboration and effect-aware pipe overloads are outside this task.

## Plan (Approval Gate)

- [ ] Specify the stage fold and source-origin representation before implementation.
- [ ] Lower stages through existing expression/application nodes before statement sequencing.
- [ ] Add token handling, malformed-pipeline diagnostics, and regression coverage.
- [ ] Verify ordinary application equivalence and document the implemented syntax.

Approval: pending implementation plan.

## Verification

Pending implementation: test the rewrite examples, nested groups, member bodies, sequencing,
lambda and projection heads, undersaturation, malformed stages, reserved declarations, and
longer symbolic operators. Check type-error attribution, evaluation count/order, and resource
transfer against equivalent ordinary calls. Run the compiler checks required by the coding rules.

## Risks / Notes

- Source origins must survive lowering to support piped-value diagnostics.
- [Placeholder partial application](placeholder-partial-application.md) supplies grouped
  operator callables such as `x |> (_ + 1)`. Basic pipe support does not depend on that feature;
  its composition checks require placeholder elaboration.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: task definition only; implementation not granted.

## Task Working Memory

- Surface rules are specified in the linked design.
- Next action: prepare a bounded implementation plan for parser lowering and diagnostic origins.
