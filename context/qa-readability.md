# Readability review: lambda unification

The readability complaint is supported by the code. The worst parts make the reader remember tuple
positions while also reasoning about ownership, evaluation order, and generated code.

This assessment covers the lambda and ownership paths reviewed for
[lambda unification rescue](specs/lambda-unify-rescue.md). It proposes readability improvements;
it does not change compiler behavior or establish new project rules.

## 1. Important concepts are anonymous tuples

`analyzeAllocatingApp` takes a six-part tuple containing an expression, source information, three
optional types, and a Boolean. Inside it, `TempInfo` and `ArgEntry` merely name more tuples.
Expressions such as `_._5`, `_._4`, and `b._1.param` require tracing back to their definitions to
understand them. These should be records with meaningful fields.

Source: [OwnershipAnalyzer.scala:1371][ownership]

## 2. Folds carry too many responsibilities

That same function analyzes arguments, creates temporary bindings, tracks errors and scope,
rebuilds calls, schedules destruction, and analyzes the generated wrappers again. The fold itself
isn't the problem: too much of the algorithm is buried inside collection operations and tuple
reconstruction. Named stages would make the sequence understandable.

Source: [OwnershipAnalyzer.scala:1371][ownership]

## 3. Named result types disappear inside the implementation

Capture lowering has a useful `CodegenCaptureLayout` type, but builds it through nested tuple
accumulators and maps whose values are further tuples. Names like `dcs`, `_._1`, and `_._2` obscure
which callable and which captured operands are being updated. The internal state deserves the same
care as the final result.

Source: [ExpressionCompiler.scala:534][capture-layout]

## 4. Comments exist, but the guidance is uneven

There are good explanations of capture layout and temporary cleanup. Elsewhere, comments describe
what happens without explaining why it is valid. Tail-recursion cleanup says it moves destruction
before the next iteration, but does not explain the conditions that make that reordering correct.
That is precisely where the reader needs help.

Source: [FunctionEmitter.scala:1181][tail-cleanup]

## 5. Some comments actively mislead

Closure collection says generated names use the enclosing binding name plus a counter; the
implementation uses only `__closure_env_$counter`. An inaccurate explanation adds another thing
the reader must verify.

Source: [ClosureMemoryFnGenerator.scala:40][closure-names]

## Refactor direction

The fix should remain functional:

- Keep folds where they express a simple accumulation.
- Use named records for meaningful multi-part state.
- Split complicated transformations into named steps.
- Write comments that explain ownership rules, ordering requirements, and assumptions, rather than
  narrating obvious operations.

These records belong inside the passes. Improving readability does not require adding more AST
metadata.

[ownership]: ../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/OwnershipAnalyzer.scala#L1371
[capture-layout]: ../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/ExpressionCompiler.scala#L534
[tail-cleanup]: ../modules/mmlc-lib/src/main/scala/mml/mmlclib/codegen/emitter/FunctionEmitter.scala#L1181
[closure-names]: ../modules/mmlc-lib/src/main/scala/mml/mmlclib/semantic/ClosureMemoryFnGenerator.scala#L40
