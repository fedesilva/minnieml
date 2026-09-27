# Placeholder partial application

## Metadata

- **Owner:** Author
- **Status:** design
- **Kind:** feature
- **Priority:** MID
- **Created:** 2026-09-27
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Settle placeholder scope, evaluation semantics, and lowering boundaries.
2. [ ] **planned** — Implement placeholder elaboration using ordinary lambdas and applications.
3. [ ] **planned** — Verify operator and function cases, diagnostics, and ownership behavior.
4. [ ] **planned** — Document the feature and obtain signoff.

## Problem

Operator partial application needs an explicit syntax for missing operands. Ordinary function
partial application supplies leading arguments; it cannot express arbitrary missing positions.

Design: [Placeholder partial application](../../docs/brainstorming/language/placeholder-partial-application.md).

## Outcome

Use `_` to introduce parameters in expression position:

```mml
_ + 2                 // { x -> x + 2 }
10 / _                // { x -> 10 / x }
_ + _                 // { x, y -> x + y }
concat _ "suffix"     // { x -> concat x "suffix" }
```

Parameters follow placeholder order from left to right. Operators retain ordinary precedence.

## Scope

- Placeholder expressions for operators and functions, including multiple missing arguments.
- Scope across groups and nested lambdas; distinction from `_` used as a lambda binder.
- Interaction with ordinary partial application and grouped callables used in pipelines.
- Type inference, diagnostics, evaluation order, capture, and ownership behavior.
- Pipe implementation is outside this task.

## Plan (Approval Gate)

- [ ] Resolve expression boundaries and when supplied expressions are evaluated.
- [ ] Confirm the proposal's preferred approach: placeholder nodes and a separate rewrite
  before `ExpressionRewriter`, producing ordinary lambdas and applications.
- [ ] Prepare bounded implementation and verification steps.

Approval: pending.

## Verification

Pending implementation: verify single and multiple placeholders, operand order, precedence,
grouping, nested scopes, function partial application, type errors, and resource captures.
Include effectful supplied expressions to establish evaluation timing and count.

## Risks / Notes

- The proposal leaves grouping and placeholder scope open.
- Lambda wrapping and eager partial application can evaluate supplied expressions at different
  times; specify the intended behavior before lowering.
- Pipeline composition must preserve the grouped placeholder expression as a callable.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: task definition only; implementation not granted.

## Task Working Memory

- Design source: the linked placeholder proposal; separate rewriting is its preferred approach.
- Next action: settle placeholder boundaries and evaluation semantics, then prepare the
  implementation plan.
