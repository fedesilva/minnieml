# Lambda unification rescue

## Summary

The branch has unified much of the syntax and representation, but it has not established one semantic contract for
functions. Several later passes still recognize particular AST shapes, recover information that earlier passes dropped,
and supply behavior that the AST does not express.

The main architectural problems are:

- Generated destructor bodies are overridden or supplemented by codegen.

- Already-processed declarations are regenerated to work around phase ordering.

- Tail-recursion lowering dismantles ownership wrappers and reconstructs their meaning.

- Immediate application, named functions, and function values still receive different semantic treatment.

- Partial application has two implementations with observably different evaluation behavior.

- Ownership depends on representation decisions and information carried outside the AST.

I would retain the Bnd/Lambda foundation, capture references, explicit move semantics, and direct/fat-pointer lowering.
The rescue should make those components authoritative and consistent. Adding more special cases around the current paths
will deepen the problem.

This review covers dev-lambdas-unify at c16231a. No repository source or documentation was changed.

## Findings

### 1. Generated destructor ASTs do not describe their emitted behavior.

This is the clearest example of the shortcut you described.

modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ClosureMemoryFnGenerator.scala:193 constructs environment
destructors whose ordinary lambda body says mml_free_raw(p). The universal closure destructor is generated with
essentially the same body.

modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/FunctionEmitter.scala:347 then interprets destructorKind:

- For an environment destructor, it inserts additional field destruction before compiling the body.

- For the universal destructor, it ignores the lambda body and emits a different implementation.

The AST therefore presents an ordinary function while metadata secretly controls its real program. Inspecting,
transforming, or testing that body cannot establish what the compiler will emit.

Refactor direction: generate the actual destruction operations in the AST. Where an operation requires an intrinsic,
represent that honestly at the intrinsic boundary. Do not manufacture an ordinary body and replace its meaning
elsewhere.

Moving destructorKind from BindingMeta to environment metadata, as S10 proposes, would change its location without
resolving this problem.

### 2. The latest struct change regenerates processed declarations instead of resolving the phase dependency.

modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/MemoryFunctionGenerator.scala:447 explicitly describes the
workaround: struct destructors are generated before __free_closure exists, then regenerated after the closure generator
runs.

wireStructClosureFrees builds fresh destructor bindings and replaces the existing ones by name. By this point, the
originals have already passed through reference resolution, expression rewriting, materialization analysis, and
typechecking. The replacements do not undergo those phases again.

There is a concrete contract mismatch in the replacement AST: a generated closure-free reference has type RawPtr ->
Unit, but its argument is still a function value.
modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Applications.scala:884 repairs this by extracting
the environment pointer during call emission.

This combines two shortcuts: replace previously processed AST nodes, then rely on codegen to reconcile their types.

Refactor direction: make the required callable declarations available before generating their users, or separate
declaration registration from body generation. Generated fragments must satisfy the same typed-AST contract as source
fragments. That does not necessarily require rerunning the entire frontend, but it does require preserving or validating
its invariants.

### 3. Tail-recursion lowering reconstructs a second interpretation of the body.

modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/FunctionEmitter.scala:493 introduces TailRecBody and
BoundStatement. A scoped application becomes approximately:

```scala
BoundStatement(bindingName: Option[String], expr: Expr)
```

Its extraction logic (modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/FunctionEmitter.scala:1138) keeps the
binding name and argument, then descends into the lambda body. The original binder identity, parameter metadata, and
lambda application structure are no longer represented in that statement.

More significantly, extractSelfCallFromAccumulated (modules/mmlclib/codegen/emitter/FunctionEmitter.scala:1197)
recognizes the ownership-generated pattern:

```text
bind result = recursive_call(arguments)
cleanup
return result
```

It removes the result-binding structure and moves the later statements into the pre-call statement list for the loop
back edge.

A backend control-flow plan is reasonable. The problem is that this one recovers ownership meaning through names and
structural recognition, then reschedules operations. It also has its own substantial borrow-closure validator.

Refactor direction: preserve resolved binder identity and explicit effect ordering through loopification. Tail-call
eligibility must account for ownership cleanup. If the required transformation cannot be justified, ordinary recursion
is the appropriate fallback; the optimization should not impose a separate closure semantics.

### 4. Some lowering actually drops expressions, not just their representation.

In compileTailRecBody (modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/FunctionEmitter.scala:916),
recursive-call arguments are filtered by non-void parameter indices before they are compiled:

```scala
filteredArgs = nonVoidIndices.map(args(_))
```

A Unit argument may contain effects. Its absence from the LLVM signature does not make its expression dispensable.

Ordinary compileArgs (modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Applications.scala:903)
correctly evaluates the expression first and then omits its void result. The two paths disagree about this fundamental
distinction.

Related bookkeeping is also inconsistent: Direct partial-application detection compares source argument counts against
non-void parameter counts, while PAP field construction zips filtered arguments with the original parameter list.

