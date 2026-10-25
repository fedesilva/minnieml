# By-name arguments: short-circuiting and/or

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Created:** 2026-09-10
- **Target Branch:** codex/by-name-arguments
- **Parent Branch:** dev-2026-03-21-lambdas (Git Town parent verified)
- **External Reference (optional):** none

## Execution Checklist

1. [ ] **ready_for_signoff** — Review the implementation plan and remaining design details below.
   - Next action: approve the first implementation stage and its proposed boundaries.
2. [ ] **planned** — Deliver short-circuiting `and`/`or` through general by-name parameters.
3. [ ] **planned** — Verify callable propagation, partial application, and ownership boundaries.
4. [ ] **planned** — Complete documentation, compiler verification, review, and task signoff.

## Primary outcome: short-circuiting and/or

Boolean `and` and `or` must control evaluation of their right operand. Their eager LLVM
implementations in [prelude.mml](../../modules/mmlc-lib/src/main/resources/prelude.mml)
evaluate both operands. Replace them with ordinary conditional operator bodies whose
right parameter is by-name:

- `and`: evaluate the left operand once; evaluate the right operand only when the left is true.
- `or`: evaluate the left operand once; evaluate the right operand only when the left is false.
- Preserve precedence (`and`: 40, `or`: 30), left associativity, and Boolean results.
- Use the general by-name mechanism; do not introduce operator-specific backend behavior.

This is the first product acceptance case. The same mechanism can support `while`, `for`,
and other control combinators. Adding a library of those combinators is outside the first
implementation; a small `while` fixture can demonstrate repeated predicate/body evaluation.

## Settled design

### Evaluation mode, independent of type

Use an enum, with no new by-name AST node:

```scala
enum Evaluation:
  case Eager, ByName
```

Add an evaluation field to the existing `FnParam`, defaulting to `Eager`. The source marker
`=>` before the parameter name selects `ByName`, as in `=> pred: Bool` or `=> body: Unit`.
This is parameter syntax, not a `TypeByName` node or a separate `ByNameFnParam` node.

Evaluation mode is independent of `typeAsc` and `typeSpec`. The annotation may be absent
where inference is supported; the mode remains known while the result type is unknown.
Full inference for currently annotation-required declarations is outside this task.

Callable types must retain each parameter's evaluation mode through aliases, higher-order
calls, and partial applications until elaboration. A declaration-only flag is insufficient:
a call through a function value must recover the same contract. The concrete extension to
`TypeFn` is an implementation-plan detail to review before coding.

### Argument adaptation and parameter use

For a by-name parameter whose source result type is `T`:

- An argument expression of type `T` is wrapped in an ordinary nullary lambda.
- An argument already supplying a matching `Unit -> T` thunk passes through unchanged.
- Each reference to the parameter produces a `T` by invoking the underlying thunk with `()`.
- Zero uses skip the thunk body; repeated uses reevaluate it. There is no memoization.
- Wrapping defers the whole expression, including conditional tests and effects inside it.
- Deferring a bound value reads that value; it does not rerun the binding's initializer.

A function-valued result follows the same rule. For `=> f: Int -> Int`, an `Int -> Int`
argument is wrapped, while a `Unit -> (Int -> Int)` argument can supply the thunk directly.
A source application of `f` first obtains the function by invoking the outer thunk.

Producing an existing thunk is distinct from invoking it. If an argument expression returns
`Unit -> T`, passing it through evaluates that producer at the ordinary argument-evaluation
point; only the returned thunk's body is deferred. An explicit lambda value follows ordinary
capture and construction rules even if its body is never invoked.

The expected type must guide adaptation. An unresolved type must not cause an arbitrary
choice between a function-valued result and a supplied thunk. The plan must define a stable
checking rule and diagnostics for unresolved cases.

