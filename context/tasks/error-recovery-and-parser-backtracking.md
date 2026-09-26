# Error recovery and parser backtracking

## Metadata

- **Owner:** Author
- **Status:** planned
- **Kind:** improvement
- **Priority:** HIGH
- **Created:** 2026-09-25
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Reconcile the recovery design with the completed lambda work and approve bounded implementation stages.
2. [ ] **planned** — Preserve valid expression structure and causal diagnostics across semantic failures.
3. [ ] **planned** — Introduce parser commitment with local recovery at context-aware boundaries.
4. [ ] **planned** — Verify recovery, independent diagnostics, termination, and parser performance.

## Problem

Error recovery does not reliably preserve valid structure in complex expressions.
A single undefined reference can prevent normalization of an entire function and
cause misleading type and ownership diagnostics on otherwise valid expressions.
Parser backtracking also needs reduction without sacrificing local recovery.

## Outcome

Malformed programs retain their valid surrounding lambda/application structure.
Diagnostics identify primary failures, suppress their dependent consequences by
default, and continue reporting independent errors. Parser commitment reduces
unnecessary reparsing while recovery remains local and always makes progress.

## Scope

Design source: [Reduce backtracking: commit and recover](../../docs/brainstorming/parser/reduce-backtracking.md).

- Error-aware semantic phases, including `ExpressionRewriter`, type checking, and
  ownership analysis.
- Recovery from reference-resolution and other semantic failures as well as parser
  errors; cover `InvalidExpression` as well as `TermError`.
- Preservation of valid sibling expressions and enclosing lambda/application structure.
- Cause information that survives bindings and later uses, without inventing valid
  type or ownership facts for erroneous input.
- Local parser cuts and recovery for `let`, `if`, `fn`, and type ascriptions, with
  nesting-aware synchronization and guaranteed progress.
- Focused correctness tests and uninstrumented performance comparisons alongside
  parser counters.

Struct mutation and intrinsic arrays are separate features. This task does not add
syntax to make invalid assignment examples compile.

## Dependencies and order

Start after [Unify lambdas](unify-lambdas.md) is complete and any necessary
correctness fixes identified during its completion are resolved. This is the next
intended workstream after that stabilization; it does not require clearing the
entire bug backlog.

## Plan (Approval Gate)

- [ ] Recheck the design against the settled AST and phase contracts; finalize stage boundaries.
- [ ] Preserve local semantic recovery and connect dependent diagnostics to their causes.
- [ ] Implement parser commitment and local recovery without changing valid-program behavior.
- [ ] Verify diagnostics, recovery termination, preserved structure, and performance.

Approval: sequencing selected; detailed implementation plan pending.

## Verification

Diagnostic recovery evidence, reproduced on 2026-09-25:

```mml
struct Thing { name: String };

pub fn main(): Unit =
  let thing = Thing "Fede";
  let x = thing.name;
  println missing;
  println x;
;
```

Compilation through `sbtn "run run <source>.mml"` reports the primary undefined
reference `missing`, but also reports:

- `Ownership analysis requires a single-term expression` on `Thing "Fede"`.
- `'String' has no field 'name'` on `thing.name`.
- Additional inference and expression-shape diagnostics on later expressions.

Removing `println missing;` compiles and prints `Fede`.
`ExpressionRewriter.rewriteModule` retains the original binding when rewriting
returns errors, leaving valid expressions unnormalized. Ownership analysis then
reports the non-single-term shape. The
[struct mutation example](../../mml/samples/mem/struct-field-mutation.mml) produces
the same cascade with undefined `=` as the primary failure.

Required checks include both semantic and parse failures, multiple independent
errors, nested expressions, missing delimiters, precise spans, preserved valid
structure, and termination. Compare valid-program results and uninstrumented parse
timings; backtrack counters alone do not establish a performance improvement.

## Risks / Notes

Parser cuts alone do not repair semantic recovery. Blanket suppression can hide
independent errors, and retaining invalid subtrees must not create ordinary
ownership facts. Recovery must remain reachable after cuts and must not stall at
synchronization boundaries or EOF.

## Signoff

- Workstream signoff: pending
- Tracked item completion: pending
- Commit authorization: not granted

## Task Working Memory

Next action after lambda stabilization: review the linked design against phase
contracts and prepare the first bounded implementation plan. Use the embedded
undefined-reference example to verify recovery independently of unsupported syntax.
