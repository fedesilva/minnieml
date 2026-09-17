# Assemble tail-recursive PHIs without string replacement

## Metadata

- **Owner:** unassigned
- **Status:** planned
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-09-16
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Specify function-section assembly and state preservation.
2. [ ] **planned** — Generate completed PHIs from back edges and remove text substitution.
3. [ ] **planned** — Verify control flow, parameter counts, closure entries, and compiler checks.

## Problem

`compileTailRecursiveLambda` emits PHI lines containing `__PHI_PLACEHOLDER_N` before
compiling the loop body. Body compilation returns `BackEdge` values containing predecessor
labels and incoming arguments. A map then associates placeholder strings with incoming lists,
and `replacePlaceholders` scans every output line in the function with `String.replace`.

This makes PHI construction depend on textual matching and a register-allocation invariant
that the replacement helper neither documents nor enforces. For example, replacing
`__PHI_PLACEHOLDER_1` also matches the prefix of `__PHI_PLACEHOLDER_10`.

The current implementation avoids that collision. For `n` non-void parameters, PHI IDs occupy
`[s, s + n - 1]`, where `s = n + 2*c + e`, `c` is the capture count, and `e` is one for a
closure entry and zero otherwise. For `n > 0`, the maximum is less than twice the minimum;
a strict decimal-prefix pair requires at least a tenfold difference. With zero parameters,
there are no PHI placeholders. Replacement also occurs before the function output is merged
into the module.

Map iteration order matters only if placeholder keys overlap. Scala's specialized immutable
maps for one through four entries and hash-trie maps for larger sizes do not cause a current
failure: the keys are non-overlapping. Unspecified iteration order is not random iteration.

This is a maintainability and robustness issue, not a demonstrated miscompilation.

## Outcome

Reserve PHI registers before compiling the body, retain the collected back edges, and render
finished PHI instructions afterward. Assemble entry, loop header, and body in their required
order. PHI completion requires no placeholder strings, replacement map, or substring scan.

## Scope

- In scope: tail-recursive function assembly, PHI incoming values and predecessor labels,
  plain and closure entry paths, and focused regression coverage.
- In scope: preserving register allocation, entry prologue placement, captures, self-closure
  setup, deferred definitions, metadata, and state merging.
- Out of scope: replacing the text emitter with a general structured IR or mutable IR builder,
  changing tail-recursion eligibility, and unrelated naming or attribute cleanup.

## Plan (Approval Gate)

- [ ] Define the smallest separation between entry, loop header, and body output, accounting
  for reversed output storage and entry prologue instructions.
- [ ] Reserve PHI registers and build the parameter scope without emitting placeholder lines.
- [ ] Compile the body once, then render each PHI from its initial argument and collected
  back-edge arguments. Keep PHIs before other loop-header instructions.
- [ ] Assemble the sections with existing function rendering/state merging and remove the
  replacement map and `replacePlaceholders` helper when unused.
- [ ] Verify the resulting control flow and observable behavior, then run applicable compiler
  checks and review before handoff.

Direction: assemble completed sections directly. Implementation approval: pending.

## Verification

Source evidence is in
[FunctionEmitter.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/FunctionEmitter.scala):
`BackEdge`, `compileTailRecursiveLambda`, `compileTailRecBody`, `replacePlaceholders`,
`renderFunctionLines`, and `mergeFunctionBodyState`.

Source inspection and independent review establish that the emitter constructs PHI IDs using
the formula above, and that replacement relies on its arithmetic protection.

A scratch prefix check covered parameter counts 0–64, capture counts 0–16, and both entry
offsets without finding overlapping keys. This checks the supplied formula, not the emitter's
actual register allocation; it cannot detect a divergence between them. A separate string
experiment with IDs 1 and 10 demonstrated order-dependent corruption for hypothetical
overlapping keys. Neither experiment ran the compiler or verified compiler-generated IR.

Pending regression coverage:

- Zero/one parameter, four/five parameters, and multi-digit PHI IDs; include erased Unit params.
- Plain direct entries and closure entries with captures and recursive self references.
- Multiple recursive branches and argument/pre-statement conditionals that change the
  actual back-edge predecessor block.
- Nested local functions and entry-allocated borrow environments.
- LLVM verification of PHI predecessors/types and execution with distinguishable argument
  values to catch argument swaps, missing edges, and duplicated effects.

Use semantic/control-flow assertions and runtime results rather than fixed register numbers.
Compiler tests and applicable handoff gates remain pending.

## Risks / Notes

Delayed rendering must preserve LLVM register numbering, block order, and all compiler state
produced while compiling the body. Text emission itself remains appropriate for this scope.

Closing delimiters, such as `__PHI_PLACEHOLDER_1__`, fully prevent numeric-prefix collisions
regardless of register numbering or map iteration order when used in both emission and
replacement keys. This is a valid small hardening fix if section assembly is deferred.
Section assembly remains the selected outcome because it removes text substitution itself;
the delimiter fallback becomes unnecessary once that change lands. Sorting the replacement
map alone retains a dependency on processing order.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Next action: propose the bounded function-section assembly change and its state invariants
for approval. Keep the existing safety proof as context for regression coverage, without
presenting the small-map transition as a current compiler bug.