General expression-to-thunk coercion wherever an ordinary `Unit -> T` is expected remains
an exploration, not a prerequisite or an approved scope expansion. By-name adaptation and
automatic invocation are governed by the parameter's evaluation mode.

### Lowering to ordinary lambdas

Place by-name elaboration immediately after type checking and before partial-application
elaboration:

```text
Type checking
  -> By-name elaboration
  -> Partial-application elaboration
  -> Closure memory generation
  -> Ownership analysis
```

Type checking preserves the evaluation mode, checks parameter uses as `T`, and establishes
whether each argument supplies a result expression or a matching thunk. Elaboration then:

1. Lowers each by-name parameter to an eager parameter of type `Unit -> T`.
2. Wraps deferred result expressions in ordinary lambdas; retains matching supplied thunks.
3. Rewrites references to by-name parameters into applications to `()` by resolved identity.
4. Updates callable types and affected reference types consistently, assigns generated IDs,
   and refreshes captures and the resolved-symbol index.

The output must be fully typed ordinary lambda/application AST. No by-name evaluation mode
may remain at the downstream executable boundary. Preserve source origins for diagnostics.

Elaboration must precede partial application so partial applications retain thunk values
instead of eagerly evaluating their bodies. The existing
[semantic pipeline](../../modules/mmlc-lib/src/main/scala/mml/mmlclib/compiler/SemanticStage.scala)
performs initial capture analysis before type checking; generated lambdas therefore need
capture analysis after elaboration as well.

### Ownership and lifetime

Generated lambdas borrow captures by default and follow ordinary capture, escape, consuming
capture, return-ownership, and destruction rules. There is no separate by-name ownership
system and no implicit move inserted to make an invalid capture legal. Explicit thunks can
express capture intent using existing lambda syntax.

Fresh heap results are produced per invocation. A borrowed heap value does not become an
owned return merely because its reference is deferred. Keep existing borrowed-return
restrictions for this implementation; broader borrowed-return support is outside scope.
Consuming captures must obey existing callable consumption rules, including rejection of
invalid repeated consumption.

