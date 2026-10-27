# Discuss protocol validation and callable representation

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** improvement
- **Priority:** MID
- **Created:** 2026-10-27
- **Target Branch:** unassigned
- **Parent:** [Implement protocols](protocols.md)

## Execution Checklist

1. [ ] **design** — Decide which phase owns malformed-signature validation.
2. [ ] **design** — Review function-value wrappers and their lowering phase.
3. [ ] **design** — Decide how lambda result annotations differ from callable annotations.
4. [ ] **design** — Review the shared callable-type and application-checking boundaries.

## Outcome and discussion format

Resolve one topic per iteration. Begin with a small source example and a concrete explanation of
the current representation, present the alternatives and costs, and record the Author's decision.
Each iteration ends with review before a separate implementation plan is prepared. Creating these
subtasks does not approve their proposed refactors.

### 1. Who rejects a malformed protocol signature?

The parser rejects unsupported defaults, omitted parameter types, and foreign type variables.
`ProtocolChecker.signatureErrors` checks them again; `instantiateSignature` repeats part of that
validation. Duplicate parameter checking remains live in the semantic phase.

The question is whether later phases may trust parsed input, or must also accept and diagnose
programmatically built ASTs such as injected declarations and test fixtures. Removing duplicate
checks without settling that promise can make a phase fragile. Keeping all checks without a stated
owner makes maintenance confusing.

Discuss an example such as a required member with an untyped parameter, then choose the authoritative
check, any necessary boundary validation, and the invariant callers can rely on. Review reference:
the malformed-signature validation finding labelled V24.

### 2. Should a function reference need a forwarding lambda?

For `let add: Int -> Int -> Int = (+);`, the implementation selects the concrete member and builds
a lambda that forwards its arguments. Bare function-value wrapping runs in
`PartialApplicationElaborator` after type checking; ordinary partial calls use the same machinery.

Discuss direct references versus forwarding lambdas, when a concrete implementation must be known,
and generated-code costs. Preserve evaluation timing, identity, captures, and ownership in comparisons.
The current implementation stays in place pending this discussion; no redesign is approved.

Operator syntax and unary/binary ambiguity belong to
[Decide what to do with operator partial application](decide-operator-partial-application.md).
Coordinate any representation change with that discussion without deciding its language rules here.

This is distinct from [placeholder partial application](placeholder-partial-application.md), whose
examples include `_ + 2` and `10 / _`. Placeholder scope and evaluation timing remain that task's
design questions. No placeholder syntax is implemented by the protocol work.

### 3. What does a lambda's annotation annotate?

`{ value: Int -> value; }: Int` describes the lambda's return type. An annotation on a grouped
callable can instead describe the whole function type. The generic `typeAsc` field represents both,
so several consumers must recognize Lambda specially when interpreting type evidence.

Compare explicit fields or shared accessors with the current node-dependent meaning. Decide how
source locations and written annotation boundaries are retained. This is the representation issue
behind the five special cases noted in review, labelled V28; it is not permission to change syntax.

### 4. Which callable operations should share one API?

Several places independently construct a callable from parameters (using Unit for a nullary call),
compute the function type remaining after arguments, or combine protocol evidence with ordinary
candidate IDs. Application checking also passes parallel lists of applications and probes and
dispatches on the application head in more than one method.

Start with `choose 0` whose expected type is `Bool -> Bool`, then trace the source call, argument
probe, candidate check, and remaining type. Decide a small common vocabulary before reorganizing
the checker. Avoid a broad framework or hiding state and diagnostic flow behind wrappers.

Include the test pipeline's copied phase order: decide whether production should expose a stable
phase boundary for tests or whether a focused test-only sequence is clearer. Related review labels
are V2, V12, V19–V23, and V26. Unreachable-match simplification and deeply nested returned-function
evidence handling belong to this same discussion where their surrounding API changes.

## Scope

These are design decisions, separate from
[repairing protocol errors and missing tests](protocols-review-repairs.md). Local corrections may
simplify code needed for a bug fix, but do not settle these broader questions implicitly.

## Verification

The current source contains the repeated validation, forwarding-lambda lowering, annotation
interpretation, and shared-operation candidates described above. No alternative design has been
implemented or measured. Each discussion must identify evidence needed for its decision.

## Signoff

- Discussion subtasks: authorized.
- Implementation approval: pending for each resulting plan.
- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: granted for the discussion task definition only.

## Task Working Memory

Next: discuss malformed-signature validation using a concrete source/AST example. Follow the
checklist one topic at a time, keeping decisions and proposed implementation separate. The current
function-value lowering stays unchanged during immediate protocol repairs.
