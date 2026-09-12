# Working Memory

Read [task-tracking-rules.md](task-tracking-rules.md) before changing task state.
This is the Author's active and near-term focus. The backlog lives in [tasks/](tasks/).

## Global Working Memory

- Record QA findings in [QA misses](tasks/qa-misses.md). Select individual items or related
  groups for cleanup; create dedicated tasks when they need their own plans.



## Active Tasks

### Lambda migration

- **Tracker:** [Unify lambdas](tasks/unify-lambdas.md)
- **Status:** in_progress
- **Current focus:** On `dev-lambda-unify`, [Binding identity and local construction](tasks/unify-lambdas.md#establish-binding-identity-and-local-construction-invariants),
  selected before further ownership changes. The nested consuming-PAP review follow-up is
  complete; verification and signoff are recorded in task working memory.

### Conditional ownership hardening

- **Tracker:** [Make conditional ownership explicit in ownership operations](tasks/conditional-ownership-witnesses.md)
- **Status:** planned
- **Current focus:** Planned within Unify lambdas, together with its mixed-ownership
  consuming-transfer bug repair. Retain Boolean witnesses and make cleanup, consumption,
  and return operations consistently respect conditional ownership.

### Eliminate unnecessary AST field decomposition

- **Tracker:** [Eliminate unnecessary AST field decomposition](tasks/preserve-ast-nodes-in-helper-apis.md)
- **Status:** planned
- **Current focus:** Find and remove unnecessary node unpacking and reassembly throughout
  compiler helper APIs, starting with OwnershipAnalyzer.
