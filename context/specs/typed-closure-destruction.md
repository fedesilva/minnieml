# Typed intrinsics for closure destruction

Approved implementation plan, 2026-09-09. Source: Author-provided fresh-context plan.

Make closure cleanup explicit and type-correct in the AST. Codegen compiles destruction
operations instead of supplementing or replacing ordinary function bodies. MML syntax and
ownership behavior remain unchanged; no new implicit clones.

## Operations

- `DestroyClosure`: typed function operand and resolved destructor target; evaluate once,
  extract environment, invoke target, consume ownership; result Unit.
- `DestroyClosureEnvironment`: pointer operand, resolved layout ID, ordered field cleanup;
  destroy owned fields in layout order, then free storage exactly once; result Unit.
- `DispatchClosureDestructor`: pointer operand; null is a no-op, otherwise load and invoke
  stored destructor; no separate storage free; result Unit.

Known environments retain specific helpers; unknown environments use the universal helper.
Both helper signatures are RawPtr -> Unit. Field and destructor references use stable IDs.
Native and struct fields use registered destructors; owned function captures use closure
cleanup. Scalars and borrowed resources have no cleanup action.

## Implementation

Register layouts and signatures before constructing bodies. Register the universal helper
once per module, independent of local move closures. Reuse Module.resolvables. Ownership
bookkeeping carries resolved destructor targets and preserves witnesses, transfers, and placement.
Build complete typed intrinsic bodies. Preserve generated ordinary struct field calls; do not
import wireStructClosureFrees or regenerate checked struct functions.

Validate after the final index rebuild: operand/result types, target signatures, layout/field
identities, unique cleanup entries; accumulate compiler errors. Update traversal, indexing,
printing, and editor support. Lower through expression codegen with existing closure ABI.
Remove destructorKind, hidden body overrides, ordinary-call argument repair, and DataDestructor.
Destruction is an effect: use ordinary recursion if loop conversion would move cleanup across
a recursive call.

## Acceptance and verification

Cover AST contracts and malformed operands, missing layouts/targets, incompatible signatures,
and duplicate fields. Cover local/returned move closures, consuming function parameters,
aliases, conditional cleanup, borrowed closures and resource-free functions. Cover scalar,
string, struct, and owning-function captures, exact-once cleanup, storage-last ordering,
specific/universal dispatch, operand evaluation, and recursive cleanup order.
Update semantic helpers to inspect intrinsic operands by identity. Preserve migrated behavioral
assertions and applicable codegen helper-call assertions. Run formatting, lint, full tests,
required smokes, local publishing, benchmark builds, memory harness, and QA.

Baseline: 437 passing tests, 58 ignored; memory 27/33. Compare documented native failures,
fix introduced regressions, explicitly report outstanding baseline failures. PAP ownership,
function-bearing structs, allocation strategy, global/literal ownership, and broader tail-call
redesign are separate workstreams.

## Handoff

Update context/lambda-test-migration.md at each meaningful checkpoint with changes, verification,
approvals, failures, and next action. Finish with reviewed diff and verification report for
workstream signoff. Tracked-item completion and commit/push approval remain separate.
