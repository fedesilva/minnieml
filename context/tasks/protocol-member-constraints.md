# Protocol member constraints

## Metadata

- **Owner:** The Author
- **Status:** design
- **Kind:** feature
- **Priority:** HIGH
- **Created:** 2026-11-15
- **Target Branch:** Unassigned

## Execution Checklist

1. [ ] **design** — Settle constraint scope, instance conformance, and call-site obligations.
2. [ ] **planned** — Approve a bounded implementation plan using the existing generic machinery.
3. [ ] **planned** — Implement signature constraints across parsing, checking, and specialization.
4. [ ] **planned** — Complete regression coverage, examples, documentation, verification, and review.

## Tags

- protocols
- generics
- constraints

## Problem

Ordinary generic functions support `where` constraints, but protocol member signatures cannot
declare them. The [language reference](../../docs/language-reference.md#protocols-and-instances)
documents this restriction. Protocol signatures also reject type variables other than the
protocol's own parameter, so constraints and member-local polymorphism need distinct scope
decisions.

## Outcome

Allow protocol member signatures to express protocol requirements with `where`, with explicit
rules for implementation bodies and callers. Reuse the generic function type-variable,
constraint, inference, and specialization machinery wherever the semantics agree.

## Scope

- In scope: constraints on protocol function and operator signatures; variable scope,
  requirement resolution, instance conformance, call checking, diagnostics, and editor support.
- Design decision: whether the first slice constrains only the protocol parameter or also
  supports member-local type variables.
- Preserve additive specialization, the existing symbol index, and callable ownership rules.
- Out of scope: protocol-level prerequisites, multiple protocol parameters, parameterized
  instances, default implementations, generic data types, and higher-kinded variables.

## Design questions

1. Which type variables may a member signature bind or constrain? Distinguish the protocol
   parameter from any member-local variables and define their substitution order.
2. Are requirements on the protocol parameter obligations for instance declarations, conditions
   for calling that member, or both? What evidence may an implementation body assume?
3. How do instance implementations inherit requirements, and how are omitted, repeated, or
   stronger implementation constraints checked against the protocol signature?
4. How do requirements propagate through constrained generic callers, inferred or explicit
   member uses, function values, and partial applications? Define missing-evidence diagnostics
   without changing ordinary protocol selection unexpectedly.

## Plan (Approval Gate)

Settle the design questions with positive and failing examples, then approve bounded stages for
signature representation and parsing, resolution and conformance, call checking and concrete
specialization, and editor/documentation integration.

Implementation approval: pending.

## Verification

Acceptance coverage must include valid constraints, unknown protocols, out-of-scope variables,
missing requirements or instances, and implementation/signature mismatches. Cover direct calls,
generic callers, callable values, partial applications, and function/operator members according
to the approved semantics. Preserve source locations and navigation through specialization.

Relevant existing suites include
[ProtocolParserTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/parser/ProtocolParserTests.scala),
[ProtocolCheckerTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolCheckerTests.scala),
[ProtocolSelectionTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/ProtocolSelectionTests.scala),
and [GenericTypeCheckingTests](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/semantic/GenericTypeCheckingTests.scala).
Compiler implementation requires the applicable gates in [coding rules](../coding-rules.md).
No implementation or runtime verification is claimed for this task.

## Risks / Notes

- Builds on [Generic functions](generic-functions.md), including declared requirements and
  additive specialization.
- [Protocol parsing](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/protocols.scala)
  diagnoses `where` clauses on both protocol signatures and instance members as unsupported.
- [ProtocolSignature](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/protocols.scala)
  has no constraint or member-local type-parameter fields.
- [ProtocolChecker](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ProtocolChecker.scala)
  restricts signatures to the protocol parameter and lowers concrete instance members.
  Member-local generic implementations may therefore require coordination between instance
  lowering and function specialization, beyond accepting `where` syntax.

## Signoff

- Design and implementation plan approval: pending.
- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Next action: settle the design questions and define the first implementation slice. Reuse the
ordinary generic function constraint model while making protocol-instance obligations explicit.
Task scope is protocol member signatures; protocol-level prerequisites remain separate.
