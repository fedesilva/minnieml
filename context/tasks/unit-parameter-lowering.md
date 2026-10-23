# Unify Unit parameter lowering

## Metadata

- **Owner:** The Author
- **Status:** planned
- **Kind:** BUG
- **Priority:** HIGH
- **Created:** 2026-10-23
- **Target Branch:** unassigned
- **External Reference:** none

## Execution Checklist

1. [ ] **planned** — Trace Unit bindings across function emission and approve a unified repair.
2. [ ] **planned** — Preserve lexical Unit bindings while omitting runtime operands; add regressions.
3. [ ] **planned** — Run compiler gates and independent review; obtain signoff.

## Problem

Passing a function with an explicit Unit parameter as a value creates a forwarding lambda.
Regular lambda emission removes Unit parameters from both the LLVM signature and the local
scope. Its body still references the generated parameter. The missing local falls through
to global loading, producing invalid LLVM such as `load void, ptr @$p0`.

## Outcome

Unit parameters remain valid lexical bindings in every function-emission path while occupying
no runtime operand. Unit argument expressions retain their effects and evaluation order.
A missing local binding cannot silently become a global reference.

## Scope

- Ordinary functions, non-capturing and capturing closures, generated forwarding lambdas,
  and tail-recursive functions.
- Shared parameter lowering and correct local/global reference classification.
- Direct calls, function values, explicit Unit parameters, nullary controls, and effectful
  Unit arguments in semantic, LLVM-validation, and native-execution regressions.
- Out of scope: returned-callee ownership classification and other callable-type repairs.

## Plan (Approval Gate)

- [ ] Map existing parameter filtering and scope construction, including the tail-recursive
  emitter's Unit bindings; define one consistent rule.
- [ ] Implement the approved repair and regression matrix without skipping Unit effects.
- [ ] Run required compiler gates and independent review.

Approval: task creation and next-task priority granted; implementation plan pending.

## Verification

On 2026-10-23, the following fixture was compiled with `sbtn "run <fixture>.mml
-b <output-dir> -O0"` using the callable-type repair working tree based on `d4566a1`.
Compilation reached `llvm-as`, which rejected the generated wrapper:

```llvm
%1 = load void, ptr @$p0
%2 = call { ptr, ptr } @owned_factory()
```

Diagnostic: `void type only allowed for function results`.
Host: arm64 macOS, Homebrew Clang 23.1.1. No baseline execution or fix verification claimed.

```mml
type Callback = Int -> Int -> Int;

fn add3(a: Int, b: Int): Int = a + b;;

fn apply(factory: Unit -> Callback): Int = let q = (factory ()) 1; q 2;;

fn apply_inline(factory: Unit -> Callback): Int = (factory ()) 1 2;;

fn owned_factory(u: Unit): Callback =
  let text = int_to_str 12345;
  ~{ a: Int, b: Int -> text.length + a + b; };
;

fn static_factory(u: Unit): Callback = add3;;

pub fn main(): Unit =
  println (int_to_str (apply owned_factory));
  println (int_to_str (apply owned_factory));
  ();
;
```

## Risks / Notes

Unit has no runtime payload, but Unit expressions can have effects. Dropping their evaluation
would violate the language's evaluation order. Tail-recursive emission already keeps Unit
bindings in its body scope and provides a reference for the intended invariant.

Selected after [Unify and simplify callable-type handling](typecheck-grouped-callable-results.md).

## Signoff

- Workstream signoff: pending.
- Tracked item completion: pending.
- Commit authorization: not granted.

## Task Working Memory

The invalid LLVM is reproduced. `ExpressionRewriter.etaExpand` creates the forwarding
parameter; `compileRegularLambdaLiteral` filters it out, and `compileNonCapturingLambda`
builds its scope only from runtime parameters. Reference emission then uses the global
fallback. Tail-recursive emission retains Unit bindings separately.

Next: inspect all affected emission paths and approve a bounded unification plan.
