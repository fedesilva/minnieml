# Replace magic `__stmt` detection for sequence lambdas

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** Unassigned
- **External Reference (optional):** https://github.com/fedesilva/minnieml/issues/265

## Execution Checklist

1. [ ] **planned** — Confirm the bounded implementation plan and acceptance checks.
2. [ ] **planned** — Introduce explicit lambda roles and replace name-based classification.
3. [ ] **planned** — Verify role preservation and behavior, review, and obtain signoff.

## Problem

`FunctionEmitter.scala` recognizes parser-lowered statement sequencing by checking for a
single parameter named `__stmt`. `DuplicateNameChecker.scala` and `TypeChecker.scala`
also rely on that name for scope classification or diagnostic wording.

The parser already marks local-binding and statement-continuation lambdas with
`LambdaMeta.isBindingScope`. `TypeResolver` uses this flag to prevent these wrappers
from declaring type parameters, but the Boolean does not distinguish the two scope roles.

## Outcome

Parser, semantics, ownership, and codegen identify sequence lambdas using explicit metadata or a shared marker.

## Scope

- In scope: The problem and outcome above; detailed scope is settled in the approved plan.
- Out of scope: HM let generalization and unrelated compiler changes.

## Plan (Approval Gate)

Proposed approach:

- Replace the stored `isBindingScope` Boolean with a lambda-role enum distinguishing
  ordinary lambdas, let bindings, and statement sequences. Final names remain to be chosen.
- Retain a derived `isBindingScope` predicate for consumers that treat both scope roles alike.
- Assign roles during parser lowering and preserve them through semantic transformations
  and lambda lowering.
- Replace `__stmt`-based classification in codegen and semantics with role checks. Use role
  information for statement diagnostic wording rather than interpreting the binder name.
- Preserve the distinction needed for future HM let generalization. Implementing
  generalization is outside this task's scope.

Approval: pending for implementation.

## Verification

Confirm role preservation through rewriting and lowering. Cover ordinary lambdas, named
local bindings, statement sequencing, duplicate-name diagnostics, type-variable scoping,
and tail-recursion extraction. Assert semantic behavior rather than synthetic binder names.
Implementation verification remains pending.

## Risks / Notes

The existing Boolean provides a shared classification point, but every transformation that
rebuilds lambda metadata must preserve the role. The implementation plan must identify those
paths and the focused regression coverage before changes begin.

## Signoff

- Workstream signoff: Pending.
- Tracked item completion: Pending.
- Commit authorization: not granted.

## Task Working Memory

The string convention remains in codegen and semantics. The proposed enum records three
lexical roles while retaining a derived predicate for the two binding-scope roles.
Next action: confirm a bounded implementation plan, including metadata preservation and
acceptance checks. Implementation approval and verification remain pending.
