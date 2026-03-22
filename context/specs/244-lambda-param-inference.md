# Plan: #244 Bottom-up type inference for lambda parameters

## Context

The TypeChecker already infers lambda param types **top-down** from call-site `expectedType`
(added in #188 Phase 2). This covers cases like `map { a -> a + 1 } list` where `map`'s
signature tells us `a`'s type.

This plan adds the complementary **bottom-up** direction: infer param types from how they
are used in the lambda body. Example: `{ a -> a * 3 }` infers `a: Int` because `*` has
signature `(Int, Int) -> Int`.

This must be designed against the **post-rewrite AST** the TypeChecker already consumes.
Operators are not checked as dedicated binary/unary nodes at this stage; they have already
been rewritten into regular `App` chains. For example:

- `a + b` becomes `App(App(Ref("+"), a), b)`
- `-a` becomes `App(Ref("-"), a)`

Inference should therefore be expressed in terms of application-position analysis, reusing
the same function-signature machinery already used by normal application checking.

## Design Principle

**No unification, no generalization.** MML's current type system is monomorphic — every
operator and function has exactly one fixed signature. This means inference is just
signature lookup, not constraint solving.

The rule: find the param in a usage site with a known function/operator signature, read
the type off that signature. All usage sites must agree. If none found or they disagree,
error asking for a type annotation.

## Inference algorithm

### Input

A lambda with one or more params where `typeSpec`, `typeAsc`, and `expectedType` are all
absent.

### Pre-pass: infer param types from body

Before full type-checking of the body, run a lightweight AST walk:

1. **Build the untyped set**: collect param names that have no type from any source.

2. **Walk the body AST**, maintaining:
   - `inferred: Map[String, Type]` — resolved types for untyped params
   - `aliases: Map[String, String]` — let-bindings that are bare references to an
     untyped param (e.g., `let b = a` → `b` maps to `a`)

3. **At each node**, check if an untyped param (or alias) appears in an application
   position whose callee has a known monomorphic signature:
   - **Rewritten operator application** `App(App(Ref("+"), a), expr)` or
     `App(App(Ref("+"), expr), a)`: look up `+`'s signature, get the type at the matching
     argument position, record it.
   - **Rewritten unary operator application** `App(Ref("-"), a)`: look up unary `-`'s
     signature, get the parameter type, record it.
   - **Named function call** `App(Ref("someFn"), a)` or a larger curried chain containing
     `a`: look up `someFn`'s signature, get the type at that argument position, record it.
   - **Let-alias shape**: expression-level lets are desugared before this phase, so alias
     support must recognize the downstream let-binding form rather than source syntax
     literally. For the simple single-binding case equivalent to `let b = a; ...`, if the
     bound value is a bare reference to an untyped param, add `b → a` to aliases. Continue
     walking the body — `b` in later usage sites resolves through the alias to `a`.
   - **Captured variable usage**: if an application site contains both an untyped lambda
     param and a captured or outer-scope value with a known type, the callee signature may
     anchor the untyped param. Example: the rewritten form of `a + captured`.

4. **Conflict check**: if the same param gets two different inferred types from different
   usage sites, emit a dedicated lambda-inference error. The current generic
   `UnresolvableType` / `TypeMismatch` surface is too vague for this case. Target message:
   "conflicting types inferred for lambda parameter `a`: Int vs Float. Add a type
   annotation."

5. **Completeness check**: if any param in the untyped set has no inferred type, emit a
   dedicated lambda-inference error. Target message: "cannot infer type for lambda
   parameter `a`. Add a type annotation."

6. **Set `typeSpec`** on each param with the inferred type. Proceed with normal
   type-checking.

### Where this runs

In `TypeChecker.checkLambdaWithContext`, after the existing expectedType inference
(lines ~1040-1048) and before the body is checked. The existing logic already handles
the top-down case; this adds a second pass for any params still untyped after that.

Pseudocode insertion point:

```
// existing: try expectedType
val paramsWithSpecs = ... // current logic

// NEW: for any still-untyped params, run bottom-up inference
val stillUntyped = paramsWithSpecs.filter(_.typeSpec.isEmpty)
if stillUntyped.nonEmpty then
  val inferred = inferParamTypesFromBody(stillUntyped, lambda.body, paramContext)
  // merge inferred types into paramsWithSpecs
```

### Signature lookup

The pre-pass needs access to operator/function signatures. These are already available in
the module's member map (for named functions) and in the stdlib definitions (for built-in
operators). The walk doesn't need to resolve overloads — there are none.

The implementation should prefer reusing existing application/type helpers in `TypeChecker`
instead of introducing a parallel operator-specific lookup path. In particular, the walk
should operate on `Ref` / `App` shapes and use the same function-type extraction logic the
checker already relies on for normal application typing.

## Subtasks

- [ ] 1 — Implement `inferParamTypesFromBody` AST walk in TypeChecker
- [ ] 2 — Wire into `checkLambdaWithContext` after existing expectedType inference
- [ ] 3 — Dedicated lambda-inference errors for conflicts and unresolvable params
- [ ] 4 — Tests: direct op usage, captures as anchors, let-aliases, conflict errors, no-anchor errors
- [ ] 5 — Verify existing top-down inference still takes priority (no regression)

## Non-goals

- Type variables or polymorphism
- Unification or constraint solving
- Inferring types through complex expression chains beyond let-aliases
- Handling overloaded operators (not applicable — MML is monomorphic)
- Introducing a second operator-specific inference path that bypasses the normal rewritten
  application representation
