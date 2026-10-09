# Implement enums

## Metadata

- **Owner:** Author
- **Status:** design
- **Kind:** feature
- **Priority:** unassigned
- **Created:** 2026-10-08
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **design** — Settle enum semantics before implementation planning.

## Problem

MML needs typed, integer-backed named alternatives for C integration.

## Outcome

Enums provide C-compatible representation while preserving MML type checking.

## Scope

- Enum members, inference, member type ascription, derived equality, explicit
  enum-to-integer conversion, and C integration.
- Parameterized protocols and instance resolution are required for equality.
- Compiler-derived `FromInt` is deferred; handwritten conversion tables suffice initially.
- Payload alternatives belong to [Implement unions](unions.md).

## Spec

See the [Enums spec](../../docs/brainstorming/language/enums.md).

## Plan (Approval Gate)

## Pre-planning findings

Source inspection only; these findings identify integration points, not an implementation plan.

- **Declarations are new work.** The declaration parser in
  [members.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/members.scala)
  handles bindings, functions, operators, structs, aliases, and native types. It has no
  enum, protocol, or instance declaration path.
- **Type-parameter scaffolding exists.**
  [ast/types.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/types.scala)
  defines `TypeVariable`, `TypeApplication`, and `TypeScheme`. Their presence does not
  establish working polymorphism: the current type grammar in
  [parser/types.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/types.scala)
  accepts grouped types, nominal references, and function arrows. Protocol parameter
  syntax and enum member type ascriptions need frontend support.
- **Qualified expressions have a starting point.** `selectionP` in
  [expressions.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala)
  represents dotted selections as qualified `Ref` nodes. Enum member resolution and
  the distinction between default enum inference and explicit member typing still need
  dedicated semantics; existing dotted syntax alone does not implement them.
- **Equality is monomorphic.** `injectStandardOperators` in
  [semantic/package.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/package.scala)
  injects `==` and `!=` with `Int -> Int -> Bool` signatures. Enum equality therefore
  depends on protocol instance resolution, not reuse through implicit integer conversion.
- **Ownership already distinguishes resource-bearing types.** `isHeapType` in
  [TypeUtils.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/ast/TypeUtils.scala)
  checks native allocation effects and recursively checks struct fields. Enum integration
  must preserve scalar copying and avoid introducing cleanup obligations.

## Verification

Findings are based on source inspection. No compiler or runtime checks were run;
implementation verification is pending.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

Derived equality requires parameterized protocols; unions and general polymorphism
are not prerequisites for this scope. `FromInt` is deferred. Next action: settle backing
integers, member numbering, and enum-to-integer conversion in the linked spec before planning.
