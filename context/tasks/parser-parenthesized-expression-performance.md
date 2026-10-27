# Bound parsing work for nested parenthesized expressions

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-27
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Reproduce parser-time growth with bounded nested-expression fixtures.
2. [ ] **planned** — Identify repeated parser work and approve a focused repair plan.
3. [ ] **planned** — Repair the repeated work while preserving parsing and recovery behavior.
4. [ ] **planned** — Verify scaling, diagnostics, and ordinary compiler regressions.

## Problem

Valid right-nested expressions such as `1 + (2 + (3 + 4))` are reported to take roughly twice
as long to parse for each added nesting level. The protocol review reproduced the same behavior
in the baseline preserved by commit `be195134` and with the protocol changes, identifying a
pre-existing parser problem.

This is distinct from repeated type checking of an erroneous expression, which belongs to the
immediate protocol repairs. It is also distinct from the known
[stack overflow on long sequences](compiler-stack-safety.md).

## Outcome and scope

Parsing a moderately nested valid arithmetic expression performs bounded work proportional to
its structure rather than repeatedly parsing the same nested suffix. Preserve precedence, grouping,
source spans, valid callable groups, and malformed-input recovery. Do not change language syntax.

## Evidence and reproduction

The source family is a function returning `1 + (2 + (... + N))`, with one matched parenthesis pair
per nested suffix. Use fixed literal fixtures for correctness tests and separate controlled workloads
for timing. Measure the parser independently of type checking, code generation, and process startup.

Review-reported parse totals, with the same observations in the baseline preserved by `be195134`:

| Terms | Parse time |
|---:|---:|
| 16 | 1.2 s |
| 20 | 13 s |
| 22 | 51 s |
| 24 | More than 120 s |

These timings are reported evidence; a fresh bounded reproduction and toolchain record are pending.
Investigate grouped expression alternatives in
[expressions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala)
without assuming the cause before measuring parser-rule work.

## Plan and signoff

- Task creation: authorized.
- Implementation plan and repair approval: pending after reproduction.
- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: granted for the task definition only.

## Task Working Memory

Next: reproduce smaller bounded cases, record parser-only evidence, then identify the repeated
rule path. Keep this task outside the protocol repair scope and preserve the separate stack-safety task.
