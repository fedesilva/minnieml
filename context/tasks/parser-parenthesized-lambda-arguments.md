# Parse parenthesized lambda applications used as arguments

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG
- **Priority:** MID
- **Created:** 2026-10-19
- **Target Branch:** unassigned
- **External Reference:** none

## Execution Checklist

1. [ ] **planned** — Reproduce the grouped-call rejection and identify the parser decision responsible.
2. [ ] **planned** — Approve and implement a bounded parser repair with regression coverage.
3. [ ] **planned** — Verify grouping, selection, recovery, and downstream expression behavior.

## Problem

The parser rejects a parenthesized lambda application used as an argument, expecting a
field-selection dot after the closing parenthesis:

```mml
fn add(a: Int, b: Int): Int = a + b;;
fn example(): Int = { a: Int -> add ({ b: Int -> add b; } a 2); } 1 3;;
```

The corresponding local-binding form parses and passes type checking:

```mml
fn add(a: Int, b: Int): Int = a + b;;
fn example(): Int =
  { a: Int ->
    let inner = { b: Int -> add b; } a 2;
    add inner;
  } 1 3;
;
```

## Outcome

The grouped form parses as an argument containing a lambda-headed application chain.
It preserves the intended grouping and lexical references and evaluates to `6`, matching
the local-binding control. Parentheses alone do not require a field-selection suffix.

## Scope

- In scope: parsing parenthesized lambda applications in argument position; preservation of
  genuine field selections, nested grouping, source spans, and malformed-input diagnostics.
- Out of scope: broad parser optimization, ownership-policy changes, and PAP elaboration repairs.

## Plan (Approval Gate)

- [ ] Reduce the source and trace grouping/selection alternatives and commitment boundaries in
  [expression parsing](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala).
- [ ] Propose the smallest repair supported by that trace and obtain implementation approval.
- [ ] Add parser regressions for the failing form, the working control, nested grouped arguments,
  actual field selections, and missing or malformed delimiters.
- [ ] Verify the parsed argument/application structure and source spans, then type checking and
  execution of the reproducer and control. Complete applicable compiler gates and review.

Approval: pending.

## Verification

Observed on 2026-10-19 with parser sources at `af8fec6`, unchanged during the observation:

- [ParserApi.parseModuleString](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/api/ParserApi.scala),
  called through the existing `parseNotFailed` test helper, returns a parse failure for the
  grouped form. The relevant diagnostic excerpt is:

  ```text
  selectionP:3:43 / selectionFieldP:3:69 / ".":3:69, found "; } 1 3;;\n"
  ```

  Line and column numbers reflect the whitespace in the test input.
- The local-binding control passes parsing, type checking, and the focused PAP elaboration
  regression, including nested residual lambdas and stable lexical identities.
- Runtime equivalence and the parser repair are unverified. The expected result `6` follows
  from the source arithmetic, not an execution result.

## Risks / Notes

- Coordinate any commitment/recovery changes with
  [Error recovery and parser backtracking](error-recovery-and-parser-backtracking.md).
- The valid-call parsing failure is separate from
  [PAP callee traversal](unify-lambdas.md#bug-elaborate-partial-applications-inside-callees).

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The failing source, parser diagnostic, and working local-binding control are recorded in
[Verification](#verification). Next action: isolate the grouping/selection parser decision
and prepare a bounded repair plan. Implementation approval is pending.
