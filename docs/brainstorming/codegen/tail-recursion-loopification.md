# Spec: Explicit Tail-Recursion Loopification

## Context
Current MML codegen relies on LLVM's `TailCallElim` pass to transform recursive functions into
loops. While this successfully prevents stack overflow, the resulting control flow graph (CFG) often
lacks the canonical loop structure required by the LLVM Loop Vectorizer. Consequently, simple
reductions (e.g., summing an array) remain scalar.

## Goal
Implement a "Loopify" strategy that detects tail-recursive functions in the frontend and emits them
as **Canonical LLVM Loops** in the backend. This allows us to attach vectorization metadata
(`!llvm.loop`) and guarantees a CFG shape that the optimizer understands.

## Design

### 1. Frontend: Detection Phase
**Location:** Semantic Analysis (Post-Typechecking).
**Task:** Identify functions eligible for transformation.

**Data Types**
  - introduce a LambdaMeta ast node.
    - we will use this now and in the future to enrich the lambda node with metadata.
      

* **Criteria:**
    1.  Function is a Lambda/Method.
    2.  Contains a call to itself (`self`).
    3.  The self-call is in the **Tail Position** (the last action before return).
    4.  (Optional Initial Scope) The function has no other side-effects or complex control flow that
        complicates phi-node generation.
* **Action:** Tag the AST node  
  `Lambda.isTailRecursive = true`.

### 2. Backend: Canonical Loop Emitter
**Location:** `ExpressionCompiler.compileLambda`
**Logic:** If `isTailRecursive` is set, bypass standard emission and use the Loop Emitter.

#### The Transformation Pattern
We convert Function Parameters into Loop State (Phi Nodes).

**Input (MML):**
```scala
fn loop(i, acc) =
  if i < size then
    loop(i + 1, acc + val) // Recursive Step
  else 
    acc // Base Case
```

**Output (LLVM IR Structure):**

1.  **Entry Block:**
    * Jump immediately to `loop.header`.

2.  **Header Block (`loop.header`):**
    * Define **Phi Nodes** for every function parameter.
    * Incoming value 1: The initial argument (from `%entry`).
    * Incoming value 2: The updated argument (from `%loop.latch`).
    * *Symbol Table Update:* Remap function parameter names to point to these Phi nodes.

3.  **Body Emission:**
    * Compile the function body as normal.
    * Since the symbol table is updated, all references to variables use the current Phi state.

4.  **Handling the Recursive Call (The Latch):**
    * When emitting the `Call(self, args)` node:
        1.  Do **not** emit `call`.
        2.  Evaluate the arguments to get the "Next State" values.
        3.  Update the Phi nodes' "back edge" inputs with these values.
        4.  Emit `br label %loop.header`.
        5.  **Critical:** Attach `!llvm.loop` metadata to this branch to force vectorization.

5.  **Handling the Base Case (The Exit):**
    * When emitting the non-recursive branch:
        1.  Emit `ret result` as normal.

### 3. Metadata Injection
To force the "Kraken" (Vectorization) on simple loops, we explicitly tag the back-edge branch.

```llvm
br label %loop.header, !llvm.loop !5

!5 = distinct !{!5, !6, !7}
!6 = !{!"llvm.loop.vectorize.enable", i1 true}
!7 = !{!"llvm.loop.unroll.count", i32 4} ; Optional hint
```

## Implementation Steps

1.  **AST Annotation:** Add `isTailRecursive: Boolean` to `Lambda` AST node.
2.  **Analysis Pass:** Implement `TailRecursionDetector` in `mmlc-lib/semantic`.
    * Walk the tree.
    * Check return positions for `Apply(Ref(self), ...)`.
3.  **Codegen Strategy:** Update `ExpressionCompiler.scala`.
    * Split `compileLambda` into `emitStandard` and `emitCanonicalLoop`.
    * Implement the Phi-mapping logic.
4.  **Vectorization Test:**
    * Compile `Sieve.mml` (specifically `count_loop`) with the new emitter.
    * Verify IR contains `vector.reduce` or `<4 x i64>` instructions.

## Edge Cases & Limitations
* **Multiple Tail Calls:** If a function has multiple exit points that recurse (e.g., `if a then
  f(x+1) else f(x+2)`), the Phi nodes must handle multiple incoming edges from the loop body.
    * *Solution:* Standard LLVM SSA construction handles this, or we force a single "Latch" block
      where all recursive branches merge before jumping to header.
* **Non-Tail Recursion:** Ignored by this pass (handled by standard stack recursion).