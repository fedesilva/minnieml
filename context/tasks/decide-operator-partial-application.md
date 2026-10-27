# Decide what to do with operator partial application

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-10-27
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Explain and confirm the operator behavior and rewriter changes below.
2. [ ] **design** — Compare operator application with placeholders, including keeping both.
3. [ ] **design** — Agree on the language rules and prepare a bounded implementation plan.

## Problem

Unary/binary operator selection should remain simple and require few type annotations.
Ordinary syntax distinguishes `+1` from `1 + 2`. Treating `(+)` as a function reference loses
that positional distinction: applying one argument can mean a complete unary call or a
partial binary call. The built-in `+` already has both forms.

The protocol implementation extends operator function-value handling and shares ordinary
partial-application lowering. The existing [placeholder plan](placeholder-partial-application.md)
expresses missing operands explicitly with `_`. Whether to keep both forms remains open.

## Outcome

A small, example-led language design that settles accepted forms, unary/binary selection,
type annotations, and diagnostics. Keep the expression rewriter's role understandable.
Keeping both forms, restricting operator application, and using placeholders for operator
partial application are alternatives to discuss; none is selected.

## Behavior to discuss

These outcomes describe the protocol changes under review by source inspection. Confirm them with
the compiler when the discussion begins. Use the actual built-in `+`, with both arities,
rather than only a custom operator that has a binary declaration.

| Expression | Meaning or current outcome |
| --- | --- |
| `+1` | Unary plus; syntax selects the unary form. |
| `1 + 2` | Binary addition; syntax selects the binary form. |
| `let plus: Int -> Int -> Int = (+);` | The expected function type selects binary Int addition. |
| `let plus = (+);` | Insufficient type information; no arguments or expected function type. |
| `let plus = (+) 1;` | Ambiguous: unary plus returns `Int`; partial binary addition returns `Int -> Int`. |
| `let plus = 1 (+);` | Rejected as an unexpected group, not interpreted as a missing right operand. |

`(+)` is a function reference. `(+) 1` supplies one argument. These are distinct cases.
The placeholder proposal includes `1 + _`, `_ + 1`, and `_ + _`; these make missing operand
positions explicit. Its scope and evaluation timing still need design.

## What changed and why

The comparison baseline is repository commit `14615983`.

- Ordinary application grouping is unchanged: `f a b` becomes `App(App(f, a), b)`.
  Arguments are consumed until an operator or the expression's end. This does not require
  knowing the function's arity or selecting a protocol instance.
- The baseline rewriter also generates lambdas around bare function values, using the resolved
  declaration's parameters. Such a lambda simply passes its parameters to the named function.
  Generation moves to `PartialApplicationElaborator`, after type checking selects a concrete
  member. The baseline already lowers partial calls with supplied arguments later, in
  `PartialApplicationElaborator`.
- The motivation for moving generation is to give overloaded members the ordinary function-value
  path and avoid separate early and late wrapper implementations. Assess that choice separately
  from the rules for operator syntax; building an application chain does not require the move.
- Prefix recognition gains an operand-present check. A lone prefix operator can therefore survive
  as a reference instead of producing a missing-operand error. This broadens accepted expressions;
  it is a separate choice from retaining protocol candidates for ordinary unary and binary calls.

Relevant code: [ExpressionRewriter](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ExpressionRewriter.scala),
[PartialApplicationElaborator](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/PartialApplicationElaborator.scala),
and [TypeChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala).

## Scope

- Operator references, partial application, unary/binary ambiguity, and annotation burden.
- Relationship to the [placeholder proposal](../../docs/brainstorming/language/placeholder-partial-application.md).
  Keeping both mechanisms is a valid option; compare evaluation order, captures, and diagnostics.
- Identify which rewriter changes serve protocol calls and which serve additional syntax.
- Ordinary named-function partial application is a separate existing behavior to preserve.
- General function-value representation is a separate question: whether function values need
  wrapper lambdas and when to generate them. Coordinate any representation changes without
  treating operator syntax as settled.

## Plan (Approval Gate)

- Discuss one small group of examples at a time; show the meaning before compiler internals.
- Compare the alternatives without presuming removal or acceptance of either form.
- Record the agreed rules, then propose implementation and readable regression tests separately.

Approval: task definition and deferred discussion only; implementation pending.

## Verification

Source comparison covers argument consumption, wrapper generation, prefix recognition, and
type-directed selection. No compiler tests were run for this task. Before a design is approved,
confirm the example table and establish evaluation timing for each proposed partial form.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Deferred design, placed after [Protocols](protocols.md) in the tracker. Next action: review the
example table with the Author when discussion is selected. Keep implementation unchanged until
the design and a separate implementation plan are approved. Neither coexistence nor removal is
decided. Keep function-value representation separate from operator syntax, and coordinate
agreed syntax with the placeholder task.