See the [lambda reference](../../docs/language-reference.md#lambda-expressions) and
[memory model](../../docs/memory-model.md#closures).

## Implementation plan (approval pending)

### Stage 1 — General by-name mechanism and short-circuiting operators

Deliver the smallest coherent end-to-end implementation, with `and`/`or` as the first
runtime acceptance tests. Before edits, resolve these representation details within the plan:

- Extend callable parameter representation to retain evaluation mode without a new by-name
  AST node. `TypeFn.paramTypes` currently holds bare types; specify how modes stay aligned
  with parameters through construction, comparison, canonicalization, aliases, and inference.
- Specify source notation for by-name parameters in callable type annotations, so an explicit
  higher-order signature can express the contract. Ordinary eager arrow types stay eager.
- Define checking precedence for an already matching thunk versus a result expression,
  including nested function results, contextual lambda inference, and unknown result types.
- Define or reject combinations of `=>` with consuming parameter syntax and foreign/native
  declarations. Do not silently map an unsupported contract to a foreign ABI.

Implementation steps:

1. Add `Evaluation` and the defaulted `FnParam` field. Parse `=> name: T` consistently for
   functions, operators, and lambda parameters. Preserve the marker in relevant printers and
   diagnostics. Add parser coverage for valid markers and malformed declarations.
2. Carry evaluation modes in callable types and all relevant type transformations. Check
   by-name references as their result type; check arguments against the result/thunk contract.
   Preserve eager behavior and accumulate useful type errors using existing test helpers.
3. Add the post-type-checking elaboration pass. Rewrite by resolved identity, including nested
   references and function-valued callees; preserve shadowing and source origins. Rebuild
   generated captures, IDs, types, and indexes before partial-application processing.
4. Replace the prelude's eager Boolean templates with ordinary conditional definitions using
   a by-name right operand. Keep operator precedence and associativity unchanged.
5. Establish skipped, required, and exactly-once right-operand execution with observable effects
   at runtime, including nested/mixed `and`/`or` expressions and explicit thunk arguments.

Stop for review and signoff before advancing to Stage 2. A partial Stage 1 artifact must not
be described as a completed compiler handoff; a compiler handoff runs the required gates.

### Stage 2 — Callable and ownership integration

1. Test local functions, lambdas, aliases, higher-order calls, and forwarding a by-name
   parameter into another by-name parameter without forcing it prematurely.
2. Test partial application: supplied eager arguments execute at creation, while deferred
   bodies execute only on use. Check residual callable modes and escaping capture lifetimes.
3. Test unused parameters, repeated uses, mixed eager/by-name argument order, function-valued
   results, matching supplied thunks, and thunk-producing argument expressions.
4. Add a bounded recursive `while` fixture to verify repeated predicate/body evaluation and
   accepting an expression predicate alongside an explicit body lambda.
5. Test fresh heap results, borrowed-return rejection, explicit move thunks, escaping borrows,
   and invalid repeated consumption. Run applicable fixtures with sanitizer coverage.
6. Validate that no unlowered by-name contract reaches closure-memory generation or codegen,
   while invalid source programs retain useful diagnostics rather than causing phase crashes.

Stop for review and signoff before advancing to Stage 3. These integration cases are required
for feature completion even though short-circuiting is the first acceptance milestone.

### Stage 3 — Documentation and full verification

Update the language reference and compiler design with the syntax, evaluation rules,
producer-versus-body timing, phase contract, and ordinary ownership limitations. Update
editor grammars/highlighting where the new parameter syntax requires it.

Run the compiler gates in [coding-rules.md](../coding-rules.md) and
[dev-tools.md](../dev-tools.md): formatting/linting, smoke checks, full tests, verified local
compiler publishing, benchmarks, and the full memory harness. Run sequential `sbtn` sessions.
If implementation changes an ABI, also run the required Linux ARM64 and AMD64 container
verification. Use local post-chores for QA enforcement and independent code review at handoff.
Record actual commands, outcomes, and unmet gates in this task; do not substitute the plan
for evidence. Obtain feature signoff before closure; commit and push remain separate actions.

## Scope boundaries

In scope: by-name parameter syntax and evaluation mode, callable propagation, type checking,
lambda elaboration, short-circuiting Boolean operators, integration tests, and documentation.

Out of scope: implicit memoization, a future effect system, full type inference, broader
borrowed-return support, general automatic invocation of ordinary function values, general
expression-to-thunk coercion outside by-name arguments, and a control-combinator library.

## Verification

This record defines proposed compiler work. Compiler/runtime verification is pending.
Document validation passed: tracking consistency review, local file links, and
`git diff --check`. No compiler checks apply to this document-only update.

## Signoff

- Design decisions: evaluation enum on existing parameters, type-independent mode, argument
  adaptation, implicit invocation, and post-type-checking lambda elaboration are agreed.
- Implementation plan: pending approval, including the Stage 1 representation details.
- Feature signoff and tracked item completion: pending.
- Document update: signed off for local commit and squash delivery to
  `dev-2026-03-21-lambdas`. Feature implementation remains pending approval; no push authorized.
- Historical evidence: the task-definition-only errand received signoff and local commit
  authorization on 2026-09-10; that authorization does not cover feature implementation.

## Task Working Memory

Primary acceptance: short-circuiting `and`/`or` using the general by-name mechanism.
Working branch: `codex/by-name-arguments`, parent `dev-2026-03-21-lambdas`.
The frontend retains `Evaluation.ByName` independently of optional parameter types and
preserves it in callable contracts. Post-type-checking elaboration removes the distinction
before partial application, closure memory generation, and ownership analysis.

Next action: review the Stage 1 representation and checking details and approve a bounded
implementation stage. Compiler implementation, verification, and feature signoff are pending.