Refactor direction: retain a mapping from each source parameter and argument to its lowered operands. Evaluate every
required argument expression in source order; omit only the LLVM operand. This mapping should also carry parameter
ownership information, rather than reconstructing it after filtering.

### 5. Immediate application is still a separate semantic category in practice.

modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/CaptureAnalyzer.scala:56 treats an immediate App(Lambda, arg) as
let-desugaring. It visits the body but does not populate that lambda’s captures. Value-position lambdas take a different
path.

modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala:1732 makes the same structural distinction:
every immediate lambda application goes through analyzeLetBinding, rather than the normal lambda/application rules.

There is a deeper mismatch underneath this. The parser’s scoped-binding lowering
(modules/mmlc-lib/src/main/scala/mml/mmlclib/parser/expressions.scala:75) creates a parameter with the default consuming
= false. Ownership nevertheless interprets rebinding an owned value through this shape as a move.

I confirmed the resulting difference:

```mml
// With show declared as an ordinary borrowing function:
show s;
println s;
```

is accepted, while:

```mml
{ x: String -> println x; } s;
println s;
```

is rejected as use-after-move.

The immediate application has acquired let semantics from its shape.

Refactor direction: first resolve how ownership-taking binding is expressed in the lowered lambda application. Then
apply ordinary parameter-transfer rules. Introducing ScopeLambda versus CallableLambda would encode the existing
workaround rather than unify it.

### 6. Even the typed lambda contract differs between named declarations and values.

modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala:394 checks a Bnd with metadata through a special
member path. That path computes the binding’s function type without assigning it to the contained lambda.

The value-lambda path assigns Lambda.typeSpec.

A frontend probe confirmed:

| Form                            | Binding typed | Contained lambda typed |
| ------------------------------- | ------------- | ---------------------- |
| `fn top(x: Int): Int = ...`     | Yes           | No                     |
| `let value = { x: Int -> ... }` | Yes           | Yes                    |

Consumers consequently need to know whether to consult the binding or the lambda.

Immediate-lambda checking has another shape assumption: checkAppliedLambdaWithParam
(modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TypeChecker.scala:809) takes the first parameter and rebuilds the
lambda with params = List(typedParam). That is not a general operation over a multi-parameter lambda.

Refactor direction: establish one post-typechecking invariant for every lambda: its parameters, captures, body, and
callable type are consistently annotated. Declaration-specific annotation requirements and diagnostics can remain
policies around that common checker.

### 7. Partial application has two semantic implementations, with different evaluation behavior.

modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ExpressionRewriter.scala:57 eta-expands partial applications of
resolved Bnd callables into lambdas. The already-supplied argument expressions remain inside the generated lambda body.

Local Direct callables can instead reach compileDirectPartialApplication
(modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/expression/Applications.scala:247), which evaluates
supplied arguments immediately and stores their results in a backend-generated environment.

I compared the emitted IR for:

```mml
let p = add (next ());
(p 1) + (p 2);
```

where next prints before returning an integer.

| Declaration of `add`  | Placement of `next()` in emitted IR     |
| --------------------- | --------------------------------------- |
| Top-level function    | Inside p’s body, executed on each call  |
| Local Direct function | At PAP creation, with the result stored |

Moving the same function declaration changes when—and how often—an argument is evaluated. This is semantic divergence,
not an ABI difference.

The Direct PAP emitter also independently constructs environment destructors and changes their dispatch after
invocation. That is more ownership behavior introduced outside the semantic AST.

Refactor direction: use one partial-application elaboration that explicitly evaluates supplied arguments once, binds
their results, and constructs the remaining callable. Direct-entry and environment optimizations should lower that
common meaning.

### 8. Ownership remains a parallel collection of function-specific rules.

isOwnedLambdaValue (modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala:326) defines ownership
using !isDirect, non-empty captures, and isMove. Thus changing representation demand changes whether the analyzer
considers the lambda value owned.

Direct move captures then require a separate backend cleanup system: evaluateDirectCaptures and emitDirectCaptureFrees
(modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/ExpressionCompiler.scala:648).

Other inconsistencies follow:

- Owned function-value rebinding does not use the ordinary move path, because isMoveOnRebind recognizes named heap types
  and excludes TypeFn. A move closure aliased through let g = f becomes borrowed.

- Function ownership classification is broader than several destruction/capture queries, which still recognize only
  named heap types.

- BindingInfo.fnParams and DirectCallable.params carry copies of declaration parameters to recover consuming information
  that the function type does not contain.

- Ownership state is predominantly keyed by names, despite resolved IDs already existing.

Refactor direction: distinguish ownership obligations from physical heap allocation. A Direct move lambda still takes
responsibility for its captures even when no environment object exists. The semantic analysis should establish transfer,
borrowing, and destruction obligations once; representation determines how those obligations are implemented.

