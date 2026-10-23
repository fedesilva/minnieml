# Canonicalize transparent aliases in TBAA metadata

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** CORRECTNESS
- **Priority:** HIGH
- **Created:** 2026-10-22
- **Target Branch:** unassigned
- **External Reference (optional):** none

## Execution Checklist

1. [ ] **planned** — Reproduce alias metadata divergence and approve a bounded repair plan.
2. [ ] **planned** — Canonicalize TBAA alias identity and correct the regression expectations.
3. [ ] **planned** — Verify mixed-alias access behavior, compiler gates, and independent review.

## Tags

- TBAA
- improvements

## Problem

Type-based alias analysis (TBAA) metadata gives `Int` and `Int32` separate scalar nodes
under the same root, although `Int` is a transparent alias of `Int32`. Accesses to the
same storage through an alias and its underlying type can therefore receive incompatible
aliasing metadata. This is a potential optimized-code correctness issue; a runtime
miscompilation has not been reproduced.

[TbaaEmitter.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/tbaa/TbaaEmitter.scala)
uses nominal names for scalar access tags and struct-field metadata.
[NominalTypeNameResolver.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/NominalTypeNameResolver.scala)
returns a `TypeRef` name without resolving aliases, and `CodeGenState.getTbaaScalar` in
[package.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/package.scala)
keys scalar nodes by that name.

The test `TBAA preserves alias identity for aliased struct fields` in
[TbaaEmissionTest.scala](../../modules/mmlc-lib/src/test/scala/mml/mmlclib/codegen/TbaaEmissionTest.scala)
explicitly requires a separate `MyInt` node for `type MyInt = Int32`, preserving the
undesired distinction.

## Outcome

Transparent aliases and their resolved underlying type share the same TBAA scalar node
across scalar accesses, struct fields, and closure environments. TBAA identity follows
resolved type identity rather than alias spelling.

## Scope

- Resolve transparent aliases consistently at TBAA metadata construction boundaries.
- Replace the distinct-alias expectation with shared-node checks, including alias chains.
- Cover mixed alias/underlying-type accesses to the same storage under optimization.
- Preserve legitimate nominal distinctions; equal LLVM layouts alone do not identify types.
- Keep unrelated heap/clone/free query consolidation and alias-scope metadata outside this repair.

## Plan (Approval Gate)

- [ ] Trace scalar and field-tag producers, including `String.length` and closure captures.
- [ ] Define canonical identity handling and a mixed-alias access regression.
- [ ] Approve the bounded implementation and verification plan.

Approval: implementation pending.

## Verification

Observed on 2026-10-22: compiling the versioned
[function/lambda sample](../../mml/samples/function-lambda-combinations.mml) with
`mmlc -aI mml/samples/function-lambda-combinations.mml` succeeds and emits these distinct
scalar nodes in the unoptimized LLVM IR:

```llvm
!1 = !{!"Int32", !0, i64 0}
!11 = !{!"Int", !0, i64 0}
```

Metadata numbering is incidental. The sample establishes the emitted distinction, not
incorrect runtime behavior. A `String.length` access tagged as `Int32` and an `Int`-typed
access to the same storage are a candidate regression scenario requiring investigation.

Required verification includes shared-node identity for direct aliases and alias chains,
consistent scalar/field/capture tags, optimized mixed-alias access behavior, controls for
distinct nominal declarations, and the applicable compiler gates and independent review.

## Risks / Notes

[Unify type-property alias resolution](type-property-alias-resolution.md) concerns
heap/clone/free queries; [Unify alias resolution](unify-alias-resolution.md) concerns
shared resolution helpers. Neither records this TBAA correctness requirement.
Avoid broadening the repair into either consolidation effort without an approved plan.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The separate alias nodes and the regression that requires them are confirmed by inspection.
Next: reproduce the metadata with the in-development compiler, establish a mixed-alias
access regression, and prepare the bounded repair plan. Runtime impact remains unverified.
