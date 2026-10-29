# Extend LSP navigation for protocols

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** feature
- **Priority:** MID
- **Created:** 2026-10-27
- **Target Branch:** unassigned

## Execution Checklist

1. [ ] **planned** — Trace existing navigation and approve a bounded implementation plan.
2. [ ] **planned** — Route protocol-member definitions to signatures and add implementation navigation.
3. [ ] **planned** — Verify instance-specific references and preserve ordinary navigation.
4. [ ] **planned** — Add readable navigation regressions and verify the LSP behavior.

## Problem

Protocol functions and operators need navigation between their use, their required signature,
and their concrete implementations. Selecting a concrete instance during type checking must
not make the protocol declaration disappear from the editor's navigation path.

## Outcome

| Starting point | Editor action | Destination |
| --- | --- | --- |
| A reference to a protocol function or operator | Go to Definition | The matching signature inside its protocol. |
| A protocol declaration | Go to Implementation | Its concrete instance declarations. |
| A signature inside a protocol | Go to Implementation | The corresponding member in each instance. |
| A concrete instance declaration | Find References | Uses selecting members of that instance. |
| A concrete instance member | Find References | Uses selecting that implementation. |

For example, a call to `describe` implemented by `Describe String` first leads to the `describe`
signature in `Describe`. From that signature, implementation navigation offers the String and
Int implementations. Finding references from the String implementation returns its uses without
including calls selecting the Int implementation. Operators follow the same rules.

## Scope

- Deliver after [Protocols](protocols.md).
- Support named functions and operators through the same navigation rules.
- Reuse protocol, signature, and instance binding identities; do not infer associations from
  generated names or add another AST traversal mechanism for each navigation direction.
- Preserve source ranges through instance lowering so jumps target written declarations.
- Preserve ordinary function/type navigation and signature-wide reference search.
- Keep compiler resolution and instance selection semantics unchanged.

## Plan (Approval Gate)

Trace the existing definition and reference lookups, identify the shared association queries,
and propose the smallest changes needed for all three navigation directions. Include the
implementation request handler and advertised server capability.

Approval: navigation behavior and placement after Protocols are defined; implementation plan pending.

## Verification

Source inspection finds definition and references support in
[LspHandler.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/LspHandler.scala),
but no implementation request handler or advertised implementation capability.
[AstLookup.scala](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/lsp/AstLookup.scala)
already uses registry associations for signature-wide references and concrete-member references.

Add literal fixtures with multiple instances for both a named function and an operator. Assert
exact destination locations, all matching implementations, and exclusion of other instances'
references. Cover capability advertisement, request dispatch, and ordinary-navigation controls.
Check both whole-instance and individual-member navigation.
Implementation and execution verification are pending.

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: granted for the task definition only.

## Task Working Memory

Scheduled after Protocols. Next: inspect the existing navigation queries and prepare the bounded
implementation plan. Keep the three directions distinct: use to signature, protocol/signature
to implementations, and concrete instance or member to its references.