A portable callable ownership contract is justified information. It should survive aliases, parameters, returns, and
fields without recovering the original declaration through special cases.

### 9. Capture lowering is duplicated, and binding identity is lost along the way.

modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/LoweredCaptureLayout.scala:58 expands captured Direct callables
while generating environment structs.

computeCodegenCaptureLayout (modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/ExpressionCompiler.scala:510)
independently expands them again using codegen scope entries.

These are two algorithms that must agree on field order, expansion, and association with callable operands.

Meanwhile, DirectOperand (modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/package.scala:683) retains an
operand string and type names, but no source binding ID. This is the architectural cause behind the documented
duplicate-capture problem: later lowering cannot reliably identify operands that originate from the same binding.

Refactor direction: preserve semantic identity and compute one capture-lowering map at the lowering boundary. Reuse it
for signatures, call arguments, environment fields, loads, and destruction. Keep Lambda.captures as the semantic source
of truth; do not add competing capture lists to the AST.

### 10. Rewriters and phase contracts permit information loss.

Two concrete examples:

- modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/Simplifier.scala:51 reconstructs Expr with typeSpec and typeAsc
  reversed, drops group ascriptions when the unwrapped term is not an Expr, and has no lambda-body traversal.

- modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/TailRecursionDetector.scala:43 reconstructs expressions with the
  old typeSpec in the positional typeAsc slot, leaving typeSpec empty.

The Simplifier problems predate this unification work. They still matter because the branch relies on these passes
preserving one evolving AST.

Ownership also generates new lambdas after capture analysis, materialization analysis, and typechecking. Its cleanup
wrappers use result types as lambda typeSpec, with empty captures and default metadata. They work because later
consumers recognize their special shape, not because they satisfy the ordinary lambda contract.

Refactor direction: define and enforce the invariant after each phase, including for generated nodes. Prefer named copy
operations when changing children. Shared traversal helpers should preserve untouched fields and visit all relevant
children; they should not obscure each pass’s semantic rules.

## AST and metadata responsibilities

The metadata boundary needs correction, but not indiscriminate removal. Materialization and ClosureEnvAllocation are
derived classifications, so they do not themselves duplicate stored state. Their problem is that the AST model exposes
concrete allocation and layout policy, and semantic ownership relies on it.

I would use this division:

- Binding identity, types, parameters, semantic captures, explicit move intent: AST

- Callable ownership contract and derived lifetime/use facts: Semantic metadata or analysis results

- Required destruction and other executable operations: Honest AST operations or explicit intrinsics

- Direct/closure entry selection, environment layout, allocation, field offsets, LLVM operands: Backend lowering

- Source spelling, export names, operator precedence, diagnostics: Binding/source metadata

In particular, captureFieldOffset and “has destructor field” are backend layout facts. They should not help define what
a source lambda means.

There are also optimizations I would not classify as AST dishonesty. Reusing a verified forwarding closure entry is
reasonable: the current matching checks its target and forwarded parameters. Removing a wrapper after preserving its
semantics is also reasonable. And the old compileBinding helper explicitly compiles and discards output, but I found no
call sites; it is a cleanup candidate, not evidence of active behavior.

## Documentation and test coverage

The documentation and tests currently overstate the degree of unification. The specification
(context/specs/unify-lambdas.md:427) makes captures authoritative and rejects separate semantic lambda categories. The
compiler design (docs/design/compiler-design.md:590) still describes the scoped-binding/value-position distinction that
perpetuates those categories. The plan marks ordinary unique-value ownership as completed, while aliasing and
representation-dependent ownership remain inconsistent.

The 96 focused tests passed, including the six semantic equivalence tests and four codegen equivalence tests. Those are
useful regression coverage, but they do not establish the claimed architectural invariant. For example, the
capture-equivalence test includes an inline form while checking capture IDs only for the local and let-bound forms.
Effectful PAP arguments expose another missing comparison.

## Proposed rescue order

My proposed rescue order is:

1. Agree on the common semantic contract. Every lambda is typed consistently; captures retain identity; parameter
   transfer and callable ownership survive every use form. Resolve the let-versus-borrowing-application mismatch here.

2. Make generated ASTs truthful. Remove dummy destructor bodies and the late replacement workaround. Establish
   validation for generated fragments.

3. Unify partial application and ownership elaboration. Preserve evaluation order and express transfers and cleanup
   before backend representation choices.

4. Consolidate backend lowering. One parameter/argument mapping and one capture-layout mapping, both retaining semantic
   identity.

5. Rebuild loopification against those contracts. Preserve effects and cleanup ordering; eliminate its separate
   interpretation of borrowing.

6. Expand equivalence coverage around the contract. Compare declaration forms, aliases, effectful arguments, Unit
   arguments, captures, and optimization settings—not merely their preferred IR shapes.

I would start with the semantic contract and truthful generated ASTs. Those give the subsequent refactor a stable
target; otherwise each cleanup risks rebuilding the same exceptions in a different file.
